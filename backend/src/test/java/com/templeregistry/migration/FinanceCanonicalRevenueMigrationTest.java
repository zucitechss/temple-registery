package com.templeregistry.migration;

import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.FinServiceDim;
import jakarta.persistence.Column;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-051 and FIN-052 verified against a real MySQL database.
 *
 * <p>The invariants under test are database invariants, so they are tested against a
 * database. The grain, in particular, cannot be checked any other way: the whole point of
 * {@code uk_frf_grain} is that it holds when application code forgets to, and a test that
 * exercised application code would prove the opposite of what is needed.
 *
 * <p>No Spring context, for the same reason as
 * {@link KollurFinanceConfigurationMigrationTest}: every full-context test in this project
 * is currently red for two pre-existing reasons unrelated to finance (FIN-X-001, FIN-X-002).
 * Flyway is driven directly, so what runs here is the committed migration rather than a
 * Hibernate-generated approximation of it.
 */
@Testcontainers(disabledWithoutDocker = true)
class FinanceCanonicalRevenueMigrationTest {

    private static final Path CANONICAL_MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration", "V112__finance_canonical_revenue.sql");

    private static final long TEMPLE_A = 900001L;
    private static final long TEMPLE_B = 900002L;

    /** Columns every fact insert needs; the rest of the grain defaults to NULL. */
    private static final String BASE_COLS =
            "temple_id, source_system_id, sync_batch_id, transaction_date, financial_year, "
                    + "category_id, payment_mode";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_canonical")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .validateOnMigrate(false)
                .load()
                .migrate();
    }

    // ---------------------------------------------------------------- FIN-051

    @Nested
    @DisplayName("FIN-051 canonical dimensions")
    class Dimensions {

        @Test
        @DisplayName("The canonical taxonomy is seeded and platform-wide, not per temple")
        void should_seedCanonicalCategories_when_migrated() throws SQLException {
            assertThat(count("fin_revenue_category")).isEqualTo(12);

            assertThat(columnsOf("fin_revenue_category"))
                    .as("the taxonomy must be identical for every temple, or two temples "
                            + "cannot be compared in a district total")
                    .doesNotContain("temple_id");

            assertThat(valuesOf("SELECT category_code FROM fin_revenue_category ORDER BY display_order"))
                    .containsExactly("SEVA", "SPECIAL_SEVA", "DONATION", "HUNDI_DONATION",
                            "PRASADAM_SALE", "ENTRY_FEE", "IN_KIND_DONATION", "ASSET_REALISATION",
                            "RENT_LEASE", "HALL_BOOKING", "OTHER_INCOME", "UNMAPPED");
        }

        /**
         * An unmapped source value is revenue whose kind nobody has established yet.
         * Dropping it loses money from the total; folding it into OTHER_INCOME asserts it is
         * income of a known kind. It gets its own visible destination instead.
         */
        @Test
        @DisplayName("UNMAPPED exists and is distinct from OTHER_INCOME")
        void should_offerAnUnmappedDestination_when_noRuleCovers() throws SQLException {
            assertThat(scalar("SELECT description FROM fin_revenue_category WHERE category_code = 'UNMAPPED'"))
                    .contains("mapping rule");
            assertThat(scalar("SELECT description FROM fin_revenue_category WHERE category_code = 'OTHER_INCOME'"))
                    .contains("never a destination for values nobody has mapped");
        }

        @Test
        @DisplayName("Every category carries a description a reader can act on")
        void should_describeEveryCategory_when_seeded() throws SQLException {
            for (String description : valuesOf("SELECT description FROM fin_revenue_category")) {
                assertThat(description).isNotBlank();
                assertThat(description.length())
                        .as("category descriptions are user-facing, not placeholders: [%s]", description)
                        .isGreaterThan(40);
            }
        }

        @Test
        @DisplayName("Two temples may use the same service code without collision")
        void should_scopeServicesPerTemple_when_codesRepeat() throws SQLException {
            insertService(TEMPLE_A, "MORNING_ARCHANA", 1L);
            insertService(TEMPLE_B, "MORNING_ARCHANA", 1L);

            assertThat(count("fin_service_dim WHERE service_code = 'MORNING_ARCHANA'")).isEqualTo(2);

            assertThatThrownBy(() -> insertService(TEMPLE_A, "MORNING_ARCHANA", 1L))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_fsd_temple_service");
        }

        /** The rate card is context, never revenue, so a temple without one is representable. */
        @Test
        @DisplayName("A service may exist with no rate card")
        void should_allowNullRateCard_when_serviceHasNoListPrice() throws SQLException {
            insertService(TEMPLE_A, "NO_LIST_PRICE", 1L);
            assertThat(scalar("SELECT rate_card_amount FROM fin_service_dim "
                    + "WHERE service_code = 'NO_LIST_PRICE'")).isNull();
        }
    }

    // ---------------------------------------------------------------- FIN-052 grain

    @Nested
    @DisplayName("FIN-052 grain is enforced by the database")
    class Grain {

        @Test
        @DisplayName("The same canonical grain cannot be inserted twice")
        void should_rejectDuplicate_when_grainRepeats() throws SQLException {
            insertFact(BASE_COLS + ", service_id, counter_ref, operator_ref, gross_amount",
                    TEMPLE_A + ", 1, 1, '2025-06-15', '2025-26', 1, 'CASH', 10, 'C1', 'OP1', 1000000.00");

            assertThatThrownBy(() -> insertFact(
                    BASE_COLS + ", service_id, counter_ref, operator_ref, gross_amount",
                    TEMPLE_A + ", 1, 2, '2025-06-15', '2025-26', 1, 'CASH', 10, 'C1', 'OP1', 1000000.00"))
                    .as("a second sync of the same day must not double the reported revenue")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_frf_grain");
        }

        /**
         * The case a naive unique key gets wrong. MySQL and TiDB treat NULLs in a unique
         * index as distinct from each other, so a key over the nullable grain columns
         * themselves would accept this twice and silently double a temple's revenue.
         */
        @Test
        @DisplayName("The grain holds even when service, counter and operator are all NULL")
        void should_rejectDuplicate_when_nullableGrainColumnsAreNull() throws SQLException {
            String cols = BASE_COLS + ", gross_amount";
            String values = TEMPLE_A + ", 1, 1, '2025-07-01', '2025-26', 4, 'UNRECORDED', 500000.00";

            insertFact(cols, values);

            assertThatThrownBy(() -> insertFact(cols, values))
                    .as("hundi collections carry no service, counter or operator; the grain "
                            + "must still be one row per day and category")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_frf_grain");
        }

        @Test
        @DisplayName("Distinct dimensions on the same day are distinct facts")
        void should_allowDistinctFacts_when_dimensionsDiffer() throws SQLException {
            long before = count("fin_revenue_fact");
            String day = "'2025-08-10'";

            insertFact(BASE_COLS + ", service_id, counter_ref, operator_ref",
                    TEMPLE_A + ", 1, 1, " + day + ", '2025-26', 1, 'CASH', 10, 'C1', 'OP1'");
            insertFact(BASE_COLS + ", service_id, counter_ref, operator_ref",
                    TEMPLE_A + ", 1, 1, " + day + ", '2025-26', 2, 'CASH', 10, 'C1', 'OP1'"); // category
            insertFact(BASE_COLS + ", service_id, counter_ref, operator_ref",
                    TEMPLE_A + ", 1, 1, " + day + ", '2025-26', 1, 'CASH', 11, 'C1', 'OP1'"); // service
            insertFact(BASE_COLS + ", service_id, counter_ref, operator_ref",
                    TEMPLE_A + ", 1, 1, " + day + ", '2025-26', 1, 'UPI',  10, 'C1', 'OP1'"); // payment mode
            insertFact(BASE_COLS + ", service_id, counter_ref, operator_ref",
                    TEMPLE_A + ", 1, 1, " + day + ", '2025-26', 1, 'CASH', 10, 'C2', 'OP1'"); // counter
            insertFact(BASE_COLS + ", service_id, counter_ref, operator_ref",
                    TEMPLE_A + ", 1, 1, " + day + ", '2025-26', 1, 'CASH', 10, 'C1', 'OP2'"); // operator

            assertThat(count("fin_revenue_fact") - before)
                    .as("collapsing any of these would make a catalogued report unanswerable")
                    .isEqualTo(6);
        }

        /**
         * FIN-056 will implement this as the loader's write path. Until then the invariant
         * it depends on is proven here: the same grain written twice converges on one row.
         */
        @Test
        @DisplayName("Re-loading the same grain updates one row rather than adding a second")
        void should_convergeOnOneRow_when_upsertRepeated() throws SQLException {
            String upsert = """
                INSERT INTO fin_revenue_fact
                    (temple_id, source_system_id, sync_batch_id, transaction_date, financial_year,
                     category_id, payment_mode, service_id, counter_ref, operator_ref,
                     transaction_count, gross_amount, cancelled_amount, created_at, updated_at)
                VALUES (%d, 1, %d, '2025-09-09', '2025-26', 1, 'CASH', 21, 'C1', 'OP1',
                        %d, %s, 0.00, NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE
                    sync_batch_id     = VALUES(sync_batch_id),
                    transaction_count = VALUES(transaction_count),
                    gross_amount      = VALUES(gross_amount),
                    cancelled_amount  = VALUES(cancelled_amount),
                    updated_at        = NOW(6)
                """;

            execute(String.format(upsert, TEMPLE_A, 7, 40, "1000000.00"));
            execute(String.format(upsert, TEMPLE_A, 8, 42, "1050000.00"));

            String where = " WHERE temple_id = " + TEMPLE_A + " AND transaction_date = '2025-09-09'";
            assertThat(count("fin_revenue_fact" + where)).isEqualTo(1);
            assertThat(scalar("SELECT gross_amount FROM fin_revenue_fact" + where))
                    .as("a restated day must replace the figure, never add to it")
                    .isEqualTo("1050000.00");
            assertThat(scalar("SELECT sync_batch_id FROM fin_revenue_fact" + where))
                    .as("provenance must follow the restatement")
                    .isEqualTo("8");
        }

        @Test
        @DisplayName("Two temples may hold the identical grain independently")
        void should_isolateTemples_when_grainsMatch() throws SQLException {
            String cols = BASE_COLS + ", service_id, counter_ref, operator_ref, gross_amount";

            insertFact(cols, TEMPLE_A + ", 1, 1, '2025-05-05', '2025-26', 1, 'CASH', 30, 'C1', 'OP1', 111.00");
            insertFact(cols, TEMPLE_B + ", 2, 5, '2025-05-05', '2025-26', 1, 'CASH', 30, 'C1', 'OP1', 222.00");

            assertThat(scalar("SELECT gross_amount FROM fin_revenue_fact WHERE temple_id = " + TEMPLE_A
                    + " AND transaction_date = '2025-05-05'")).isEqualTo("111.00");
            assertThat(scalar("SELECT gross_amount FROM fin_revenue_fact WHERE temple_id = " + TEMPLE_B
                    + " AND transaction_date = '2025-05-05'")).isEqualTo("222.00");
            assertThat(scalar("SELECT COUNT(*) FROM fin_revenue_fact WHERE temple_id = " + TEMPLE_B))
                    .as("one temple's facts must never be visible in another's total")
                    .isEqualTo("1");
        }
    }


    // ---------------------------------------------------------------- FIN-052A source grain

    @Nested
    @DisplayName("FIN-052A the grain distinguishes source systems")
    class SourceSystemGrain {

        /**
         * The defect V118 closes, and it never announced itself. Before V118 the second insert
         * did not fail — an upsert accepted it as a duplicate key and <em>replaced</em> the first
         * source's money, carrying the row's source_system_id and sync_batch_id across with it.
         * Nothing anywhere recorded that the first figure had existed (limitation 47).
         */
        @Test
        @DisplayName("Two source systems may hold the identical remaining grain independently")
        void should_keepSourcesApart_when_everyOtherGrainColumnMatches() throws SQLException {
            String cols = BASE_COLS + ", service_id, counter_ref, operator_ref, gross_amount";
            String day = "'2025-10-06'";

            insertFact(cols, TEMPLE_A + ", 41, 1, " + day + ", '2025-26', 1, 'CASH', 70, 'C1', 'OP1', 111.00");
            insertFact(cols, TEMPLE_A + ", 42, 2, " + day + ", '2025-26', 1, 'CASH', 70, 'C1', 'OP1', 222.00");

            assertThat(count("fin_revenue_fact WHERE transaction_date = " + day))
                    .as("two systems genuinely reported separately; one must not replace the other")
                    .isEqualTo(2);
            assertThat(scalar("SELECT SUM(gross_amount) FROM fin_revenue_fact "
                    + "WHERE transaction_date = " + day))
                    .as("the temple's figure for the day is the sum of its sources, not one of them")
                    .isEqualTo("333.00");
        }

        /**
         * The source distinction has to survive the generated stand-ins, because a hundi
         * collection carries no service, counter or operator — which is exactly the shape
         * FIN-D-018 had to defend once already.
         */
        @Test
        @DisplayName("Sources stay apart even when service, counter and operator are all NULL")
        void should_keepSourcesApart_when_nullableGrainColumnsAreNull() throws SQLException {
            String cols = BASE_COLS + ", gross_amount";
            String day = "'2025-10-07'";

            insertFact(cols, TEMPLE_A + ", 43, 1, " + day + ", '2025-26', 4, 'UNRECORDED', 500.00");
            insertFact(cols, TEMPLE_A + ", 44, 2, " + day + ", '2025-26', 4, 'UNRECORDED', 600.00");

            assertThat(count("fin_revenue_fact WHERE transaction_date = " + day)).isEqualTo(2);

            assertThatThrownBy(() -> insertFact(cols,
                    TEMPLE_A + ", 43, 3, " + day + ", '2025-26', 4, 'UNRECORDED', 700.00"))
                    .as("widening the key must not have weakened it: within one source the grain "
                            + "is still one row per day and category")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_frf_grain");
        }

        @Test
        @DisplayName("Restating one source's figure leaves the other source's untouched")
        void should_leaveOtherSourceIntact_when_oneSourceIsRestated() throws SQLException {
            String upsert = """
                INSERT INTO fin_revenue_fact
                    (temple_id, source_system_id, sync_batch_id, transaction_date, financial_year,
                     category_id, payment_mode, service_id, counter_ref, operator_ref,
                     gross_amount, created_at, updated_at)
                VALUES (%d, %d, %d, '2025-10-08', '2025-26', 1, 'CASH', 71, 'C1', 'OP1',
                        %s, NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE
                    sync_batch_id = VALUES(sync_batch_id),
                    gross_amount  = VALUES(gross_amount),
                    updated_at    = NOW(6)
                """;

            execute(String.format(upsert, TEMPLE_A, 45, 1, "100.00"));
            execute(String.format(upsert, TEMPLE_A, 46, 2, "200.00"));
            execute(String.format(upsert, TEMPLE_A, 45, 3, "150.00"));

            String day = " WHERE transaction_date = '2025-10-08'";
            assertThat(count("fin_revenue_fact" + day))
                    .as("a restatement replaces one source's row; it does not create a third")
                    .isEqualTo(2);
            assertThat(scalar("SELECT gross_amount FROM fin_revenue_fact" + day
                    + " AND source_system_id = 45"))
                    .as("the restating source's own figure is replaced, not added to")
                    .isEqualTo("150.00");
            assertThat(scalar("SELECT gross_amount FROM fin_revenue_fact" + day
                    + " AND source_system_id = 46"))
                    .as("one source's restatement must never move another source's figure")
                    .isEqualTo("200.00");
            assertThat(scalar("SELECT sync_batch_id FROM fin_revenue_fact" + day
                    + " AND source_system_id = 46"))
                    .as("nor its provenance")
                    .isEqualTo("2");
        }

        /**
         * The constraint itself, read back from the server. Every other test here proves a
         * behaviour the index happens to produce; this one proves the index is what V118 says it
         * is, including the column order that makes {@code (temple_id, source_system_id)} a usable
         * prefix for the source-scoped reconciliation queries.
         */
        @Test
        @DisplayName("uk_frf_grain covers the eight grain columns, in order, and is still unique")
        void should_defineGrainOverEightColumns_when_v118HasRun() throws SQLException {
            assertThat(valuesOf("""
                    SELECT column_name FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = 'fin_revenue_fact'
                       AND index_name = 'uk_frf_grain'
                     ORDER BY seq_in_index
                    """))
                    .containsExactly("temple_id", "source_system_id", "transaction_date",
                            "grain_service_key", "category_id", "payment_mode",
                            "grain_counter_key", "grain_operator_key");

            assertThat(scalar("""
                    SELECT non_unique FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = 'fin_revenue_fact'
                       AND index_name = 'uk_frf_grain' AND seq_in_index = 1
                    """))
                    .as("widening the grain must not have quietly turned it into a plain index")
                    .isEqualTo("0");
        }

        /** V118 changes an index and nothing else. No column may have moved or relaxed. */
        @Test
        @DisplayName("V118 left source_system_id NOT NULL and the generated columns intact")
        void should_leaveColumnsUntouched_when_grainWasWidened() throws SQLException {
            assertThat(scalar("SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = 'fin_revenue_fact' "
                    + "AND column_name = 'source_system_id'")).isEqualTo("NO");

            assertThat(valuesOf("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = 'fin_revenue_fact' "
                    + "AND extra LIKE '%GENERATED%' ORDER BY column_name"))
                    .as("the NULL stand-ins and the derived net must all have survived")
                    .containsExactly("grain_counter_key", "grain_operator_key",
                            "grain_service_key", "net_amount");
        }
    }
    // ---------------------------------------------------------------- semantics

    @Nested
    @DisplayName("Canonical semantics")
    class Semantics {

        /**
         * A source may edit a two-year-old receipt today. That corrects an old financial
         * day; it does not move the money into today.
         */
        @Test
        @DisplayName("Business date is separate from load and restatement time")
        void should_keepBusinessDateSeparate_when_factIsRestated() throws SQLException {
            insertFact(BASE_COLS + ", service_id, gross_amount",
                    TEMPLE_A + ", 1, 1, '2023-04-11', '2023-24', 1, 'CASH', 55, 900.00");

            String where = " WHERE temple_id = " + TEMPLE_A + " AND service_id = 55";
            String loadedAt = scalar("SELECT created_at FROM fin_revenue_fact" + where);

            execute("UPDATE fin_revenue_fact SET gross_amount = 950.00, updated_at = NOW(6)" + where);

            assertThat(scalar("SELECT transaction_date FROM fin_revenue_fact" + where))
                    .as("restating an old receipt must not move its revenue into today")
                    .isEqualTo("2023-04-11");
            assertThat(scalar("SELECT financial_year FROM fin_revenue_fact" + where)).isEqualTo("2023-24");
            assertThat(scalar("SELECT updated_at FROM fin_revenue_fact" + where))
                    .isNotEqualTo(loadedAt);

            assertThat(typeOf("fin_revenue_fact", "transaction_date")).isEqualTo("date");
            assertThat(typeOf("fin_revenue_fact", "created_at")).isEqualTo("datetime");
        }

        /**
         * Three distinguishable states, and the difference between them is the difference
         * between "nothing was cancelled" and "nobody knows what was cancelled".
         */
        @Test
        @DisplayName("Cancelled, none-cancelled and not-recorded remain three different answers")
        void should_distinguishCancellationStates_when_stored() throws SQLException {
            String cols = BASE_COLS + ", service_id, gross_amount, cancelled_count, cancelled_amount";

            insertFact(cols, TEMPLE_A + ", 1, 1, '2025-01-02', '2024-25', 1, 'CASH', 61, 1000.00, 2, 150.00");
            insertFact(cols, TEMPLE_A + ", 1, 1, '2025-01-02', '2024-25', 1, 'CASH', 62, 1000.00, 0, 0.00");
            insertFact(BASE_COLS + ", service_id, gross_amount",
                    TEMPLE_A + ", 1, 1, '2025-01-02', '2024-25', 1, 'CASH', 63, 1000.00");

            assertThat(netOf(61)).as("cancelled revenue is deducted, and remains visible as its own figure")
                    .isEqualTo("850.00");
            assertThat(scalar("SELECT cancelled_amount FROM fin_revenue_fact WHERE service_id = 61"))
                    .isEqualTo("150.00");

            assertThat(netOf(62)).as("measured zero cancellations means net equals gross")
                    .isEqualTo("1000.00");

            assertThat(scalar("SELECT cancelled_amount FROM fin_revenue_fact WHERE service_id = 63"))
                    .as("a source that does not record cancellations reports unknown, not zero")
                    .isNull();
            assertThat(netOf(63))
                    .as("net revenue is unknown when cancellations are unknown; reporting gross as "
                            + "net would assert that nothing was cancelled")
                    .isNull();
        }

        @Test
        @DisplayName("Provenance is mandatory: a fact must name its source system and batch")
        void should_requireProvenance_when_factInserted() {
            assertThatThrownBy(() -> execute("""
                    INSERT INTO fin_revenue_fact
                        (temple_id, transaction_date, financial_year, category_id, payment_mode,
                         created_at, updated_at)
                    VALUES (900003, '2025-02-02', '2024-25', 1, 'CASH', NOW(6), NOW(6))
                    """))
                    .as("a published figure must always be traceable to the run that produced it")
                    .isInstanceOf(SQLException.class);
        }

        @Test
        @DisplayName("Provenance survives on the row: source system, batch and declaration version")
        void should_retainProvenance_when_stored() throws SQLException {
            insertFact(BASE_COLS + ", service_id, source_of_truth_version, source_record_ref",
                    TEMPLE_A + ", 4, 77, '2025-03-03', '2024-25', 1, 'CASH', 71, 2, 'revenue|2025-03-03|71'");

            String where = " WHERE service_id = 71";
            assertThat(scalar("SELECT source_system_id FROM fin_revenue_fact" + where)).isEqualTo("4");
            assertThat(scalar("SELECT sync_batch_id FROM fin_revenue_fact" + where)).isEqualTo("77");
            assertThat(scalar("SELECT source_of_truth_version FROM fin_revenue_fact" + where)).isEqualTo("2");
            assertThat(scalar("SELECT source_record_ref FROM fin_revenue_fact" + where))
                    .as("an opaque handle back to staging, never a query")
                    .doesNotContainIgnoringCase("select");
        }

        @Test
        @DisplayName("Payment mode carries its confidence, so inference is never read as measurement")
        void should_recordPaymentModeConfidence_when_inferred() throws SQLException {
            insertFact(BASE_COLS + ", service_id, payment_mode_confidence",
                    TEMPLE_A + ", 1, 1, '2025-04-04', '2025-26', 1, 'UNRECORDED', 81, 'INFERRED'");

            assertThat(scalar("SELECT payment_mode FROM fin_revenue_fact WHERE service_id = 81"))
                    .as("an unrecorded mode must not be stored as CASH")
                    .isEqualTo("UNRECORDED");
            assertThat(scalar("SELECT payment_mode_confidence FROM fin_revenue_fact WHERE service_id = 81"))
                    .isEqualTo("INFERRED");
        }
    }

    // ---------------------------------------------------------------- money and purity

    @Nested
    @DisplayName("Money and source purity")
    class MoneyAndPurity {

        @Test
        @DisplayName("Money is exact decimal, never floating point")
        void should_storeExactDecimals_when_amountsPersisted() throws SQLException {
            for (String table : List.of("fin_revenue_fact", "fin_service_dim", "fin_revenue_category")) {
                assertThat(valuesOf("SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = '" + table + "'"))
                        .as("%s must not store money in a type that cannot represent it", table)
                        .doesNotContain("float", "double", "real");
            }

            assertThat(typeOf("fin_revenue_fact", "gross_amount")).isEqualTo("decimal");

            insertFact(BASE_COLS + ", service_id, gross_amount, cancelled_amount, quantity",
                    TEMPLE_A + ", 1, 1, '2025-10-10', '2025-26', 1, 'CASH', 91, "
                            + "9061629360.05, 0.07, 1234.567");

            assertThat(scalar("SELECT gross_amount FROM fin_revenue_fact WHERE service_id = 91"))
                    .isEqualTo("9061629360.05");
            assertThat(netOf(91)).isEqualTo("9061629359.98");
            assertThat(scalar("SELECT quantity FROM fin_revenue_fact WHERE service_id = 91"))
                    .isEqualTo("1234.567");
        }

        /**
         * The canonical layer must work unchanged for the second temple onboarded. Source
         * vocabulary reaching it would make that false without anybody noticing, so the
         * committed DDL is scanned rather than reviewed.
         */
        @Test
        @DisplayName("No source-system vocabulary appears in the canonical schema")
        void should_containNoSourceVocabulary_when_migrationScanned() throws IOException {
            String file = Files.readString(CANONICAL_MIGRATION, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            // Comments are stripped for the vocabulary scan so that a header documenting
            // which source words are banned is not flagged for naming them. The identity
            // tokens below are checked against the whole file, comments included: a comment
            // describing one temple as if it were the platform is itself the problem.
            String ddl = file.replaceAll("(?m)--.*$", " ");

            for (String token : List.of(
                    "dailyseva", "hkanike", "hitemmaster", "sevacode", "billcancled",
                    "deleteflag", "multirecpno", "receiptno", "ssv_", "sannidhi", "finyear",
                    "password", "jdbc:", "credential", "sql server", "sqlserver")) {
                assertThat(ddl)
                        .as("[%s] belongs to one source system or to the integration boundary, "
                                + "not to the canonical model every temple shares", token)
                        .doesNotContain(token);
            }

            for (String token : List.of("kolsoham", "mookambika", "300001")) {
                assertThat(file)
                        .as("[%s] names the first onboarded temple; the canonical model must "
                                + "read the same for the second one", token)
                        .doesNotContain(token);
            }
        }

        @Test
        @DisplayName("No canonical column is named after a source field")
        void should_containNoSourceColumns_when_schemaInspected() throws SQLException {
            List<String> forbidden = List.of("sevacode", "billcancled", "deleteflag", "recpno",
                    "ssv", "sannidhi", "devotee", "person_name", "mobile", "address", "email");

            for (String table : List.of("fin_revenue_fact", "fin_service_dim", "fin_revenue_category")) {
                for (String column : columnsOf(table)) {
                    for (String token : forbidden) {
                        assertThat(column)
                                .as("%s.%s carries source vocabulary or personal data into the "
                                        + "canonical model", table, column)
                                .doesNotContain(token);
                    }
                }
            }
        }

        /**
         * The defect class behind FIN-X-001: a column mapped by an entity that no migration
         * creates. It is invisible until a schema validation runs, and this project currently
         * has no working one.
         */
        @Test
        @DisplayName("Every entity column exists in the migrated schema")
        void should_matchEntities_when_schemaCompared() throws SQLException {
            assertEntityMatchesTable(FinRevenueCategory.class, "fin_revenue_category");
            assertEntityMatchesTable(FinServiceDim.class, "fin_service_dim");
            assertEntityMatchesTable(FinRevenueFact.class, "fin_revenue_fact");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertEntityMatchesTable(Class<?> entity, String table) throws SQLException {
        List<String> columns = columnsOf(table);
        assertThat(columns).as("%s must exist", table).isNotEmpty();

        for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null || column.name().isBlank()) {
                    continue;
                }
                assertThat(columns)
                        .as("%s.%s is mapped to column [%s], which the migration does not create",
                                entity.getSimpleName(), field.getName(), column.name())
                        .contains(column.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static void insertService(long templeId, String serviceCode, long categoryId) throws SQLException {
        execute("INSERT INTO fin_service_dim "
                + "(temple_id, service_code, service_name_en, category_id, is_active, "
                + " is_deleted, created_at, updated_at, created_by, updated_by) VALUES ("
                + templeId + ", '" + serviceCode + "', 'Test service', " + categoryId
                + ", 1, 0, NOW(6), NOW(6), 0, 0)");
    }

    private static void insertFact(String columns, String values) throws SQLException {
        execute("INSERT INTO fin_revenue_fact (" + columns + ", created_at, updated_at) VALUES ("
                + values + ", NOW(6), NOW(6))");
    }

    private static String netOf(long serviceId) throws SQLException {
        return scalar("SELECT net_amount FROM fin_revenue_fact WHERE service_id = " + serviceId);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static long count(String fromClause) throws SQLException {
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + fromClause));
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> valuesOf(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static List<String> columnsOf(String table) throws SQLException {
        return valuesOf("SELECT LOWER(column_name) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "'");
    }

    private static String typeOf(String table, String column) throws SQLException {
        return scalar("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }
}

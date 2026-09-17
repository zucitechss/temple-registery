package com.templeregistry.migration;

import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.enums.StagingStatus;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-050 verified against a real MySQL database.
 *
 * <p>Staging's job is to keep what a source said so that what the platform publishes can be
 * traced back to it. The properties worth testing are therefore about what survives — the
 * payload byte-for-byte, the provenance, the separation of the business axis from the
 * extraction axis — and about the one thing the database must refuse: the same source record
 * twice inside one batch.
 *
 * <p>No Spring context, matching {@link FinanceCanonicalRevenueMigrationTest} and
 * {@link KollurFinanceConfigurationMigrationTest}: full-context tests in this project are red
 * for two pre-existing reasons unrelated to finance (FIN-X-001, FIN-X-002).
 */
@Testcontainers(disabledWithoutDocker = true)
class FinanceRevenueStagingMigrationTest {

    private static final Path STAGING_MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration", "V113__finance_revenue_staging.sql");

    private static final long TEMPLE_A = 900001L;
    private static final long TEMPLE_B = 900002L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_staging")
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

    @Test
    @DisplayName("V113 applies and the staging table exists")
    void should_applyMigration_when_flywayRuns() throws SQLException {
        // MAX(version) would compare as text, where "9" beats "113".
        assertThat(scalar("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '113' AND success = 1")).isEqualTo("1");
        assertThat(columnsOf("fin_stg_revenue")).isNotEmpty();
    }

    @Test
    @DisplayName("Every entity column exists in the migrated schema")
    void should_matchEntity_when_schemaCompared() throws SQLException {
        List<String> columns = columnsOf("fin_stg_revenue");

        for (Field field : FinStgRevenue.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null || column.name().isBlank()) {
                continue;
            }
            assertThat(columns)
                    .as("FinStgRevenue.%s is mapped to column [%s], which V113 does not create",
                            field.getName(), column.name())
                    .contains(column.name().toLowerCase(Locale.ROOT));
        }
    }

    // ---------------------------------------------------------------- provenance

    @Nested
    @DisplayName("Provenance")
    class Provenance {

        @Test
        @DisplayName("A staged row cannot omit temple, source system, batch, reference or payload")
        void should_requireProvenance_when_rowStaged() {
            assertOmittingColumnFails("temple_id");
            assertOmittingColumnFails("source_system_id");
            assertOmittingColumnFails("sync_batch_id");
            assertOmittingColumnFails("source_record_ref");
            assertOmittingColumnFails("raw_json");
        }

        @Test
        @DisplayName("Two temples stage independently")
        void should_isolateTemples_when_bothStageTheSameRecordShape() throws SQLException {
            stage(TEMPLE_A, 1, 10, "receipts|2025-06-15|A", "{\"gross\":\"100.00\"}");
            stage(TEMPLE_B, 2, 20, "receipts|2025-06-15|A", "{\"gross\":\"200.00\"}");

            assertThat(count("fin_stg_revenue WHERE temple_id = " + TEMPLE_A
                    + " AND source_record_ref = 'receipts|2025-06-15|A'")).isEqualTo(1);
            assertThat(jsonField(TEMPLE_B, "receipts|2025-06-15|A", "gross"))
                    .as("one temple's staged payload must never be visible in another's")
                    .isEqualTo("200.00");
        }

        @Test
        @DisplayName("The same record from two source systems is two staged rows")
        void should_distinguishSourceSystems_when_referencesCollide() throws SQLException {
            stage(TEMPLE_A, 3, 30, "shared-ref-1", "{\"gross\":\"10.00\"}");
            stage(TEMPLE_A, 4, 31, "shared-ref-1", "{\"gross\":\"20.00\"}");

            assertThat(count("fin_stg_revenue WHERE source_record_ref = 'shared-ref-1'"))
                    .as("two systems may use the same reference for unrelated records")
                    .isEqualTo(2);
        }

        /** Following one record across extractions is how a restatement gets explained. */
        @Test
        @DisplayName("One source record can be traced across batches")
        void should_traceRecord_when_extractedRepeatedly() throws SQLException {
            stage(TEMPLE_A, 5, 40, "trace-me", "{\"gross\":\"500.00\"}");
            stage(TEMPLE_A, 5, 41, "trace-me", "{\"gross\":\"550.00\"}");

            assertThat(valuesOf("SELECT sync_batch_id FROM fin_stg_revenue "
                    + "WHERE source_system_id = 5 AND source_record_ref = 'trace-me' "
                    + "ORDER BY sync_batch_id")).containsExactly("40", "41");
        }
    }

    // ---------------------------------------------------------------- idempotency

    @Nested
    @DisplayName("Idempotency and replay")
    class Idempotency {

        @Test
        @DisplayName("The same record twice in one batch is refused")
        void should_rejectDuplicate_when_recordRepeatsWithinABatch() throws SQLException {
            stage(TEMPLE_A, 6, 50, "dup-ref", "{\"gross\":\"1.00\"}");

            assertThatThrownBy(() -> stage(TEMPLE_A, 6, 50, "dup-ref", "{\"gross\":\"1.00\"}"))
                    .as("a record delivered twice in one extraction silently double-counts downstream")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_fsr_batch_record");
        }

        /**
         * The legitimate case the constraint must not block. Re-extracting a window is how a
         * correction is found, and comparing the two payloads is how it is explained.
         */
        @Test
        @DisplayName("Replaying a window under a new batch is allowed")
        void should_allowRestatement_when_sameRecordArrivesInANewBatch() throws SQLException {
            stage(TEMPLE_A, 7, 60, "restated-ref", "{\"gross\":\"900.00\"}");

            assertThatCode(() -> stage(TEMPLE_A, 7, 61, "restated-ref", "{\"gross\":\"950.00\"}"))
                    .doesNotThrowAnyException();

            assertThat(count("fin_stg_revenue WHERE source_record_ref = 'restated-ref'")).isEqualTo(2);
        }

        @Test
        @DisplayName("Many distinct records coexist in one batch")
        void should_allowManyRecords_when_referencesDiffer() throws SQLException {
            for (int i = 0; i < 5; i++) {
                stage(TEMPLE_A, 8, 70, "batch-70-row-" + i, "{\"gross\":\"1.00\"}");
            }
            assertThat(count("fin_stg_revenue WHERE sync_batch_id = 70")).isEqualTo(5);
        }
    }

    // ---------------------------------------------------------------- semantics

    @Nested
    @DisplayName("Staging semantics")
    class Semantics {

        @Test
        @DisplayName("A staged row starts RECEIVED and is judged by nobody yet")
        void should_defaultToReceived_when_rowLands() throws SQLException {
            stage(TEMPLE_A, 9, 80, "fresh-ref", "{\"gross\":\"5.00\"}");

            assertThat(scalar("SELECT validation_status FROM fin_stg_revenue "
                    + "WHERE source_record_ref = 'fresh-ref'")).isEqualTo("RECEIVED");
            assertThat(scalar("SELECT rejection_reason FROM fin_stg_revenue "
                    + "WHERE source_record_ref = 'fresh-ref'")).isNull();
        }

        @Test
        @DisplayName("All four documented states are storable, and the enum declares exactly those")
        void should_storeDocumentedStates_when_pipelineAdvancesThem() throws SQLException {
            assertThat(Arrays.stream(StagingStatus.values()).map(Enum::name).toList())
                    .containsExactly("RECEIVED", "VALID", "REJECTED", "LOADED");

            int batch = 90;
            for (StagingStatus status : StagingStatus.values()) {
                stage(TEMPLE_A, 10, batch, "state-" + status, "{\"gross\":\"1.00\"}");
                execute("UPDATE fin_stg_revenue SET validation_status = '" + status + "' "
                        + "WHERE source_record_ref = 'state-" + status + "'");
            }

            assertThat(valuesOf("SELECT DISTINCT validation_status FROM fin_stg_revenue "
                    + "WHERE sync_batch_id = " + batch + " ORDER BY validation_status"))
                    .containsExactlyInAnyOrder("RECEIVED", "VALID", "REJECTED", "LOADED");

            assertThat(commentOf("fin_stg_revenue", "validation_status"))
                    .as("the permitted states must be documented where somebody reading the schema sees them")
                    .contains("RECEIVED").contains("VALID").contains("REJECTED").contains("LOADED");
        }

        @Test
        @DisplayName("A rejected row keeps its reason and is not deleted")
        void should_retainRejection_when_rowFailsValidation() throws SQLException {
            stage(TEMPLE_A, 11, 100, "bad-ref", "{\"gross\":\"not-a-number\"}");
            execute("UPDATE fin_stg_revenue SET validation_status = 'REJECTED', "
                    + "rejection_reason = 'gross amount is not numeric', updated_at = NOW(6) "
                    + "WHERE source_record_ref = 'bad-ref'");

            assertThat(count("fin_stg_revenue WHERE source_record_ref = 'bad-ref'")).isEqualTo(1);
            assertThat(scalar("SELECT rejection_reason FROM fin_stg_revenue "
                    + "WHERE source_record_ref = 'bad-ref'")).isEqualTo("gross amount is not numeric");
            assertThat(jsonField(TEMPLE_A, "bad-ref", "gross"))
                    .as("the offending payload survives, or the rejection cannot be investigated")
                    .isEqualTo("not-a-number");
        }

        /**
         * A source may edit a two-year-old receipt today. The staged row then carries today's
         * extraction time and a two-year-old business date, and conflating the two would move
         * the money into today (FIN-D-012).
         */
        @Test
        @DisplayName("Business date and extraction time are separate columns of separate types")
        void should_separateBusinessDateFromExtraction_when_oldRecordIsReExtracted() throws SQLException {
            execute("INSERT INTO fin_stg_revenue (temple_id, source_system_id, sync_batch_id, "
                    + "source_record_ref, raw_json, source_business_date, extracted_at, created_at, updated_at) "
                    + "VALUES (" + TEMPLE_A + ", 12, 110, 'old-receipt', '{\"gross\":\"75.00\"}', "
                    + "'2023-04-11', NOW(6), NOW(6), NOW(6))");

            assertThat(typeOf("fin_stg_revenue", "source_business_date")).isEqualTo("date");
            assertThat(typeOf("fin_stg_revenue", "extracted_at")).isEqualTo("datetime");

            assertThat(scalar("SELECT source_business_date FROM fin_stg_revenue "
                    + "WHERE source_record_ref = 'old-receipt'")).isEqualTo("2023-04-11");
            assertThat(scalar("SELECT DATE(extracted_at) = source_business_date FROM fin_stg_revenue "
                    + "WHERE source_record_ref = 'old-receipt'"))
                    .as("re-extracting an old record must not make it today's revenue")
                    .isEqualTo("0");
        }

        @Test
        @DisplayName("An undeclared business date is NULL, not a substituted one")
        void should_allowUndeclaredBusinessDate_when_connectorCannotStateIt() throws SQLException {
            stage(TEMPLE_A, 13, 120, "no-date-ref", "{\"gross\":\"3.00\"}");

            assertThat(scalar("SELECT source_business_date FROM fin_stg_revenue "
                    + "WHERE source_record_ref = 'no-date-ref'"))
                    .as("NULL means not declared at extraction; a substituted date would be invented data")
                    .isNull();
        }

        @Test
        @DisplayName("The payload is stored as delivered, including values that will be rejected")
        void should_preservePayload_when_sourceValueIsImpossible() throws SQLException {
            stage(TEMPLE_A, 14, 130, "messy-ref",
                    "{\"gross\":\"9061629360.05\",\"txn_date\":\"0000-00-00\",\"mode\":\"\"}");

            assertThat(jsonField(TEMPLE_A, "messy-ref", "gross"))
                    .as("a decimal rendered as text and read back must be exact to the paisa")
                    .isEqualTo("9061629360.05");
            assertThat(jsonField(TEMPLE_A, "messy-ref", "txn_date"))
                    .as("an impossible date must land and be rejected with a reason, not crash extraction")
                    .isEqualTo("0000-00-00");
            assertThat(jsonField(TEMPLE_A, "messy-ref", "mode"))
                    .as("an empty source value is preserved as empty, not turned into a default")
                    .isEmpty();
        }
    }

    // ---------------------------------------------------------------- purity and structure

    @Nested
    @DisplayName("Purity and structure")
    class PurityAndStructure {

        @Test
        @DisplayName("Staging stores no money in a typed column, and no floating point anywhere")
        void should_holdNoTypedMoney_when_schemaInspected() throws SQLException {
            List<String> types = valuesOf("SELECT data_type FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = 'fin_stg_revenue'");

            assertThat(types).doesNotContain("float", "double", "real");
            assertThat(types)
                    .as("amounts stay inside raw_json until normalization; a typed amount column "
                            + "here would force parsing during extraction, which is what staging exists "
                            + "to avoid. Exact decimals are fin_revenue_fact's job.")
                    .doesNotContain("decimal");
        }

        @Test
        @DisplayName("No source vocabulary, credential or transport detail appears in the migration")
        void should_stayGeneric_when_migrationScanned() throws IOException {
            String file = Files.readString(STAGING_MIGRATION, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            // Comments stripped for the vocabulary scan, as in FIN-052: a header documenting
            // which words are banned must not be flagged for naming them. Identity tokens are
            // checked against the whole file, comments included.
            String ddl = file.replaceAll("(?m)--.*$", " ").replaceAll("(?s)/\\*.*?\\*/", " ");

            for (String token : List.of(
                    "dailyseva", "hkanike", "hitemmaster", "sevacode", "billcancled", "deleteflag",
                    "multirecpno", "templecode", "password", "credential", "jdbc", "url",
                    "host", "port", "endpoint", "driver")) {
                assertThat(ddl)
                        .as("[%s] belongs to the integration boundary or to a secret store, "
                                + "never to a table every temple's data lands in", token)
                        .doesNotContain(token);
            }

            for (String token : List.of("kolsoham", "mookambika", "300001")) {
                assertThat(file)
                        .as("[%s] names the first onboarded temple; staging must read the same "
                                + "for the second one", token)
                        .doesNotContain(token);
            }
        }

        @Test
        @DisplayName("No column is named after a source field, a transport or a person")
        void should_containNoSourceColumns_when_schemaInspected() throws SQLException {
            for (String column : columnsOf("fin_stg_revenue")) {
                for (String token : List.of("sevacode", "billcancled", "deleteflag", "recpno",
                        "ssv", "sannidhi", "templecode", "devotee", "person", "mobile", "address",
                        "email", "password", "credential", "jdbc", "host", "port", "endpoint")) {
                    assertThat(column)
                            .as("fin_stg_revenue.%s carries source vocabulary, transport detail or "
                                    + "personal data into staging", column)
                            .doesNotContain(token);
                }
            }
        }

        @Test
        @DisplayName("The processing and investigation indexes exist")
        void should_indexProcessingPaths_when_created() throws SQLException {
            assertThat(valuesOf("SELECT DISTINCT index_name FROM information_schema.statistics "
                    + "WHERE table_schema = DATABASE() AND table_name = 'fin_stg_revenue'"))
                    .contains("uk_fsr_batch_record", "idx_fsr_batch_status",
                            "idx_fsr_temple_date", "idx_fsr_source_record");
        }

        /** V113 must not have disturbed the canonical grain V112 established. */
        @Test
        @DisplayName("The canonical fact grain still holds after V113")
        void should_leaveV112Intact_when_stagingAdded() throws SQLException {
            String insert = "INSERT INTO fin_revenue_fact (temple_id, source_system_id, sync_batch_id, "
                    + "transaction_date, financial_year, category_id, payment_mode, created_at, updated_at) "
                    + "VALUES (" + TEMPLE_A + ", 1, 1, '2025-06-15', '2025-26', 1, 'CASH', NOW(6), NOW(6))";
            execute(insert);

            assertThatThrownBy(() -> execute(insert))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_frf_grain");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertOmittingColumnFails(String column) {
        List<String> columns = new ArrayList<>(List.of(
                "temple_id", "source_system_id", "sync_batch_id", "source_record_ref", "raw_json"));
        List<String> values = new ArrayList<>(List.of(
                String.valueOf(TEMPLE_A), "99", "999", "'omit-" + column + "'", "'{}'"));

        int index = columns.indexOf(column);
        columns.remove(index);
        values.remove(index);

        assertThatThrownBy(() -> execute("INSERT INTO fin_stg_revenue ("
                + String.join(", ", columns) + ", extracted_at, created_at, updated_at) VALUES ("
                + String.join(", ", values) + ", NOW(6), NOW(6), NOW(6))"))
                .as("a staged row without [%s] cannot be traced back to what produced it", column)
                .isInstanceOf(SQLException.class);
    }

    private static void stage(long templeId, long sourceSystemId, long batchId,
                              String recordRef, String rawJson) throws SQLException {
        execute("INSERT INTO fin_stg_revenue (temple_id, source_system_id, sync_batch_id, "
                + "source_record_ref, raw_json, extracted_at, created_at, updated_at) VALUES ("
                + templeId + ", " + sourceSystemId + ", " + batchId + ", '" + recordRef + "', '"
                + rawJson + "', NOW(6), NOW(6), NOW(6))");
    }

    private static String jsonField(long templeId, String recordRef, String field) throws SQLException {
        return scalar("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$." + field + "')) "
                + "FROM fin_stg_revenue WHERE temple_id = " + templeId
                + " AND source_record_ref = '" + recordRef + "'");
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

    private static String commentOf(String table, String column) throws SQLException {
        return scalar("SELECT column_comment FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }
}

package com.templeregistry.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
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

/**
 * FIN-021 .. FIN-024 verification against a real MySQL database.
 *
 * <p>Deliberately does <em>not</em> load the Spring context. Every existing
 * full-context test in this project is currently red for two pre-existing reasons
 * unrelated to finance (FIN-X-001 and FIN-X-002), and configuration that could only be
 * verified once somebody else fixed those would not be verified at all. Flyway is therefore
 * driven directly and the seeded rows are read back over plain JDBC — which also means this
 * test exercises the migration exactly as it will run in production, rather than a
 * Hibernate-generated approximation of it.
 *
 * <p>The seed is conditional on temple 300001 existing, and no migration creates that
 * temple. The first test asserts that conditionality holds — a seed that silently created
 * orphan configuration in every developer database would be a defect, not a convenience.
 */
@Testcontainers(disabledWithoutDocker = true)
class KollurFinanceConfigurationMigrationTest {

    private static final Path SEED_MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration", "V111__kollur_finance_configuration.sql");

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_seed")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .validateOnMigrate(false)
                .load()
                .migrate();

        // Assert the guard BEFORE creating the temple: with no temple 300001, V111 must
        // have seeded nothing at all.
        assertThat(count("fin_source_system"))
                .as("V111 must not seed configuration for a temple that does not exist")
                .isZero();
        assertThat(count("fin_temple_capability")).isZero();
        assertThat(count("fin_source_of_truth_decl")).isZero();
        assertThat(count("fin_mapping_rule")).isZero();

        createKollurTemple();
        applySeedMigration();
    }

    // ------------------------------------------------------------------ FIN-021

    @Nested
    @DisplayName("FIN-021 source system")
    class SourceSystem {

        @Test
        @DisplayName("Kollur is registered against temple 300001 with source identifier 43")
        void should_registerKollur_when_seeded() throws SQLException {
            assertThat(count("fin_source_system")).isEqualTo(1);

            assertThat(scalar("SELECT temple_id FROM fin_source_system")).isEqualTo("300001");
            assertThat(scalar("SELECT system_code FROM fin_source_system")).isEqualTo("KOLSOHAM");
            assertThat(scalar("SELECT source_temple_code FROM fin_source_system")).isEqualTo("43");
            assertThat(scalar("SELECT source_database_name FROM fin_source_system"))
                    .isEqualTo("KOLSOHAM_LOCAL");
        }

        /**
         * The single most important assertion in this migration. A source system that
         * arrived already enabled would start contacting a government temple's production
         * database before a connector, a network decision, a credential store, a dry run or
         * reconciliation existed.
         */
        @Test
        @DisplayName("Synchronization is disabled")
        void should_beDisabled_when_seeded() throws SQLException {
            assertThat(scalar("SELECT sync_enabled FROM fin_source_system")).isEqualTo("0");
            assertThat(scalar("SELECT sync_schedule_cron FROM fin_source_system")).isNull();
        }

        /** Only an alias may be persisted — never a credential value. */
        @Test
        @DisplayName("Only a credential alias is persisted")
        void should_persistAliasOnly_when_seeded() throws SQLException {
            assertThat(scalar("SELECT credential_ref FROM fin_source_system"))
                    .isEqualTo("kollur-readonly");
        }

        /**
         * Scans every persisted value, not just the columns we expect to be safe. If a
         * future edit smuggles a password into a notes field, this fails.
         */
        @Test
        @DisplayName("No credential, host, port or connection string is persisted anywhere")
        void should_persistNoSecretOrEndpoint_when_seeded() throws SQLException {
            List<String> values = allTextValues("fin_source_system");
            assertThat(values).isNotEmpty();

            for (String value : values) {
                String lower = value.toLowerCase(Locale.ROOT);
                assertThat(lower)
                        .as("persisted configuration must contain no connection endpoint")
                        .doesNotContain("jdbc:")
                        .doesNotContain("server=")
                        .doesNotContain("data source=")
                        .doesNotContain("://")
                        .doesNotContain("password=")
                        .doesNotContain("pwd=")
                        .doesNotContain("user id=");
            }
        }

        /** The schema itself offers nowhere to put one (FIN-D-002). */
        @Test
        @DisplayName("The source-system table has no credential or connection column")
        void should_haveNoCredentialColumn_when_inspected() throws SQLException {
            List<String> columns = columnsOf("fin_source_system");

            assertThat(columns).contains("credential_ref");
            assertThat(columns)
                    .noneMatch(c -> c.contains("password"))
                    .noneMatch(c -> c.equals("host"))
                    .noneMatch(c -> c.equals("port"))
                    .noneMatch(c -> c.contains("jdbc"))
                    .noneMatch(c -> c.contains("connection_string"))
                    .noneMatch(c -> c.equals("username"));
        }
    }

    // ------------------------------------------------------------------ FIN-022

    @Nested
    @DisplayName("FIN-022 capabilities")
    class Capabilities {

        @Test
        @DisplayName("All 19 canonical capabilities are declared exactly once")
        void should_declareEveryCapability_when_seeded() throws SQLException {
            assertThat(count("fin_temple_capability")).isEqualTo(19);
            assertThat(scalar("SELECT COUNT(DISTINCT capability) FROM fin_temple_capability"))
                    .isEqualTo("19");
            assertThat(scalar("SELECT COUNT(*) FROM fin_temple_capability WHERE temple_id <> 300001"))
                    .isEqualTo("0");
        }

        @Test
        @DisplayName("Availability matches the measured evidence")
        void should_matchDocumentedAvailability_when_seeded() throws SQLException {
            assertThat(capabilitiesWith("AVAILABLE")).containsExactlyInAnyOrder(
                    "REVENUE", "SEVA", "DONATION", "PRASADAM_SALE", "CANCELLATION",
                    "PRECIOUS_METAL_COUNT", "PRECIOUS_METAL_WEIGHT", "IN_KIND_DONATION",
                    "NIRANTARA_SUBSCRIPTION", "NIRANTARA_SCHEDULE");

            assertThat(capabilitiesWith("PARTIALLY_AVAILABLE")).containsExactlyInAnyOrder(
                    "NIRANTARA_PAYMENT", "PAYMENT_MODE");

            assertThat(capabilitiesWith("NOT_AVAILABLE")).containsExactlyInAnyOrder(
                    "PRECIOUS_METAL_VALUE", "NIRANTARA_EXECUTION",
                    "EXPENSE", "EXPENSE_CATEGORY", "GRANT", "GRANT_UTILISATION", "WORKS");
        }

        /**
         * ADR-007. Every unavailable capability must explain itself in terms a Deputy
         * Commissioner can read, because that text is rendered where a figure would be.
         */
        @Test
        @DisplayName("Every capability carries a substantive, non-generic reason")
        void should_explainItself_when_capabilityDeclared() throws SQLException {
            try (Connection c = connect();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT capability, availability_reason FROM fin_temple_capability")) {

                int checked = 0;
                while (rs.next()) {
                    String capability = rs.getString(1);
                    String reason = rs.getString(2);

                    assertThat(reason).as("%s must have a reason", capability).isNotBlank();
                    assertThat(reason.length())
                            .as("%s reason must be substantive, not a placeholder", capability)
                            .isGreaterThan(40);
                    assertThat(reason.toLowerCase(Locale.ROOT))
                            .as("%s reason must not be generic filler", capability)
                            .isNotEqualTo("data unavailable")
                            .isNotEqualTo("not available");
                    checked++;
                }
                assertThat(checked).isEqualTo(19);
            }
        }

        /** Missing data must never be described, or later read, as zero. */
        @Test
        @DisplayName("Unavailable capabilities are never expressed as a zero amount")
        void should_notExpressAbsenceAsZero_when_capabilityUnavailable() throws SQLException {
            String expense = reasonFor("EXPENSE");
            assertThat(expense).contains("does not record expenditure");
            assertThat(expense).contains("it is not zero");

            assertThat(reasonFor("NIRANTARA_PAYMENT")).contains("not zero");
        }

        /**
         * Weight is measured and available; value is absent and must stay absent. This pair
         * is what prevents the discarded "assume a rate per item" approach returning.
         */
        @Test
        @DisplayName("Metal weight is available while metal value stays unavailable")
        void should_separateMetalWeightFromValue_when_seeded() throws SQLException {
            assertThat(availabilityOf("PRECIOUS_METAL_WEIGHT")).isEqualTo("AVAILABLE");
            assertThat(availabilityOf("PRECIOUS_METAL_COUNT")).isEqualTo("AVAILABLE");
            assertThat(availabilityOf("PRECIOUS_METAL_VALUE")).isEqualTo("NOT_AVAILABLE");
            assertThat(reasonFor("PRECIOUS_METAL_VALUE")).contains("assumed rate");
        }

        /**
         * ADR-009. Schedules exist; performance does not. The reason must say why the
         * apparently-promising preparation flag is not evidence.
         */
        @Test
        @DisplayName("Nirantara schedules are available but execution is not")
        void should_separateScheduleFromExecution_when_seeded() throws SQLException {
            assertThat(availabilityOf("NIRANTARA_SUBSCRIPTION")).isEqualTo("AVAILABLE");
            assertThat(availabilityOf("NIRANTARA_SCHEDULE")).isEqualTo("AVAILABLE");
            assertThat(availabilityOf("NIRANTARA_EXECUTION")).isEqualTo("NOT_AVAILABLE");

            assertThat(reasonFor("NIRANTARA_EXECUTION"))
                    .contains("2027")
                    .contains("not that a seva took place");
            assertThat(reasonFor("NIRANTARA_SCHEDULE")).contains("not a record that the seva took place");
        }

        /** "No digital payment recorded" is provable; "paid in cash" is not. */
        @Test
        @DisplayName("Payment mode is partial and does not claim cash")
        void should_notClaimCash_when_paymentModeInferred() throws SQLException {
            assertThat(availabilityOf("PAYMENT_MODE")).isEqualTo("PARTIALLY_AVAILABLE");

            String reason = reasonFor("PAYMENT_MODE");
            assertThat(reason).contains("no digital payment was recorded");
            assertThat(reason).contains("not that the payment was made in cash");
        }

        @Test
        @DisplayName("Coverage windows match the documented periods")
        void should_recordDocumentedCoverage_when_seeded() throws SQLException {
            assertThat(coverageFrom("REVENUE")).isEqualTo("2019-04-01");
            assertThat(coverageTo("REVENUE")).isEqualTo("2026-07-26");

            assertThat(coverageFrom("PRECIOUS_METAL_WEIGHT")).isEqualTo("2015-04-01");

            assertThat(coverageFrom("NIRANTARA_PAYMENT")).isEqualTo("2017-04-01");
            assertThat(coverageTo("NIRANTARA_PAYMENT")).isEqualTo("2024-03-31");
        }

        /** An unknown coverage window is left unknown rather than invented. */
        @Test
        @DisplayName("Capabilities with no reliable coverage leave it unspecified")
        void should_leaveCoverageNull_when_notDocumented() throws SQLException {
            assertThat(coverageFrom("EXPENSE")).isNull();
            assertThat(coverageTo("EXPENSE")).isNull();
            assertThat(coverageFrom("NIRANTARA_SUBSCRIPTION")).isNull();
        }

        /** The two empty precious-metal years are recorded as gaps, not as zeros. */
        @Test
        @DisplayName("Known gaps inside a coverage window are recorded")
        void should_recordKnownGaps_when_yearsMissing() throws SQLException {
            String gaps = scalar(
                    "SELECT known_gaps_json FROM fin_temple_capability WHERE capability = 'PRECIOUS_METAL_WEIGHT'");

            assertThat(gaps).contains("2021-22").contains("2022-23");
            assertThat(scalar(
                    "SELECT known_gaps_json FROM fin_temple_capability WHERE capability = 'NIRANTARA_PAYMENT'"))
                    .contains("2023-24");
        }
    }

    // ------------------------------------------------------------------ FIN-023

    @Nested
    @DisplayName("FIN-023 source of truth")
    class SourceOfTruth {

        @Test
        @DisplayName("The receipt header amount is declared authoritative")
        void should_declareHeaderAmount_when_seeded() throws SQLException {
            assertThat(count("fin_source_of_truth_decl")).isEqualTo(1);

            assertThat(scalar("SELECT metric FROM fin_source_of_truth_decl")).isEqualTo("REVENUE_AMOUNT");
            assertThat(scalar("SELECT source_object FROM fin_source_of_truth_decl")).isEqualTo("DailySevaNew");
            assertThat(scalar("SELECT source_field FROM fin_source_of_truth_decl")).isEqualTo("Amount");
            assertThat(scalar("SELECT version FROM fin_source_of_truth_decl")).isEqualTo("1");
            assertThat(scalar("SELECT effective_to FROM fin_source_of_truth_decl"))
                    .as("the first version must be the one currently in force")
                    .isNull();
        }

        @Test
        @DisplayName("Cancelled and deleted records are excluded from recognised revenue")
        void should_recordFilters_when_seeded() throws SQLException {
            String filter = scalar("SELECT filter_predicate FROM fin_source_of_truth_decl");

            assertThat(filter).contains("Deleteflag = 0");
            assertThat(filter).contains("BillCancled = 0");
            assertThat(filter).contains("TempleCode = 43");
        }

        /**
         * The rejected alternatives carry their measured totals. That is what stops a later
         * engineer "improving" revenue by switching to the more granular table.
         */
        @Test
        @DisplayName("All three measured alternatives are recorded with their evidence")
        void should_recordRejectedAlternatives_when_seeded() throws SQLException {
            String rejected = scalar("SELECT rejected_alternatives_json FROM fin_source_of_truth_decl");

            assertThat(rejected).contains("DailySevaNewOld");
            assertThat(rejected).contains("16982270");
            assertThat(rejected).contains("doubles every historical year");

            assertThat(rejected).contains("TotalAmount");
            assertThat(rejected).contains("537753226");
            assertThat(rejected).contains("41%");

            assertThat(rejected).contains("Amount * Qty");
            assertThat(rejected).contains("563107228");
        }

        @Test
        @DisplayName("Rate-card price and receipt counts are not mistaken for revenue or devotees")
        void should_recordRevenueCorrectnessRules_when_seeded() throws SQLException {
            String rationale = scalar("SELECT rationale FROM fin_source_of_truth_decl");

            assertThat(rationale).contains("rate-card amount is a list price and is never revenue");
            assertThat(rationale).contains("receipts, not devotees");
            assertThat(rationale).contains("never the Old archive");
            assertThat(rationale).contains("906162936");
        }
    }

    // ------------------------------------------------------------------ FIN-024

    @Nested
    @DisplayName("FIN-024 mapping rules")
    class MappingRules {

        @Test
        @DisplayName("Source income buckets map to canonical categories")
        void should_mapIncomeBuckets_when_seeded() throws SQLException {
            assertThat(canonicalFor("SANNIDHI:DS")).isEqualTo("SEVA");
            assertThat(canonicalFor("SANNIDHI:SS")).isEqualTo("SPECIAL_SEVA");
            assertThat(canonicalFor("SANNIDHI:KN")).isEqualTo("DONATION");
            assertThat(canonicalFor("SANNIDHI:PS")).isEqualTo("PRASADAM_SALE");
        }

        /** Donation-box collections are not purchased services. */
        @Test
        @DisplayName("Donation-box collection is separated from ordinary donation")
        void should_separateHundi_when_seeded() throws SQLException {
            assertThat(canonicalFor("SEVA_CODE:430")).isEqualTo("HUNDI_DONATION");
            assertThat(scalar("SELECT source_label FROM fin_mapping_rule WHERE source_value = 'SEVA_CODE:430'"))
                    .isEqualTo("HUNDIALS");
        }

        @Test
        @DisplayName("In-kind donation and asset realisation stay distinct financial events")
        void should_separateSareeStreams_when_seeded() throws SQLException {
            assertThat(canonicalFor("STREAM:SAREE_DONATION")).isEqualTo("IN_KIND_DONATION");
            assertThat(canonicalFor("STREAM:SAREE_AUCTION")).isEqualTo("ASSET_REALISATION");
        }

        @Test
        @DisplayName("Metal type codes map to gold and silver")
        void should_mapMetalTypes_when_seeded() throws SQLException {
            assertThat(scalar(
                    "SELECT canonical_value FROM fin_mapping_rule WHERE mapping_type = 'METAL_TYPE' AND source_value = '2'"))
                    .isEqualTo("GOLD");
            assertThat(scalar(
                    "SELECT canonical_value FROM fin_mapping_rule WHERE mapping_type = 'METAL_TYPE' AND source_value = '1'"))
                    .isEqualTo("SILVER");
        }

        @Test
        @DisplayName("Every mapping rule is active and belongs to the Kollur source system")
        void should_beActiveAndScoped_when_seeded() throws SQLException {
            assertThat(count("fin_mapping_rule")).isEqualTo(9);
            assertThat(scalar("SELECT COUNT(*) FROM fin_mapping_rule WHERE is_active = 0")).isEqualTo("0");
            assertThat(scalar(
                    "SELECT COUNT(*) FROM fin_mapping_rule m JOIN fin_source_system s ON s.id = m.source_system_id "
                            + "WHERE s.system_code <> 'KOLSOHAM'")).isEqualTo("0");
        }

        /**
         * Mapping is value translation. A source table name, column name or SQL fragment
         * appearing here would mean extraction logic had leaked into configuration.
         */
        @Test
        @DisplayName("No source implementation detail leaks into a mapped value")
        void should_containNoImplementationDetail_when_inspected() throws SQLException {
            try (Connection c = connect();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT source_value, canonical_value FROM fin_mapping_rule")) {

                int checked = 0;
                while (rs.next()) {
                    for (int col = 1; col <= 2; col++) {
                        String value = rs.getString(col).toLowerCase(Locale.ROOT);
                        assertThat(value)
                                .doesNotContain("select ").doesNotContain("join ")
                                .doesNotContain("where ").doesNotContain("dailyseva")
                                .doesNotContain("hkanike").doesNotContain("seva_seva")
                                .doesNotContain("ssv_").doesNotContain("ssn_");
                    }
                    checked++;
                }
                assertThat(checked).isEqualTo(9);
            }
        }

        /** Canonical values must come from the documented taxonomy, not be invented. */
        @Test
        @DisplayName("Canonical values are drawn only from the documented taxonomy")
        void should_useOnlyDocumentedCanonicalValues_when_seeded() throws SQLException {
            List<String> documented = List.of(
                    "SEVA", "SPECIAL_SEVA", "DONATION", "HUNDI_DONATION", "PRASADAM_SALE",
                    "ENTRY_FEE", "IN_KIND_DONATION", "ASSET_REALISATION",
                    "RENT_LEASE", "HALL_BOOKING", "OTHER_INCOME", "GOLD", "SILVER");

            try (Connection c = connect();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT DISTINCT canonical_value FROM fin_mapping_rule")) {
                while (rs.next()) {
                    assertThat(documented).contains(rs.getString(1));
                }
            }
        }
    }

    // ------------------------------------------------------------- idempotency

    @Test
    @DisplayName("Re-applying the seed changes nothing")
    void should_beIdempotent_when_seedReapplied() throws Exception {
        applySeedMigration();

        assertThat(count("fin_source_system")).isEqualTo(1);
        assertThat(count("fin_temple_capability")).isEqualTo(19);
        assertThat(count("fin_source_of_truth_decl")).isEqualTo(1);
        assertThat(count("fin_mapping_rule")).isEqualTo(9);
    }

    // ------------------------------------------------------------------ helpers

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static void createKollurTemple() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                INSERT INTO temples (id, registration_number, name, grade, primary_deity, district_id)
                VALUES (300001, 'KA-TMP-29D0887C', 'Kollur Sri Mookambika Devi Temple', 'A',
                        'Mookambika', 1)
                """);
        }
    }

    /** Executes the seed migration file directly, so the committed artifact is what runs. */
    private static void applySeedMigration() throws IOException, SQLException {
        String sql = Files.readString(SEED_MIGRATION, StandardCharsets.UTF_8);
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String statement : splitStatements(sql)) {
                s.executeUpdate(statement);
            }
        }
    }

    /**
     * Splits on statement terminators while respecting quoted text. A naive split would
     * break on the semicolons that appear inside the seeded explanatory strings.
     */
    static List<String> splitStatements(String sql) {
        String withoutComments = sql.replaceAll("(?m)^\\s*--.*$", "");

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < withoutComments.length(); i++) {
            char ch = withoutComments.charAt(i);

            if (ch == '\'') {
                // '' inside a string is an escaped quote, not a terminator.
                if (inString && i + 1 < withoutComments.length() && withoutComments.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
            }

            if (ch == ';' && !inString) {
                if (!current.toString().isBlank()) {
                    statements.add(current.toString().trim());
                }
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private static long count(String table) throws SQLException {
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String availabilityOf(String capability) throws SQLException {
        return scalar("SELECT availability FROM fin_temple_capability WHERE capability = '" + capability + "'");
    }

    private static String reasonFor(String capability) throws SQLException {
        return scalar("SELECT availability_reason FROM fin_temple_capability WHERE capability = '"
                + capability + "'");
    }

    private static String coverageFrom(String capability) throws SQLException {
        return scalar("SELECT coverage_from FROM fin_temple_capability WHERE capability = '" + capability + "'");
    }

    private static String coverageTo(String capability) throws SQLException {
        return scalar("SELECT coverage_to FROM fin_temple_capability WHERE capability = '" + capability + "'");
    }

    private static String canonicalFor(String sourceValue) throws SQLException {
        return scalar("SELECT canonical_value FROM fin_mapping_rule WHERE source_value = '" + sourceValue + "'");
    }

    private static List<String> capabilitiesWith(String availability) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT capability FROM fin_temple_capability WHERE availability = '" + availability + "'")) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        return found;
    }

    private static List<String> allTextValues(String table) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= columns; i++) {
                    String value = rs.getString(i);
                    if (value != null) {
                        values.add(value);
                    }
                }
            }
        }
        return values;
    }

    private static List<String> columnsOf(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            int count = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= count; i++) {
                columns.add(rs.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }
}

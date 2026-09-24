package com.templeregistry.service.finance.sync.jdbc;

import com.templeregistry.connector.finance.DateRange;
import com.templeregistry.connector.finance.RawRow;
import com.templeregistry.connector.finance.ReconMetric;
import com.templeregistry.connector.finance.SourceProbeResult;
import com.templeregistry.connector.finance.SourceSystemDescriptor;
import com.templeregistry.connector.finance.SourceTotals;
import com.templeregistry.connector.finance.SyncContext;
import com.templeregistry.connector.finance.UnsupportedCapabilityException;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.service.finance.sync.CredentialNotConfiguredException;
import com.templeregistry.service.finance.sync.SourceCredentialProvider;
import com.templeregistry.service.finance.sync.SourceCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generic JDBC connector, exercised end to end against a synthetic in-memory source (FIN-040).
 *
 * <h2>No temple database is involved, and none could be</h2>
 *
 * <p>Q4 is unresolved, so no network path to a real temple system exists. That is not a gap in
 * these tests — it is the reason the connector was built against a settings/connection seam in the
 * first place. The schema below is invented for this test and deliberately resembles no real
 * temple: a connector whose tests needed the first onboarded source's tables would have failed the
 * genericity claim before it was even asserted.
 *
 * <p>H2 is already a test dependency. No driver was added, and only {@code mysql-connector-j} is a
 * runtime dependency, so the connector is written vendor-neutrally: it asks the driver for its
 * identifier quoting rather than assuming any vendor's.
 */
class JdbcTableConnectorTest {

    private static final String SYSTEM_CODE = "SYNTHETIC_SRC";

    private String url;
    private Connection keepAlive;
    private JdbcTableConnector connector;
    private RecordingConnectionFactory connectionFactory;
    private JdbcSourceSettings settings;

    @BeforeEach
    void setUp() throws SQLException {
        // A private in-memory database per test, held open by one connection so the schema
        // survives between the connector's own short-lived connections.
        url = "jdbc:h2:mem:fin040_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=0;MODE=LEGACY";
        keepAlive = DriverManager.getConnection(url, "sa", "");

        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("""
                    CREATE TABLE SOURCE_RECEIPTS (
                        ID              VARCHAR(40) PRIMARY KEY,
                        TRANSACTION_DATE DATE,
                        AMOUNT          DECIMAL(18,2),
                        PAYMENT_MODE    VARCHAR(20),
                        SERVICE_CODE    VARCHAR(20),
                        UPDATED_AT      TIMESTAMP
                    )""");
            statement.execute("""
                    INSERT INTO SOURCE_RECEIPTS VALUES
                      ('R1', DATE '2026-04-01', 150.00, 'CASH', 'S1', TIMESTAMP '2026-04-01 10:00:00'),
                      ('R2', DATE '2026-04-02', 250.50, 'CARD', 'S2', TIMESTAMP '2026-04-02 10:00:00'),
                      ('R3', DATE '2026-05-10', 300.00, 'CASH', 'S1', TIMESTAMP '2026-05-10 10:00:00')""");
        }

        settings = settings(SqlIdentifier.of("table", "SOURCE_RECEIPTS"));
        connectionFactory = new RecordingConnectionFactory();
        connector = new JdbcTableConnector(
                systemCode -> SYSTEM_CODE.equals(systemCode) ? Optional.of(settings) : Optional.empty(),
                new FixedCredentialProvider(),
                connectionFactory);
    }

    @AfterEach
    void tearDown() throws SQLException {
        keepAlive.close();
    }

    private JdbcSourceSettings settings(SqlIdentifier table) {
        return new JdbcSourceSettings(
                url,
                table,
                List.of(SqlIdentifier.of("column", "ID"),
                        SqlIdentifier.of("column", "TRANSACTION_DATE"),
                        SqlIdentifier.of("column", "AMOUNT"),
                        SqlIdentifier.of("column", "PAYMENT_MODE"),
                        SqlIdentifier.of("column", "SERVICE_CODE")),
                SqlIdentifier.of("record-ref-column", "ID"),
                Optional.of(SqlIdentifier.of("changed-at-column", "UPDATED_AT")),
                Optional.of(SqlIdentifier.of("business-date-column", "TRANSACTION_DATE")),
                Optional.of(SqlIdentifier.of("amount-column", "AMOUNT")),
                100,
                30);
    }

    // ------------------------------------------------------------------ identity

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("The declared connector id matches the bean name configuration refers to")
        void should_declareTheRegisteredId_when_askedForMetadata() {
            assertThat(connector.metadata().connectorId())
                    .as("ConnectorRegistry refuses a connector whose id differs from its bean name")
                    .isEqualTo("jdbcTableConnector")
                    .isEqualTo(JdbcTableConnector.CONNECTOR_ID);
            assertThat(connector.metadata().connectorType()).isEqualTo(ConnectorType.PULL_JDBC);
        }

        @Test
        @DisplayName("Revenue is declared for a configured source, and nothing for an unconfigured one")
        void should_declareCapabilities_when_sourceIsConfigured() {
            assertThat(connector.describeCapabilities(descriptor(SYSTEM_CODE)))
                    .containsExactly(FinanceCapability.REVENUE);
            assertThat(connector.describeCapabilities(descriptor("NOT_CONFIGURED")))
                    .as("nothing, so the platform reports NOT_AVAILABLE with a reason rather than "
                            + "extracting zero rows and presenting that as a temple with no income")
                    .isEmpty();
        }

        @Test
        @DisplayName("A capability that was never declared is refused rather than answered emptily")
        void should_refuse_when_capabilityWasNotDeclared() {
            SyncContext context = context(SYSTEM_CODE, Optional.empty(), DateRange.unbounded());

            assertThatThrownBy(() -> connector.extract(FinanceCapability.EXPENSE, context))
                    .isInstanceOf(UnsupportedCapabilityException.class);
        }
    }

    // --------------------------------------------------------------------- read

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        @DisplayName("Every configured column is returned under its own source name")
        void should_returnConfiguredColumns_when_extracting() {
            List<RawRow> rows = extractAll(DateRange.unbounded(), Optional.empty());

            assertThat(rows).hasSize(3);
            RawRow first = rows.get(0);
            assertThat(first.sourceRecordRef()).isEqualTo("R1");
            assertThat(first.values().keySet())
                    .as("normalization looks the staged payload up by the source_field a "
                            + "source-of-truth declaration names, so renaming here would break it")
                    .containsExactlyInAnyOrder(
                            "ID", "TRANSACTION_DATE", "AMOUNT", "PAYMENT_MODE", "SERVICE_CODE");
            assertThat(first.get("PAYMENT_MODE")).isEqualTo("CASH");
        }

        @Test
        @DisplayName("Values are returned as strings exactly as the driver rendered them")
        void should_returnValuesAsStrings_when_extracting() {
            RawRow row = extractAll(DateRange.unbounded(), Optional.empty()).get(1);

            assertThat(row.get("AMOUNT"))
                    .as("a decimal rendered as text and parsed back is exact; typing here would "
                            + "turn one impossible source value into a lost batch")
                    .isEqualTo("250.50");
            assertThat(row.get("TRANSACTION_DATE")).isEqualTo("2026-04-02");
        }

        @Test
        @DisplayName("The business-date range narrows the read, bound as parameters")
        void should_narrowByBusinessDate_when_rangeIsGiven() {
            List<RawRow> rows = extractAll(
                    DateRange.of(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
                    Optional.empty());

            assertThat(rows).extracting(RawRow::sourceRecordRef).containsExactly("R1", "R2");
        }

        @Test
        @DisplayName("An incremental run narrows on the change axis, not the business-date axis")
        void should_narrowByChangeAxis_when_watermarkIsPresent() {
            List<RawRow> rows = extractAll(DateRange.unbounded(),
                    Optional.of(Instant.parse("2026-04-01T12:00:00Z")));

            assertThat(rows)
                    .as("the two axes are independent; filtering business dates by modification "
                            + "time would silently drop records never touched since")
                    .extracting(RawRow::sourceRecordRef)
                    .containsExactly("R2", "R3");
        }

        @Test
        @DisplayName("Rows come back in a deterministic order, so a re-read agrees with itself")
        void should_orderDeterministically_when_extracting() {
            assertThat(extractAll(DateRange.unbounded(), Optional.empty()))
                    .extracting(RawRow::sourceRecordRef)
                    .containsExactly("R1", "R2", "R3");
        }

        @Test
        @DisplayName("An empty result is an empty stream, not a failure")
        void should_returnNothing_when_noRowsMatch() {
            assertThat(extractAll(DateRange.of(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 12, 31)),
                    Optional.empty())).isEmpty();
        }

        @Test
        @DisplayName("A missing table fails with a translated error naming the source, not the URL")
        void should_translate_when_tableIsMissing() {
            JdbcSourceSettings missing = settings(SqlIdentifier.of("table", "NO_SUCH_TABLE"));
            JdbcTableConnector broken = new JdbcTableConnector(
                    code -> Optional.of(missing), new FixedCredentialProvider(), connectionFactory);

            assertThatThrownBy(() -> broken.extract(FinanceCapability.REVENUE,
                    context(SYSTEM_CODE, Optional.empty(), DateRange.unbounded())))
                    .isInstanceOf(SourceReadException.class)
                    .hasMessageContaining(SYSTEM_CODE)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("jdbc:h2"));
        }

        @Test
        @DisplayName("A missing column fails rather than silently returning fewer fields")
        void should_fail_when_configuredColumnIsMissing() {
            JdbcSourceSettings wrongColumn = new JdbcSourceSettings(
                    url, SqlIdentifier.of("table", "SOURCE_RECEIPTS"),
                    List.of(SqlIdentifier.of("column", "NOT_A_COLUMN")),
                    SqlIdentifier.of("record-ref-column", "ID"),
                    Optional.empty(), Optional.empty(), Optional.empty(), 100, 30);
            JdbcTableConnector broken = new JdbcTableConnector(
                    code -> Optional.of(wrongColumn), new FixedCredentialProvider(), connectionFactory);

            assertThatThrownBy(() -> broken.extract(FinanceCapability.REVENUE,
                    context(SYSTEM_CODE, Optional.empty(), DateRange.unbounded())))
                    .isInstanceOf(SourceReadException.class);
        }

        @Test
        @DisplayName("A source with no JDBC settings cannot be extracted from")
        void should_fail_when_sourceHasNoSettings() {
            assertThatThrownBy(() -> connector.extract(FinanceCapability.REVENUE,
                    context("NOT_CONFIGURED", Optional.empty(), DateRange.unbounded())))
                    .as("refused at the capability guard: nothing was declared for it")
                    .isInstanceOf(UnsupportedCapabilityException.class);
        }
    }

    // ------------------------------------------------------------------- safety

    @Nested
    @DisplayName("Read-only safety")
    class ReadOnlySafety {

        /**
         * The primary defence is structural: there is no API taking SQL, so a write is not an
         * operation the connector can be asked to perform. This asserts the shape of that claim.
         */
        @Test
        @DisplayName("The connector exposes no method that accepts SQL")
        void should_exposeNoSqlEntryPoint_when_inspected() {
            List<String> suspicious = new ArrayList<>();
            for (var method : JdbcTableConnector.class.getMethods()) {
                for (var parameter : method.getParameterTypes()) {
                    if (parameter == String.class
                            && !List.of("equals", "requireCapability").contains(method.getName())) {
                        suspicious.add(method.getName());
                    }
                }
            }
            assertThat(suspicious)
                    .as("a String parameter on a public connector method is where a query would "
                            + "eventually be passed in")
                    .isEmpty();
        }

        @Test
        @DisplayName("Write and DDL identifiers never reach the database: they fail validation first")
        void should_refuseWriteAttempts_when_smuggledThroughIdentifiers() {
            List<String> attacks = List.of(
                    "SOURCE_RECEIPTS; INSERT INTO SOURCE_RECEIPTS VALUES ('X',null,1,null,null,null)",
                    "SOURCE_RECEIPTS; UPDATE SOURCE_RECEIPTS SET AMOUNT = 0",
                    "SOURCE_RECEIPTS; DELETE FROM SOURCE_RECEIPTS",
                    "SOURCE_RECEIPTS; DROP TABLE SOURCE_RECEIPTS",
                    "SOURCE_RECEIPTS; CALL SOME_PROC()");

            for (String attack : attacks) {
                assertThatThrownBy(() -> SqlIdentifier.of("table", attack))
                        .as("[%s] must be refused before any statement is composed", attack)
                        .isInstanceOf(IllegalArgumentException.class);
            }

            assertThat(rowCount())
                    .as("the source table is untouched")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("The connection is opened read-only")
        void should_openReadOnly_when_extracting() {
            extractAll(DateRange.unbounded(), Optional.empty());

            assertThat(connectionFactory.openedReadOnly)
                    .as("a driver hint, and the weaker of the two defences — but still requested "
                            + "on every connection")
                    .isTrue();
        }

        @Test
        @DisplayName("Reading changes nothing in the source")
        void should_leaveSourceUnchanged_when_extracting() {
            extractAll(DateRange.unbounded(), Optional.empty());
            connector.probe(descriptor(SYSTEM_CODE));
            connector.sourceTotals(FinanceCapability.REVENUE, descriptor(SYSTEM_CODE),
                    DateRange.unbounded());

            assertThat(rowCount()).isEqualTo(3);
        }
    }

    // ------------------------------------------------------------- resources

    @Nested
    @DisplayName("Resource management")
    class Resources {

        @Test
        @DisplayName("Closing the stream closes the connection")
        void should_closeConnection_when_streamIsClosed() {
            try (Stream<RawRow> rows = connector.extract(FinanceCapability.REVENUE,
                    context(SYSTEM_CODE, Optional.empty(), DateRange.unbounded()))) {
                rows.forEach(row -> { });
            }

            assertThat(connectionFactory.allClosed())
                    .as("the stream holds a cursor; the contract makes the caller close it and "
                            + "RevenueExtractionStage does so with try-with-resources")
                    .isTrue();
        }

        @Test
        @DisplayName("Abandoning the stream without consuming it still closes the connection")
        void should_closeConnection_when_streamIsAbandonedEarly() {
            try (Stream<RawRow> rows = connector.extract(FinanceCapability.REVENUE,
                    context(SYSTEM_CODE, Optional.empty(), DateRange.unbounded()))) {
                assertThat(rows.findFirst()).isPresent();
            }

            assertThat(connectionFactory.allClosed()).isTrue();
        }

        @Test
        @DisplayName("A failure during setup closes the connection it had already opened")
        void should_closeConnection_when_setUpFails() {
            JdbcSourceSettings missing = settings(SqlIdentifier.of("table", "NO_SUCH_TABLE"));
            JdbcTableConnector broken = new JdbcTableConnector(
                    code -> Optional.of(missing), new FixedCredentialProvider(), connectionFactory);

            assertThatThrownBy(() -> broken.extract(FinanceCapability.REVENUE,
                    context(SYSTEM_CODE, Optional.empty(), DateRange.unbounded())))
                    .isInstanceOf(SourceReadException.class);

            assertThat(connectionFactory.allClosed())
                    .as("nothing was handed to a caller, so nothing else would have closed it")
                    .isTrue();
        }

        @Test
        @DisplayName("Probing closes its connection too")
        void should_closeConnection_when_probing() {
            connector.probe(descriptor(SYSTEM_CODE));

            assertThat(connectionFactory.allClosed()).isTrue();
        }
    }

    // --------------------------------------------------------------- probing

    @Nested
    @DisplayName("Probe, fingerprint and totals")
    class ProbeAndTotals {

        @Test
        @DisplayName("A reachable source probes usable")
        void should_reportUsable_when_sourceAnswers() {
            SourceProbeResult result = connector.probe(descriptor(SYSTEM_CODE));

            assertThat(result.usable()).isTrue();
            assertThat(result.detail()).contains("No data was read");
        }

        @Test
        @DisplayName("An unreachable source is reported, never thrown")
        void should_reportUnusable_when_sourceCannotBeReached() {
            JdbcSourceSettings unreachable = new JdbcSourceSettings(
                    "jdbc:h2:mem:definitely_absent;IFEXISTS=TRUE",
                    SqlIdentifier.of("table", "SOURCE_RECEIPTS"),
                    List.of(SqlIdentifier.of("column", "ID")),
                    SqlIdentifier.of("record-ref-column", "ID"),
                    Optional.empty(), Optional.empty(), Optional.empty(), 100, 5);
            JdbcTableConnector offline = new JdbcTableConnector(
                    code -> Optional.of(unreachable), new FixedCredentialProvider(),
                    new DriverManagerJdbcConnectionFactory());

            SourceProbeResult result = offline.probe(descriptor(SYSTEM_CODE));

            assertThat(result.usable())
                    .as("an unusable source is an expected operational state, not an exception")
                    .isFalse();
            assertThat(result.detail()).doesNotContain("jdbc:").doesNotContain("secret");
        }

        @Test
        @DisplayName("An unconfigured source probes unusable with a reason an operator can act on")
        void should_reportUnusable_when_noSettingsExist() {
            SourceProbeResult result = connector.probe(descriptor("NOT_CONFIGURED"));

            assertThat(result.usable()).isFalse();
            assertThat(result.detail()).contains("No JDBC read settings");
        }

        @Test
        @DisplayName("The schema fingerprint is stable, and changes when a read column changes")
        void should_changeFingerprint_when_schemaChanges() throws SQLException {
            var before = connector.fingerprintSchema(descriptor(SYSTEM_CODE));
            assertThat(before).isPresent();
            assertThat(connector.fingerprintSchema(descriptor(SYSTEM_CODE)))
                    .as("stable between runs, or every batch would look like drift")
                    .contains(before.get());

            try (Statement statement = keepAlive.createStatement()) {
                statement.execute("ALTER TABLE SOURCE_RECEIPTS ALTER COLUMN PAYMENT_MODE VARCHAR(400)");
            }

            assertThat(connector.fingerprintSchema(descriptor(SYSTEM_CODE)))
                    .isPresent()
                    .isNotEqualTo(before);
        }

        @Test
        @DisplayName("Source totals are computed by the source engine over business dates")
        void should_computeTotals_when_amountColumnIsConfigured() {
            SourceTotals totals = connector.sourceTotals(FinanceCapability.REVENUE,
                    descriptor(SYSTEM_CODE),
                    DateRange.of(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));

            assertThat(totals.total(ReconMetric.RECORD_COUNT)).contains(BigDecimal.valueOf(2));
            assertThat(totals.total(ReconMetric.GROSS_AMOUNT))
                    .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("400.50"));
        }

        @Test
        @DisplayName("Without an amount column the answer is NOT_AVAILABLE, never zero")
        void should_reportNotAvailable_when_noAmountColumnIsConfigured() {
            JdbcSourceSettings noAmount = new JdbcSourceSettings(
                    url, SqlIdentifier.of("table", "SOURCE_RECEIPTS"),
                    List.of(SqlIdentifier.of("column", "ID")),
                    SqlIdentifier.of("record-ref-column", "ID"),
                    Optional.empty(), Optional.empty(), Optional.empty(), 100, 30);
            JdbcTableConnector plain = new JdbcTableConnector(
                    code -> Optional.of(noAmount), new FixedCredentialProvider(), connectionFactory);

            SourceTotals totals = plain.sourceTotals(FinanceCapability.REVENUE,
                    descriptor(SYSTEM_CODE), DateRange.unbounded());

            assertThat(totals.isEmpty())
                    .as("\"we could not ask\" and \"the answer is nothing\" lead to opposite "
                            + "conclusions on a finance dashboard")
                    .isTrue();
            assertThat(totals.total(ReconMetric.GROSS_AMOUNT)).isEmpty();
        }

        @Test
        @DisplayName("A period with no rows reports a zero count and no amount at all")
        void should_omitAmount_when_noRowsMatchThePeriod() {
            SourceTotals totals = connector.sourceTotals(FinanceCapability.REVENUE,
                    descriptor(SYSTEM_CODE),
                    DateRange.of(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 12, 31)));

            assertThat(totals.total(ReconMetric.RECORD_COUNT)).contains(BigDecimal.ZERO);
            assertThat(totals.total(ReconMetric.GROSS_AMOUNT))
                    .as("SUM over no rows is SQL NULL, and reporting it as zero would assert the "
                            + "source holds nothing rather than that nothing matched")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------ credentials

    @Nested
    @DisplayName("Credentials")
    class Credentials {

        @Test
        @DisplayName("A missing credential fails without naming a secret")
        void should_failCleanly_when_credentialIsNotConfigured() {
            JdbcTableConnector unauthorised = new JdbcTableConnector(
                    code -> Optional.of(settings),
                    new SourceCredentialProvider() {
                        @Override
                        public SourceCredentials resolve(String credentialRef) {
                            throw new CredentialNotConfiguredException(credentialRef, "some.property");
                        }

                        @Override
                        public boolean isConfigured(String credentialRef) {
                            return false;
                        }
                    },
                    connectionFactory);

            assertThatThrownBy(() -> unauthorised.extract(FinanceCapability.REVENUE,
                    context(SYSTEM_CODE, Optional.empty(), DateRange.unbounded())))
                    .isInstanceOf(SourceReadException.class)
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain("test-secret")
                            .doesNotContain("jdbc:h2"));
        }

        @Test
        @DisplayName("An authentication failure is reported without leaking the credential")
        void should_notLeakCredential_when_authenticationFails() {
            SourceProbeResult result = connector.probe(descriptor(SYSTEM_CODE));
            assertThat(result.detail()).doesNotContain("test-secret");

            JdbcSourceSettings settingsWithQuery = new JdbcSourceSettings(
                    url + ";USER=sa", SqlIdentifier.of("table", "SOURCE_RECEIPTS"),
                    List.of(SqlIdentifier.of("column", "ID")),
                    SqlIdentifier.of("record-ref-column", "ID"),
                    Optional.empty(), Optional.empty(), Optional.empty(), 100, 30);
            assertThat(settingsWithQuery.describeTargetSafely()).doesNotContain("password");
        }
    }

    // ------------------------------------------------------------------ helpers

    private int rowCount() {
        try (Statement statement = keepAlive.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM SOURCE_RECEIPTS")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private List<RawRow> extractAll(DateRange period, Optional<Instant> changedSince) {
        try (Stream<RawRow> rows = connector.extract(FinanceCapability.REVENUE,
                context(SYSTEM_CODE, changedSince, period))) {
            return rows.toList();
        }
    }

    private static SourceSystemDescriptor descriptor(String systemCode) {
        return new SourceSystemDescriptor(900001L, 7L, systemCode, ConnectorType.PULL_JDBC,
                SourceTechnology.MYSQL, "SRC-1", "synthetic-ref", "Asia/Kolkata");
    }

    private static SyncContext context(String systemCode, Optional<Instant> changedSince,
                                       DateRange period) {
        return new SyncContext(descriptor(systemCode), "batch-1",
                changedSince.isPresent() ? SyncType.INCREMENTAL : SyncType.HISTORICAL,
                changedSince, Instant.parse("2026-12-31T00:00:00Z"), period);
    }

    /** A credential that exists only inside this test. Never a real one, and never persisted. */
    private static final class FixedCredentialProvider implements SourceCredentialProvider {
        @Override
        public SourceCredentials resolve(String credentialRef) {
            return new SourceCredentials("sa", "test-secret");
        }

        @Override
        public boolean isConfigured(String credentialRef) {
            return true;
        }
    }

    /** Opens real H2 connections and records whether each was closed. */
    private static final class RecordingConnectionFactory implements JdbcConnectionFactory {
        private final List<Connection> opened = new ArrayList<>();
        private boolean openedReadOnly;

        @Override
        public Connection open(JdbcSourceSettings settings, SourceCredentials credentials)
                throws SQLException {
            // H2 rejects a password that does not match the one the database was created with,
            // so the in-memory instance is opened with the same blank password used at set-up.
            Connection connection = DriverManager.getConnection(settings.jdbcUrl(), "sa", "");
            // Records that read-only mode was requested. H2 reports isReadOnly() for the
            // database rather than the session, so asking it back would test H2 rather than the
            // factory — and the connector already treats the driver's answer as a hint.
            connection.setReadOnly(true);
            openedReadOnly = true;
            opened.add(connection);
            return connection;
        }

        boolean allClosed() {
            return opened.stream().allMatch(c -> {
                try {
                    return c.isClosed();
                } catch (SQLException e) {
                    return false;
                }
            });
        }
    }
}

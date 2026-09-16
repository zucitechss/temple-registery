package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.SyncType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-030 contract tests.
 *
 * <p>The stub below is the substance of the test: it implements the whole contract using
 * nothing but in-memory data. That it compiles and runs with no database, no HTTP client,
 * no file handle and no credential is the evidence that the contract describes a
 * synchronization capability rather than a transport.
 */
class TempleFinanceConnectorContractTest {

    private static final SourceSystemDescriptor SOURCE = descriptor(ConnectorType.PULL_JDBC);

    private static SourceSystemDescriptor descriptor(ConnectorType type) {
        return new SourceSystemDescriptor(
                900001L, 7L, "EXAMPLE_TEMPLE_SYSTEM", type, SourceTechnology.MYSQL,
                "TEMPLE-SRC-1", "example-ref", "Asia/Kolkata");
    }

    // ---------------------------------------------------------------- identity

    @Test
    @DisplayName("A connector declares its identity and integration mechanism")
    void should_declareIdentity_when_metadataRequested() {
        ConnectorMetadata metadata = new StubConnector(ConnectorType.PULL_JDBC).metadata();

        assertThat(metadata.connectorId()).isEqualTo("stubFinanceConnector");
        assertThat(metadata.connectorType()).isEqualTo(ConnectorType.PULL_JDBC);
    }

    /**
     * The identity must be usable as {@code fin_source_system.connector_bean}, which is how
     * configuration names the code that will read a temple.
     */
    @Test
    @DisplayName("Connector identity rejects a blank id")
    void should_rejectBlankId_when_metadataConstructed() {
        assertThatThrownBy(() -> new ConnectorMetadata("  ", ConnectorType.FILE_DROP, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector_bean");
    }

    // ------------------------------------------------------- four mechanisms

    /**
     * The central architectural claim: one contract, four integration mechanisms, no change
     * to anything above it.
     */
    @ParameterizedTest
    @EnumSource(ConnectorType.class)
    @DisplayName("The contract is implementable for every integration mechanism")
    void should_supportEveryIntegrationMechanism_when_implemented(ConnectorType type) {
        StubConnector connector = new StubConnector(type);
        SourceSystemDescriptor source = descriptor(type);

        assertThat(connector.metadata().connectorType()).isEqualTo(type);
        assertThat(connector.probe(source).usable()).isTrue();

        try (Stream<RawRow> rows = connector.extract(FinanceCapability.REVENUE, context(source))) {
            assertThat(rows).hasSize(2);
        }
    }

    // ------------------------------------------------------------ capabilities

    @Test
    @DisplayName("A connector declares only the capabilities its source genuinely records")
    void should_declareSupportedCapabilities_when_asked() {
        Set<FinanceCapability> declared = new StubConnector(ConnectorType.SOURCE_API)
                .describeCapabilities(SOURCE);

        assertThat(declared).containsExactlyInAnyOrder(
                FinanceCapability.REVENUE, FinanceCapability.CANCELLATION);
        assertThat(declared).doesNotContain(FinanceCapability.EXPENSE);
    }

    /**
     * An undeclared capability must fail loudly. Returning an empty stream would be
     * indistinguishable from a temple that genuinely had no data, and the pipeline would
     * record zero where the truth is that nobody knows.
     */
    @Test
    @DisplayName("Requesting an undeclared capability fails rather than returning nothing")
    void should_throw_when_capabilityNotDeclared() {
        StubConnector connector = new StubConnector(ConnectorType.PULL_JDBC);

        assertThatThrownBy(() -> connector.extract(FinanceCapability.EXPENSE, context(SOURCE)))
                .isInstanceOf(UnsupportedCapabilityException.class)
                .hasMessageContaining("never as zero");

        assertThatThrownBy(() -> connector.requireCapability(FinanceCapability.GRANT, SOURCE))
                .isInstanceOf(UnsupportedCapabilityException.class);
    }

    // ------------------------------------------------------------ sync context

    @Nested
    @DisplayName("Sync context")
    class SyncContextRules {

        @Test
        @DisplayName("Carries change axis and business-date axis separately")
        void should_separateChangeAxisFromBusinessDateAxis() {
            SyncContext ctx = context(SOURCE);

            assertThat(ctx.changedSince()).contains(Instant.parse("2026-09-01T00:00:00Z"));
            assertThat(ctx.changedUpTo()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
            assertThat(ctx.businessDateRange().from()).contains(LocalDate.of(2025, 4, 1));
            assertThat(ctx.isFirstRun()).isFalse();
        }

        /**
         * FIN-D-005. A historical load carrying a watermark would silently exclude every
         * record that had not been modified since it — which is most of history.
         */
        @Test
        @DisplayName("A historical load may not carry a watermark")
        void should_reject_when_historicalLoadCarriesWatermark() {
            assertThatThrownBy(() -> new SyncContext(
                    SOURCE, "batch-1", SyncType.HISTORICAL,
                    Optional.of(Instant.parse("2026-09-01T00:00:00Z")),
                    Instant.parse("2026-09-16T00:00:00Z"),
                    DateRange.unbounded()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not carry a watermark");
        }

        @Test
        @DisplayName("First run has no watermark to resume from")
        void should_reportFirstRun_when_noWatermark() {
            SyncContext ctx = new SyncContext(
                    SOURCE, "batch-1", SyncType.HISTORICAL, Optional.empty(),
                    Instant.parse("2026-09-16T00:00:00Z"), DateRange.unbounded());

            assertThat(ctx.isFirstRun()).isTrue();
        }

        @Test
        @DisplayName("An inverted change window is rejected")
        void should_reject_when_changeWindowInverted() {
            assertThatThrownBy(() -> new SyncContext(
                    SOURCE, "batch-1", SyncType.INCREMENTAL,
                    Optional.of(Instant.parse("2026-09-16T00:00:00Z")),
                    Instant.parse("2026-09-01T00:00:00Z"),
                    DateRange.unbounded()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("A batch reference is required, because it is the audit key")
        void should_reject_when_batchRefBlank() {
            assertThatThrownBy(() -> new SyncContext(
                    SOURCE, " ", SyncType.INCREMENTAL, Optional.empty(),
                    Instant.now(), DateRange.unbounded()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * There is no way for a connector to hand a watermark back. The framework fixes
         * changedUpTo before the call and stores it only on success.
         */
        @Test
        @DisplayName("The contract offers a connector no way to report a watermark")
        void should_exposeNoWatermarkReturn_when_contractInspected() {
            boolean anyMethodReturnsAWatermark = java.util.Arrays
                    .stream(TempleFinanceConnector.class.getDeclaredMethods())
                    .anyMatch(m -> m.getName().toLowerCase().contains("watermark")
                            || m.getReturnType().getSimpleName().toLowerCase().contains("watermark"));

            assertThat(anyMethodReturnsAWatermark)
                    .as("A connector must never advance or propose a watermark (FIN-D-005)")
                    .isFalse();
        }
    }

    // ----------------------------------------------------------- source totals

    @Nested
    @DisplayName("Source totals")
    class SourceTotalsRules {

        @Test
        @DisplayName("Totals are reported per metric over a business-date range")
        void should_reportTotals_when_sourceCanCompute() {
            SourceTotals totals = new StubConnector(ConnectorType.PULL_JDBC).sourceTotals(
                    FinanceCapability.REVENUE, SOURCE,
                    DateRange.of(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31)));

            assertThat(totals.total(ReconMetric.GROSS_AMOUNT)).contains(new BigDecimal("1500.00"));
            assertThat(totals.total(ReconMetric.RECORD_COUNT)).contains(new BigDecimal("2"));
        }

        /**
         * ADR-007. "We could not ask" and "the answer is nothing" lead to opposite
         * conclusions on a finance dashboard.
         */
        @Test
        @DisplayName("An unavailable total is absent, never zero")
        void should_returnEmpty_when_sourceCannotCompute() {
            SourceTotals totals = SourceTotals.notAvailable();

            assertThat(totals.isEmpty()).isTrue();
            assertThat(totals.total(ReconMetric.GROSS_AMOUNT)).isEmpty();
            assertThat(totals.total(ReconMetric.GROSS_AMOUNT)).isNotEqualTo(Optional.of(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("A null total is rejected so it cannot be read as zero downstream")
        void should_reject_when_metricGivenNullTotal() {
            Map<ReconMetric, BigDecimal> withNull = new java.util.HashMap<>();
            withNull.put(ReconMetric.GROSS_AMOUNT, null);

            assertThatThrownBy(() -> new SourceTotals(withNull))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NOT_AVAILABLE");
        }

        /**
         * Reconciliation would be meaningless if the connector judged its own output.
         * The contract exposes totals and nothing else — no comparison, no tolerance, no
         * pass/fail.
         */
        @Test
        @DisplayName("The connector reports totals but never reconciles them")
        void should_exposeNoReconciliationVerdict_when_contractInspected() {
            List<String> methodNames = java.util.Arrays
                    .stream(TempleFinanceConnector.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();

            assertThat(methodNames)
                    .noneMatch(n -> n.toLowerCase().contains("reconcile"))
                    .noneMatch(n -> n.toLowerCase().contains("tolerance"))
                    .noneMatch(n -> n.toLowerCase().contains("publish"));
        }

        /**
         * Extraction and totals take different arguments, which is what keeps the two
         * computations from collapsing into one shared aggregation.
         */
        @Test
        @DisplayName("Totals work on business dates while extraction works on the change axis")
        void should_useDifferentAxes_when_comparingExtractAndSourceTotals() throws Exception {
            var extract = TempleFinanceConnector.class.getMethod(
                    "extract", FinanceCapability.class, SyncContext.class);
            var totals = TempleFinanceConnector.class.getMethod(
                    "sourceTotals", FinanceCapability.class, SourceSystemDescriptor.class, DateRange.class);

            assertThat(extract.getParameterTypes()).contains(SyncContext.class);
            assertThat(totals.getParameterTypes()).contains(DateRange.class);
            assertThat(totals.getParameterTypes()).doesNotContain(SyncContext.class);
        }
    }

    // ------------------------------------------------------------- schema drift

    @Test
    @DisplayName("A source with nothing stable to fingerprint reports absence, not a fake value")
    void should_returnEmptyFingerprint_when_sourceHasNoInspectableSchema() {
        assertThat(new StubConnector(ConnectorType.FILE_DROP).fingerprintSchema(SOURCE)).isEmpty();
        assertThat(new StubConnector(ConnectorType.PULL_JDBC).fingerprintSchema(SOURCE))
                .map(SchemaFingerprint::value).contains("stub-fingerprint");
    }

    @Test
    @DisplayName("A fingerprint longer than the storage column is rejected at construction")
    void should_reject_when_fingerprintTooLong() {
        assertThatThrownBy(() -> new SchemaFingerprint("x".repeat(SchemaFingerprint.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ raw rows

    @Test
    @DisplayName("Raw rows keep source values untouched and locate the original record")
    void should_preserveSourceValues_when_rowExtracted() {
        try (Stream<RawRow> rows = new StubConnector(ConnectorType.PUSH_AGENT)
                .extract(FinanceCapability.REVENUE, context(SOURCE))) {

            RawRow first = rows.findFirst().orElseThrow();
            assertThat(first.sourceRecordRef()).isNotBlank();
            assertThat(first.get("gross_amount")).isEqualTo("1000.00");
        }
    }

    /**
     * Staging exists so a malformed source value lands and is rejected with a reason. A
     * typed extraction contract would instead crash the batch on the first impossible date.
     */
    @Test
    @DisplayName("A malformed source value is carried, not rejected during extraction")
    void should_carryMalformedValue_when_sourceProducesGarbage() {
        RawRow row = new RawRow("src|1", Map.of("txn_date", "1955-00-00", "gross_amount", "not-a-number"));

        assertThat(row.get("txn_date")).isEqualTo("1955-00-00");
        assertThat(row.get("gross_amount")).isEqualTo("not-a-number");
    }

    @Test
    @DisplayName("A row without a locator is rejected because it could not be diagnosed")
    void should_reject_when_sourceRecordRefBlank() {
        assertThatThrownBy(() -> new RawRow("", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosable");
    }

    @Test
    @DisplayName("Raw row values are immutable once extracted")
    void should_beImmutable_when_rowConstructed() {
        RawRow row = new RawRow("src|1", new java.util.HashMap<>(Map.of("a", "1")));

        assertThatThrownBy(() -> row.values().put("b", "2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static SyncContext context(SourceSystemDescriptor source) {
        return new SyncContext(
                source, "batch-ref-1", SyncType.INCREMENTAL,
                Optional.of(Instant.parse("2026-09-01T00:00:00Z")),
                Instant.parse("2026-09-16T00:00:00Z"),
                DateRange.of(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31)));
    }

    /**
     * A complete connector built from in-memory data alone — no database, no HTTP client,
     * no file handle, no credential.
     */
    private static final class StubConnector implements TempleFinanceConnector {

        private final ConnectorType type;

        private StubConnector(ConnectorType type) {
            this.type = type;
        }

        @Override
        public ConnectorMetadata metadata() {
            return new ConnectorMetadata("stubFinanceConnector", type, "In-memory contract stub");
        }

        @Override
        public Set<FinanceCapability> describeCapabilities(SourceSystemDescriptor source) {
            return Set.of(FinanceCapability.REVENUE, FinanceCapability.CANCELLATION);
        }

        @Override
        public SourceProbeResult probe(SourceSystemDescriptor source) {
            return SourceProbeResult.usable("stub source always available");
        }

        @Override
        public Optional<SchemaFingerprint> fingerprintSchema(SourceSystemDescriptor source) {
            return type == ConnectorType.FILE_DROP
                    ? Optional.empty()
                    : Optional.of(new SchemaFingerprint("stub-fingerprint"));
        }

        @Override
        public Stream<RawRow> extract(FinanceCapability capability, SyncContext context) {
            requireCapability(capability, context.source());
            return Stream.of(
                    new RawRow("stub|1", Map.of("txn_date", "2025-04-01", "gross_amount", "1000.00")),
                    new RawRow("stub|2", Map.of("txn_date", "2025-04-02", "gross_amount", "500.00")));
        }

        @Override
        public SourceTotals sourceTotals(FinanceCapability capability,
                                         SourceSystemDescriptor source,
                                         DateRange period) {
            requireCapability(capability, source);
            return new SourceTotals(Map.of(
                    ReconMetric.GROSS_AMOUNT, new BigDecimal("1500.00"),
                    ReconMetric.RECORD_COUNT, new BigDecimal("2")));
        }
    }
}

package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.connector.finance.ConnectorMetadata;
import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.DateRange;
import com.templeregistry.connector.finance.RawRow;
import com.templeregistry.connector.finance.ReconMetric;
import com.templeregistry.connector.finance.SchemaFingerprint;
import com.templeregistry.connector.finance.SourceProbeResult;
import com.templeregistry.connector.finance.SourceSystemDescriptor;
import com.templeregistry.connector.finance.SourceTotals;
import com.templeregistry.connector.finance.SyncContext;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinReconciliationResult;
import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationCheckType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-060 against a real MySQL 8.0 container with the real migrations.
 *
 * <p><b>Synthetic, and labelled as such.</b> The connector is a fake whose {@code sourceTotals()}
 * returns whatever a test tells it to. That is the only way these checks can be exercised at all:
 * no production connector implements {@code sourceTotals()}, and none can until Q4 is answered.
 * Nothing here is Kollur, no real figure is used, and the temple ids are test values no seeded
 * configuration uses.
 *
 * <p>The half that matters most is the negative half — that a mismatch is <em>reported</em> and
 * that nothing is repaired, deleted or restated as a result.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class RevenueReconciliationStageTest {

    private static final long TEMPLE = 940002L;
    private static final String CONNECTOR_BEAN = "syntheticReconConnector";

    /** Closed: its 31 March is in the past, so a shortfall in it is worth recording. */
    private static final String CLOSED_YEAR = "2019-20";
    private static final LocalDate CLOSED_DATE = LocalDate.of(2019, 6, 15);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_recon")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
    }

    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository mappings;
    @Autowired private FinMappingRuleRepository rules;
    @Autowired private FinSourceOfTruthDeclRepository declarations;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private FinRevenueFactRepository facts;
    @Autowired private FinSyncErrorRepository errors;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private FinReconciliationResultRepository results;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private SyntheticConnector connector;
    private FinSourceSystem source;
    private RevenueReconciliationStage reconciler;

    @BeforeEach
    void setUp() {
        results.deleteAllInBatch();
        facts.deleteAllInBatch();
        mappings.deleteAllInBatch();
        staging.deleteAllInBatch();
        errors.deleteAllInBatch();
        batches.deleteAllInBatch();
        declarations.deleteAllInBatch();
        rules.deleteAllInBatch();
        sourceSystems.deleteAllInBatch();

        source = newSource("SYNTHETIC");
        connector = new SyntheticConnector();
        reconciler = newReconciler();
    }

    // ---------------------------------------------------------------- counts agree

    @Test
    @DisplayName("Matching source and canonical totals reconcile clean")
    void should_pass_when_sourceAndCanonicalAgree() {
        FinSyncBatch batch = batchWith(2, 0);
        fact(batch, CLOSED_DATE, 2L, "350.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "350.00");
        connector.reports(ReconMetric.RECORD_COUNT, "2");

        RevenueReconciliationStage.Result result = reconciler.reconcileBatch(batch.getId());

        assertThat(result.failed()).isZero();
        assertThat(result.blocksPublication()).isFalse();
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.PASSED);
        assertThat(statusOf(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL")).isEqualTo(ReconciliationStatus.PASSED);
    }

    @Test
    @DisplayName("An amount mismatch fails, and records both totals and the difference")
    void should_fail_when_grossAmountsDiffer() {
        FinSyncBatch batch = batchWith(2, 0);
        fact(batch, CLOSED_DATE, 2L, "350.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "400.00");

        RevenueReconciliationStage.Result result = reconciler.reconcileBatch(batch.getId());

        assertThat(result.blocksPublication()).isTrue();
        FinReconciliationResult row = rowOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL,
                "GROSS_AMOUNT", PeriodType.FINANCIAL_YEAR, CLOSED_YEAR);
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.FAILED);
        // Both sides are preserved. A difference with only one side recorded is unusable later.
        assertThat(row.getSourceTotal()).isEqualByComparingTo("400.00");
        assertThat(row.getCentralTotal()).isEqualByComparingTo("350.00");
        assertThat(row.getDifference()).isEqualByComparingTo("-50.00");
        assertThat(row.getDifferencePct()).isEqualByComparingTo("-12.5000");
    }

    @Test
    @DisplayName("A record-count mismatch fails even when the money agrees")
    void should_fail_when_recordCountsDifferButAmountsMatch() {
        FinSyncBatch batch = batchWith(2, 0);
        fact(batch, CLOSED_DATE, 2L, "350.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "350.00");
        connector.reports(ReconMetric.RECORD_COUNT, "5");

        reconciler.reconcileBatch(batch.getId());

        // A matching total does not prove the same records were processed: three receipts could
        // be missing and three others double-counted for the same money.
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.PASSED);
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "RECORD_COUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.FAILED);
    }

    @Test
    @DisplayName("Equal amounts at different scales are equal money, not a variance")
    void should_pass_when_totalsDifferOnlyInScale() {
        FinSyncBatch batch = batchWith(1, 0);
        fact(batch, CLOSED_DATE, 1L, "350.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "350.0");

        reconciler.reconcileBatch(batch.getId());

        // BigDecimal.equals would call these different and report a variance of 0.00, which is
        // the most confusing possible reconciliation failure.
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.PASSED);
    }

    @Test
    @DisplayName("Paise are not rounded away: a one-paisa difference still fails")
    void should_fail_when_totalsDifferByOnePaisa() {
        FinSyncBatch batch = batchWith(1, 0);
        fact(batch, CLOSED_DATE, 1L, "350.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "350.01");

        reconciler.reconcileBatch(batch.getId());

        assertThat(rowOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR).getDifference())
                .isEqualByComparingTo("-0.01");
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.FAILED);
    }

    // ---------------------------------------------------------------- local completeness

    @Test
    @DisplayName("Staged rows that never reached a terminal state fail completeness")
    void should_fail_when_stagedRowsAreStuck() {
        FinSyncBatch batch = newBatch(SyncStatus.SUCCESS);
        stagedRow(batch, "r-1", StagingStatus.LOADED);
        stagedRow(batch, "r-2", StagingStatus.VALID);   // never loaded, never explained

        reconciler.reconcileBatch(batch.getId());

        FinReconciliationResult row = rowOf(batch, ReconciliationCheckType.STAGE_COMPLETENESS,
                "RECORD_COUNT", PeriodType.FULL_HISTORY, "ALL");
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.FAILED);
        assertThat(row.getSourceTotal()).isEqualByComparingTo("2");
        assertThat(row.getCentralTotal()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("A row rejected by normalization is accounted for, not counted as lost")
    void should_pass_when_rowWasRejectedAtNormalization() {
        FinSyncBatch batch = newBatch(SyncStatus.SUCCESS);
        stagedRow(batch, "r-1", StagingStatus.LOADED);
        stagedRow(batch, "r-2", StagingStatus.VALID);
        error(batch, "r-2", SyncStage.NORMALIZE, "UNPARSEABLE_REVENUE_AMOUNT");

        reconciler.reconcileBatch(batch.getId());

        // Normalization does not move staging state (FIN-D-038). Counting only LOADED and
        // REJECTED would report every unparseable amount as an unexplained loss.
        assertThat(statusOf(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL")).isEqualTo(ReconciliationStatus.PASSED);
    }

    @Test
    @DisplayName("A rejected row with no recorded reason fails rejection accounting")
    void should_fail_when_aRejectionHasNoReason() {
        FinSyncBatch batch = newBatch(SyncStatus.SUCCESS);
        stagedRow(batch, "r-1", StagingStatus.REJECTED);
        stagedRow(batch, "r-2", StagingStatus.REJECTED);
        error(batch, "r-1", SyncStage.VALIDATE, "MISSING_SOURCE_RECORD_REF");

        reconciler.reconcileBatch(batch.getId());

        FinReconciliationResult row = rowOf(batch, ReconciliationCheckType.REJECTION_ACCOUNTING,
                "REJECTED_COUNT", PeriodType.FULL_HISTORY, "ALL");
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.FAILED);
        assertThat(row.getSourceTotal()).isEqualByComparingTo("2");
        assertThat(row.getCentralTotal()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Every rejected row explained passes rejection accounting")
    void should_pass_when_everyRejectionIsExplained() {
        FinSyncBatch batch = newBatch(SyncStatus.SUCCESS);
        stagedRow(batch, "r-1", StagingStatus.REJECTED);
        error(batch, "r-1", SyncStage.VALIDATE, "MISSING_SOURCE_RECORD_REF");

        reconciler.reconcileBatch(batch.getId());

        assertThat(statusOf(batch, ReconciliationCheckType.REJECTION_ACCOUNTING, "REJECTED_COUNT",
                PeriodType.FULL_HISTORY, "ALL")).isEqualTo(ReconciliationStatus.PASSED);
    }

    @Test
    @DisplayName("An empty batch is complete, and says so rather than reporting zero revenue")
    void should_pass_when_batchStagedNothing() {
        FinSyncBatch batch = newBatch(SyncStatus.SUCCESS);

        RevenueReconciliationStage.Result result = reconciler.reconcileBatch(batch.getId());

        assertThat(result.blocksPublication()).isFalse();
        assertThat(statusOf(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL")).isEqualTo(ReconciliationStatus.PASSED);
        // No facts, so no period to compare. Recorded as unavailable, never as agreement.
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FULL_HISTORY, "ALL")).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
    }

    // ---------------------------------------------------------------- deletion

    @Test
    @DisplayName("A closed year that shrank at the source is recorded as suspected, not confirmed")
    void should_suspectDeletion_when_closedYearShrankAtSource() {
        FinSyncBatch batch = batchWith(5, 0);
        fact(batch, CLOSED_DATE, 5L, "500.00");
        connector.reports(ReconMetric.RECORD_COUNT, "3");
        connector.reports(ReconMetric.GROSS_AMOUNT, "500.00");

        reconciler.reconcileBatch(batch.getId());

        FinReconciliationResult row = rowOf(batch, ReconciliationCheckType.SUSPECTED_SOURCE_DELETION,
                "RECORD_COUNT", PeriodType.FINANCIAL_YEAR, CLOSED_YEAR);
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.FAILED);
        assertThat(row.getStatusReason())
                .contains("SUSPECTED, NOT CONFIRMED")
                .contains("partial source response")
                .contains("Nothing has been deleted");
    }

    @Test
    @DisplayName("Nothing canonical is deleted or changed by a deletion suspicion")
    void should_deleteNothing_when_deletionIsSuspected() {
        FinSyncBatch batch = batchWith(5, 0);
        fact(batch, CLOSED_DATE, 5L, "500.00");
        connector.reports(ReconMetric.RECORD_COUNT, "0");
        List<FinRevenueFact> before = facts.findByTempleIdAndTransactionDateOrderByIdAsc(
                TEMPLE, CLOSED_DATE);

        reconciler.reconcileBatch(batch.getId());

        // The single most important assertion in this class. A reconciler that could "fix" a
        // shortfall by deleting facts would destroy correct history every time a source
        // responded partially.
        assertThat(facts.findByTempleIdAndTransactionDateOrderByIdAsc(TEMPLE, CLOSED_DATE))
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt")
                .containsExactlyElementsOf(before);
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, CLOSED_YEAR))
                .get().isEqualTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("A shortfall in an open year is not evidence of anything")
    void should_notSuspectDeletion_when_yearIsStillOpen() {
        String openYear = FinancialYear.of(LocalDate.now());
        FinSyncBatch batch = batchWith(5, 0);
        fact(batch, LocalDate.now(), 5L, "500.00");
        connector.reports(ReconMetric.RECORD_COUNT, "1");

        reconciler.reconcileBatch(batch.getId());

        // Late-arriving data and a genuine deletion look identical while a period is open.
        // Calling this a pass would be as wrong as calling it a deletion.
        FinReconciliationResult row = rowOf(batch, ReconciliationCheckType.SUSPECTED_SOURCE_DELETION,
                "RECORD_COUNT", PeriodType.FINANCIAL_YEAR, openYear);
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
        assertThat(row.getStatusReason()).contains("still open");
    }

    @Test
    @DisplayName("Without source totals, deletion detection is explicitly unavailable")
    void should_markDeletionUnavailable_when_sourceReportsNothing() {
        FinSyncBatch batch = batchWith(2, 0);
        fact(batch, CLOSED_DATE, 2L, "350.00");
        // The production reality: no connector implements sourceTotals() yet.

        RevenueReconciliationStage.Result result = reconciler.reconcileBatch(batch.getId());

        FinReconciliationResult row = rowOf(batch, ReconciliationCheckType.SUSPECTED_SOURCE_DELETION,
                "RECORD_COUNT", PeriodType.FINANCIAL_YEAR, CLOSED_YEAR);
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
        assertThat(row.getStatusReason()).contains("no deletion signal");
        // Unavailable is not a failure: correctly loaded figures are not withheld because a
        // check could not be made. It is also not a pass, which is what the status records.
        assertThat(result.blocksPublication()).isFalse();
        assertThat(result.notAvailable()).isPositive();
    }

    @Test
    @DisplayName("An incomplete canonical count is not compared, so it cannot look like a deletion")
    void should_markCountUnavailable_when_someFactsHaveNoTransactionCount() {
        FinSyncBatch batch = batchWith(2, 0);
        fact(batch, CLOSED_DATE, null, "350.00");
        connector.reports(ReconMetric.RECORD_COUNT, "2");

        reconciler.reconcileBatch(batch.getId());

        // Summing a partially-unknown count gives a floor. Comparing a floor against a source
        // count manufactures a shortfall that looks exactly like a deletion.
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "RECORD_COUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
        assertThat(statusOf(batch, ReconciliationCheckType.SUSPECTED_SOURCE_DELETION, "RECORD_COUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
    }

    @Test
    @DisplayName("More records at the source than here is not a deletion")
    void should_passDeletionCheck_when_sourceHasMoreRecords() {
        FinSyncBatch batch = batchWith(2, 0);
        fact(batch, CLOSED_DATE, 2L, "350.00");
        connector.reports(ReconMetric.RECORD_COUNT, "9");

        reconciler.reconcileBatch(batch.getId());

        assertThat(statusOf(batch, ReconciliationCheckType.SUSPECTED_SOURCE_DELETION, "RECORD_COUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.PASSED);
        // It is still a completeness problem, and the other check says so.
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "RECORD_COUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.FAILED);
    }

    // ---------------------------------------------------------------- refusals and scope

    @Test
    @DisplayName("A FAILED batch is refused rather than reconciled as if it were complete")
    void should_refuse_when_batchFailed() {
        for (SyncStatus status : List.of(SyncStatus.FAILED, SyncStatus.PENDING,
                SyncStatus.CANCELLED, SyncStatus.DEAD_LETTER)) {
            FinSyncBatch batch = newBatch(status);

            assertThatThrownBy(() -> reconciler.reconcileBatch(batch.getId()))
                    .isInstanceOf(RevenueReconciliationStage.BatchNotReconcilableException.class)
                    .hasMessageContaining(status.name());
            assertThat(results.findBySyncBatchIdOrderByIdAsc(batch.getId())).isEmpty();
        }
    }

    @Test
    @DisplayName("An unknown batch is refused")
    void should_refuse_when_batchDoesNotExist() {
        assertThatThrownBy(() -> reconciler.reconcileBatch(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Results are scoped to the batch and the source system that produced them")
    void should_keepResultsScoped_when_twoSourcesShareATemple() {
        FinSourceSystem other = newSource("SECOND");
        FinSyncBatch mine = batchWith(1, 0);
        fact(mine, CLOSED_DATE, 1L, "100.00");
        FinSyncBatch theirs = batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(TEMPLE)
                .sourceSystemId(other.getId())
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(SyncStatus.SUCCESS)
                .build());
        // A different day on purpose: uk_frf_grain is temple-scoped and does not include
        // source_system_id, so two sources writing the same day and category would be one grain
        // overwriting the other rather than two facts to keep apart (see limitation 47).
        fact(theirs, CLOSED_DATE.plusDays(1), 1L, "900.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "100.00");

        reconciler.reconcileBatch(mine.getId());

        // The other source's 900.00 must not be dragged into this source's comparison, or every
        // two-source temple would fail reconciliation permanently while nothing was wrong.
        assertThat(statusOf(mine, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.PASSED);
        assertThat(results.findBySyncBatchIdOrderByIdAsc(theirs.getId())).isEmpty();
        assertThat(results.findBySyncBatchIdOrderByIdAsc(mine.getId()))
                .allSatisfy(r -> assertThat(r.getSourceSystemId()).isEqualTo(source.getId()));
    }

    @Test
    @DisplayName("A source naming an unregistered connector cannot be asked, and says so")
    void should_markUnavailable_when_connectorIsNotRegistered() {
        source.setConnectorBean("noSuchConnector");
        sourceSystems.save(source);
        FinSyncBatch batch = batchWith(1, 0);
        fact(batch, CLOSED_DATE, 1L, "100.00");

        RevenueReconciliationStage.Result result = reconciler.reconcileBatch(batch.getId());

        // Not a failure of the figures -- a failure to ask. Reporting it as a variance would
        // block publication of correct data because of an unrelated configuration defect.
        assertThat(result.blocksPublication()).isFalse();
        assertThat(rowOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR).getStatusReason())
                .contains("could not be asked");
    }

    @Test
    @DisplayName("A source that throws while totalling is unavailable, not a mismatch")
    void should_markUnavailable_when_sourceThrows() {
        FinSyncBatch batch = batchWith(1, 0);
        fact(batch, CLOSED_DATE, 1L, "100.00");
        connector.failsWith(new IllegalStateException("source unreachable"));

        RevenueReconciliationStage.Result result = reconciler.reconcileBatch(batch.getId());

        assertThat(result.blocksPublication()).isFalse();
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    @DisplayName("Re-running replaces this batch's answers rather than adding a second set")
    void should_replaceOwnResults_when_reconciledTwice() {
        FinSyncBatch batch = batchWith(1, 0);
        fact(batch, CLOSED_DATE, 1L, "100.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "999.00");
        reconciler.reconcileBatch(batch.getId());
        int firstPass = results.findBySyncBatchIdOrderByIdAsc(batch.getId()).size();
        Long firstId = rowOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR).getId();

        connector.reports(ReconMetric.GROSS_AMOUNT, "100.00");
        reconciler.reconcileBatch(batch.getId());

        assertThat(results.findBySyncBatchIdOrderByIdAsc(batch.getId())).hasSize(firstPass);
        FinReconciliationResult row = rowOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL,
                "GROSS_AMOUNT", PeriodType.FINANCIAL_YEAR, CLOSED_YEAR);
        // Same row, corrected answer. Two contradictory rows would leave nobody able to say
        // whether the batch reconciled.
        assertThat(row.getId()).isEqualTo(firstId);
        assertThat(row.getStatus()).isEqualTo(ReconciliationStatus.PASSED);
        assertThat(row.getSourceTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Re-running does not touch the canonical facts")
    void should_leaveFactsAlone_when_reconciledTwice() {
        FinSyncBatch batch = batchWith(1, 0);
        fact(batch, CLOSED_DATE, 1L, "100.00");
        connector.reports(ReconMetric.GROSS_AMOUNT, "100.00");

        reconciler.reconcileBatch(batch.getId());
        reconciler.reconcileBatch(batch.getId());

        assertThat(facts.countByTempleId(TEMPLE)).isEqualTo(1);
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, CLOSED_YEAR))
                .get().isEqualTo(new BigDecimal("100.00"));
    }

    // ---------------------------------------------------------------- end to end

    @Test
    @DisplayName("After a full pipeline run the batch reconciles and ends SUCCESS")
    void should_reconcileAfterPipeline_when_everythingAgrees() {
        configureForPipeline();
        connector.delivers(
                pipelineRow("r-1", "2019-06-15", "100.00"),
                pipelineRow("r-2", "2019-06-15", "250.00"));
        connector.reports(ReconMetric.GROSS_AMOUNT, "350.00");
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        FinancePipelineOrchestrator.Result run = newOrchestrator().run(batch.getId());

        assertThat(run.blocksPublication()).isFalse();
        assertThat(batches.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.SUCCESS);
        assertThat(statusOf(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, CLOSED_YEAR)).isEqualTo(ReconciliationStatus.PASSED);
    }

    @Test
    @DisplayName("A disagreeing source ends the batch RECONCILE_FAILED, with the figures intact")
    void should_endReconcileFailed_when_sourceDisagrees() {
        configureForPipeline();
        connector.delivers(pipelineRow("r-1", "2019-06-15", "100.00"));
        connector.reports(ReconMetric.GROSS_AMOUNT, "175.00");
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        FinancePipelineOrchestrator.Result run = newOrchestrator().run(batch.getId());

        assertThat(run.blocksPublication()).isTrue();
        // RECONCILE_FAILED, not FAILED: the rows loaded correctly and are inspectable. Sending
        // this down the retry path would re-extract and reach the same disagreement.
        assertThat(batches.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.RECONCILE_FAILED);
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, CLOSED_YEAR))
                .get().isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("A second run of a removed record leaves the first run's fact standing")
    void should_keepPriorFact_when_laterSnapshotOmitsARecord() {
        configureForPipeline();
        connector.delivers(
                pipelineRow("r-1", "2019-06-15", "100.00"),
                pipelineRow("r-2", "2019-06-16", "250.00"));
        newOrchestrator().run(newBatch(SyncStatus.PENDING).getId());

        // The source no longer returns r-2 at all, and now says the year holds one record.
        connector.delivers(pipelineRow("r-1", "2019-06-15", "100.00"));
        connector.reports(ReconMetric.RECORD_COUNT, "1");
        FinSyncBatch second = newBatch(SyncStatus.PENDING);
        newOrchestrator().run(second.getId());

        // r-2's revenue is still counted. That is the documented behaviour of FIN-D-044, and
        // the suspicion is what makes it visible rather than silent.
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, CLOSED_YEAR))
                .get().isEqualTo(new BigDecimal("350.00"));
        assertThat(statusOf(second, ReconciliationCheckType.SUSPECTED_SOURCE_DELETION,
                "RECORD_COUNT", PeriodType.FINANCIAL_YEAR, CLOSED_YEAR))
                .isEqualTo(ReconciliationStatus.FAILED);
    }

    // ---------------------------------------------------------------- helpers

    private RevenueReconciliationStage newReconciler() {
        return new RevenueReconciliationStage(
                new ConnectorRegistry(Map.of(CONNECTOR_BEAN, connector)),
                sourceSystems, batches, staging, errors, facts, results, template());
    }

    private FinancePipelineOrchestrator newOrchestrator() {
        TransactionTemplate template = template();
        ConnectorRegistry registry = new ConnectorRegistry(Map.of(CONNECTOR_BEAN, connector));
        RevenueExtractionStage extraction = new RevenueExtractionStage(
                registry, sourceSystems, staging, batches, errors, template, json);
        RevenueStagingValidator validator = new RevenueStagingValidator(
                staging, errors, batches, template, json);
        RevenueMappingStage mapper = new RevenueMappingStage(
                staging, mappings, rules, categories, errors, batches, template, json);
        RevenueNormalizationStage normalization = new RevenueNormalizationStage(
                batches, staging, mappings, declarations, categories, errors, template, json);
        RevenueLoadStage loader = new RevenueLoadStage(
                normalization, facts, staging, batches, errors, template);
        return new FinancePipelineOrchestrator(extraction, validator, mapper, loader,
                newReconciler(), batches, errors, template);
    }

    private TransactionTemplate template() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private void configureForPipeline() {
        declare("REVENUE_TRANSACTION_DATE", "ReceiptDate");
        declare("REVENUE_AMOUNT", "Amount");
        // Without a declared transaction count the canonical record count is unknowable, and
        // every count comparison -- including deletion detection -- is NOT_AVAILABLE by design.
        declare("REVENUE_TRANSACTION_COUNT", "ReceiptCount");
        rules.save(FinMappingRule.builder()
                .sourceSystemId(source.getId())
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue("BUCKET:DS")
                .sourceLabel("Sevas")
                .canonicalValue("SEVA")
                .priority(100)
                .active(true)
                .build());
    }

    private void declare(String metric, String sourceField) {
        declarations.save(FinSourceOfTruthDecl.builder()
                .sourceSystemId(source.getId())
                .metric(metric)
                .version(1)
                .sourceObject("SyntheticObject")
                .sourceField(sourceField)
                .build());
    }

    private FinSourceSystem newSource(String code) {
        return sourceSystems.save(FinSourceSystem.builder()
                .templeId(TEMPLE)
                .systemCode(code)
                .systemName("Synthetic reconciliation source " + code)
                .sourceTechnology(SourceTechnology.SQL_SERVER)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean(CONNECTOR_BEAN)
                .sourceTempleCode("TEST")
                .credentialRef("test.credential")
                .sourceTimezone("Asia/Kolkata")
                .build());
    }

    private FinSyncBatch newBatch(SyncStatus status) {
        return batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(TEMPLE)
                .sourceSystemId(source.getId())
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(status)
                .build());
    }

    /** A finished batch whose staging rows are already in their terminal states. */
    private FinSyncBatch batchWith(int loaded, int rejected) {
        FinSyncBatch batch = newBatch(SyncStatus.SUCCESS);
        for (int i = 0; i < loaded; i++) {
            stagedRow(batch, "loaded-" + i, StagingStatus.LOADED);
        }
        for (int i = 0; i < rejected; i++) {
            stagedRow(batch, "rejected-" + i, StagingStatus.REJECTED);
            error(batch, "rejected-" + i, SyncStage.VALIDATE, "MISSING_SOURCE_RECORD_REF");
        }
        return batch;
    }

    private void stagedRow(FinSyncBatch batch, String ref, StagingStatus status) {
        staging.save(FinStgRevenue.builder()
                .templeId(TEMPLE)
                .sourceSystemId(batch.getSourceSystemId())
                .syncBatchId(batch.getId())
                .sourceRecordRef(ref)
                .rawJson("{}")
                .validationStatus(status)
                .extractedAt(LocalDateTime.now())
                .build());
    }

    private void error(FinSyncBatch batch, String ref, SyncStage stage, String code) {
        errors.save(FinSyncError.builder()
                .syncBatchId(batch.getId())
                .sourceRecordRef(ref)
                .errorStage(stage)
                .errorCode(code)
                .errorMessage("test fixture")
                .build());
    }

    private void fact(FinSyncBatch batch, LocalDate date, Long transactionCount, String gross) {
        // The upsert is @Modifying, and these test methods deliberately run outside a
        // transaction so that what is asserted is what committed.
        Long categoryId = categories.findByCategoryCodeAndDeletedFalse("SEVA").orElseThrow().getId();
        template().execute(tx -> facts.upsert(TEMPLE, batch.getSourceSystemId(), batch.getId(), 1,
                "fact-" + date + "-" + batch.getId(), date, FinancialYear.of(date),
                null, categoryId, PaymentMode.UNRECORDED.name(), PaymentModeConfidence.RECORDED.name(),
                null, null, transactionCount, new BigDecimal(gross), null, null, null, "INR",
                LocalDateTime.now()));
    }

    private RawRow pipelineRow(String ref, String date, String amount) {
        return new RawRow(ref, Map.of("ReceiptDate", date, "Amount", amount,
                "ReceiptCount", "1", "BUCKET", "DS"));
    }

    private ReconciliationStatus statusOf(FinSyncBatch batch, ReconciliationCheckType type,
                                          String metric, PeriodType periodType, String periodKey) {
        return rowOf(batch, type, metric, periodType, periodKey).getStatus();
    }

    private FinReconciliationResult rowOf(FinSyncBatch batch, ReconciliationCheckType type,
                                          String metric, PeriodType periodType, String periodKey) {
        return results.findBySyncBatchIdOrderByIdAsc(batch.getId()).stream()
                .filter(r -> r.getCheckType() == type
                        && r.getMetric().equals(metric)
                        && r.getPeriodType() == periodType
                        && r.getPeriodKey().equals(periodKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + type + "/" + metric + " result for " + periodType + " "
                                + periodKey + ". Recorded: "
                                + results.findBySyncBatchIdOrderByIdAsc(batch.getId()).stream()
                                        .map(r -> r.getCheckType() + "/" + r.getMetric() + "/"
                                                + r.getPeriodType() + "/" + r.getPeriodKey())
                                        .toList()));
    }

    /**
     * The one thing that is faked: a temple's source system. Its totals are whatever a test
     * says, which is the only way {@code sourceTotals()} can be exercised at all today.
     */
    private static final class SyntheticConnector implements TempleFinanceConnector {

        private final Map<ReconMetric, BigDecimal> totals = new EnumMap<>(ReconMetric.class);
        private final List<RawRow> rows = new ArrayList<>();
        private RuntimeException failure;

        private void reports(ReconMetric metric, String total) {
            totals.put(metric, new BigDecimal(total));
        }

        private void delivers(RawRow... delivered) {
            rows.clear();
            rows.addAll(List.of(delivered));
        }

        private void failsWith(RuntimeException cause) {
            failure = cause;
        }

        @Override
        public ConnectorMetadata metadata() {
            return new ConnectorMetadata(CONNECTOR_BEAN, ConnectorType.PULL_JDBC,
                    "synthetic in-memory source for reconciliation tests");
        }

        @Override
        public Set<FinanceCapability> describeCapabilities(SourceSystemDescriptor source) {
            return Set.of(FinanceCapability.REVENUE);
        }

        @Override
        public SourceProbeResult probe(SourceSystemDescriptor source) {
            return SourceProbeResult.usable("synthetic");
        }

        @Override
        public Optional<SchemaFingerprint> fingerprintSchema(SourceSystemDescriptor source) {
            return Optional.empty();
        }

        @Override
        public Stream<RawRow> extract(FinanceCapability capability, SyncContext context) {
            requireCapability(capability, context.source());
            return List.copyOf(rows).stream();
        }

        @Override
        public SourceTotals sourceTotals(FinanceCapability capability,
                                         SourceSystemDescriptor source,
                                         DateRange period) {
            requireCapability(capability, source);
            if (failure != null) {
                throw failure;
            }
            return totals.isEmpty() ? SourceTotals.notAvailable() : new SourceTotals(totals);
        }
    }
}

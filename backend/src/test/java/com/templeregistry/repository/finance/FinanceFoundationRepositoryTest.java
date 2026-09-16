package com.templeregistry.repository.finance;

import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.entity.finance.*;
import com.templeregistry.entity.finance.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 foundation check.
 *
 * <p>Loading the JPA context is itself most of the test: Spring Data validates
 * every derived query method name against the entity model at startup, so a
 * mistyped finder fails here rather than at runtime in the sync worker.
 *
 * <p>The assertions then cover the defaults that carry business meaning -- the
 * ones where a wrong value would be silently harmful rather than obviously
 * broken.
 */
@DataJpaTest
@Import(JpaAuditConfig.class)
@ActiveProfiles("test")
class FinanceFoundationRepositoryTest {

    private static final Long TEMPLE_ID = 300001L;

    @Autowired private FinSourceSystemRepository sourceSystemRepo;
    @Autowired private FinTempleCapabilityRepository capabilityRepo;
    @Autowired private FinSourceOfTruthDeclRepository sourceOfTruthRepo;
    @Autowired private FinMappingRuleRepository mappingRuleRepo;
    @Autowired private FinSyncBatchRepository syncBatchRepo;
    @Autowired private FinSyncErrorRepository syncErrorRepo;
    @Autowired private FinReconciliationResultRepository reconciliationRepo;

    private Long sourceSystemId;

    @BeforeEach
    void seedSourceSystem() {
        FinSourceSystem saved = sourceSystemRepo.save(FinSourceSystem.builder()
                .templeId(TEMPLE_ID)
                .systemCode("KOLSOHAM")
                .systemName("Kollur Sri Mookambika Devi Temple operational system")
                .sourceTechnology(SourceTechnology.SQL_SERVER)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean("kollurFinanceConnector")
                .sourceTempleCode("43")
                .sourceDatabaseName("KOLSOHAM_LOCAL")
                .build());
        sourceSystemId = saved.getId();
    }

    /**
     * Registering a source system must never, by itself, start contacting a temple.
     * The kill switch defaults off so that enabling sync is always a deliberate act.
     */
    @Test
    void should_defaultSyncDisabled_when_sourceSystemRegistered() {
        FinSourceSystem found = sourceSystemRepo
                .findByTempleIdAndSystemCodeAndDeletedFalse(TEMPLE_ID, "KOLSOHAM")
                .orElseThrow();

        assertThat(found.isSyncEnabled()).isFalse();
        assertThat(sourceSystemRepo.findBySyncEnabledTrueAndDeletedFalse()).isEmpty();
        assertThat(found.getSourceTempleCode()).isEqualTo("43");
        assertThat(found.getSourceTimezone()).isEqualTo("Asia/Kolkata");
    }

    /**
     * The schema must be incapable of holding a source credential: the registry
     * runtime is not permitted to reach a temple database (ADR-001). Verified by
     * reflection so that adding a password field later fails this test.
     */
    @Test
    void should_haveNoCredentialOrConnectionFields_when_sourceSystemInspected() {
        var fieldNames = java.util.Arrays.stream(FinSourceSystem.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .map(String::toLowerCase)
                .toList();

        assertThat(fieldNames)
                .noneMatch(n -> n.contains("password"))
                .noneMatch(n -> n.contains("host"))
                .noneMatch(n -> n.contains("port"))
                .noneMatch(n -> n.contains("jdbcurl"))
                .noneMatch(n -> n.contains("connectionstring"));
        assertThat(fieldNames).contains("credentialref");
    }

    /**
     * An unavailable capability carries a user-facing reason. That reason is the
     * thing rendered instead of a zero, so it must survive the round trip.
     */
    @Test
    void should_persistReason_when_capabilityNotAvailable() {
        capabilityRepo.save(FinTempleCapability.builder()
                .templeId(TEMPLE_ID)
                .sourceSystemId(sourceSystemId)
                .capability(FinanceCapability.EXPENSE)
                .availability(DataAvailability.NOT_AVAILABLE)
                .availabilityReason("The source system does not record expenditure.")
                .build());

        FinTempleCapability found = capabilityRepo
                .findByTempleIdAndCapabilityAndDeletedFalse(TEMPLE_ID, FinanceCapability.EXPENSE)
                .orElseThrow();

        assertThat(found.getAvailability()).isEqualTo(DataAvailability.NOT_AVAILABLE);
        assertThat(found.getAvailabilityReason()).contains("does not record expenditure");
        // Coverage dates are absent, not zero-valued, when nothing is recorded.
        assertThat(found.getCoverageFrom()).isNull();
        assertThat(found.getCoverageTo()).isNull();
    }

    /** A partially available capability records where the data actually stops. */
    @Test
    void should_recordCoverageWindow_when_capabilityPartiallyAvailable() {
        capabilityRepo.save(FinTempleCapability.builder()
                .templeId(TEMPLE_ID)
                .sourceSystemId(sourceSystemId)
                .capability(FinanceCapability.NIRANTARA_PAYMENT)
                .availability(DataAvailability.PARTIALLY_AVAILABLE)
                .availabilityReason("Payments are recorded only up to FY2023-24.")
                .coverageFrom(LocalDate.of(2019, 4, 1))
                .coverageTo(LocalDate.of(2024, 3, 31))
                .build());

        FinTempleCapability found = capabilityRepo
                .findByTempleIdAndCapabilityAndDeletedFalse(TEMPLE_ID, FinanceCapability.NIRANTARA_PAYMENT)
                .orElseThrow();

        assertThat(found.getCoverageTo()).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(capabilityRepo.findByTempleIdAndDeletedFalse(TEMPLE_ID)).hasSize(1);
    }

    /**
     * Only one declaration per metric may be in force. Superseding sets
     * effectiveTo on the old version rather than editing it, so a previously
     * published figure stays explicable.
     */
    @Test
    void should_returnOnlyCurrentVersion_when_declarationSuperseded() {
        sourceOfTruthRepo.save(FinSourceOfTruthDecl.builder()
                .sourceSystemId(sourceSystemId)
                .metric("REVENUE_AMOUNT")
                .version(1)
                .sourceObject("DailySevaNew")
                .sourceField("Amount")
                .effectiveFrom(LocalDate.of(2019, 4, 1))
                .effectiveTo(LocalDate.of(2026, 1, 1))
                .build());

        sourceOfTruthRepo.save(FinSourceOfTruthDecl.builder()
                .sourceSystemId(sourceSystemId)
                .metric("REVENUE_AMOUNT")
                .version(2)
                .sourceObject("DailySevaNew")
                .sourceField("Amount")
                .filterPredicate("BillCancled=0 AND Deleteflag=0")
                .effectiveFrom(LocalDate.of(2026, 1, 2))
                .build());

        FinSourceOfTruthDecl current = sourceOfTruthRepo
                .findFirstBySourceSystemIdAndMetricAndEffectiveToIsNullAndDeletedFalseOrderByVersionDesc(
                        sourceSystemId, "REVENUE_AMOUNT")
                .orElseThrow();

        assertThat(current.getVersion()).isEqualTo(2);
        assertThat(current.getFilterPredicate()).contains("BillCancled=0");
        assertThat(sourceOfTruthRepo
                .findBySourceSystemIdAndMetricAndDeletedFalseOrderByVersionDesc(sourceSystemId, "REVENUE_AMOUNT"))
                .hasSize(2);
    }

    /** An inactive mapping rule must not be returned as if it were in force. */
    @Test
    void should_excludeInactiveRules_when_loadingMappings() {
        mappingRuleRepo.save(FinMappingRule.builder()
                .sourceSystemId(sourceSystemId)
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue("430")
                .sourceLabel("HUNDIALS")
                .canonicalValue("HUNDI_DONATION")
                .build());

        mappingRuleRepo.save(FinMappingRule.builder()
                .sourceSystemId(sourceSystemId)
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue("999")
                .canonicalValue("OTHER_INCOME")
                .active(false)
                .build());

        var active = mappingRuleRepo.findBySourceSystemIdAndMappingTypeAndActiveTrueAndDeletedFalse(
                sourceSystemId, MappingType.REVENUE_CATEGORY);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getCanonicalValue()).isEqualTo("HUNDI_DONATION");
        assertThat(mappingRuleRepo.findBySourceSystemIdAndDeletedFalse(sourceSystemId)).hasSize(2);
    }

    /**
     * The watermark of a failed batch must not be mistaken for the authoritative
     * one. A failed run after a successful run would otherwise let the pipeline
     * skip a window it never actually loaded.
     */
    @Test
    void should_distinguishLatestSuccessFromLatestAttempt_when_lastBatchFailed() {
        syncBatchRepo.save(batch(SyncStatus.SUCCESS, "2025-04-30T00:00:00"));
        FinSyncBatch failed = syncBatchRepo.save(batch(SyncStatus.FAILED, null));

        FinSyncBatch latestAttempt = syncBatchRepo
                .findFirstBySourceSystemIdAndCapabilityOrderByIdDesc(sourceSystemId, FinanceCapability.REVENUE)
                .orElseThrow();
        FinSyncBatch latestSuccess = syncBatchRepo
                .findFirstBySourceSystemIdAndCapabilityAndStatusOrderByIdDesc(
                        sourceSystemId, FinanceCapability.REVENUE, SyncStatus.SUCCESS)
                .orElseThrow();

        assertThat(latestAttempt.getId()).isEqualTo(failed.getId());
        assertThat(latestSuccess.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(latestSuccess.getWatermarkAfter()).isEqualTo("2025-04-30T00:00:00");
        assertThat(latestAttempt.getWatermarkAfter()).isNull();
    }

    /** Exhausted batches stop retrying; the rest remain eligible. */
    @Test
    void should_excludeExhaustedBatches_when_findingRetryable() {
        FinSyncBatch eligible = batch(SyncStatus.FAILED, null);
        eligible.setRetryCount(2);
        eligible.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
        syncBatchRepo.save(eligible);

        FinSyncBatch exhausted = batch(SyncStatus.FAILED, null);
        exhausted.setRetryCount(5);
        exhausted.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
        syncBatchRepo.save(exhausted);

        var retryable = syncBatchRepo.findRetryable(SyncStatus.FAILED, LocalDateTime.now());

        assertThat(retryable).hasSize(1);
        assertThat(retryable.get(0).getRetryCount()).isEqualTo(2);
        assertThat(eligible.isRetryable()).isTrue();
        assertThat(exhausted.isRetryable()).isFalse();
    }

    /** Every rejected row is recorded, so a rejection count is always explainable. */
    @Test
    void should_linkErrorsToBatch_when_rowsRejected() {
        FinSyncBatch b = syncBatchRepo.save(batch(SyncStatus.SUCCESS, "2025-04-30T00:00:00"));

        syncErrorRepo.save(FinSyncError.builder()
                .syncBatchId(b.getId())
                .sourceRecordRef("DailySevaNew|2025-04-01|83|1")
                .errorStage(SyncStage.MAP)
                .errorCode("UNMAPPED_SERVICE")
                .errorMessage("No mapping rule for source service code 8811")
                .build());

        assertThat(syncErrorRepo.countBySyncBatchId(b.getId())).isEqualTo(1);
    }

    /**
     * A total the source could not produce is NOT_AVAILABLE with a null total --
     * never a zero, and never a silent pass.
     */
    @Test
    void should_storeNullTotal_when_reconciliationNotAvailable() {
        reconciliationRepo.save(FinReconciliationResult.builder()
                .templeId(TEMPLE_ID)
                .sourceSystemId(sourceSystemId)
                .capability(FinanceCapability.EXPENSE)
                .metric("EXPENSE_AMOUNT")
                .periodType(PeriodType.FINANCIAL_YEAR)
                .periodKey("2025-26")
                .status(ReconciliationStatus.NOT_AVAILABLE)
                .statusReason("The source system does not record expenditure.")
                .checkedAt(LocalDateTime.now())
                .build());

        FinReconciliationResult found = reconciliationRepo
                .findFirstByTempleIdAndMetricAndPeriodTypeAndPeriodKeyOrderByIdDesc(
                        TEMPLE_ID, "EXPENSE_AMOUNT", PeriodType.FINANCIAL_YEAR, "2025-26")
                .orElseThrow();

        assertThat(found.getStatus()).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
        assertThat(found.getSourceTotal()).isNull();
        assertThat(found.getCentralTotal()).isNull();
        assertThat(found.getDifference()).isNull();
    }

    /** Revenue is expected to match exactly: the default tolerance is zero. */
    @Test
    void should_defaultToZeroTolerance_when_reconciliationRecorded() {
        FinReconciliationResult saved = reconciliationRepo.save(FinReconciliationResult.builder()
                .templeId(TEMPLE_ID)
                .sourceSystemId(sourceSystemId)
                .capability(FinanceCapability.REVENUE)
                .metric("REVENUE_AMOUNT")
                .periodType(PeriodType.FINANCIAL_YEAR)
                .periodKey("2025-26")
                .sourceTotal(new BigDecimal("906162936.00"))
                .centralTotal(new BigDecimal("906162936.00"))
                .difference(BigDecimal.ZERO)
                .status(ReconciliationStatus.PASSED)
                .checkedAt(LocalDateTime.now())
                .build());

        assertThat(saved.getTolerancePct()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private FinSyncBatch batch(SyncStatus status, String watermarkAfter) {
        return FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(TEMPLE_ID)
                .sourceSystemId(sourceSystemId)
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .status(status)
                .watermarkAfter(watermarkAfter)
                .build();
    }
}

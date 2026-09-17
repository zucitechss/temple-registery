package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinReconciliationResult;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationCheckType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Source-vs-central verification outcomes. A FAILED row blocks publication. */
@Repository
public interface FinReconciliationResultRepository extends JpaRepository<FinReconciliationResult, Long> {

    /** Latest verification for one period, reported alongside the figure itself. */
    Optional<FinReconciliationResult> findFirstByTempleIdAndMetricAndPeriodTypeAndPeriodKeyOrderByIdDesc(
            Long templeId, String metric, PeriodType periodType, String periodKey);

    List<FinReconciliationResult> findBySyncBatchIdOrderByIdAsc(Long syncBatchId);

    List<FinReconciliationResult> findByTempleIdAndMetricAndPeriodTypeOrderByPeriodKeyAsc(
            Long templeId, String metric, PeriodType periodType);

    /**
     * Writes one check's answer, replacing this batch's previous answer to the same question.
     *
     * <p>Idempotent by {@code uk_frr_batch_check}: re-running reconciliation for a batch updates
     * its rows rather than leaving two contradictory ones. Assignment, never accumulation — the
     * rule FIN-D-041 imposed on the revenue upsert, and for the same reason: an accumulating
     * version would satisfy the constraint perfectly and quietly inflate every total it wrote.
     *
     * <p>{@code created_at} is excluded from the update list, so the first time a question was
     * asked survives every re-check of it.
     *
     * <p>A scheduled re-verification passes a null {@code syncBatchId}. NULL never matches NULL in
     * the unique index, so those rows append instead of replacing — which is what an append-only
     * history of a period's figures needs, and is used here deliberately rather than worked
     * around.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO fin_reconciliation_result
            (temple_id, source_system_id, sync_batch_id, capability, check_type, metric,
             period_type, period_key, source_total, central_total, difference, difference_pct,
             tolerance_pct, status, status_reason, checked_at, created_at)
        VALUES
            (:templeId, :sourceSystemId, :syncBatchId, :capability, :checkType, :metric,
             :periodType, :periodKey, :sourceTotal, :centralTotal, :difference, :differencePct,
             :tolerancePct, :status, :statusReason, :checkedAt, :checkedAt)
        ON DUPLICATE KEY UPDATE
            temple_id        = VALUES(temple_id),
            source_system_id = VALUES(source_system_id),
            source_total     = VALUES(source_total),
            central_total    = VALUES(central_total),
            difference       = VALUES(difference),
            difference_pct   = VALUES(difference_pct),
            tolerance_pct    = VALUES(tolerance_pct),
            status           = VALUES(status),
            status_reason    = VALUES(status_reason),
            checked_at       = VALUES(checked_at)
        """, nativeQuery = true)
    int record(@Param("templeId") Long templeId,
               @Param("sourceSystemId") Long sourceSystemId,
               @Param("syncBatchId") Long syncBatchId,
               @Param("capability") String capability,
               @Param("checkType") String checkType,
               @Param("metric") String metric,
               @Param("periodType") String periodType,
               @Param("periodKey") String periodKey,
               @Param("sourceTotal") BigDecimal sourceTotal,
               @Param("centralTotal") BigDecimal centralTotal,
               @Param("difference") BigDecimal difference,
               @Param("differencePct") BigDecimal differencePct,
               @Param("tolerancePct") BigDecimal tolerancePct,
               @Param("status") String status,
               @Param("statusReason") String statusReason,
               @Param("checkedAt") LocalDateTime checkedAt);

    long countBySyncBatchIdAndStatus(Long syncBatchId, ReconciliationStatus status);

    /**
     * The most recent answer to one question for a period, whichever batch asked it.
     *
     * <p>Source of the "was this figure verified" flag a report carries, and of the prior
     * canonical count a deletion suspicion is measured against.
     */
    Optional<FinReconciliationResult>
        findFirstByTempleIdAndSourceSystemIdAndCheckTypeAndMetricAndPeriodTypeAndPeriodKeyOrderByIdDesc(
            Long templeId, Long sourceSystemId, ReconciliationCheckType checkType, String metric,
            PeriodType periodType, String periodKey);
}

package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** The audit spine: one row per extraction attempt. */
@Repository
public interface FinSyncBatchRepository extends JpaRepository<FinSyncBatch, Long> {

    Optional<FinSyncBatch> findByBatchRef(String batchRef);

    /**
     * Takes ownership of a batch for a pipeline run (FIN-057).
     *
     * <p>Conditional on {@code expected}, which is what makes it a claim rather than a check
     * followed by a write. Two runners racing cannot both succeed: the second update matches no
     * row and returns 0. Reading the status and then setting it would leave a window in which
     * both believe they own the batch, and both would run every stage over the same rows —
     * which the stages would survive, being idempotent, but at twice the cost and with two
     * conflicting sets of counters.
     *
     * @return 1 when this caller claimed the batch, 0 when somebody else holds it or it has
     *         already finished
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE FinSyncBatch b
           SET b.status    = :next,
               b.startedAt = :now,
               b.updatedAt = :now
         WHERE b.id = :id
           AND b.status = :expected
        """)
    int claimForRun(@Param("id") Long id,
                    @Param("expected") SyncStatus expected,
                    @Param("next") SyncStatus next,
                    @Param("now") LocalDateTime now);

    /**
     * Most recent batch for a capability, whatever its outcome. Drives the
     * sync-health fields of report responses.
     */
    Optional<FinSyncBatch> findFirstBySourceSystemIdAndCapabilityOrderByIdDesc(
            Long sourceSystemId, FinanceCapability capability);

    /**
     * Most recent SUCCESSFUL batch, whose watermark is the authoritative one.
     * Deliberately distinct from the method above: a later failed attempt must
     * never advance the watermark or the reported data freshness.
     */
    Optional<FinSyncBatch> findFirstBySourceSystemIdAndCapabilityAndStatusOrderByIdDesc(
            Long sourceSystemId, FinanceCapability capability, SyncStatus status);

    List<FinSyncBatch> findByTempleIdOrderByIdDesc(Long templeId);

    /** Failed batches whose back-off has elapsed and whose retries are not exhausted. */
    @Query("""
        SELECT b FROM FinSyncBatch b
        WHERE b.status = :status
          AND b.retryCount < b.maxRetries
          AND (b.nextRetryAt IS NULL OR b.nextRetryAt <= :now)
        ORDER BY b.nextRetryAt ASC
        """)
    List<FinSyncBatch> findRetryable(@Param("status") SyncStatus status,
                                     @Param("now") LocalDateTime now);

    long countByStatus(SyncStatus status);
}

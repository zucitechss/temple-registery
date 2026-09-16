package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.SyncStage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Row-level rejections, so that a rejected-row count is always explainable. */
@Repository
public interface FinSyncErrorRepository extends JpaRepository<FinSyncError, Long> {

    Page<FinSyncError> findBySyncBatchIdOrderByIdAsc(Long syncBatchId, Pageable pageable);

    long countBySyncBatchId(Long syncBatchId);

    /**
     * Errors a single stage recorded.
     *
     * <p>Needed because the stages do not all record at the same grain. Validation writes one
     * error per rejected row, which is what makes {@code rows_rejected} traceable row by row;
     * mapping writes one per distinct unresolved source value, because a single missing rule
     * can account for a whole batch and forty thousand identical errors would bury it. Counting
     * across stages would mix the two and inflate a financial counter.
     */
    long countBySyncBatchIdAndErrorStage(Long syncBatchId, SyncStage errorStage);

    List<FinSyncError> findBySyncBatchIdAndErrorStageOrderByIdAsc(Long syncBatchId, SyncStage errorStage);

    /**
     * Clears one stage's errors for a batch so a re-run replaces them rather than adding to
     * them. Scoped to a stage so that re-running mapping cannot erase validation's record.
     */
    long deleteBySyncBatchIdAndErrorStage(Long syncBatchId, SyncStage errorStage);
}

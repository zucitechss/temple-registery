package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinSyncError;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Row-level rejections, so that a rejected-row count is always explainable. */
@Repository
public interface FinSyncErrorRepository extends JpaRepository<FinSyncError, Long> {

    Page<FinSyncError> findBySyncBatchIdOrderByIdAsc(Long syncBatchId, Pageable pageable);

    long countBySyncBatchId(Long syncBatchId);
}

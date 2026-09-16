package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinReconciliationResult;
import com.templeregistry.entity.finance.enums.PeriodType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}

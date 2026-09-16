package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Versioned declarations of which source field is authoritative for a metric. */
@Repository
public interface FinSourceOfTruthDeclRepository extends JpaRepository<FinSourceOfTruthDecl, Long> {

    /**
     * The declaration currently in force for a metric. A superseded version has
     * effectiveTo set; exactly one row per metric should have it null.
     */
    Optional<FinSourceOfTruthDecl> findFirstBySourceSystemIdAndMetricAndEffectiveToIsNullAndDeletedFalseOrderByVersionDesc(
            Long sourceSystemId, String metric);

    /** Full history for a metric, newest first. Used when explaining a restatement. */
    List<FinSourceOfTruthDecl> findBySourceSystemIdAndMetricAndDeletedFalseOrderByVersionDesc(
            Long sourceSystemId, String metric);
}

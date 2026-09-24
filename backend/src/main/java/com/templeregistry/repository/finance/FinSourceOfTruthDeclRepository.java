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

    /**
     * Every version of every metric for one source, newest version of each metric first.
     *
     * <p>Superseded rows included on purpose: they are what the versioning is for. An
     * administrator asking why a published figure changed needs the declaration that used to be
     * in force, and a fact loaded under it carries its version number.
     */
    List<FinSourceOfTruthDecl> findBySourceSystemIdAndDeletedFalseOrderByMetricAscVersionDesc(
            Long sourceSystemId);

    /**
     * The highest version ever assigned for a metric, retired rows included.
     *
     * <p>Not filtered by {@code deleted}, deliberately. {@code uk_fsotd_source_metric_version} is
     * {@code (source_system_id, metric, version)} and ignores {@code is_deleted}, so a retired
     * row still holds its number; computing the next version from live rows alone would reuse one
     * and reach the constraint as an opaque server error — the defect slice 140-A hit on the
     * equivalent key for source system codes.
     */
    Optional<FinSourceOfTruthDecl> findFirstBySourceSystemIdAndMetricOrderByVersionDesc(
            Long sourceSystemId, String metric);
}

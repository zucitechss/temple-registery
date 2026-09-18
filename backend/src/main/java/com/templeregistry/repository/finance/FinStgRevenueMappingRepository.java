package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinStgRevenueMapping;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Recorded decisions about what staged source values mean canonically (FIN-054). */
@Repository
public interface FinStgRevenueMappingRepository extends JpaRepository<FinStgRevenueMapping, Long> {

    /**
     * The current decision for one staged row and mapping type, if one has been made.
     *
     * <p>This pair is the idempotency key, enforced by {@code uk_fsrm_row_type}: re-running the
     * stage after a rule correction updates the decision rather than recording a second one.
     */
    Optional<FinStgRevenueMapping> findByStgRevenueIdAndMappingType(
            Long stgRevenueId, MappingType mappingType);

    /**
     * The decisions for a chunk of staged rows, in one query.
     *
     * <p>Normalization reads each record's decision alongside the staged rows it is already
     * scanning (FIN-055). Fetching them one at a time would put a query per record on the
     * pipeline's hot path for no benefit.
     */
    List<FinStgRevenueMapping> findByStgRevenueIdInAndMappingType(
            List<Long> stgRevenueIds, MappingType mappingType);

    long countBySyncBatchIdAndMappingTypeAndOutcome(
            Long syncBatchId, MappingType mappingType, MappingOutcome outcome);

    List<FinStgRevenueMapping> findBySyncBatchIdAndMappingTypeOrderByIdAsc(
            Long syncBatchId, MappingType mappingType);

    /**
     * Every source value of one outcome in a batch, with how many records it cost, worst first.
     *
     * <p>The operational question behind {@code UNMAPPED}: not "which rows failed" but "which
     * missing rule would fix the most". A single absent seva code can account for a whole
     * batch, and one line naming it is more use than forty thousand identical row-level errors.
     */
    @Query("""
        SELECT m.sourceValue, COUNT(m)
          FROM FinStgRevenueMapping m
         WHERE m.syncBatchId = :syncBatchId
           AND m.mappingType = :mappingType
           AND m.outcome     = :outcome
         GROUP BY m.sourceValue
         ORDER BY COUNT(m) DESC, m.sourceValue ASC
        """)
    List<Object[]> summariseBySourceValue(@Param("syncBatchId") Long syncBatchId,
                                          @Param("mappingType") MappingType mappingType,
                                          @Param("outcome") MappingOutcome outcome);

    /**
     * The most recent batch of this source system that has recorded decisions (FIN-054A).
     *
     * <p>The administrative unmapped view is scoped to one batch, and this is how it picks the
     * one. Aggregating across batches would be wrong rather than merely expensive: re-extracting
     * a period stages the same source records again, so a source value present in three batches
     * would be counted three times and an operator would be shown a number that is not the number
     * of records affected.
     *
     * @return empty when nothing has been mapped for this source system yet
     */
    @Query("""
        SELECT MAX(m.syncBatchId)
          FROM FinStgRevenueMapping m
         WHERE m.sourceSystemId = :sourceSystemId
           AND m.mappingType    = :mappingType
        """)
    Optional<Long> findLatestDecidedBatch(@Param("sourceSystemId") Long sourceSystemId,
                                          @Param("mappingType") MappingType mappingType);

    /**
     * Unresolved source values in one batch, with the field each was read from, worst first.
     *
     * <p>{@link #summariseBySourceValue} answers the pipeline's question — which missing rule
     * costs the most records. This answers the administrator's, which needs one thing more: the
     * staged field the value came from. That field is the namespace the new rule must carry, and
     * pre-filling it from an observed value is the whole mitigation for a rule that is accepted,
     * shown as active, and silently never matches.
     */
    @Query("""
        SELECT m.sourceField, m.sourceValue, COUNT(m), MAX(m.mappedAt)
          FROM FinStgRevenueMapping m
         WHERE m.syncBatchId = :syncBatchId
           AND m.mappingType = :mappingType
           AND m.outcome     = :outcome
         GROUP BY m.sourceField, m.sourceValue
         ORDER BY COUNT(m) DESC, m.sourceValue ASC
        """)
    List<Object[]> summariseByFieldAndSourceValue(@Param("syncBatchId") Long syncBatchId,
                                                  @Param("mappingType") MappingType mappingType,
                                                  @Param("outcome") MappingOutcome outcome);
}

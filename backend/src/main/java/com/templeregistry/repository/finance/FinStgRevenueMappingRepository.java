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
}

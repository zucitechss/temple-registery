package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinStgFund;
import com.templeregistry.entity.finance.enums.StagingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Staged Fund rows (V136).
 *
 * <p>Nothing outside the pipeline reads this table. Writes come from the
 * staging step of the Fund ingestion stages; reads are the validator, the
 * mapper and the load, each of which takes a batch id and asks for the rows in
 * the state it is responsible for.
 */
@Repository
public interface FinStgFundRepository extends JpaRepository<FinStgFund, Long> {

    List<FinStgFund> findBySyncBatchIdAndValidationStatus(Long syncBatchId,
                                                      StagingStatus validationStatus);

    List<FinStgFund> findBySyncBatchId(Long syncBatchId);

    long countBySyncBatchIdAndValidationStatus(Long syncBatchId, StagingStatus validationStatus);

    long countBySyncBatchId(Long syncBatchId);
}

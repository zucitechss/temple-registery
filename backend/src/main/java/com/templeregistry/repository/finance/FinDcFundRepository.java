package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinDcFund;
import com.templeregistry.entity.finance.enums.WorkStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DC-approved funds (V125, FR5).
 *
 * <p>{@link #findByTempleIdAndWorkStatus} is the whole of FR6: ongoing works is
 * this report filtered to {@link WorkStatus#IN_PROGRESS}, which is why no
 * work-project table exists.
 */
@Repository
public interface FinDcFundRepository extends JpaRepository<FinDcFund, Long> {

    Optional<FinDcFund> findBySourceSystemIdAndSourceRecordRef(Long sourceSystemId,
                                                                String sourceRecordRef);

    List<FinDcFund> findByTempleIdAndFinancialYear(Long templeId, String financialYear);

    /** FR6. */
    List<FinDcFund> findByTempleIdAndWorkStatus(Long templeId, WorkStatus workStatus);

    List<FinDcFund> findByTempleId(Long templeId);
}

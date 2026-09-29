package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinAggRevenueService;
import com.templeregistry.entity.finance.enums.PeriodType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Service-grained revenue aggregates (V131, FR8 and FR10).
 *
 * <p>Empty until service resolution populates {@code fin_revenue_fact.service_id}
 * (FIN-D-069). The interface exists now because it is part of the schema
 * contract two streams are building against.
 */
@Repository
public interface FinAggRevenueServiceRepository extends JpaRepository<FinAggRevenueService, Long> {

    Optional<FinAggRevenueService> findByTempleIdAndSourceSystemIdAndPeriodTypeAndPeriodKeyAndServiceId(
            Long templeId, Long sourceSystemId, PeriodType periodType, String periodKey, Long serviceId);

    /** FR10: the full split for one period, across every source the temple has. */
    List<FinAggRevenueService> findByTempleIdAndPeriodTypeAndPeriodKey(
            Long templeId, PeriodType periodType, String periodKey);

    List<FinAggRevenueService> findByTempleIdAndSourceSystemIdAndFinancialYear(
            Long templeId, Long sourceSystemId, String financialYear);

    /** Removes a period before it is rewritten. Only ever called behind the publication gate. */
    @Modifying
    @Query("DELETE FROM FinAggRevenueService a WHERE a.templeId = :templeId "
         + "AND a.sourceSystemId = :sourceSystemId "
         + "AND a.periodType = :periodType AND a.periodKey = :periodKey")
    int deletePeriod(@Param("templeId") Long templeId,
                     @Param("sourceSystemId") Long sourceSystemId,
                     @Param("periodType") PeriodType periodType,
                     @Param("periodKey") String periodKey);
}

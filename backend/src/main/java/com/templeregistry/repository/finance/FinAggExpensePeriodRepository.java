package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinAggExpensePeriod;
import com.templeregistry.entity.finance.enums.PeriodType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Period-grained expenditure aggregates (V139, FR1.3 and FR4). */
@Repository
public interface FinAggExpensePeriodRepository extends JpaRepository<FinAggExpensePeriod, Long> {

    /** FR4: the breakdown for one period, across every source the temple has. */
    List<FinAggExpensePeriod> findByTempleIdAndPeriodTypeAndPeriodKey(
            Long templeId, PeriodType periodType, String periodKey);

    List<FinAggExpensePeriod> findByTempleIdAndSourceSystemIdAndFinancialYear(
            Long templeId, Long sourceSystemId, String financialYear);

    /** Removes a period before it is rewritten. Only ever called behind the publication gate. */
    @Modifying
    @Query("DELETE FROM FinAggExpensePeriod a WHERE a.templeId = :templeId "
         + "AND a.sourceSystemId = :sourceSystemId "
         + "AND a.periodType = :periodType AND a.periodKey = :periodKey")
    int deletePeriod(@Param("templeId") Long templeId,
                     @Param("sourceSystemId") Long sourceSystemId,
                     @Param("periodType") PeriodType periodType,
                     @Param("periodKey") String periodKey);
}

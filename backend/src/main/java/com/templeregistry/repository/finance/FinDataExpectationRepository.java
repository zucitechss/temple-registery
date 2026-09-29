package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinDataExpectation;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** What each temple owes daily, and by when (V129, FR17). */
@Repository
public interface FinDataExpectationRepository extends JpaRepository<FinDataExpectation, Long> {

    Optional<FinDataExpectation> findByTempleIdAndSourceSystemIdAndCapabilityAndDeletedFalse(
            Long templeId, Long sourceSystemId, FinanceCapability capability);

    List<FinDataExpectation> findByTempleIdAndDeletedFalse(Long templeId);

    /**
     * Every obligation in force on a date. The freshness job starts here and
     * then filters by each source timezone, because the cutoff is a local wall
     * clock and this query cannot express that.
     */
    @Query("SELECT e FROM FinDataExpectation e WHERE e.deleted = false "
         + "AND e.activeFrom <= :onDate "
         + "AND (e.activeTo IS NULL OR e.activeTo >= :onDate)")
    List<FinDataExpectation> findActiveOn(@Param("onDate") LocalDate onDate);
}

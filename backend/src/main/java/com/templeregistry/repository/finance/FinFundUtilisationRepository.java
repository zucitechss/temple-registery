package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinFundUtilisation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Drawdowns against DC-approved funds (V133, FR5 and FR6). */
@Repository
public interface FinFundUtilisationRepository extends JpaRepository<FinFundUtilisation, Long> {

    Optional<FinFundUtilisation> findBySourceSystemIdAndSourceRecordRef(Long sourceSystemId,
                                                                        String sourceRecordRef);

    List<FinFundUtilisation> findByFundIdOrderByUtilisedOnAsc(Long fundId);

    List<FinFundUtilisation> findByTempleIdAndFinancialYear(Long templeId, String financialYear);

    /**
     * The importer supersede. Scoped by source so one channel cannot delete another's figures.
     *
     * <p><b>The caller must already be in a transaction.</b> A {@code @Modifying} query does not
     * get one of its own the way the CRUD methods do, so this throws outside one. Leave it that
     * way: the delete is only ever safe as the first half of a delete-then-insert, and a
     * {@code @Transactional} here would let it commit alone and leave the day empty when the
     * insert that was meant to follow it fails.
     */
    @Modifying
    @Query("DELETE FROM FinFundUtilisation u WHERE u.templeId = :templeId "
         + "AND u.sourceSystemId = :sourceSystemId "
         + "AND u.utilisedOn BETWEEN :from AND :to")
    int deleteBySourceAndDateRange(@Param("templeId") Long templeId,
                                   @Param("sourceSystemId") Long sourceSystemId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);
}

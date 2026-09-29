package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinPreciousItemFact;
import com.templeregistry.entity.finance.enums.MetalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Precious items received (V134, FR7).
 *
 * <p>{@link #findOverlay} is the join that assembles one FR7 row out of two
 * channels: a connector row carrying count and weight, and a manual row
 * carrying purity and value for the same temple, date and metal. Neither
 * channel updates the other row, so the report is where they meet.
 */
@Repository
public interface FinPreciousItemFactRepository extends JpaRepository<FinPreciousItemFact, Long> {

    Optional<FinPreciousItemFact> findBySourceSystemIdAndSourceRecordRef(Long sourceSystemId,
                                                                          String sourceRecordRef);

    List<FinPreciousItemFact> findByTempleIdAndFinancialYear(Long templeId, String financialYear);

    List<FinPreciousItemFact> findByTempleIdAndReceivedDateBetween(Long templeId,
                                                                    LocalDate from,
                                                                    LocalDate to);

    /** Every row for one temple, date and metal, from whichever channel wrote it. */
    List<FinPreciousItemFact> findByTempleIdAndReceivedDateAndMetalType(Long templeId,
                                                                         LocalDate receivedDate,
                                                                         MetalType metalType);

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
    @Query("DELETE FROM FinPreciousItemFact p WHERE p.templeId = :templeId "
         + "AND p.sourceSystemId = :sourceSystemId "
         + "AND p.receivedDate BETWEEN :from AND :to")
    int deleteBySourceAndDateRange(@Param("templeId") Long templeId,
                                   @Param("sourceSystemId") Long sourceSystemId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);
}

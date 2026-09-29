package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinExpenseFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Canonical expenditure (V132).
 *
 * <h2>Why no native upsert, unlike the revenue fact</h2>
 *
 * <p>{@code FinRevenueFactRepository.upsert} is native {@code ON DUPLICATE KEY
 * UPDATE} because a connector load writes on the order of 121 thousand rows a
 * batch and a select-then-insert would both race and cost. Expenditure does
 * not: a form submission is one row, and an Excel import is a few hundred.
 * {@link #findBySourceSystemIdAndSourceRecordRef} followed by {@code save} is
 * the same restatement with one extra read, and it keeps the mapping in JPA
 * where a new column cannot be silently dropped from a hand-written column
 * list.
 *
 * <p>ponytail: read-then-write upsert. Switch to a native
 * {@code ON DUPLICATE KEY UPDATE} if bulk import volume ever makes the extra
 * read measurable, or if two concurrent imports of the same file become
 * possible.
 *
 * <h2>Superseding a day</h2>
 *
 * <p>{@link #deleteBySourceAndDateRange} exists for the importer protocol
 * described in the V132 header: a second workbook covering an already-loaded
 * day carries different row numbers, so the grain alone cannot prevent the
 * double count. The importer deletes what that source previously wrote for the
 * dates the file covers, then inserts. It is scoped by source system, so one
 * channel can never delete another channel figures.
 */
@Repository
public interface FinExpenseFactRepository extends JpaRepository<FinExpenseFact, Long> {

    Optional<FinExpenseFact> findBySourceSystemIdAndSourceRecordRef(Long sourceSystemId,
                                                                     String sourceRecordRef);

    List<FinExpenseFact> findByTempleIdAndFinancialYear(Long templeId, String financialYear);

    List<FinExpenseFact> findByTempleIdAndExpenseDateBetween(Long templeId,
                                                              LocalDate from,
                                                              LocalDate to);

    List<FinExpenseFact> findBySyncBatchId(Long syncBatchId);

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
    @Query("DELETE FROM FinExpenseFact f WHERE f.templeId = :templeId "
         + "AND f.sourceSystemId = :sourceSystemId "
         + "AND f.expenseDate BETWEEN :from AND :to")
    int deleteBySourceAndDateRange(@Param("templeId") Long templeId,
                                   @Param("sourceSystemId") Long sourceSystemId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);
}

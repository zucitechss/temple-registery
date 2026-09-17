package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinRevenueFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The canonical revenue facts — the reporting boundary of the finance platform (FIN-052).
 *
 * <p>Reads here are what the rest of the platform is allowed to see. Writes come from exactly
 * one place, {@code RevenueLoadStage} (FIN-056), through {@link #upsert}.
 */
@Repository
public interface FinRevenueFactRepository extends JpaRepository<FinRevenueFact, Long> {

    /**
     * Writes one fact at the canonical grain, replacing the measures if it already exists.
     *
     * <h2>Why a native upsert and not read-then-write</h2>
     *
     * <p>A select-then-insert races: two loads of the same batch can both find nothing and both
     * insert, and only {@code uk_frf_grain} stops the second — as an exception, after the work
     * is done. {@code ON DUPLICATE KEY UPDATE} lets the database resolve it, which is the same
     * reason FIN-D-018 put the grain in a constraint rather than in loader logic.
     *
     * <p>A delete-then-insert would also be wrong, and less obviously: it loses {@code
     * created_at}, so a restatement of a two-year-old day becomes indistinguishable from a day
     * loaded for the first time. {@code created_at} is deliberately not in the update list.
     *
     * <h2>Assignment, not accumulation</h2>
     *
     * <p>Every measure is <em>replaced</em>. A second batch covering days already loaded is a
     * restatement of those days — the source was re-read and this is what it says now — and
     * adding to the existing figure would double a temple's reported revenue on every replay.
     * The fact is keyed on the grain, not on the batch, precisely so that the second load
     * corrects the first rather than joining it.
     *
     * <p>{@code sync_batch_id} therefore records which batch <em>last</em> wrote the row, not
     * every batch that contributed. That is the honest reading: after a restatement, the earlier
     * batch's figures are no longer what the platform reports.
     *
     * <p>The generated columns — {@code net_amount} and the three {@code grain_*} keys — are
     * absent from both lists because the database computes them (FIN-D-018). Naming them here
     * would be rejected, which is the protection working.
     *
     * @return 1 when the row was inserted, 2 when an existing row was updated (MySQL's count),
     *         and 0 when an update changed nothing because the values were already identical
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO fin_revenue_fact
            (temple_id, source_system_id, sync_batch_id, source_of_truth_version,
             source_record_ref, transaction_date, financial_year, service_id, category_id,
             payment_mode, payment_mode_confidence, counter_ref, operator_ref,
             transaction_count, gross_amount, cancelled_count, cancelled_amount, quantity,
             currency, created_at, updated_at)
        VALUES
            (:templeId, :sourceSystemId, :syncBatchId, :sourceOfTruthVersion,
             :sourceRecordRef, :transactionDate, :financialYear, :serviceId, :categoryId,
             :paymentMode, :paymentModeConfidence, :counterRef, :operatorRef,
             :transactionCount, :grossAmount, :cancelledCount, :cancelledAmount, :quantity,
             :currency, :now, :now)
        ON DUPLICATE KEY UPDATE
            sync_batch_id           = VALUES(sync_batch_id),
            source_system_id        = VALUES(source_system_id),
            source_of_truth_version = VALUES(source_of_truth_version),
            source_record_ref       = VALUES(source_record_ref),
            financial_year          = VALUES(financial_year),
            payment_mode_confidence = VALUES(payment_mode_confidence),
            transaction_count       = VALUES(transaction_count),
            gross_amount            = VALUES(gross_amount),
            cancelled_count         = VALUES(cancelled_count),
            cancelled_amount        = VALUES(cancelled_amount),
            quantity                = VALUES(quantity),
            currency                = VALUES(currency),
            updated_at              = VALUES(updated_at)
        """, nativeQuery = true)
    int upsert(@Param("templeId") Long templeId,
               @Param("sourceSystemId") Long sourceSystemId,
               @Param("syncBatchId") Long syncBatchId,
               @Param("sourceOfTruthVersion") Integer sourceOfTruthVersion,
               @Param("sourceRecordRef") String sourceRecordRef,
               @Param("transactionDate") LocalDate transactionDate,
               @Param("financialYear") String financialYear,
               @Param("serviceId") Long serviceId,
               @Param("categoryId") Long categoryId,
               @Param("paymentMode") String paymentMode,
               @Param("paymentModeConfidence") String paymentModeConfidence,
               @Param("counterRef") String counterRef,
               @Param("operatorRef") String operatorRef,
               @Param("transactionCount") Long transactionCount,
               @Param("grossAmount") BigDecimal grossAmount,
               @Param("cancelledCount") Long cancelledCount,
               @Param("cancelledAmount") BigDecimal cancelledAmount,
               @Param("quantity") BigDecimal quantity,
               @Param("currency") String currency,
               @Param("now") LocalDateTime now);

    /** Everything a batch last wrote. Used to check a load, and by reconciliation later. */
    List<FinRevenueFact> findBySyncBatchIdOrderByIdAsc(Long syncBatchId);

    List<FinRevenueFact> findByTempleIdAndTransactionDateOrderByIdAsc(
            Long templeId, LocalDate transactionDate);

    long countByTempleId(Long templeId);

    /**
     * One temple's total for a financial year, from the recognised gross.
     *
     * <p>Deliberately not {@code net_amount}: where a source does not record cancellations the
     * net is NULL and summing it would silently omit those rows entirely, reporting a total far
     * below the truth rather than one that is merely gross (ADR-007).
     */
    @Query("""
        SELECT SUM(f.grossAmount) FROM FinRevenueFact f
         WHERE f.templeId = :templeId AND f.financialYear = :financialYear
        """)
    Optional<BigDecimal> sumGrossForFinancialYear(@Param("templeId") Long templeId,
                                                  @Param("financialYear") String financialYear);
}

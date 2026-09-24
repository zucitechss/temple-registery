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
     * <p><b>A restatement is a restatement by the same source</b> (FIN-052A, V118).
     * {@code source_system_id} joined {@code uk_frf_grain}, so a matched row necessarily already
     * holds the value being written and assigning it again would be a no-op — it is absent from
     * the update list for the same reason the generated columns are. Before V118 that assignment
     * was the mechanism by which a second source took ownership of another source's figures, and
     * the money it replaced was not recoverable.
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

    /**
     * The financial years a batch's facts fall into (FIN-060).
     *
     * <p>Reconciliation compares per financial year because that is the period a source can be
     * asked to total independently, and because a batch's change window says nothing about which
     * business periods it touched — a single incremental batch can carry corrections to three
     * different years.
     */
    @Query("""
        SELECT DISTINCT f.financialYear FROM FinRevenueFact f
         WHERE f.syncBatchId = :syncBatchId
         ORDER BY f.financialYear
        """)
    List<String> findFinancialYearsBySyncBatchId(@Param("syncBatchId") Long syncBatchId);

    /**
     * One source's gross for a financial year.
     *
     * <p>Scoped by source system as well as temple, unlike {@link #sumGrossForFinancialYear}. A
     * temple with two source systems would otherwise have one source's total compared against
     * both sources' facts, and the comparison would fail every time while nothing was wrong.
     */
    @Query("""
        SELECT SUM(f.grossAmount) FROM FinRevenueFact f
         WHERE f.templeId = :templeId
           AND f.sourceSystemId = :sourceSystemId
           AND f.financialYear = :financialYear
        """)
    Optional<BigDecimal> sumGrossForSourceAndFinancialYear(@Param("templeId") Long templeId,
                                                           @Param("sourceSystemId") Long sourceSystemId,
                                                           @Param("financialYear") String financialYear);

    /**
     * How many source transactions one source's facts represent for a financial year.
     *
     * <p>Not {@code COUNT(*)}. A canonical fact is a daily grain, not a receipt — several
     * thousand receipts collapse into one row — so counting rows and comparing that against a
     * source's record count would compare two different things and disagree by design.
     *
     * <p>Meaningful only when every contributing fact recorded a transaction count; see
     * {@link #countFactsWithUnknownTransactionCount}.
     */
    @Query("""
        SELECT SUM(f.transactionCount) FROM FinRevenueFact f
         WHERE f.templeId = :templeId
           AND f.sourceSystemId = :sourceSystemId
           AND f.financialYear = :financialYear
        """)
    Optional<Long> sumTransactionCountForSourceAndFinancialYear(@Param("templeId") Long templeId,
                                                                @Param("sourceSystemId") Long sourceSystemId,
                                                                @Param("financialYear") String financialYear);

    /**
     * Facts whose transaction count is unknown.
     *
     * <p>Any at all makes the year's summed count a floor rather than a total, and a floor
     * compared against a source count produces a false shortfall. The reconciler reports
     * NOT_AVAILABLE instead of a number, because a partial count presented as a total is exactly
     * the "convert missing data to zero" failure this platform refuses (ADR-007).
     */
    @Query("""
        SELECT COUNT(f) FROM FinRevenueFact f
         WHERE f.templeId = :templeId
           AND f.sourceSystemId = :sourceSystemId
           AND f.financialYear = :financialYear
           AND f.transactionCount IS NULL
        """)
    long countFactsWithUnknownTransactionCount(@Param("templeId") Long templeId,
                                               @Param("sourceSystemId") Long sourceSystemId,
                                               @Param("financialYear") String financialYear);

    /**
     * The batches whose facts sit in one source's financial year (FIN-061).
     *
     * <p>The publication gate needs these because a period's trustworthiness is not only a
     * property of the period: a batch that lost rows on its way through the pipeline taints every
     * period it fed, however well that period's totals happen to agree.
     */
    @Query("""
        SELECT DISTINCT f.syncBatchId FROM FinRevenueFact f
         WHERE f.templeId = :templeId
           AND f.sourceSystemId = :sourceSystemId
           AND f.financialYear = :financialYear
         ORDER BY f.syncBatchId
        """)
    List<Long> findContributingBatchIds(@Param("templeId") Long templeId,
                                        @Param("sourceSystemId") Long sourceSystemId,
                                        @Param("financialYear") String financialYear);

    /**
     * Every fact in one publication scope, oldest first (FIN-072).
     *
     * <p>The rebuild unit. {@code (temple, source, financial year)} is deliberately the same triple
     * {@code ReconciliationGate} decides on, so the facts a rebuild reads are exactly the facts the
     * verdict covers. Ordered for determinism: two rebuilds of an unchanged scope must produce byte-
     * identical aggregates, and an unordered read makes that a coincidence rather than a guarantee.
     *
     * <p>Month rows need no query of their own. {@code RevenueAggregator} emits a {@code MONTH}
     * candidate alongside the {@code FINANCIAL_YEAR} one from these same facts, so a year's rebuild
     * necessarily rebuilds the months inside it.
     */
    List<FinRevenueFact> findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
            Long templeId, Long sourceSystemId, String financialYear);

    /**
     * The latest business date any of a temple's facts recorded, across every source (FIN-081).
     *
     * <p>The "source data through" half of the data-freshness block. Deliberately not
     * {@code sync_batch.finished_at} — that says when the platform last talked to the source,
     * not the newest date the source actually had anything to report.
     */
    @Query("SELECT MAX(f.transactionDate) FROM FinRevenueFact f WHERE f.templeId = :templeId")
    Optional<LocalDate> findMaxTransactionDateByTempleId(@Param("templeId") Long templeId);
}

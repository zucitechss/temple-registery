package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinAggRevenuePeriod;
import com.templeregistry.entity.finance.enums.PeriodType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Published period aggregates (FIN-070).
 *
 * <p>Writes come from exactly one place, {@code RevenueAggregationWriter}, through {@link #upsert} —
 * and that class will not call it without a publishable {@code ReconciliationGate.Decision}. Reads
 * here are what a future reporting API is allowed to see.
 */
@Repository
public interface FinAggRevenuePeriodRepository extends JpaRepository<FinAggRevenuePeriod, Long> {

    /**
     * Writes one period total, replacing the measures if the period already has one.
     *
     * <h2>Assignment, not accumulation</h2>
     *
     * <p>Every measure is <em>replaced</em>. This is the single easiest way to double a temple's
     * reported revenue, and the unique key does not prevent it: a writer that said
     * {@code gross_amount = gross_amount + VALUES(gross_amount)} would satisfy {@code uk_farp_grain}
     * perfectly and still be wrong on every replay. The same trap {@code fin_revenue_fact}'s upsert
     * documents, one layer up — and here it is worse, because an aggregate is recomputed far more
     * often than a fact is reloaded.
     *
     * <p>Recomputing a period is therefore idempotent by construction: the row converges on whatever
     * the facts currently say, however many times the run happens.
     *
     * <h2>Why a native upsert and not read-then-write</h2>
     *
     * <p>A select-then-insert races, and only the unique key would stop the second writer — as an
     * exception, after the work was done. {@code ON DUPLICATE KEY UPDATE} lets the database resolve
     * it, which is also what makes two concurrent rebuilds of one period safe.
     *
     * <p>{@code created_at} is deliberately absent from the update list: a period recomputed today
     * should still say when it was first published. {@code computed_at} carries the recomputation.
     * The grain columns are absent because a matched row already holds them by definition, and
     * {@code net_amount} because the database computes it (naming it would be rejected, which is the
     * protection working).
     *
     * @return 1 when the row was inserted, 2 when an existing row was updated (MySQL's count), and
     *         0 when an update changed nothing because the values were already identical
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO fin_agg_revenue_period
            (temple_id, source_system_id, period_type, period_key, category_id, payment_mode,
             financial_year, period_start, period_end,
             fact_count, transaction_count, facts_with_unknown_count,
             gross_amount, facts_with_unknown_gross, cancelled_count, cancelled_amount,
             quantity, currency, payment_mode_inferred_facts,
             reconciliation_status, reconciliation_inherited, calc_version,
             computed_at, created_at, updated_at)
        VALUES
            (:templeId, :sourceSystemId, :periodType, :periodKey, :categoryId, :paymentMode,
             :financialYear, :periodStart, :periodEnd,
             :factCount, :transactionCount, :factsWithUnknownCount,
             :grossAmount, :factsWithUnknownGross, :cancelledCount, :cancelledAmount,
             :quantity, :currency, :paymentModeInferredFacts,
             :reconciliationStatus, :reconciliationInherited, :calcVersion,
             :now, :now, :now)
        ON DUPLICATE KEY UPDATE
            financial_year              = VALUES(financial_year),
            period_start                = VALUES(period_start),
            period_end                  = VALUES(period_end),
            fact_count                  = VALUES(fact_count),
            transaction_count           = VALUES(transaction_count),
            facts_with_unknown_count    = VALUES(facts_with_unknown_count),
            gross_amount                = VALUES(gross_amount),
            facts_with_unknown_gross    = VALUES(facts_with_unknown_gross),
            cancelled_count             = VALUES(cancelled_count),
            cancelled_amount            = VALUES(cancelled_amount),
            quantity                    = VALUES(quantity),
            currency                    = VALUES(currency),
            payment_mode_inferred_facts = VALUES(payment_mode_inferred_facts),
            reconciliation_status       = VALUES(reconciliation_status),
            reconciliation_inherited    = VALUES(reconciliation_inherited),
            calc_version                = VALUES(calc_version),
            computed_at                 = VALUES(computed_at),
            updated_at                  = VALUES(updated_at)
        """, nativeQuery = true)
    int upsert(@Param("templeId") Long templeId,
               @Param("sourceSystemId") Long sourceSystemId,
               @Param("periodType") String periodType,
               @Param("periodKey") String periodKey,
               @Param("categoryId") Long categoryId,
               @Param("paymentMode") String paymentMode,
               @Param("financialYear") String financialYear,
               @Param("periodStart") LocalDate periodStart,
               @Param("periodEnd") LocalDate periodEnd,
               @Param("factCount") Integer factCount,
               @Param("transactionCount") Long transactionCount,
               @Param("factsWithUnknownCount") Integer factsWithUnknownCount,
               @Param("grossAmount") BigDecimal grossAmount,
               @Param("factsWithUnknownGross") Integer factsWithUnknownGross,
               @Param("cancelledCount") Long cancelledCount,
               @Param("cancelledAmount") BigDecimal cancelledAmount,
               @Param("quantity") BigDecimal quantity,
               @Param("currency") String currency,
               @Param("paymentModeInferredFacts") Integer paymentModeInferredFacts,
               @Param("reconciliationStatus") String reconciliationStatus,
               @Param("reconciliationInherited") Boolean reconciliationInherited,
               @Param("calcVersion") Short calcVersion,
               @Param("now") LocalDateTime now);

    /**
     * One temple's published rows for one source and financial year, months included.
     *
     * <p>The scope the gate decides on, and therefore the scope FIN-072 will rebuild. Ordered so a
     * caller comparing two runs compares like with like.
     */
    List<FinAggRevenuePeriod> findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
            Long templeId, Long sourceSystemId, String financialYear);

    long countByTempleId(Long templeId);

    /**
     * A temple's total for a financial year, summed across its source systems.
     *
     * <p>Reads the {@code FINANCIAL_YEAR} rows only — adding the monthly rows as well would count
     * every rupee twice, which is the specific error storing both granularities in one table invites.
     *
     * <p>Deliberately not {@code net_amount}: where cancellations are not fully recorded the net is
     * NULL, and summing it would silently omit those periods entirely, reporting a total far below
     * the truth rather than one that is merely gross (ADR-007).
     */
    @Query("""
        SELECT SUM(a.grossAmount) FROM FinAggRevenuePeriod a
         WHERE a.templeId = :templeId
           AND a.financialYear = :financialYear
           AND a.periodType = com.templeregistry.entity.finance.enums.PeriodType.FINANCIAL_YEAR
        """)
    BigDecimal sumGrossForFinancialYear(@Param("templeId") Long templeId,
                                        @Param("financialYear") String financialYear);

    /**
     * One year's FINANCIAL_YEAR rows across every source system and category (FIN-081).
     *
     * <p>Deliberately not scoped to a source: a temple-level report sums its sources, the same
     * choice {@code idx_farp_report} was built for.
     */
    List<FinAggRevenuePeriod> findByTempleIdAndPeriodTypeAndPeriodKeyOrderByIdAsc(
            Long templeId, PeriodType periodType, String periodKey);

    /**
     * Every published period of one type for a temple, oldest first (FIN-081).
     *
     * <p>Used for the revenue trend: one row per {@code (source, category, payment mode)} per
     * financial year, grouped by {@code periodKey} in the service layer.
     */
    List<FinAggRevenuePeriod> findByTempleIdAndPeriodTypeOrderByPeriodKeyAscIdAsc(
            Long templeId, PeriodType periodType);

    /**
     * One financial year's rows of one period type, oldest first (FIN-081).
     *
     * <p>Used for the monthly breakdown ({@code periodType = MONTH}): grouped by {@code periodKey}
     * (the calendar month) in the service layer.
     */
    List<FinAggRevenuePeriod> findByTempleIdAndPeriodTypeAndFinancialYearOrderByPeriodKeyAscIdAsc(
            Long templeId, PeriodType periodType, String financialYear);
}

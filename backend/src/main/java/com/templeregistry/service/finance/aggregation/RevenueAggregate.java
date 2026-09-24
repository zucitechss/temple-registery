package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PeriodType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One period total, computed from canonical facts and not yet written anywhere (FIN-070).
 *
 * <p>A value object on purpose. {@link RevenueAggregator} produces these from facts with no database
 * in sight, and {@code RevenueAggregationWriter} is the only thing that turns one into a row — so the
 * arithmetic can be tested exhaustively without a container, and the write path has nothing to decide
 * except whether it is allowed to write.
 *
 * <h2>Reading the nullable measures</h2>
 *
 * <p>NULL means "not known", never zero (ADR-007), and each NULL here has a counter beside it saying
 * how much of the period could not be measured — so a reader can tell a floor from a total.
 *
 * <p>The three nullable measures are deliberately <b>not</b> treated alike, and the asymmetry is the
 * point:
 *
 * <ul>
 *   <li>{@link #grossAmount} is the sum of what was recorded, with {@link #factsWithUnknownGross}
 *       saying how many facts contributed nothing to it. Nulling the whole period because one fact of
 *       fifty lacked an amount would hide real revenue, which is worse than reporting a figure the
 *       reader is told is a floor.
 *   <li>{@link #transactionCount} is NULL as soon as <em>any</em> contributing fact lacks a count.
 *       That follows the precedent FIN-D-053 already set in reconciliation, where a partial count
 *       compared against a source's total produces a false shortfall. A count is used to check
 *       agreement, and a floor cannot do that job.
 *   <li>{@link #cancelledAmount} and {@link #cancelledCount} are NULL as soon as any fact did not
 *       record cancellations, because a partial cancellation total understates cancellations and so
 *       <em>overstates</em> net revenue. That NULL is what makes {@code net_amount} correctly unknown.
 * </ul>
 *
 * <p>There is no {@code netAmount} field. The database computes it as {@code gross - cancelled}, so
 * no writer can disagree with it — the same reasoning V112 applied to the fact table.
 *
 * @param factCount                how many canonical facts rolled up here. A roll-up count, never a
 *                                 receipt count: a fact is already a daily grain
 * @param currency                 asserted single-valued across the contributing facts; the
 *                                 aggregator refuses a mixed group rather than summing across
 *                                 currencies
 * @param paymentModeInferredFacts contributing facts whose mode a connector derived rather than the
 *                                 source stating it. A flag, never a grouping dimension — confidence
 *                                 is not part of the fact grain, so an upsert can change it without
 *                                 changing a fact's identity
 */
public record RevenueAggregate(RevenueAggregateKey key,
                               String financialYear,
                               LocalDate periodStart,
                               LocalDate periodEnd,
                               int factCount,
                               Long transactionCount,
                               int factsWithUnknownCount,
                               BigDecimal grossAmount,
                               int factsWithUnknownGross,
                               Long cancelledCount,
                               BigDecimal cancelledAmount,
                               BigDecimal quantity,
                               String currency,
                               int paymentModeInferredFacts) {

    public long templeId() {
        return key.templeId();
    }

    public long sourceSystemId() {
        return key.sourceSystemId();
    }

    public PeriodType periodType() {
        return key.periodType();
    }

    public String periodKey() {
        return key.periodKey();
    }

    public long categoryId() {
        return key.categoryId();
    }

    public PaymentMode paymentMode() {
        return key.paymentMode();
    }

    /**
     * Whether this row's reconciliation verdict is its parent financial year's rather than its own.
     *
     * <p>Always true for a month, because {@code RevenueReconciliationStage} writes results only at
     * {@code FULL_HISTORY} and {@code FINANCIAL_YEAR} scope — there is no monthly evidence and so
     * there can be no monthly verdict. Recorded on the row so nothing tells a reader that a month was
     * verified when it was not.
     */
    public boolean reconciliationInherited() {
        return key.periodType() == PeriodType.MONTH;
    }
}

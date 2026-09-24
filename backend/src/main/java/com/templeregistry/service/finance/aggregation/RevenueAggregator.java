package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.service.finance.pipeline.FinancialYear;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolls canonical facts up into period totals (FIN-070). Pure, and deliberately so.
 *
 * <p>No Spring, no repository, no clock, no configuration. Given the same facts it returns the same
 * aggregates in the same order, for ever — which is what makes "rebuild is deterministic" (ADR-011) a
 * property that can be tested rather than hoped for, and what lets every arithmetic rule below be
 * exercised without a database.
 *
 * <p>It does <b>not</b> decide whether anything may be published. It computes; {@code
 * RevenueAggregationWriter} is the only thing that writes, and it will not write without a
 * publishable {@code ReconciliationGate.Decision}. Computing a figure that turns out to be blocked is
 * harmless and useful: it is how a run can report what it withheld.
 *
 * <h2>What each fact contributes</h2>
 *
 * <p>Exactly two rows: one {@code FINANCIAL_YEAR} and one {@code MONTH}, both keyed on the fact's own
 * temple, source system, category and payment mode. The two are computed in one pass over the facts,
 * so they cannot drift from one another.
 *
 * <p>The period comes from {@link FinRevenueFact#getTransactionDate()} — the <em>business</em> date,
 * never {@code createdAt} or the batch window. A source may edit a two-year-old receipt today; that
 * corrects an old month, it does not move money into this one (FIN-D-012).
 *
 * <h2>Two refusals, and why neither is a silent fallback</h2>
 *
 * <p><b>A fact whose stored {@code financial_year} disagrees with its own {@code transaction_date}
 * is refused.</b> {@link FinancialYear} exists precisely because that pair can be made inconsistent
 * by a loader with the wrong year start, and because nothing downstream can detect it — both columns
 * look plausible alone. Aggregation is the first place that reads them together, so it is the last
 * place the disagreement can be caught before it becomes a published total.
 *
 * <p><b>A group spanning two currencies is refused.</b> {@code currency} is not in the fact grain and
 * so is not a grouping dimension here; summing across currencies would produce a large number that
 * looked like rupees. Every fact the platform can currently produce is INR, so this is a guard
 * against a future that has not happened yet rather than a case anyone has seen.
 *
 * <p>Both throw {@link AggregationRefusedException}. Neither substitutes a zero, a default or a
 * best guess, because a wrong financial figure that looks reasonable is the failure this whole
 * platform is built to avoid.
 */
public final class RevenueAggregator {

    /**
     * Which formula produced a row.
     *
     * <p>Stamped on every aggregate so that, when the arithmetic below changes, rows computed under
     * the old rules are identifiable and FIN-072 can rebuild exactly those. Bump it whenever a change
     * would give a different answer for unchanged facts — not for a refactor that would not.
     */
    public static final short CALC_VERSION = 1;

    /** The period types revenue is aggregated by. No DAY: no catalogued report reads one. */
    private static final List<PeriodType> PERIOD_TYPES =
            List.of(PeriodType.FINANCIAL_YEAR, PeriodType.MONTH);

    private static final Comparator<RevenueAggregate> DETERMINISTIC_ORDER =
            Comparator.comparingLong(RevenueAggregate::templeId)
                    .thenComparingLong(RevenueAggregate::sourceSystemId)
                    .thenComparing(a -> a.periodType().name())
                    .thenComparing(RevenueAggregate::periodKey)
                    .thenComparingLong(RevenueAggregate::categoryId)
                    .thenComparing(a -> a.paymentMode().name());

    private RevenueAggregator() {
    }

    /**
     * Rolls up every fact given.
     *
     * <p>The caller chooses the scope. This method aggregates what it is handed and nothing else: it
     * issues no query, so it cannot accidentally widen a temple's or a source's boundary. Facts for
     * several temples or sources in one call are grouped apart correctly, but a caller who wants one
     * scope written should pass one scope's facts.
     *
     * @return one aggregate per (temple, source, period type, period key, category, payment mode),
     *         in a stable order; empty for empty or null input
     * @throws AggregationRefusedException if a fact disagrees with itself about its financial year,
     *                                     or a group spans more than one currency
     */
    public static List<RevenueAggregate> aggregate(List<FinRevenueFact> facts) {
        if (facts == null || facts.isEmpty()) {
            // Empty in, empty out. Not a row of zeroes: no facts means nothing was measured, and a
            // zero would assert that the temple took nothing (ADR-007).
            return List.of();
        }

        Map<RevenueAggregateKey, Accumulator> accumulators = new LinkedHashMap<>();
        for (FinRevenueFact fact : facts) {
            assertFinancialYearAgreesWithDate(fact);
            for (PeriodType periodType : PERIOD_TYPES) {
                RevenueAggregateKey key = new RevenueAggregateKey(
                        fact.getTempleId(),
                        fact.getSourceSystemId(),
                        periodType,
                        AggregationPeriod.keyOf(periodType, fact.getTransactionDate()),
                        fact.getCategoryId(),
                        fact.getPaymentMode());
                accumulators.computeIfAbsent(key, Accumulator::new).add(fact);
            }
        }

        List<RevenueAggregate> aggregates = new ArrayList<>(accumulators.size());
        accumulators.values().forEach(accumulator -> aggregates.add(accumulator.toAggregate()));
        aggregates.sort(DETERMINISTIC_ORDER);
        return List.copyOf(aggregates);
    }

    private static void assertFinancialYearAgreesWithDate(FinRevenueFact fact) {
        String derived = FinancialYear.of(fact.getTransactionDate());
        if (!derived.equals(fact.getFinancialYear())) {
            throw new AggregationRefusedException(
                    "Fact " + fact.getId() + " is dated " + fact.getTransactionDate()
                            + ", which falls in " + derived + ", but the fact says "
                            + fact.getFinancialYear() + ". One of the two is wrong and there is no "
                            + "way to tell which, so no total is produced from it.");
        }
    }

    /**
     * One period's running totals.
     *
     * <p>Mutable and package-private on purpose: it exists for the length of one {@code aggregate}
     * call and never escapes it, which is what keeps the public surface a pure function.
     */
    private static final class Accumulator {

        private final RevenueAggregateKey key;
        private final String financialYear;

        private String currency;
        private int factCount;
        private long transactionCount;
        private int factsWithUnknownCount;
        private BigDecimal grossAmount = BigDecimal.ZERO;
        private int factsWithUnknownGross;
        private long cancelledCount;
        private boolean cancelledCountUnknown;
        private BigDecimal cancelledAmount = BigDecimal.ZERO;
        private boolean cancelledAmountUnknown;
        private BigDecimal quantity = BigDecimal.ZERO;
        private int factsWithUnknownQuantity;
        private int paymentModeInferredFacts;

        private Accumulator(RevenueAggregateKey key) {
            this.key = key;
            this.financialYear = AggregationPeriod.financialYearOf(key.periodType(), key.periodKey());
        }

        private void add(FinRevenueFact fact) {
            assertOneCurrency(fact);
            factCount++;

            if (fact.getTransactionCount() == null) {
                factsWithUnknownCount++;
            } else {
                transactionCount += fact.getTransactionCount();
            }

            if (fact.getGrossAmount() == null) {
                factsWithUnknownGross++;
            } else {
                grossAmount = grossAmount.add(fact.getGrossAmount());
            }

            if (fact.getCancelledCount() == null) {
                cancelledCountUnknown = true;
            } else {
                cancelledCount += fact.getCancelledCount();
            }

            if (fact.getCancelledAmount() == null) {
                cancelledAmountUnknown = true;
            } else {
                cancelledAmount = cancelledAmount.add(fact.getCancelledAmount());
            }

            if (fact.getQuantity() == null) {
                factsWithUnknownQuantity++;
            } else {
                quantity = quantity.add(fact.getQuantity());
            }

            if (fact.getPaymentModeConfidence() == PaymentModeConfidence.INFERRED) {
                paymentModeInferredFacts++;
            }
        }

        private void assertOneCurrency(FinRevenueFact fact) {
            String factCurrency = fact.getCurrency();
            if (currency == null) {
                currency = factCurrency;
            } else if (!currency.equals(factCurrency)) {
                throw new AggregationRefusedException(
                        "Period " + key.periodKey() + " for temple " + key.templeId() + " source "
                                + key.sourceSystemId() + " holds facts in both " + currency + " and "
                                + factCurrency + ". Summing across currencies would report one "
                                + "currency's total in another's name, so no total is produced.");
            }
        }

        private RevenueAggregate toAggregate() {
            return new RevenueAggregate(
                    key,
                    financialYear,
                    AggregationPeriod.startOf(key.periodType(), key.periodKey()),
                    AggregationPeriod.endOf(key.periodType(), key.periodKey()),
                    factCount,
                    // A count is used to check agreement against a source's own total, so a floor
                    // cannot do its job: one unknown makes the whole period's count unknown
                    // (FIN-D-053). The counter beside it says how far short the sum would have been.
                    factsWithUnknownCount > 0 ? null : transactionCount,
                    factsWithUnknownCount,
                    // Gross is the opposite case. Nulling a period because one fact of fifty lacked
                    // an amount would hide real revenue; reporting the sum with an explicit count of
                    // what is missing lets the reader see it is a floor.
                    factsWithUnknownGross == factCount ? null : grossAmount,
                    factsWithUnknownGross,
                    // A partial cancellation total understates cancellations and so overstates net.
                    // NULL here is what makes the generated net_amount correctly unknown.
                    cancelledCountUnknown ? null : cancelledCount,
                    cancelledAmountUnknown ? null : cancelledAmount,
                    factsWithUnknownQuantity == factCount ? null : quantity,
                    currency,
                    paymentModeInferredFacts);
        }
    }

    /**
     * A total that will not be produced, because producing it would require a guess.
     *
     * <p>Unchecked and deliberately fatal to the run. A caller that caught this and wrote a partial
     * aggregate would publish a figure whose provenance nobody could reconstruct.
     */
    public static class AggregationRefusedException extends RuntimeException {
        public AggregationRefusedException(String message) {
            super(message);
        }
    }
}

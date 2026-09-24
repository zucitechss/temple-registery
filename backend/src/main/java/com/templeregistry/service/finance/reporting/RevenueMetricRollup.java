package com.templeregistry.service.finance.reporting;

import com.templeregistry.entity.finance.FinAggRevenuePeriod;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Sums a group of {@code fin_agg_revenue_period} rows into one reportable figure (FIN-081).
 *
 * <p>Every reporting endpoint needs the same thing: several rows — one per source system,
 * category and payment mode — collapsed into one number for a year, a month or a category. This
 * is the one place that collapse happens, so the null-propagation rules that make a single
 * aggregate row honest (V119, FIN-070) are not re-derived, and possibly gotten wrong, at every
 * call site.
 *
 * <p>Pure and stateless, like {@code RevenueAggregator} one layer down: no repository, no clock,
 * no Spring context. Reporting-layer summing, never re-deriving figures from facts — this class
 * never sees a {@code FinRevenueFact}.
 *
 * <h2>Why a whole-currency refusal here too</h2>
 *
 * <p>Every row already carries one currency by {@code RevenueAggregator}'s own refusal. Summing
 * rows of two different currencies would still be a nonsense number, so this class re-asserts the
 * same rule at its own boundary rather than trusting that no caller ever mixes scopes.
 */
public final class RevenueMetricRollup {

    private RevenueMetricRollup() {
    }

    /**
     * @param grossAmount           summed recognised gross across every row that recorded any;
     *                              {@code null} when not one row did
     * @param grossFullyKnown       {@code false} when at least one row's own gross was NULL (no
     *                              contributing fact recorded one) or recorded facts with unknown
     *                              gross — {@code grossAmount} is then a floor, not a total
     * @param factsWithUnknownGross how many contributing facts, across every row, had no recorded
     *                              gross — explains the gap when {@code grossFullyKnown} is false
     * @param netAmount             summed {@code net_amount}; {@code null} when not one row knew it
     * @param netFullyKnown         {@code false} when any row's net was NULL (cancellations not
     *                              fully recorded for that bucket, or gross itself unknown)
     * @param transactionCount      summed transaction count; {@code null} when not one row knew it
     * @param transactionCountFullyKnown {@code false} when any row's count was NULL
     * @param reconciliationStatus  {@code PASSED} only if every row is; otherwise
     *                              {@code NOT_AVAILABLE} — a blend can never honestly claim a full
     *                              pass, and {@code FAILED}/{@code PENDING} rows never reach this
     *                              table at all (the writer refuses to publish them)
     * @param currency              the one currency every row shares
     * @param asOfDate              the latest {@code period_end} among the rows rolled up
     */
    public record Rollup(
            BigDecimal grossAmount,
            boolean grossFullyKnown,
            int factsWithUnknownGross,
            BigDecimal netAmount,
            boolean netFullyKnown,
            Long transactionCount,
            boolean transactionCountFullyKnown,
            ReconciliationStatus reconciliationStatus,
            String currency,
            LocalDate asOfDate) {
    }

    /**
     * @throws IllegalArgumentException if {@code rows} is null or empty — the caller distinguishes
     *                                  "no data published" from "data published, some of it
     *                                  unknown", and those are different reasons for the same
     *                                  {@code NOT_AVAILABLE}/{@code PARTIALLY_AVAILABLE} split
     * @throws IllegalStateException    if the rows do not share one currency
     */
    public static Rollup rollUp(List<FinAggRevenuePeriod> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot roll up an empty scope; the caller must report NOT_AVAILABLE itself.");
        }

        BigDecimal gross = null;
        boolean grossFullyKnown = true;
        int unknownGrossFacts = 0;

        BigDecimal net = null;
        boolean netFullyKnown = true;

        Long transactionCount = null;
        boolean transactionCountFullyKnown = true;

        boolean anyNotPassed = false;
        String currency = null;
        LocalDate asOfDate = null;

        for (FinAggRevenuePeriod row : rows) {
            if (row.getGrossAmount() == null) {
                grossFullyKnown = false;
            } else {
                gross = (gross == null) ? row.getGrossAmount() : gross.add(row.getGrossAmount());
            }
            int rowUnknown = row.getFactsWithUnknownGross() == null ? 0 : row.getFactsWithUnknownGross();
            if (rowUnknown > 0) {
                grossFullyKnown = false;
            }
            unknownGrossFacts += rowUnknown;

            if (row.getNetAmount() == null) {
                netFullyKnown = false;
            } else {
                net = (net == null) ? row.getNetAmount() : net.add(row.getNetAmount());
            }

            if (row.getTransactionCount() == null) {
                transactionCountFullyKnown = false;
            } else {
                transactionCount = (transactionCount == null)
                        ? row.getTransactionCount()
                        : transactionCount + row.getTransactionCount();
            }

            if (row.getReconciliationStatus() != ReconciliationStatus.PASSED) {
                anyNotPassed = true;
            }

            if (currency == null) {
                currency = row.getCurrency();
            } else if (!currency.equals(row.getCurrency())) {
                throw new IllegalStateException(
                        "Rows of currencies " + currency + " and " + row.getCurrency()
                                + " cannot be summed into one metric.");
            }

            if (asOfDate == null || row.getPeriodEnd().isAfter(asOfDate)) {
                asOfDate = row.getPeriodEnd();
            }
        }

        return new Rollup(gross, grossFullyKnown, unknownGrossFacts, net, netFullyKnown,
                transactionCount, transactionCountFullyKnown,
                anyNotPassed ? ReconciliationStatus.NOT_AVAILABLE : ReconciliationStatus.PASSED,
                Objects.requireNonNull(currency), asOfDate);
    }
}

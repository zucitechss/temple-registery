package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.service.finance.publication.ReconciliationGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The only thing that writes a period aggregate, and it will not write an ungated one (FIN-070).
 *
 * <pre>
 *   facts --> RevenueAggregator (pure) --> List&lt;RevenueAggregate&gt; --> this --> fin_agg_revenue_period
 *                                                                        ^
 *                                                          requires a publishable Decision
 * </pre>
 *
 * <h2>It requires a verdict; it does not fetch one</h2>
 *
 * <p>A {@code ReconciliationGate.Decision} is an argument, not a dependency. This class holds no
 * reference to the gate and never asks it anything — deciding <em>when</em> to aggregate a period,
 * and therefore when to consult the gate, belongs to whatever orchestrates runs (FIN-072), and
 * inventing that here would be inventing a trigger this task must not build.
 *
 * <p>But it refuses to write without one. FIN-070B's argument is that existing in
 * {@code fin_agg_revenue_period} <em>is</em> what publication means, so a writer that could be called
 * without a verdict is one forgotten call away from publishing figures reconciliation does not stand
 * behind. Passing the verdict in makes the omission impossible to express rather than merely
 * discouraged: there is no overload that skips it.
 *
 * <h2>Blocking means writing nothing</h2>
 *
 * <p>A {@code FAILED} or {@code PENDING} decision is refused outright. Nothing is written, nothing is
 * deleted, and any previously published row for that period survives untouched — which is ADR-011's
 * "slightly old and correct beats fresh and wrong", implemented by doing nothing rather than by a
 * status column. There is deliberately no withheld row: a row that exists but must not be read is a
 * trap for the next reader.
 *
 * <p>{@code NOT_AVAILABLE} writes, and records that it did so unverified. What is withheld in that
 * case is the <em>claim</em> that the figures were checked, not the figures (FIN-D-054).
 *
 * <h2>Scope, enforced rather than assumed</h2>
 *
 * <p>Every aggregate handed in must belong to the temple, source system and financial year the
 * decision covers. A caller who aggregated two years and gated one would otherwise publish the
 * ungated year on the strength of the other's verdict, and the rows would look identical afterwards.
 *
 * <h2>Worker-side</h2>
 *
 * <p>No {@code @PreAuthorize} and no principal: this runs in the sync worker, where there is no
 * authenticated user to authorize. Read-side jurisdiction isolation belongs to the future reporting
 * API. Registered as an explicit {@code @Bean} in {@code SyncWorkerConfig}, never as a
 * {@code @Component} (FIN-D-008).
 */
public class RevenueAggregationWriter {

    private static final Logger log = LoggerFactory.getLogger(RevenueAggregationWriter.class);

    private final FinAggRevenuePeriodRepository aggregates;
    private final TransactionTemplate transactionTemplate;

    public RevenueAggregationWriter(FinAggRevenuePeriodRepository aggregates,
                                    TransactionTemplate transactionTemplate) {
        this.aggregates = aggregates;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Writes every aggregate for one gated scope, replacing whatever that scope held before.
     *
     * <p>All of them or none. A partially written financial year is a table that contradicts itself —
     * twelve months that do not add up to their year — and that is worse than a year left as it was,
     * because the contradiction is invisible to a reader who only asks for one of the two.
     *
     * @param decision  the gate's verdict for the (temple, source, financial year) being written
     * @param computed  what {@link RevenueAggregator} produced for exactly that scope; may be empty,
     *                  in which case nothing is written and nothing is destroyed
     * @return how many aggregate rows were written or replaced
     * @throws ReconciliationGate.PublicationBlockedException if the decision does not permit
     *                                                        publication
     * @throws IllegalArgumentException if any aggregate falls outside the decision's scope
     */
    public int write(ReconciliationGate.Decision decision, List<RevenueAggregate> computed) {
        if (decision == null) {
            throw new IllegalArgumentException(
                    "A period aggregate cannot be written without a reconciliation decision. "
                            + "Existing in this table is what publication means.");
        }
        if (!decision.publishable()) {
            // Not an error in the run's own terms — the figures may be perfectly correct — but the
            // replacement is withheld and the caller must be able to tell that it was.
            log.warn("[FinanceAggregation] Withholding {} aggregate row(s): {}",
                    computed == null ? 0 : computed.size(), decision.explain());
            throw new ReconciliationGate.PublicationBlockedException(decision);
        }
        if (computed == null || computed.isEmpty()) {
            // No facts means nothing was measured. Writing a row of zeroes would assert the temple
            // took nothing, and deleting the previous row would destroy a figure on no evidence.
            log.info("[FinanceAggregation] Nothing to write for {}", decision.explain());
            return 0;
        }

        computed.forEach(aggregate -> assertInScope(aggregate, decision));

        ReconciliationStatus status = decision.status();
        LocalDateTime now = LocalDateTime.now();
        Integer written = transactionTemplate.execute(tx -> {
            int rows = 0;
            for (RevenueAggregate aggregate : computed) {
                aggregates.upsert(
                        aggregate.templeId(), aggregate.sourceSystemId(),
                        aggregate.periodType().name(), aggregate.periodKey(),
                        aggregate.categoryId(), aggregate.paymentMode().name(),
                        aggregate.financialYear(), aggregate.periodStart(), aggregate.periodEnd(),
                        aggregate.factCount(), aggregate.transactionCount(),
                        aggregate.factsWithUnknownCount(), aggregate.grossAmount(),
                        aggregate.factsWithUnknownGross(), aggregate.cancelledCount(),
                        aggregate.cancelledAmount(), aggregate.quantity(), aggregate.currency(),
                        aggregate.paymentModeInferredFacts(),
                        status.name(), aggregate.reconciliationInherited(),
                        RevenueAggregator.CALC_VERSION, now);
                rows++;
            }
            return rows;
        });

        int rows = written == null ? 0 : written;
        log.info("[FinanceAggregation] Published {} aggregate row(s) for temple {} source {} {} ({})",
                rows, decision.templeId(), decision.sourceSystemId(), decision.financialYear(), status);
        return rows;
    }

    /**
     * A verdict covers one temple, one source and one financial year, and so does what it may write.
     *
     * <p>Checked per row rather than once, because the failure this prevents is a caller mixing scopes
     * in one list — which a single spot-check on the first element would pass.
     */
    private void assertInScope(RevenueAggregate aggregate, ReconciliationGate.Decision decision) {
        boolean inScope = aggregate.templeId() == decision.templeId()
                && aggregate.sourceSystemId() == decision.sourceSystemId()
                && aggregate.financialYear().equals(decision.financialYear());
        if (!inScope) {
            throw new IllegalArgumentException(
                    "Aggregate for temple " + aggregate.templeId() + " source "
                            + aggregate.sourceSystemId() + " " + aggregate.financialYear()
                            + " is outside the decision's scope (temple " + decision.templeId()
                            + " source " + decision.sourceSystemId() + " "
                            + decision.financialYear() + "). One verdict cannot publish another "
                            + "scope's figures.");
        }
    }
}

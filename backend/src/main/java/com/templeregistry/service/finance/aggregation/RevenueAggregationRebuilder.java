package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.service.finance.publication.ReconciliationGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recomputes period aggregates for exactly the scopes a batch touched (FIN-072).
 *
 * <pre>
 *   batch --> which financial years did its facts land in?
 *              |
 *              +--> for each: gate verdict --> facts --> RevenueAggregator --> writer
 * </pre>
 *
 * <h2>This is not a correction mechanism</h2>
 *
 * <p>Saying that plainly matters, because a button labelled "rebuild" is what people reach for when a
 * figure looks wrong. A rebuild recomputes aggregates <em>from the facts as they currently stand</em>.
 * If a fact is wrong, its aggregate will be rebuilt just as wrong, deterministically, every time. It
 * re-runs no extraction, no validation, no mapping and no load; it writes, updates and deletes no
 * {@code fin_revenue_fact} row; and it restates no history. What it fixes is an aggregate that has
 * fallen behind its facts — nothing else.
 *
 * <h2>Affected scopes, not a time window</h2>
 *
 * <p>The scopes come from {@code findFinancialYearsBySyncBatchId}: the financial years the batch's
 * facts actually landed in. Never from the batch's change window, which says only when rows were
 * modified in the source — one incremental batch routinely carries corrections to three different
 * years (ADR-006). A window-derived scope would silently skip the years it did not guess.
 *
 * <p>Months need no scope of their own. {@link RevenueAggregator} emits a {@code MONTH} candidate
 * beside every {@code FINANCIAL_YEAR} one from the same facts, so rebuilding a year rebuilds the
 * months inside it by construction. FIN-070B section 12 anticipated a separate distinct-months-per-
 * batch query; it would return a strict subset of what the year scope already covers, and a second
 * query that can disagree with the first is a defect waiting to happen.
 *
 * <h2>A blocked year is skipped, not fatal</h2>
 *
 * <p>Each year is gated independently. A {@code FAILED} or {@code PENDING} verdict for one year
 * withholds that year's replacement and leaves its previously published rows exactly as they were —
 * and the remaining years still rebuild. Collapsing that into a thrown exception would let one
 * unverifiable year block eleven good ones; swallowing it silently would be worse. Blocked scopes are
 * named in the {@link Result} and logged.
 */
public class RevenueAggregationRebuilder {

    private static final Logger log = LoggerFactory.getLogger(RevenueAggregationRebuilder.class);

    private final FinSyncBatchRepository batches;
    private final FinRevenueFactRepository facts;
    private final ReconciliationGate gate;
    private final RevenueAggregationWriter writer;

    public RevenueAggregationRebuilder(FinSyncBatchRepository batches,
                                       FinRevenueFactRepository facts,
                                       ReconciliationGate gate,
                                       RevenueAggregationWriter writer) {
        this.batches = Objects.requireNonNull(batches, "batches");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Rebuilds every financial year the batch's facts landed in.
     *
     * <p>A batch that wrote no facts touches no scope and rebuilds nothing — which is the correct
     * answer, not a failure. An extract that legitimately found no new rows must not cause any
     * published figure to move.
     *
     * @throws IllegalArgumentException if no such batch exists
     */
    public Result rebuildBatch(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch " + syncBatchId));

        List<String> affected = facts.findFinancialYearsBySyncBatchId(syncBatchId);
        if (affected.isEmpty()) {
            log.info("[FinanceAggregation] Batch {} touched no financial year; nothing to rebuild",
                    syncBatchId);
            return new Result(List.of(), List.of(), 0);
        }

        List<String> rebuilt = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        int rows = 0;

        for (String financialYear : affected) {
            ScopeOutcome outcome =
                    rebuildScope(batch.getTempleId(), batch.getSourceSystemId(), financialYear);
            if (outcome.published()) {
                rebuilt.add(financialYear);
                rows += outcome.rowsWritten();
            } else {
                blocked.add(financialYear);
            }
        }

        log.info("[FinanceAggregation] Batch {} rebuilt {} of {} affected year(s), {} row(s) "
                        + "published, {} withheld",
                syncBatchId, rebuilt.size(), affected.size(), rows, blocked.size());
        return new Result(List.copyOf(rebuilt), List.copyOf(blocked), rows);
    }

    /**
     * Rebuilds one {@code (temple, source, financial year)} scope.
     *
     * <p>The unit of both the gate's decision and this rebuild, so a verdict never covers more or
     * less than what gets written under it. Reads the facts, recomputes, and publishes only on a
     * publishable verdict.
     *
     * <p>A scope whose facts have all gone is <em>not</em> emptied here. Writing nothing leaves the
     * previous rows standing, because this platform cannot distinguish "the source deleted these"
     * from "the extract did not reach them", and zeroing a temple's published revenue on that
     * ambiguity is the worse of the two errors (ADR-007).
     */
    public ScopeOutcome rebuildScope(long templeId, long sourceSystemId, String financialYear) {
        ReconciliationGate.Decision decision = gate.evaluate(templeId, sourceSystemId, financialYear);
        if (!decision.publishable()) {
            log.warn("[FinanceAggregation] Not rebuilding: {}", decision.explain());
            return new ScopeOutcome(financialYear, false, 0);
        }

        List<FinRevenueFact> scope = facts
                .findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                        templeId, sourceSystemId, financialYear);

        List<RevenueAggregate> computed = RevenueAggregator.aggregate(scope);
        int written = writer.write(decision, computed);
        return new ScopeOutcome(financialYear, true, written);
    }

    /** What one scope's rebuild did. */
    public record ScopeOutcome(String financialYear, boolean published, int rowsWritten) {
    }

    /**
     * What a batch's rebuild did.
     *
     * <p>{@code blocked} is a list rather than a count so that an operator reading a run can see
     * <em>which</em> year was withheld. "Two years blocked" is not actionable; "2023-24 blocked" is.
     */
    public record Result(List<String> rebuilt, List<String> blocked, int rowsWritten) {

        /** True when at least one affected year could not be republished. */
        public boolean hasWithheldScopes() {
            return !blocked.isEmpty();
        }
    }
}

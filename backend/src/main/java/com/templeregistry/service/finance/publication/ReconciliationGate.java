package com.templeregistry.service.finance.publication;

import com.templeregistry.entity.finance.FinReconciliationResult;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.entity.finance.enums.PeriodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether one temple's figures for one financial year may be published (FIN-061).
 *
 * <p>FIN-060 records what reconciliation found; this decides what that means. Until now a
 * {@code FAILED} result and a clean one led to exactly the same outcome, because nothing read
 * them — the limitation the handoff recorded as "{@code RECONCILE_FAILED} blocks nothing".
 *
 * <h2>The decision is derived, never stored</h2>
 *
 * <p>There is no decision table and no decision status column. The verdict is computed from
 * {@code fin_reconciliation_result} every time it is asked for, which makes it idempotent by
 * construction, impossible to leave stale, and impossible to put in contradiction with the
 * evidence it is drawn from. A stored verdict would be a second status system that has to be kept
 * in agreement with the first one by hand, and the first thing to go wrong with it would be a
 * period published under a verdict written before the batch that changed it.
 *
 * <h2>Four outcomes, and only one of them publishes nothing new</h2>
 *
 * <pre>
 *   PASSED         -> publish. Every check that ran agreed.
 *   NOT_AVAILABLE  -> publish, flagged. Checks could not be made; what did run agreed.
 *   FAILED         -> DO NOT publish. A check found a real disagreement.
 *   PENDING        -> DO NOT publish. Figures exist that nothing has verified.
 * </pre>
 *
 * <p>These are the four values the API contract already documents for its {@code reconciliation}
 * field, which is why no new vocabulary was invented for them (FIN-D-057).
 *
 * <p><b>{@code NOT_AVAILABLE} publishes.</b> No production connector implements
 * {@code sourceTotals()} yet, so blocking on it would publish nothing at all, forever, for every
 * temple — and the figures in question were loaded correctly. What is withheld is the *claim* that
 * they were verified, which the returned status carries (FIN-D-054).
 *
 * <p><b>{@code PENDING} does not publish.</b> Absence of a result is not absence of a problem. A
 * batch that loaded facts and then failed before reconciliation leaves exactly this state, and
 * treating it as publishable is how unverified figures reach a dashboard.
 *
 * <h2>Blocking means "keep the previous figures", not "fail"</h2>
 *
 * <p>Per the architecture: aggregates are only replaced after reconciliation passes, so a blocked
 * period leaves the last good aggregates visible and marks the temple stale rather than wrong.
 * This class therefore withholds a replacement; it never deletes, restates or overwrites anything,
 * and it holds no reference to a repository that could.
 */
@Service
public class ReconciliationGate {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationGate.class);

    /** Batch-scoped checks are recorded against the whole of what a batch touched. */
    private static final PeriodType BATCH_SCOPE = PeriodType.FULL_HISTORY;
    private static final String BATCH_SCOPE_KEY = "ALL";

    private final FinReconciliationResultRepository results;
    private final FinRevenueFactRepository facts;

    public ReconciliationGate(FinReconciliationResultRepository results,
                              FinRevenueFactRepository facts) {
        this.results = results;
        this.facts = facts;
    }

    /**
     * Whether this temple's figures for this financial year, from this source, may be published.
     *
     * <p>Read-only and non-transactional by intent: it takes no locks, writes nothing, and two
     * callers asking at once cannot produce contradictory states because neither changes anything.
     * Concurrency safety here is a property of having no mutable state, not of a lock.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Decision evaluate(long templeId, long sourceSystemId, String financialYear) {
        List<Long> contributingBatches =
                facts.findContributingBatchIds(templeId, sourceSystemId, financialYear);

        if (contributingBatches.isEmpty()) {
            return Decision.pending(templeId, sourceSystemId, financialYear, List.of(),
                    List.of("No canonical figures exist for " + financialYear
                            + " from this source, so there is nothing to publish."));
        }

        List<String> blocking = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        // A batch that contributed figures and was never reconciled is the PENDING case, and it
        // is checked first: its absence of evidence cannot be outvoted by another batch's pass.
        List<FinReconciliationResult> batchScoped =
                results.findBySyncBatchIdInOrderByIdAsc(contributingBatches);
        List<Long> unreconciled = contributingBatches.stream()
                .filter(batchId -> batchScoped.stream()
                        .noneMatch(row -> batchId.equals(row.getSyncBatchId())))
                .toList();
        if (!unreconciled.isEmpty()) {
            return Decision.pending(templeId, sourceSystemId, financialYear, contributingBatches,
                    List.of("Batch(es) " + unreconciled + " loaded figures into " + financialYear
                            + " and were never reconciled. Unverified figures are not published."));
        }

        // Batch-scoped checks: each contributing batch's own completeness stands on its own. A
        // batch that lost rows taints every period it fed, however well that period's totals agree.
        batchScoped.stream()
                .filter(row -> row.getPeriodType() == BATCH_SCOPE
                        && BATCH_SCOPE_KEY.equals(row.getPeriodKey()))
                .forEach(row -> collect(row, "batch " + row.getSyncBatchId(), blocking, unavailable));

        // Period-scoped checks: the newest verdict per question wins, because a later batch may
        // have corrected what an earlier one found. Rows arrive id-ascending, so the last wins.
        latestPerQuestion(results.findByTempleIdAndSourceSystemIdAndPeriodTypeAndPeriodKeyOrderByIdAsc(
                templeId, sourceSystemId, PeriodType.FINANCIAL_YEAR, financialYear))
                .forEach(row -> collect(row, financialYear, blocking, unavailable));

        if (!blocking.isEmpty()) {
            log.warn("[FinanceGate] Publication blocked for temple {} source {} {}: {}",
                    templeId, sourceSystemId, financialYear, blocking);
            return new Decision(templeId, sourceSystemId, financialYear, ReconciliationStatus.FAILED,
                    false, List.copyOf(blocking), contributingBatches);
        }
        if (!unavailable.isEmpty()) {
            return new Decision(templeId, sourceSystemId, financialYear,
                    ReconciliationStatus.NOT_AVAILABLE, true, List.copyOf(unavailable),
                    contributingBatches);
        }
        return new Decision(templeId, sourceSystemId, financialYear, ReconciliationStatus.PASSED,
                true, List.of("Every check that ran agreed."), contributingBatches);
    }

    /**
     * The same decision, as a guard.
     *
     * <p>For a caller whose next statement would replace published figures. Throwing rather than
     * returning false is what makes forgetting to check impossible to do quietly — the aggregate
     * writer that skips this does not compile differently, but the one that calls it and ignores
     * the result cannot exist.
     *
     * @throws PublicationBlockedException if the period must not be published
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Decision requirePublishable(long templeId, long sourceSystemId, String financialYear) {
        Decision decision = evaluate(templeId, sourceSystemId, financialYear);
        if (!decision.publishable()) {
            throw new PublicationBlockedException(decision);
        }
        return decision;
    }

    /**
     * Keeps the newest row per question.
     *
     * <p>A question is (check type, metric). Two batches that both reconciled FY2025-26 each wrote
     * their own answer to the same question, and the later one is the current truth — otherwise a
     * corrected batch could never clear a variance an earlier one recorded.
     */
    private Collection<FinReconciliationResult> latestPerQuestion(List<FinReconciliationResult> rows) {
        Map<String, FinReconciliationResult> newest = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(FinReconciliationResult::getId))
                .forEach(row -> newest.put(row.getCheckType() + "|" + row.getMetric(), row));
        return newest.values();
    }

    private void collect(FinReconciliationResult row, String scope,
                         List<String> blocking, List<String> unavailable) {
        String line = scope + ": " + row.getCheckType() + "/" + row.getMetric() + " — "
                + row.getStatusReason();
        if (row.getStatus() == ReconciliationStatus.FAILED) {
            blocking.add(line);
        } else if (row.getStatus() == ReconciliationStatus.NOT_AVAILABLE) {
            unavailable.add(line);
        }
    }

    /**
     * Why a period may or may not be published, with the evidence that decided it.
     *
     * @param status         the documented API vocabulary: PASSED, NOT_AVAILABLE, FAILED, PENDING
     * @param publishable    false for FAILED and PENDING, and for nothing else
     * @param reasons        one line per check that drove the verdict, quotable to an operator
     * @param contributingBatchIds the batches whose facts are in this period, for tracing
     */
    public record Decision(long templeId,
                           long sourceSystemId,
                           String financialYear,
                           ReconciliationStatus status,
                           boolean publishable,
                           List<String> reasons,
                           List<Long> contributingBatchIds) {

        static Decision pending(long templeId, long sourceSystemId, String financialYear,
                                List<Long> batches, List<String> reasons) {
            return new Decision(templeId, sourceSystemId, financialYear,
                    ReconciliationStatus.PENDING, false, List.copyOf(reasons), List.copyOf(batches));
        }

        /** One line an operator can act on, without opening the database. */
        public String explain() {
            return "temple " + templeId + " source " + sourceSystemId + " " + financialYear
                    + ": " + status + (publishable ? " (publishable)" : " (BLOCKED)")
                    + " — " + String.join("; ", reasons);
        }
    }

    /** Raised instead of publishing figures that reconciliation does not stand behind. */
    public static class PublicationBlockedException extends RuntimeException {
        private final transient Decision decision;

        public PublicationBlockedException(Decision decision) {
            super("Publication blocked. " + decision.explain());
            this.decision = decision;
        }

        public Decision decision() {
            return decision;
        }
    }
}

package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.enums.FinanceCapability;

/**
 * The one call an ingestion load makes into aggregation (Phase 0 contract).
 *
 * <p>This interface exists so that the two halves of the finance build can be
 * written in parallel without either calling the other implementation. A load
 * stage finishes writing facts and asks for the affected period to be
 * republished; it does not know how aggregation works, which tables it writes,
 * or that a publication gate exists.
 *
 * <h2>Why a port and not a direct call</h2>
 *
 * <p>Aggregation owns {@code ReconciliationGate}, and a load stage that called
 * the aggregator directly would sooner or later be tempted to skip it. Behind
 * this interface, the gate is not skippable: every implementation is required
 * to consult it, and an implementation that publishes a blocked period is
 * wrong regardless of what the caller asked for.
 *
 * <p>It also means the ingestion side can be tested against a recording fake
 * that asserts <em>which</em> periods were requested, which is the property
 * that actually matters at the seam and is far cheaper to assert than a rebuilt
 * aggregate.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>Idempotent.</b> Calling it twice for one period produces the same
 *       rows. A rebuild is deterministic from facts.</li>
 *   <li><b>Gated.</b> The implementation consults {@code ReconciliationGate}
 *       per period. A blocked period keeps its previous figures rather than
 *       gaining corrupted ones, and that is not an error the caller sees.</li>
 *   <li><b>Never throws for a blocked period.</b> The load succeeded; only
 *       publication was withheld. A caller that treated withholding as failure
 *       would roll back correctly loaded facts.</li>
 *   <li><b>Called after the facts are committed</b>, not inside the same write.
 *       Aggregation reads what the load wrote.</li>
 * </ul>
 */
public interface AggregateRefresh {

    /**
     * Republish the aggregates for one temple, source and financial year.
     *
     * <p>A batch that touched three financial years calls this three times.
     * The financial year is the unit because it is the scope
     * {@code ReconciliationGate} is asked about; month rows inside it are
     * rebuilt by the implementation and inherit the year verdict.
     *
     * @param templeId       registry temple id
     * @param sourceSystemId which channel produced the facts; aggregates are never
     *                       summed across sources by the aggregator
     * @param capability     which subject to rebuild, e.g. {@code REVENUE} or {@code EXPENSE}.
     *                       An implementation that does not handle a capability returns
     *                       {@link Outcome#NOT_APPLICABLE} rather than failing, because a
     *                       subject with no aggregate table is a normal state
     * @param financialYear  canonical string, e.g. {@code 2025-26}
     * @return what happened, for the caller to log. Never null
     */
    Outcome refresh(Long templeId, Long sourceSystemId, FinanceCapability capability,
                    String financialYear);

    /** What a refresh did. None of these is an error. */
    enum Outcome {
        /** Aggregates for the period were rebuilt and published. */
        PUBLISHED,
        /** The gate withheld publication. Previous figures remain in place. */
        BLOCKED,
        /** No facts contributed to this period, so nothing was written. */
        NOTHING_TO_DO,
        /** This capability has no aggregate table. Reports read its facts directly. */
        NOT_APPLICABLE
    }
}

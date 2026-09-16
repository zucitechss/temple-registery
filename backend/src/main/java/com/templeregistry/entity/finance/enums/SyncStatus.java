package com.templeregistry.entity.finance.enums;

/**
 * Lifecycle of a sync batch. Deliberately mirrors the {@code email_outbox}
 * state machine this codebase already operates, including exponential back-off
 * and a terminal dead-letter state.
 *
 * <pre>
 *   PENDING -&gt; RUNNING -&gt; SUCCESS
 *                      -&gt; FAILED           -&gt; (retry) -&gt; RUNNING
 *                      -&gt; RECONCILE_FAILED
 *                      -&gt; DEAD_LETTER
 * </pre>
 */
public enum SyncStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    /** Extraction or load failed; eligible for retry until max_retries. */
    FAILED,
    /**
     * Data loaded, but source and central totals disagree. Distinct from FAILED
     * because the rows are present and inspectable -- what is blocked is
     * publication of the affected aggregates, not the load itself.
     */
    RECONCILE_FAILED,
    /** Retries exhausted; requires human intervention. */
    DEAD_LETTER,
    /** Superseded or manually stopped before completion. */
    CANCELLED
}

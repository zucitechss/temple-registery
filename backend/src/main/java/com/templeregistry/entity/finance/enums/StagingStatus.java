package com.templeregistry.entity.finance.enums;

/**
 * Processing state of a staged source record.
 *
 * <p>Four states, each owned by exactly one pipeline stage:
 *
 * <pre>
 *   RECEIVED -&gt; VALID     validation (FIN-053)
 *   RECEIVED -&gt; REJECTED  validation (FIN-053), with a reason
 *   VALID    -&gt; LOADED    the load (FIN-056)
 * </pre>
 *
 * <p>Transitions are monotonic: nothing returns to {@link #RECEIVED} and
 * {@link #REJECTED} is terminal. Re-processing a record means extracting it again
 * under a new batch, not resetting a state, so the record of what was rejected
 * and why survives the retry.
 *
 * <p>There is deliberately no {@code DUPLICATE} state. A repeated record within
 * one batch cannot land at all -- {@code uk_fsr_batch_record} rejects it -- and a
 * record repeated across batches is a legitimate restatement, not a duplicate.
 */
public enum StagingStatus {
    /** Landed, untouched. Nothing has judged it yet, and nothing may report it. */
    RECEIVED,
    /** Structurally usable. Says nothing about whether the figures are correct. */
    VALID,
    /** Rejected with a reason; the coded form and payload are in {@code fin_sync_error}. */
    REJECTED,
    /** Contributed to a canonical fact. */
    LOADED
}

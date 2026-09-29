package com.templeregistry.entity.finance.enums;

/**
 * What happened to one day of one capability for one temple (FR17, FR20).
 *
 * <p>{@link #NIL_RETURN} is the value that keeps the feature honest. Without it,
 * a temple that genuinely had nothing to report is indistinguishable from one
 * that forgot, and gets alerted for complying. That is the same conflation of
 * absent and zero that ADR-007 exists to prevent, arriving through the back
 * door. A nil return fulfils the day and writes no fact, because no transaction
 * occurred.
 *
 * <p>{@link #WAIVED} covers a day on which nothing was expected. Without it,
 * every closure day produces an alert nobody can clear.
 */
public enum DataSubmissionStatus {
    /** The day is owed and not yet accounted for. Its cutoff has not passed. */
    EXPECTED,
    /** Data arrived. {@code fulfilled_by_batch_id} names the batch that delivered it. */
    SUBMITTED,
    /** The temple stated there was nothing to report. Fulfils the day; writes no fact. */
    NIL_RETURN,
    /** The cutoff passed with nothing submitted. The only status that opens an alert. */
    MISSED,
    /** Nothing was expected on this day. Someone said so, and is recorded. */
    WAIVED;

    /** Whether this day counts against the temple. The alert gap is exactly these. */
    public boolean isOutstanding() {
        return this == MISSED;
    }

    /** Whether the obligation for this day is discharged, however it was discharged. */
    public boolean isFulfilled() {
        return this == SUBMITTED || this == NIL_RETURN || this == WAIVED;
    }
}

package com.templeregistry.service.finance.onboarding;

/**
 * How ready a source system's <em>configuration</em> is, and how serious one finding is
 * (FIN-140 slice 140-A).
 *
 * <h2>One enum, used at two levels</h2>
 *
 * <p>The same three values grade an individual finding and the whole report: a report's status is
 * the worst status among its findings, and {@link #READY} when there are none. A separate
 * severity enum would have held exactly the same two write-values ({@code WARNING},
 * {@code BLOCKED}) under different names, which is the duplication the task brief asks to avoid.
 *
 * <h2>Why there is no NOT_READY</h2>
 *
 * <p>The brief lists four labels — READY, NOT_READY, BLOCKED, WARNING. {@code NOT_READY} and
 * {@code BLOCKED} name one state: a configuration with an unresolved blocking finding is not
 * ready, and a configuration that is not ready is blocked by something. Carrying both would leave
 * every future reader deciding which to write, and every future test asserting one of two
 * spellings of the same fact. The distinction the brief is reaching for — may this source be
 * switched on? — is answered by a boolean beside the status, not by a fourth value.
 *
 * <h2>What none of these mean</h2>
 *
 * <p>{@code READY} means the configuration is internally coherent. It does <b>not</b> mean the
 * source is reachable, that the connector exists, or that a single figure has ever been read.
 * Nothing in the registry runtime can establish any of those (ADR-001); see
 * {@code SourceSystemReadinessResponse.connectivityVerified}.
 */
public enum ReadinessStatus {

    /** No finding of any kind. The configuration is coherent as far as registry data can tell. */
    READY,

    /** Something is legal but probably wrong. Does not prevent activation. */
    WARNING,

    /** Something would certainly fail, or would publish a wrong figure. Prevents activation. */
    BLOCKED;

    /** The worse of two statuses, used to fold a list of findings into one verdict. */
    public ReadinessStatus worseOf(ReadinessStatus other) {
        return compareTo(other) >= 0 ? this : other;
    }
}

package com.templeregistry.entity.finance.enums;

/**
 * Outcome of comparing a source-computed total against the centrally-computed one.
 *
 * <p>{@code NOT_AVAILABLE} exists so that "the source could not give us a total"
 * is never silently recorded as a pass.
 */
public enum ReconciliationStatus {
    PASSED,
    FAILED,
    NOT_AVAILABLE,

    /**
     * Nothing has verified these figures yet (FIN-061).
     *
     * <p><b>Derived, and never written to {@code fin_reconciliation_result}.</b> A stored row
     * always records a check that ran; this is what the absence of such a row means, and it is a
     * verdict {@link com.templeregistry.service.finance.publication.ReconciliationGate} returns
     * rather than a state anything persists.
     *
     * <p>It is in this enum rather than a parallel one because the API contract already documents
     * {@code PASSED · FAILED · NOT_AVAILABLE · PENDING} as one vocabulary for the
     * {@code reconciliation} field a report carries, and two enums covering one documented
     * vocabulary is the duplicate status system worth more than the extra constant costs.
     *
     * <p>It blocks publication. Absence of a result is not absence of a problem.
     */
    PENDING
}

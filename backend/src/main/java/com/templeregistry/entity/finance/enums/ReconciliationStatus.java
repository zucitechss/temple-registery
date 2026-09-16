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
    NOT_AVAILABLE
}

package com.templeregistry.entity.finance.enums;

/**
 * Progress of the work a DC-approved fund pays for (FR5, FR6).
 *
 * <p>FR6 is the fund report filtered to {@link #IN_PROGRESS}. That is the whole
 * of FR6, which is why no separate work-project table exists: it would carry a
 * copy of the fund name, amount and approver, and the copies would disagree.
 */
public enum WorkStatus {
    NOT_STARTED,
    /** FR6 reports exactly these. */
    IN_PROGRESS,
    COMPLETED,
    ON_HOLD,
    CANCELLED
}

package com.templeregistry.entity.finance.enums;

/**
 * How bad a missed-entry gap is (FR18).
 *
 * <p>Derived from the current gap on every evaluation, never incremented. That
 * is what lets a partial backfill DE-ESCALATE a HIGH alert to LOW instead of
 * leaving it stuck, and what makes the hourly job idempotent.
 */
public enum AlertSeverity {
    /** One to seven consecutive missed days. */
    LOW,
    /** Eight or more. FR18 escalates here from day 8. */
    HIGH;

    /** The FR18 rule, in the one place it is written. */
    public static AlertSeverity forConsecutiveMissedDays(int days) {
        return days >= ESCALATION_THRESHOLD_DAYS ? HIGH : LOW;
    }

    /** Day 8 is the first HIGH day, so seven days of grace. */
    public static final int ESCALATION_THRESHOLD_DAYS = 8;
}

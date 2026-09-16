package com.templeregistry.entity.finance.enums;

/** Granularity of a reconciliation or aggregate period key. */
public enum PeriodType {
    /** period_key = yyyy-MM-dd */
    DAY,
    /** period_key = yyyy-MM */
    MONTH,
    /** period_key = yyyy-yy, e.g. 2025-26 */
    FINANCIAL_YEAR,
    /** period_key = ALL */
    FULL_HISTORY
}

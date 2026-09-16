package com.templeregistry.entity.finance.enums;

/**
 * A distinct financial question the platform may be able to answer for a temple.
 *
 * <p>Capabilities are declared per temple in {@code fin_temple_capability} with a
 * {@link DataAvailability} and a user-facing reason. Reports consult the
 * declaration rather than inferring anything from the presence or absence of rows
 * -- see ADR-007.
 *
 * <p>A capability that is {@code NOT_AVAILABLE} is NOT zero. Kollur records no
 * expenditure at all; rendering its expenditure as a zero would tell a Deputy
 * Commissioner that the temple spends nothing.
 */
public enum FinanceCapability {
    REVENUE,
    SEVA,
    DONATION,
    PRASADAM_SALE,
    PAYMENT_MODE,
    CANCELLATION,

    PRECIOUS_METAL_COUNT,
    PRECIOUS_METAL_WEIGHT,
    PRECIOUS_METAL_VALUE,

    NIRANTARA_SUBSCRIPTION,
    NIRANTARA_PAYMENT,
    NIRANTARA_SCHEDULE,
    /** Whether a booked perpetual seva was actually performed. Unknown for Kollur. */
    NIRANTARA_EXECUTION,

    EXPENSE,
    EXPENSE_CATEGORY,
    GRANT,
    GRANT_UTILISATION,
    WORKS,
    IN_KIND_DONATION
}

package com.templeregistry.connector.finance;

/**
 * A quantity that can be computed independently on both sides of reconciliation.
 *
 * <p>Metrics are deliberately capability-agnostic. {@code GROSS_AMOUNT} means revenue under
 * the {@code REVENUE} capability and payments under {@code NIRANTARA_PAYMENT}; the pair
 * (capability, metric) is what identifies a comparison. That keeps this enum small and
 * avoids a combinatorial list that would grow with every capability.
 *
 * <p>Derived quantities are absent on purpose. Net amount is gross minus cancelled, so
 * reconciling it proves nothing that reconciling its two inputs does not already prove --
 * it would simply be a third chance to agree with ourselves.
 */
public enum ReconMetric {

    /** Number of source records in scope. Compared against staging row count. */
    RECORD_COUNT,

    /** Sum of the authoritative amount field, before cancellations are deducted. */
    GROSS_AMOUNT,

    /** Number of records the source marks as cancelled. */
    CANCELLED_COUNT,

    /** Sum of amounts belonging to cancelled records. */
    CANCELLED_AMOUNT,

    /**
     * Sum of a non-monetary quantity, with the unit implied by the capability -- grams for
     * precious metal weight, item count for donated articles.
     */
    QUANTITY
}

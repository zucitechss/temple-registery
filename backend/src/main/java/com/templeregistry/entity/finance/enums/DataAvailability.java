package com.templeregistry.entity.finance.enums;

/**
 * Whether a temple can answer a given financial question -- modelled as data,
 * not as a UI string (ADR-007).
 *
 * <p>{@code NOT_AVAILABLE} and a value of zero are different kinds of statement:
 * zero asserts "there were none", {@code NOT_AVAILABLE} asserts "we do not know".
 * Converting the second into the first misinforms the reader, so amount fields
 * are nullable throughout and this enum travels with every reported metric.
 */
public enum DataAvailability {
    /** Source records this fully for the declared coverage window. */
    AVAILABLE,
    /** Recorded for part of the window, or with known gaps inside it. */
    PARTIALLY_AVAILABLE,
    /** The source system does not record this at all. */
    NOT_AVAILABLE,
    /** Meaningless for this temple (e.g. hall booking at a temple with no hall). */
    NOT_APPLICABLE
}

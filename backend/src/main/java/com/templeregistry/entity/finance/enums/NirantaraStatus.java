package com.templeregistry.entity.finance.enums;

/** Standing of a perpetual seva booking (FR11). */
public enum NirantaraStatus {
    ACTIVE,
    /** Payments stopped; the arrangement was not formally ended. */
    LAPSED,
    CANCELLED,
    /** Ran to a stated end date. Uncommon: Nirantara normally means perpetual. */
    COMPLETED
}

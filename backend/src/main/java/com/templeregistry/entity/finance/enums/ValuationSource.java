package com.templeregistry.entity.finance.enums;

/**
 * Who says what a precious item is worth (FR7).
 *
 * <p>Recorded beside the figure so that a value someone declared is never read
 * as a value someone assessed. The specification sources purity and value from
 * temple management input, which in practice means {@link #DECLARED} far more
 * often than {@link #ASSESSED}, and a DC dashboard that showed the two
 * identically would overstate how much is known.
 */
public enum ValuationSource {
    /** Stated by the donor or by temple staff. No independent basis. */
    DECLARED,
    /** Valued by an assayer or appraiser. */
    ASSESSED,
    /** Computed from weight, purity and a market rate on a stated date. */
    MARKET_RATE
}

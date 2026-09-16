package com.templeregistry.entity.finance.enums;

/**
 * How a payment reached the temple, as recorded centrally.
 *
 * <p>{@link #UNRECORDED} exists so that a source which does not capture payment
 * mode can say so. It is not a synonym for {@code CASH}: a receipt where the
 * operator left the card field blank is indistinguishable from a genuine cash
 * sale, and calling both cash would assert a cash ratio the source never
 * measured -- a figure with real consequences for a temple under financial
 * oversight.
 *
 * <p>Always paired with {@link PaymentModeConfidence}, which says whether the
 * source stated the mode or a connector derived it.
 */
public enum PaymentMode {
    CASH,
    CARD,
    UPI,
    BANK_TRANSFER,
    CHEQUE,
    /** A mode the source records explicitly but that maps to none of the above. */
    OTHER,
    /** The source does not record how this payment was made. Never treat as CASH. */
    UNRECORDED
}

package com.templeregistry.entity.finance.enums;

/**
 * Whether a payment mode was read from the source or derived by a connector.
 *
 * <p>Carried alongside the mode itself because the two answer different
 * questions. A dashboard splitting collections by payment mode is reporting a
 * measurement when this is {@link #RECORDED} and a connector's reasoning when it
 * is {@link #INFERRED}, and the reader is entitled to know which.
 */
public enum PaymentModeConfidence {
    /** The source system stated the payment mode. */
    RECORDED,
    /** A connector derived the mode from other evidence; the source did not state it. */
    INFERRED
}

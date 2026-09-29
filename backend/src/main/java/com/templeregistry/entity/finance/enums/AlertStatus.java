package com.templeregistry.entity.finance.enums;

/**
 * Whether a missed-entry alert is still standing (FR20).
 *
 * <p>There is deliberately no ACKNOWLEDGED. FR20 says the alert clears once the
 * missing entry is submitted; a dismissable alert could be cleared without the
 * data ever arriving, which would make the DC dashboard report compliance that
 * did not happen.
 */
public enum AlertStatus {
    OPEN,
    CLOSED
}

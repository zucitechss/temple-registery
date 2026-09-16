package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.FinanceCapability;

/**
 * Thrown when a connector is asked for a capability it does not declare.
 *
 * <p>Failing loudly is the point. A connector that returned an empty stream for an
 * unsupported capability would be indistinguishable from a temple that genuinely had no
 * data, and the pipeline would record zero where it should record NOT_AVAILABLE.
 */
public class UnsupportedCapabilityException extends RuntimeException {

    private final transient FinanceCapability capability;

    public UnsupportedCapabilityException(String connectorId, FinanceCapability capability) {
        super("Connector [" + connectorId + "] does not support capability [" + capability
                + "]. A capability that is not declared must be reported as NOT_AVAILABLE, never as zero.");
        this.capability = capability;
    }

    public FinanceCapability getCapability() {
        return capability;
    }
}

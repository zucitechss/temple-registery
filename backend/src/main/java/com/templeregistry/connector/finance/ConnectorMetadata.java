package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;

/**
 * Identifies a connector implementation to the framework.
 *
 * <p>{@link #connectorId()} is the value a source system stores in
 * {@code fin_source_system.connector_bean}; it is how configuration names the code that
 * will read it. Declaring the {@link #connectorType()} lets onboarding reject a source
 * configured as one integration mechanism but pointed at a connector implementing another
 * -- a configuration error that would otherwise surface as a confusing runtime failure.
 *
 * <p>No source technology is declared here. A file-drop or push-agent connector has no
 * database technology, and requiring one would force a meaningless value; technology is a
 * property of the source system, not of the connector.
 */
public record ConnectorMetadata(String connectorId, ConnectorType connectorType, String description) {

    public ConnectorMetadata {
        if (connectorId == null || connectorId.isBlank()) {
            throw new IllegalArgumentException(
                    "connectorId must not be blank: it is what fin_source_system.connector_bean refers to.");
        }
        if (connectorType == null) {
            throw new IllegalArgumentException("connectorType must not be null.");
        }
    }
}

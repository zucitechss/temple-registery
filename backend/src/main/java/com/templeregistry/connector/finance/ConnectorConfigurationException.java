package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;

import java.util.Collection;

/**
 * Thrown when a source system names a connector the running worker cannot honour.
 *
 * <p>Configuration saying {@code connector_bean = X} does not mean X exists. The gap
 * between <em>configured</em> and <em>registered</em> is the failure this exception makes
 * visible, and it must stay a failure: returning nothing, returning a no-op connector or
 * skipping the source would let a temple appear fully onboarded while its dashboard
 * silently reported no revenue. An operator would then be looking at a figure that means
 * "nobody ran anything" while it reads as "nothing happened".
 *
 * <p>Messages name the connector identifier and the source system identity, because that
 * is what an operator needs to find the offending row. They never name a credential
 * reference or any value resolved from one.
 */
public class ConnectorConfigurationException extends RuntimeException {

    private final transient String connectorBean;

    private ConnectorConfigurationException(String connectorBean, String message) {
        super(message);
        this.connectorBean = connectorBean;
    }

    /** The connector identifier taken from configuration, for logging and alerting. */
    public String getConnectorBean() {
        return connectorBean;
    }

    /** Configuration names a connector that no bean in this runtime provides. */
    public static ConnectorConfigurationException notRegistered(String connectorBean,
                                                                SourceSystemDescriptor source,
                                                                Collection<String> registered) {
        return new ConnectorConfigurationException(connectorBean,
                "Finance connector [" + connectorBean + "] is configured for source system ["
                        + identify(source) + "] but is not registered in this runtime. Registered connectors: "
                        + (registered.isEmpty() ? "[none]" : registered)
                        + ". A configured connector that does not exist is a configuration error: the source "
                        + "must not be skipped and must not be reported as having no data.");
    }

    /** A source system is enabled for synchronization without naming any connector. */
    public static ConnectorConfigurationException notConfigured(SourceSystemDescriptor source) {
        return new ConnectorConfigurationException(null,
                "Source system [" + identify(source) + "] names no finance connector. "
                        + "fin_source_system.connector_bean must identify a registered connector before "
                        + "this source can be synchronized.");
    }

    /**
     * Configuration and implementation disagree about the integration mechanism.
     *
     * <p>Worth failing on rather than tolerating: the declared type is what operations,
     * firewall approvals and the onboarding record are based on, so a source approved as a
     * delivered extract must not quietly be read by a connector that reaches into the
     * temple instead.
     */
    public static ConnectorConfigurationException typeMismatch(String connectorBean,
                                                               SourceSystemDescriptor source,
                                                               ConnectorType implemented) {
        return new ConnectorConfigurationException(connectorBean,
                "Finance connector [" + connectorBean + "] implements integration mechanism ["
                        + implemented + "] but source system [" + identify(source)
                        + "] is configured as [" + source.connectorType()
                        + "]. Resolve the disagreement in configuration rather than in code.");
    }

    /** A connector was registered under a name that disagrees with its own metadata. */
    public static ConnectorConfigurationException ambiguousIdentity(String registeredName, String declaredId) {
        return new ConnectorConfigurationException(registeredName,
                "Finance connector registered as [" + registeredName + "] declares connectorId ["
                        + declaredId + "]. Configuration refers to exactly one name, so the two must match "
                        + "or a source system could name a connector that resolves to nothing.");
    }

    private static String identify(SourceSystemDescriptor source) {
        return source == null
                ? "unknown"
                : source.systemCode() + " id=" + source.sourceSystemId() + " temple=" + source.templeId();
    }
}

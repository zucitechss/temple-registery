package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves the connector identifier held in {@code fin_source_system.connector_bean} to the
 * connector implementation that will read that source.
 *
 * <p>This is the whole of its job. It creates nothing, opens nothing, resolves no
 * credential, chooses no integration mechanism and runs no synchronization. Given a name it
 * returns the implementation registered under that name, or it fails.
 *
 * <h2>Configured is not registered</h2>
 *
 * <p>A source system row naming a connector is a statement of intent by whoever onboarded
 * the temple. Whether the code exists is a separate fact, decided at build and deployment
 * time. When the two disagree the only safe outcome is a loud failure, so there is no
 * method here returning {@code null}, an empty {@link java.util.Optional} or a placeholder
 * connector -- any of which would let a batch complete "successfully" having read nothing,
 * and a temple's dashboard report emptiness that is indistinguishable from a real zero.
 *
 * <h2>Nothing source-specific</h2>
 *
 * <p>Resolution is a map lookup on the configured identifier. There is no branch, no switch
 * and no knowledge of any individual temple, so onboarding the second, tenth and hundredth
 * source system adds a connector bean and a configuration row and changes nothing here.
 *
 * <p>Registered per FIN-D-008 as an explicit {@code @Bean} in the sync-worker runtime from
 * the connector beans declared there; this class performs no component scanning, which
 * would let an unannotated connector class drift into the registry runtime.
 *
 * <p>Immutable after construction and safe for concurrent use.
 */
public final class ConnectorRegistry {

    private final Map<String, TempleFinanceConnector> connectors;

    /**
     * @param connectorsByName registered connectors keyed by the name configuration refers to
     *                         (the Spring bean name in the worker runtime)
     * @throws ConnectorConfigurationException if a connector's declared
     *         {@link ConnectorMetadata#connectorId()} differs from the name it is registered
     *         under -- configuration has one field, so two names would make it ambiguous
     */
    public ConnectorRegistry(Map<String, TempleFinanceConnector> connectorsByName) {
        Map<String, TempleFinanceConnector> copy =
                new TreeMap<>(connectorsByName == null ? Map.of() : connectorsByName);

        copy.forEach((name, connector) -> {
            String declaredId = connector.metadata().connectorId();
            if (!name.equals(declaredId)) {
                throw ConnectorConfigurationException.ambiguousIdentity(name, declaredId);
            }
        });

        this.connectors = Collections.unmodifiableMap(copy);
    }

    /** Identifiers a source system may legitimately name in this runtime. Never null. */
    public Set<String> registeredConnectorIds() {
        return connectors.keySet();
    }

    /** Whether a name is registered, for onboarding validation that wants to report rather than throw. */
    public boolean isRegistered(String connectorBean) {
        return connectorBean != null && connectors.containsKey(connectorBean);
    }

    /**
     * The connector registered under {@code connectorBean}, verified against the integration
     * mechanism the source system is configured for.
     *
     * @return the registered connector, never {@code null}
     * @throws ConnectorConfigurationException if no connector is registered under that name,
     *         if the source names no connector at all, or if the registered connector
     *         implements a different {@link ConnectorType} than the source declares
     */
    public TempleFinanceConnector resolve(String connectorBean, SourceSystemDescriptor source) {
        Objects.requireNonNull(source, "source descriptor is required to resolve a connector");

        if (connectorBean == null || connectorBean.isBlank()) {
            throw ConnectorConfigurationException.notConfigured(source);
        }

        TempleFinanceConnector connector = connectors.get(connectorBean);
        if (connector == null) {
            throw ConnectorConfigurationException.notRegistered(connectorBean, source, registeredConnectorIds());
        }

        ConnectorType implemented = connector.metadata().connectorType();
        if (implemented != source.connectorType()) {
            throw ConnectorConfigurationException.typeMismatch(connectorBean, source, implemented);
        }

        return connector;
    }
}

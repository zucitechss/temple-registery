package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.SourceTechnology;

/**
 * Immutable identification of the source system a connector is being asked to work with.
 *
 * <p>Deliberately carries <b>no connection information of any kind</b> -- no host, port,
 * URL, database name, username, password or connection string. {@link #credentialRef()} is
 * an alias, not a secret; a connector implementation exchanges it for real credentials
 * through the credential provider that exists only in the sync-worker runtime
 * (FIN-D-002, FIN-D-009).
 *
 * <p>This is a plain contract type rather than the {@code FinSourceSystem} entity. The
 * connector layer must not depend on JPA: an entity would drag persistence context,
 * lazy-loading and audit columns into code whose job is to talk to a foreign system, and
 * would let a connector accidentally write to the registry database.
 *
 * <p>{@link #sourceTempleCode()} is the temple key <em>as the source system knows
 * it</em>, which is rarely the registry id. Keeping both is what lets one source system
 * serve several temples.
 */
public record SourceSystemDescriptor(
        Long templeId,
        Long sourceSystemId,
        String systemCode,
        ConnectorType connectorType,
        SourceTechnology sourceTechnology,
        String sourceTempleCode,
        String credentialRef,
        String sourceTimezone) {

    public SourceSystemDescriptor {
        if (templeId == null) {
            throw new IllegalArgumentException("templeId must not be null.");
        }
        if (systemCode == null || systemCode.isBlank()) {
            throw new IllegalArgumentException("systemCode must not be blank.");
        }
        if (connectorType == null) {
            throw new IllegalArgumentException("connectorType must not be null.");
        }
        if (sourceTimezone == null || sourceTimezone.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceTimezone must not be blank: a transaction date read in the wrong zone can "
                            + "move a receipt across a financial-year boundary.");
        }
    }
}

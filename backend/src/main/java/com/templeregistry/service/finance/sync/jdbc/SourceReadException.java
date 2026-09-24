package com.templeregistry.service.finance.sync.jdbc;

/**
 * A read from a temple source system failed (FIN-040).
 *
 * <p>The connector contract has exceptions for a connector that is misconfigured
 * ({@code ConnectorConfigurationException}) and for a capability that was never declared
 * ({@code UnsupportedCapabilityException}). Neither describes a source that was reachable and
 * then refused, timed out, or turned out not to have the configured table. This is that case, and
 * it is a distinct one: the configuration may be perfectly valid and the source simply unwell.
 *
 * <p><b>The cause is kept; the driver's message is not repeated blindly.</b> A JDBC exception can
 * carry the connection URL, and some drivers put credentials in it. Messages built here name the
 * source system and what was being attempted, never the URL, the principal or the secret.
 */
public class SourceReadException extends RuntimeException {

    private final transient String systemCode;

    public SourceReadException(String systemCode, String message, Throwable cause) {
        super(message, cause);
        this.systemCode = systemCode;
    }

    /** Which source system failed, for logging and alerting. Never a credential. */
    public String getSystemCode() {
        return systemCode;
    }
}

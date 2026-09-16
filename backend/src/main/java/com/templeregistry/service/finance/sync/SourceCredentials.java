package com.templeregistry.service.finance.sync;

/**
 * Credentials for one temple source system, resolved at extraction time and held only for
 * the duration of a sync batch.
 *
 * <p>Shape is deliberately generic so that all four integration mechanisms fit without a
 * variant per mechanism:
 *
 * <ul>
 *   <li>{@code PULL_JDBC}   -- principal is a read-only database user, secret its password</li>
 *   <li>{@code SOURCE_API}  -- principal may be null, secret is an API token</li>
 *   <li>{@code PUSH_AGENT}  -- secret is the shared key an inbound agent authenticates with</li>
 *   <li>{@code FILE_DROP}   -- secret is a decryption or transfer key, or none is required</li>
 * </ul>
 *
 * <p><b>Never persisted and never serialised.</b> These values do not enter the registry
 * database, an API response, or any entity. {@code fin_source_system} stores only a
 * {@code credential_ref} alias, and resolving that alias to an actual secret is possible
 * only in the sync-worker runtime (FIN-D-002).
 *
 * <p>{@link #toString()} is overridden to redact the secret. That matters more than it
 * looks: the common way a credential reaches a log file is an exception message or a
 * debug statement interpolating an object that happened to contain one.
 */
public record SourceCredentials(String principal, String secret) {

    public SourceCredentials {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("SourceCredentials secret must not be blank.");
        }
    }

    /** True when this credential carries a user identity as well as a secret. */
    public boolean hasPrincipal() {
        return principal != null && !principal.isBlank();
    }

    @Override
    public String toString() {
        return "SourceCredentials[principal=" + (hasPrincipal() ? principal : "<none>") + ", secret=<redacted>]";
    }
}

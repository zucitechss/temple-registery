package com.templeregistry.service.finance.sync;

/**
 * Resolves a {@code credential_ref} alias into actual source-system credentials.
 *
 * <p>This is the seam Q5 needs. The storage mechanism is still undecided -- there is no
 * secrets manager in this deployment today -- so the platform commits to the abstraction
 * now and to an implementation later. Swapping the environment-backed implementation for
 * a vault-backed one is a single bean replacement in {@code SyncWorkerConfig}; nothing
 * that calls this interface changes.
 *
 * <p><b>Implementations exist only in the sync-worker runtime.</b> No bean of this type is
 * registered in the registry/API runtime, so there is no code path by which a web request
 * could obtain a temple credential. A regression test asserts that absence.
 *
 * <p>Implementations must never log a secret, never return a default or placeholder when a
 * credential is missing, and never write a resolved credential back to the database.
 */
public interface SourceCredentialProvider {

    /**
     * @param credentialRef the alias stored in {@code fin_source_system.credential_ref}
     * @return resolved credentials, never null
     * @throws CredentialNotConfiguredException if no credential is configured for the alias
     */
    SourceCredentials resolve(String credentialRef);

    /**
     * Whether a credential is configured, without resolving it.
     *
     * <p>Lets onboarding and health checks report "this source is not yet configured"
     * without materialising a secret in order to find out.
     */
    boolean isConfigured(String credentialRef);
}

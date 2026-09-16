package com.templeregistry.service.finance.sync;

/**
 * Thrown when a source system is asked to sync but no credential has been configured for
 * its {@code credential_ref}.
 *
 * <p>This is deliberately a hard failure rather than a fallback. A provider that quietly
 * substituted a default, an empty password, or a credential belonging to a different
 * system would turn a configuration mistake into either a failed connection blamed on the
 * temple, or -- worse -- a successful connection to something nobody intended.
 *
 * <p>The message names the reference and the expected property, never the value.
 */
public class CredentialNotConfiguredException extends RuntimeException {

    private final String credentialRef;

    public CredentialNotConfiguredException(String credentialRef, String expectedProperty) {
        super("No credential configured for source credentialRef [" + credentialRef
                + "]. Expected property [" + expectedProperty
                + "] to be supplied to the sync worker via environment or secret configuration. "
                + "Credentials must not be added to application.yml or to the registry database.");
        this.credentialRef = credentialRef;
    }

    public String getCredentialRef() {
        return credentialRef;
    }
}

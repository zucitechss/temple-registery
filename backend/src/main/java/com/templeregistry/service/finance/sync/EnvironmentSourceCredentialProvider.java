package com.templeregistry.service.finance.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * Resolves source credentials from the sync worker process environment.
 *
 * <p>Property convention, for {@code credential_ref = kollur-readonly}:
 *
 * <pre>
 *   trm.finance.source.kollur-readonly.principal
 *   trm.finance.source.kollur-readonly.secret
 * </pre>
 *
 * <p>In deployment these arrive as environment variables
 * ({@code TRM_FINANCE_SOURCE_KOLLUR_READONLY_SECRET} and so on) injected into the worker
 * process only. They are deliberately <b>not</b> declared in any committed YAML: a
 * property present in {@code application.yml} with a placeholder default is how the
 * existing {@code DB_USERNAME} / {@code DB_PASSWORD} fallbacks ended up in version
 * control, and temple credentials must not repeat that.
 *
 * <p>This is an interim implementation. When a secrets manager is chosen (Q5), it replaces
 * this class and nothing else changes -- callers depend on
 * {@link SourceCredentialProvider}, not on where the value came from.
 *
 * <p>Registered as a bean only under the {@code sync-worker} profile, by
 * {@code SyncWorkerConfig}. It is intentionally not a {@code @Component}: a stereotype
 * annotation would make it visible to the application-wide component scan, and a future
 * refactor that dropped the profile guard would silently place credential resolution
 * inside the registry runtime.
 */
@RequiredArgsConstructor
@Slf4j
public class EnvironmentSourceCredentialProvider implements SourceCredentialProvider {

    static final String PROPERTY_PREFIX = "trm.finance.source.";
    static final String PRINCIPAL_SUFFIX = ".principal";
    static final String SECRET_SUFFIX = ".secret";

    private final Environment environment;

    @Override
    public SourceCredentials resolve(String credentialRef) {
        String ref = requireRef(credentialRef);
        String secretProperty = PROPERTY_PREFIX + ref + SECRET_SUFFIX;

        String secret = environment.getProperty(secretProperty);
        if (secret == null || secret.isBlank()) {
            // Log the reference, never the property value.
            log.error("[FinanceSync] No credential configured for credentialRef [{}]", ref);
            throw new CredentialNotConfiguredException(ref, secretProperty);
        }

        String principal = environment.getProperty(PROPERTY_PREFIX + ref + PRINCIPAL_SUFFIX);
        log.info("[FinanceSync] Resolved credential for credentialRef [{}] (principal present: {})",
                ref, principal != null && !principal.isBlank());

        return new SourceCredentials(principal, secret);
    }

    @Override
    public boolean isConfigured(String credentialRef) {
        String secret = environment.getProperty(PROPERTY_PREFIX + requireRef(credentialRef) + SECRET_SUFFIX);
        return secret != null && !secret.isBlank();
    }

    /**
     * Rejects a reference that could escape the property namespace. {@code credential_ref}
     * comes from a database row, and a value such as {@code x.secret} or
     * {@code ..spring.datasource} would otherwise let a configuration row read an
     * unrelated property -- including the registry database password.
     */
    private String requireRef(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException("credentialRef must not be blank.");
        }
        String ref = credentialRef.trim().toLowerCase(Locale.ROOT);
        if (!ref.matches("[a-z0-9][a-z0-9_-]{0,98}[a-z0-9]")) {
            throw new IllegalArgumentException(
                    "credentialRef [" + credentialRef + "] must contain only letters, digits, hyphen and underscore.");
        }
        return ref;
    }
}

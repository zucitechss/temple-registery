package com.templeregistry.service.finance.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Q5 boundary: how a {@code credential_ref} becomes an actual secret, and what happens
 * when it cannot.
 */
class EnvironmentSourceCredentialProviderTest {

    private MockEnvironment environment;
    private EnvironmentSourceCredentialProvider provider;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        provider = new EnvironmentSourceCredentialProvider(environment);
    }

    @Test
    @DisplayName("Resolves principal and secret from the worker environment")
    void should_resolveCredentials_when_configured() {
        environment.setProperty("trm.finance.source.example-ref.principal", "readonly_user");
        environment.setProperty("trm.finance.source.example-ref.secret", "s3cr3t-value");

        SourceCredentials credentials = provider.resolve("example-ref");

        assertThat(credentials.principal()).isEqualTo("readonly_user");
        assertThat(credentials.secret()).isEqualTo("s3cr3t-value");
        assertThat(credentials.hasPrincipal()).isTrue();
    }

    /** Token-style mechanisms (SOURCE_API, FILE_DROP) have a secret but no user identity. */
    @Test
    @DisplayName("Resolves a secret with no principal")
    void should_resolveSecretOnly_when_noPrincipalConfigured() {
        environment.setProperty("trm.finance.source.api-ref.secret", "token-value");

        SourceCredentials credentials = provider.resolve("api-ref");

        assertThat(credentials.hasPrincipal()).isFalse();
        assertThat(credentials.secret()).isEqualTo("token-value");
    }

    /**
     * The decisive behaviour. A provider that fell back to a default, an empty password, or
     * another system's credential would convert a configuration mistake into either a
     * failure blamed on the temple, or a successful connection to something nobody intended.
     */
    @Test
    @DisplayName("Fails loudly rather than falling back when no credential is configured")
    void should_throw_when_credentialMissing() {
        assertThatThrownBy(() -> provider.resolve("absent-ref"))
                .isInstanceOf(CredentialNotConfiguredException.class)
                .hasMessageContaining("absent-ref")
                .hasMessageContaining("trm.finance.source.absent-ref.secret")
                .hasMessageContaining("must not be added to application.yml");
    }

    @Test
    @DisplayName("Treats a blank secret as absent")
    void should_throw_when_secretIsBlank() {
        environment.setProperty("trm.finance.source.blank-ref.secret", "   ");

        assertThatThrownBy(() -> provider.resolve("blank-ref"))
                .isInstanceOf(CredentialNotConfiguredException.class);
    }

    @Test
    @DisplayName("Reports configuration status without materialising the secret")
    void should_reportConfigured_when_queried() {
        environment.setProperty("trm.finance.source.present-ref.secret", "value");

        assertThat(provider.isConfigured("present-ref")).isTrue();
        assertThat(provider.isConfigured("absent-ref")).isFalse();
    }

    /**
     * {@code credential_ref} arrives from a database row. Without validation, a value such
     * as {@code ..spring.datasource} would let a configuration row read an unrelated
     * property -- including the registry database password.
     */
    @Test
    @DisplayName("Rejects a reference that could escape the property namespace")
    void should_reject_when_referenceContainsPropertyPathCharacters() {
        environment.setProperty("spring.datasource.password", "registry-db-password");

        assertThat(provider.isConfigured("present-ref")).isFalse();

        for (String hostile : new String[]{"..spring.datasource", "a.b", "ref/../other", "ref secret", ""}) {
            assertThatThrownBy(() -> provider.resolve(hostile))
                    .as("reference [%s] must be rejected", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Reference lookup is case-insensitive")
    void should_normaliseCase_when_resolving() {
        environment.setProperty("trm.finance.source.mixed-case.secret", "value");

        assertThat(provider.isConfigured("MIXED-CASE")).isTrue();
        assertThat(provider.resolve("Mixed-Case").secret()).isEqualTo("value");
    }

    /**
     * The usual way a credential reaches a log file is an exception message or a debug
     * statement interpolating an object that happens to contain one.
     */
    @Test
    @DisplayName("toString never exposes the secret")
    void should_redactSecret_when_rendered() {
        SourceCredentials credentials = new SourceCredentials("readonly_user", "s3cr3t-value");

        assertThat(credentials.toString())
                .doesNotContain("s3cr3t-value")
                .contains("<redacted>")
                .contains("readonly_user");
    }

    @Test
    @DisplayName("A credential cannot be constructed without a secret")
    void should_reject_when_secretIsAbsent() {
        assertThatThrownBy(() -> new SourceCredentials("user", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceCredentials("user", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

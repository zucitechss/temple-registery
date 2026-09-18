package com.templeregistry.service.impl.auth;

import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves JWT initialisation works from externally supplied key material and
 * fails loudly when none is available (C-1).
 *
 * <p>The production requirement is that a clean Git checkout — which contains no
 * PEM files — can still start when {@code APP_JWT_PRIVATE_KEY} /
 * {@code APP_JWT_PUBLIC_KEY} are provided by the platform secret store.</p>
 */
class JwtKeyProviderTest {

    private static final ResourceLoader LOADER = new DefaultResourceLoader();
    private static RsaTestKeys keys;

    @BeforeAll
    static void generateKeys() {
        keys = RsaTestKeys.generate();
    }

    // ─── Environment-supplied PEM values (the production path) ────────────────

    @Nested
    class InlinePemKeys {

        @Test
        void should_loadKeypair_when_pemSuppliedInline() {
            JwtKeyProvider provider = new JwtKeyProvider(
                    keys.privateKeyPem(), keys.publicKeyPem(), "", "", LOADER);

            assertThat(provider.getPrivateKey()).isNotNull();
            assertThat(provider.getPublicKey()).isNotNull();
            assertThat(provider.getPublicKey().getModulus())
                    .isEqualTo(keys.publicKey().getModulus());
        }

        @Test
        void should_issueAndVerifyToken_when_serviceBuiltFromInlinePemKeys() {
            JwtKeyProvider provider = new JwtKeyProvider(
                    keys.privateKeyPem(), keys.publicKeyPem(), "", "", LOADER);
            JwtServiceImpl service = new JwtServiceImpl(provider, 900_000L);

            String token = service.generateAccessToken(buildUser());
            Claims claims = service.validateAndParse(token);

            assertThat(claims.getSubject()).isEqualTo("sa@example.com");
            assertThat(claims.get("role", String.class)).isEqualTo("SUPER_ADMIN");
        }

        @Test
        void should_loadKeypair_when_pemUsesEscapedNewlines() {
            // Secret stores that cannot hold multi-line values round-trip a PEM
            // as a single line containing literal backslash-n sequences.
            String flatPrivate = keys.privateKeyPem().replace("\n", "\\n");
            String flatPublic = keys.publicKeyPem().replace("\n", "\\n");

            JwtKeyProvider provider = new JwtKeyProvider(
                    flatPrivate, flatPublic, "", "", LOADER);

            assertThat(provider.getPrivateKey().getModulus())
                    .isEqualTo(keys.privateKey().getModulus());
        }

        @Test
        void should_preferInlinePem_when_bothInlineAndPathAreConfigured() {
            // A stale on-disk key must never win over the injected secret.
            JwtKeyProvider provider = new JwtKeyProvider(
                    keys.privateKeyPem(), keys.publicKeyPem(),
                    "classpath:keys/does-not-exist.pem",
                    "classpath:keys/does-not-exist.pem",
                    LOADER);

            assertThat(provider.getPublicKey().getModulus())
                    .isEqualTo(keys.publicKey().getModulus());
        }
    }

    // ─── File/classpath fallback (local development only) ─────────────────────

    @Nested
    class ResourcePathKeys {

        @Test
        void should_loadKeypair_when_pemFilesExistOnDisk() throws Exception {
            Path dir = Files.createTempDirectory("jwt-keys-test");
            Path priv = dir.resolve("jwt-private.pem");
            Path pub = dir.resolve("jwt-public.pem");
            Files.writeString(priv, keys.privateKeyPem(), StandardCharsets.UTF_8);
            Files.writeString(pub, keys.publicKeyPem(), StandardCharsets.UTF_8);

            JwtKeyProvider provider = new JwtKeyProvider(
                    "", "", "file:" + priv, "file:" + pub, LOADER);

            assertThat(provider.getPrivateKey().getModulus())
                    .isEqualTo(keys.privateKey().getModulus());

            Files.deleteIfExists(priv);
            Files.deleteIfExists(pub);
            Files.deleteIfExists(dir);
        }
    }

    // ─── Fail-fast behaviour ──────────────────────────────────────────────────

    @Nested
    class MissingOrInvalidKeys {

        @Test
        void should_failStartup_when_noKeySourceIsConfigured() {
            assertThatThrownBy(() -> new JwtKeyProvider("", "", "", "", LOADER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No JWT private key available")
                    .hasMessageContaining("APP_JWT_PRIVATE_KEY");
        }

        @Test
        void should_failStartup_when_configuredPathDoesNotExist() {
            // This is the clean-checkout case: keys/ is git-ignored and absent.
            assertThatThrownBy(() -> new JwtKeyProvider(
                    "", "",
                    "classpath:keys/jwt-private-absent.pem",
                    "classpath:keys/jwt-public-absent.pem",
                    LOADER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No JWT private key available");
        }

        @Test
        void should_failStartup_when_publicKeyMissingButPrivateKeyPresent() {
            assertThatThrownBy(() -> new JwtKeyProvider(
                    keys.privateKeyPem(), "", "", "", LOADER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No JWT public key available")
                    .hasMessageContaining("APP_JWT_PUBLIC_KEY");
        }

        @Test
        void should_failWithActionableMessage_when_pemBodyIsNotBase64() {
            assertThatThrownBy(() -> new JwtKeyProvider(
                    "-----BEGIN PRIVATE KEY-----\n!!!not base64!!!\n-----END PRIVATE KEY-----",
                    keys.publicKeyPem(), "", "", LOADER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not valid Base64 PEM content");
        }

        @Test
        void should_failWithActionableMessage_when_pemHasArmourButEmptyBody() {
            assertThatThrownBy(() -> new JwtKeyProvider(
                    "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----",
                    keys.publicKeyPem(), "", "", LOADER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("contains no Base64 body");
        }

        @Test
        void should_notLeakKeyMaterial_when_initialisationFails() {
            String secretBody = keys.privateKeyPem();

            assertThatThrownBy(() -> new JwtKeyProvider(
                    secretBody, "", "", "", LOADER))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(secretBody));
        }
    }

    private static User buildUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("sa@example.com");
        user.setEmail("sa@example.com");
        user.setFullName("Super Administrator");
        user.setRole(UserRole.SUPER_ADMIN);
        return user;
    }
}

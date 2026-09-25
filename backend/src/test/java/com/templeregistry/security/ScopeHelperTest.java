package com.templeregistry.security;

import com.templeregistry.service.impl.auth.JwtKeyProvider;
import com.templeregistry.service.impl.auth.RsaTestKeys;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link ScopeHelper} — the JWT verification path used by
 * {@link JwtAuthenticationFilter}.
 *
 * <p>Before this fix {@code ScopeHelper} bound {@code ${app.jwt.public-key-path}} to a
 * {@code Resource} and parsed the PEM itself, bypassing {@link JwtKeyProvider}. On the
 * production profile that path is deliberately blank, so the resource was null and the
 * application aborted at startup with a {@code NullPointerException} (C-1 gap). These
 * tests pin the corrected contract: the key comes from the provider, and the
 * production-style configuration — PEM supplied inline, both paths blank — works.</p>
 */
class ScopeHelperTest {

    private static RsaTestKeys keys;
    private static RsaTestKeys otherKeys;

    @BeforeAll
    static void generateKeys() {
        keys = RsaTestKeys.generate();
        otherKeys = RsaTestKeys.generate();
    }

    /**
     * Builds a provider exactly the way the {@code prod} profile does: PEM contents
     * inline (from {@code APP_JWT_PUBLIC_KEY}) and both {@code *-key-path} properties
     * blank, so no file on the classpath or disk is ever consulted.
     */
    private static ScopeHelper productionStyleScopeHelper() {
        return new ScopeHelper(new JwtKeyProvider(
                keys.privateKeyPem(),
                keys.publicKeyPem(),
                "",   // app.jwt.private-key-path — blank in production
                "",   // app.jwt.public-key-path  — blank in production
                new DefaultResourceLoader()));
    }

    private static String tokenSignedWith(RsaTestKeys signingKeys) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("dc_user")
                .claim("userId", 42L)
                .claim("role", "DISTRICT_COLLECTOR")
                .claim("districtId", 7L)
                .claim("templeId", 13L)
                .claim("accessType", "EDIT")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000))
                .signWith(signingKeys.privateKey())
                .compact();
    }

    // ─── The configuration that used to abort startup ────────────────────────

    @Nested
    class ProductionStyleKeyConfiguration {

        @Test
        void should_beConstructible_when_keyPathsAreBlankAndPemIsSuppliedInline() {
            assertThatCode(ScopeHelperTest::productionStyleScopeHelper)
                    .doesNotThrowAnyException();
        }

        @Test
        void should_verifyToken_when_keyComesFromInlinePemRatherThanAFile() {
            ScopeHelper scopeHelper = productionStyleScopeHelper();

            ScopeHelper.Claims claims = scopeHelper.parse(tokenSignedWith(keys));

            assertThat(claims.username()).isEqualTo("dc_user");
        }

        @Test
        void should_failWithAnActionableMessage_when_noKeyIsConfiguredAtAll() {
            // The old code produced a bare NullPointerException here. The provider now
            // names the environment variable to set.
            assertThatThrownBy(() -> new ScopeHelper(new JwtKeyProvider(
                    "", "", "", "", new DefaultResourceLoader())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APP_JWT_PRIVATE_KEY");
        }
    }

    // ─── Claim extraction must be unchanged ──────────────────────────────────

    @Nested
    class ClaimExtraction {

        @Test
        void should_mapEveryScopeClaim_when_tokenIsValid() {
            ScopeHelper.Claims claims = productionStyleScopeHelper().parse(tokenSignedWith(keys));

            assertThat(claims.userId()).isEqualTo(42L);
            assertThat(claims.role()).isEqualTo("DISTRICT_COLLECTOR");
            assertThat(claims.districtId()).isEqualTo(7L);
            assertThat(claims.templeId()).isEqualTo(13L);
            assertThat(claims.username()).isEqualTo("dc_user");
            assertThat(claims.accessType()).isEqualTo("EDIT");
        }

        @Test
        void should_leaveScopeFieldsNull_when_tokenOmitsThem() {
            long now = System.currentTimeMillis();
            String sparseToken = Jwts.builder()
                    .subject("sa_user")
                    .claim("userId", 1L)
                    .claim("role", "SUPER_ADMIN")
                    .issuedAt(new Date(now))
                    .expiration(new Date(now + 60_000))
                    .signWith(keys.privateKey())
                    .compact();

            ScopeHelper.Claims claims = productionStyleScopeHelper().parse(sparseToken);

            assertThat(claims.districtId()).isNull();
            assertThat(claims.templeId()).isNull();
            assertThat(claims.accessType()).isNull();
            assertThat(claims.templeIds()).isEmpty();
        }
    }

    // ─── Verification must still reject bad tokens ───────────────────────────

    @Nested
    class Rejection {

        @Test
        void should_rejectToken_when_signedByADifferentKey() {
            ScopeHelper scopeHelper = productionStyleScopeHelper();
            String foreignToken = tokenSignedWith(otherKeys);

            assertThatThrownBy(() -> scopeHelper.parse(foreignToken))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        void should_rejectToken_when_expired() {
            ScopeHelper scopeHelper = productionStyleScopeHelper();
            long past = System.currentTimeMillis() - 120_000;
            String expired = Jwts.builder()
                    .subject("dc_user")
                    .issuedAt(new Date(past))
                    .expiration(new Date(past + 60_000))
                    .signWith(keys.privateKey())
                    .compact();

            assertThatThrownBy(() -> scopeHelper.parse(expired))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        void should_rejectToken_when_payloadIsTampered() {
            ScopeHelper scopeHelper = productionStyleScopeHelper();
            String tampered = tokenSignedWith(keys) + "x";

            assertThatThrownBy(() -> scopeHelper.parse(tampered))
                    .isInstanceOf(JwtException.class);
        }
    }
}

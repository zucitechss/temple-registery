package com.templeregistry.service.impl.auth;

import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for JwtServiceImpl.
 *
 * <p>Uses an ephemeral RSA keypair generated per test run (see
 * {@link RsaTestKeys}). Nothing here depends on committed key material, so the
 * suite passes on a clean checkout where src/main/resources/keys/ is absent (C-1).</p>
 */
class JwtServiceImplTest {

    private static JwtServiceImpl jwtService;
    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void setUp() {
        RsaTestKeys keys = RsaTestKeys.generate();
        privateKey = keys.privateKey();
        publicKey = keys.publicKey();
        jwtService = new JwtServiceImpl(privateKey, publicKey, 900_000L); // 15 minutes
    }

    private User buildDcUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("dc@example.com");
        user.setEmail("dc@example.com");
        user.setFullName("DC Officer");
        user.setRole(UserRole.DISTRICT_COLLECTOR);
        user.setDistrictId(5L);
        user.setTempleId(null);
        return user;
    }

    private User buildTaUser() {
        User user = new User();
        user.setId(2L);
        user.setUsername("ta@example.com");
        user.setEmail("ta@example.com");
        user.setFullName("Temple Authority");
        user.setRole(UserRole.TEMPLE_AUTHORITY);
        user.setDistrictId(null);
        user.setTempleId(10L);
        return user;
    }

    // ─── generateAccessToken ──────────────────────────────────────────────────

    @Nested
    class GenerateAccessToken {

        @Test
        void should_generateToken_when_validUserProvided() {
            User user = buildDcUser();

            String token = jwtService.generateAccessToken(user);

            assertThat(token).isNotNull().isNotBlank();
        }

        @Test
        void should_embedUserIdInToken_when_dcUserProvided() {
            User user = buildDcUser();

            String token = jwtService.generateAccessToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.get("userId", Long.class)).isEqualTo(1L);
        }

        @Test
        void should_embedRoleInToken_when_userProvided() {
            User user = buildDcUser();

            String token = jwtService.generateAccessToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.get("role", String.class)).isEqualTo("DISTRICT_COLLECTOR");
        }

        @Test
        void should_embedDistrictIdInToken_when_dcUser() {
            User user = buildDcUser();

            String token = jwtService.generateAccessToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.get("districtId", Long.class)).isEqualTo(5L);
        }

        @Test
        void should_embedTempleIdInToken_when_taUser() {
            User user = buildTaUser();

            String token = jwtService.generateAccessToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.get("templeId", Long.class)).isEqualTo(10L);
        }

        @Test
        void should_embedSubjectAsUsername_when_tokenGenerated() {
            User user = buildDcUser();

            String token = jwtService.generateAccessToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.getSubject()).isEqualTo("dc@example.com");
        }

        @Test
        void should_setExpirationInFuture_when_tokenGenerated() {
            User user = buildDcUser();
            long beforeGeneration = System.currentTimeMillis();

            String token = jwtService.generateAccessToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.getExpiration().getTime())
                .isGreaterThan(beforeGeneration);
        }
    }

    // ─── generateTempToken ────────────────────────────────────────────────────

    @Nested
    class GenerateTempToken {

        @Test
        void should_generateTempToken_when_validUserProvided() {
            User user = buildDcUser();

            String token = jwtService.generateTempToken(user);

            assertThat(token).isNotNull().isNotBlank();
        }

        @Test
        void should_embedTypeClaimAsTEMP_when_tempTokenGenerated() {
            User user = buildDcUser();

            String token = jwtService.generateTempToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.get("type", String.class)).isEqualTo("TEMP");
        }

        @Test
        void should_notIncludeRoleClaim_when_tempTokenGenerated() {
            User user = buildDcUser();

            String token = jwtService.generateTempToken(user);
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.get("role")).isNull();
        }

        @Test
        void should_expire5MinutesFromNow_when_tempTokenGenerated() {
            User user = buildDcUser();
            long before = System.currentTimeMillis();

            String token = jwtService.generateTempToken(user);
            Claims claims = jwtService.validateAndParse(token);

            long expirationMs = claims.getExpiration().getTime();
            // Should be roughly 5 minutes = 300000ms in the future
            assertThat(expirationMs - before)
                .isGreaterThan(290_000L)
                .isLessThanOrEqualTo(305_000L);
        }
    }

    // ─── generateRegistrationToken ────────────────────────────────────────────

    @Nested
    class GenerateRegistrationToken {

        @Test
        void should_generateTokenWithCustomClaims_when_validInputProvided() {
            Map<String, Object> customClaims = Map.of("email", "new@example.com", "role", "DC");

            String token = jwtService.generateRegistrationToken(customClaims, Duration.ofMinutes(30));

            assertThat(token).isNotNull().isNotBlank();
        }

        @Test
        void should_embedCustomClaimsInToken_when_registrationTokenGenerated() {
            Map<String, Object> customClaims = Map.of("email", "new@example.com");

            String token = jwtService.generateRegistrationToken(customClaims, Duration.ofMinutes(30));
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.get("email", String.class)).isEqualTo("new@example.com");
        }

        @Test
        void should_setSubjectToRegistration_when_registrationTokenGenerated() {
            String token = jwtService.generateRegistrationToken(Map.of(), Duration.ofMinutes(10));
            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims.getSubject()).isEqualTo("registration");
        }

        @Test
        void should_respectTtl_when_customDurationProvided() {
            long before = System.currentTimeMillis();
            Duration ttl = Duration.ofHours(1);

            String token = jwtService.generateRegistrationToken(Map.of(), ttl);
            Claims claims = jwtService.validateAndParse(token);

            long expirationMs = claims.getExpiration().getTime();
            // Expiration should be ~1 hour from now
            assertThat(expirationMs - before)
                .isGreaterThan(3590_000L)
                .isLessThanOrEqualTo(3605_000L);
        }
    }

    // ─── generateRefreshToken ────────────────────────────────────────────────

    @Nested
    class GenerateRefreshToken {

        @Test
        void should_generateNonBlankToken_when_called() {
            String token = jwtService.generateRefreshToken();

            assertThat(token).isNotNull().isNotBlank();
        }

        @Test
        void should_generate64CharHexString_when_called() {
            // 2 UUIDs stripped of dashes = 32 + 32 = 64 hex chars
            String token = jwtService.generateRefreshToken();

            assertThat(token).hasSize(64);
            assertThat(token).matches("[a-f0-9]+");
        }

        @Test
        void should_generateUniqueTokens_when_calledMultipleTimes() {
            String token1 = jwtService.generateRefreshToken();
            String token2 = jwtService.generateRefreshToken();

            assertThat(token1).isNotEqualTo(token2);
        }
    }

    // ─── validateAndParse ─────────────────────────────────────────────────────

    @Nested
    class ValidateAndParse {

        @Test
        void should_returnClaims_when_validTokenProvided() {
            User user = buildDcUser();
            String token = jwtService.generateAccessToken(user);

            Claims claims = jwtService.validateAndParse(token);

            assertThat(claims).isNotNull();
        }

        @Test
        void should_throwException_when_tokenTamperedWith() {
            User user = buildDcUser();
            String token = jwtService.generateAccessToken(user);
            // Tamper with the signature
            String tamperedToken = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsignature";

            assertThatThrownBy(() -> jwtService.validateAndParse(tamperedToken))
                .isInstanceOf(Exception.class);
        }

        @Test
        void should_throwException_when_expiredTokenProvided() throws Exception {
            // Create service with 1ms expiry
            JwtServiceImpl shortLivedService =
                new JwtServiceImpl(privateKey, publicKey, 1L); // 1ms expiry
            User user = buildDcUser();
            String token = shortLivedService.generateAccessToken(user);
            Thread.sleep(10); // Let it expire

            assertThatThrownBy(() -> shortLivedService.validateAndParse(token))
                .isInstanceOf(ExpiredJwtException.class);
        }

        @Test
        void should_throwException_when_randomStringProvided() {
            assertThatThrownBy(() -> jwtService.validateAndParse("this.is.not.a.jwt"))
                .isInstanceOf(Exception.class);
        }
    }
}

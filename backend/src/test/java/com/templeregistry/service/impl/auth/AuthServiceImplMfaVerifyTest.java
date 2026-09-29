package com.templeregistry.service.impl.auth;

import com.templeregistry.dto.request.auth.MfaVerifyRequest;
import com.templeregistry.dto.response.auth.AuthTokenResponse;
import com.templeregistry.entity.auth.MfaType;
import com.templeregistry.entity.auth.RefreshToken;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.exception.AccountLockedException;
import com.templeregistry.exception.MfaVerificationException;
import com.templeregistry.repository.auth.RefreshTokenRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.service.auth.JwtService;
import com.templeregistry.service.auth.MfaService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@code AuthServiceImpl.verifyMfa} — the second login step (C-3).
 *
 * <p>Before remediation this method validated only the temporary token and
 * issued a full access/refresh pair without ever inspecting the submitted
 * code, so any value (or none) satisfied MFA. These tests pin the corrected
 * contract: a token pair is issued if and only if the code verifies against
 * the factor configured on the account.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplMfaVerifyTest {

    private static final String TEMP_TOKEN = "temp-token";
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock MfaService mfaService;
    @Mock Claims claims;

    @InjectMocks AuthServiceImpl authService;

    private User totpUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "refreshTokenExpiryDays", 7);

        totpUser = User.builder()
                .username("dcuser").passwordHash("hashed")
                .role(UserRole.DISTRICT_COLLECTOR)
                .mfaType(MfaType.TOTP).mfaSecret(TOTP_SECRET)
                .isActive(true).failedLoginCount(0).build();
        totpUser.setId(42L);
    }

    /** Makes the mocked JWT layer return a well-formed temp token for {@code dcuser}. */
    private void givenValidTempToken() {
        when(jwtService.validateAndParse(TEMP_TOKEN)).thenReturn(claims);
        when(claims.get("type", String.class)).thenReturn("TEMP");
        when(claims.getSubject()).thenReturn("dcuser");
    }

    private MfaVerifyRequest request(String mfaCode) {
        MfaVerifyRequest rq = new MfaVerifyRequest();
        setField(rq, "tempToken", TEMP_TOKEN);
        setField(rq, "mfaCode", mfaCode);
        return rq;
    }

    /** MfaVerifyRequest has no setters; populate it the way Jackson would. */
    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Asserts no credential of any kind was minted. */
    private void assertNoTokensIssued() {
        verify(jwtService, never()).generateAccessToken(any());
        verify(jwtService, never()).generateRefreshToken();
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    // ─── TOTP: the one factor that is actually supported ─────────────────────

    @Nested
    class TotpFactor {

        @Test
        void should_issueTokenPair_when_totpCodeIsValid() {
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));
            when(jwtService.generateAccessToken(totpUser)).thenReturn("access-token");
            when(jwtService.generateRefreshToken()).thenReturn("refresh-token");

            AuthTokenResponse response = authService.verifyMfa(request("123456"));

            verify(mfaService).verifyTotp(TOTP_SECRET, "123456");
            assertThat(response.getAccessToken()).isEqualTo("access-token");
            assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
            assertThat(response.getUserId()).isEqualTo(42L);
        }

        @Test
        void should_verifyAgainstTheUsersOwnSecret_when_codeSubmitted() {
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));
            when(jwtService.generateAccessToken(any())).thenReturn("access-token");
            when(jwtService.generateRefreshToken()).thenReturn("refresh-token");

            authService.verifyMfa(request("654321"));

            // The enrolled secret must be used — not a constant or another user's.
            verify(mfaService).verifyTotp(eq(TOTP_SECRET), eq("654321"));
        }

        @Test
        void should_denyAndIssueNoTokens_when_totpCodeIsWrong() {
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));
            doThrow(new MfaVerificationException("Invalid TOTP code."))
                    .when(mfaService).verifyTotp(anyString(), anyString());

            assertThatThrownBy(() -> authService.verifyMfa(request("000000")))
                    .isInstanceOf(MfaVerificationException.class)
                    .hasMessageContaining("Invalid TOTP code");

            assertNoTokensIssued();
        }

        @Test
        void should_countFailureTowardsLockout_when_totpCodeIsWrong() {
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));
            doThrow(new MfaVerificationException("Invalid TOTP code."))
                    .when(mfaService).verifyTotp(anyString(), anyString());

            assertThatThrownBy(() -> authService.verifyMfa(request("000000")))
                    .isInstanceOf(MfaVerificationException.class);

            verify(userRepository).save(org.mockito.ArgumentMatchers
                    .argThat(u -> u.getFailedLoginCount() == 1));
        }

        @Test
        void should_lockAccount_when_totpFailureReachesTheThreshold() {
            totpUser.setFailedLoginCount(4);
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));
            doThrow(new MfaVerificationException("Invalid TOTP code."))
                    .when(mfaService).verifyTotp(anyString(), anyString());

            assertThatThrownBy(() -> authService.verifyMfa(request("000000")))
                    .isInstanceOf(MfaVerificationException.class);

            verify(userRepository).save(org.mockito.ArgumentMatchers
                    .argThat(u -> u.getLockedUntil() != null));
            assertNoTokensIssued();
        }

        @Test
        void should_resetFailureCounter_when_totpCodeIsValid() {
            totpUser.setFailedLoginCount(3);
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));
            when(jwtService.generateAccessToken(any())).thenReturn("access-token");
            when(jwtService.generateRefreshToken()).thenReturn("refresh-token");

            authService.verifyMfa(request("123456"));

            assertThat(totpUser.getFailedLoginCount()).isZero();
            assertThat(totpUser.getLastLoginAt()).isNotNull();
        }

        @Test
        void should_denyAndIssueNoTokens_when_totpEnabledButNoSecretEnrolled() {
            totpUser.setMfaSecret(null);
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));

            assertThatThrownBy(() -> authService.verifyMfa(request("123456")))
                    .isInstanceOf(MfaVerificationException.class)
                    .hasMessageContaining("no secret is enrolled");

            verify(mfaService, never()).verifyTotp(anyString(), anyString());
            assertNoTokensIssued();
        }
    }

    // ─── Empty / missing codes ────────────────────────────────────────────────

    @Nested
    class MissingCode {

        @Test
        void should_denyAndIssueNoTokens_when_mfaCodeIsNull() {
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));

            assertThatThrownBy(() -> authService.verifyMfa(request(null)))
                    .isInstanceOf(MfaVerificationException.class)
                    .hasMessageContaining("MFA code is required");

            verify(mfaService, never()).verifyTotp(anyString(), anyString());
            assertNoTokensIssued();
        }

        @Test
        void should_denyAndIssueNoTokens_when_mfaCodeIsBlank() {
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));

            assertThatThrownBy(() -> authService.verifyMfa(request("   ")))
                    .isInstanceOf(MfaVerificationException.class)
                    .hasMessageContaining("MFA code is required");

            assertNoTokensIssued();
        }
    }

    // ─── SMS OTP: explicitly unavailable until implemented ────────────────────

    @Nested
    class SmsOtpFactor {

        @Test
        void should_denyAndIssueNoTokens_when_factorIsSmsOtp() {
            totpUser.setMfaType(MfaType.SMS_OTP);
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));

            assertThatThrownBy(() -> authService.verifyMfa(request("123456")))
                    .isInstanceOf(MfaVerificationException.class)
                    .hasMessageContaining("SMS one-time passcodes are not available");

            assertNoTokensIssued();
        }
    }

    // ─── Session / token-type integrity ───────────────────────────────────────

    @Nested
    class TempTokenIntegrity {

        @Test
        void should_denyAndIssueNoTokens_when_tokenIsNotATempToken() {
            // A full access token carries no "type" claim; it must not be
            // accepted as an MFA session.
            when(jwtService.validateAndParse(TEMP_TOKEN)).thenReturn(claims);
            when(claims.get("type", String.class)).thenReturn(null);

            assertThatThrownBy(() -> authService.verifyMfa(request("123456")))
                    .isInstanceOf(MfaVerificationException.class)
                    .hasMessageContaining("Invalid MFA session");

            verify(userRepository, never()).findByUsername(anyString());
            assertNoTokensIssued();
        }

        @Test
        void should_denyAndIssueNoTokens_when_accountIsLocked() {
            totpUser.setLockedUntil(LocalDateTime.now().plusMinutes(10));
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));

            assertThatThrownBy(() -> authService.verifyMfa(request("123456")))
                    .isInstanceOf(AccountLockedException.class);

            verify(mfaService, never()).verifyTotp(anyString(), anyString());
            assertNoTokensIssued();
        }

        @Test
        void should_denyAndIssueNoTokens_when_accountHasNoMfaConfigured() {
            totpUser.setMfaType(MfaType.NONE);
            givenValidTempToken();
            when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(totpUser));

            assertThatThrownBy(() -> authService.verifyMfa(request("123456")))
                    .isInstanceOf(MfaVerificationException.class)
                    .hasMessageContaining("No multi-factor method is configured");

            assertNoTokensIssued();
        }
    }
}

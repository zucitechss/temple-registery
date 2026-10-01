package com.templeregistry.service.impl.auth;

import com.templeregistry.dto.request.auth.LoginRequest;
import com.templeregistry.dto.request.auth.PasswordResetConfirmRequest;
import com.templeregistry.dto.request.auth.PasswordResetRequest;
import com.templeregistry.dto.response.auth.AuthTokenResponse;
import com.templeregistry.dto.response.auth.MfaChallengeResponse;
import com.templeregistry.entity.auth.MfaType;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.exception.AccountLockedException;
import com.templeregistry.repository.auth.RefreshTokenRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.service.auth.JwtService;
import com.templeregistry.service.auth.MfaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock MfaService mfaService;
    @Mock com.templeregistry.security.TokenRevocationGuard tokenRevocationGuard;
    @Mock com.templeregistry.service.notification.EmailService emailService;
    @Mock com.templeregistry.service.audit.AuditService auditService;

    @InjectMocks AuthServiceImpl authService;

    private User activeUser;

    @BeforeEach
    void setUp() {
        activeUser = User.builder()
                .username("dcuser").passwordHash("hashed")
                .role(UserRole.DISTRICT_COLLECTOR).mfaType(MfaType.NONE)
                .isActive(true).failedLoginCount(0).build();
    }

    @Test
    void should_throw_AccountLocked_when_user_is_locked() {
        activeUser.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(activeUser));

        LoginRequest rq = new LoginRequest("dcuser", "correct");
        assertThatThrownBy(() -> authService.login(rq))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void should_increment_failedLoginCount_when_wrong_password() {
        when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        LoginRequest rq = new LoginRequest("dcuser", "wrong");
        assertThatThrownBy(() -> authService.login(rq))
                .isInstanceOf(com.templeregistry.exception.EntityNotFoundException.class);

        verify(userRepository).save(argThat(u -> u.getFailedLoginCount() == 1));
    }

    @Test
    void should_issue_token_pair_when_mfa_is_none() {
        when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken()).thenReturn("refresh-token");

        Object result = authService.login(new LoginRequest("dcuser", "correct"));

        assertThat(result).isInstanceOf(AuthTokenResponse.class);
        assertThat(((AuthTokenResponse) result).getAccessToken()).isEqualTo("access-token");
    }

    @Test
    void should_issue_mfa_challenge_when_mfa_is_totp() {
        activeUser.setMfaType(MfaType.TOTP);
        when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(jwtService.generateTempToken(any())).thenReturn("temp-token");

        Object result = authService.login(new LoginRequest("dcuser", "correct"));

        assertThat(result).isInstanceOf(MfaChallengeResponse.class);
        assertThat(((MfaChallengeResponse) result).getTempToken()).isEqualTo("temp-token");
    }

    @Test
    void should_lock_account_after_five_failed_attempts() {
        activeUser.setFailedLoginCount(4);
        when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("dcuser", "bad")))
                .isInstanceOf(com.templeregistry.exception.EntityNotFoundException.class);

        verify(userRepository).save(argThat(u -> u.getLockedUntil() != null));
    }

    // ── Forgot password ──────────────────────────────────────────────────────

    private User resetUser() {
        User u = User.builder()
                .username("dcuser").email("dc@example.com").passwordHash("old-hash")
                .role(UserRole.DISTRICT_COLLECTOR).isActive(true).build();
        u.setId(5L);
        return u;
    }

    @Test
    void should_issueHashedResetToken_when_accountExists() {
        User user = resetUser();
        when(userRepository.findByEmail("dc@example.com")).thenReturn(Optional.of(user));

        authService.requestPasswordReset(new PasswordResetRequest("dc@example.com"));

        assertThat(user.getPasswordResetTokenHash())
                .isNotNull()
                .hasSize(64);                                     // SHA-256 hex, not the raw token
        assertThat(user.getPasswordResetTokenExpiresAt()).isAfter(LocalDateTime.now());
        verify(emailService).sendPasswordResetEmail(eq("dc@example.com"), contains("token="));
    }

    @Test
    void should_generateUnpredictableResetTokens_when_requestedRepeatedly() {
        User first = resetUser();
        User second = resetUser();
        when(userRepository.findByEmail(anyString()))
                .thenReturn(Optional.of(first), Optional.of(second));

        authService.requestPasswordReset(new PasswordResetRequest("a@example.com"));
        authService.requestPasswordReset(new PasswordResetRequest("b@example.com"));

        assertThat(first.getPasswordResetTokenHash())
                .isNotEqualTo(second.getPasswordResetTokenHash());
    }

    @Test
    void should_succeedSilently_when_accountDoesNotExist() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        // No exception — the controller returns the same generic message either way,
        // so the response cannot be used to enumerate accounts.
        authService.requestPasswordReset(new PasswordResetRequest("nobody@example.com"));

        verifyNoInteractions(emailService);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void should_throwRateLimit_when_tooManyResetRequestsForSameEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        PasswordResetRequest rq = new PasswordResetRequest("spam@example.com");

        authService.requestPasswordReset(rq);
        authService.requestPasswordReset(rq);
        authService.requestPasswordReset(rq);

        assertThatThrownBy(() -> authService.requestPasswordReset(rq))
                .isInstanceOf(com.templeregistry.exception.RateLimitExceededException.class);
    }

    // ── Reset password ───────────────────────────────────────────────────────

    /** SHA-256 hex of the raw token, matching the service's internal hashing. */
    private static String sha256Hex(String raw) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    @Test
    void should_updatePasswordAndConsumeToken_when_resetTokenIsValid() throws Exception {
        User user = resetUser();
        user.setPasswordResetTokenHash(sha256Hex("raw-token"));
        user.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByPasswordResetTokenHash(sha256Hex("raw-token")))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPass456")).thenReturn("new-hash");

        authService.confirmPasswordReset(
                new PasswordResetConfirmRequest("raw-token", "NewPass456", "NewPass456"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getPasswordResetTokenHash()).isNull();       // single use
        assertThat(user.getPasswordResetTokenExpiresAt()).isNull();
        assertThat(user.getPasswordUpdatedAt()).isNotNull();
        verify(refreshTokenRepository).revokeAllByUserId(eq(5L), any());
    }

    @Test
    void should_rejectReuse_when_resetTokenAlreadyConsumed() {
        // The first successful reset nulls the hash, so the second lookup finds nothing.
        when(userRepository.findByPasswordResetTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.confirmPasswordReset(
                new PasswordResetConfirmRequest("raw-token", "NewPass456", "NewPass456")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    void should_rejectAndClearToken_when_resetTokenHasExpired() throws Exception {
        User user = resetUser();
        user.setPasswordResetTokenHash(sha256Hex("raw-token"));
        user.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByPasswordResetTokenHash(sha256Hex("raw-token")))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.confirmPasswordReset(
                new PasswordResetConfirmRequest("raw-token", "NewPass456", "NewPass456")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        // Cleared so the expired link cannot be replayed.
        assertThat(user.getPasswordResetTokenHash()).isNull();
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
    }

    @Test
    void should_rejectInvalidToken_when_tokenDoesNotMatchAnyUser() {
        when(userRepository.findByPasswordResetTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.confirmPasswordReset(
                new PasswordResetConfirmRequest("bogus", "NewPass456", "NewPass456")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_rejectMismatchedConfirmation_when_resettingPassword() {
        assertThatThrownBy(() -> authService.confirmPasswordReset(
                new PasswordResetConfirmRequest("raw-token", "NewPass456", "Different789")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match");

        verifyNoInteractions(userRepository);
    }

    @Test
    void should_clearForcedChangeFlag_when_userResetsViaEmailedLink() throws Exception {
        User user = resetUser();
        user.setMustChangePassword(true);
        user.setPasswordResetTokenHash(sha256Hex("raw-token"));
        user.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByPasswordResetTokenHash(sha256Hex("raw-token")))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");

        authService.confirmPasswordReset(
                new PasswordResetConfirmRequest("raw-token", "NewPass456", "NewPass456"));

        assertThat(user.isMustChangePassword()).isFalse();
    }
}

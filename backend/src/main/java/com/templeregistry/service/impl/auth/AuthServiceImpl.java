package com.templeregistry.service.impl.auth;

import com.templeregistry.dto.request.auth.*;
import com.templeregistry.dto.response.auth.AuthTokenResponse;
import com.templeregistry.dto.response.auth.MfaChallengeResponse;
import com.templeregistry.entity.auth.MfaType;
import com.templeregistry.entity.auth.RefreshToken;
import com.templeregistry.entity.auth.User;
import com.templeregistry.exception.AccountLockedException;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.RateLimitExceededException;
import com.templeregistry.repository.auth.RefreshTokenRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.security.TokenRevocationGuard;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.auth.AuthService;
import com.templeregistry.service.auth.JwtService;
import com.templeregistry.service.auth.MfaService;
import com.templeregistry.service.notification.EmailService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MfaService mfaService;
    private final TokenRevocationGuard tokenRevocationGuard;
    private final EmailService emailService;
    private final AuditService auditService;

    @Value("${app.jwt.refresh-token-expiry-days:7}")
    private int refreshTokenExpiryDays;

    @Value("${app.base-url}")
    private String baseUrl;

    private static final int RESET_TOKEN_EXPIRY_MINUTES = 30;

    /** Password-reset abuse protection: at most N requests per email address per window. */
    private static final int MAX_RESET_REQUESTS_PER_WINDOW = 3;
    private static final int RESET_REQUEST_WINDOW_MINUTES = 15;

    private final Cache<String, Integer> resetRequestCounts = Caffeine.newBuilder()
            .expireAfterWrite(RESET_REQUEST_WINDOW_MINUTES, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    @Override
    @Transactional
    public Object login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new EntityNotFoundException("Invalid credentials.", "INVALID_CREDENTIALS"));

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new AccountLockedException(
                    user.getLockedUntil().toEpochSecond(java.time.ZoneOffset.UTC));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            int attempts = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                log.warn("Account locked for user [{}] after {} failed attempts", user.getId(), attempts);
            }
            userRepository.save(user);
            throw new EntityNotFoundException("Invalid credentials.", "INVALID_CREDENTIALS");
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        if (user.getMfaType() != null && user.getMfaType() != MfaType.NONE) {
            String tempToken = jwtService.generateTempToken(user);
            return MfaChallengeResponse.builder()
                    .mfaRequired(true)
                    .challengeType(user.getMfaType().name())
                    .tempToken(tempToken)
                    .build();
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return issueTokenPair(user);
    }

    @Override
    @Transactional
    public AuthTokenResponse verifyMfa(MfaVerifyRequest request) {
        Claims claims = jwtService.validateAndParse(request.getTempToken());
        String username = claims.getSubject();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found.", "USER_NOT_FOUND"));

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return issueTokenPair(user);
    }

    @Override
    @Transactional
    public AuthTokenResponse refresh(String rawRefreshToken) {
        String tokenHash = sha256(rawRefreshToken);
        tokenRevocationGuard.assertNotRevoked(tokenHash);

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new SecurityException("Refresh token not found."));

        // Rotate: revoke the old token
        storedToken.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(storedToken);

        return issueTokenPair(storedToken.getUser());
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = sha256(refreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(rt -> {
            rt.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(rt);
            log.info("User [{}] logged out", rt.getUser().getId());
        });
    }

    @Override
    @Transactional
    public void requestPasswordReset(PasswordResetRequest request) {
        // Throttled on the SUBMITTED address, before the account lookup, so an attacker cannot
        // tell a real address from an unknown one by which requests get throttled.
        assertResetRequestAllowed(request.getEmail());

        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            // Generate a 32-byte cryptographically random token
            byte[] tokenBytes = new byte[32];
            new SecureRandom().nextBytes(tokenBytes);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            String tokenHash = sha256(rawToken);

            user.setPasswordResetTokenHash(tokenHash);
            user.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(RESET_TOKEN_EXPIRY_MINUTES));
            userRepository.save(user);

            String resetLink = baseUrl + "/reset-password?token=" + rawToken;
            emailService.sendPasswordResetEmail(user.getEmail(), resetLink);
            auditService.logAuthEvent(user.getId(), user.getUsername(), "PASSWORD_RESET_REQUESTED",
                    null, "SUCCESS", "Password reset link issued");
            // Log the user id only — never the raw token or its hash.
            log.info("Password reset token issued for user [{}]", user.getId());
        });
        // Always return without error to prevent user enumeration
    }

    /**
     * Caps password-reset requests per submitted email address. Keeps abuse from turning the
     * endpoint into a free mail relay without pulling in an external rate-limiting dependency —
     * Caffeine is already on the classpath for {@link com.templeregistry.config.CacheConfig}.
     *
     * <p>ponytail: per-instance counter, fine for the current single-node deployment. Move to a
     * shared store (Redis) if the backend is ever scaled horizontally.
     */
    private void assertResetRequestAllowed(String email) {
        String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        int attempts = resetRequestCounts.asMap().merge(key, 1, Integer::sum);
        if (attempts > MAX_RESET_REQUESTS_PER_WINDOW) {
            log.warn("Password reset request throttled after {} attempts in the current window", attempts);
            throw new RateLimitExceededException(RESET_REQUEST_WINDOW_MINUTES * 60);
        }
    }

    @Override
    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {
        // Verify the confirmation server-side whenever the client sent one.
        if (request.getConfirmPassword() != null
                && !request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalStateException("New password and confirmation password do not match.");
        }

        String tokenHash = sha256(request.getToken());

        User user = userRepository.findByPasswordResetTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalStateException("This password reset link is no longer valid."));

        if (user.getPasswordResetTokenExpiresAt() == null
                || user.getPasswordResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
            // Clear the expired token to prevent replay attacks
            user.setPasswordResetTokenHash(null);
            user.setPasswordResetTokenExpiresAt(null);
            userRepository.save(user);
            throw new IllegalStateException("This password reset link has expired. Please request a new one.");
        }

        // Update password and invalidate reset token (single use)
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetTokenExpiresAt(null);
        user.setPasswordUpdatedAt(LocalDateTime.now());
        // A self-service reset satisfies any outstanding forced-change requirement.
        user.setMustChangePassword(false);
        // Let the user back in if they were locked out before resetting.
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Revoke all outstanding refresh tokens for security
        refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());

        auditService.logAuthEvent(user.getId(), user.getUsername(), "PASSWORD_RESET_COMPLETED",
                null, "SUCCESS", "Password reset completed via emailed link");
        log.info("Password reset completed for user [{}] — all refresh tokens revoked", user.getId());
    }

    private AuthTokenResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateRefreshToken();
        String tokenHash = sha256(rawRefreshToken);

        RefreshToken rt = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plusDays(refreshTokenExpiryDays))
                .build();
        refreshTokenRepository.save(rt);

        return AuthTokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .expiresIn(7200)
                .role(user.getRole().name())
                .userId(user.getId())
                .build();
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available.", e);
        }
    }
}

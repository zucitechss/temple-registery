package com.templeregistry.service.impl.auth;

import com.templeregistry.dto.request.auth.MfaSetupRequest;
import com.templeregistry.dto.request.auth.MfaSetupVerifyRequest;
import com.templeregistry.dto.response.auth.MfaSetupVerifyResponse;
import com.templeregistry.entity.auth.MfaRecoveryCode;
import com.templeregistry.entity.auth.MfaType;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.MfaVerificationException;
import com.templeregistry.repository.auth.MfaRecoveryCodeRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.service.auth.MfaService;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.secret.SecretGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MfaServiceImpl implements MfaService {

    private static final int    RECOVERY_CODE_COUNT       = 8;
    private static final int    RECOVERY_CODE_BCRYPT_COST = 12;
    private static final long   OTP_TTL_SECONDS           = 300; // 5 minutes

    private static final String ALLOWED_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // unambiguous chars

    /** In-memory OTP store keyed by userId. Production: replace with Redis. */
    // package-private for testing; do not expose beyond this package
    final ConcurrentHashMap<Long, SmsOtpEntry> otpStore = new ConcurrentHashMap<>();

    private final UserRepository        userRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final CodeVerifier          codeVerifier;
    private final SecretGenerator       secretGenerator;

    // ── Step 4: SMS MFA Setup ───────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void setupSmsMfa(MfaSetupRequest request) {
        User user = findUser(request.getUserId());
        guardNotAlreadyEnabled(user);

        String otp = generateNumericOtp();
        String otpHash = sha256Hex(otp);

        otpStore.put(user.getId(), new SmsOtpEntry(otpHash, Instant.now().plusSeconds(OTP_TTL_SECONDS), request.getPhone()));

        // TODO: Replace with AWS SNS / Twilio call in production.
        // Log only last 4 digits of phone (SC-05: no PII in logs).
        log.info("SMS MFA setup initiated — userId=[{}], phone ending=[{}], OTP=[{}]",
                user.getId(),
                request.getPhone().substring(request.getPhone().length() - 4),
                otp // Dev environment only — remove this log line before production deployment.
        );
    }

    // ── Step 5: Verify OTP + Enable MFA + Issue Recovery Codes ──────────────

    @Override
    @Transactional
    public MfaSetupVerifyResponse verifyAndEnableMfa(MfaSetupVerifyRequest request) {
        User user = findUser(request.getUserId());
        guardNotAlreadyEnabled(user);

        SmsOtpEntry entry = otpStore.get(user.getId());
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            otpStore.remove(user.getId());
            throw new MfaVerificationException("OTP has expired or was not requested. Please restart MFA setup.");
        }

        String submittedHash = sha256Hex(request.getOtp());
        if (!MessageDigest.isEqual(
                entry.hashedOtp().getBytes(StandardCharsets.UTF_8),
                submittedHash.getBytes(StandardCharsets.UTF_8))) {
            throw new MfaVerificationException("Invalid OTP. Please try again.");
        }

        // Enable SMS MFA
        user.setMfaType(MfaType.SMS_OTP);
        user.setMfaPhone(entry.phone());
        userRepository.save(user);
        otpStore.remove(user.getId());

        // Generate 8 recovery codes, bcrypt-hash each, persist
        List<String> plainCodes = generateRecoveryCodes();
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(RECOVERY_CODE_BCRYPT_COST);
        List<MfaRecoveryCode> codeEntities = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (String plain : plainCodes) {
            codeEntities.add(MfaRecoveryCode.builder()
                    .userId(user.getId())
                    .codeHash(bcrypt.encode(plain))
                    .build());
        }
        recoveryCodeRepository.saveAll(codeEntities);

        log.info("SMS MFA enabled — userId=[{}]; {} recovery codes generated", user.getId(), RECOVERY_CODE_COUNT);

        return MfaSetupVerifyResponse.builder()
                .message("MFA enabled successfully. Store your recovery codes in a safe place.")
                .userId(user.getId())
                .recoveryCodes(plainCodes)
                .build();
    }

    // ── Login-time helpers (existing methods, kept for AuthService) ───────────

    @Override
    public String sendSmsOtp(String mobile) {
        String otp = generateNumericOtp();
        log.info("Login SMS OTP generated for mobile ending [{}]", mobile.substring(mobile.length() - 4));
        // TODO: dispatch via AWS SNS / Twilio; store hash in Redis with TTL
        return "login-otp-ref-" + mobile;
    }

    @Override
    public void verifyTotp(String secret, String code) {
        if (!codeVerifier.isValidCode(secret, code)) {
            throw new MfaVerificationException("Invalid TOTP code.");
        }
    }

    /**
     * Login-time SMS OTP verification is NOT implemented.
     *
     * <p>This method used to log and return, i.e. report success for any code
     * (audit finding C-3). No persisted OTP store exists to check against and
     * {@link #sendSmsOtp(String)} never dispatches anything, so it now fails
     * closed. {@code AuthServiceImpl.verifyMfa} rejects the SMS_OTP factor
     * before reaching this point; this is the backstop.</p>
     *
     * @throws MfaVerificationException always, until a real OTP store and SMS
     *         provider are wired up.
     */
    @Override
    public void verifySmsOtp(String referenceKey, String code) {
        log.warn("Login SMS OTP verification attempted but the factor is not implemented — refusing.");
        throw new MfaVerificationException(
                "SMS one-time passcodes are not available. "
                        + "Contact an administrator to switch this account to an authenticator app.");
    }

    @Override
    public String generateTotpSecret() {
        return secretGenerator.generate();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
    }

    private void guardNotAlreadyEnabled(User user) {
        if (user.getRole() != UserRole.TEMPLE_AUTHORITY) {
            throw new MfaVerificationException("MFA setup via this endpoint is only available for TEMPLE_AUTHORITY users.");
        }
        if (user.getMfaType() != null && user.getMfaType() != MfaType.NONE) {
            throw new MfaVerificationException("MFA is already configured for this account.");
        }
    }

    /** Generates a 6-digit numeric OTP using cryptographically secure random. */
    private static String generateNumericOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    /**
     * Generates 8 single-use recovery codes.
     * Format: XXXX-XXXX-XX (10 unambiguous alphanumeric chars split with hyphens).
     */
    private static List<String> generateRecoveryCodes() {
        SecureRandom random = new SecureRandom();
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            char[] segment1 = randomSegment(random, 4);
            char[] segment2 = randomSegment(random, 4);
            char[] segment3 = randomSegment(random, 2);
            codes.add(new String(segment1) + "-" + new String(segment2) + "-" + new String(segment3));
        }
        return codes;
    }

    private static char[] randomSegment(SecureRandom random, int length) {
        char[] seg = new char[length];
        for (int i = 0; i < length; i++) {
            seg[i] = ALLOWED_CHARS.charAt(random.nextInt(ALLOWED_CHARS.length()));
        }
        return seg;
    }

    /** SHA-256 hex encoding for short-lived OTP storage — intentionally NOT bcrypt (speed matters for OTPs). */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    /** Immutable value object for the in-memory OTP store. Package-private for testability. */
    record SmsOtpEntry(String hashedOtp, Instant expiresAt, String phone) {}
}

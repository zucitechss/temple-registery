package com.templeregistry.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Fails startup when a non-development environment is running on a crypto key
 * that was never meant to leave a developer machine (C-6).
 *
 * <p>{@code application.yml} deliberately supplies no defaults for
 * {@code app.encryption.key} / {@code app.hmac.key}, so an unset variable
 * already aborts startup. This guard closes the remaining hole: an operator
 * copying the historic placeholder values — which are public in this
 * repository's git history — into a real environment.</p>
 *
 * <p>Active for every profile except {@code dev} and {@code test}.</p>
 */
@Configuration
@Profile("!dev & !test")
@Slf4j
public class CryptoKeyValidator {

    /** Required key length in bytes for AES-256 / HMAC-SHA256. */
    private static final int REQUIRED_KEY_BYTES = 32;

    /**
     * Key values that appeared as committed defaults or templates. These are
     * compromised by publication and must never authenticate real data.
     */
    private static final Set<String> BANNED_KEYS = Set.of(
            "TempleRegDev32ByteKey!!Replace!!",
            "TempleRegHmacDev32ByteKey!Repl!!",
            "TestEncryptionKey2024ABCDEFGHIJ!",
            "TestHmacKey1234567890123456789012",
            "replace-with-32-byte-secret-key!!",
            "replace-with-32-byte-hmac-key!!!!",
            "12345678901234567890123456789012"
    );

    private final String encryptionKey;
    private final String hmacKey;

    public CryptoKeyValidator(
            @Value("${app.encryption.key}") String encryptionKey,
            @Value("${app.hmac.key}") String hmacKey) {
        this.encryptionKey = encryptionKey;
        this.hmacKey = hmacKey;
    }

    @PostConstruct
    void validate() {
        check("app.encryption.key (APP_ENCRYPTION_KEY)", encryptionKey);
        check("app.hmac.key (APP_HMAC_KEY)", hmacKey);
        log.info("Crypto key validation passed for {} and {}.",
                "app.encryption.key", "app.hmac.key");
    }

    /** Never logs or echoes the key material itself — only the property name. */
    private static void check(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " is not set. Supply it from the platform secret store.");
        }
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length != REQUIRED_KEY_BYTES) {
            throw new IllegalStateException(
                    propertyName + " must be exactly " + REQUIRED_KEY_BYTES
                            + " bytes, but the configured value is " + length + " bytes.");
        }
        if (BANNED_KEYS.contains(value)) {
            throw new IllegalStateException(
                    propertyName + " is set to a known development/placeholder key that is "
                            + "public in this repository's history. Generate a fresh key "
                            + "(openssl rand -base64 24) and supply it from the secret store.");
        }
    }
}

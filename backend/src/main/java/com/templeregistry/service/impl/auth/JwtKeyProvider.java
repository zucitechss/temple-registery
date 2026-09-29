package com.templeregistry.service.impl.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Resolves the RS256 signing keypair without requiring any key material to be
 * committed to Git (C-1).
 *
 * <p>Resolution order, per key:</p>
 * <ol>
 *   <li>{@code app.jwt.private-key} / {@code app.jwt.public-key} — the PEM body
 *       itself, normally injected from {@code APP_JWT_PRIVATE_KEY} /
 *       {@code APP_JWT_PUBLIC_KEY}. This is the production path. Literal
 *       {@code \n} sequences are accepted for secret stores that cannot hold
 *       multi-line values.</li>
 *   <li>{@code app.jwt.private-key-path} / {@code app.jwt.public-key-path} — a
 *       Spring {@link Resource} location. Retained purely as a local-development
 *       convenience; the default location is git-ignored.</li>
 * </ol>
 *
 * <p>If neither source yields a key the application fails to start with an
 * actionable message. Key material is never logged — only which source was
 * used.</p>
 */
@Component
@Slf4j
public class JwtKeyProvider {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public JwtKeyProvider(
            @Value("${app.jwt.private-key:}") String privateKeyPem,
            @Value("${app.jwt.public-key:}") String publicKeyPem,
            @Value("${app.jwt.private-key-path:}") String privateKeyPath,
            @Value("${app.jwt.public-key-path:}") String publicKeyPath,
            ResourceLoader resourceLoader) {

        this.privateKey = parsePrivateKey(resolve(
                "private", "APP_JWT_PRIVATE_KEY", "app.jwt.private-key-path",
                privateKeyPem, privateKeyPath, resourceLoader));

        this.publicKey = parsePublicKey(resolve(
                "public", "APP_JWT_PUBLIC_KEY", "app.jwt.public-key-path",
                publicKeyPem, publicKeyPath, resourceLoader));
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    private static String resolve(String keyKind,
                                  String envVarName,
                                  String pathPropertyName,
                                  String inlinePem,
                                  String resourcePath,
                                  ResourceLoader resourceLoader) {

        if (inlinePem != null && !inlinePem.isBlank()) {
            log.info("JWT {} key loaded from {}.", keyKind, envVarName);
            return inlinePem;
        }

        if (resourcePath != null && !resourcePath.isBlank()) {
            Resource resource = resourceLoader.getResource(resourcePath.trim());
            if (resource.exists() && resource.isReadable()) {
                try {
                    String pem = resource.getContentAsString(StandardCharsets.UTF_8);
                    log.info("JWT {} key loaded from {} (local development fallback).",
                            keyKind, pathPropertyName);
                    return pem;
                } catch (Exception ex) {
                    // Message only — never the file contents.
                    throw new IllegalStateException(
                            "JWT " + keyKind + " key at " + pathPropertyName
                                    + " could not be read: " + ex.getMessage(), ex);
                }
            }
        }

        throw new IllegalStateException(
                "No JWT " + keyKind + " key available. Set " + envVarName
                        + " to the PEM contents (recommended for all deployed "
                        + "environments), or point " + pathPropertyName
                        + " at a readable PEM file for local development.");
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    static RSAPrivateKey parsePrivateKey(String pem) {
        byte[] encoded = decode(pem,
                "-----BEGIN RSA PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----",
                "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "JWT private key is not a valid PKCS#8 RSA key. "
                            + "Regenerate with: openssl genpkey -algorithm RSA "
                            + "-pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem", ex);
        }
    }

    static RSAPublicKey parsePublicKey(String pem) {
        byte[] encoded = decode(pem,
                "-----BEGIN PUBLIC KEY-----", "-----END PUBLIC KEY-----");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "JWT public key is not a valid X.509 RSA key. "
                            + "Regenerate with: openssl rsa -pubout -in jwt-private.pem "
                            + "-out jwt-public.pem", ex);
        }
    }

    /**
     * Strips PEM armour and whitespace, then Base64-decodes. Accepts values
     * carrying literal {@code \n} escapes, which is how most secret managers
     * round-trip a multi-line PEM through a single-line field.
     */
    private static byte[] decode(String pem, String... markers) {
        String body = pem.replace("\\n", "\n")
                .lines()
                .filter(line -> !line.startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);

        for (String marker : markers) {
            body = body.replace(marker, "");
        }
        body = body.replaceAll("\\s+", "");

        if (body.isEmpty()) {
            throw new IllegalStateException(
                    "JWT key value is present but contains no Base64 body after "
                            + "stripping PEM headers.");
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "JWT key value is not valid Base64 PEM content.", ex);
        }
    }
}

package com.templeregistry.service.impl.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Generates a throwaway RS256 keypair for tests, plus its PEM encodings.
 *
 * <p>Exists so the auth test suite never depends on {@code src/main/resources/keys/},
 * which is git-ignored and therefore absent from a clean checkout (C-1).</p>
 */
public record RsaTestKeys(RSAPrivateKey privateKey,
                   RSAPublicKey publicKey,
                   String privateKeyPem,
                   String publicKeyPem) {

    public static RsaTestKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();

            RSAPrivateKey priv = (RSAPrivateKey) pair.getPrivate();
            RSAPublicKey pub = (RSAPublicKey) pair.getPublic();

            return new RsaTestKeys(
                    priv,
                    pub,
                    toPem("PRIVATE KEY", priv.getEncoded()),
                    toPem("PUBLIC KEY", pub.getEncoded()));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not generate an RSA test keypair", ex);
        }
    }

    /** Wraps DER bytes in standard 64-column PEM armour. */
    private static String toPem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }
}

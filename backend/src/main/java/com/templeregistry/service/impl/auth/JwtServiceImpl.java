package com.templeregistry.service.impl.auth;

import com.templeregistry.entity.auth.User;
import com.templeregistry.service.auth.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class JwtServiceImpl implements JwtService {

    private static final String CLAIM_USER_ID    = "userId";
    private static final String CLAIM_ROLE       = "role";
    private static final String CLAIM_DISTRICT   = "districtId";
    private static final String CLAIM_TEMPLE     = "templeId";
    private static final String CLAIM_ACCESS_TYPE = "accessType";
    private static final String CLAIM_MUST_CHANGE_PASSWORD = "mustChangePassword";
    private static final String TOKEN_TYPE_TEMP  = "TEMP";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey  publicKey;
    private final long accessTokenExpiryMs;

    public JwtServiceImpl(
            @Value("${app.jwt.private-key-path}") Resource privateKeyResource,
            @Value("${app.jwt.public-key-path}")  Resource publicKeyResource,
            @Value("${app.jwt.access-token-expiry-ms:900000}") long accessTokenExpiryMs) throws Exception {
        this.privateKey = loadPrivateKey(privateKeyResource);
        this.publicKey  = loadPublicKey(publicKeyResource);
        this.accessTokenExpiryMs = accessTokenExpiryMs;
    }

    @Override
    public String generateAccessToken(User user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(CLAIM_USER_ID,   user.getId())
                .claim(CLAIM_ROLE,      user.getRole().name())
                .claim(CLAIM_DISTRICT,  user.getDistrictId())
                .claim(CLAIM_TEMPLE,    user.getTempleId())
                .claim(CLAIM_ACCESS_TYPE, user.getAccessType() != null ? user.getAccessType().name() : null)
                // Carried in the token so JwtAuthenticationFilter can gate requests without a DB read.
                .claim(CLAIM_MUST_CHANGE_PASSWORD, user.isMustChangePassword())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessTokenExpiryMs))
                .signWith(privateKey)
                .compact();
    }

    @Override
    public String generateTempToken(User user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(CLAIM_USER_ID, user.getId())
                .claim("type", TOKEN_TYPE_TEMP)
                .issuedAt(new Date(now))
                .expiration(new Date(now + 300_000)) // 5 minutes for MFA window
                .signWith(privateKey)
                .compact();
    }

    @Override
    public Claims validateAndParse(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Override
    public String generateRegistrationToken(Map<String, Object> claims, Duration ttl) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject("registration")
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl.toMillis()));
        claims.forEach(builder::claim);
        return builder.signWith(privateKey).compact();
    }

    @Override
    public String generateRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private RSAPrivateKey loadPrivateKey(Resource resource) throws Exception {
        String pem = resource.getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] encoded = Base64.getDecoder().decode(pem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private RSAPublicKey loadPublicKey(Resource resource) throws Exception {
        String pem = resource.getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] encoded = Base64.getDecoder().decode(pem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(encoded));
    }
}

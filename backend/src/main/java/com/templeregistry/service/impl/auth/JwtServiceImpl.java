package com.templeregistry.service.impl.auth;

import com.templeregistry.entity.auth.User;
import com.templeregistry.service.auth.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
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
    private static final String TOKEN_TYPE_TEMP  = "TEMP";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey  publicKey;
    private final long accessTokenExpiryMs;

    /**
     * Production/Spring constructor. The keypair is resolved by
     * {@link JwtKeyProvider}, which prefers environment-supplied PEM values so
     * that no key material has to be committed to Git (C-1).
     */
    public JwtServiceImpl(
            JwtKeyProvider keyProvider,
            @Value("${app.jwt.access-token-expiry-ms:900000}") long accessTokenExpiryMs) {
        this(keyProvider.getPrivateKey(), keyProvider.getPublicKey(), accessTokenExpiryMs);
    }

    /** Direct-key constructor, used by tests that generate an ephemeral keypair. */
    public JwtServiceImpl(RSAPrivateKey privateKey,
                          RSAPublicKey publicKey,
                          long accessTokenExpiryMs) {
        this.privateKey = privateKey;
        this.publicKey  = publicKey;
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
}

package com.templeregistry.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses and validates RS256 JWTs and extracts typed claims.
 * Used only by the filter; the full JwtService (sign + verify) lives in the auth module.
 */
@Component
@Slf4j
public class ScopeHelper {

    /**
     * Typed claims extracted from a validated JWT.
     */
    public record Claims(Long userId, String role, Long districtId, Long templeId, String username, String accessType) {
        /**
         * Returns temple IDs as a Set for compatibility with ActionContext.
         * Currently supports single temple per user; returns singleton set.
         * Future: JWT may contain multiple temple IDs for multi-temple TAs.
         */
        public java.util.Set<Long> templeIds() {
            return templeId != null ? java.util.Set.of(templeId) : java.util.Set.of();
        }

        public static Claims fromContext() {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof Claims claims) {
                return claims;
            }
            throw new org.springframework.security.access.AccessDeniedException("Invalid authentication principal");
        }
    }

    private final RSAPublicKey publicKey;

    public ScopeHelper(@Value("${app.jwt.public-key-path}") Resource publicKeyResource) throws Exception {
        this.publicKey = loadPublicKey(publicKeyResource);
    }

    /**
     * A parsed token: the principal claims plus token-level flags that are deliberately kept
     * off {@link Claims} so the principal record (constructed in ~50 places) stays unchanged.
     */
    public record ParsedToken(Claims claims, boolean mustChangePassword) {
    }

    public Claims parse(String token) {
        return parseFull(token).claims();
    }

    public ParsedToken parseFull(String token) {
        io.jsonwebtoken.Claims body = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId      = body.get("userId", Long.class);
        String role      = body.get("role", String.class);
        Long districtId  = body.get("districtId", Long.class);
        Long templeId    = body.get("templeId", Long.class);
        String username  = body.getSubject();
        String accessType = body.get("accessType", String.class);
        // Absent in tokens minted before this claim existed — treat as "no change required".
        Boolean mustChange = body.get("mustChangePassword", Boolean.class);

        return new ParsedToken(
                new Claims(userId, role, districtId, templeId, username, accessType),
                Boolean.TRUE.equals(mustChange));
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
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(encoded));
    }
}

package com.templeregistry.security;

import com.templeregistry.service.impl.auth.JwtKeyProvider;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPublicKey;

/**
 * Parses and validates RS256 JWTs and extracts typed claims.
 * Used only by the filter; the full JwtService (sign + verify) lives in the auth module.
 *
 * <p>The verification key comes from {@link JwtKeyProvider}, the single key-loading
 * mechanism introduced by C-1. This class previously bound
 * {@code ${app.jwt.public-key-path}} to a {@link org.springframework.core.io.Resource}
 * and parsed the PEM itself, which made it blind to {@code APP_JWT_PUBLIC_KEY}: on the
 * production profile that path is deliberately blank, so the resource was null and
 * startup failed with a {@code NullPointerException}.</p>
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

    public ScopeHelper(JwtKeyProvider keyProvider) {
        this.publicKey = keyProvider.getPublicKey();
    }

    public Claims parse(String token) {
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

        return new Claims(userId, role, districtId, templeId, username, accessType);
    }
}

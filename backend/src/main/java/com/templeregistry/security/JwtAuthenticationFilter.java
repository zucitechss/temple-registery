package com.templeregistry.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final ScopeHelper scopeHelper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        log.debug("JWT extraction for [{}]: token={}", request.getRequestURI(), 
                token != null ? "found" : "NOT_FOUND");
        if (token != null && token.length() > 50) {
            log.debug("Token first 50 chars: {}", token.substring(0, 50));
        }
        
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                ScopeHelper.Claims claims = scopeHelper.parse(token);
                log.debug("JWT parsed successfully for user: {}", claims.username());
                MDC.put("userId", String.valueOf(claims.userId()));
                MDC.put("role", claims.role());

                var authority = new SimpleGrantedAuthority("ROLE_" + claims.role());
                var auth = new UsernamePasswordAuthenticationToken(
                        claims, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("Authentication set in context for user: {}", claims.username());
            } catch (Exception ex) {
                log.warn("JWT validation failed for request [{}]: {}", request.getRequestURI(), ex.getMessage());
                // Do not set authentication; downstream security will return 401
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        // Prefer Authorization header
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            log.debug("Token extracted from Authorization header");
            return header.substring(7);
        }
        // Fall back to httpOnly cookie (preferred for browser clients)
        if (request.getCookies() != null) {
            log.debug("Cookies found: {} cookies present", request.getCookies().length);
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (StringUtils.hasText(value)) {
                        log.debug("Token extracted from access_token cookie");
                        return value;
                    }
                }
            }
            log.debug("No access_token cookie found in request cookies");
        } else {
            log.debug("No cookies in request");
        }
        // Last resort: query parameter — ONLY for SSE EventSource connections that cannot set
        // Authorization headers. Query-parameter tokens appear in access logs and browser history;
        // restrict this path to SSE streaming endpoints only to minimise token leakage surface.
        String requestUri = request.getRequestURI();
        if (requestUri != null && requestUri.endsWith("/stream")) {
            String queryToken = request.getParameter("token");
            if (StringUtils.hasText(queryToken)) {
                log.debug("JWT extracted from query parameter for SSE endpoint: {}", requestUri);
                return queryToken;
            }
        }
        return null;
    }
}

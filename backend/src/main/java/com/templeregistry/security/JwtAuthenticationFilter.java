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
                ScopeHelper.ParsedToken parsed = scopeHelper.parseFull(token);
                ScopeHelper.Claims claims = parsed.claims();
                log.debug("JWT parsed successfully for user: {}", claims.username());
                MDC.put("userId", String.valueOf(claims.userId()));
                MDC.put("role", claims.role());

                // A user holding an admin-issued temporary password may only reach the endpoints
                // needed to replace it. Hiding the UI is not enough — this is the real gate.
                if (parsed.mustChangePassword() && !isPasswordChangePath(request)) {
                    log.debug("Blocking [{}] for user [{}] — password change required",
                            request.getRequestURI(), claims.userId());
                    writePasswordChangeRequired(response);
                    return;
                }

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

    /**
     * Endpoints a user must still reach while their password change is outstanding: the change
     * endpoint itself, plus the whole auth module (login, logout, refresh, me) which carries no
     * application data and is already {@code permitAll} in {@code SecurityConfig}.
     */
    private static final List<String> PASSWORD_CHANGE_ALLOWED_PATHS = List.of(
            "/api/v1/profile/password",
            "/api/v1/auth/");

    private boolean isPasswordChangePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && PASSWORD_CHANGE_ALLOWED_PATHS.stream().anyMatch(uri::startsWith);
    }

    /** Matches the application-wide ApiResponse error shape so the frontend parses it normally. */
    private void writePasswordChangeRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,"
                        + "\"message\":\"You must change your temporary password before continuing.\","
                        + "\"errorCode\":\"PASSWORD_CHANGE_REQUIRED\"}");
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

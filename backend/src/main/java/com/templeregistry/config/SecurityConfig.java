package com.templeregistry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.common.ApiResponse;
import com.templeregistry.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            "/api/v1/geo/**",
            "/actuator/health",
            "/actuator/info",
            "/error"
    };

    /**
     * OpenAPI/Swagger paths (H-6).
     *
     * <p>These are public only while the documentation is actually being served. Production
     * switches springdoc off, and leaving the paths permitted there would be worse than
     * useless: with no handler behind them a request passes security, matches nothing, and
     * falls into the catch-all exception handler, which answers 500. Keeping the permit tied
     * to the same flag makes them fall through to {@code authenticated()} and answer 401,
     * exactly like any other unknown path.</p>
     */
    private static final String[] API_DOCS_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * Mirrors {@code springdoc.api-docs.enabled}, which {@code application-prod.yml} sets to
     * false. A plain field rather than a constructor parameter: this class has a single
     * Lombok-generated constructor and adding a second would reintroduce the ambiguity that
     * broke JwtServiceImpl's wiring.
     */
    @Value("${springdoc.api-docs.enabled:true}")
    private boolean apiDocsEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(csrfTokenRequestHandler()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                        auth.requestMatchers(PUBLIC_PATHS).permitAll();
                        if (apiDocsEnabled) {
                            auth.requestMatchers(API_DOCS_PATHS).permitAll();
                        }
                        // Public temple search — exact path only (NOT wildcard, to prevent PII exposure via /{id})
                        auth.requestMatchers(HttpMethod.GET, "/api/v1/temples").permitAll()
                        // Public photo serve — temple photos are not sensitive
                        .requestMatchers(HttpMethod.GET, "/api/v1/temples/*/profile-photo/serve").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/temples/*/photos/*/serve").permitAll()
                        .anyRequest().authenticated();
                })
                // Return 401 (not Spring's default 403) for requests with missing/expired tokens
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(csrfAwareAccessDeniedHandler()))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CSRF token store for the SPA (H-5).
     *
     * <p>The application authenticates browsers with an {@code access_token} cookie
     * ({@link com.templeregistry.security.JwtAuthenticationFilter}) that is issued
     * {@code SameSite=None}, so the browser attaches it to cross-site requests and every
     * state-changing endpoint was forgeable while {@code csrf.disable()} was in place.</p>
     *
     * <p>This is the stateless double-submit pattern: the token is stored in a cookie the SPA
     * can read and must be echoed back in a header. A cross-site attacker can make the browser
     * replay cookies but cannot read them or set a custom header, so it cannot produce a
     * matching pair. CORS is a separate control and is not relied on here.</p>
     *
     * <p>Cookie contract, deliberately mirroring the auth cookies so it survives the same
     * deployment topologies: name {@code XSRF-TOKEN}, header {@code X-XSRF-TOKEN},
     * {@code Path=/}, {@code Secure}, {@code SameSite=None}, and <b>not</b> {@code HttpOnly} —
     * the SPA has to read this value. The authentication cookies stay {@code HttpOnly}.</p>
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .secure(true)
                .sameSite("None"));
        return repository;
    }

    /**
     * Plain (non-XOR) request handler so the header value the SPA sends is the literal cookie
     * value. Spring Security's default {@code XorCsrfTokenRequestAttributeHandler} masks the
     * token per request, which a client that simply echoes the cookie cannot reproduce.
     *
     * <p>{@code setCsrfRequestAttributeName(null)} opts out of deferred token loading so the
     * token is resolved — and therefore the cookie issued — on every request rather than only
     * when something reads it.</p>
     */
    private CsrfTokenRequestAttributeHandler csrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * Gives CSRF rejections a distinct, machine-readable error code.
     *
     * <p>The SPA retries a request once after a CSRF rejection, so it has to tell that case
     * apart from the application's genuine authorization failures, which already return 403
     * with {@code ACCESS_DENIED} / {@code JURISDICTION_DENIED} (see
     * {@code GlobalExceptionHandler}). Retrying those would be wrong.</p>
     *
     * <p>Only {@link CsrfException} is handled here; everything else is delegated to the exact
     * handler Spring Security would have used anyway, so non-CSRF denials are unchanged.</p>
     */
    private AccessDeniedHandler csrfAwareAccessDeniedHandler() {
        AccessDeniedHandler defaultHandler = new AccessDeniedHandlerImpl();
        return (request, response, accessDeniedException) -> {
            if (!(accessDeniedException instanceof CsrfException)) {
                defaultHandler.handle(request, response, accessDeniedException);
                return;
            }
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.error(
                    "Missing or invalid CSRF token. Retry after refreshing the token.",
                    "CSRF_TOKEN_INVALID"));
        };
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}

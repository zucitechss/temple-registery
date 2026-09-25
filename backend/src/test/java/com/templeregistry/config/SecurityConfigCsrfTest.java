package com.templeregistry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.controller.auth.AuthController;
import com.templeregistry.dto.request.auth.LoginRequest;

import com.templeregistry.dto.response.auth.AuthTokenResponse;
import com.templeregistry.security.JwtAuthenticationFilter;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.accesscontrol.PolicyEvaluationService;
import com.templeregistry.service.auth.AuthService;
import com.templeregistry.service.auth.UserProfileService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H-5 - CSRF protection for cookie-based JWT authentication.
 *
 * <p>Unlike the other {@code @WebMvcTest} slices in this project, this one deliberately does
 * NOT set {@code excludeAutoConfiguration = {SecurityAutoConfiguration.class, ...}}. Every
 * existing controller slice switches Spring Security off, which is precisely why the
 * {@code csrf.disable()} in {@link SecurityConfig} was never caught by a test: no test in the
 * repository exercised the filter chain at all.</p>
 *
 * <p>Authentication here goes through the real {@link JwtAuthenticationFilter} using the
 * {@code access_token} cookie, because that is the exact combination that makes the
 * application forgeable: the auth cookie is {@code SameSite=None}, so a browser attaches it
 * to cross-site requests.</p>
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtAuthenticationFilter.class,
        SecurityConfigCsrfTest.CsrfProbeController.class})
@TestPropertySource(properties = "app.cors.allowed-origins=https://portal.temple-registry.gov.in")
class SecurityConfigCsrfTest {

    /** Spring Security's conventional SPA cookie/header pair. */
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String PROBE = "/api/v1/csrf-probe";
    private static final String ALLOWED_ORIGIN = "https://portal.temple-registry.gov.in";

    /** MfaVerifyRequest has no all-args constructor, so the body is written directly. */
    private static final String MFA_VERIFY_BODY =
            "{\"tempToken\":\"temp.token\",\"mfaCode\":\"123456\"}";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ScopeHelper scopeHelper;
    @MockBean AuthService authService;
    @MockBean UserProfileService userProfileService;
    @MockBean PolicyEvaluationService policyEvaluationService;
    @MockBean UserDetailsService userDetailsService;

    @BeforeEach
    void stubAuthenticatedUser() {
        when(scopeHelper.parse(anyString())).thenReturn(new ScopeHelper.Claims(
                7L, "TEMPLE_AUTHORITY", 3L, 11L, "ta-user", "FULL"));
    }

    /** A request carrying a valid auth cookie but no CSRF token - the forgery scenario. */
    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.cookie(new Cookie("access_token", "stub.jwt.token"));
    }

    /** Adds a matching CSRF cookie + header, which is what the SPA does on unsafe methods. */
    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder) {
        String token = "csrf-token-value";
        return builder.cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // Rejection: unsafe methods without a CSRF token

    @Nested
    class Rejection {

        @Test
        void should_return403_when_authenticatedPostHasNoCsrfToken() throws Exception {
            mockMvc.perform(authed(post(PROBE)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return403_when_authenticatedPutHasNoCsrfToken() throws Exception {
            mockMvc.perform(authed(put(PROBE)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return403_when_authenticatedPatchHasNoCsrfToken() throws Exception {
            mockMvc.perform(authed(patch(PROBE)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return403_when_authenticatedDeleteHasNoCsrfToken() throws Exception {
            mockMvc.perform(authed(delete(PROBE)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return403_when_csrfHeaderDoesNotMatchCsrfCookie() throws Exception {
            mockMvc.perform(authed(post(PROBE))
                            .cookie(new Cookie(CSRF_COOKIE, "the-real-token"))
                            .header(CSRF_HEADER, "an-attacker-guess"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return403_when_csrfCookieIsPresentButHeaderIsMissing() throws Exception {
            // A cross-site forgery replays cookies automatically but cannot set headers.
            mockMvc.perform(authed(post(PROBE))
                            .cookie(new Cookie(CSRF_COOKIE, "the-real-token")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_returnDistinctCsrfErrorCode_when_csrfTokenIsMissing() throws Exception {
            // The SPA retries once on a CSRF rejection. It must be able to tell this apart from
            // the application's own 403s (ACCESS_DENIED / JURISDICTION_DENIED), which are
            // genuine authorization failures and must NOT trigger a retry.
            mockMvc.perform(authed(post(PROBE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
        }

        @Test
        void should_returnDistinctCsrfErrorCode_when_csrfTokenIsMismatched() throws Exception {
            mockMvc.perform(authed(post(PROBE))
                            .cookie(new Cookie(CSRF_COOKIE, "the-real-token"))
                            .header(CSRF_HEADER, "an-attacker-guess"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
        }
    }

    // Success: unsafe methods with a matching token

    @Nested
    class Success {

        @Test
        void should_return200_when_authenticatedPostCarriesMatchingCsrfToken() throws Exception {
            mockMvc.perform(withCsrf(authed(post(PROBE))))
                    .andExpect(status().isOk())
                    .andExpect(content().string("POST ok"));
        }

        @Test
        void should_return200_when_authenticatedPutCarriesMatchingCsrfToken() throws Exception {
            mockMvc.perform(withCsrf(authed(put(PROBE))))
                    .andExpect(status().isOk())
                    .andExpect(content().string("PUT ok"));
        }

        @Test
        void should_return200_when_authenticatedPatchCarriesMatchingCsrfToken() throws Exception {
            mockMvc.perform(withCsrf(authed(patch(PROBE))))
                    .andExpect(status().isOk())
                    .andExpect(content().string("PATCH ok"));
        }

        @Test
        void should_return200_when_authenticatedDeleteCarriesMatchingCsrfToken() throws Exception {
            mockMvc.perform(withCsrf(authed(delete(PROBE))))
                    .andExpect(status().isOk())
                    .andExpect(content().string("DELETE ok"));
        }
    }

    // Safe methods are untouched

    @Nested
    class SafeMethods {

        @Test
        void should_return200_when_authenticatedGetHasNoCsrfToken() throws Exception {
            mockMvc.perform(authed(get(PROBE)))
                    .andExpect(status().isOk());
        }

        @Test
        void should_return200_when_authenticatedHeadHasNoCsrfToken() throws Exception {
            mockMvc.perform(authed(head(PROBE)))
                    .andExpect(status().isOk());
        }

        @Test
        void should_allowCorsPreflight_when_optionsRequestHasNoCsrfToken() throws Exception {
            mockMvc.perform(options(PROBE)
                            .header("Origin", ALLOWED_ORIGIN)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
        }
    }

    // Authentication flows must keep working, and must NOT be exempt

    @Nested
    class AuthenticationFlows {

        private AuthTokenResponse tokens() {
            return AuthTokenResponse.builder()
                    .accessToken("access.jwt.token").refreshToken("refresh-token-hex")
                    .expiresIn(3600).role("TEMPLE_AUTHORITY").userId(10L).build();
        }

        @Test
        void should_return200_when_loginCarriesCsrfToken() throws Exception {
            when(authService.login(any())).thenReturn(tokens());

            mockMvc.perform(withCsrf(post("/api/v1/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LoginRequest("user1", "password123"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        void should_return403_when_loginHasNoCsrfToken() throws Exception {
            // Login is deliberately NOT exempt: login-CSRF lets an attacker silently
            // sign a victim browser into an account the attacker controls.
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LoginRequest("user1", "password123"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return200_when_mfaVerifyCarriesCsrfToken() throws Exception {
            when(authService.verifyMfa(any())).thenReturn(tokens());

            mockMvc.perform(withCsrf(post("/api/v1/auth/mfa-verify"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(MFA_VERIFY_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        void should_return403_when_mfaVerifyHasNoCsrfToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa-verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(MFA_VERIFY_BODY))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return200_when_refreshCarriesCsrfToken() throws Exception {
            when(authService.refresh(anyString())).thenReturn(tokens());

            mockMvc.perform(withCsrf(post("/api/v1/auth/refresh"))
                            .cookie(new Cookie("refresh_token", "refresh-token-hex")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        void should_return403_when_refreshHasNoCsrfToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(new Cookie("refresh_token", "refresh-token-hex")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_return200_when_logoutCarriesCsrfToken() throws Exception {
            mockMvc.perform(withCsrf(post("/api/v1/auth/logout"))
                            .cookie(new Cookie("refresh_token", "refresh-token-hex")))
                    .andExpect(status().isOk());
        }

        @Test
        void should_return403_when_logoutHasNoCsrfToken() throws Exception {
            // Logout-CSRF is a real denial of service: any site could log the user out.
            mockMvc.perform(post("/api/v1/auth/logout")
                            .cookie(new Cookie("refresh_token", "refresh-token-hex")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_stillSetHttpOnlyAuthCookies_when_loginSucceedsWithCsrfToken() throws Exception {
            when(authService.login(any())).thenReturn(tokens());

            MvcResult result = mockMvc.perform(withCsrf(post("/api/v1/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LoginRequest("user1", "password123"))))
                    .andExpect(status().isOk())
                    .andReturn();

            List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
            String accessCookie = setCookies.stream()
                    .filter(c -> c.startsWith("access_token=access.jwt.token"))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "login must still issue the access_token cookie, got: " + setCookies));

            assertThat(accessCookie)
                    .as("auth cookies must stay HttpOnly - H-5 must not expose the JWT to JS")
                    .contains("HttpOnly")
                    .contains("Secure")
                    .contains("SameSite=None");
        }
    }

    // GET /api/v1/auth/csrf

    @Nested
    class CsrfTokenEndpoint {

        @Test
        void should_return200WithTokenAndHeaderName_when_csrfEndpointCalledUnauthenticated()
                throws Exception {
            mockMvc.perform(get("/api/v1/auth/csrf"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.token").isNotEmpty())
                    .andExpect(jsonPath("$.data.headerName").value(CSRF_HEADER))
                    .andExpect(jsonPath("$.data.parameterName").value("_csrf"));
        }

        @Test
        void should_issueReadableCsrfCookie_when_csrfEndpointCalled() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            // Asserted on the Cookie object rather than the rendered Set-Cookie header:
            // CookieCsrfTokenRepository expresses SameSite via Cookie#setAttribute, and
            // MockHttpServletResponse only renders SameSite into the header string for its own
            // MockCookie type. A real servlet container writes the attribute out correctly.
            Cookie csrfCookie = result.getResponse().getCookie(CSRF_COOKIE);
            assertThat(csrfCookie)
                    .as("GET /auth/csrf must issue the %s cookie", CSRF_COOKIE)
                    .isNotNull();

            assertThat(csrfCookie.isHttpOnly())
                    .as("the SPA must be able to read this cookie, so it must NOT be HttpOnly")
                    .isFalse();
            assertThat(csrfCookie.getValue())
                    .as("cookie must carry the same token the body returned")
                    .isNotBlank();
            assertThat(csrfCookie.getPath()).isEqualTo("/");
            assertThat(csrfCookie.getSecure())
                    .as("must match the auth cookie attributes so it survives the same deployments")
                    .isTrue();
            assertThat(csrfCookie.getAttribute("SameSite"))
                    .as("SameSite must match the auth cookies (None), or the token cookie is "
                            + "dropped on exactly the cross-site requests the auth cookie survives")
                    .isEqualTo("None");
        }

        @Test
        void should_returnTokenThatIsAcceptedAsCsrfToken_when_usedOnAnUnsafeRequest()
                throws Exception {
            MvcResult issued = mockMvc.perform(get("/api/v1/auth/csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            String token = objectMapper.readTree(issued.getResponse().getContentAsString())
                    .path("data").path("token").asText();
            assertThat(token).isNotBlank();

            mockMvc.perform(authed(post(PROBE))
                            .cookie(new Cookie(CSRF_COOKIE, token))
                            .header(CSRF_HEADER, token))
                    .andExpect(status().isOk());
        }
    }

    /**
     * Stand-in for the 117 state-changing endpoints across the application's 39 controllers.
     * It is not in {@code PUBLIC_PATHS}, so it requires authentication like the real ones.
     */
    @RestController
    @RequestMapping(PROBE)
    static class CsrfProbeController {
        @GetMapping    String read()    { return "GET ok"; }
        @PostMapping   String create()  { return "POST ok"; }
        @PutMapping    String replace() { return "PUT ok"; }
        @PatchMapping  String amend()   { return "PATCH ok"; }
        @DeleteMapping String remove()  { return "DELETE ok"; }
    }
}

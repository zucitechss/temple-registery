package com.templeregistry.config;

import com.templeregistry.controller.auth.AuthController;
import com.templeregistry.security.JwtAuthenticationFilter;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.accesscontrol.PolicyEvaluationService;
import com.templeregistry.service.auth.AuthService;
import com.templeregistry.service.auth.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * H-6 — the development posture: documentation stays reachable without logging in.
 *
 * <p>H-6 is about production exposure, not about removing Swagger from the team's local
 * workflow. Without this test, tying the security permit to
 * {@code springdoc.api-docs.enabled} could silently lock developers out of the UI.</p>
 *
 * <p>There is no springdoc handler inside a MockMvc slice, so the assertion is about
 * authorization only: the request must not be turned away with 401.</p>
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=https://portal.temple-registry.gov.in",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
})
class SecurityConfigSwaggerEnabledTest {

    @Autowired MockMvc mockMvc;

    @MockBean ScopeHelper scopeHelper;
    @MockBean AuthService authService;
    @MockBean UserProfileService userProfileService;
    @MockBean PolicyEvaluationService policyEvaluationService;
    @MockBean UserDetailsService userDetailsService;

    @Test
    void should_notRequireAuthentication_forTheOpenApiDocument_when_documentationIsEnabled()
            throws Exception {
        int status = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus();

        assertThat(status).as("GET /v3/api-docs must not be rejected as unauthorized").isNotEqualTo(401);
    }

    @Test
    void should_notRequireAuthentication_forSwaggerUi_when_documentationIsEnabled()
            throws Exception {
        int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();

        assertThat(status).as("GET /swagger-ui.html must not be rejected as unauthorized").isNotEqualTo(401);
    }
}

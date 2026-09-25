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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H-6 — the production posture: API documentation is switched off, so its paths must not be
 * publicly reachable either.
 *
 * <p>Disabling springdoc alone is not enough. These paths sit in
 * {@link SecurityConfig}'s public list, so with no handler behind them a request passes
 * security, finds nothing, and falls into the catch-all exception handler — which answers
 * <strong>500 INTERNAL_ERROR</strong>. That was observed on a running server. Tying the
 * permit to the same property that enables the documentation keeps the two in step, so the
 * paths fall through to {@code authenticated()} and answer 401 like any other unknown path.</p>
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=https://portal.temple-registry.gov.in",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
})
class SecurityConfigSwaggerDisabledTest {

    @Autowired MockMvc mockMvc;

    @MockBean ScopeHelper scopeHelper;
    @MockBean AuthService authService;
    @MockBean UserProfileService userProfileService;
    @MockBean PolicyEvaluationService policyEvaluationService;
    @MockBean UserDetailsService userDetailsService;

    @Test
    void should_notExposeTheOpenApiDocument_when_documentationIsDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_notExposeTheOpenApiDocumentGroups_when_documentationIsDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_notExposeSwaggerUi_when_documentationIsDisabled() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_notExposeSwaggerUiAssets_when_documentationIsDisabled() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_keepOtherPublicPathsPublic_when_documentationIsDisabled() throws Exception {
        // Guards against over-restricting: only the documentation paths are affected.
        int status = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();

        assertThat(status).as("GET /actuator/health must stay public").isNotEqualTo(401);
    }
}

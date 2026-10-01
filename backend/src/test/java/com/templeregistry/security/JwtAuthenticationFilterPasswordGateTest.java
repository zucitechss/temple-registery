package com.templeregistry.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The forced-password-change gate: a user holding an admin-issued temporary password must not be
 * able to reach application endpoints, regardless of what the frontend chooses to render.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterPasswordGateTest {

    private static final String TOKEN = "a-signed-jwt";

    @Mock ScopeHelper scopeHelper;
    @Mock FilterChain filterChain;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    private static final ScopeHelper.Claims CLAIMS =
            new ScopeHelper.Claims(7L, "TEMPLE_AUTHORITY", null, 3L, "ta_user", "EDIT");

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(scopeHelper);
        request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void givenTokenRequiresPasswordChange(boolean mustChange) {
        when(scopeHelper.parseFull(TOKEN))
                .thenReturn(new ScopeHelper.ParsedToken(CLAIMS, mustChange));
    }

    @Test
    void should_blockApplicationEndpoint_when_passwordChangeIsRequired() throws Exception {
        givenTokenRequiresPasswordChange(true);
        request.setRequestURI("/api/v1/temples/3");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void should_allowChangePasswordEndpoint_when_passwordChangeIsRequired() throws Exception {
        givenTokenRequiresPasswordChange(true);
        request.setRequestURI("/api/v1/profile/password");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void should_allowAuthEndpoints_when_passwordChangeIsRequired() throws Exception {
        givenTokenRequiresPasswordChange(true);
        request.setRequestURI("/api/v1/auth/me");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void should_notBlockAnything_when_passwordChangeIsNotRequired() throws Exception {
        givenTokenRequiresPasswordChange(false);
        request.setRequestURI("/api/v1/temples/3");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }
}

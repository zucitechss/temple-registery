package com.templeregistry.security;

import com.templeregistry.controller.admin.AdminController;
import com.templeregistry.service.impl.admin.AdminServiceImpl;
import com.templeregistry.service.impl.auth.UserProfileServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the Part 10 authorization matrix for the password-management feature.
 *
 * <p>Enforcement itself is Spring's {@code @EnableMethodSecurity} (asserted live by
 * {@code ApplicationContextIntegrationTest}); what this test guarantees is that the guards are
 * present and scoped correctly, so nobody can silently widen or drop one.
 */
class PasswordManagementAuthorizationTest {

    private static String preAuthorizeOn(Class<?> type, String methodName, Class<?>... params)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, params);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        return annotation == null ? null : annotation.value();
    }

    // ── Reset another user's password: SUPER_ADMIN only ──────────────────────

    @Test
    void should_restrictAdminPasswordReset_toSuperAdminOnly() throws Exception {
        String expression = preAuthorizeOn(AdminServiceImpl.class, "resetUserPassword", Long.class);

        assertThat(expression)
                .as("resetUserPassword must carry a @PreAuthorize guard")
                .isNotNull()
                .isEqualTo(RoleConstants.ADMIN_ONLY);
    }

    @Test
    void should_denyNonSuperAdminRoles_forAdminPasswordReset() throws Exception {
        String expression = preAuthorizeOn(AdminServiceImpl.class, "resetUserPassword", Long.class);

        // DC, DC Staff, Temple Authority, Auditor and Viewer must all be excluded.
        assertThat(expression)
                .contains("SUPER_ADMIN")
                .doesNotContain("DISTRICT_COLLECTOR")
                .doesNotContain("DC_STAFF")
                .doesNotContain("TEMPLE_AUTHORITY")
                .doesNotContain("AUDITOR")
                .doesNotContain("VIEWER");
    }

    @Test
    void should_guardTheAdminControllerItself_withSuperAdminOnly() {
        PreAuthorize annotation = AdminController.class.getAnnotation(PreAuthorize.class);

        assertThat(annotation).as("AdminController must be guarded at the class level").isNotNull();
        assertThat(annotation.value()).isEqualTo(RoleConstants.ADMIN_ONLY);
    }

    // ── Own profile and own password: every authenticated role ───────────────

    @Test
    void should_allowAnyAuthenticatedRole_toViewOwnProfile() throws Exception {
        assertThat(preAuthorizeOn(UserProfileServiceImpl.class, "getCurrentUserProfile"))
                .isEqualTo("isAuthenticated()");
    }

    @Test
    void should_allowAnyAuthenticatedRole_toChangeOwnPassword() throws Exception {
        String expression = preAuthorizeOn(UserProfileServiceImpl.class, "changeOwnPassword",
                com.templeregistry.dto.request.auth.ChangePasswordRequest.class);

        assertThat(expression)
                .as("changing your own password must require authentication, not a specific role")
                .isEqualTo("isAuthenticated()");
    }
}

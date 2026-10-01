package com.templeregistry.service.impl.auth;

import com.templeregistry.dto.request.auth.ChangePasswordRequest;
import com.templeregistry.dto.response.auth.UserProfileResponse;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.auth.RefreshTokenRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.repository.contractor.ContractorRepository;
import com.templeregistry.repository.declaration.DeclarationRepository;
import com.templeregistry.repository.employee.EmployeeRepository;
import com.templeregistry.repository.geo.DistrictRepository;
import com.templeregistry.repository.temple.TempleProfileStagingRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.trust.TrustRepository;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.workflow.WorkflowEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceImplTest {

    private static final long USER_ID = 7L;

    @Mock UserRepository userRepository;
    @Mock TempleProfileStagingRepository stagingRepository;
    @Mock TempleRepository templeRepository;
    @Mock TrustRepository trustRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock ContractorRepository contractorRepository;
    @Mock DeclarationRepository declarationRepository;
    @Mock WorkflowEngine workflowEngine;
    @Mock DistrictRepository districtRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuditService auditService;

    @InjectMocks UserProfileServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .username("dc_mysuru").email("dc@example.com").fullName("DC Mysuru")
                .role(UserRole.DISTRICT_COLLECTOR).passwordHash("current-hash")
                .isActive(true).build();
        user.setId(USER_ID);

        var claims = new ScopeHelper.Claims(USER_ID, "DISTRICT_COLLECTOR", null, null, "dc_mysuru", "EDIT");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ChangePasswordRequest request(String current, String next, String confirm) {
        return new ChangePasswordRequest(current, next, confirm);
    }

    // ── Profile ──────────────────────────────────────────────────────────────

    @Test
    void should_returnProfileFields_when_authenticatedUserRequestsProfile() {
        user.setLastLoginAt(LocalDateTime.now().minusHours(2));
        user.setPasswordUpdatedAt(LocalDateTime.now().minusDays(10));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserProfileResponse response = service.getCurrentUserProfile();

        assertThat(response.getUserId()).isEqualTo(USER_ID);
        assertThat(response.getUsername()).isEqualTo("dc_mysuru");
        assertThat(response.getLastLoginAt()).isNotNull();
        assertThat(response.getPasswordUpdatedAt()).isNotNull();
        assertThat(response.isMustChangePassword()).isFalse();
    }

    @Test
    void should_neverSerialisePasswordOrToken_when_profileIsReturned() throws Exception {
        user.setPasswordResetTokenHash("secret-reset-token-hash");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        // Serialise exactly as the API does, so a future field addition cannot leak a secret.
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(service.getCurrentUserProfile());

        assertThat(json)
                .doesNotContain("current-hash")
                .doesNotContain("secret-reset-token-hash")
                .doesNotContain("passwordHash")
                .doesNotContain("passwordResetToken")
                .doesNotContain("mfaSecret");
    }

    @Test
    void should_throwEntityNotFound_when_profileRequestedForDeletedUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentUserProfile())
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Change own password ──────────────────────────────────────────────────

    @Test
    void should_updateHashedPassword_when_currentPasswordIsCorrect() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "current-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewPass456", "current-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass456")).thenReturn("new-hash");

        service.changeOwnPassword(request("OldPass123", "NewPass456", "NewPass456"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getPasswordUpdatedAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void should_throw_when_currentPasswordIsIncorrect() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "current-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changeOwnPassword(request("WrongPass", "NewPass456", "NewPass456")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Current password is incorrect.");

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void should_throw_when_confirmationDoesNotMatchNewPassword() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "current-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changeOwnPassword(request("OldPass123", "NewPass456", "Different789")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("New password and confirmation password do not match.");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void should_throw_when_newPasswordEqualsCurrentPassword() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "current-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changeOwnPassword(request("OldPass123", "OldPass123", "OldPass123")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("New password must be different from the current password.");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void should_revokeSessionsAndAudit_when_passwordChangeSucceeds() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "current-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewPass456", "current-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass456")).thenReturn("new-hash");

        service.changeOwnPassword(request("OldPass123", "NewPass456", "NewPass456"));

        verify(refreshTokenRepository).revokeAllByUserId(eq(USER_ID), any());
        verify(auditService).logAuthEvent(anyLong(), eq("dc_mysuru"), eq("PASSWORD_CHANGED"),
                any(), eq("SUCCESS"), anyString());
    }

    @Test
    void should_clearForcedChangeFlagAndPendingResetToken_when_passwordChangeSucceeds() {
        user.setMustChangePassword(true);
        user.setPasswordResetTokenHash("pending");
        user.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("TempPass99", "current-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewPass456", "current-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass456")).thenReturn("new-hash");

        service.changeOwnPassword(request("TempPass99", "NewPass456", "NewPass456"));

        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.getPasswordResetTokenHash()).isNull();
        assertThat(user.getPasswordResetTokenExpiresAt()).isNull();
    }
}

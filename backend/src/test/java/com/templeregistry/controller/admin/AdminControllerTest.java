package com.templeregistry.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.admin.CreateUserRequest;
import com.templeregistry.dto.request.admin.UpdateUserRequest;
import com.templeregistry.dto.response.admin.UserAdminResponse;
import com.templeregistry.entity.auth.UserRole;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.templeregistry.repository.audit.AuditAuthEventRepository;
import com.templeregistry.repository.audit.AuditDataEventRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.admin.AdminDashboardService;
import com.templeregistry.service.admin.AdminService;
import com.templeregistry.service.audit.GovernanceAuditService;
import com.templeregistry.service.declaration.DeclarationService;
import com.templeregistry.service.notification.NotificationRuleService;
import com.templeregistry.util.PaginationUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AdminController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class AdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AdminService adminService;
    @MockBean DeclarationService declarationService;
    @MockBean AuditDataEventRepository dataEventRepo;
    @MockBean AuditAuthEventRepository authEventRepo;
    @MockBean UserRepository userRepository;
    @MockBean TempleRepository templeRepository;
    @MockBean PaginationUtil paginationUtil;
    @MockBean GovernanceAuditService governanceAuditService;
    @MockBean NotificationRuleService notificationRuleService;
    @MockBean AdminDashboardService adminDashboardService;
    @MockBean ScopeHelper scopeHelper;

    private UserAdminResponse sampleUser() {
        return UserAdminResponse.builder()
                .id(1L)
                .username("admin_user")
                .email("admin@temple.gov")
                .role(UserRole.SUPER_ADMIN)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    class ListUsers {

        @Test
        void should_return200WithPage_when_usersExist() throws Exception {
            when(paginationUtil.clampSize(10)).thenReturn(10);
            var pageImpl = new PageImpl<>(List.of(sampleUser()), PageRequest.of(0, 10), 1L);
            when(adminService.listUsers(0, 10, "", "")).thenReturn(PaginatedResponse.of(pageImpl));

            mockMvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].username").value("admin_user"));
        }

        @Test
        void should_return200EmptyPage_when_noUsersExist() throws Exception {
            when(paginationUtil.clampSize(10)).thenReturn(10);
            var pageImpl = new PageImpl<>(List.<UserAdminResponse>of(), PageRequest.of(0, 10), 0L);
            when(adminService.listUsers(0, 10, "", "")).thenReturn(PaginatedResponse.of(pageImpl));

            mockMvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    @Nested
    class GetUser {

        @Test
        void should_return200_when_userFound() throws Exception {
            when(adminService.getUserById(1L)).thenReturn(sampleUser());

            mockMvc.perform(get("/api/v1/admin/users/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1));
        }
    }

    @Nested
    class CreateUser {

        @Test
        void should_return201_when_validRequest() throws Exception {
            CreateUserRequest req = new CreateUserRequest();
            req.setUsername("newuser");
            req.setEmail("new@temple.gov");
            req.setPassword("Password123!");
            req.setFullName("New User");
            req.setRole(UserRole.TEMPLE_AUTHORITY);
            req.setDistrictId(5L);

            when(adminService.createUser(any())).thenReturn(sampleUser());

            mockMvc.perform(post("/api/v1/admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("User created."));
        }

        @Test
        void should_return400_when_usernameBlank() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"\",\"email\":\"a@b.com\",\"password\":\"Password123!\",\"fullName\":\"Full\",\"role\":\"TEMPLE_AUTHORITY\",\"districtId\":1}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class UpdateUser {

        @Test
        void should_return200_when_validUpdate() throws Exception {
            UpdateUserRequest req = new UpdateUserRequest();
            req.setEmail("updated@temple.gov");
            req.setActive(true);

            when(adminService.updateUser(eq(1L), any())).thenReturn(sampleUser());

            mockMvc.perform(put("/api/v1/admin/users/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    class DeactivateUser {

        @Test
        void should_return200_when_userDeactivated() throws Exception {
            doNothing().when(adminService).deactivateUser(1L);

            mockMvc.perform(post("/api/v1/admin/users/1/deactivate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("User deactivated."));
        }
    }

    @Nested
    class ActivateUser {

        @Test
        void should_return200_when_userActivated() throws Exception {
            doNothing().when(adminService).activateUser(1L);

            mockMvc.perform(post("/api/v1/admin/users/1/activate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("User activated."));
        }
    }

    @Nested
    class ResetUserPassword {

        @Test
        void should_return200_when_temporaryPasswordIssued() throws Exception {
            doNothing().when(adminService).resetUserPassword(1L);

            mockMvc.perform(post("/api/v1/admin/users/1/reset-password"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message")
                            .value("Temporary password generated and sent to the user's registered email."));

            verify(adminService).resetUserPassword(1L);
        }

        @Test
        void should_neverReturnTheTemporaryPassword_when_passwordIsReset() throws Exception {
            doNothing().when(adminService).resetUserPassword(1L);

            String body = mockMvc.perform(post("/api/v1/admin/users/1/reset-password"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // The email channel is the only place the password may appear.
            org.assertj.core.api.Assertions.assertThat(body)
                    .doesNotContain("password\":\"")
                    .doesNotContain("temporaryPassword");
        }
    }
}

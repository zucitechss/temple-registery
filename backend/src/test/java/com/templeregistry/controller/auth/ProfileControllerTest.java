package com.templeregistry.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.dto.request.auth.ChangePasswordRequest;
import com.templeregistry.service.auth.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ProfileController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class ProfileControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean UserProfileService userProfileService;
    @MockBean com.templeregistry.security.ScopeHelper scopeHelper;

    private String body(String current, String next, String confirm) throws Exception {
        return objectMapper.writeValueAsString(new ChangePasswordRequest(current, next, confirm));
    }

    @Test
    void should_return200_when_passwordChangeSucceeds() throws Exception {
        mockMvc.perform(patch("/api/v1/profile/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OldPass123", "NewPass456", "NewPass456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Your password has been changed successfully."));

        verify(userProfileService).changeOwnPassword(any(ChangePasswordRequest.class));
    }

    @Test
    void should_return422_when_currentPasswordIsIncorrect() throws Exception {
        // IllegalStateException is mapped to 422 by GlobalExceptionHandler.
        doThrow(new IllegalStateException("Current password is incorrect."))
                .when(userProfileService).changeOwnPassword(any());

        mockMvc.perform(patch("/api/v1/profile/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WrongPass", "NewPass456", "NewPass456")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Current password is incorrect."));
    }

    @Test
    void should_return400_when_newPasswordIsTooShort() throws Exception {
        mockMvc.perform(patch("/api/v1/profile/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OldPass123", "short", "short")))
                .andExpect(status().isBadRequest());

        verify(userProfileService, never()).changeOwnPassword(any());
    }

    @Test
    void should_return400_when_currentPasswordIsMissing() throws Exception {
        mockMvc.perform(patch("/api/v1/profile/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPass456\",\"confirmPassword\":\"NewPass456\"}"))
                .andExpect(status().isBadRequest());

        verify(userProfileService, never()).changeOwnPassword(any());
    }

    @Test
    void should_return400_when_confirmationIsMissing() throws Exception {
        mockMvc.perform(patch("/api/v1/profile/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"OldPass123\",\"newPassword\":\"NewPass456\"}"))
                .andExpect(status().isBadRequest());

        verify(userProfileService, never()).changeOwnPassword(any());
    }

    @Test
    void should_neverEchoPasswords_when_passwordChangeSucceeds() throws Exception {
        String response = mockMvc.perform(patch("/api/v1/profile/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OldPass123", "NewPass456", "NewPass456")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain("OldPass123")
                .doesNotContain("NewPass456");
    }
}

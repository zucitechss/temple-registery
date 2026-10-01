package com.templeregistry.controller.auth;

import com.templeregistry.common.ApiResponse;
import com.templeregistry.dto.request.auth.ChangePasswordRequest;
import com.templeregistry.service.auth.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service profile endpoints for the currently authenticated user.
 *
 * <p>Deliberately mounted outside {@code /api/v1/auth/**}, which {@code SecurityConfig}
 * exposes as {@code permitAll} for the login/reset flows. Everything under
 * {@code /api/v1/profile} therefore requires an authenticated request at the filter chain,
 * with {@code @PreAuthorize} in the service layer as the final check.
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Current user profile and self-service password change")
public class ProfileController {

    private final UserProfileService userProfileService;

    // Profile reads are served by the pre-existing GET /api/v1/auth/me — no duplicate endpoint.

    @PatchMapping("/password")
    @Operation(summary = "Change the current authenticated user's own password.")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        userProfileService.changeOwnPassword(request);
        return ResponseEntity.ok(ApiResponse.success("Your password has been changed successfully."));
    }
}

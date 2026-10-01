package com.templeregistry.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Request body for PATCH /api/v1/profile/password.
 *
 * <p>Size bounds mirror the existing application password policy
 * ({@code CreateUserRequest.password}, {@code PasswordResetConfirmRequest.newPassword}).
 * Cross-field rules (confirmation match, new != current, current is correct) are enforced
 * in the service layer — frontend validation is never trusted.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required.")
    private String currentPassword;

    @NotBlank(message = "New password is required.")
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters.")
    private String newPassword;

    @NotBlank(message = "Password confirmation is required.")
    private String confirmPassword;
}

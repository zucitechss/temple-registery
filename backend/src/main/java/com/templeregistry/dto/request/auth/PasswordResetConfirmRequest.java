package com.templeregistry.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetConfirmRequest {

    @NotBlank(message = "Reset token is required.")
    private String token;

    @NotBlank(message = "New password is required.")
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters.")
    private String newPassword;

    /**
     * Optional confirmation of {@link #newPassword}. When supplied it MUST match, so the
     * confirmation is verified server-side and not only in the browser. Left optional so the
     * existing published request contract is not tightened for callers that never sent it.
     */
    private String confirmPassword;
}

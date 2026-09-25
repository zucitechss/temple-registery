package com.templeregistry.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MfaVerifyRequest {

    @NotBlank(message = "Temporary token is required.")
    private String tempToken;

    /**
     * Required (C-3). Before remediation this field was optional and never
     * read, so an empty submission still produced a full token pair.
     */
    @NotBlank(message = "MFA code is required.")
    @Size(min = 6, max = 8, message = "MFA code must be 6-8 characters.")
    private String mfaCode;
}

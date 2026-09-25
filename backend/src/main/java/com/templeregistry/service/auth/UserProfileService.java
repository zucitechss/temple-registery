package com.templeregistry.service.auth;

import com.templeregistry.dto.request.auth.ChangePasswordRequest;
import com.templeregistry.dto.response.auth.UserProfileResponse;

/**
 * Provides the current authenticated user's profile data plus the TA first-login
 * setup completion checklist (GET /api/auth/me).
 */
public interface UserProfileService {

    /**
     * Returns the profile for the currently authenticated user.
     * For TEMPLE_AUTHORITY users, also returns the module completion checklist.
     */
    UserProfileResponse getCurrentUserProfile();

    /**
     * Changes the currently authenticated user's own password.
     *
     * <p>Verifies the supplied current password against the stored hash, enforces the
     * confirmation match and rejects reusing the current password. On success the new password
     * is stored with the application password encoder, any pending reset token is invalidated,
     * the forced-change flag is cleared and all refresh tokens for the user are revoked.
     *
     * @throws IllegalStateException when the current password is wrong or a rule is violated
     */
    void changeOwnPassword(ChangePasswordRequest request);
}

package com.templeregistry.dto.response.auth;

import com.templeregistry.entity.auth.UserAccessType;
import com.templeregistry.entity.auth.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Response for GET /api/auth/me.
 * Includes profile fields and a first-login setup checklist for TA users.
 */
@Getter
@Builder
public class UserProfileResponse {

    private Long userId;
    private String username;
    private String email;
    private String fullName;
    private String mobile;
    private UserRole role;
    private boolean active;
    private Long districtId;
    private Long templeId;
    private boolean aadhaarVerified;
    private String designation;
    private UserAccessType accessType;

    /** Resolved district name for the assigned districtId. Null when unassigned or unknown. */
    private String districtName;

    /** Resolved temple name for the assigned templeId. Null when unassigned or unknown. */
    private String templeName;

    /** Last successful sign-in. Null for accounts that have never logged in. */
    private LocalDateTime lastLoginAt;

    /** Last successful password change. Null when the password has never been changed. */
    private LocalDateTime passwordUpdatedAt;

    /** True while an admin-issued temporary password must still be replaced. */
    private boolean mustChangePassword;

    /** Null for non-TEMPLE_AUTHORITY roles. */
    private TempleCompletionChecklist completionChecklist;

    @Getter
    @Builder
    public static class TempleCompletionChecklist {
        /** Latest staging status label, or null if no profile draft exists. */
        private String templeProfileStatus;
        private boolean trustExists;
        private long employeeCount;
        private long contractorCount;
        /** Latest declaration status, or null if no declaration exists. */
        private String latestDeclarationStatus;
    }
}

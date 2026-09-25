package com.templeregistry.service.impl.auth;

import com.templeregistry.dto.request.auth.ChangePasswordRequest;
import com.templeregistry.dto.response.auth.UserProfileResponse;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.entity.declaration.DeclarationStatus;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.service.workflow.WorkflowEngine;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.VerificationStatus;
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
import com.templeregistry.service.auth.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import com.templeregistry.entity.temple.TempleProfileStaging;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final UserRepository userRepository;
    private final TempleProfileStagingRepository stagingRepository;
    private final TempleRepository templeRepository;
    private final TrustRepository trustRepository;
    private final EmployeeRepository employeeRepository;
    private final ContractorRepository contractorRepository;
    private final DeclarationRepository declarationRepository;
    private final WorkflowEngine workflowEngine;
    private final DistrictRepository districtRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public UserProfileResponse getCurrentUserProfile() {
        ScopeHelper.Claims claims = currentClaims();
        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new EntityNotFoundException("User", claims.userId()));

        UserProfileResponse.TempleCompletionChecklist checklist = null;
        if (user.getRole() == UserRole.TEMPLE_AUTHORITY && user.getTempleId() != null) {
            checklist = buildChecklist(user.getTempleId());
        }

        return UserProfileResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .mobile(user.getMobile())
                .role(user.getRole())
                .active(user.isActive())
                .districtId(user.getDistrictId())
                .templeId(user.getTempleId())
                .aadhaarVerified(user.isAadhaarVerified())
                .designation(user.getDesignation())
                .accessType(user.getAccessType())
                .districtName(resolveDistrictName(user.getDistrictId()))
                .templeName(resolveTempleName(user.getTempleId()))
                .lastLoginAt(user.getLastLoginAt())
                .passwordUpdatedAt(user.getPasswordUpdatedAt())
                .mustChangePassword(user.isMustChangePassword())
                .completionChecklist(checklist)
                .build();
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void changeOwnPassword(ChangePasswordRequest request) {
        ScopeHelper.Claims claims = currentClaims();
        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new EntityNotFoundException("User", claims.userId()));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            log.warn("[ChangePassword] Incorrect current password for user [{}]", user.getId());
            throw new IllegalStateException("Current password is incorrect.");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalStateException("New password and confirmation password do not match.");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalStateException("New password must be different from the current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordUpdatedAt(LocalDateTime.now());
        user.setMustChangePassword(false);
        // A completed change supersedes any pending reset link.
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetTokenExpiresAt(null);
        userRepository.save(user);

        // Force every other session to re-authenticate with the new password.
        refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());

        auditService.logAuthEvent(user.getId(), user.getUsername(), "PASSWORD_CHANGED",
                null, "SUCCESS", "User changed their own password");
        log.info("[ChangePassword] Password changed for user [{}] — refresh tokens revoked", user.getId());
    }

    private String resolveDistrictName(Long districtId) {
        return districtId == null ? null
                : districtRepository.findById(districtId).map(d -> d.getName()).orElse(null);
    }

    private String resolveTempleName(Long templeId) {
        return templeId == null ? null
                : templeRepository.findById(templeId).map(Temple::getName).orElse(null);
    }

    private UserProfileResponse.TempleCompletionChecklist buildChecklist(Long templeId) {
        Temple temple = templeRepository.findById(templeId).orElse(null);

        // Derive the profile status from the latest non-superseded staging record.
        // This must always be checked first — even for VERIFIED temples — because a
        // subsequent staging submission may have been REJECTED after the initial approval.
        String profileStatus = deriveProfileStatusForChecklist(templeId, temple);

        if (temple != null && temple.getVerificationStatus() == VerificationStatus.FLAGGED
                && !"REJECTED".equals(profileStatus) && !"DRAFT".equals(profileStatus)
                && !"SUBMITTED".equals(profileStatus)) {
            // Only override with FLAGGED if there is no more specific workflow status.
            profileStatus = "FLAGGED";
        }

        boolean trustExists = !trustRepository.findAllByTempleId(templeId).isEmpty();
        long employeeCount = employeeRepository.findAllByTempleId(templeId, PageRequest.of(0, 1)).getTotalElements();
        long contractorCount = contractorRepository.findAllByTempleId(templeId, PageRequest.of(0, 1)).getTotalElements();
        String declarationStatus = declarationRepository
                .findAllByTempleId(templeId, PageRequest.of(0, 1))
                .stream().findFirst().map(d -> d.getStatus().name()).orElse(null);

        return UserProfileResponse.TempleCompletionChecklist.builder()
                .templeProfileStatus(profileStatus)
                .trustExists(trustExists)
                .employeeCount(employeeCount)
                .contractorCount(contractorCount)
                .latestDeclarationStatus(declarationStatus)
                .build();
    }

    /**
     * Derives the canonical profile status for the completion checklist by inspecting
     * the most recent workflow instance for the temple's profile staging.
     * Uses findTopByTempleIdOrderByVersionNumberDesc (paginated) to avoid
     * IncorrectResultSizeDataAccessException from the raw @Query method.
     * Falls back to "APPROVED" only when the temple is VERIFIED and no staging exists.
     */
    private String deriveProfileStatusForChecklist(Long templeId, Temple temple) {
        // Use the paginated helper — safe to call even when multiple staging records exist.
        Optional<TempleProfileStaging> latestStaging =
                stagingRepository.findTopByTempleIdOrderByVersionNumberDesc(templeId);

        if (latestStaging.isPresent()) {
            try {
                WorkflowInstance instance = workflowEngine.getState(
                        WorkflowEntityType.TEMPLE_PROFILE, latestStaging.get().getId());
                return switch (instance.getStatus()) {
                    case REJECTED                -> "REJECTED";
                    case DRAFT,
                         UPDATED_AFTER_APPROVAL  -> "DRAFT";
                    case SUBMITTED,
                         UNDER_REVIEW,
                         RESUBMITTED             -> "SUBMITTED";
                    case APPROVED,
                         RE_APPROVED,
                         SUPERSEDED             -> "APPROVED";
                    default                      -> instance.getStatus().name();
                };
            } catch (Exception e) {
                log.warn("[UserProfile] No workflow instance for staging id={} templeId={} — skipping",
                        latestStaging.get().getId(), templeId);
            }
        }

        // No staging found (or no workflow instance) — fall back to verificationStatus.
        if (temple != null && temple.getVerificationStatus() == VerificationStatus.VERIFIED) {
            return "APPROVED";
        }
        return null;
    }

    private ScopeHelper.Claims currentClaims() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims c) return c;
        throw new IllegalStateException("Authenticated principal is not a ScopeHelper.Claims instance.");
    }
}

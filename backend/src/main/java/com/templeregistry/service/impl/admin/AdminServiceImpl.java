package com.templeregistry.service.impl.admin;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.admin.CreateUserRequest;
import com.templeregistry.dto.request.admin.UpdateUserRequest;
import com.templeregistry.dto.response.admin.TempleOptionResponse;
import com.templeregistry.dto.response.admin.UserAdminResponse;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.exception.DuplicateResourceException;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.auth.RefreshTokenRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.repository.geo.CityRepository;
import com.templeregistry.repository.geo.DistrictRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.admin.AdminService;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.notification.EmailService;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.util.PaginationUtil;
import com.templeregistry.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final TempleRepository templeRepository;
    private final DistrictRepository districtRepository;
    private final CityRepository cityRepository;
    private final PasswordEncoder passwordEncoder;
    private final TempleSearchSummaryService searchSummaryService;
    private final AuditService auditService;
    private final PaginationUtil paginationUtil;
    private final EmailService emailService;
    private final RefreshTokenRepository refreshTokenRepository;

    @org.springframework.beans.factory.annotation.Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional(readOnly = true)
    public PaginatedResponse<UserAdminResponse> listUsers(int page, int size, String search, String role) {
        Pageable pageable = PageRequest.of(page, paginationUtil.clampSize(size));
        String normalizedSearch = (search == null) ? "" : search.trim();
        Page<User> result;
        if (role != null && !role.isBlank() && !"ALL".equalsIgnoreCase(role)) {
            UserRole userRole = UserRole.valueOf(role.toUpperCase());
            result = userRepository.searchUsersByRole(userRole, normalizedSearch, pageable);
        } else {
            result = userRepository.searchUsers(normalizedSearch, pageable);
        }
        return PaginatedResponse.of(result.map(this::toResponse));
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional(readOnly = true)
    public UserAdminResponse getUserById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public UserAdminResponse createUser(CreateUserRequest rq) {
        if (userRepository.existsByUsername(rq.getUsername())) {
            throw new DuplicateResourceException("User", "username", rq.getUsername());
        }
        if (userRepository.existsByEmail(rq.getEmail())) {
            throw new DuplicateResourceException("User", "email", rq.getEmail());
        }

        Long templeId = null;

        if (rq.getRole() == UserRole.TEMPLE_AUTHORITY) {
            if (rq.isCreateTemple()) {
                templeId = createAndSaveTemple(rq);
            } else {
                templeId = resolveExistingTemple(rq);
            }
        }

        User user = User.builder()
                .username(rq.getUsername()).email(rq.getEmail())
                .passwordHash(passwordEncoder.encode(rq.getPassword()))
                .fullName(rq.getFullName()).mobile(rq.getMobile())
                .role(rq.getRole()).districtId(rq.getDistrictId())
                .cityId(rq.getCityId())
                .templeId(templeId)
                .aadhaarNumber(rq.getAadhaarNumber())
                .designation(rq.getDesignation())
                .accessType(rq.getAccessType() != null ? rq.getAccessType() : com.templeregistry.entity.auth.UserAccessType.EDIT)
                .isActive(true).build();
        User saved = userRepository.save(user);
        auditService.logDataEvent(currentActorId(), "SUPER_ADMIN", "CREATE_USER",
                "User", saved.getId(), "Created user: " + saved.getUsername());

        // Send credentials email — password is passed directly and is NEVER logged
        if (rq.isSendCredentialsEmail()) {
            emailService.sendUserAccountCreatedEmail(
                saved.getEmail(),
                saved.getUsername(),
                rq.getPassword(),         // plaintext; used only for email render, never stored
                saved.getRole().name(),
                baseUrl + "/login"
            );
        }

        return toResponse(saved);
    }

    /** Case 1: auto-create a minimal Temple, return its ID. */
    private Long createAndSaveTemple(CreateUserRequest rq) {
        if (rq.getTempleName() == null || rq.getTempleName().isBlank()) {
            throw new IllegalStateException("Temple name is required when creating a new temple.");
        }
        if (rq.getDistrictId() == null) {
            throw new IllegalStateException("District is required when creating a new temple.");
        }

        String registrationNumber = generateTempleRegistrationNumber();
        Temple temple = Temple.builder()
                .registrationNumber(registrationNumber)
                .name(rq.getTempleName())
                .primaryDeity("")
                .districtId(rq.getDistrictId())
                .status(TempleStatus.ACTIVE)
                .trustRegistered(false)
                .build();
        Temple saved = templeRepository.save(temple);
        searchSummaryService.scheduleRefresh(saved.getId());
        log.info("Auto-created temple [id={}, regNo={}, name='{}'] for new TA user",
                saved.getId(), registrationNumber, rq.getTempleName());
        return saved.getId();
    }

    /** Case 2: validate and return the ID of an existing temple. */
    private Long resolveExistingTemple(CreateUserRequest rq) {
        if (rq.getExistingTempleId() == null) {
            throw new IllegalStateException("An existing temple must be selected when createTemple is false.");
        }
        Temple temple = templeRepository.findById(rq.getExistingTempleId())
                .orElseThrow(() -> new EntityNotFoundException("Temple", rq.getExistingTempleId()));
        if (temple.getStatus() == TempleStatus.ARCHIVED) {
            throw new IllegalStateException("Cannot assign an archived temple to a user.");
        }
        log.info("Assigning existing temple [id={}, name='{}'] to new TA user",
                temple.getId(), temple.getName());
        return temple.getId();
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public UserAdminResponse updateUser(Long id, UpdateUserRequest rq) {
        User user = findOrThrow(id);
        if (rq.getEmail() != null) user.setEmail(rq.getEmail());
        if (rq.getFullName() != null) user.setFullName(rq.getFullName());
        if (rq.getPassword() != null && !rq.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(rq.getPassword()));
            log.info("Password reset for user [{}]", id);
            auditService.logDataEvent(currentActorId(), "SUPER_ADMIN", "RESET_PASSWORD",
                    "User", id, "Password reset by admin");
        }
        if (rq.getMobile() != null) user.setMobile(rq.getMobile());
        if (rq.getRole() != null) user.setRole(rq.getRole());
        if (rq.getActive() != null) user.setActive(rq.getActive());
        if (rq.getDistrictId() != null) user.setDistrictId(rq.getDistrictId());
        if (rq.getCityId() != null) user.setCityId(rq.getCityId());
        if (rq.getTempleId() != null) user.setTempleId(rq.getTempleId());
        if (rq.getDesignation() != null) user.setDesignation(rq.getDesignation());
        if (rq.getAccessType() != null) user.setAccessType(rq.getAccessType());
        User saved = userRepository.save(user);
        auditService.logDataEvent(currentActorId(), "SUPER_ADMIN", "UPDATE_USER",
                "User", id, "Updated user details");
        return toResponse(saved);
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public void deactivateUser(Long id) {
        User user = findOrThrow(id);
        user.setActive(false);
        userRepository.save(user);
        auditService.logDataEvent(currentActorId(), "SUPER_ADMIN", "DEACTIVATE_USER",
                "User", id, "Deactivated user");
        log.info("User [{}] deactivated.", id);
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public void activateUser(Long id) {
        User user = findOrThrow(id);
        user.setActive(true);
        userRepository.save(user);
        auditService.logDataEvent(currentActorId(), "SUPER_ADMIN", "ACTIVATE_USER",
                "User", id, "Activated user");
        if (user.getTempleId() != null) {
            searchSummaryService.scheduleRefresh(user.getTempleId());
        }
        log.info("User [{}] activated.", id);
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public void resetUserPassword(Long id) {
        User user = findOrThrow(id);

        // Generated locally, held only for the duration of this method and the email render.
        String temporaryPassword = TemporaryPasswordGenerator.generate();

        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        user.setPasswordUpdatedAt(java.time.LocalDateTime.now());
        // An admin reset supersedes any self-service reset link the user may hold.
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetTokenExpiresAt(null);
        // Clear any lockout so the user can actually sign in with the new temporary password.
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Every existing session must die — the old password is gone.
        refreshTokenRepository.revokeAllByUserId(user.getId(), java.time.LocalDateTime.now());

        emailService.sendTemporaryPasswordEmail(
                user.getEmail(),
                user.getFullName(),
                user.getUsername(),
                temporaryPassword,        // email render only — never logged or persisted in plaintext
                baseUrl + "/login");

        auditService.logDataEvent(currentActorId(), "SUPER_ADMIN", "ADMIN_RESET_USER_PASSWORD",
                "User", id, "Temporary password issued and emailed to the registered address");
        // Log the user id only — never the temporary password.
        log.info("[AdminResetPassword] Temporary password issued for user [{}] — sessions revoked", id);
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    public void refreshTempleSearchSummary(Long templeId) {
        searchSummaryService.refresh(templeId);
        log.info("Manual search summary refresh triggered for temple [{}]", templeId);
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    public void rebuildSearchSummary() {
        searchSummaryService.rebuildAll();
        auditService.logDataEvent(currentActorId(), "SUPER_ADMIN", "REBUILD_SEARCH_SUMMARY",
                "System", 0L, "Triggered manual rebuild");
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional(readOnly = true)
    public PaginatedResponse<TempleOptionResponse> searchTemples(String query, int page, int size) {
        int clampedSize = paginationUtil.clampSize(size);
        String trimmed = (query == null) ? "" : query.trim();

        // Collect district IDs matching the query (for district-name search)
        List<Long> districtIds = trimmed.isBlank()
                ? Collections.emptyList()
                : districtRepository.findByNameContainingIgnoreCase(trimmed)
                        .stream().map(d -> d.getId()).collect(Collectors.toList());

        // Pad with a sentinel if empty so IN clause doesn't fail
        if (districtIds.isEmpty()) {
            districtIds = Collections.singletonList(-1L);
        }

        Page<Temple> result = templeRepository.searchForAssignment(
                trimmed, districtIds, PageRequest.of(page, clampedSize));

        // Batch-load district names
        java.util.Set<Long> dIds = result.getContent().stream()
                .map(Temple::getDistrictId).collect(Collectors.toSet());
        Map<Long, String> districtNameById = districtRepository.findAllById(dIds)
                .stream().collect(Collectors.toMap(
                        d -> d.getId(),
                        d -> d.getName()));

        return PaginatedResponse.of(result.map(t -> TempleOptionResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .registrationNumber(t.getRegistrationNumber())
                .districtName(districtNameById.getOrDefault(t.getDistrictId(), ""))
                .grade(t.getGrade() != null ? t.getGrade().name() : null)
                .status(t.getStatus() != null ? t.getStatus().name() : null)
                .districtId(t.getDistrictId())
                .cityId(t.getCityId())
                .build()));
    }

    private Long currentActorId() {
        var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims) {
            return ((ScopeHelper.Claims) principal).userId();
        }
        return 0L;
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User", id));
    }

    private UserAdminResponse toResponse(User u) {
        String districtName = u.getDistrictId() != null
                ? districtRepository.findById(u.getDistrictId()).map(d -> d.getName()).orElse(null)
                : null;
        String templeName = u.getTempleId() != null
                ? templeRepository.findById(u.getTempleId()).map(t -> t.getName()).orElse(null)
                : null;
        return UserAdminResponse.builder()
                .id(u.getId()).username(u.getUsername()).email(u.getEmail())
                .fullName(u.getFullName()).mobile(u.getMobile()).role(u.getRole())
                .active(u.isActive()).aadhaarVerified(u.isAadhaarVerified())
                .aadhaarNumber(u.getAadhaarNumber())
                .districtId(u.getDistrictId()).districtName(districtName)
                .cityId(u.getCityId())
                .templeId(u.getTempleId()).templeName(templeName)
                .designation(u.getDesignation())
                .accessType(u.getAccessType())
                .lastLoginAt(u.getLastLoginAt()).createdAt(u.getCreatedAt()).build();
    }

    /** Generates a unique temple registration number in the format KA-TMP-{UUID8}. */
    private static String generateTempleRegistrationNumber() {
        String uuid8 = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "KA-TMP-" + uuid8;
    }
}

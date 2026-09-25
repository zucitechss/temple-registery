package com.templeregistry.controller.admin;

import com.templeregistry.common.ApiResponse;
import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.admin.CreateUserRequest;
import com.templeregistry.dto.request.admin.UpdateNotificationRuleRequest;
import com.templeregistry.dto.request.admin.UpdateUserRequest;
import com.templeregistry.dto.response.admin.AuditEventResponse;
import com.templeregistry.dto.response.admin.GovernanceHistoryResponse;
import com.templeregistry.dto.response.admin.NotificationRuleResponse;
import com.templeregistry.dto.response.admin.StatewideDashboardResponse;
import com.templeregistry.dto.response.admin.TempleOptionResponse;
import com.templeregistry.dto.response.admin.UserAdminResponse;
import com.templeregistry.entity.audit.GovernanceActionHistory;
import com.templeregistry.entity.audit.AuditDataEvent;
import com.templeregistry.repository.audit.AuditAuthEventRepository;
import com.templeregistry.repository.audit.AuditDataEventRepository;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.admin.AdminDashboardService;
import com.templeregistry.service.admin.AdminService;
import com.templeregistry.service.audit.GovernanceAuditService;
import com.templeregistry.service.notification.NotificationRuleService;
import com.templeregistry.service.declaration.DeclarationService;
import com.templeregistry.util.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Super-admin: user management, audit logs, search summary rebuild")
@PreAuthorize(RoleConstants.ADMIN_ONLY)
public class AdminController {

    private final AdminService adminService;
    private final DeclarationService declarationService;
    private final AuditDataEventRepository dataEventRepo;
    private final AuditAuthEventRepository authEventRepo;
    private final UserRepository userRepository;
    private final TempleRepository templeRepository;
    private final PaginationUtil paginationUtil;
    private final GovernanceAuditService governanceAuditService;
    private final NotificationRuleService notificationRuleService;
    private final AdminDashboardService adminDashboardService;

    /* ───── Users ───── */

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<PaginatedResponse<UserAdminResponse>>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String role) {
        return ResponseEntity.ok(ApiResponse.success("Users retrieved.", adminService.listUsers(page, size, search, role)));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserAdminResponse>> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("User retrieved.", adminService.getUserById(id)));
    }

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<UserAdminResponse>> createUser(@Valid @RequestBody CreateUserRequest rq) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created.", adminService.createUser(rq)));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserAdminResponse>> updateUser(
            @PathVariable Long id, @Valid @RequestBody UpdateUserRequest rq) {
        return ResponseEntity.ok(ApiResponse.success("User updated.", adminService.updateUser(id, rq)));
    }

    @PostMapping("/users/{id}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        adminService.deactivateUser(id);
        return ResponseEntity.ok(ApiResponse.success("User deactivated."));
    }

    @PostMapping("/users/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activate(@PathVariable Long id) {
        adminService.activateUser(id);
        return ResponseEntity.ok(ApiResponse.success("User activated."));
    }

    @PostMapping("/users/{id}/reset-password")
    @Operation(summary = "Generate a temporary password for a user and email it to their registered address")
    public ResponseEntity<ApiResponse<Void>> resetUserPassword(@PathVariable Long id) {
        adminService.resetUserPassword(id);
        // The temporary password is deliberately absent from the response body.
        return ResponseEntity.ok(ApiResponse.success(
                "Temporary password generated and sent to the user's registered email."));
    }

    /* ───── Temple assignment search ───── */

    @GetMapping("/temples/search")
    @Operation(summary = "Search active temples for TA user assignment dropdown")
    public ResponseEntity<ApiResponse<PaginatedResponse<TempleOptionResponse>>> searchTemples(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success("Temples retrieved.",
                adminService.searchTemples(q, page, size)));
    }

    /* ───── Audit logs ───── */

    @GetMapping("/audit-events")
    @Operation(summary = "Paginated data mutation audit log (SA only)")
    public ResponseEntity<ApiResponse<PaginatedResponse<AuditEventResponse>>> listAuditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var result = dataEventRepo.findAll(
                PageRequest.of(page, paginationUtil.clampSize(size),
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "occurredAt")));

        // Batch-resolve actor names to avoid N+1 queries
        Set<Long> actorIds = result.stream().map(AuditDataEvent::getActorId).collect(Collectors.toSet());
        Map<Long, String> actorNames = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(u -> u.getId(), u -> u.getFullName()));

        // Batch-resolve temple names for TEMPLE entity types
        Set<Long> templeEntityIds = result.stream()
                .filter(e -> "TEMPLE".equals(e.getEntityType()))
                .map(AuditDataEvent::getEntityId)
                .collect(Collectors.toSet());
        Map<Long, String> templeNames = templeRepository.findAllById(templeEntityIds).stream()
                .collect(Collectors.toMap(t -> t.getId(), t -> t.getName()));

        var mapped = result.map(e -> {
            String actorName = actorNames.getOrDefault(e.getActorId(), "User #" + e.getActorId());
            String entityName;
            if ("TEMPLE".equals(e.getEntityType())) {
                entityName = templeNames.getOrDefault(e.getEntityId(), "Temple #" + e.getEntityId());
            } else {
                // For sub-temple entities, show "Temple Name > EntityType" when temple context is available
                entityName = e.getEntityType() + " #" + e.getEntityId();
            }
            return AuditEventResponse.builder()
                    .id(e.getId())
                    .actorId(e.getActorId())
                    .actorName(actorName)
                    .actorRole(e.getActorRole())
                    .action(e.getAction())
                    .entityType(e.getEntityType())
                    .entityId(e.getEntityId())
                    .entityName(entityName)
                    .details(e.getDetail() != null ? e.getDetail() : "")
                    .occurredAt(e.getOccurredAt())
                    .build();
        });
        return ResponseEntity.ok(ApiResponse.success("Audit events retrieved.", PaginatedResponse.of(mapped)));
    }

    @GetMapping("/auth-events")
    @Operation(summary = "Paginated authentication audit log (SA only)")
    public ResponseEntity<ApiResponse<?>> listAuthEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var result = authEventRepo.findAllByOrderByOccurredAtDesc(PageRequest.of(page, paginationUtil.clampSize(size)));
        return ResponseEntity.ok(ApiResponse.success("Auth events retrieved.", PaginatedResponse.of(result)));
    }

    /* ───── Search summary ───── */

    @PostMapping("/search-summary/rebuild")
    @Operation(summary = "Trigger async rebuild of temple_search_summary table")
    public ResponseEntity<ApiResponse<Void>> rebuildSearchSummary() {
        adminService.rebuildSearchSummary();
        return ResponseEntity.accepted().body(ApiResponse.success("Search summary rebuild queued."));
    }

    @PostMapping("/search-summary/refresh/{templeId}")
    @Operation(summary = "Refresh search summary for a single temple (faster than full rebuild)")
    public ResponseEntity<ApiResponse<Void>> refreshTempleSearchSummary(@PathVariable Long templeId) {
        adminService.refreshTempleSearchSummary(templeId);
        return ResponseEntity.accepted().body(ApiResponse.success("Search summary refresh queued for temple " + templeId));
    }

    /* ───── Declaration admin actions ───── */

    @PatchMapping("/declarations/{id}/force-draft")
    @Operation(summary = "Force a SUBMITTED declaration back to DRAFT (SA only — for data correction)")
    public ResponseEntity<ApiResponse<Void>> forceDeclarationDraft(@PathVariable Long id) {
        declarationService.forceDraft(id);
        governanceAuditService.logAction(id, "DECLARATION", currentUserId(), "FORCE_DRAFT", "Admin forced declaration back to DRAFT");
        return ResponseEntity.ok(ApiResponse.success("Declaration forced back to DRAFT."));
    }

    @GetMapping("/pending-approvals")
    @Operation(summary = "Consolidated list of all statewide declarations pending DC/SA approval (SA only)")
    public ResponseEntity<ApiResponse<?>> getPendingApprovals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success("Pending approvals retrieved.",
                declarationService.listByDistrict(null, "SUBMITTED", null, page, size)));
    }

    @GetMapping("/declarations/physical-verification-pending")
    @Operation(summary = "List declarations flagged for physical verification > 30 days ago (SA only)")
    public ResponseEntity<ApiResponse<PaginatedResponse<?>>> getPhysicalVerificationPending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success("Physical verification pending list retrieved.",
                declarationService.getPhysicalVerificationPending(page, size)));
    }

    /* ───── Statewide dashboard ───── */

    @GetMapping("/dashboard/statewide")
    @Operation(summary = "Statewide aggregation dashboard for SUPER_ADMIN")
    public ResponseEntity<ApiResponse<StatewideDashboardResponse>> getStatewideDashboard() {
        return ResponseEntity.ok(ApiResponse.success("Statewide dashboard retrieved.", adminDashboardService.getStatewideDashboard()));
    }

    /* ───── Governance history ───── */

    @GetMapping("/governance-history")
    @Operation(summary = "Paginated governance action history across all entities (SA only)")
    public ResponseEntity<ApiResponse<PaginatedResponse<GovernanceHistoryResponse>>> listGovernanceHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var result = governanceAuditService
                .getAllHistory(PageRequest.of(page, paginationUtil.clampSize(size)));
        return ResponseEntity.ok(ApiResponse.success("Governance history retrieved.",
                PaginatedResponse.of(result.map(this::toGovernanceResponse))));
    }

    @GetMapping("/governance-history/{entityType}/{entityId}")
    @Operation(summary = "Governance action history for a specific entity (SA only)")
    public ResponseEntity<ApiResponse<List<GovernanceHistoryResponse>>> listGovernanceHistoryByEntity(
            @PathVariable String entityType,
            @PathVariable Long entityId) {
        List<GovernanceHistoryResponse> items = governanceAuditService
                .getHistoryForEntity(entityType, entityId)
                .stream().map(this::toGovernanceResponse).toList();
        return ResponseEntity.ok(ApiResponse.success("Governance history retrieved.", items));
    }

    /* ───── Notification rules ───── */

    @GetMapping("/notification-rules")
    @Operation(summary = "List all notification routing rules (SA only)")
    public ResponseEntity<ApiResponse<List<NotificationRuleResponse>>> listNotificationRules() {
        return ResponseEntity.ok(ApiResponse.success("Notification rules retrieved.",
                notificationRuleService.listActiveRules()));
    }

    @PutMapping("/notification-rules/{id}")
    @Operation(summary = "Update a notification rule (enable/disable, change priority) (SA only)")
    public ResponseEntity<ApiResponse<NotificationRuleResponse>> updateNotificationRule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateNotificationRuleRequest rq) {
        return ResponseEntity.ok(ApiResponse.success("Notification rule updated.",
                notificationRuleService.updateRule(id, rq)));
    }

    /* ───── Helpers ───── */

    private GovernanceHistoryResponse toGovernanceResponse(GovernanceActionHistory h) {
        String actorName = h.getDcUserId() != null
                ? userRepository.findById(h.getDcUserId()).map(u -> u.getFullName()).orElse("User #" + h.getDcUserId())
                : "System";
        return GovernanceHistoryResponse.builder()
                .id(h.getId())
                .entityId(h.getEntityId())
                .entityType(h.getEntityType())
                .workflowInstanceId(h.getWorkflowInstanceId())
                .workflowTransitionId(h.getWorkflowTransitionId())
                .actorUserId(h.getDcUserId())
                .actorName(actorName)
                .actorRole(h.getActorRole())
                .action(h.getAction())
                .comment(h.getComment())
                .timestamp(h.getTimestamp())
                .build();
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof ScopeHelper.Claims c ? c.userId() : 0L;
    }
}

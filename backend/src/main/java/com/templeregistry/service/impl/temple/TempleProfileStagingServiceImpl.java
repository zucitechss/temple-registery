package com.templeregistry.service.impl.temple;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.temple.CreateTempleProfileStagingRequest;
import com.templeregistry.dto.response.temple.TempleProfileStagingResponse;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleProfileStaging;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.geo.HobliRepository;
import com.templeregistry.repository.temple.TempleProfileStagingRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.OwnershipGuard;
import com.templeregistry.security.AccessGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.temple.TempleProfileStagingService;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.service.workflow.WorkflowEngine;
import com.templeregistry.service.workflow.WorkflowActionRequest;
import com.templeregistry.service.workflow.ActionContext;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowAction;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.util.PaginationUtil;
import com.templeregistry.service.workflow.VersionService;
import com.templeregistry.service.clarification.ClarificationEngine;
import com.templeregistry.service.workflow.WorkflowEngineAdaptor;
import com.templeregistry.service.governance.GovernanceStatusResolver;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TempleProfileStagingServiceImpl implements TempleProfileStagingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TempleProfileStagingServiceImpl.class);

    private final TempleProfileStagingRepository stagingRepository;
    private final TempleRepository templeRepository;
    private final TempleSearchSummaryService summaryService;
    private final OwnershipGuard ownershipGuard;
    private final AccessGuard accessGuard;
    private final PaginationUtil paginationUtil;
    private final WorkflowEngine workflowEngine;
    private final WorkflowEngineAdaptor workflowEngineAdaptor;
    private final VersionService versionService;
    private final ClarificationEngine clarificationEngine;
    private final GovernanceStatusResolver governanceStatusResolver;
    private final HobliRepository hobliRepository;

    @Override
    @PreAuthorize(RoleConstants.CAN_SUBMIT)
    @Transactional
    public TempleProfileStagingResponse createOrUpdateDraft(Long templeId, CreateTempleProfileStagingRequest request) {
        accessGuard.assertCanEdit();
        ownershipGuard.assertOwnsTemple(templeId);
        Temple temple = findTempleOrThrow(templeId);
        assertNotSuspended(temple);

        // EC-04: Block editing while a submission is under active DC review.
        // Check SUBMITTED, UNDER_REVIEW, and RESUBMITTED states — all are DC-side.
        Optional<TempleProfileStaging> pending = stagingRepository
                .findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                        templeId,
                        List.of(
                                com.templeregistry.entity.workflow.WorkflowStatus.SUBMITTED,
                                com.templeregistry.entity.workflow.WorkflowStatus.UNDER_REVIEW,
                                com.templeregistry.entity.workflow.WorkflowStatus.RESUBMITTED));
        if (pending.isPresent()) {
            if (temple.getVerificationStatus() == com.templeregistry.entity.temple.VerificationStatus.VERIFIED) {
                TempleProfileStaging stale = pending.get();
                WorkflowInstance staleInstance = workflowEngine.getState(
                        WorkflowEntityType.TEMPLE_PROFILE, stale.getId());
                workflowEngine.executeSystem(staleInstance.getId(), WorkflowAction.REJECT,
                        "Auto-resolved: DC verified the temple profile directly; " +
                        "this submission was not explicitly reviewed through the staging workflow.");
                log.info("Auto-resolved stale SUBMITTED staging [{}] for VERIFIED temple [{}]; " +
                         "TA can now create a new draft.", stale.getId(), templeId);
                // Fall through — TA is now unblocked to create/update a DRAFT
            } else {
                throw new IllegalStateException(
                        "A profile submission is already under DC review (status: " +
                        workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, pending.get().getId()).getStatus() +
                        "). Editing is locked until DC responds.");
            }
        }

        // Find or create/edit the staging record.
        // Priority:  DRAFT (new draft in progress)
        //         → UPDATED_AFTER_APPROVAL (edit already started on approved version)
        //         → APPROVED/RE_APPROVED (first edit since approval — transition in-place)
        //         → new DRAFT (no prior staging)
        TempleProfileStaging staging = stagingRepository
                .findFirstByTempleIdAndStatus(templeId, com.templeregistry.entity.workflow.WorkflowStatus.DRAFT)
                .or(() -> stagingRepository.findFirstByTempleIdAndStatus(
                        templeId, com.templeregistry.entity.workflow.WorkflowStatus.UPDATED_AFTER_APPROVAL))
                .orElseGet(() -> {
                    // Check if there is an approved version to edit in place
                    Optional<TempleProfileStaging> approvedOpt = stagingRepository
                            .findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                                    templeId,
                                    List.of(com.templeregistry.entity.workflow.WorkflowStatus.APPROVED,
                                            com.templeregistry.entity.workflow.WorkflowStatus.RE_APPROVED));
                    if (approvedOpt.isPresent()) {
                        // Edit-in-place: transition approved staging → UPDATED_AFTER_APPROVAL
                        // This mirrors Trust's edit-after-approval parity.
                        TempleProfileStaging approvedStaging = approvedOpt.get();
                        workflowEngineAdaptor.adaptEditApproved(
                                WorkflowEntityType.TEMPLE_PROFILE,
                                approvedStaging.getId(),
                                currentUserId(),
                                templeId);
                        log.info("Temple profile staging [{}] transitioned to UPDATED_AFTER_APPROVAL for edit (templeId={})",
                                approvedStaging.getId(), templeId);
                        return approvedStaging;
                    }
                    // No prior version: create a fresh DRAFT
                    int nextVersion = stagingRepository.findMaxVersionNumberByTempleId(templeId)
                            .map(v -> v + 1).orElse(1);
                    return TempleProfileStaging.builder()
                            .templeId(templeId)
                            .version(nextVersion)
                            .versionNumber(nextVersion)
                            .build();
                });

        applyFields(staging, request);
        TempleProfileStaging saved = stagingRepository.save(staging);
        
        // ── Workflow Engine: ensure instance exists on first save ──────────────
        // initiate() is now idempotent — it returns the existing instance if one already
        // exists, so no existence check is needed here. This eliminates the TOCTOU race
        // that the previous existsByEntityTypeAndEntityId() check introduced: two concurrent
        // requests could both pass the exists() check before either called initiate(),
        // causing the second to throw WorkflowException. Now both calls safely converge
        // on the same instance row via the unique index (entity_type, entity_id).
        workflowEngine.initiate(
            WorkflowEntityType.TEMPLE_PROFILE,
            saved.getId(),
            templeId,
            temple.getDistrictId(),
            currentUserId()
        );
        
        log.info("Temple profile staging draft saved: stagingId=[{}] templeId=[{}]", saved.getId(), templeId);
        return toResponse(saved);
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_SUBMIT)
    @Transactional
    public TempleProfileStagingResponse submitForReview(Long templeId) {
        accessGuard.assertCanEdit();
        ownershipGuard.assertOwnsTemple(templeId);
        Temple temple = findTempleOrThrow(templeId);
        assertNotSuspended(temple);

        // Find submittable staging: DRAFT (first submission) or UPDATED_AFTER_APPROVAL (resubmission after approval)
        TempleProfileStaging staging = stagingRepository
            .findFirstByTempleIdAndStatus(templeId, com.templeregistry.entity.workflow.WorkflowStatus.DRAFT)
            .or(() -> stagingRepository.findFirstByTempleIdAndStatus(
                    templeId, com.templeregistry.entity.workflow.WorkflowStatus.UPDATED_AFTER_APPROVAL))
            .orElseGet(() -> createDraftFromTempleSnapshot(temple));

        // Ensure a workflow instance exists (idempotent — handles legacy staging records
        // that were created before the workflow engine was introduced).
        workflowEngine.initiate(
            WorkflowEntityType.TEMPLE_PROFILE,
            staging.getId(),
            templeId,
            temple.getDistrictId(),
            currentUserId()
        );

        // Route through adaptSubmit — it automatically selects SUBMIT vs RESUBMIT:
        //   DRAFT                  → SUBMIT   → SUBMITTED
        //   UPDATED_AFTER_APPROVAL → RESUBMIT → RESUBMITTED
        // Pass null districtId for SUPER_ADMIN so the adaptor builds actorRole="TA",
        // matching the SUBMIT transition rule which requires "TA" (not "DC").
        workflowEngineAdaptor.adaptSubmit(
            WorkflowEntityType.TEMPLE_PROFILE,
            staging.getId(),
            templeId,
            isSuperAdmin() ? null : temple.getDistrictId(),
            currentUserId()
        );

        // Re-fetch instance to get the updated versionNumber for the snapshot.
        WorkflowInstance instance = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, staging.getId());

        // Sync legacy staging.status field (projection only — never authority).
        staging.setStatus(com.templeregistry.entity.temple.TempleProfileStagingStatus.PENDING_REVIEW);
        stagingRepository.save(staging);

        // Snapshot domain entity for versioning
        versionService.snapshot(WorkflowEntityType.TEMPLE_PROFILE, staging.getId(), instance.getVersionNumber(), staging, currentUserId(), null);

        log.info("Temple profile submitted for review (NOT promoted): stagingId=[{}] templeId=[{}]", staging.getId(), templeId);

        return toResponse(staging);
    }

    private TempleProfileStaging createDraftFromTempleSnapshot(Temple temple) {
        CreateTempleProfileStagingRequest prefill = CreateTempleProfileStagingRequest.builder()
                .phone(temple.getContactMobile())
                .email(temple.getContactEmail())
                .contactPersonName(temple.getContactName())
                .contactPersonDesignation(temple.getContactDesignation())
                .photoFilePath(temple.getPhotoUrl())
                .languagesOfWorship(temple.getLanguagesOfWorship())
                .landmark(temple.getLandmark())
                .historicalSignificance(temple.getHistoricalSignificance())
                .description(temple.getHistory())
                // Identity fields (V93)
                .aliasName(temple.getAliasName())
                .primaryDeity(temple.getPrimaryDeity())
                .grade(temple.getGrade() != null ? temple.getGrade().name() : null)
                .tradition(temple.getTradition() != null ? temple.getTradition().name() : null)
                .hobliId(temple.getHobliId())
                .talukId(temple.getTalukId())
                .addressLine1(temple.getStreet())
                .pinCode(temple.getPinCode())
                .latitude(temple.getLatitude() != null ? temple.getLatitude().doubleValue() : null)
                .longitude(temple.getLongitude() != null ? temple.getLongitude().doubleValue() : null)
                .yearEstablished(temple.getYearEstablished())
                .build();

        return stagingRepository.findById(createOrUpdateDraft(temple.getId(), prefill).getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "TempleProfileStaging",
                        temple.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public TempleProfileStagingResponse getActiveStagingOrNull(Long templeId) {
        ownershipGuard.assertOwnsTemple(templeId);
        return stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                templeId, List.of(
                        com.templeregistry.entity.workflow.WorkflowStatus.DRAFT,
                        com.templeregistry.entity.workflow.WorkflowStatus.SUBMITTED,
                        com.templeregistry.entity.workflow.WorkflowStatus.UPDATED_AFTER_APPROVAL,
                        com.templeregistry.entity.workflow.WorkflowStatus.RESUBMITTED))
                .map(this::toResponse).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public TempleProfileStagingResponse getById(Long id) {
        return toResponse(findStagingOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PaginatedResponse<TempleProfileStagingResponse> getHistory(Long templeId, int page, int size) {
        ownershipGuard.assertOwnsTemple(templeId);
        Page<TempleProfileStaging> result = stagingRepository.findAllByTempleIdOrderByVersionNumberDesc(
                templeId, PageRequest.of(page, paginationUtil.clampSize(size)));
        return PaginatedResponse.of(result.map(this::toResponse));
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void requestClarification(Long templeId, Long stagingId, String message, Long requestedByUserId) {
        TempleProfileStaging staging = findStagingOrThrow(stagingId);
        
        // [P6] Canonical: ClarificationEngine handles thread creation + WorkflowEngine transition
        WorkflowInstance instance = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, stagingId);

        clarificationEngine.requestClarification(
            instance.getId(),
            com.templeregistry.service.clarification.ClarificationRequest.builder()
                .message(message)
                .build(),
            requestedByUserId,
            null);

        log.info("Clarification requested for temple profile staging [{}] by userId={}", stagingId, requestedByUserId);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_SUBMIT)
    public void respondToClarification(Long templeId, Long threadId, String response, Long respondedByUserId) {
        accessGuard.assertCanEdit();
        // [P6] Canonical: ClarificationEngine handles response + WorkflowEngine transition
        clarificationEngine.respond(threadId,
            com.templeregistry.service.clarification.ClarificationResponse.builder()
                .message(response)
                .build(),
            respondedByUserId);

        log.info("Clarification response submitted for thread [{}] by userId={}", threadId, respondedByUserId);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.TEMPLE_AUTHORITY_ONLY)
    public void deleteDraftStaging(Long templeId, Long stagingId) {
        accessGuard.assertCanEdit();
        TempleProfileStaging staging = stagingRepository.findById(stagingId)
                .orElseThrow(() -> new EntityNotFoundException("TempleProfileStaging", stagingId));

        if (!templeId.equals(staging.getTempleId())) {
            throw new EntityNotFoundException("TempleProfileStaging", stagingId);
        }

        // Use WorkflowInstance as the canonical status source — the entity-level status field
        // is never updated after creation and must not be used for guard checks.
        WorkflowInstance wfInstance = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, stagingId);
        if (wfInstance.getStatus() != com.templeregistry.entity.workflow.WorkflowStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT staging records can be deleted. Current status: " + wfInstance.getStatus());
        }

        stagingRepository.delete(staging); // triggers @SQLDelete soft-delete
        log.info("Draft staging [{}] soft-deleted for temple [{}]", stagingId, templeId);
    }

    /* ── Helpers ─────────────────────────────────────────── */

    private Temple findTempleOrThrow(Long templeId) {
        return templeRepository.findById(templeId)
                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
    }

    private TempleProfileStaging findStagingOrThrow(Long stagingId) {
        return stagingRepository.findById(stagingId)
                .orElseThrow(() -> new EntityNotFoundException("TempleProfileStaging", stagingId));
    }

    private void assertNotSuspended(Temple temple) {
        if (temple.getStatus() == TempleStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Temple [" + temple.getId() + "] is currently SUSPENDED. "
                            + "Profile edits are disabled. Contact the District Collector's office.");
        }
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims c) return c.userId();
        return 0L;
    }

    private boolean isSuperAdmin() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims c) return RoleConstants.SUPER_ADMIN.equals(c.role());
        return false;
    }

    private ActionContext buildActionContext(Long districtId) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims claims) {
            String role = claims.districtId() != null ? "DC" : "TA";
            return ActionContext.builder()
                .actorId(claims.userId())
                .actorRole(role)
                .actorDistrictId(claims.districtId())
                .ownedTempleIds(claims.templeIds() != null ? claims.templeIds() : java.util.Set.of())
                .build();
        }
        // Fallback for system/test contexts
        return ActionContext.builder()
            .actorId(0L)
            .actorRole("SYSTEM")
            .build();
    }

    private void applyFields(TempleProfileStaging staging, CreateTempleProfileStagingRequest rq) {
        if (normalized(rq.getPhone()) != null)                    staging.setPhone(normalized(rq.getPhone()));
        if (normalized(rq.getEmail()) != null)                    staging.setEmail(normalized(rq.getEmail()));
        if (normalized(rq.getWebsite()) != null)                  staging.setWebsite(normalized(rq.getWebsite()));
        if (normalized(rq.getContactPersonName()) != null)        staging.setContactPersonName(normalized(rq.getContactPersonName()));
        if (normalized(rq.getContactPersonDesignation()) != null) staging.setContactPersonDesignation(normalized(rq.getContactPersonDesignation()));
        if (normalized(rq.getPhotoFilePath()) != null)            staging.setPhotoFilePath(normalized(rq.getPhotoFilePath()));
        // Plain text; AesEncryptionConverter transparently encrypts on JPA save (AES-256-GCM)
        if (normalized(rq.getBankAccountNumber()) != null)        staging.setBankAccountNumberEncrypted(normalized(rq.getBankAccountNumber()));
        if (normalized(rq.getBankName()) != null)                 staging.setBankName(normalized(rq.getBankName()));
        if (normalized(rq.getBankIfsc()) != null)                 staging.setBankIfsc(normalized(rq.getBankIfsc()).toUpperCase());
        if (normalized(rq.getLanguagesOfWorship()) != null)       staging.setLanguagesOfWorship(normalized(rq.getLanguagesOfWorship()));
        if (normalized(rq.getLinkedInstitutions()) != null)       staging.setLinkedInstitutions(normalized(rq.getLinkedInstitutions()));
        if (normalized(rq.getDescription()) != null)              staging.setDescription(normalized(rq.getDescription()));
        if (normalized(rq.getAnnualFestivals()) != null)          staging.setAnnualFestivals(normalized(rq.getAnnualFestivals()));
        if (normalized(rq.getLandmark()) != null)                 staging.setLandmark(normalized(rq.getLandmark()));
        if (normalized(rq.getHistoricalSignificance()) != null)   staging.setHistoricalSignificance(normalized(rq.getHistoricalSignificance()));
        // Identity fields (V93)
        if (normalized(rq.getAliasName()) != null)                staging.setAliasName(normalized(rq.getAliasName()));
        if (normalized(rq.getPrimaryDeity()) != null)             staging.setPrimaryDeity(normalized(rq.getPrimaryDeity()));
        if (normalized(rq.getGrade()) != null)                    staging.setGrade(normalized(rq.getGrade()));
        if (normalized(rq.getTradition()) != null)                staging.setTradition(normalized(rq.getTradition()));
        if (rq.getHobliId() != null)                              staging.setHobliId(rq.getHobliId());
        if (rq.getTalukId() != null)                              staging.setTalukId(rq.getTalukId());
        if (normalized(rq.getAddressLine1()) != null)             staging.setAddressLine1(normalized(rq.getAddressLine1()));
        if (normalized(rq.getPinCode()) != null)                  staging.setPinCode(normalized(rq.getPinCode()));
        if (rq.getLatitude() != null)                             staging.setLatitude(rq.getLatitude());
        if (rq.getLongitude() != null)                            staging.setLongitude(rq.getLongitude());
        // Location metadata (V97)
        if (normalized(rq.getPlaceId()) != null)          staging.setPlaceId(normalized(rq.getPlaceId()));
        if (normalized(rq.getFormattedAddress()) != null)  staging.setFormattedAddress(normalized(rq.getFormattedAddress()));
        if (rq.getYearEstablished() != null)                      staging.setYearEstablished(rq.getYearEstablished());
    }

    private String normalized(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private TempleProfileStagingResponse toResponse(TempleProfileStaging s) {
        WorkflowInstance instance = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, s.getId());

        // VAL-008: bank account — show last 4 digits only (never echo full number)
        String masked = null;
        if (s.getBankAccountNumberEncrypted() != null && s.getBankAccountNumberEncrypted().length() >= 4) {
            String raw = s.getBankAccountNumberEncrypted();
            masked = "****" + raw.substring(raw.length() - 4);
        }

        String photoUrl = null;
        if (s.getPhotoFilePath() != null && !s.getPhotoFilePath().isBlank()) {
            photoUrl = "/api/v1/temples/" + s.getTempleId() + "/profile-photo/serve";
        }

        return TempleProfileStagingResponse.builder()
                .id(s.getId())
                .workflowInstanceId(instance.getId())
                .templeId(s.getTempleId())
                .versionNumber(instance.getVersionNumber())
                .statusLabel(instance.getStatus().name())
                .phone(s.getPhone())
                .email(s.getEmail())
                .website(s.getWebsite())
                .contactPersonName(s.getContactPersonName())
                .contactPersonDesignation(s.getContactPersonDesignation())
                .photoUrl(photoUrl)
                .bankAccountMasked(masked)
                .bankName(s.getBankName())
                .bankIfsc(s.getBankIfsc())
                .languagesOfWorship(s.getLanguagesOfWorship())
                .linkedInstitutions(s.getLinkedInstitutions())
                .description(s.getDescription())
                .annualFestivals(s.getAnnualFestivals())
                .landmark(s.getLandmark())
                .historicalSignificance(s.getHistoricalSignificance())
                // Identity fields (V93)
                .aliasName(s.getAliasName())
                .primaryDeity(s.getPrimaryDeity())
                .grade(s.getGrade())
                .tradition(s.getTradition())
                .hobliId(s.getHobliId())
                .talukId(s.getTalukId() != null ? s.getTalukId()
                        : (s.getHobliId() != null ? hobliRepository.findTalukIdById(s.getHobliId()).orElse(null) : null))
                .addressLine1(s.getAddressLine1())
                .pinCode(s.getPinCode())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .yearEstablished(s.getYearEstablished())
                .reviewComment(s.getReviewComment())
                .submittedAt(instance.getSubmittedAt() != null ? LocalDateTime.ofInstant(instance.getSubmittedAt(), java.time.ZoneId.systemDefault()) : null)
                .reviewedAt(instance.getStatusUpdatedAt() != null ? LocalDateTime.ofInstant(instance.getStatusUpdatedAt(), java.time.ZoneId.systemDefault()) : null)
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .governanceStatus(governanceStatusResolver.resolveFromInstance(instance))
                .build();
    }
}

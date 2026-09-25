package com.templeregistry.service.impl.dc;

import com.templeregistry.dto.request.dc.ApproveProfileRequest;
import com.templeregistry.dto.request.dc.RejectProfileRequest;
import com.templeregistry.dto.response.dc.WorkflowActionResponse;
import com.templeregistry.entity.dc.TempleProfileCurrent;
import com.templeregistry.entity.dc.TempleProfileHistory;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleProfileStaging;
import com.templeregistry.entity.temple.TempleProfileStagingStatus;
import com.templeregistry.entity.temple.VerificationStatus;
import com.templeregistry.entity.workflow.WorkflowAction;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.entity.workflow.WorkflowStatus;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.dc.TempleProfileCurrentRepository;
import com.templeregistry.repository.dc.TempleProfileHistoryRepository;
import com.templeregistry.repository.geo.HobliRepository;
import com.templeregistry.repository.temple.TempleProfileStagingRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.audit.GovernanceAuditService;
import com.templeregistry.service.dc.TempleProfileWorkflowService;
import com.templeregistry.service.notification.NotificationHelper;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.service.workflow.ActionContextResolver;
import com.templeregistry.service.workflow.WorkflowActionRequest;
import com.templeregistry.service.workflow.WorkflowEngine;
import com.templeregistry.service.workflow.ActionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Executes DC profile workflow actions on temple_profile_staging.
 *
 * On APPROVE:
 *  1. Validates canonical WorkflowInstance.status is SUBMITTED / UNDER_REVIEW / RESUBMITTED
 *  2. Asserts district scope
 *  3. Archives existing temple_profile_current → temple_profile_history
 *  4. Writes new temple_profile_current row (UPSERT: delete + save)
 *  5. Sets legacy staging.status = APPROVED (projection only — never authority)
 *  6. Supersedes prior APPROVED version via WorkflowEngine.executeSystem(AUTO_SUPERSEDE)
 *  7. Advances WorkflowInstance via WorkflowEngine.execute(APPROVE or RE_APPROVE)
 *  8. Publishes NotificationEvent and fires audit log
 *
 * On REJECT:
 *  1. Validates canonical WorkflowInstance.status is SUBMITTED / UNDER_REVIEW / RESUBMITTED
 *  2. Asserts district scope
 *  3. Sets legacy staging.status = REJECTED (projection only)
 *  4. Advances WorkflowInstance via WorkflowEngine.execute(REJECT)
 *  5. Publishes NotificationEvent and fires audit log
 *
 * dc_e2e Section 4.4, 2.6, 2.8.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TempleProfileWorkflowServiceImpl implements TempleProfileWorkflowService {

    private final TempleProfileStagingRepository stagingRepository;
    private final TempleProfileCurrentRepository currentRepository;
    private final TempleProfileHistoryRepository historyRepository;
    private final TempleRepository templeRepository;
    private final JurisdictionGuard jurisdictionGuard;
    private final NotificationHelper notificationHelper;
    private final TempleSearchSummaryService summaryService;
    private final AuditService auditService;
    private final GovernanceAuditService governanceAuditService;
    private final WorkflowEngine workflowEngine;
    private final ActionContextResolver actionContextResolver;
    private final HobliRepository hobliRepository;

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_APPROVE)
    public WorkflowActionResponse approveProfile(Long stagingId, ApproveProfileRequest request,
                                                  ScopeHelper.Claims claims) {
        TempleProfileStaging staging = loadStaging(stagingId);

        // Canonical status from WorkflowEngine — NOT the legacy staging.status field.
        WorkflowInstance workflowInstance = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, stagingId);
        WorkflowStatus currentStatus = workflowInstance.getStatus();
        assertReviewable(currentStatus);

        Temple temple = loadTempleWithGeo(staging.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // UPSERT pattern — avoids delete+insert on temple_profile_current (unique on temple_id).
        // JPA batches INSERT before DELETE within the same flush cycle, violating the unique
        // constraint. Instead: load the existing row (if any), archive it to history, then
        // update it in-place. A brand-new temple gets a plain INSERT.
        TempleProfileCurrent current = currentRepository.findByTempleId(staging.getTempleId())
                .orElseGet(() -> TempleProfileCurrent.builder()
                        .templeId(staging.getTempleId())
                        .build());

        if (current.getId() != null) {
            // Archive a snapshot of the current approved data before overwriting it.
            TempleProfileHistory history = TempleProfileHistory.builder()
                    .templeId(current.getTempleId())
                    .version(staging.getVersionNumber() - 1)
                    .phone(current.getPhone())
                    .email(current.getEmail())
                    .website(current.getWebsite())
                    .contactPersonName(current.getContactPersonName())
                    .contactPersonDesignation(current.getContactPersonDesignation())
                    .photoFilePath(current.getPhotoFilePath())
                    .bankName(current.getBankName())
                    .bankAccountNumberEncrypted(current.getBankAccountNumberEncrypted())
                    .bankIfsc(current.getBankIfsc())
                    .languagesOfWorship(current.getLanguagesOfWorship())
                    .linkedInstitutions(current.getLinkedInstitutions())
                    .description(current.getDescription())
                    .annualFestivals(current.getAnnualFestivals())
                    .landmark(current.getLandmark())
                    .historicalSignificance(current.getHistoricalSignificance())
                    .publishedAt(current.getPublishedAt())
                    .build();
            historyRepository.save(history);
        }

        // Overwrite (or initialise) the current record with approved staging content.
        current.setPhone(staging.getPhone());
        current.setEmail(staging.getEmail());
        current.setWebsite(staging.getWebsite());
        current.setContactPersonName(staging.getContactPersonName());
        current.setContactPersonDesignation(staging.getContactPersonDesignation());
        current.setPhotoFilePath(staging.getPhotoFilePath());
        current.setBankName(staging.getBankName());
        current.setBankAccountNumberEncrypted(staging.getBankAccountNumberEncrypted());
        current.setBankIfsc(staging.getBankIfsc());
        current.setLanguagesOfWorship(staging.getLanguagesOfWorship());
        current.setLinkedInstitutions(staging.getLinkedInstitutions());
        current.setDescription(staging.getDescription());
        current.setAnnualFestivals(staging.getAnnualFestivals());
        current.setLandmark(staging.getLandmark());
        current.setHistoricalSignificance(staging.getHistoricalSignificance());
        current.setPublishedAt(LocalDateTime.now());
        current.setPublishedBy(claims.userId());
        currentRepository.save(current);

        // Promote staging fields to the Temple entity so the TA's own profile view
        // reflects the approved data immediately (Temple entity is the TA-facing read model).
        promoteToTemple(temple, staging);
        temple.setVerificationStatus(VerificationStatus.VERIFIED);
        temple.setDcRejectionReason(null);
        templeRepository.save(temple);

        // Update legacy staging status for backward compatibility.
        // Null out reviewComment so approved staging rows never leak a prior rejection reason.
        staging.setStatus(TempleProfileStagingStatus.APPROVED);
        staging.setReviewComment(null);
        staging.setReviewedAt(LocalDateTime.now());
        staging.setReviewedBy(claims.userId());
        stagingRepository.save(staging);

        // Supersede the previous APPROVED version (if any) via canonical WorkflowEngine.
        // AUTO_SUPERSEDE is a SYSTEM action — no actor context needed.
        stagingRepository.findFirstByTempleIdAndStatus(staging.getTempleId(), TempleProfileStagingStatus.APPROVED)
                .ifPresent(prev -> {
                    if (!prev.getId().equals(staging.getId())) {
                        WorkflowInstance prevInstance = workflowEngine.getState(
                                WorkflowEntityType.TEMPLE_PROFILE, prev.getId());
                        workflowEngine.executeSystem(prevInstance.getId(),
                                WorkflowAction.AUTO_SUPERSEDE,
                                "Superseded by approved stagingId=" + staging.getId());
                    }
                });

        // Canonical: transition WorkflowInstance to APPROVED / RE_APPROVED
        WorkflowAction approveAction = (currentStatus == WorkflowStatus.RESUBMITTED)
                ? WorkflowAction.RE_APPROVE : WorkflowAction.APPROVE;
        ActionContext context = actionContextResolver.resolve(claims);
        workflowEngine.execute(
                workflowInstance.getId(),
                WorkflowActionRequest.builder()
                        .action(approveAction)
                        .expectedVersion(workflowInstance.getLockVersion())
                        .idempotencyKey(UUID.randomUUID().toString())
                        .comment(request.getRemarks())
                        .build(),
                context);

        // notificationHelper.notifyTempleApproved — removed: WorkflowEngine outbox already fires TA notification.
        auditService.logDataEvent(claims.userId(), claims.role(), "APPROVE", "TEMPLE_PROFILE", staging.getTempleId(),
                "Approved version " + staging.getVersionNumber());
        
        governanceAuditService.logAction(staging.getTempleId(), "TEMPLE_PROFILE", claims.userId(), "APPROVE", 
                "Approved version " + staging.getVersionNumber() + ". Remarks: " + request.getRemarks());

        summaryService.scheduleRefresh(staging.getTempleId());

        return WorkflowActionResponse.builder()
                .declarationId(staging.getId())
                .newStatus(TempleProfileStagingStatus.APPROVED.name())
                .message("Temple profile version " + staging.getVersionNumber() + " approved and published.")
                .build();
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_APPROVE)
    public WorkflowActionResponse rejectProfile(Long stagingId, RejectProfileRequest request,
                                                   ScopeHelper.Claims claims) {
        TempleProfileStaging staging = loadStaging(stagingId);

        // Canonical status from WorkflowEngine — NOT the legacy staging.status field.
        WorkflowInstance workflowInstance = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, stagingId);
        WorkflowStatus currentStatus = workflowInstance.getStatus();
        assertReviewable(currentStatus);

        Temple temple = loadTempleWithGeo(staging.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // Update legacy staging status for backward compatibility
        staging.setStatus(TempleProfileStagingStatus.REJECTED);
        staging.setReviewedAt(LocalDateTime.now());
        staging.setReviewedBy(claims.userId());
        staging.setReviewComment(request.getReason());
        stagingRepository.save(staging);

        // Canonical: transition WorkflowInstance to REJECTED
        ActionContext context = actionContextResolver.resolve(claims);
        workflowEngine.execute(
                workflowInstance.getId(),
                WorkflowActionRequest.builder()
                        .action(WorkflowAction.REJECT)
                        .expectedVersion(workflowInstance.getLockVersion())
                        .idempotencyKey(UUID.randomUUID().toString())
                        .comment(request.getReason())
                        .build(),
                context);

        // notificationHelper.notifyTempleRejected — removed: WorkflowEngine outbox already fires TA notification.
        auditService.logDataEvent(claims.userId(), claims.role(), "REJECT", "TEMPLE_PROFILE", staging.getTempleId(),
                "Rejected version " + staging.getVersionNumber() + ": " + request.getReason());
        
        governanceAuditService.logAction(staging.getTempleId(), "TEMPLE_PROFILE", claims.userId(), "REJECT", 
                "Rejected version " + staging.getVersionNumber() + ". Remarks: " + request.getReason());

        summaryService.scheduleRefresh(staging.getTempleId());

        return WorkflowActionResponse.builder()
                .declarationId(staging.getId())
                .newStatus(TempleProfileStagingStatus.REJECTED.name())
                .message("Temple profile version " + staging.getVersionNumber() + " rejected.")
                .build();
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private TempleProfileStaging loadStaging(Long id) {
        return stagingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("TempleProfileStaging", id));
    }

    /**
     * Validates that the staging's canonical WorkflowInstance status is in a DC-reviewable state.
     * Replaces the legacy assertPendingReview() which used the stale staging.status field.
     */
    private void assertReviewable(WorkflowStatus currentStatus) {
        if (currentStatus != WorkflowStatus.SUBMITTED
                && currentStatus != WorkflowStatus.UNDER_REVIEW
                && currentStatus != WorkflowStatus.RESUBMITTED) {
            throw new IllegalStateException(
                    "Temple profile staging is not in a DC-reviewable state. Current canonical status: "
                            + currentStatus + ". Expected: SUBMITTED, UNDER_REVIEW, or RESUBMITTED.");
        }
    }

    private Temple loadTempleWithGeo(Long templeId) {
        return templeRepository.findWithGeoById(templeId)
                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
    }

    /**
     * Promotes non-null staging fields to the Temple entity so the TA's profile view
     * reflects approved content immediately (Temple is the TA-facing read model).
     * Mirrors TempleProfileStagingServiceImpl#promoteToTemple.
     */
    private void promoteToTemple(Temple temple, TempleProfileStaging staging) {
        if (staging.getContactPersonName() != null)        temple.setContactName(staging.getContactPersonName());
        if (staging.getContactPersonDesignation() != null) temple.setContactDesignation(staging.getContactPersonDesignation());
        if (staging.getLanguagesOfWorship() != null)       temple.setLanguagesOfWorship(staging.getLanguagesOfWorship());
        if (staging.getPhotoFilePath() != null)            temple.setPhotoUrl(staging.getPhotoFilePath());
        if (staging.getPhone() != null)                    temple.setContactMobile(staging.getPhone());
        if (staging.getEmail() != null)                    temple.setContactEmail(staging.getEmail());
        if (staging.getWebsite() != null)                  temple.setWebsite(staging.getWebsite());
        if (staging.getDescription() != null)              temple.setHistory(staging.getDescription());
        if (staging.getAnnualFestivals() != null)          temple.setAnnualFestivals(staging.getAnnualFestivals());
        if (staging.getLandmark() != null)                 temple.setLandmark(staging.getLandmark());
        if (staging.getHistoricalSignificance() != null)   temple.setHistoricalSignificance(staging.getHistoricalSignificance());
        // Identity fields (V93)
        if (staging.getAliasName() != null)                temple.setAliasName(staging.getAliasName());
        if (staging.getPrimaryDeity() != null)             temple.setPrimaryDeity(staging.getPrimaryDeity());
        if (staging.getGrade() != null) {
            try { temple.setGrade(com.templeregistry.entity.temple.TempleGrade.valueOf(staging.getGrade())); } catch (IllegalArgumentException ignored) {}
        }
        if (staging.getTradition() != null) {
            try { temple.setTradition(com.templeregistry.entity.temple.ReligiousTradition.valueOf(staging.getTradition())); } catch (IllegalArgumentException ignored) {}
        }
        if (staging.getHobliId() != null) temple.setHobliId(staging.getHobliId());
        // talukId (V117): prefer the staging row's own explicit talukId — a TA may have
        // picked District+Taluk with no Hobli yet available for their location, so it
        // cannot always be derived from hobliId. Fall back to hobli-derivation only when
        // no explicit talukId was submitted (staging rows from before V117, or a
        // hobli-only submission). A lookup miss on the fallback path leaves the existing
        // talukId alone rather than nulling out otherwise-good data.
        if (staging.getTalukId() != null) {
            temple.setTalukId(staging.getTalukId());
        } else if (staging.getHobliId() != null) {
            hobliRepository.findTalukIdById(staging.getHobliId()).ifPresent(temple::setTalukId);
        }
        if (staging.getAddressLine1() != null)             temple.setStreet(staging.getAddressLine1());
        if (staging.getPinCode() != null)                  temple.setPinCode(staging.getPinCode());
        if (staging.getLatitude() != null)                 temple.setLatitude(java.math.BigDecimal.valueOf(staging.getLatitude()));
        if (staging.getLongitude() != null)                temple.setLongitude(java.math.BigDecimal.valueOf(staging.getLongitude()));
        if (staging.getYearEstablished() != null)          temple.setYearEstablished(staging.getYearEstablished());
        if (staging.getBankName() != null)                 temple.setBankName(staging.getBankName());
        if (staging.getBankIfsc() != null)                 temple.setBankIfsc(staging.getBankIfsc());
        if (staging.getLinkedInstitutions() != null)       temple.setLinkedInstitutions(staging.getLinkedInstitutions());
        // Location metadata (V97)
        if (staging.getPlaceId() != null)          temple.setPlaceId(staging.getPlaceId());
        if (staging.getFormattedAddress() != null)  temple.setFormattedAddress(staging.getFormattedAddress());
    }
}

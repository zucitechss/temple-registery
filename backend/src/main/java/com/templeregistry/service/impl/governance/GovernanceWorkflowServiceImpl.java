package com.templeregistry.service.impl.governance;

import com.templeregistry.dto.request.dc.DcClarifyRequest;
import com.templeregistry.dto.request.dc.WorkflowApproveRequest;
import com.templeregistry.dto.request.dc.WorkflowRejectRequest;
import com.templeregistry.dto.request.governance.OrderPhysicalVerificationRequest;
import com.templeregistry.dto.request.governance.RejectRequest;
import com.templeregistry.dto.request.governance.SendBackRequest;
import com.templeregistry.dto.request.governance.UpdatePhysicalVerificationRequest;
import com.templeregistry.dto.response.dc.WorkflowActionResponse;
import com.templeregistry.dto.response.governance.PhysicalVerificationHistoryResponse;
import com.templeregistry.entity.declaration.AssetDeclaration;
import com.templeregistry.entity.declaration.ClarificationDirection;
import com.templeregistry.entity.declaration.DeclarationStatus;
import com.templeregistry.entity.governance.PhysicalVerificationHistory;
import com.templeregistry.entity.governance.PhysicalVerificationStatus;
import com.templeregistry.entity.governance.SubmissionStatus;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.trust.Trust;
import com.templeregistry.entity.workflow.WorkflowAction;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowStatus;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.IllegalStatusTransitionException;
import com.templeregistry.repository.declaration.DeclarationRepository;
import com.templeregistry.repository.governance.PhysicalVerificationHistoryRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.trust.TrustRepository;
import com.templeregistry.repository.workflow.WorkflowInstanceRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.OwnershipGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.audit.AuditActionType;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.audit.DeclarationAuditLogService;
import com.templeregistry.service.audit.GovernanceAuditService;
import com.templeregistry.service.declaration.AcknowledgementService;
import com.templeregistry.service.governance.GovernanceWorkflowService;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.service.workflow.ActionContext;
import com.templeregistry.service.workflow.WorkflowActionRequest;
import com.templeregistry.service.workflow.WorkflowEngine;
import com.templeregistry.service.workflow.WorkflowEngineAdaptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.templeregistry.entity.declaration.DeclarationClarification;
import com.templeregistry.repository.auth.UserRepository;
import com.templeregistry.service.dc.NotificationEventPublisher;
import com.templeregistry.repository.declaration.DeclarationClarificationRepository;
import com.templeregistry.service.workflow.VersionService;
import com.templeregistry.service.clarification.ClarificationEngine;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.service.document.FileStorageService;
import com.templeregistry.util.pdf.AcknowledgementTemplate;
import java.io.ByteArrayOutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.entity.trust.TrustType;

@Service
@RequiredArgsConstructor
@Slf4j
public class GovernanceWorkflowServiceImpl implements GovernanceWorkflowService {

    private final TrustRepository trustRepository;
    private final DeclarationRepository declarationRepository;
    private final TempleRepository templeRepository;
    private final PhysicalVerificationHistoryRepository physicalVerificationHistoryRepository;
    private final GovernanceAuditService governanceAuditService;
    private final AuditService auditService;
    private final OwnershipGuard ownershipGuard;
    private final JurisdictionGuard jurisdictionGuard;
    private final AcknowledgementService acknowledgementService;
    private final TempleSearchSummaryService summaryService;
    private final WorkflowEngine workflowEngine;
    private final WorkflowEngineAdaptor workflowEngineAdaptor;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final com.templeregistry.service.notification.NotificationRecipientResolver recipientResolver;
    private final UserRepository userRepository;
    private final NotificationEventPublisher notificationPublisher;
    private final ObjectMapper objectMapper;
    private final DeclarationClarificationRepository clarificationRepository;
    private final VersionService versionService;
    private final ClarificationEngine clarificationEngine;
    private final FileStorageService fileStorageService;

    // =========================================================================
    // TRUST — Submit / Approve / Send Back / Reject
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_SUBMIT)
    public void submitTrust(Long trustId) {
        Trust trust = loadTrust(trustId);
        ownershipGuard.assertOwnsTemple(trust.getTempleId());
        WorkflowStatus statusBeforeSubmit = workflowEngineAdaptor.currentStatus(WorkflowEntityType.TRUST, trustId);

        // Canonical: WorkflowEngine validates and transitions
        boolean transitioned = workflowEngineAdaptor.adaptSubmit(
            WorkflowEntityType.TRUST, trustId,
            trust.getTempleId(), districtIdForTrust(trust), currentUserId());

        if (!transitioned) {
            throw new IllegalStatusTransitionException(
                "Trust [" + trustId + "] cannot be submitted from status [" + statusBeforeSubmit + "].");
        }

        // [P2] Snapshot the domain entity only if a new transition occurred
        if (transitioned) {
            versionService.snapshot(WorkflowEntityType.TRUST, trustId, 1, trust, currentUserId(), null);
        }

        summaryService.scheduleRefresh(trust.getTempleId());

        log.info("Trust [{}] submitted by userId={}", trustId, currentUserId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void approveTrust(Long trustId) {
        Trust trust = loadTrust(trustId);
        assertDistrictScopeForTrust(trust);

        workflowEngineAdaptor.adaptApprove(
            WorkflowEntityType.TRUST, trustId, districtIdForTrust(trust), currentUserId());

        // [P2] Snapshot on approval
        versionService.snapshot(WorkflowEntityType.TRUST, trustId, 1, trust, currentUserId(), null);

        // Store approved data snapshot so it can be restored if a subsequent edit is rejected.
        // Sensitive fields (PAN, bank account number) are deliberately excluded.
        try {
            java.util.Map<String, Object> approvedSnapshot = new java.util.LinkedHashMap<>();
            approvedSnapshot.put("trustName", trust.getTrustName());
            approvedSnapshot.put("trustType", trust.getTrustType() != null ? trust.getTrustType().name() : null);
            approvedSnapshot.put("trustRegistrationNumber", trust.getTrustRegistrationNumber());
            approvedSnapshot.put("registeringAuthority", trust.getRegisteringAuthority());
            approvedSnapshot.put("dateOfRegistration",
                trust.getDateOfRegistration() != null ? trust.getDateOfRegistration().toString() : null);
            approvedSnapshot.put("bankNameAndBranch", trust.getBankNameAndBranch());
            approvedSnapshot.put("annualIncome", trust.getAnnualIncome());
            trust.setApprovedData(objectMapper.writeValueAsString(approvedSnapshot));
            trustRepository.save(trust);
        } catch (Exception ex) {
            log.warn("Non-fatal: failed to store approved_data snapshot for trust [{}]: {}", trustId, ex.getMessage());
        }

        summaryService.scheduleRefresh(trust.getTempleId());

        log.info("Trust [{}] APPROVED by userId={}", trustId, currentUserId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void sendBackTrust(Long trustId, SendBackRequest request) {
        Trust trust = loadTrust(trustId);
        assertDistrictScopeForTrust(trust);

        workflowEngineAdaptor.adaptSendBack(
            WorkflowEntityType.TRUST, trustId, districtIdForTrust(trust),
            currentUserId(), request.getReason());

        trust.setSendBackReason(request.getReason());
        trustRepository.save(trust);
        summaryService.scheduleRefresh(trust.getTempleId());

        log.info("Trust [{}] SENT BACK by userId={}", trustId, currentUserId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void rejectTrust(Long trustId, RejectRequest request) {
        Trust trust = loadTrust(trustId);
        assertDistrictScopeForTrust(trust);

        // If this trust was previously approved (approvedData is non-null), this is a rejection
        // of an edit — not a first-time submission rejection. Restore the last approved values
        // so the displayed data reverts to the approved state rather than showing
        // the rejected (edited) values.
        if (trust.getApprovedData() != null) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> snapshot =
                    objectMapper.readValue(trust.getApprovedData(), java.util.Map.class);
                if (snapshot.get("trustName") != null)
                    trust.setTrustName((String) snapshot.get("trustName"));
                if (snapshot.get("trustType") != null)
                    trust.setTrustType(TrustType.valueOf((String) snapshot.get("trustType")));
                if (snapshot.get("trustRegistrationNumber") != null)
                    trust.setTrustRegistrationNumber((String) snapshot.get("trustRegistrationNumber"));
                if (snapshot.get("registeringAuthority") != null)
                    trust.setRegisteringAuthority((String) snapshot.get("registeringAuthority"));
                if (snapshot.get("dateOfRegistration") != null)
                    trust.setDateOfRegistration(java.time.LocalDate.parse((String) snapshot.get("dateOfRegistration")));
                if (snapshot.get("bankNameAndBranch") != null)
                    trust.setBankNameAndBranch((String) snapshot.get("bankNameAndBranch"));
                trust.setAnnualIncome(snapshot.get("annualIncome") != null
                    ? new java.math.BigDecimal(snapshot.get("annualIncome").toString()) : null);
                trustRepository.save(trust);
                log.info("Trust [{}] approved data restored after edit-rejection by userId={}", trustId, currentUserId());
            } catch (Exception ex) {
                log.error("Failed to restore approved_data for trust [{}] on rejection: {}", trustId, ex.getMessage());
                throw new RuntimeException("Failed to restore approved trust data — rejection aborted to prevent data loss", ex);
            }

            // Edit rejection: transition back to RE_APPROVED (non-terminal).
            // The trust data has been restored; the edit is discarded.
            // adaptRejectEdit handles RESUBMITTED and UNDER_REVIEW states.
            workflowEngineAdaptor.adaptRejectEdit(
                WorkflowEntityType.TRUST, trustId, districtIdForTrust(trust),
                currentUserId(), request.getReason());

            summaryService.scheduleRefresh(trust.getTempleId());

            log.info("Trust [{}] edit REJECTED (reverted to RE_APPROVED) by userId={}", trustId, currentUserId());
            return;
        }

        // First-time rejection (trust was never approved): terminal, TA must create new trust.
        workflowEngineAdaptor.adaptReject(
            WorkflowEntityType.TRUST, trustId, districtIdForTrust(trust),
            currentUserId(), request.getReason());
        summaryService.scheduleRefresh(trust.getTempleId());

        log.info("Trust [{}] REJECTED (terminal) by userId={}", trustId, currentUserId());
    }

    // =========================================================================
    // DECLARATION — Submit / Approve / Send Back / Reject
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_SUBMIT)
    public void submitDeclaration(Long declarationId) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        ownershipGuard.assertOwnsTemple(declaration.getTempleId());

        // Canonical: WorkflowEngine validates and transitions
        boolean transitioned = workflowEngineAdaptor.adaptSubmit(
            WorkflowEntityType.DECLARATION, declarationId,
            declaration.getTempleId(), declaration.getDistrictId(), currentUserId());

        // [P2] Snapshot the domain entity only if a new transition occurred
        if (transitioned) {
            versionService.snapshot(WorkflowEntityType.DECLARATION, declarationId, 1, declaration, currentUserId(), null);
        }

        // Keep legacy status in sync — CRITICAL: DC listing queries filter by entity.status != 'DRAFT'
        // so we must set status to SUBMITTED here or DC will not see it.
        if (transitioned && (declaration.getStatus() == DeclarationStatus.DRAFT
                             || declaration.getStatus() == DeclarationStatus.REJECTED)) {
            declaration.setStatus(DeclarationStatus.SUBMITTED);
        }
        declaration.setSubmittedBy(currentUserId());
        declaration.setSubmittedAt(LocalDateTime.now());
        declarationRepository.save(declaration);

        log.info("Declaration [{}] submitted by userId={}", declarationId, currentUserId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public WorkflowActionResponse approveDeclaration(Long declarationId, WorkflowApproveRequest request,
                                                      ScopeHelper.Claims claims,
                                                      String idempotencyKey) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // BLOCK: DC must NOT approve if physical verification has failed
        // [P4] This is now ALSO enforced by SiteVisitBlocksApprovalPolicy in the engine.
        if (PhysicalVerificationStatus.VERIFICATION_FAILED.equals(declaration.getPhysicalVerificationStatus())) {
            throw new IllegalStatusTransitionException(
                    "Cannot approve declaration [" + declarationId + "]: physical verification has FAILED.");
        }

        // Preserve response contract on client retries: once approved, return the original
        // acknowledgement instead of generating a new one.
        if (DeclarationStatus.APPROVED.equals(declaration.getStatus())
            && StringUtils.hasText(declaration.getAcknowledgementNumber())) {
            String existingAck = declaration.getAcknowledgementNumber();
            return WorkflowActionResponse.builder()
                .declarationId(declarationId)
                .newStatus(DeclarationStatus.APPROVED.name())
                .acknowledgementNumber(existingAck)
                .message("Declaration approved successfully. Acknowledgement: " + existingAck)
                .build();
        }

        // [P3] Canonical: WorkflowEngine records transition + publishes event
        // IMPORTANT: adaptApprove() must happen BEFORE we mutate the entity status
        workflowEngineAdaptor.adaptApprove(
            WorkflowEntityType.DECLARATION,
            declarationId,
            claims.districtId(),
            claims.userId(),
            effectiveIdempotencyKey(idempotencyKey));

        // Generate acknowledgement number
        String ackNumber = acknowledgementService.generate(declaration.getDistrictId(), declaration.getFinancialYear());
        String acknowledgementPath = generateAcknowledgementDocument(declaration, ackNumber);
        declaration.setAcknowledgementNumber(ackNumber);
        declaration.setAcknowledgementDocFilePath(acknowledgementPath);
        declaration.setAcknowledgedAt(LocalDateTime.now());
        declaration.setStatus(DeclarationStatus.APPROVED);
        declaration.setReviewedAt(LocalDateTime.now());
        declaration.setReviewedBy(claims.userId());
        if (request != null && request.getRemarks() != null) {
            declaration.setReviewComment(request.getRemarks());
        }
        declarationRepository.save(declaration);

        // [V-H1] Guard: verify entity status matches WorkflowInstance status after dual-write
        assertEntityStatusConsistency(WorkflowEntityType.DECLARATION, declarationId, DeclarationStatus.APPROVED.name());

        // [P2] Snapshot on approval
        versionService.snapshot(WorkflowEntityType.DECLARATION, declarationId, 1, declaration, claims.userId(), null);

        summaryService.scheduleRefresh(declaration.getTempleId());

        return WorkflowActionResponse.builder()
                .declarationId(declarationId)
                .newStatus(DeclarationStatus.APPROVED.name())
                .acknowledgementNumber(ackNumber)
                .message("Declaration approved successfully. Acknowledgement: " + ackNumber)
                .build();
    }

    public WorkflowActionResponse approveDeclaration(Long declarationId, WorkflowApproveRequest request,
                                                     ScopeHelper.Claims claims) {
        return approveDeclaration(declarationId, request, claims, null);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void sendBackDeclaration(Long declarationId, SendBackRequest request) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        jurisdictionGuard.assertSameDistrict(declaration.getDistrictId());

        workflowEngineAdaptor.adaptSendBack(
            WorkflowEntityType.DECLARATION, declarationId, declaration.getDistrictId(),
            currentUserId(), request.getReason());

        declaration.setStatus(DeclarationStatus.CLARIFICATION_REQUIRED);
        declaration.setSendBackReason(request.getReason());
        declaration.setClarificationRound(declaration.getClarificationRound() + 1);
        declarationRepository.save(declaration);

        summaryService.scheduleRefresh(declaration.getTempleId());

        // [V-H1] Guard: verify entity status matches WorkflowInstance status after dual-write
        assertEntityStatusConsistency(WorkflowEntityType.DECLARATION, declarationId, DeclarationStatus.CLARIFICATION_REQUIRED.name());

        log.info("Declaration [{}] SENT_BACK by userId={}", declarationId, currentUserId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public WorkflowActionResponse rejectDeclaration(Long declarationId, WorkflowRejectRequest request,
                                                     ScopeHelper.Claims claims,
                                                     String idempotencyKey) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        workflowEngineAdaptor.adaptReject(
            WorkflowEntityType.DECLARATION,
            declarationId,
            claims.districtId(),
            claims.userId(),
            request.getRemarks(),
            effectiveIdempotencyKey(idempotencyKey));

        declaration.setStatus(DeclarationStatus.REJECTED);
        declaration.setReviewedAt(LocalDateTime.now());
        declaration.setReviewedBy(claims.userId());
        declaration.setReviewComment(request.getRemarks());
        declarationRepository.save(declaration);

        // [V-H1] Guard: verify entity status matches WorkflowInstance status after dual-write
        assertEntityStatusConsistency(WorkflowEntityType.DECLARATION, declarationId, DeclarationStatus.REJECTED.name());

        // [P2] Snapshot on rejection
        versionService.snapshot(WorkflowEntityType.DECLARATION, declarationId, 1, declaration, claims.userId(), null);

        summaryService.scheduleRefresh(declaration.getTempleId());

        // [P3] Manual notificationHelper call removed — NotificationRouter handles GovernanceDomainEvent.

        log.info("Declaration [{}] REJECTED by userId={}", declarationId, claims.userId());
        return WorkflowActionResponse.builder()
                .declarationId(declarationId)
                .newStatus(DeclarationStatus.REJECTED.name())
                .acknowledgementNumber(null)
                .message("Declaration rejected.")
                .build();
    }

    public WorkflowActionResponse rejectDeclaration(Long declarationId, WorkflowRejectRequest request,
                                                    ScopeHelper.Claims claims) {
        return rejectDeclaration(declarationId, request, claims, null);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public WorkflowActionResponse requestClarification(Long declarationId, DcClarifyRequest request,
                                                        ScopeHelper.Claims claims,
                                                        String idempotencyKey) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // BLOCK: Limit rounds
        if (declaration.getClarificationRound() >= 3) {
            throw new com.templeregistry.exception.ClarificationLimitExceededException(
                "Cannot request more clarifications. Maximum round [3] reached.");
        }

        // [P6] Canonical: ClarificationEngine handles thread creation + WorkflowEngine transition
        WorkflowInstance instance = workflowInstanceRepository.findByEntityTypeAndEntityId(WorkflowEntityType.DECLARATION, declarationId)
            .orElseThrow(() -> new EntityNotFoundException("WorkflowInstance", declarationId));

        clarificationEngine.requestClarification(
            instance.getId(),
            com.templeregistry.service.clarification.ClarificationRequest.builder()
                .message(request.getMessage())
                .sectionName(request.getSectionName())
                .build(),
            claims.userId(),
            effectiveIdempotencyKey(idempotencyKey));

        declaration.setStatus(DeclarationStatus.CLARIFICATION_REQUIRED);
        declaration.setClarificationRound(declaration.getClarificationRound() + 1);
        declarationRepository.save(declaration);

        // [P9] Legacy record creation for parity (TO BE REMOVED in Phase B)
        clarificationRepository.save(DeclarationClarification.builder()
                .declarationId(declarationId)
                .direction(ClarificationDirection.DC_TO_TEMPLE)
                .message(request.getMessage())
                .sectionName(request.getSectionName())
                .authorId(claims.userId())
                .build());

        summaryService.scheduleRefresh(declaration.getTempleId());

        // [P3] Manual notificationHelper removed — event outbox takes over.

        // Escalation logic
        if (declaration.getClarificationRound() >= 2) {
            List<com.templeregistry.entity.auth.User> superAdmins = userRepository.findAllByRole(com.templeregistry.entity.auth.UserRole.SUPER_ADMIN);
            for (com.templeregistry.entity.auth.User sa : superAdmins) {
                notificationPublisher.publish(sa.getId(), "CLARIFICATION_ESCALATION", declarationId, "ASSET_DECLARATION");
            }
        }

        log.info("Clarification requested for declaration [{}] by userId={}", declarationId, claims.userId());
        return WorkflowActionResponse.builder()
                .declarationId(declarationId)
                .newStatus(DeclarationStatus.CLARIFICATION_REQUIRED.name())
                .acknowledgementNumber(null)
                .message("Clarification requested from temple authority.")
                .build();
    }

    public WorkflowActionResponse requestClarification(Long declarationId, DcClarifyRequest request,
                                                       ScopeHelper.Claims claims) {
        return requestClarification(declarationId, request, claims, null);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public WorkflowActionResponse markUnderReview(Long declarationId, ScopeHelper.Claims claims) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        executeDeclarationTransition(declarationId, WorkflowAction.BEGIN_REVIEW, claims, null);

        declaration.setStatus(DeclarationStatus.UNDER_REVIEW);
        declarationRepository.save(declaration);

        summaryService.scheduleRefresh(declaration.getTempleId());

        log.info("Declaration [{}] marked UNDER_REVIEW by userId={}", declarationId, claims.userId());
        return WorkflowActionResponse.builder()
                .declarationId(declarationId)
                .newStatus(DeclarationStatus.UNDER_REVIEW.name())
                .message("Declaration marked as under review.")
                .build();
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public WorkflowActionResponse flagPhysicalVerification(Long declarationId, DcClarifyRequest request,
                                                            ScopeHelper.Claims claims,
                                                            String idempotencyKey) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // [P3] Canonical: WorkflowEngine sub-status transition
        executeDeclarationTransition(declarationId, WorkflowAction.SCHEDULE_SITE_VISIT, claims, idempotencyKey);

        declaration.setStatus(DeclarationStatus.SITE_VISIT_SCHEDULED);
        declarationRepository.save(declaration);

        auditService.logDataEvent(claims.userId(), claims.role(), "PHYSICAL_VERIFICATION_REQUESTED",
                "AssetDeclaration", declarationId, "flagged=true");
        summaryService.scheduleRefresh(declaration.getTempleId());

        // [P3] Manual notificationHelper removed — event outbox takes over.

        log.info("Physical verification flagged for declaration [{}] by userId={}", declarationId, claims.userId());
        return WorkflowActionResponse.builder()
                .declarationId(declarationId)
                .newStatus(DeclarationStatus.SITE_VISIT_SCHEDULED.name())
                .message("Declaration flagged for physical verification.")
                .build();
    }

    public WorkflowActionResponse flagPhysicalVerification(Long declarationId, DcClarifyRequest request,
                                                           ScopeHelper.Claims claims) {
        return flagPhysicalVerification(declarationId, request, claims, null);
    }

    public void sendBackDeclaration(Long declarationId, DcClarifyRequest request,
                                    ScopeHelper.Claims claims) {
        SendBackRequest sendBackRequest = new SendBackRequest();
        sendBackRequest.setReason(request != null ? request.getMessage() : null);
        sendBackDeclaration(declarationId, sendBackRequest);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void scheduleSiteVisit(Long id, com.templeregistry.dto.request.governance.SiteVisitRequest request, ScopeHelper.Claims claims) {
        AssetDeclaration declaration = loadDeclaration(id);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // [P3] Canonical: WorkflowEngine sub-status transition
        executeDeclarationTransition(id, WorkflowAction.SCHEDULE_SITE_VISIT, claims, null);

        declaration.setStatus(DeclarationStatus.SITE_VISIT_SCHEDULED);
        declaration.setPhysicalVerificationStatus(PhysicalVerificationStatus.ORDERED_FOR_PHYSICAL_VERIFICATION);
        declaration.setPhysicalVerificationOrderedAt(LocalDateTime.now());
        declaration.setPhysicalVerificationOrderedBy(claims.userId());
        declarationRepository.save(declaration);

        // [P2] Snapshot on site visit schedule
        versionService.snapshot(WorkflowEntityType.DECLARATION, id, 1, declaration, claims.userId(), null);

        log.info("Declaration [{}] SITE_VISIT_SCHEDULED by userId={}", id, claims.userId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void completeSiteVisit(Long id, ScopeHelper.Claims claims) {
        AssetDeclaration declaration = loadDeclaration(id);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // [P3] Canonical: WorkflowEngine sub-status transition
        executeDeclarationTransition(id, WorkflowAction.COMPLETE_SITE_VISIT, claims, null);

        declaration.setStatus(DeclarationStatus.SITE_VISIT_COMPLETED);
        declaration.setPhysicalVerificationCompletedAt(LocalDateTime.now());
        declarationRepository.save(declaration);

        // [P2] Snapshot on completion
        versionService.snapshot(WorkflowEntityType.DECLARATION, id, 1, declaration, claims.userId(), null);

        log.info("Declaration [{}] SITE_VISIT_COMPLETED by userId={}", id, claims.userId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void verifyDeclaration(Long id, ScopeHelper.Claims claims) {
        AssetDeclaration declaration = loadDeclaration(id);
        Temple temple = loadTempleWithGeo(declaration.getTempleId());
        jurisdictionGuard.assertDistrictScope(temple, claims);

        // [P3] Canonical: WorkflowEngine sub-status transition
        executeDeclarationTransition(id, WorkflowAction.VERIFY_SITE_VISIT, claims, null);

        declaration.setStatus(DeclarationStatus.VERIFIED);
        declaration.setPhysicalVerificationStatus(PhysicalVerificationStatus.PHYSICALLY_VERIFIED);
        declarationRepository.save(declaration);

        // [P2] Snapshot on verification
        versionService.snapshot(WorkflowEntityType.DECLARATION, id, 1, declaration, claims.userId(), null);

        log.info("Declaration [{}] VERIFIED by userId={}", id, claims.userId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void failSiteVisit(Long id, ScopeHelper.Claims claims) {
        AssetDeclaration declaration = loadDeclaration(id);
        jurisdictionGuard.assertSameDistrict(declaration.getDistrictId());

        // [P3] Canonical: WorkflowEngine sub-status transition
        executeDeclarationTransition(id, WorkflowAction.FAIL_SITE_VISIT, claims, null);

        declaration.setPhysicalVerificationStatus(PhysicalVerificationStatus.VERIFICATION_FAILED);
        declarationRepository.save(declaration);

        log.info("Declaration [{}] site visit FAILED by userId={}", id, claims.userId());
    }

    // =========================================================================
    // PHYSICAL VERIFICATION — Declarations only, DC-only
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void orderPhysicalVerification(Long declarationId, OrderPhysicalVerificationRequest request) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        jurisdictionGuard.assertSameDistrict(declaration.getDistrictId());

        if (declaration.getStatus() == DeclarationStatus.DRAFT
                || declaration.getStatus() == DeclarationStatus.REJECTED) {
            throw new IllegalStatusTransitionException(
                    "Cannot order physical verification for declaration [" + declarationId +
                    "] in status " + declaration.getStatus() + ". Declaration must be SUBMITTED.");
        }

        PhysicalVerificationStatus previous = declaration.getPhysicalVerificationStatus();
        declaration.setPhysicalVerificationStatus(PhysicalVerificationStatus.ORDERED_FOR_PHYSICAL_VERIFICATION);
        declaration.setPhysicalVerificationOrderedAt(LocalDateTime.now());
        declaration.setPhysicalVerificationOrderedBy(currentUserId());
        declarationRepository.save(declaration);

        physicalVerificationHistoryRepository.save(PhysicalVerificationHistory.builder()
                .declarationId(declarationId)
                .dcUserId(currentUserId())
                .previousStatus(previous)
                .newStatus(PhysicalVerificationStatus.ORDERED_FOR_PHYSICAL_VERIFICATION)
                .notes(request.getNotes())
                .build());

        governanceAuditService.logAction(declarationId, "DECLARATION", currentUserId(),
                "ORDER_PHYSICAL_VERIFICATION", "Physical verification ordered. Notes: " + request.getNotes());
        log.info("Declaration [{}] physical verification ORDERED by userId={}", declarationId, currentUserId());
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    public void updatePhysicalVerification(Long declarationId, UpdatePhysicalVerificationRequest request) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        jurisdictionGuard.assertSameDistrict(declaration.getDistrictId());

        PhysicalVerificationStatus current = declaration.getPhysicalVerificationStatus();
        PhysicalVerificationStatus newStatus = request.getNewStatus();

        if (current != PhysicalVerificationStatus.ORDERED_FOR_PHYSICAL_VERIFICATION) {
            throw new IllegalStatusTransitionException(
                    "Physical verification status can only be updated from ORDERED_FOR_PHYSICAL_VERIFICATION. " +
                    "Current status: " + current);
        }
        if (newStatus != PhysicalVerificationStatus.PHYSICALLY_VERIFIED
                && newStatus != PhysicalVerificationStatus.VERIFICATION_FAILED) {
            throw new IllegalStatusTransitionException(
                    "Physical verification can only be updated to PHYSICALLY_VERIFIED or VERIFICATION_FAILED. " +
                    "Requested: " + newStatus);
        }

        declaration.setPhysicalVerificationStatus(newStatus);
        declaration.setPhysicalVerificationCompletedAt(LocalDateTime.now());
        declarationRepository.save(declaration);

        physicalVerificationHistoryRepository.save(PhysicalVerificationHistory.builder()
                .declarationId(declarationId)
                .dcUserId(currentUserId())
                .previousStatus(current)
                .newStatus(newStatus)
                .notes(request.getNotes())
                .build());

        governanceAuditService.logAction(declarationId, "DECLARATION", currentUserId(),
                "UPDATE_PHYSICAL_VERIFICATION",
                "Physical verification updated: " + current + " → " + newStatus + ". Notes: " + request.getNotes());
        log.info("Declaration [{}] physical verification updated: {} → {} by userId={}",
                declarationId, current, newStatus, currentUserId());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(RoleConstants.CAN_ACT_DC + " or " + RoleConstants.IS_DC_ROLE)
    public List<PhysicalVerificationHistoryResponse> getPhysicalVerificationHistory(Long declarationId) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        jurisdictionGuard.assertSameDistrict(declaration.getDistrictId());

        return physicalVerificationHistoryRepository
                .findAllByDeclarationIdOrderByOccurredAtDesc(declarationId)
                .stream()
                .map(h -> PhysicalVerificationHistoryResponse.builder()
                        .id(h.getId())
                        .declarationId(h.getDeclarationId())
                        .dcUserId(h.getDcUserId())
                        .previousStatus(h.getPreviousStatus())
                        .newStatus(h.getNewStatus())
                        .notes(h.getNotes())
                        .occurredAt(h.getOccurredAt())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_SUBMIT)
    public void withdrawDeclaration(Long declarationId) {
        AssetDeclaration declaration = loadDeclaration(declarationId);
        ownershipGuard.assertOwnsTemple(declaration.getTempleId());

        if (!DeclarationStatus.SUBMITTED.equals(declaration.getStatus())
                && !DeclarationStatus.CLARIFICATION_REQUIRED.equals(declaration.getStatus())) {
            throw new IllegalStatusTransitionException(
                    "Declaration [" + declarationId + "] cannot be withdrawn. Current status: "
                    + declaration.getStatus() + ". Only SUBMITTED or CLARIFICATION_REQUIRED declarations can be withdrawn.");
        }

        WorkflowInstance wi = workflowEngineAdaptor.findState(WorkflowEntityType.DECLARATION, declarationId)
                .orElseThrow(() -> new EntityNotFoundException("WorkflowInstance for Declaration", declarationId));

        workflowEngine.execute(wi.getId(),
                WorkflowActionRequest.builder()
                        .action(WorkflowAction.WITHDRAW)
                        .idempotencyKey(UUID.randomUUID().toString())
                        .build(),
                ActionContext.builder()
                        .actorId(currentUserId())
                        .actorRole("TEMPLE_AUTHORITY")
                        .build());

        declaration.setStatus(DeclarationStatus.WITHDRAWN);
        declarationRepository.save(declaration);

        log.info("Declaration [{}] withdrawn by userId={}", declarationId, currentUserId());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Long districtIdForTrust(Trust trust) {
        return templeRepository.findById(trust.getTempleId())
            .map(t -> t.getDistrictId())
            .orElse(0L);
    }

    private void assertDistrictScopeForTrust(Trust trust) {
        Temple temple = templeRepository.findWithGeoById(trust.getTempleId())
                .orElseThrow(() -> new EntityNotFoundException("Temple", trust.getTempleId()));
        jurisdictionGuard.assertDistrictScope(temple, currentClaims());
    }

    private Trust loadTrust(Long id) {
        return trustRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Trust", id));
    }

    private AssetDeclaration loadDeclaration(Long id) {
        return declarationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeclaration", id));
    }

    private Temple loadTempleWithGeo(Long templeId) {
        return templeRepository.findWithGeoById(templeId)
                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
    }

    private Long currentUserId() {
        org.springframework.security.core.Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) return 0L;
        Object principal = authentication.getPrincipal();
        if (principal instanceof ScopeHelper.Claims c) return c.userId();
        return 0L;
    }

    private String currentRole() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims c) return c.role();
        return "UNKNOWN";
    }

    private ScopeHelper.Claims currentClaims() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims c) return c;
        throw new IllegalStateException("Authenticated principal is not a ScopeHelper.Claims instance.");
    }

    private void executeDeclarationTransition(Long declarationId,
                                              WorkflowAction action,
                                              ScopeHelper.Claims claims,
                                              String clientIdempotencyKey) {
        WorkflowInstance wi = workflowEngineAdaptor.findState(WorkflowEntityType.DECLARATION, declarationId)
            .orElseGet(() -> {
                AssetDeclaration declaration = loadDeclaration(declarationId);
                return workflowEngineAdaptor.ensureInitiated(
                    WorkflowEntityType.DECLARATION,
                    declarationId,
                    declaration.getTempleId(),
                    declaration.getDistrictId(),
                    claims.userId());
            });

        workflowEngine.execute(wi.getId(),
            WorkflowActionRequest.builder()
                .action(action)
                .idempotencyKey(effectiveIdempotencyKey(clientIdempotencyKey))
                .build(),
            ActionContext.builder()
                .actorId(claims.userId())
                .actorRole("DC")
                .actorDistrictId(claims.districtId())
                .build());
    }

    private String effectiveIdempotencyKey(String clientProvidedKey) {
        return StringUtils.hasText(clientProvidedKey)
            ? clientProvidedKey
            : UUID.randomUUID().toString();
    }

    private String generateAcknowledgementDocument(AssetDeclaration declaration, String acknowledgementNumber) {
        try {
            Temple temple = loadTempleWithGeo(declaration.getTempleId());

            String income = declaration.getAnnualIncome() != null
                ? String.format("₹ %,.2f", declaration.getAnnualIncome()) : null;
            String expenditure = declaration.getAnnualExpenditure() != null
                ? String.format("₹ %,.2f", declaration.getAnnualExpenditure()) : null;

            byte[] pdfBytes = AcknowledgementTemplate.builder()
                .acknowledgementNumber(acknowledgementNumber)
                .declarationId(declaration.getId())
                .templeName(temple.getName())
                .templeId(declaration.getTempleId())
                .districtId(declaration.getDistrictId())
                .financialYear(declaration.getFinancialYear())
                .version(String.valueOf(declaration.getVersionNumber()))
                .annualIncome(income)
                .annualExpenditure(expenditure)
                .status(declaration.getStatus() != null ? declaration.getStatus().name() : "APPROVED")
                .reviewedAt(LocalDateTime.now())
                .remarks(declaration.getReviewComment())
                .build()
                .render();

            String filename = "ACK_DECLARATION_" + declaration.getId() + ".pdf";
            return fileStorageService.uploadBytes("declarations/acknowledgements", filename, pdfBytes);
        } catch (Exception ex) {
            log.error("Acknowledgement PDF generation failed for declarationId={} ackNumber={}: {}",
                declaration.getId(), acknowledgementNumber, ex.getMessage());
            throw new IllegalStateException("Failed to generate acknowledgement document.", ex);
        }
    }

    /**
     * V-H1 guard: asserts that the entity-level status string matches the WorkflowInstance
     * status after a dual-write transition.
     *
     * <p>Both writes happen in the same {@code @Transactional}, so there is no partial-failure
     * atomicity risk. This guard exists to detect <em>mapping divergence</em> introduced by future
     * refactors — e.g., a developer changes the entity status enum value without updating the
     * corresponding WorkflowStatus enum, or vice-versa.
     *
     * <p>Throws {@link IllegalStateException} only when the names do not match, so it is safe
     * in production (both sides should always agree). In tests the mismatch will surface
     * immediately.
     *
     * @param entityType      the workflow entity type (DECLARATION, TRUST, …)
     * @param entityId        the domain entity PK
     * @param entityStatusName the {@code .name()} of the entity-level status enum just set
     */
    private void assertEntityStatusConsistency(
            WorkflowEntityType entityType, Long entityId, String entityStatusName) {
        workflowEngineAdaptor.findState(entityType, entityId).ifPresent(wi -> {
            String workflowStatusName = wi.getStatus() != null ? wi.getStatus().name() : "null";
            String canonicalWorkflowStatusName = canonicalizeWorkflowStatusForEntity(entityType, workflowStatusName);
            if (!canonicalWorkflowStatusName.equalsIgnoreCase(entityStatusName)) {
                // Log at ERROR so that it surfaces in monitoring before any production issue
                log.error(
                    "[V-H1] Status divergence detected after transition: entityType={} entityId={} " +
                    "entityStatus={} workflowStatus={} canonicalWorkflowStatus={} — these must be kept in sync",
                    entityType, entityId, entityStatusName, workflowStatusName, canonicalWorkflowStatusName);
                throw new IllegalStateException(
                    "Entity status [" + entityStatusName + "] diverged from WorkflowInstance status ["
                    + workflowStatusName + "] for " + entityType + " id=" + entityId
                    + ". Update the status mapping in GovernanceWorkflowServiceImpl.");
            }
        });
    }

    private String canonicalizeWorkflowStatusForEntity(WorkflowEntityType entityType, String workflowStatusName) {
        if (entityType == WorkflowEntityType.TRUST) {
            // Trust entity uses SubmissionStatus.SENT_BACK while the workflow engine
            // uses WorkflowStatus.CLARIFICATION_REQUESTED for the same state.
            return switch (workflowStatusName) {
                case "CLARIFICATION_REQUESTED" -> "SENT_BACK";
                default -> workflowStatusName;
            };
        }
        if (entityType != WorkflowEntityType.DECLARATION) {
            return workflowStatusName;
        }

        return switch (workflowStatusName) {
            case "RE_APPROVED" -> "APPROVED";
            case "CLARIFICATION_REQUESTED" -> "CLARIFICATION_REQUIRED";
            case "RESUBMITTED" -> "SUBMITTED";
            default -> workflowStatusName;
        };
    }
}

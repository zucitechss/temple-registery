package com.templeregistry.service.impl.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.dto.request.governance.RejectRequest;
import com.templeregistry.dto.request.governance.SendBackRequest;
import com.templeregistry.entity.declaration.AssetDeclaration;
import com.templeregistry.entity.declaration.DeclarationStatus;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.trust.Trust;
import com.templeregistry.entity.trust.TrustType;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.entity.workflow.WorkflowStatus;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.trust.TrustRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.audit.GovernanceAuditService;
import com.templeregistry.service.declaration.AcknowledgementService;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.service.workflow.WorkflowEngine;
import com.templeregistry.service.workflow.WorkflowEngineAdaptor;
import com.templeregistry.service.workflow.VersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GovernanceWorkflowServiceImpl â€” focuses on:
 *   - Trust workflow methods call WorkflowEngineAdaptor correctly
 *   - sendBackTrust persists sendBackReason display field
 *   - EntityNotFoundException propagates when Trust not found
 *   - rejectTrust restores approved data when trust was previously approved
 *   - No dual-write of SubmissionStatus (removed in Canonical Status Architecture)
 */
@ExtendWith(MockitoExtension.class)
class GovernanceWorkflowServiceImplTest {

    @Mock TrustRepository trustRepository;
    @Mock TempleRepository templeRepository;
    @Mock WorkflowEngineAdaptor workflowEngineAdaptor;
    @Mock WorkflowEngine workflowEngine;
    @Mock VersionService versionService;
    @Mock JurisdictionGuard jurisdictionGuard;
    @Mock AuditService auditService;
    @Mock GovernanceAuditService governanceAuditService;
    @Mock AcknowledgementService acknowledgementService;
    @Mock TempleSearchSummaryService summaryService;
    @Mock com.templeregistry.repository.declaration.DeclarationRepository declarationRepository;
    @Mock com.templeregistry.repository.governance.PhysicalVerificationHistoryRepository physicalVerificationHistoryRepository;
    @Mock com.templeregistry.repository.workflow.WorkflowInstanceRepository workflowInstanceRepository;
    @Mock com.templeregistry.service.notification.NotificationRecipientResolver recipientResolver;
    @Mock com.templeregistry.repository.auth.UserRepository userRepository;
    @Mock com.templeregistry.service.dc.NotificationEventPublisher notificationPublisher;
    @Mock com.templeregistry.repository.declaration.DeclarationClarificationRepository clarificationRepository;
    @Mock com.templeregistry.service.clarification.ClarificationEngine clarificationEngine;
    @Mock com.templeregistry.security.OwnershipGuard ownershipGuard;
    @Mock com.templeregistry.security.ScopeHelper scopeHelper;
    @Mock com.templeregistry.service.document.FileStorageService fileStorageService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks
    GovernanceWorkflowServiceImpl service;

    private static final Long TRUST_ID    = 1L;
    private static final Long TEMPLE_ID   = 10L;
    private static final Long DISTRICT_ID = 7L;
    private static final Long ACTOR_ID    = 5L;

    private Trust trust;
    private Temple temple;

    @BeforeEach
    void setUp() {
        trust = Trust.builder()
            .templeId(TEMPLE_ID)
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(trust, "id", TRUST_ID);

        temple = Temple.builder()
            .districtId(DISTRICT_ID)
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(temple, "id", TEMPLE_ID);

        ScopeHelper.Claims claims = new ScopeHelper.Claims(ACTOR_ID, "DISTRICT_COLLECTOR", DISTRICT_ID, null, "dc_user", "EDIT");
        var auth = new UsernamePasswordAuthenticationToken(claims, null);
        var secCtx = new org.springframework.security.core.context.SecurityContextImpl(auth);
        SecurityContextHolder.setContext(secCtx);

        lenient().when(trustRepository.findById(TRUST_ID)).thenReturn(Optional.of(trust));
        lenient().when(templeRepository.findById(TEMPLE_ID)).thenReturn(Optional.of(temple));
        lenient().when(templeRepository.findWithGeoById(TEMPLE_ID)).thenReturn(Optional.of(temple));
        lenient().doNothing().when(jurisdictionGuard).assertDistrictScope(any(), any());
    }

        @Test
        @DisplayName("submitTrust() transitions, snapshots, and refreshes summary when submit is valid")
        void should_snapshotAndRefresh_when_submitTrustTransitions() {
        when(workflowEngineAdaptor.currentStatus(WorkflowEntityType.TRUST, TRUST_ID))
            .thenReturn(WorkflowStatus.UPDATED_AFTER_APPROVAL);
        when(workflowEngineAdaptor.adaptSubmit(
            WorkflowEntityType.TRUST, TRUST_ID, TEMPLE_ID, DISTRICT_ID, ACTOR_ID))
            .thenReturn(true);

        service.submitTrust(TRUST_ID);

        verify(versionService).snapshot(
            eq(WorkflowEntityType.TRUST), eq(TRUST_ID), eq(1), any(), eq(ACTOR_ID), isNull());
        verify(summaryService).scheduleRefresh(TEMPLE_ID);
        }

        @Test
        @DisplayName("submitTrust() throws when no workflow transition occurs")
        void should_throw_when_submitTrustDoesNotTransition() {
        when(workflowEngineAdaptor.currentStatus(WorkflowEntityType.TRUST, TRUST_ID))
            .thenReturn(WorkflowStatus.APPROVED);
        when(workflowEngineAdaptor.adaptSubmit(
            WorkflowEntityType.TRUST, TRUST_ID, TEMPLE_ID, DISTRICT_ID, ACTOR_ID))
            .thenReturn(false);

        assertThatThrownBy(() -> service.submitTrust(TRUST_ID))
            .isInstanceOf(com.templeregistry.exception.IllegalStatusTransitionException.class)
            .hasMessageContaining("cannot be submitted");

        verify(versionService, never()).snapshot(any(), anyLong(), anyInt(), any(), anyLong(), any());
        }

    // â”€â”€ approveTrust â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("approveTrust() calls adaptor, takes snapshot, and stores approved_data")
    void should_callAdaptorAndSnapshot_when_approveTrust() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"trustName\":\"Test\"}");

        service.approveTrust(TRUST_ID);

        verify(workflowEngineAdaptor).adaptApprove(
            eq(WorkflowEntityType.TRUST), eq(TRUST_ID), eq(DISTRICT_ID), eq(ACTOR_ID));
        verify(versionService).snapshot(
            eq(WorkflowEntityType.TRUST), eq(TRUST_ID), eq(1), any(), eq(ACTOR_ID), isNull());
        // Trust is saved with approvedData populated
        verify(trustRepository).save(argThat(t -> t.getApprovedData() != null));
    }

    @Test
    @DisplayName("approveTrust() does not throw when ObjectMapper serialization fails (non-fatal)")
    void should_notThrow_when_approvedDataSnapshotFails() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("serialization error"));

        // Must not throw â€” snapshot failure is non-fatal
        service.approveTrust(TRUST_ID);

        verify(workflowEngineAdaptor).adaptApprove(any(), any(), any(), any());
        // Save is NOT called since the exception is caught before save
        verify(trustRepository, never()).save(any());
    }

    // â”€â”€ sendBackTrust â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("sendBackTrust() calls adaptor and persists sendBackReason on trust entity")
    void should_callAdaptorAndPersistSendBackReason_when_sendBackTrust() {
        SendBackRequest req = new SendBackRequest();
        req.setReason("Missing document");
        service.sendBackTrust(TRUST_ID, req);

        verify(workflowEngineAdaptor).adaptSendBack(
            eq(WorkflowEntityType.TRUST), eq(TRUST_ID), eq(DISTRICT_ID), eq(ACTOR_ID), eq("Missing document"));
        verify(trustRepository, atLeastOnce()).save(argThat(t -> "Missing document".equals(t.getSendBackReason())));
    }

    // â”€â”€ rejectTrust â€” first-time rejection (no approvedData) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("rejectTrust() when trust was never approved: calls adaptor only, does not save trust")
    void should_callAdaptorOnly_when_rejectTrustWithNoApprovedData() {
        // trust.approvedData == null (never approved) â€” default from builder
        RejectRequest req = new RejectRequest();
        req.setReason("Non-compliant");
        service.rejectTrust(TRUST_ID, req);

        verify(workflowEngineAdaptor).adaptReject(
            eq(WorkflowEntityType.TRUST), eq(TRUST_ID), eq(DISTRICT_ID), eq(ACTOR_ID), eq("Non-compliant"));
        // No entity save for first-time rejection â€” no data to restore
        verify(trustRepository, never()).save(any());
    }

    // â”€â”€ rejectTrust â€” rejection of an edit to an approved trust â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("rejectTrust() when trust has approvedData: restores approved fields, saves trust, and uses adaptRejectEdit (non-terminal)")
    void should_restoreApprovedData_when_rejectTrustWithExistingApprovedData() throws Exception {
        // Simulate a previously approved trust that was then edited
        String approvedJson = new ObjectMapper().writeValueAsString(java.util.Map.of(
            "trustName",               "Original Trust Name",
            "trustType",               "SINGLE_TRUSTEE",
            "trustRegistrationNumber", "REG-001",
            "registeringAuthority",    "Sub-Registrar",
            "dateOfRegistration",      "2020-01-15",
            "bankNameAndBranch",       "SBI||Main Branch",
            "annualIncome",            "500000"
        ));
        trust.setApprovedData(approvedJson);
        trust.setTrustName("Edited Name"); // simulates what TA changed before submission

        // Use real ObjectMapper for deserialization in the service
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        RejectRequest req = new RejectRequest();
        req.setReason("Data mismatch");
        service.rejectTrust(TRUST_ID, req);

        // Verify trust fields are restored to approved values
        verify(trustRepository).save(argThat(t ->
            "Original Trust Name".equals(t.getTrustName()) &&
            TrustType.SINGLE_TRUSTEE.equals(t.getTrustType()) &&
            "REG-001".equals(t.getTrustRegistrationNumber()) &&
            LocalDate.of(2020, 1, 15).equals(t.getDateOfRegistration())
        ));
        // Edit rejection must use adaptRejectEdit (â†’ RE_APPROVED), NOT adaptReject (â†’ REJECTED).
        verify(workflowEngineAdaptor).adaptRejectEdit(
            eq(WorkflowEntityType.TRUST), eq(TRUST_ID), eq(DISTRICT_ID), eq(ACTOR_ID), eq("Data mismatch"));
        verify(workflowEngineAdaptor, never()).adaptReject(any(), anyLong(), anyLong(), anyLong(), any());
    }

    // â”€â”€ EntityNotFoundException propagates â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("approveTrust() throws EntityNotFoundException when trust is not found")
    void should_throwEntityNotFoundException_when_trustNotFound() {
        when(trustRepository.findById(TRUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveTrust(TRUST_ID))
            .isInstanceOf(EntityNotFoundException.class);
    }

    // â”€â”€ No WorkflowInstance â€” no throw â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("approveTrust() does not throw when no WorkflowInstance exists for trust")
    void should_notThrow_when_noWorkflowInstanceExistsForTrust() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.approveTrust(TRUST_ID);

        verify(workflowEngineAdaptor).adaptApprove(
            eq(WorkflowEntityType.TRUST), eq(TRUST_ID), anyLong(), eq(ACTOR_ID));
    }

    // â”€â”€ Declaration submit â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_setSubmittedAt_when_declarationSubmitted() {
        // Regression: submitDeclaration previously set submittedBy but never
        // submittedAt, so every declaration showed a blank "Submitted" date
        // on both the TA and DC sides regardless of submission state.
        AssetDeclaration declaration = AssetDeclaration.builder()
                .templeId(TEMPLE_ID)
                .districtId(DISTRICT_ID)
                .status(DeclarationStatus.DRAFT)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(declaration, "id", 100L);

        when(declarationRepository.findById(100L)).thenReturn(Optional.of(declaration));
        when(workflowEngineAdaptor.adaptSubmit(
                eq(WorkflowEntityType.DECLARATION), eq(100L), eq(TEMPLE_ID), eq(DISTRICT_ID), anyLong()))
                .thenReturn(true);

        service.submitDeclaration(100L);

        assertThat(declaration.getSubmittedAt()).isNotNull();
        assertThat(declaration.getSubmittedBy()).isEqualTo(ACTOR_ID);
        verify(declarationRepository).save(declaration);
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @SuppressWarnings("unused")
    private WorkflowInstance workflowInstanceWithStatus(WorkflowStatus status) {
        return WorkflowInstance.builder()
            .entityType(WorkflowEntityType.TRUST)
            .entityId(TRUST_ID)
            .status(status)
            .lockVersion(1L)
            .build();
    }
}


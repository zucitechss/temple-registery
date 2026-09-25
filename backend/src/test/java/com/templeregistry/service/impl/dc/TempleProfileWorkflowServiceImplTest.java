package com.templeregistry.service.impl.dc;

import com.templeregistry.dto.request.dc.ApproveProfileRequest;
import com.templeregistry.dto.request.dc.RejectProfileRequest;
import com.templeregistry.dto.response.dc.WorkflowActionResponse;
import org.springframework.test.util.ReflectionTestUtils;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleProfileStaging;
import com.templeregistry.entity.temple.TempleProfileStagingStatus;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.entity.temple.VerificationStatus;
import com.templeregistry.entity.workflow.WorkflowAction;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.entity.workflow.WorkflowStatus;
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
import com.templeregistry.service.notification.NotificationHelper;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.service.workflow.ActionContext;
import com.templeregistry.service.workflow.ActionContextResolver;
import com.templeregistry.service.workflow.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for Temple Profile workflow parity with Trust.
 * Verifies DC can approve/reject SUBMITTED, UNDER_REVIEW, and RESUBMITTED staging records.
 */
@ExtendWith(MockitoExtension.class)
class TempleProfileWorkflowServiceImplTest {

    @Mock private TempleProfileStagingRepository stagingRepository;
    @Mock private TempleProfileCurrentRepository currentRepository;
    @Mock private TempleProfileHistoryRepository historyRepository;
    @Mock private TempleRepository templeRepository;
    @Mock private JurisdictionGuard jurisdictionGuard;
    @Mock private NotificationHelper notificationHelper;
    @Mock private TempleSearchSummaryService summaryService;
    @Mock private AuditService auditService;
    @Mock private GovernanceAuditService governanceAuditService;
    @Mock private WorkflowEngine workflowEngine;
    @Mock private ActionContextResolver actionContextResolver;
    @Mock private HobliRepository hobliRepository;

    @InjectMocks
    private TempleProfileWorkflowServiceImpl service;

    private ScopeHelper.Claims dcClaims;
    private Temple activeTemple;

    @BeforeEach
    void setUp() {
        dcClaims = new ScopeHelper.Claims(2L, RoleConstants.DISTRICT_COLLECTOR, 10L, null, "dc", "EDIT");
        activeTemple = Temple.builder()
                .id(1L)
                .districtId(10L)
                .status(TempleStatus.ACTIVE)
                .build();

        lenient().doNothing().when(jurisdictionGuard).assertDistrictScope(any(), any());
        lenient().doNothing().when(notificationHelper).notifyTempleApproved(any(), any());
        lenient().doNothing().when(notificationHelper).notifyTempleRejected(any(), any(), any());
        lenient().doNothing().when(auditService).logDataEvent(any(), any(), any(), any(), any(), any());
        lenient().doNothing().when(governanceAuditService).logAction(any(), any(), any(), any(), any());
        lenient().doNothing().when(summaryService).refresh(any());
        lenient().when(workflowEngine.executeSystem(any(), any(), any())).thenReturn(null);
        lenient().when(currentRepository.findByTempleId(any())).thenReturn(Optional.empty());
        lenient().when(stagingRepository.findFirstByTempleIdAndStatus(any(), eq(TempleProfileStagingStatus.APPROVED)))
                .thenReturn(Optional.empty());
        lenient().when(actionContextResolver.resolve(any())).thenReturn(
                ActionContext.builder().actorId(2L).actorRole("DC").actorDistrictId(10L).build());
        lenient().when(stagingRepository.save(any(TempleProfileStaging.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(currentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private TempleProfileStaging stagingWith(Long id, Long templeId) {
        TempleProfileStaging s = TempleProfileStaging.builder()
                .templeId(templeId)
                .versionNumber(1)
                .build();
        s.setId(id);
        return s;
    }

    private WorkflowInstance workflowAt(Long instanceId, WorkflowStatus status) {
        return WorkflowInstance.builder()
                .id(instanceId)
                .status(status)
                .lockVersion(1L)
                .versionNumber(1)
                .entityType(WorkflowEntityType.TEMPLE_PROFILE)
                .build();
    }

    // â”€â”€ Approve: SUBMITTED â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_approve_SUBMITTED_staging_and_call_workflow_engine() {
        TempleProfileStaging staging = stagingWith(100L, 1L);
        when(stagingRepository.findById(100L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 100L))
                .thenReturn(workflowAt(999L, WorkflowStatus.SUBMITTED));

        ApproveProfileRequest approveReq = new ApproveProfileRequest();
        ReflectionTestUtils.setField(approveReq, "remarks", "Looks good");
        WorkflowActionResponse result = service.approveProfile(100L, approveReq, dcClaims);

        assertThat(result.getNewStatus()).isEqualTo("APPROVED");
        assertThat(activeTemple.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(activeTemple.getDcRejectionReason()).isNull();
        verify(workflowEngine).execute(eq(999L),
                argThat(req -> req.getAction() == WorkflowAction.APPROVE), any());
    }

    // â”€â”€ Approve: UNDER_REVIEW â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_approve_UNDER_REVIEW_staging_with_APPROVE_action() {
        TempleProfileStaging staging = stagingWith(200L, 1L);
        when(stagingRepository.findById(200L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 200L))
                .thenReturn(workflowAt(998L, WorkflowStatus.UNDER_REVIEW));

        ApproveProfileRequest approveReq = new ApproveProfileRequest();
        ReflectionTestUtils.setField(approveReq, "remarks", "All clear");
        service.approveProfile(200L, approveReq, dcClaims);

        verify(workflowEngine).execute(eq(998L),
                argThat(req -> req.getAction() == WorkflowAction.APPROVE), any());
    }

    // â”€â”€ Approve: RESUBMITTED (parity with Trust) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_approve_RESUBMITTED_staging_with_RE_APPROVE_action() {
        // Core parity test: RESUBMITTED (after approved edit + resubmit) must be approvable
        TempleProfileStaging staging = stagingWith(300L, 1L);
        when(stagingRepository.findById(300L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 300L))
                .thenReturn(workflowAt(997L, WorkflowStatus.RESUBMITTED));

        ApproveProfileRequest approveReq = new ApproveProfileRequest();
        ReflectionTestUtils.setField(approveReq, "remarks", "Resubmission approved");
        WorkflowActionResponse result = service.approveProfile(300L, approveReq, dcClaims);

        assertThat(result.getNewStatus()).isEqualTo("APPROVED");
        // RESUBMITTED â†’ RE_APPROVE
        verify(workflowEngine).execute(eq(997L),
                argThat(req -> req.getAction() == WorkflowAction.RE_APPROVE), any());
    }

    // â”€â”€ Reject: SUBMITTED â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_reject_SUBMITTED_staging_and_call_workflow_engine() {
        TempleProfileStaging staging = stagingWith(400L, 1L);
        when(stagingRepository.findById(400L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 400L))
                .thenReturn(workflowAt(996L, WorkflowStatus.SUBMITTED));

        RejectProfileRequest rejectReq = new RejectProfileRequest();
        ReflectionTestUtils.setField(rejectReq, "reason", "Incomplete documents");
        WorkflowActionResponse result = service.rejectProfile(400L, rejectReq, dcClaims);

        assertThat(result.getNewStatus()).isEqualTo("REJECTED");
        verify(workflowEngine).execute(eq(996L),
                argThat(req -> req.getAction() == WorkflowAction.REJECT
                        && "Incomplete documents".equals(req.getComment())),
                any());
    }

    // â”€â”€ Reject: RESUBMITTED (parity with Trust) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_reject_RESUBMITTED_staging_via_REJECT_action() {
        TempleProfileStaging staging = stagingWith(500L, 1L);
        when(stagingRepository.findById(500L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 500L))
                .thenReturn(workflowAt(995L, WorkflowStatus.RESUBMITTED));

        RejectProfileRequest rejectReq = new RejectProfileRequest();
        ReflectionTestUtils.setField(rejectReq, "reason", "Still missing");
        service.rejectProfile(500L, rejectReq, dcClaims);

        verify(workflowEngine).execute(eq(995L),
                argThat(req -> req.getAction() == WorkflowAction.REJECT), any());
    }

    // â”€â”€ Guard: non-reviewable status â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_throw_when_approve_called_on_DRAFT_staging() {
        TempleProfileStaging staging = stagingWith(600L, 1L);
        when(stagingRepository.findById(600L)).thenReturn(Optional.of(staging));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 600L))
                .thenReturn(workflowAt(994L, WorkflowStatus.DRAFT));

        ApproveProfileRequest approveReq600 = new ApproveProfileRequest();
        ReflectionTestUtils.setField(approveReq600, "remarks", "test");
        assertThatThrownBy(() -> service.approveProfile(600L, approveReq600, dcClaims))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void should_throw_when_approve_called_on_already_APPROVED_staging() {
        TempleProfileStaging staging = stagingWith(700L, 1L);
        when(stagingRepository.findById(700L)).thenReturn(Optional.of(staging));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 700L))
                .thenReturn(workflowAt(993L, WorkflowStatus.APPROVED));

        ApproveProfileRequest approveReq700 = new ApproveProfileRequest();
        ReflectionTestUtils.setField(approveReq700, "remarks", "test");
        assertThatThrownBy(() -> service.approveProfile(700L, approveReq700, dcClaims))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void should_throw_when_reject_called_on_REJECTED_staging() {
        TempleProfileStaging staging = stagingWith(800L, 1L);
        when(stagingRepository.findById(800L)).thenReturn(Optional.of(staging));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 800L))
                .thenReturn(workflowAt(992L, WorkflowStatus.REJECTED));

        RejectProfileRequest rejectReq800 = new RejectProfileRequest();
        ReflectionTestUtils.setField(rejectReq800, "reason", "test");
        assertThatThrownBy(() -> service.rejectProfile(800L, rejectReq800, dcClaims))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REJECTED");
    }

    // â”€â”€ Approve: reviewComment is cleared â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_nullReviewComment_when_profileApproved() {
        // Arrange: staging previously had a rejection comment
        TempleProfileStaging staging = stagingWith(900L, 1L);
        staging.setReviewComment("Prior rejection reason from DC");
        when(stagingRepository.findById(900L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 900L))
                .thenReturn(workflowAt(991L, WorkflowStatus.SUBMITTED));

        ApproveProfileRequest req = new ApproveProfileRequest();
        ReflectionTestUtils.setField(req, "remarks", "Approved after fix");

        // Act
        service.approveProfile(900L, req, dcClaims);

        // Assert: staging.reviewComment must be null after approval
        assertThat(staging.getReviewComment()).isNull();
    }

    // â”€â”€ Approve: dcRejectionReason cleared on temple entity â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_clearDcRejectionReason_when_profileApproved() {
        activeTemple.setDcRejectionReason("Temple was flagged for incomplete address");
        TempleProfileStaging staging = stagingWith(901L, 1L);
        when(stagingRepository.findById(901L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 901L))
                .thenReturn(workflowAt(990L, WorkflowStatus.SUBMITTED));

        ApproveProfileRequest req = new ApproveProfileRequest();
        ReflectionTestUtils.setField(req, "remarks", "Clear and approve");

        service.approveProfile(901L, req, dcClaims);

        assertThat(activeTemple.getDcRejectionReason()).isNull();
        assertThat(activeTemple.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    // â”€â”€ Location metadata (V97) promotion on approval â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_promote_placeId_and_formattedAddress_to_temple_on_approval() {
        TempleProfileStaging staging = stagingWith(902L, 1L);
        staging.setPlaceId("ChIJ21P2rgVRrhkRjIgqmoQ0pIE");
        staging.setFormattedAddress("ISKCON Temple, Bengaluru");
        when(stagingRepository.findById(902L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 902L))
                .thenReturn(workflowAt(989L, WorkflowStatus.SUBMITTED));

        ApproveProfileRequest req = new ApproveProfileRequest();
        ReflectionTestUtils.setField(req, "remarks", "Approved");
        service.approveProfile(902L, req, dcClaims);

        assertThat(activeTemple.getPlaceId()).isEqualTo("ChIJ21P2rgVRrhkRjIgqmoQ0pIE");
        assertThat(activeTemple.getFormattedAddress()).isEqualTo("ISKCON Temple, Bengaluru");
    }

    // ── Bug repro: taluk must be derived from hobli on DC approval ──────────
    //
    // Expected: when a TA-submitted staging row carries a new hobliId, DC approval
    // must promote BOTH temple.hobliId and temple.talukId (derived via
    // HobliRepository.findTalukIdById) onto the Temple entity — talukId is a flat,
    // denormalized column with no JPA relation, so nothing keeps it in sync
    // automatically; the service must do it explicitly, exactly as
    // TempleServiceImpl/RegistrationServiceImpl already do on their own write paths.
    //
    // Actual (bug): promoteToTemple() sets temple.hobliId (line 310) but never
    // references talukId at all, so it stays null/stale after every DC approval.

    @Test
    void should_populate_talukId_when_hobliId_is_set_on_approval() {
        TempleProfileStaging staging = stagingWith(1000L, 1L);
        staging.setHobliId(55L);
        when(stagingRepository.findById(1000L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 1000L))
                .thenReturn(workflowAt(989L, WorkflowStatus.SUBMITTED));
        when(hobliRepository.findTalukIdById(55L)).thenReturn(Optional.of(42L));

        ApproveProfileRequest req = new ApproveProfileRequest();
        ReflectionTestUtils.setField(req, "remarks", "Approved");
        service.approveProfile(1000L, req, dcClaims);

        assertThat(activeTemple.getHobliId()).isEqualTo(55L);
        assertThat(activeTemple.getTalukId())
                .as("talukId must be derived from the newly-approved hobliId")
                .isEqualTo(42L);
    }

    @Test
    void should_not_touch_talukId_when_staging_hobliId_is_null() {
        // No hobliId change in this submission (e.g. an edit that only touched contact info) —
        // the existing hobli/taluk assignment must be left alone.
        activeTemple.setHobliId(7L);
        activeTemple.setTalukId(3L);
        TempleProfileStaging staging = stagingWith(1001L, 1L);
        when(stagingRepository.findById(1001L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 1001L))
                .thenReturn(workflowAt(988L, WorkflowStatus.SUBMITTED));

        ApproveProfileRequest req = new ApproveProfileRequest();
        ReflectionTestUtils.setField(req, "remarks", "Approved");
        service.approveProfile(1001L, req, dcClaims);

        assertThat(activeTemple.getHobliId()).isEqualTo(7L);
        assertThat(activeTemple.getTalukId()).isEqualTo(3L);
        verifyNoInteractions(hobliRepository);
    }

    @Test
    void should_leave_talukId_unchanged_when_hobli_has_no_resolvable_taluk() {
        // Defensive case: hobliId points at a row with no taluk link (data integrity issue
        // elsewhere). Must not null out a previously-good talukId just because the lookup missed.
        activeTemple.setTalukId(9L);
        TempleProfileStaging staging = stagingWith(1002L, 1L);
        staging.setHobliId(66L);
        when(stagingRepository.findById(1002L)).thenReturn(Optional.of(staging));
        when(templeRepository.findWithGeoById(1L)).thenReturn(Optional.of(activeTemple));
        when(workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, 1002L))
                .thenReturn(workflowAt(987L, WorkflowStatus.SUBMITTED));
        when(hobliRepository.findTalukIdById(66L)).thenReturn(Optional.empty());

        ApproveProfileRequest req = new ApproveProfileRequest();
        ReflectionTestUtils.setField(req, "remarks", "Approved");
        service.approveProfile(1002L, req, dcClaims);

        assertThat(activeTemple.getHobliId()).isEqualTo(66L);
        assertThat(activeTemple.getTalukId()).isEqualTo(9L);
    }
}

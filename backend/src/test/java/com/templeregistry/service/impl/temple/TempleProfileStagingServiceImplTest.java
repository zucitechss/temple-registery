package com.templeregistry.service.impl.temple;

import com.templeregistry.dto.request.temple.CreateTempleProfileStagingRequest;
import com.templeregistry.dto.response.temple.TempleProfileStagingResponse;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleProfileStaging;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.entity.workflow.WorkflowStatus;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.temple.TempleProfileStagingRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.OwnershipGuard;
import com.templeregistry.security.AccessGuard;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.service.workflow.ActionContextResolver;
import com.templeregistry.util.PaginationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TempleProfileStagingServiceImplTest {

    @Mock TempleProfileStagingRepository stagingRepository;
    @Mock TempleRepository templeRepository;
    @Mock TempleSearchSummaryService summaryService;
    @Mock OwnershipGuard ownershipGuard;
    @Mock AccessGuard accessGuard;
    @Mock PaginationUtil paginationUtil;
    @Mock com.templeregistry.service.workflow.WorkflowEngine workflowEngine;
    @Mock com.templeregistry.service.workflow.WorkflowEngineAdaptor workflowEngineAdaptor;
    @Mock com.templeregistry.service.workflow.VersionService versionService;
    @Mock com.templeregistry.service.clarification.ClarificationEngine clarificationEngine;
    @Mock ActionContextResolver actionContextResolver;
    @Mock com.templeregistry.service.document.FileStorageService fileStorageService;
    @Mock com.templeregistry.service.governance.GovernanceStatusResolver governanceStatusResolver;
    @Mock com.templeregistry.repository.geo.HobliRepository hobliRepository;

    @InjectMocks TempleProfileStagingServiceImpl stagingService;

    private Temple activeTemple;
    private Temple suspendedTemple;

    @BeforeEach
    void setUp() {
        activeTemple = Temple.builder().id(1L).districtId(10L).status(TempleStatus.ACTIVE).build();
        suspendedTemple = Temple.builder().id(1L).status(TempleStatus.SUSPENDED).build();

        lenient().doNothing().when(ownershipGuard).assertOwnsTemple(any());
        lenient().doNothing().when(accessGuard).assertCanEdit();

        // Mock security context
        SecurityContext ctx = mock(SecurityContext.class);
        Authentication auth = mock(Authentication.class);
        var claims = mock(com.templeregistry.security.ScopeHelper.Claims.class);
        lenient().when(ctx.getAuthentication()).thenReturn(auth);
        lenient().when(auth.getPrincipal()).thenReturn(claims);
        lenient().when(claims.userId()).thenReturn(42L);
        SecurityContextHolder.setContext(ctx);

        // Standard save behavior: set ID if missing
        lenient().when(stagingRepository.save(any(TempleProfileStaging.class))).thenAnswer(invocation -> {
            TempleProfileStaging s = invocation.getArgument(0);
            if (s.getId() == null) s.setId(100L);
            return s;
        });
    }

    private WorkflowInstance mockWorkflow(WorkflowStatus status, int version) {
        WorkflowInstance instance = WorkflowInstance.builder()
                .id(999L)
                .status(status)
                .versionNumber(version)
                .lockVersion(1L)
                .submittedAt(Instant.now())
                .statusUpdatedAt(Instant.now())
                .build();
        lenient().when(workflowEngine.getState(any(), any())).thenReturn(instance);
        return instance;
    }

    @Test
    void should_throw_when_createOrUpdateDraft_called_on_SUSPENDED_temple() {
        when(templeRepository.findById(1L)).thenReturn(Optional.of(suspendedTemple));

        assertThatThrownBy(() -> stagingService.createOrUpdateDraft(1L, new CreateTempleProfileStagingRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED");
    }

    @Test
    void should_throw_when_submitForReview_called_on_SUBMITTED_staging_exists() {
        when(templeRepository.findById(2L)).thenReturn(Optional.of(activeTemple));
        TempleProfileStaging pending = TempleProfileStaging.builder().templeId(2L).build();
        pending.setId(101L);
        // Implementation checks for pending DC-review states via findTopByTempleIdAndStatusInOrderByVersionNumberDesc
        when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(2L), argThat(list -> list.contains(WorkflowStatus.SUBMITTED))))
                .thenReturn(Optional.of(pending));
        // Make workflowEngine.getState return a SUBMITTED instance (used in the error message)
        lenient().when(workflowEngine.getState(any(), eq(101L))).thenReturn(
                WorkflowInstance.builder().id(999L).status(WorkflowStatus.SUBMITTED).build());

        assertThatThrownBy(() -> stagingService.createOrUpdateDraft(2L, new CreateTempleProfileStagingRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUBMITTED");
    }

    @Test
    void should_delegate_to_workflow_engine_when_submitForReview() {
        Temple temple3 = Temple.builder().id(3L).districtId(10L).status(TempleStatus.ACTIVE).build();
        when(templeRepository.findById(3L)).thenReturn(Optional.of(temple3));
        TempleProfileStaging draft = TempleProfileStaging.builder().templeId(3L).build();
        draft.setId(102L);
        
        when(stagingRepository.findFirstByTempleIdAndStatus(3L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.of(draft));
        // No pending review blocking (returns empty)
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(3L), any())).thenReturn(Optional.empty());
        
        WorkflowInstance instance = mockWorkflow(WorkflowStatus.DRAFT, 1);

        stagingService.submitForReview(3L);

        verify(workflowEngineAdaptor).adaptSubmit(
                eq(WorkflowEntityType.TEMPLE_PROFILE),
                eq(102L),
                eq(3L),
                eq(10L),
                eq(42L));
        verify(versionService).snapshot(eq(WorkflowEntityType.TEMPLE_PROFILE), eq(102L), eq(1), eq(draft), eq(42L), isNull());
    }

    @Test
    void should_approve_staging_and_supersede_previous_via_system_action() {
        // This test validates TempleProfileWorkflowServiceImpl.approveProfile(),
        // which is now the canonical approval path. Coverage exists in
        // TempleProfileWorkflowServiceImplTest. This placeholder is kept to
        // document that staging.approve() has been intentionally removed from
        // the TempleProfileStagingService contract (dual-path elimination).
        // The method no longer exists on TempleProfileStagingService.
    }

    @Test
    void should_reject_staging_via_workflow_engine() {
        // Similarly, staging.reject() has been removed from TempleProfileStagingService.
        // Full coverage for the reject path lives in TempleProfileWorkflowServiceImplTest.
    }

    // ── Location metadata (V97) ───────────────────────────────────────────────

    @Test
    void should_apply_placeId_and_formattedAddress_when_provided() {
        when(templeRepository.findById(1L)).thenReturn(Optional.of(activeTemple));
        when(stagingRepository.findFirstByTempleIdAndStatus(1L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.empty());
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(1L), any())).thenReturn(Optional.empty());
        when(stagingRepository.findMaxVersionNumberByTempleId(1L)).thenReturn(Optional.of(0));
        mockWorkflow(WorkflowStatus.DRAFT, 1);

        CreateTempleProfileStagingRequest request = CreateTempleProfileStagingRequest.builder()
                .placeId("ChIJ21P2rgVRrhkRjIgqmoQ0pIE")
                .formattedAddress("ISKCON Temple, Rajajinagar, Bengaluru, Karnataka 560010, India")
                .build();

        stagingService.createOrUpdateDraft(1L, request);

        verify(stagingRepository).save(argThat(s ->
                "ChIJ21P2rgVRrhkRjIgqmoQ0pIE".equals(s.getPlaceId()) &&
                s.getFormattedAddress().contains("ISKCON")));
    }

    @Test
    void should_not_overwrite_placeId_when_null_in_request() {
        TempleProfileStaging existing = TempleProfileStaging.builder()
                .templeId(1L)
                .placeId("existing-place-id")
                .build();
        existing.setId(200L);

        when(templeRepository.findById(1L)).thenReturn(Optional.of(activeTemple));
        when(stagingRepository.findFirstByTempleIdAndStatus(1L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.of(existing));
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(1L), any())).thenReturn(Optional.empty());
        mockWorkflow(WorkflowStatus.DRAFT, 1);

        // No placeId in request — existing value must be preserved
        CreateTempleProfileStagingRequest request = CreateTempleProfileStagingRequest.builder()
                .phone("9876543210")
                .build();

        stagingService.createOrUpdateDraft(1L, request);

        verify(stagingRepository).save(argThat(s -> "existing-place-id".equals(s.getPlaceId())));
    }

    // ── Taluk ID: independent field, not purely hobli-derived (bug repro) ──
    //
    // Expected: a TA can persist a Taluk selection even when no Hobli exists yet for
    // their location (a real geo-master-data gap) — talukId must be an independently
    // settable/persistable column on the staging row, mirroring how hobliId/districtId
    // already work on every other temple write path (TempleServiceImpl.applyUpdates,
    // RegistrationServiceImpl).
    //
    // Actual (bug, before V117): TempleProfileStaging had no talukId column at all, so
    // an explicitly submitted talukId was silently dropped — never bound to anything.

    @Test
    void should_persist_talukId_independently_of_hobliId() {
        when(templeRepository.findById(1L)).thenReturn(Optional.of(activeTemple));
        when(stagingRepository.findFirstByTempleIdAndStatus(1L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.empty());
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(1L), any())).thenReturn(Optional.empty());
        when(stagingRepository.findMaxVersionNumberByTempleId(1L)).thenReturn(Optional.of(0));
        mockWorkflow(WorkflowStatus.DRAFT, 1);

        // No hobliId at all — simulates a location with no hobli yet in the master geo data.
        CreateTempleProfileStagingRequest request = CreateTempleProfileStagingRequest.builder()
                .talukId(77L)
                .build();

        stagingService.createOrUpdateDraft(1L, request);

        verify(stagingRepository).save(argThat(s ->
                Long.valueOf(77L).equals(s.getTalukId()) && s.getHobliId() == null));
    }

    @Test
    void should_not_overwrite_talukId_when_null_in_request() {
        TempleProfileStaging existing = TempleProfileStaging.builder()
                .templeId(1L)
                .talukId(9L)
                .build();
        existing.setId(201L);

        when(templeRepository.findById(1L)).thenReturn(Optional.of(activeTemple));
        when(stagingRepository.findFirstByTempleIdAndStatus(1L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.of(existing));
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(1L), any())).thenReturn(Optional.empty());
        mockWorkflow(WorkflowStatus.DRAFT, 1);

        CreateTempleProfileStagingRequest request = CreateTempleProfileStagingRequest.builder()
                .phone("9876543210")
                .build();

        stagingService.createOrUpdateDraft(1L, request);

        verify(stagingRepository).save(argThat(s -> Long.valueOf(9L).equals(s.getTalukId())));
    }

    @Test
    void response_should_prefer_stored_talukId_over_hobli_derivation() {
        TempleProfileStaging existing = TempleProfileStaging.builder()
                .templeId(1L)
                .hobliId(10L)
                .talukId(5L)
                .build();
        existing.setId(202L);

        when(templeRepository.findById(1L)).thenReturn(Optional.of(activeTemple));
        when(stagingRepository.findFirstByTempleIdAndStatus(1L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.of(existing));
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(1L), any())).thenReturn(Optional.empty());
        mockWorkflow(WorkflowStatus.DRAFT, 1);

        TempleProfileStagingResponse response =
                stagingService.createOrUpdateDraft(1L, CreateTempleProfileStagingRequest.builder().build());

        assertThat(response.getTalukId()).isEqualTo(5L);
        verifyNoInteractions(hobliRepository);
    }

    @Test
    void response_should_derive_talukId_from_hobli_when_not_stored() {
        TempleProfileStaging existing = TempleProfileStaging.builder()
                .templeId(1L)
                .hobliId(10L)
                .build();
        existing.setId(203L);

        when(templeRepository.findById(1L)).thenReturn(Optional.of(activeTemple));
        when(stagingRepository.findFirstByTempleIdAndStatus(1L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.of(existing));
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(1L), any())).thenReturn(Optional.empty());
        when(hobliRepository.findTalukIdById(10L)).thenReturn(Optional.of(4L));
        mockWorkflow(WorkflowStatus.DRAFT, 1);

        TempleProfileStagingResponse response =
                stagingService.createOrUpdateDraft(1L, CreateTempleProfileStagingRequest.builder().build());

        assertThat(response.getTalukId()).isEqualTo(4L);
    }

    // ── AccessGuard: VIEW-only enforcement ──────────────────────────────────

    @Test
    void should_reject_createOrUpdateDraft_when_user_has_VIEW_access() {
        doThrow(new org.springframework.security.access.AccessDeniedException("VIEW-only"))
                .when(accessGuard).assertCanEdit();

        assertThatThrownBy(() -> stagingService.createOrUpdateDraft(1L, new CreateTempleProfileStagingRequest()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("VIEW-only");

        verifyNoInteractions(templeRepository);
        verifyNoInteractions(stagingRepository);
    }

    @Test
    void should_reject_submitForReview_when_user_has_VIEW_access() {
        doThrow(new org.springframework.security.access.AccessDeniedException("VIEW-only"))
                .when(accessGuard).assertCanEdit();

        assertThatThrownBy(() -> stagingService.submitForReview(1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("VIEW-only");

        verifyNoInteractions(templeRepository);
        verifyNoInteractions(stagingRepository);
    }

    @Test
    void should_allow_createOrUpdateDraft_when_user_has_EDIT_access() {
        doNothing().when(accessGuard).assertCanEdit();
        when(templeRepository.findById(1L)).thenReturn(Optional.of(activeTemple));
        when(stagingRepository.findFirstByTempleIdAndStatus(1L, WorkflowStatus.DRAFT))
                .thenReturn(Optional.empty());
        lenient().when(stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(1L), any())).thenReturn(Optional.empty());
        when(stagingRepository.findMaxVersionNumberByTempleId(1L)).thenReturn(Optional.of(0));
        mockWorkflow(WorkflowStatus.DRAFT, 1);

        stagingService.createOrUpdateDraft(1L, new CreateTempleProfileStagingRequest());

        verify(accessGuard).assertCanEdit();
        verify(stagingRepository).save(any(TempleProfileStaging.class));
    }
}

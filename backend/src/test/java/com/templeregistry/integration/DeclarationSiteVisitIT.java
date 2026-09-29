package com.templeregistry.integration;

import com.templeregistry.dto.request.dc.WorkflowApproveRequest;
import com.templeregistry.dto.request.declaration.CreateDeclarationRequest;
import com.templeregistry.dto.request.governance.SiteVisitRequest;
import com.templeregistry.dto.response.declaration.CompleteDeclarationResponse;
import com.templeregistry.entity.declaration.AssetDeclaration;
import com.templeregistry.entity.declaration.DeclarationStatus;
import com.templeregistry.entity.geo.City;
import com.templeregistry.entity.geo.District;
import com.templeregistry.entity.geo.Hobli;
import com.templeregistry.entity.geo.State;
import com.templeregistry.entity.geo.Taluk;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleGrade;
import com.templeregistry.entity.temple.ReligiousTradition;
import com.templeregistry.entity.versioning.EntityVersion;
import com.templeregistry.entity.versioning.EntityVersionStatus;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.repository.audit.GovernanceActionRepository;
import com.templeregistry.repository.declaration.DeclarationRepository;
import com.templeregistry.repository.versioning.EntityVersionRepository;
import com.templeregistry.repository.geo.CityRepository;
import com.templeregistry.repository.geo.DistrictRepository;
import com.templeregistry.repository.geo.HobliRepository;
import com.templeregistry.repository.geo.StateRepository;
import com.templeregistry.repository.geo.TalukRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.declaration.DeclarationService;
import com.templeregistry.service.governance.GovernanceWorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: full site visit workflow
 * DRAFT â†’ SUBMITTED â†’ SITE_VISIT_SCHEDULED â†’ SITE_VISIT_COMPLETED â†’ VERIFIED â†’ APPROVED
 *
 * Asserts:
 * - Status at each step: SUBMITTED â†’ SITE_VISIT_SCHEDULED â†’ SITE_VISIT_COMPLETED â†’ VERIFIED â†’ APPROVED
 * - Snapshot count = 5 (submit + scheduleSiteVisit + completeSiteVisit + verify + approve)
 *
 * Testcontainers MySQL base resolves H2 incompatibility â€” see {@link MySQLContainerBase}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class DeclarationSiteVisitIT extends MySQLContainerBase {

    @Autowired
    private DeclarationService declarationService;

    @Autowired
    private GovernanceWorkflowService governanceWorkflowService;

    @Autowired
    private TempleRepository templeRepository;

    @Autowired
    private DeclarationRepository declarationRepository;

    @Autowired
    private EntityVersionRepository versionRepository;

    @Autowired
    private GovernanceActionRepository governanceActionRepository;

    @Autowired
    private StateRepository stateRepository;

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private DistrictRepository districtRepository;

    @Autowired
    private TalukRepository talukRepository;

    @Autowired
    private HobliRepository hobliRepository;

    private Long templeId;
    private Long districtId;
    private ScopeHelper.Claims taClaims;
    private ScopeHelper.Claims dcClaims;

    @BeforeEach
    void setUp() {
        governanceActionRepository.deleteAll();
        versionRepository.deleteAll();
        declarationRepository.deleteAll();
        templeRepository.deleteAll();
        hardDeleteGeoHierarchy();

        // Set up bootstrap security context for JPA auditing
        ScopeHelper.Claims bootstrapClaims = new ScopeHelper.Claims(1L, "TEMPLE_AUTHORITY", null, null, "ta_user", "EDIT");
        setSecurityContext(bootstrapClaims);

        Hobli hobli = createGeoHierarchy();
        districtId = hobli.getTaluk().getDistrict().getId();

        Temple temple = createTemple(hobli);
        templeId = temple.getId();

        taClaims = new ScopeHelper.Claims(1L, "TEMPLE_AUTHORITY", null, templeId, "ta_user", "EDIT");
        dcClaims = new ScopeHelper.Claims(2L, "DISTRICT_COLLECTOR", districtId, null, "dc_user", "EDIT");

        setSecurityContext(taClaims);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fullSiteVisitFlow_assertStatusAtEachStepAndSnapshotCount() {
        // Step 1: Create DRAFT
        CompleteDeclarationResponse created = createDraftDeclaration(templeId);
        Long declarationId = created.getId();

        AssetDeclaration afterCreate = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterCreate.getStatus()).isEqualTo(DeclarationStatus.DRAFT);

        // Step 2: Submit (TA)
        governanceWorkflowService.submitDeclaration(declarationId);

        AssetDeclaration afterSubmit = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterSubmit.getStatus()).isEqualTo(DeclarationStatus.SUBMITTED);

        // Step 3: Mark under review (DC) — required before SCHEDULE_SITE_VISIT per TransitionRuleRegistry
        setSecurityContext(dcClaims);
        governanceWorkflowService.markUnderReview(declarationId, dcClaims);

        AssetDeclaration afterUnderReview = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterUnderReview.getStatus()).isEqualTo(DeclarationStatus.UNDER_REVIEW);

        // Step 4: Schedule site visit (DC)
        SiteVisitRequest siteVisitRequest = new SiteVisitRequest("Scheduled for inspection");
        governanceWorkflowService.scheduleSiteVisit(declarationId, siteVisitRequest, dcClaims);

        AssetDeclaration afterSchedule = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterSchedule.getStatus()).isEqualTo(DeclarationStatus.SITE_VISIT_SCHEDULED);
        // Regression: physical_verification_status must persist and round-trip the full 34-char
        // enum name without truncation (V114 widened the column from VARCHAR(30) to VARCHAR(50)).
        assertThat(afterSchedule.getPhysicalVerificationStatus())
                .isEqualTo(com.templeregistry.entity.governance.PhysicalVerificationStatus.ORDERED_FOR_PHYSICAL_VERIFICATION);

        // Step 5: Complete site visit (DC)
        governanceWorkflowService.completeSiteVisit(declarationId, dcClaims);

        AssetDeclaration afterComplete = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterComplete.getStatus()).isEqualTo(DeclarationStatus.SITE_VISIT_COMPLETED);

        // Step 6: Verify declaration (DC)
        governanceWorkflowService.verifyDeclaration(declarationId, dcClaims);

        AssetDeclaration afterVerify = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterVerify.getStatus()).isEqualTo(DeclarationStatus.VERIFIED);

        // Step 7: Approve (DC)
        WorkflowApproveRequest approveRequest = new WorkflowApproveRequest();
        governanceWorkflowService.approveDeclaration(declarationId, approveRequest, dcClaims);

        AssetDeclaration afterApprove = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterApprove.getStatus()).isEqualTo(DeclarationStatus.APPROVED);

        // Assert snapshot count = 6:
        // v1 is the bootstrap DRAFT_OVERLAY row WorkflowEngineImpl.initiate() writes when the
        // WorkflowInstance is first created (at declaration creation) — EntityVersion rows are
        // immutable and append-only (never updated/replaced; version_number/snapshot_json are
        // updatable=false), so this row is never consumed by later snapshots, only superseded by
        // higher version numbers. v2-v6 are the five explicit VersionService.snapshot() calls
        // this flow triggers: submit, scheduleSiteVisit, completeSiteVisit, verify, approve.
        List<EntityVersion> versions = versionRepository
                .findAllByEntityTypeAndEntityIdOrderByVersionNumberDesc(WorkflowEntityType.DECLARATION.name(), declarationId);
        assertThat(versions).hasSize(6);
        assertThat(versions).extracting(EntityVersion::getVersionNumber)
                .containsExactly(6, 5, 4, 3, 2, 1); // DESC order

        EntityVersion bootstrap = versions.get(versions.size() - 1);
        assertThat(bootstrap.getVersionNumber()).isEqualTo(1);
        assertThat(bootstrap.getStatus()).isEqualTo(EntityVersionStatus.DRAFT_OVERLAY);
    }

    // â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Creates the full geo hierarchy: State â†’ City â†’ District â†’ Taluk â†’ Hobli.
     */
    private Hobli createGeoHierarchy() {
        State state = stateRepository.save(State.builder()
                .name("Test State")
                .code("TS")
                .build());

        City city = cityRepository.save(City.builder()
                .state(state)
                .name("Test City")
                .build());

        District district = districtRepository.save(District.builder()
                .city(city)
                .name("Test District")
                .code("TD")
                .build());

        Taluk taluk = talukRepository.save(Taluk.builder()
                .district(district)
                .name("Test Taluk")
                .build());

        return hobliRepository.save(Hobli.builder()
                .taluk(taluk)
                .name("Test Hobli")
                .build());
    }

    private Temple createTemple(Hobli hobli) {
        Temple temple = Temple.builder()
                .name("Site Visit Temple")
                .registrationNumber("REG-SV-001")
                .grade(TempleGrade.A)
                .tradition(ReligiousTradition.OTHER)
                .doorNumber("3")
                .street("Inspection Lane")
                .villageTown("Visitville")
                .pinCode("560003")
                .districtId(hobli.getTaluk().getDistrict().getId())
                .hobliId(hobli.getId())
                .primaryDeity("Ganesha")
                .build();
        return templeRepository.save(temple);
    }

    private CompleteDeclarationResponse createDraftDeclaration(Long templeId) {
        CreateDeclarationRequest request = new CreateDeclarationRequest();
        request.setFinancialYear("2025-26");
        request.setDueDate(LocalDate.now().plusDays(30));
        return declarationService.create(templeId, request);
    }

    private void setSecurityContext(ScopeHelper.Claims claims) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, List.of(new SimpleGrantedAuthority("ROLE_" + claims.role()))));
    }
}

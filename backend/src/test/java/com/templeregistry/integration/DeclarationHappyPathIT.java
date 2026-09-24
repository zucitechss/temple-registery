package com.templeregistry.integration;

import com.templeregistry.dto.request.dc.WorkflowApproveRequest;
import com.templeregistry.dto.request.declaration.CreateDeclarationRequest;
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
 * Integration test: full happy-path workflow
 * DRAFT â†’ SUBMITTED â†’ UNDER_REVIEW â†’ APPROVED
 *
 * Asserts:
 * - Status at each step
 * - Acknowledgement number is non-null and matches ACK-.* pattern after approve
 * - Snapshot count increases at submit and approve (at least 2 versions)
 * - Audit log entries exist for SUBMIT, UNDER_REVIEW, APPROVED actions
 *
 * Disabled because H2 does not support TINYINT(1) column definitions used in entities.
 * Testcontainers MySQL base resolves this â€” see {@link MySQLContainerBase}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class DeclarationHappyPathIT extends MySQLContainerBase {

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
        hobliRepository.deleteAll();
        talukRepository.deleteAll();
        districtRepository.deleteAll();
        cityRepository.deleteAll();
        stateRepository.deleteAll();

        // Set up TA security context first (needed for JPA auditing)
        ScopeHelper.Claims bootstrapClaims = new ScopeHelper.Claims(1L, "TEMPLE_AUTHORITY", null, null, "ta_user", "EDIT");
        setSecurityContext(bootstrapClaims);

        // Create geo hierarchy: State â†’ City â†’ District â†’ Taluk â†’ Hobli
        Hobli hobli = createGeoHierarchy();
        districtId = hobli.getTaluk().getDistrict().getId();

        Temple temple = createTemple(hobli);
        templeId = temple.getId();

        taClaims = new ScopeHelper.Claims(1L, "TEMPLE_AUTHORITY", null, templeId, "ta_user", "EDIT");
        dcClaims = new ScopeHelper.Claims(2L, "DISTRICT_COLLECTOR", districtId, null, "dc_user", "EDIT");

        // Set TA security context
        setSecurityContext(taClaims);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fullHappyPath_draftToApproved_assertStatusAndAckAndSnapshotsAndAudit() {
        // Step 1: Create DRAFT
        CompleteDeclarationResponse created = createDraftDeclaration(templeId);
        Long declarationId = created.getId();

        AssetDeclaration afterCreate = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterCreate.getStatus()).isEqualTo(DeclarationStatus.DRAFT);

        // Step 2: Submit (TA action)
        governanceWorkflowService.submitDeclaration(declarationId);

        AssetDeclaration afterSubmit = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterSubmit.getStatus()).isEqualTo(DeclarationStatus.SUBMITTED);

        // Assert snapshot created at submit
        int versionsAfterSubmit = versionRepository
                .findAllByEntityTypeAndEntityIdOrderByVersionNumberDesc(WorkflowEntityType.DECLARATION.name(), declarationId)
                .size();
        assertThat(versionsAfterSubmit).isGreaterThanOrEqualTo(1);

        // Step 3: Mark under review (DC action)
        setSecurityContext(dcClaims);
        governanceWorkflowService.markUnderReview(declarationId, dcClaims);

        AssetDeclaration afterUnderReview = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterUnderReview.getStatus()).isEqualTo(DeclarationStatus.UNDER_REVIEW);

        // Step 4: Approve (DC action)
        WorkflowApproveRequest approveRequest = new WorkflowApproveRequest();
        governanceWorkflowService.approveDeclaration(declarationId, approveRequest, dcClaims);

        AssetDeclaration afterApprove = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterApprove.getStatus()).isEqualTo(DeclarationStatus.APPROVED);

        // Assert acknowledgement number is non-null and matches ACK-* pattern
        assertThat(afterApprove.getAcknowledgementNumber()).isNotNull();
        assertThat(afterApprove.getAcknowledgementNumber()).matches("ACK-.*");

        // Assert snapshot count increased at submit and approve (at least 2 versions)
        List<?> versions = versionRepository
                .findAllByEntityTypeAndEntityIdOrderByVersionNumberDesc(WorkflowEntityType.DECLARATION.name(), declarationId);
        assertThat(versions).hasSizeGreaterThanOrEqualTo(2);

        // Assert audit log entries exist for SUBMIT, UNDER_REVIEW, APPROVED
        var auditEntries = governanceActionRepository
                .findByEntityTypeAndEntityIdOrderByTimestampAsc("DECLARATION", declarationId);
        assertThat(auditEntries).isNotEmpty();

        List<String> actions = auditEntries.stream()
                .map(e -> e.getAction())
                .toList();
        assertThat(actions).anyMatch(a -> a.equalsIgnoreCase("SUBMIT") || a.contains("SUBMIT"));
        assertThat(actions).anyMatch(a -> a.equalsIgnoreCase("BEGIN_REVIEW") || a.contains("BEGIN_REVIEW"));
        assertThat(actions).anyMatch(a -> a.equalsIgnoreCase("APPROVE") || a.contains("APPROVE"));
    }

    // â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Creates the full geo hierarchy: State â†’ City â†’ District â†’ Taluk â†’ Hobli.
     * Returns the Hobli (leaf node) with all parent references loaded.
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
                .name("Happy Path Temple")
                .registrationNumber("REG-HP-001")
                .grade(TempleGrade.A)
                .tradition(ReligiousTradition.OTHER)
                .doorNumber("1")
                .street("Main St")
                .villageTown("Testville")
                .pinCode("560001")
                .districtId(hobli.getTaluk().getDistrict().getId())
                .hobliId(hobli.getId())
                .primaryDeity("Shiva")
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

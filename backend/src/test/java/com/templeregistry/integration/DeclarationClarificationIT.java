package com.templeregistry.integration;

import com.templeregistry.dto.request.dc.DcClarifyRequest;
import com.templeregistry.dto.request.dc.WorkflowApproveRequest;
import com.templeregistry.dto.request.declaration.ClarificationRespondRequest;
import com.templeregistry.dto.request.declaration.CreateDeclarationRequest;
import com.templeregistry.dto.response.declaration.CompleteDeclarationResponse;
import com.templeregistry.entity.declaration.AssetDeclaration;
import com.templeregistry.entity.declaration.ClarificationDirection;
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
import com.templeregistry.repository.declaration.DeclarationClarificationRepository;
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

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: full clarification cycle
 * DRAFT â†’ SUBMITTED â†’ CLARIFICATION_REQUIRED â†’ CLARIFICATION_RESPONDED â†’ APPROVED
 *
 * Asserts:
 * - clarification_round = 1 after requestClarification
 * - Two clarification records exist (one DC_TO_TEMPLE, one TEMPLE_TO_DC)
 * - Snapshot created at respondToClarification and approve
 * - Status transitions correctly at each step
 *
 * Testcontainers MySQL base resolves H2 incompatibility â€” see {@link MySQLContainerBase}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class DeclarationClarificationIT extends MySQLContainerBase {

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
    private DeclarationClarificationRepository clarificationRepository;

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
        clarificationRepository.deleteAll();
        declarationRepository.deleteAll();
        templeRepository.deleteAll();
        hobliRepository.deleteAll();
        talukRepository.deleteAll();
        districtRepository.deleteAll();
        cityRepository.deleteAll();
        stateRepository.deleteAll();

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
    void fullClarificationCycle_assertRoundCountAndRecordsAndSnapshots() {
        // Step 1: Create DRAFT
        CompleteDeclarationResponse created = createDraftDeclaration(templeId);
        Long declarationId = created.getId();

        // Step 2: Submit (TA)
        governanceWorkflowService.submitDeclaration(declarationId);

        AssetDeclaration afterSubmit = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterSubmit.getStatus()).isEqualTo(DeclarationStatus.SUBMITTED);

        // Step 3: Request clarification (DC)
        setSecurityContext(dcClaims);
        DcClarifyRequest clarifyRequest = buildDcClarifyRequest("Please clarify assets details");
        governanceWorkflowService.requestClarification(declarationId, clarifyRequest, dcClaims);

        AssetDeclaration afterClarify = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterClarify.getStatus()).isEqualTo(DeclarationStatus.CLARIFICATION_REQUIRED);

        // Assert clarification_round = 1
        assertThat(afterClarify.getClarificationRound()).isEqualTo(1);

        // Step 4: Respond to clarification (TA)
        setSecurityContext(taClaims);
        ClarificationRespondRequest respondRequest = new ClarificationRespondRequest("Clarification provided");
        declarationService.respondToClarification(declarationId, respondRequest, taClaims.userId(), taClaims.role());

        AssetDeclaration afterRespond = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterRespond.getStatus()).isEqualTo(DeclarationStatus.CLARIFICATION_RESPONDED);

        // Assert snapshot created at respondToClarification
        int versionsAfterRespond = versionRepository
                .findAllByEntityTypeAndEntityIdOrderByVersionNumberDesc(WorkflowEntityType.DECLARATION.name(), declarationId)
                .size();
        assertThat(versionsAfterRespond).isGreaterThanOrEqualTo(2); // submit + respond

        // Assert two clarification records exist: one DC_TO_TEMPLE, one TEMPLE_TO_DC
        var clarifications = clarificationRepository.findAllByDeclarationIdOrderByCreatedAtAsc(declarationId);
        assertThat(clarifications).hasSize(2);

        long dcToTempleCount = clarifications.stream()
                .filter(c -> c.getDirection() == ClarificationDirection.DC_TO_TEMPLE)
                .count();
        long templeToDcCount = clarifications.stream()
                .filter(c -> c.getDirection() == ClarificationDirection.TEMPLE_TO_DC)
                .count();
        assertThat(dcToTempleCount).isEqualTo(1);
        assertThat(templeToDcCount).isEqualTo(1);

        // Step 5: Approve (DC)
        setSecurityContext(dcClaims);
        WorkflowApproveRequest approveRequest = new WorkflowApproveRequest();
        governanceWorkflowService.approveDeclaration(declarationId, approveRequest, dcClaims);

        AssetDeclaration afterApprove = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterApprove.getStatus()).isEqualTo(DeclarationStatus.APPROVED);

        // Assert snapshot created at approve
        int versionsAfterApprove = versionRepository
                .findAllByEntityTypeAndEntityIdOrderByVersionNumberDesc(WorkflowEntityType.DECLARATION.name(), declarationId)
                .size();
        assertThat(versionsAfterApprove).isGreaterThan(versionsAfterRespond);
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
                .name("Clarification Temple")
                .registrationNumber("REG-CL-001")
                .grade(TempleGrade.B)
                .tradition(ReligiousTradition.OTHER)
                .doorNumber("2")
                .street("Temple Road")
                .villageTown("Clarityville")
                .pinCode("560002")
                .districtId(hobli.getTaluk().getDistrict().getId())
                .hobliId(hobli.getId())
                .primaryDeity("Vishnu")
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

    /**
     * Builds a DcClarifyRequest using reflection since the class only has
     * {@code @Getter @NoArgsConstructor} (no builder or setter).
     */
    private DcClarifyRequest buildDcClarifyRequest(String message) {
        DcClarifyRequest request = new DcClarifyRequest();
        try {
            Field field = DcClarifyRequest.class.getDeclaredField("message");
            field.setAccessible(true);
            field.set(request, message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set DcClarifyRequest.message", e);
        }
        return request;
    }
}

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
import com.templeregistry.repository.audit.GovernanceActionRepository;
import com.templeregistry.repository.declaration.AssetDeclarationVersionRepository;
import com.templeregistry.repository.declaration.DeclarationRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: concurrent approve requests on the same declaration.
 *
 * Asserts:
 * - Exactly one approve succeeds (no exception)
 * - The other throws an exception (optimistic locking or state transition violation)
 * - Final status is APPROVED
 *
 * Testcontainers MySQL base resolves H2 incompatibility â€” see {@link MySQLContainerBase}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class DeclarationConcurrencyIT extends MySQLContainerBase {

    @Autowired
    private DeclarationService declarationService;

    @Autowired
    private GovernanceWorkflowService governanceWorkflowService;

    @Autowired
    private TempleRepository templeRepository;

    @Autowired
    private DeclarationRepository declarationRepository;

    @Autowired
    private AssetDeclarationVersionRepository versionRepository;

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
    void concurrentApprove_exactlyOneSucceedsAndOtherThrows() throws Exception {
        // Set up declaration in SUBMITTED state
        CompleteDeclarationResponse created = createDraftDeclaration(templeId);
        Long declarationId = created.getId();

        governanceWorkflowService.submitDeclaration(declarationId);

        AssetDeclaration afterSubmit = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterSubmit.getStatus()).isEqualTo(DeclarationStatus.SUBMITTED);

        // Use two threads to call approveDeclaration concurrently
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        WorkflowApproveRequest approveRequest = new WorkflowApproveRequest();
        ScopeHelper.Claims dcClaimsForThread = dcClaims;

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Runnable approveTask = () -> {
            // Each thread sets its own security context (ThreadLocal)
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(dcClaimsForThread, null, List.of(new SimpleGrantedAuthority("ROLE_" + dcClaimsForThread.role()))));
            try {
                startLatch.await(); // wait for both threads to be ready
                governanceWorkflowService.approveDeclaration(declarationId, approveRequest, dcClaimsForThread);
                successCount.incrementAndGet();
            } catch (Exception e) {
                // Expected: one thread should fail with optimistic locking or state transition exception
                failureCount.incrementAndGet();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        Future<?> f1 = executor.submit(approveTask);
        Future<?> f2 = executor.submit(approveTask);

        // Release both threads simultaneously
        startLatch.countDown();

        f1.get();
        f2.get();
        executor.shutdown();

        // Assert exactly one succeeds and the other throws
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        // Assert final status is APPROVED
        AssetDeclaration finalDeclaration = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(finalDeclaration.getStatus()).isEqualTo(DeclarationStatus.APPROVED);
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
                .name("Concurrency Temple")
                .registrationNumber("REG-CC-001")
                .grade(TempleGrade.A)
                .tradition(ReligiousTradition.OTHER)
                .doorNumber("4")
                .street("Race Condition Road")
                .villageTown("Concurrentville")
                .pinCode("560004")
                .districtId(hobli.getTaluk().getDistrict().getId())
                .hobliId(hobli.getId())
                .primaryDeity("Murugan")
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

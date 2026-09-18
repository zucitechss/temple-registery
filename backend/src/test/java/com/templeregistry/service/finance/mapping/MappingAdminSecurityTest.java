package com.templeregistry.service.finance.mapping;

import com.templeregistry.dto.request.finance.CreateMappingRuleRequest;
import com.templeregistry.dto.request.finance.MappingRuleStatusRequest;
import com.templeregistry.dto.request.finance.UpdateMappingRuleRequest;
import com.templeregistry.dto.response.finance.MappingRuleResponse;
import com.templeregistry.dto.response.finance.SourceSystemSummaryResponse;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.temple.TempleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static com.templeregistry.service.finance.mapping.MappingAdminTestFixture.DISTRICT_A;
import static com.templeregistry.service.finance.mapping.MappingAdminTestFixture.DISTRICT_B;
import static com.templeregistry.service.finance.mapping.MappingAdminTestFixture.authenticateAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may administer mapping rules, and whose rules they may reach (FIN-054A).
 *
 * <p>This is the finance pipeline's first authorization surface, so every assertion here is made
 * by invoking the service as a role rather than by checking that an annotation is present. An
 * annotation that is present and not applied is exactly the failure worth catching.
 *
 * <p>Isolation is asserted against a second temple in a second district, because a single-temple
 * fixture passes every cross-temple test by having nothing to leak.
 */
@SpringBootTest
@ActiveProfiles("test")
class MappingAdminSecurityTest extends FinanceApiTestBase {

    @Autowired private MappingAdminService service;
    @Autowired private TempleRepository temples;
    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinMappingRuleRepository rules;
    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository decisions;
    @Autowired private FinSyncBatchRepository batches;

    private MappingAdminTestFixture fixture;
    private FinSourceSystem sourceInA;
    private FinSourceSystem sourceInB;
    private FinMappingRule ruleInB;

    @BeforeEach
    void setUp() {
        fixture = new MappingAdminTestFixture(
                temples, sourceSystems, rules, staging, decisions, batches);
        authenticateAs("SUPER_ADMIN", 5001L, null, null);

        Temple templeInA = fixture.temple("Temple in district A", DISTRICT_A);
        Temple templeInB = fixture.temple("Temple in district B", DISTRICT_B);
        sourceInA = fixture.sourceSystem(templeInA, code());
        sourceInB = fixture.sourceSystem(templeInB, code());
        fixture.rule(sourceInA.getId(), "SEVA_CODE:100", "SEVA", 100);
        ruleInB = fixture.rule(sourceInB.getId(), "SEVA_CODE:200", "SEVA", 100);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- who reads

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "DISTRICT_COLLECTOR", "DC_STAFF", "AUDITOR"})
    @DisplayName("The roles that administer the integration can read mapping rules")
    void should_allowRead_when_roleAdministersIntegration(String role) {
        authenticateAs(role, 5002L, DISTRICT_A, null);

        assertThat(service.listRules(sourceInA.getId(), null, null, null, null, 0, 20, null)
                .getTotalElements()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEMPLE_AUTHORITY", "VIEWER"})
    @DisplayName("Mapping configuration is not readable by the regulated party or by public browsing")
    void should_refuseRead_when_roleIsOutsideTheIntegration(String role) {
        authenticateAs(role, 5003L, DISTRICT_A, sourceInA.getTempleId());

        assertThatThrownBy(() -> service.listRules(
                sourceInA.getId(), null, null, null, null, 0, 20, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------ who writes

    @ParameterizedTest
    @ValueSource(strings = {"DC_STAFF", "AUDITOR", "TEMPLE_AUTHORITY", "VIEWER"})
    @DisplayName("A role that may read a rule still may not change what a temple's income is called")
    void should_refuseWrite_when_roleCannotActForTheDistrict(String role) {
        authenticateAs(role, 5004L, DISTRICT_A, sourceInA.getTempleId());
        CreateMappingRuleRequest create = create(sourceInA.getId(), "SEVA_CODE", "900");
        UpdateMappingRuleRequest update = update(0, "SEVA_CODE", "901");
        MappingRuleStatusRequest status = status(false, 0);
        long ruleId = rules.findBySourceSystemIdAndDeletedFalse(sourceInA.getId()).get(0).getId();

        assertThatThrownBy(() -> service.create(create)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(ruleId, update)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.setActive(ruleId, status)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "DISTRICT_COLLECTOR"})
    @DisplayName("The roles that act for a district can create a rule")
    void should_allowWrite_when_roleActsForTheDistrict(String role) {
        authenticateAs(role, 5005L, DISTRICT_A, null);

        MappingRuleResponse created =
                service.create(create(sourceInA.getId(), "SEVA_CODE", "9" + role.charAt(0))).rule();

        assertThat(created.id()).isNotNull();
    }

    // -------------------------------------------------------------- isolation

    @Test
    @DisplayName("A district collector cannot read another district's rules, and is told the source does not exist")
    void should_hideSourceSystem_when_itBelongsToAnotherDistrict() {
        authenticateAs("DISTRICT_COLLECTOR", 5006L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.listRules(
                sourceInB.getId(), null, null, null, null, 0, 20, null))
                .as("404, not 403 — a distinguishable refusal enumerates every temple's integrations")
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("A district collector cannot edit another district's rule by naming its id")
    void should_refuseWrite_when_ruleBelongsToAnotherDistrict() {
        authenticateAs("DISTRICT_COLLECTOR", 5007L, DISTRICT_A, null);
        UpdateMappingRuleRequest rq = update(ruleInB.getVersion(), "SEVA_CODE", "201");

        assertThatThrownBy(() -> service.update(ruleInB.getId(), rq))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(rules.findById(ruleInB.getId()).orElseThrow().getSourceValue())
                .isEqualTo("SEVA_CODE:200");
    }

    @Test
    @DisplayName("A rule cannot be created against another district's source system")
    void should_refuseCreate_when_sourceSystemBelongsToAnotherDistrict() {
        authenticateAs("DISTRICT_COLLECTOR", 5008L, DISTRICT_A, null);
        CreateMappingRuleRequest rq = create(sourceInB.getId(), "SEVA_CODE", "202");

        assertThatThrownBy(() -> service.create(rq)).isInstanceOf(EntityNotFoundException.class);
        assertThat(rules.findBySourceSystemIdAndDeletedFalse(sourceInB.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Unresolved values and namespaces are scoped the same way the rules are")
    void should_scopeEveryRead_when_sourceSystemBelongsToAnotherDistrict() {
        authenticateAs("DC_STAFF", 5009L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.listUnresolved(sourceInB.getId(), MappingOutcome.UNMAPPED))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.namespaces(sourceInB.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.getRule(ruleInB.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("The source system list shows a district collector only their own district")
    void should_listOnlyOwnDistrict_when_callerIsDistrictScoped() {
        authenticateAs("DISTRICT_COLLECTOR", 5010L, DISTRICT_A, null);

        assertThat(service.listSourceSystems())
                .extracting(SourceSystemSummaryResponse::id)
                .contains(sourceInA.getId())
                .doesNotContain(sourceInB.getId());
    }

    @Test
    @DisplayName("A statewide role sees both districts, because it is not district-scoped")
    void should_listEveryDistrict_when_callerIsStatewide() {
        authenticateAs("AUDITOR", 5011L, null, null);

        assertThat(service.listSourceSystems())
                .extracting(SourceSystemSummaryResponse::id)
                .contains(sourceInA.getId(), sourceInB.getId());
    }

    @Test
    @DisplayName("An auditor with no district claim is not treated as a corrupted token")
    void should_notFail_when_statewideRoleCarriesNoDistrict() {
        authenticateAs("AUDITOR", 5012L, null, null);

        // JurisdictionGuard.assertDistrictScope treats a null districtId on a non-SUPER_ADMIN,
        // non-TEMPLE_AUTHORITY, non-VIEWER role as a corrupted JWT. AUDITOR is statewide and
        // legitimately carries none, so the scope check must not be applied to it at all.
        assertThat(service.listRules(sourceInB.getId(), null, null, null, null, 0, 20, null)
                .getTotalElements()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private static String code() {
        return "SEC-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private CreateMappingRuleRequest create(Long sourceSystemId, String namespace, String value) {
        CreateMappingRuleRequest rq = new CreateMappingRuleRequest();
        rq.setSourceSystemId(sourceSystemId);
        rq.setMappingType(MappingType.REVENUE_CATEGORY);
        rq.setNamespace(namespace);
        rq.setSourceValue(value);
        rq.setCanonicalValue("SEVA");
        rq.setPriority(100);
        rq.setActive(true);
        return rq;
    }

    private UpdateMappingRuleRequest update(Integer version, String namespace, String value) {
        UpdateMappingRuleRequest rq = new UpdateMappingRuleRequest();
        rq.setVersion(version);
        rq.setNamespace(namespace);
        rq.setSourceValue(value);
        rq.setCanonicalValue("SEVA");
        rq.setPriority(100);
        rq.setActive(true);
        return rq;
    }

    private MappingRuleStatusRequest status(boolean active, Integer version) {
        MappingRuleStatusRequest rq = new MappingRuleStatusRequest();
        rq.setActive(active);
        rq.setVersion(version);
        return rq;
    }
}

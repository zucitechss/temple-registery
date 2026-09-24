package com.templeregistry.service.finance.onboarding;

import com.templeregistry.dto.request.finance.DeclareCapabilityRequest;
import com.templeregistry.dto.request.finance.DeclareSourceOfTruthRequest;
import com.templeregistry.dto.request.finance.RegisterSourceSystemRequest;
import com.templeregistry.dto.request.finance.SetSourceSystemActivationRequest;
import com.templeregistry.dto.request.finance.UpdateCapabilityDeclarationRequest;
import com.templeregistry.dto.request.finance.UpdateSourceSystemRequest;
import com.templeregistry.dto.response.finance.CapabilityDeclarationResponse;
import com.templeregistry.dto.response.finance.SourceSystemDetailResponse;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.RoleConstants;
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

import java.time.LocalDate;

import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.DISTRICT_A;
import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.authenticateAs;
import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.registration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may onboard a source system (FIN-140 slice 140-A).
 *
 * <p>Every assertion invokes the service as a role rather than checking that an annotation is
 * present, because an annotation that is present and not applied is exactly the failure worth
 * catching.
 *
 * <p>This surface is stricter than the Source Mapper's on purpose. A mapping rule says what a
 * value means; these rows say <b>which external database a temple's published figures come
 * from</b>, and they carry the connector bean, the source database name and the credential alias.
 * Platform administrator only, matching the precedent set by system configuration rather than the
 * one set by district governance actions.
 */
@SpringBootTest
@ActiveProfiles("test")
class SourceSystemAdminSecurityTest extends FinanceOnboardingTestBase {

    @Autowired private SourceSystemAdminService service;
    @Autowired private TempleRepository temples;
    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinTempleCapabilityRepository capabilities;
    @Autowired private FinSourceOfTruthDeclRepository declarations;
    @Autowired private FinMappingRuleRepository rules;

    private OnboardingTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new OnboardingTestFixture(temples, capabilities, declarations, rules);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} may not register a source system")
    @ValueSource(strings = {
            RoleConstants.DISTRICT_COLLECTOR,
            RoleConstants.DC_STAFF,
            RoleConstants.AUDITOR,
            RoleConstants.VIEWER,
            RoleConstants.TEMPLE_AUTHORITY})
    @DisplayName("Only a platform administrator may register a source system")
    void should_refuseRegistration_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Reg guard " + role, DISTRICT_A);

        authenticateAs(role, 2L, DISTRICT_A, null);
        RegisterSourceSystemRequest request = registration(temple.getId());

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} may not read source system detail")
    @ValueSource(strings = {
            RoleConstants.DISTRICT_COLLECTOR,
            RoleConstants.DC_STAFF,
            RoleConstants.AUDITOR,
            RoleConstants.VIEWER})
    @DisplayName("Connector bean, source database name and credential state are administrator-only")
    void should_refuseDetailRead_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Read guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.get(created.id()))
                .as("these fields describe how a temple's live system is reached; the four-role "
                        + "read surface is the Source Mapper's summary, which excludes them")
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} may not read readiness")
    @ValueSource(strings = {RoleConstants.DISTRICT_COLLECTOR, RoleConstants.AUDITOR})
    @DisplayName("Readiness is administrator-only in this slice")
    void should_refuseReadiness_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Readiness guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.readiness(created.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("A district collector may not update a source system")
    void should_refuseUpdate_when_callerIsNotSuperAdmin() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Update guard", DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(RoleConstants.DISTRICT_COLLECTOR, 2L, DISTRICT_A, null);
        UpdateSourceSystemRequest request = new UpdateSourceSystemRequest();
        request.setVersion(created.version());
        request.setSystemName("Renamed by a DC");
        request.setSourceTechnology(created.sourceTechnology());
        request.setConnectorType(created.connectorType());
        request.setConnectorBean(created.connectorBean());

        assertThatThrownBy(() -> service.update(created.id(), request))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -------------------------------------------------- capabilities (FIN-140-B)

    @ParameterizedTest(name = "{0} may not declare a capability")
    @ValueSource(strings = {
            RoleConstants.DISTRICT_COLLECTOR,
            RoleConstants.DC_STAFF,
            RoleConstants.AUDITOR,
            RoleConstants.VIEWER,
            RoleConstants.TEMPLE_AUTHORITY})
    @DisplayName("Only a platform administrator may declare what a temple can answer")
    void should_refuseDeclaration_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Cap guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);
        DeclareCapabilityRequest request = new DeclareCapabilityRequest();
        request.setCapability(FinanceCapability.REVENUE);
        request.setAvailability(DataAvailability.AVAILABLE);

        assertThatThrownBy(() -> service.declareCapability(created.id(), request))
                .as("this row decides whether a metric renders as a figure or as a reason")
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} may not read capability declarations here")
    @ValueSource(strings = {RoleConstants.DISTRICT_COLLECTOR, RoleConstants.AUDITOR})
    @DisplayName("The administrative capability list is administrator-only in this slice")
    void should_refuseCapabilityRead_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Cap read guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.listCapabilities(created.id()))
                .as("the dashboard's own capability endpoint remains open to four roles and "
                        + "returns no administrative field; this one is not that endpoint")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("A district collector may not revise a capability declaration")
    void should_refuseCapabilityUpdate_when_callerIsNotSuperAdmin() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Cap update guard", DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));
        DeclareCapabilityRequest declare = new DeclareCapabilityRequest();
        declare.setCapability(FinanceCapability.REVENUE);
        declare.setAvailability(DataAvailability.AVAILABLE);
        CapabilityDeclarationResponse declared = service.declareCapability(created.id(), declare);

        authenticateAs(RoleConstants.DISTRICT_COLLECTOR, 2L, DISTRICT_A, null);
        UpdateCapabilityDeclarationRequest request = new UpdateCapabilityDeclarationRequest();
        request.setVersion(declared.version());
        request.setAvailability(DataAvailability.NOT_AVAILABLE);
        request.setAvailabilityReason("Changed by a DC.");

        assertThatThrownBy(() -> service.updateCapability(created.id(), declared.id(), request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} may not read the capability catalogue")
    @ValueSource(strings = {RoleConstants.DC_STAFF, RoleConstants.VIEWER})
    @DisplayName("Even the vocabulary is administrator-only, matching the rest of this controller")
    void should_refuseCatalogue_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.capabilityCatalogue())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("No capability response carries a credential, connector bean or database name")
    void should_leakNoSourceConnectionDetail_when_declarationIsRead() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Cap redaction", DISTRICT_A);
        RegisterSourceSystemRequest registration = registration(temple.getId());
        registration.setCredentialRef("kollur-readonly");
        registration.setSourceDatabaseName("KOLSOHAM_LOCAL");
        SourceSystemDetailResponse created = service.register(registration);

        DeclareCapabilityRequest declare = new DeclareCapabilityRequest();
        declare.setCapability(FinanceCapability.REVENUE);
        declare.setAvailability(DataAvailability.AVAILABLE);
        String rendered = service.declareCapability(created.id(), declare).toString()
                + service.listCapabilities(created.id());

        assertThat(rendered)
                .as("a capability declaration says what a temple records, never how it is reached")
                .doesNotContain("kollur-readonly")
                .doesNotContain("KOLSOHAM_LOCAL")
                .doesNotContain("someFinanceConnector");
    }

    @Test
    @DisplayName("A platform administrator is not district-scoped")
    void should_allowAnyDistrict_when_callerIsSuperAdmin() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Statewide", OnboardingTestFixture.DISTRICT_B);

        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        assertThat(service.get(created.id()).templeId()).isEqualTo(temple.getId());
    }

    @Test
    @DisplayName("No existing source system is altered by registering a new one")
    void should_leaveExistingConfigurationUntouched_when_aNewSourceIsRegistered() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);

        // Stands in for the first onboarded source in an environment where V111 applied: this
        // slice must be additive, and the regression worth protecting is that onboarding temple
        // number two changes nothing about temple number one.
        var before = sourceSystems.findByDeletedFalse().stream()
                .map(source -> source.getId() + "|" + source.getSystemCode() + "|"
                        + source.getConnectorBean() + "|" + source.isSyncEnabled() + "|"
                        + source.getVersion())
                .sorted()
                .toList();

        Temple temple = fixture.temple("Newcomer", OnboardingTestFixture.DISTRICT_B);
        service.register(registration(temple.getId()));

        var after = sourceSystems.findByDeletedFalse().stream()
                .map(source -> source.getId() + "|" + source.getSystemCode() + "|"
                        + source.getConnectorBean() + "|" + source.isSyncEnabled() + "|"
                        + source.getVersion())
                .sorted()
                .toList();

        assertThat(after).containsAll(before);
        assertThat(after).hasSize(before.size() + 1);
    }

    // ------------------------------------------- source of truth (FIN-140-C)

    @ParameterizedTest(name = "{0} may not declare a source of truth")
    @ValueSource(strings = {
            RoleConstants.DISTRICT_COLLECTOR,
            RoleConstants.DC_STAFF,
            RoleConstants.AUDITOR,
            RoleConstants.VIEWER,
            RoleConstants.TEMPLE_AUTHORITY})
    @DisplayName("Only a platform administrator may declare which source field is authoritative")
    void should_refuseSourceOfTruthDeclaration_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("SoT guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.declareSourceOfTruth(created.id(), sourceOfTruthRequest()))
                .as("this row decides which column a temple's published revenue is read from")
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} may not read source-of-truth declarations")
    @ValueSource(strings = {
            RoleConstants.DISTRICT_COLLECTOR,
            RoleConstants.DC_STAFF,
            RoleConstants.AUDITOR,
            RoleConstants.VIEWER})
    @DisplayName("Source-of-truth declarations are administrator-only in this slice")
    void should_refuseSourceOfTruthRead_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("SoT read guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.listSourceOfTruth(created.id()))
                .as("a declaration names a table and a column in the temple's own system")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("No source-of-truth response carries a credential, connector bean or database name")
    void should_leakNoSourceConnectionDetail_when_sourceOfTruthIsRead() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("SoT redaction", DISTRICT_A);
        RegisterSourceSystemRequest registration = registration(temple.getId());
        registration.setCredentialRef("kollur-readonly");
        registration.setSourceDatabaseName("KOLSOHAM_LOCAL");
        SourceSystemDetailResponse created = service.register(registration);

        String rendered = service.declareSourceOfTruth(created.id(), sourceOfTruthRequest()).toString()
                + service.listSourceOfTruth(created.id());

        assertThat(rendered)
                .as("a declaration names the field inside the source, never the way in to it")
                .doesNotContain("kollur-readonly")
                .doesNotContain("KOLSOHAM_LOCAL")
                .doesNotContain("someFinanceConnector");
    }

    @Test
    @DisplayName("A source system that does not exist is reported absent rather than refused")
    void should_reportAbsent_when_declaringForAnUnknownSourceSystem() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);

        assertThatThrownBy(() -> service.declareSourceOfTruth(9_999_999L, sourceOfTruthRequest()))
                .as("a caller who can tell \"no such source\" from \"not yours\" can enumerate "
                        + "every temple's integrations one id at a time")
                .isInstanceOf(EntityNotFoundException.class);
    }

    /**
     * Declarations are reached only through their own source system.
     *
     * <p>Every write method is administrator-only today, so this cannot be a privilege escalation
     * yet. It is the isolation the model itself provides, asserted so that widening the role later
     * is a change to one annotation rather than a cross-temple leak.
     */
    @Test
    @DisplayName("One temple's declarations never appear under another temple's source system")
    void should_isolateAcrossTemples_when_listingSourceOfTruth() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple templeA = fixture.temple("SoT cross A", DISTRICT_A);
        Temple templeB = fixture.temple("SoT cross B", OnboardingTestFixture.DISTRICT_B);
        SourceSystemDetailResponse sourceA = service.register(registration(templeA.getId()));
        SourceSystemDetailResponse sourceB = service.register(registration(templeB.getId()));

        service.declareSourceOfTruth(sourceA.id(), sourceOfTruthRequest());

        assertThat(service.listSourceOfTruth(sourceB.id())).isEmpty();
    }

    private static DeclareSourceOfTruthRequest sourceOfTruthRequest() {
        DeclareSourceOfTruthRequest request = new DeclareSourceOfTruthRequest();
        request.setMetric("REVENUE_AMOUNT");
        request.setSourceObject("DailyReceipts");
        request.setSourceField("Amount");
        request.setEffectiveFrom(LocalDate.now());
        return request;
    }

    // ---------------------------------------------- activation (FIN-140-D)

    @ParameterizedTest(name = "{0} may not enable a source system for sync")
    @ValueSource(strings = {
            RoleConstants.DISTRICT_COLLECTOR,
            RoleConstants.DC_STAFF,
            RoleConstants.AUDITOR,
            RoleConstants.VIEWER,
            RoleConstants.TEMPLE_AUTHORITY})
    @DisplayName("Only a platform administrator may enable a source system")
    void should_refuseActivation_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Activation guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() -> service.setActivation(created.id(), activation(true, null)))
                .as("this decides whether the platform may reach into a temple's own system")
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} may not disable a source system")
    @ValueSource(strings = {
            RoleConstants.DISTRICT_COLLECTOR,
            RoleConstants.DC_STAFF,
            RoleConstants.AUDITOR,
            RoleConstants.VIEWER})
    @DisplayName("Only a platform administrator may disable a source system")
    void should_refuseDeactivation_when_callerIsNotSuperAdmin(String role) {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Deactivation guard " + role, DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(role, 2L, DISTRICT_A, null);

        assertThatThrownBy(() ->
                service.setActivation(created.id(), activation(false, "Turning it off.")))
                .as("cutting off a temple's financial data is as consequential as starting it")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("A refused caller changes no activation state")
    void should_leaveStateUntouched_when_activationIsRefused() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Activation state guard", DISTRICT_A);
        SourceSystemDetailResponse created = service.register(registration(temple.getId()));

        authenticateAs(RoleConstants.DISTRICT_COLLECTOR, 2L, DISTRICT_A, null);
        assertThatThrownBy(() -> service.setActivation(created.id(), activation(true, null)))
                .isInstanceOf(AccessDeniedException.class);

        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        assertThat(sourceSystems.findById(created.id()).orElseThrow().isSyncEnabled()).isFalse();
    }

    @Test
    @DisplayName("An activation response carries no credential, connector bean or database name")
    void should_leakNoSourceConnectionDetail_when_activationIsRead() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
        Temple temple = fixture.temple("Activation redaction", DISTRICT_A);
        RegisterSourceSystemRequest registration = registration(temple.getId());
        registration.setCredentialRef("kollur-readonly");
        registration.setSourceDatabaseName("KOLSOHAM_LOCAL");
        SourceSystemDetailResponse created = service.register(registration);

        // Refused while blocked, so read the disabled-state response instead: same payload shape.
        String rendered = service.setActivation(created.id(),
                activation(false, "Not being onboarded yet.")).toString();

        assertThat(rendered)
                .as("a permission flag says nothing about how the source is reached")
                .doesNotContain("kollur-readonly")
                .doesNotContain("KOLSOHAM_LOCAL")
                .doesNotContain("someFinanceConnector");
    }

    @Test
    @DisplayName("A source system that does not exist is reported absent rather than refused")
    void should_reportAbsent_when_activatingAnUnknownSourceSystem() {
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);

        assertThatThrownBy(() -> service.setActivation(9_999_999L, activation(true, null)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private static SetSourceSystemActivationRequest activation(boolean enabled, String reason) {
        SetSourceSystemActivationRequest request = new SetSourceSystemActivationRequest();
        request.setEnabled(enabled);
        request.setReason(reason);
        return request;
    }
}

package com.templeregistry.service.finance.onboarding;

import com.templeregistry.dto.request.finance.DeclareCapabilityRequest;
import com.templeregistry.dto.request.finance.DeclareSourceOfTruthRequest;
import com.templeregistry.dto.request.finance.RegisterSourceSystemRequest;
import com.templeregistry.dto.request.finance.SetSourceSystemActivationRequest;
import com.templeregistry.dto.request.finance.UpdateCapabilityDeclarationRequest;
import com.templeregistry.dto.request.finance.UpdateSourceSystemRequest;
import com.templeregistry.dto.response.finance.CapabilityCatalogueResponse;
import com.templeregistry.dto.response.finance.CapabilityDeclarationResponse;
import com.templeregistry.dto.response.finance.SourceOfTruthDeclarationResponse;
import com.templeregistry.dto.response.finance.SourceSystemActivationResponse;
import com.templeregistry.dto.response.finance.SourceSystemDetailResponse;
import com.templeregistry.dto.response.finance.SourceSystemReadinessResponse;
import com.templeregistry.entity.audit.AuditDataEvent;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.DuplicateResourceException;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.audit.AuditDataEventRepository;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.RoleConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.DISTRICT_A;
import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.DISTRICT_B;
import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.authenticateAs;
import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.registration;
import static com.templeregistry.service.finance.onboarding.OnboardingTestFixture.templeBRegistration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Registering a source system, declaring its capabilities and assessing readiness, against a real
 * database (FIN-140 slices 140-A through 140-D).
 *
 * <p>Two temples in two districts throughout, because isolation cannot fail against a fixture
 * with nothing to leak into.
 */
@SpringBootTest
@ActiveProfiles("test")
class SourceSystemAdminServiceTest extends FinanceOnboardingTestBase {

    @Autowired private SourceSystemAdminService service;
    @Autowired private TempleRepository temples;
    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinTempleCapabilityRepository capabilities;
    @Autowired private FinSourceOfTruthDeclRepository declarations;
    @Autowired private FinMappingRuleRepository rules;
    @Autowired private AuditDataEventRepository auditEvents;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private FinSyncErrorRepository errors;

    /** Source-of-truth dates. A declaration may not start in the future, so "now" is the ceiling. */
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private OnboardingTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new OnboardingTestFixture(temples, capabilities, declarations, rules);
        authenticateAs(RoleConstants.SUPER_ADMIN, 1L, null, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------- registration

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("A source system is registered against its temple, switched off")
        void should_register_when_requestIsValid() {
            Temple temple = fixture.temple("Register OK", DISTRICT_A);
            RegisterSourceSystemRequest request = registration(temple.getId());

            SourceSystemDetailResponse created = service.register(request);

            assertThat(created.id()).isNotNull();
            assertThat(created.templeId()).isEqualTo(temple.getId());
            assertThat(created.systemCode()).isEqualTo(request.getSystemCode());
            assertThat(created.syncEnabled())
                    .as("registering a source must never, by itself, start traffic to a temple")
                    .isFalse();
            assertThat(created.version()).isZero();
            assertThat(sourceSystems.findById(created.id())).isPresent();
        }

        @Test
        @DisplayName("Defaults are applied when the caller omits them")
        void should_applyDefaults_when_optionalFieldsAreOmitted() {
            Temple temple = fixture.temple("Defaults", DISTRICT_A);
            RegisterSourceSystemRequest request = registration(temple.getId());
            request.setStalenessThresholdHours(null);
            request.setSourceTimezone(null);

            SourceSystemDetailResponse created = service.register(request);

            assertThat(created.stalenessThresholdHours()).isEqualTo(48);
            assertThat(created.sourceTimezone()).isEqualTo("Asia/Kolkata");
        }

        @Test
        @DisplayName("A registration naming a temple that does not exist is refused as not found")
        void should_refuse_when_templeDoesNotExist() {
            RegisterSourceSystemRequest request = registration(88_888_888L);

            assertThatThrownBy(() -> service.register(request))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("A retired source keeps its code, and reusing it is refused as a duplicate not a server error")
        void should_refuse_when_codeIsAlreadyUsedForThatTemple() {
            Temple temple = fixture.temple("Duplicate code", DISTRICT_A);
            RegisterSourceSystemRequest first = registration(temple.getId());
            service.register(first);

            // Retire the first, so the D9 single-source rule does not mask the duplicate check.
            FinSourceSystem existing = sourceSystems
                    .findByTempleIdAndSystemCodeAndDeletedFalse(temple.getId(), first.getSystemCode())
                    .orElseThrow();
            existing.setDeleted(true);
            sourceSystems.saveAndFlush(existing);

            RegisterSourceSystemRequest second = registration(temple.getId());
            second.setSystemCode(first.getSystemCode());

            assertThatThrownBy(() -> service.register(second))
                    .as("uk_fss_temple_system ignores is_deleted, so a check that skipped retired "
                            + "rows would reach the constraint and surface as a 500")
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining(first.getSystemCode())
                    .hasMessageContaining("retired");
        }

        @Test
        @DisplayName("A live source's code is refused for the same temple")
        void should_refuse_when_codeIsAlreadyUsedByALiveSource() {
            Temple temple = fixture.temple("Live duplicate", DISTRICT_A);
            RegisterSourceSystemRequest first = registration(temple.getId());
            service.register(first);

            RegisterSourceSystemRequest second = registration(temple.getId());
            second.setSystemCode(first.getSystemCode());

            // Both the duplicate-code rule and the D9 single-source rule apply here. Either
            // refusal is correct; what must not happen is a constraint violation reaching the
            // caller as a server error.
            assertThatThrownBy(() -> service.register(second))
                    .isInstanceOfAny(DuplicateResourceException.class, IllegalStateException.class);
        }

        @Test
        @DisplayName("The same code may be used by a different temple")
        void should_allow_when_codeIsReusedByAnotherTemple() {
            Temple templeA = fixture.temple("Code reuse A", DISTRICT_A);
            Temple templeB = fixture.temple("Code reuse B", DISTRICT_B);
            RegisterSourceSystemRequest first = registration(templeA.getId());
            service.register(first);

            RegisterSourceSystemRequest second = registration(templeB.getId());
            second.setSystemCode(first.getSystemCode());

            assertThat(service.register(second).systemCode())
                    .as("the unique key is per temple; two temples may each run a system called "
                            + "the same thing")
                    .isEqualTo(first.getSystemCode());
        }

        @Test
        @DisplayName("A temple may have only one source system while D9 is open, and is told why")
        void should_refuse_when_templeAlreadyHasASourceSystem() {
            Temple temple = fixture.temple("Second source", DISTRICT_A);
            service.register(registration(temple.getId()));

            assertThatThrownBy(() -> service.register(registration(temple.getId())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("D9")
                    .hasMessageContaining("one finance source per temple");
        }

        @Test
        @DisplayName("Registration is audited without naming the credential alias")
        void should_auditRegistration_without_namingTheCredentialAlias() {
            Temple temple = fixture.temple("Audited", DISTRICT_A);
            RegisterSourceSystemRequest request = registration(temple.getId());
            request.setCredentialRef("super-secret-alias-name");

            SourceSystemDetailResponse created = service.register(request);

            var events = auditEvents.findAll().stream()
                    .filter(event -> "FIN_SOURCE_SYSTEM".equals(event.getEntityType()))
                    .filter(event -> created.id().equals(event.getEntityId()))
                    .toList();

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getAction()).isEqualTo("CREATE");
            assertThat(events.get(0).getActorRole()).isEqualTo(RoleConstants.SUPER_ADMIN);
            assertThat(events.get(0).getDetail())
                    .as("an audit trail is read by more people than the screen is, and the alias "
                            + "names where the secret lives")
                    .doesNotContain("super-secret-alias-name")
                    .contains("credentialRefSet=true");
        }
    }

    // --------------------------------------------------------------------- read

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        @DisplayName("The detail response reports that a credential is configured, never which one")
        void should_redactCredentialAlias_when_detailIsRead() {
            Temple temple = fixture.temple("Redaction", DISTRICT_A);
            RegisterSourceSystemRequest request = registration(temple.getId());
            request.setCredentialRef("kollur-readonly");
            SourceSystemDetailResponse created = service.register(request);

            SourceSystemDetailResponse read = service.get(created.id());

            assertThat(read.credentialRefSet()).isTrue();
            assertThat(read.toString())
                    .as("the record has no field for it, so no serialiser or log can leak it")
                    .doesNotContain("kollur-readonly");
        }

        @Test
        @DisplayName("credentialRefSet is false when no alias was given")
        void should_reportCredentialUnset_when_noAliasWasGiven() {
            Temple temple = fixture.temple("No credential", DISTRICT_A);
            RegisterSourceSystemRequest request = registration(temple.getId());
            request.setCredentialRef(null);

            assertThat(service.register(request).credentialRefSet()).isFalse();
        }

        @Test
        @DisplayName("An unknown id is not found")
        void should_refuse_when_sourceSystemDoesNotExist() {
            assertThatThrownBy(() -> service.get(77_777_777L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // ------------------------------------------------------------------- update

    @Nested
    @DisplayName("Updating")
    class Updating {

        @Test
        @DisplayName("Metadata is updated and the version advances")
        void should_update_when_versionIsCurrent() {
            Temple temple = fixture.temple("Update OK", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            SourceSystemDetailResponse updated =
                    service.update(created.id(), update(created, "Renamed system"));

            assertThat(updated.systemName()).isEqualTo("Renamed system");
            assertThat(updated.version()).isGreaterThan(created.version());
        }

        @Test
        @DisplayName("An edit written against a stale version is refused rather than silently winning")
        void should_refuse_when_versionIsStale() {
            Temple temple = fixture.temple("Stale edit", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            service.update(created.id(), update(created, "First writer"));

            UpdateSourceSystemRequest secondWriter = update(created, "Second writer");

            assertThatThrownBy(() -> service.update(created.id(), secondWriter))
                    .isInstanceOf(OptimisticLockingFailureException.class);
            assertThat(service.get(created.id()).systemName()).isEqualTo("First writer");
        }

        @Test
        @DisplayName("An omitted credentialRef leaves the stored alias alone")
        void should_keepCredential_when_fieldIsOmitted() {
            Temple temple = fixture.temple("Keep credential", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            UpdateSourceSystemRequest request = update(created, "Still configured");
            request.setCredentialRef(null);

            assertThat(service.update(created.id(), request).credentialRefSet()).isTrue();
        }

        @Test
        @DisplayName("An empty credentialRef clears the stored alias")
        void should_clearCredential_when_fieldIsEmpty() {
            Temple temple = fixture.temple("Clear credential", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            UpdateSourceSystemRequest request = update(created, "Cleared");
            request.setCredentialRef("");

            assertThat(service.update(created.id(), request).credentialRefSet()).isFalse();
        }

        @Test
        @DisplayName("Update cannot move a source system to another temple or switch it on")
        void should_notExposeTempleOrSyncSwitch_onUpdate() {
            assertThat(java.util.Arrays.stream(UpdateSourceSystemRequest.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName))
                    .as("moving a source to another temple would reattribute every figure it has "
                            + "loaded; switching it on is a gated act, not a metadata edit")
                    .doesNotContain("templeId", "systemCode", "syncEnabled");
        }
    }

    // ---------------------------------------------------------------- readiness

    @Nested
    @DisplayName("Readiness")
    class Readiness {

        @Test
        @DisplayName("A bare registration is blocked, and never claims connectivity was checked")
        void should_block_when_nothingElseIsConfigured() {
            Temple temple = fixture.temple("Bare", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            SourceSystemReadinessResponse readiness = service.readiness(created.id());

            assertThat(readiness.status()).isEqualTo(ReadinessStatus.BLOCKED);
            assertThat(readiness.activationAllowed()).isFalse();
            assertThat(readiness.blockingCount()).isPositive();
            assertThat(codes(readiness)).contains("NO_CAPABILITY_DECLARED");
            assertThat(readiness.connectivityVerified()).isFalse();
            assertThat(readiness.connectivityNote()).contains("has contacted the source system");
        }

        @Test
        @DisplayName("A fully configured source reaches READY and reports activation as permitted")
        void should_reportReady_when_fullyConfigured() {
            Temple temple = fixture.temple("Ready", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            fixture.makeFullyConfigured(temple.getId(), created.id());

            SourceSystemReadinessResponse readiness = service.readiness(created.id());

            assertThat(readiness.findings()).isEmpty();
            assertThat(readiness.status()).isEqualTo(ReadinessStatus.READY);
            assertThat(readiness.activationAllowed()).isTrue();
            assertThat(readiness.syncEnabled())
                    .as("ready to activate is not activated")
                    .isFalse();
        }

        @Test
        @DisplayName("A missing source-of-truth declaration blocks activation")
        void should_block_when_requiredDeclarationIsMissing() {
            Temple temple = fixture.temple("No declaration", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            fixture.capability(temple.getId(), created.id(), FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, null, LocalDate.of(2019, 4, 1));
            fixture.rule(created.id(), "SANNIDHI:DS", "SEVA", 100);

            SourceSystemReadinessResponse readiness = service.readiness(created.id());

            assertThat(codes(readiness)).contains("SOURCE_OF_TRUTH_MISSING");
            assertThat(readiness.activationAllowed()).isFalse();
        }

        @Test
        @DisplayName("A rule naming a category that does not exist blocks activation")
        void should_block_when_canonicalValueIsUnknown() {
            Temple temple = fixture.temple("Bad canonical", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            fixture.makeFullyConfigured(temple.getId(), created.id());
            fixture.rule(created.id(), "SANNIDHI:XX", "NO_SUCH_CATEGORY", 100);

            assertThat(codes(service.readiness(created.id()))).contains("MAPPING_CANONICAL_UNKNOWN");
        }

        @Test
        @DisplayName("Two namespaces at one priority warns but still permits activation")
        void should_warnOnly_when_rulesCouldBeAmbiguous() {
            Temple temple = fixture.temple("Ambiguous", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            fixture.makeFullyConfigured(temple.getId(), created.id());
            fixture.rule(created.id(), "STREAM:SAREE_DONATION", "IN_KIND_DONATION", 100);

            SourceSystemReadinessResponse readiness = service.readiness(created.id());

            assertThat(codes(readiness)).contains("MAPPING_RULE_POSSIBLY_AMBIGUOUS");
            assertThat(readiness.status()).isEqualTo(ReadinessStatus.WARNING);
            assertThat(readiness.activationAllowed()).isTrue();
        }

        @Test
        @DisplayName("Readiness is recomputed, so correcting the configuration clears the finding")
        void should_recompute_when_configurationChanges() {
            Temple temple = fixture.temple("Recompute", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            assertThat(service.readiness(created.id()).status()).isEqualTo(ReadinessStatus.BLOCKED);

            fixture.makeFullyConfigured(temple.getId(), created.id());

            assertThat(service.readiness(created.id()).status())
                    .as("a persisted verdict would still be reporting BLOCKED here")
                    .isEqualTo(ReadinessStatus.READY);
        }
    }

    // ---------------------------------------------------------------- isolation

    @Nested
    @DisplayName("Isolation")
    class Isolation {

        @Test
        @DisplayName("One source's readiness never reads another source's configuration")
        void should_isolateSources_when_twoTemplesAreConfigured() {
            Temple templeA = fixture.temple("Isolated A", DISTRICT_A);
            Temple templeB = fixture.temple("Isolated B", DISTRICT_B);
            SourceSystemDetailResponse sourceA = service.register(registration(templeA.getId()));
            SourceSystemDetailResponse sourceB = service.register(registration(templeB.getId()));

            fixture.makeFullyConfigured(templeA.getId(), sourceA.id());

            SourceSystemReadinessResponse readinessB = service.readiness(sourceB.id());

            assertThat(readinessB.status())
                    .as("A's capabilities, declarations and rules must not make B look ready")
                    .isEqualTo(ReadinessStatus.BLOCKED);
            assertThat(codes(readinessB)).contains("NO_CAPABILITY_DECLARED");
            assertThat(service.readiness(sourceA.id()).status()).isEqualTo(ReadinessStatus.READY);
        }

        @Test
        @DisplayName("A capability row belonging to another source of the same temple is not counted")
        void should_ignoreOtherSourcesRows_when_snapshotIsBuilt() {
            Temple temple = fixture.temple("Same temple, other source", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            // A capability row attributed to a different source system id, as a pre-D9 database
            // could legitimately contain.
            fixture.capability(temple.getId(), created.id() + 5_000L, FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, null, LocalDate.of(2019, 4, 1));

            assertThat(codes(service.readiness(created.id())))
                    .as("the row carries source_system_id even though the unique key ignores it")
                    .contains("NO_CAPABILITY_DECLARED");
        }
    }

    // ----------------------------------------------------------------- Temple B

    @Nested
    @DisplayName("Temple B — the genericity proof")
    class TempleB {

        @Test
        @DisplayName("A PostgreSQL push-agent source with expenses and no Nirantara onboards unchanged")
        void should_onboardADissimilarSource_when_nothingIsKollurShaped() {
            Temple temple = fixture.temple("Sri Ranganatha, Mandya", DISTRICT_B);

            SourceSystemDetailResponse created = service.register(templeBRegistration(temple.getId()));

            assertThat(created.sourceTechnology()).isEqualTo(SourceTechnology.POSTGRESQL);
            assertThat(created.connectorType()).isEqualTo(ConnectorType.PUSH_AGENT);

            // B has expenses and no Nirantara — the opposite of the first onboarded source.
            fixture.capability(temple.getId(), created.id(), FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, null, LocalDate.of(2022, 4, 1));
            fixture.capability(temple.getId(), created.id(), FinanceCapability.EXPENSE,
                    DataAvailability.AVAILABLE, null, LocalDate.of(2022, 4, 1));
            fixture.capability(temple.getId(), created.id(), FinanceCapability.NIRANTARA_SCHEDULE,
                    DataAvailability.NOT_APPLICABLE, "This temple runs no Nirantara scheme.", null);
            fixture.declaration(created.id(), "REVENUE_AMOUNT");
            fixture.declaration(created.id(), "REVENUE_TRANSACTION_DATE");
            fixture.rule(created.id(), "SERVICE_CATEGORY:seva", "SEVA", 100);
            fixture.declareRemainingAsNotApplicable(temple.getId(), created.id());

            SourceSystemReadinessResponse readiness = service.readiness(created.id());

            assertThat(readiness.status()).isEqualTo(ReadinessStatus.READY);
            assertThat(readiness.activationAllowed()).isTrue();
        }

        @Test
        @DisplayName("A source that reports no revenue at all is not forced to configure revenue")
        void should_notDemandRevenueConfiguration_when_sourceHasNone() {
            Temple temple = fixture.temple("Expenses only", DISTRICT_B);
            SourceSystemDetailResponse created = service.register(templeBRegistration(temple.getId()));

            fixture.capability(temple.getId(), created.id(), FinanceCapability.REVENUE,
                    DataAvailability.NOT_AVAILABLE, "This system records no receipts.", null);
            fixture.capability(temple.getId(), created.id(), FinanceCapability.EXPENSE,
                    DataAvailability.AVAILABLE, null, LocalDate.of(2022, 4, 1));

            SourceSystemReadinessResponse readiness = service.readiness(created.id());

            assertThat(codes(readiness))
                    .doesNotContain("SOURCE_OF_TRUTH_MISSING", "NO_ACTIVE_MAPPING_RULE");
            assertThat(readiness.activationAllowed()).isTrue();
        }
    }

    // -------------------------------------------------- capabilities (FIN-140-B)

    @Nested
    @DisplayName("Capability declarations")
    class Capabilities {

        @Test
        @DisplayName("The catalogue is derived from the enums, so no client keeps its own copy")
        void should_serveTheCanonicalCatalogue() {
            CapabilityCatalogueResponse catalogue = service.capabilityCatalogue();

            assertThat(catalogue.capabilities())
                    .hasSize(FinanceCapability.values().length)
                    .extracting(CapabilityCatalogueResponse.CapabilityOption::capability)
                    .containsExactly(FinanceCapability.values());
            assertThat(catalogue.availabilities())
                    .containsExactly(DataAvailability.values());
            assertThat(catalogue.capabilities())
                    .filteredOn(CapabilityCatalogueResponse.CapabilityOption::drivesRevenueRequirements)
                    .extracting(CapabilityCatalogueResponse.CapabilityOption::capability)
                    .as("the flag must come from the validator that enforces the rule, not a second copy")
                    .containsExactly(FinanceCapability.REVENUE);
        }

        @Test
        @DisplayName("A declaration is created against the source system's own temple")
        void should_declareCapability_when_requestIsValid() {
            Temple temple = fixture.temple("Declare", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            CapabilityDeclarationResponse declared = service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null));

            assertThat(declared.templeId())
                    .as("resolved from the source system; the request has no templeId field")
                    .isEqualTo(temple.getId());
            assertThat(declared.sourceSystemId()).isEqualTo(created.id());
            assertThat(declared.capability()).isEqualTo(FinanceCapability.REVENUE);
            assertThat(declared.availability()).isEqualTo(DataAvailability.AVAILABLE);
            assertThat(declared.version()).isZero();
            assertThat(declared.lastReviewedAt()).isNotNull();
        }

        @Test
        @DisplayName("Declarations are listed for the source system in canonical order")
        void should_listDeclarations_inCanonicalOrder() {
            Temple temple = fixture.temple("List order", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            service.declareCapability(created.id(),
                    declare(FinanceCapability.EXPENSE, DataAvailability.NOT_AVAILABLE, "No module."));
            service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null));

            assertThat(service.listCapabilities(created.id()))
                    .extracting(CapabilityDeclarationResponse::capability)
                    .containsExactly(FinanceCapability.REVENUE, FinanceCapability.EXPENSE);
        }

        @Test
        @DisplayName("A capability that is not AVAILABLE needs a reason, refused rather than saved wrong")
        void should_refuse_when_unavailableDeclarationHasNoReason() {
            Temple temple = fixture.temple("No reason", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            assertThatThrownBy(() -> service.declareCapability(created.id(),
                    declare(FinanceCapability.EXPENSE, DataAvailability.NOT_AVAILABLE, "   ")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("verbatim");
        }

        @Test
        @DisplayName("An AVAILABLE capability needs no reason")
        void should_allow_when_availableDeclarationHasNoReason() {
            Temple temple = fixture.temple("Available no reason", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            assertThat(service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null))
                    .availabilityReason()).isNull();
        }

        @Test
        @DisplayName("An inverted coverage window is refused")
        void should_refuse_when_coverageWindowIsInverted() {
            Temple temple = fixture.temple("Inverted", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            DeclareCapabilityRequest request =
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);
            request.setCoverageFrom(LocalDate.of(2024, 4, 1));
            request.setCoverageTo(LocalDate.of(2019, 3, 31));

            assertThatThrownBy(() -> service.declareCapability(created.id(), request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before it begins");
        }

        @Test
        @DisplayName("Declaring the same capability twice is refused, not left to the database")
        void should_refuse_when_capabilityIsAlreadyDeclared() {
            Temple temple = fixture.temple("Duplicate capability", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            CapabilityDeclarationResponse first = service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null));

            assertThatThrownBy(() -> service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.NOT_AVAILABLE, "Changed mind.")))
                    .as("uk_ftc_temple_capability would otherwise surface as a 500")
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining(String.valueOf(first.id()));
        }

        @Test
        @DisplayName("A capability declared by another source system for the same temple is refused clearly")
        void should_refuse_when_anotherSourceHoldsTheCapability() {
            Temple temple = fixture.temple("Cross source", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));

            // A declaration attributed to a different source system, as a pre-D9 database could
            // legitimately contain. The unique key is per temple, so it occupies the slot.
            fixture.capability(temple.getId(), created.id() + 9_000L, FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, null, LocalDate.of(2019, 4, 1));

            assertThatThrownBy(() -> service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null)))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("D9");
        }

        @Test
        @DisplayName("A retired declaration keeps its slot and says so")
        void should_refuse_when_retiredDeclarationHoldsTheCapability() {
            Temple temple = fixture.temple("Retired declaration", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            var retired = fixture.capability(temple.getId(), created.id(), FinanceCapability.SEVA,
                    DataAvailability.AVAILABLE, null, null);
            retired.setDeleted(true);
            capabilities.saveAndFlush(retired);

            assertThatThrownBy(() -> service.declareCapability(created.id(),
                    declare(FinanceCapability.SEVA, DataAvailability.AVAILABLE, null)))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("retired");
        }

        @Test
        @DisplayName("A declaration is revised and its version advances")
        void should_updateDeclaration_when_versionIsCurrent() {
            Temple temple = fixture.temple("Update declaration", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            CapabilityDeclarationResponse declared = service.declareCapability(created.id(),
                    declare(FinanceCapability.EXPENSE, DataAvailability.NOT_AVAILABLE, "No module."));

            CapabilityDeclarationResponse updated = service.updateCapability(created.id(),
                    declared.id(), update(declared, DataAvailability.AVAILABLE, null));

            assertThat(updated.availability()).isEqualTo(DataAvailability.AVAILABLE);
            assertThat(updated.version()).isGreaterThan(declared.version());
            assertThat(updated.capability())
                    .as("the capability is identity and no request field can change it")
                    .isEqualTo(FinanceCapability.EXPENSE);
        }

        @Test
        @DisplayName("A revision written against a stale version is refused")
        void should_refuseUpdate_when_versionIsStale() {
            Temple temple = fixture.temple("Stale declaration", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            CapabilityDeclarationResponse declared = service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null));
            service.updateCapability(created.id(), declared.id(),
                    update(declared, DataAvailability.PARTIALLY_AVAILABLE, "Gaps in FY2021-22."));

            assertThatThrownBy(() -> service.updateCapability(created.id(), declared.id(),
                    update(declared, DataAvailability.NOT_AVAILABLE, "Second writer.")))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }

        @Test
        @DisplayName("A declaration reached through the wrong source system is not found")
        void should_refuseUpdate_when_declarationBelongsToAnotherSource() {
            Temple templeA = fixture.temple("Owner", DISTRICT_A);
            Temple templeB = fixture.temple("Other", DISTRICT_B);
            SourceSystemDetailResponse sourceA = service.register(registration(templeA.getId()));
            SourceSystemDetailResponse sourceB = service.register(registration(templeB.getId()));
            CapabilityDeclarationResponse declared = service.declareCapability(sourceA.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null));

            assertThatThrownBy(() -> service.updateCapability(sourceB.id(), declared.id(),
                    update(declared, DataAvailability.NOT_AVAILABLE, "Hijacked.")))
                    .as("absent and not-yours are the same answer, so ids cannot be probed")
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("One source's declarations never appear in another's list")
        void should_isolateDeclarations_acrossSourceSystems() {
            Temple templeA = fixture.temple("Isolated decl A", DISTRICT_A);
            Temple templeB = fixture.temple("Isolated decl B", DISTRICT_B);
            SourceSystemDetailResponse sourceA = service.register(registration(templeA.getId()));
            SourceSystemDetailResponse sourceB = service.register(registration(templeB.getId()));
            service.declareCapability(sourceA.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null));

            assertThat(service.listCapabilities(sourceB.id())).isEmpty();
            assertThat(service.listCapabilities(sourceA.id())).hasSize(1);
        }

        @Test
        @DisplayName("Known gaps round-trip, and blank entries are dropped rather than stored")
        void should_storeKnownGaps_droppingBlanks() {
            Temple temple = fixture.temple("Gaps", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            DeclareCapabilityRequest request = declare(FinanceCapability.PRECIOUS_METAL_WEIGHT,
                    DataAvailability.PARTIALLY_AVAILABLE, "Two financial years are missing.");
            request.setKnownGaps(List.of("FY2021-22 absent", "   ", "FY2022-23 absent"));

            assertThat(service.declareCapability(created.id(), request).knownGaps())
                    .containsExactly("FY2021-22 absent", "FY2022-23 absent");
        }

        @Test
        @DisplayName("Declaring a capability is audited without any credential detail")
        void should_auditDeclaration() {
            Temple temple = fixture.temple("Audited capability", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            CapabilityDeclarationResponse declared = service.declareCapability(created.id(),
                    declare(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null));

            var events = auditEvents.findAll().stream()
                    .filter(event -> "FIN_TEMPLE_CAPABILITY".equals(event.getEntityType()))
                    .filter(event -> declared.id().equals(event.getEntityId()))
                    .toList();

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getAction()).isEqualTo("CREATE");
            assertThat(events.get(0).getActorRole()).isEqualTo(RoleConstants.SUPER_ADMIN);
            assertThat(events.get(0).getDetail())
                    .contains("templeId=" + temple.getId())
                    .contains("REVENUE=AVAILABLE")
                    .doesNotContain("some-readonly-alias");
        }

        @Test
        @DisplayName("Declaring a capability never switches a source system on")
        void should_notActivate_when_capabilityIsDeclared() {
            Temple temple = fixture.temple("No activation", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            fixture.makeFullyConfigured(temple.getId(), created.id());

            assertThat(service.get(created.id()).syncEnabled()).isFalse();
            assertThat(service.readiness(created.id()).syncEnabled()).isFalse();
        }

        @Test
        @DisplayName("A declaration made through the API clears the readiness finding it answers")
        void should_clearReadinessFinding_when_capabilitiesAreDeclared() {
            Temple temple = fixture.temple("Readiness via API", DISTRICT_A);
            SourceSystemDetailResponse created = service.register(registration(temple.getId()));
            assertThat(codes(service.readiness(created.id()))).contains("NO_CAPABILITY_DECLARED");

            for (FinanceCapability capability : FinanceCapability.values()) {
                service.declareCapability(created.id(), declare(capability,
                        DataAvailability.NOT_APPLICABLE, "Does not arise for this temple."));
            }

            assertThat(codes(service.readiness(created.id())))
                    .doesNotContain("NO_CAPABILITY_DECLARED", "CAPABILITY_NOT_DECLARED");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static DeclareCapabilityRequest declare(FinanceCapability capability,
                                                    DataAvailability availability, String reason) {
        DeclareCapabilityRequest request = new DeclareCapabilityRequest();
        request.setCapability(capability);
        request.setAvailability(availability);
        request.setAvailabilityReason(reason);
        return request;
    }

    private static UpdateCapabilityDeclarationRequest update(CapabilityDeclarationResponse from,
                                                              DataAvailability availability,
                                                              String reason) {
        UpdateCapabilityDeclarationRequest request = new UpdateCapabilityDeclarationRequest();
        request.setVersion(from.version());
        request.setAvailability(availability);
        request.setAvailabilityReason(reason);
        return request;
    }

    private static List<String> codes(SourceSystemReadinessResponse readiness) {
        return readiness.findings().stream().map(ReadinessFinding::code).toList();
    }

    private static UpdateSourceSystemRequest update(SourceSystemDetailResponse from, String newName) {
        UpdateSourceSystemRequest request = new UpdateSourceSystemRequest();
        request.setVersion(from.version());
        request.setSystemName(newName);
        request.setSourceTechnology(from.sourceTechnology());
        request.setConnectorType(from.connectorType());
        request.setConnectorBean(from.connectorBean());
        request.setSourceTempleCode(from.sourceTempleCode());
        request.setSourceDatabaseName(from.sourceDatabaseName());
        return request;
    }

    // ------------------------------------------- source of truth (FIN-140-C)

    @Nested
    @DisplayName("Source of truth")
    class SourceOfTruth {

        @Test
        @DisplayName("The first declaration for a metric is version 1, and it is in force")
        void should_createFirstVersion_when_noneExists() {
            Long source = registerFor("SoT first");

            SourceOfTruthDeclarationResponse declared =
                    declare(source, "REVENUE_AMOUNT", "DailyReceipts", "Amount", TODAY, null);

            assertThat(declared.version()).isEqualTo(1);
            assertThat(declared.inForce()).isTrue();
            assertThat(declared.effectiveTo()).isNull();
            assertThat(declared.sourceObject()).isEqualTo("DailyReceipts");
            assertThat(declared.sourceField()).isEqualTo("Amount");
        }

        @Test
        @DisplayName("A metric nothing reads is refused rather than stored as an inert row")
        void should_refuse_when_metricIsUnknown() {
            Long source = registerFor("SoT unknown metric");

            assertThatThrownBy(() ->
                    declare(source, "REVENUE_VIBES", "DailyReceipts", "Amount", TODAY, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REVENUE_VIBES")
                    .hasMessageContaining("REVENUE_AMOUNT");
        }

        @Test
        @DisplayName("A declaration cannot start in the future, because it applies as soon as it is saved")
        void should_refuse_when_effectiveFromIsInTheFuture() {
            Long source = registerFor("SoT future");

            assertThatThrownBy(() -> declare(source, "REVENUE_AMOUNT", "DailyReceipts", "Amount",
                    LocalDate.now().plusDays(1), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("future");
        }

        @Test
        @DisplayName("A second declaration supersedes the first — and the first is kept, not overwritten")
        void should_supersedePreviousVersion_when_declaredAgain() {
            Long source = registerFor("SoT supersede");
            SourceOfTruthDeclarationResponse first =
                    declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", YESTERDAY, null);

            SourceOfTruthDeclarationResponse second =
                    declare(source, "REVENUE_AMOUNT", "NewTable", "GrossAmount", TODAY, 1);

            assertThat(second.version()).isEqualTo(2);
            assertThat(second.inForce()).isTrue();

            FinSourceOfTruthDecl kept = declarations.findById(first.id()).orElseThrow();
            assertThat(kept.getSourceObject())
                    .as("a fact loaded under version 1 carries that version; rewriting the row "
                            + "would leave the stamp pointing at something it never said")
                    .isEqualTo("OldTable");
            assertThat(kept.getEffectiveTo()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("Exactly one version is in force after a supersession")
        void should_leaveOneVersionInForce_when_superseded() {
            Long source = registerFor("SoT one in force");
            declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", YESTERDAY, null);
            declare(source, "REVENUE_AMOUNT", "NewTable", "Amount", TODAY, 1);

            assertThat(service.listSourceOfTruth(source).stream()
                    .filter(SourceOfTruthDeclarationResponse::inForce))
                    .hasSize(1);
        }

        @Test
        @DisplayName("A caller superseding a version that is no longer in force is refused")
        void should_refuse_when_supersedingAStaleVersion() {
            Long source = registerFor("SoT stale");
            declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", YESTERDAY, null);
            declare(source, "REVENUE_AMOUNT", "NewTable", "Amount", TODAY, 1);

            assertThatThrownBy(() ->
                    declare(source, "REVENUE_AMOUNT", "ThirdTable", "Amount", TODAY, 1))
                    .as("two administrators who each read version 1 would otherwise each write "
                            + "the next one, and the first change would vanish unannounced")
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining("Version 2");
        }

        @Test
        @DisplayName("Declaring without naming the version in force is refused")
        void should_refuse_when_supersedesVersionIsOmittedButOneIsInForce() {
            Long source = registerFor("SoT omitted");
            declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", YESTERDAY, null);

            assertThatThrownBy(() ->
                    declare(source, "REVENUE_AMOUNT", "NewTable", "Amount", TODAY, null))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }

        @Test
        @DisplayName("Superseding a version when none is in force is refused")
        void should_refuse_when_supersedingButNothingIsInForce() {
            Long source = registerFor("SoT nothing in force");

            assertThatThrownBy(() ->
                    declare(source, "REVENUE_AMOUNT", "NewTable", "Amount", TODAY, 1))
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining("No declaration is in force");
        }

        @Test
        @DisplayName("A new version cannot begin before the one it closes")
        void should_refuse_when_newVersionIsBackdatedBehindTheOldOne() {
            Long source = registerFor("SoT backdated");
            declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", TODAY, null);

            assertThatThrownBy(() -> declare(source, "REVENUE_AMOUNT", "NewTable", "Amount",
                    TODAY.minusDays(5), 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before version 1 began");
        }

        /**
         * The database backstop behind the concurrency guard.
         *
         * <p>{@code requireSupersedesTheVersionInForce} catches the race between two administrators
         * in the application; this asserts that the constraint underneath catches it too, for two
         * transactions interleaved closely enough that both read the same version as in force.
         * The service converts the violation into a 409 naming what happened.
         */
        @Test
        @DisplayName("The database refuses a duplicate version for the same source and metric")
        void should_refuseDuplicateVersion_when_writtenDirectly() {
            Long source = registerFor("SoT duplicate version");
            declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", TODAY, null);

            assertThatThrownBy(() -> declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                    .sourceSystemId(source)
                    .metric("REVENUE_AMOUNT")
                    .version(1)
                    .sourceObject("Racing")
                    .sourceField("Amount")
                    .build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("A version number is never reused, even after a row is retired")
        void should_skipRetiredVersionNumbers_when_computingTheNextOne() {
            Long source = registerFor("SoT retired version");
            SourceOfTruthDeclarationResponse first =
                    declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", YESTERDAY, null);

            FinSourceOfTruthDecl retired = declarations.findById(first.id()).orElseThrow();
            retired.setDeleted(true);
            declarations.saveAndFlush(retired);

            SourceOfTruthDeclarationResponse next =
                    declare(source, "REVENUE_AMOUNT", "NewTable", "Amount", TODAY, null);

            assertThat(next.version())
                    .as("uk_fsotd_source_metric_version ignores is_deleted, so a retired row still "
                            + "holds its number")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("Sign-off is recorded when asked for, and left open when it is not")
        void should_recordApproval_when_requested() {
            Long source = registerFor("SoT approval");

            SourceOfTruthDeclarationResponse unsigned =
                    declare(source, "REVENUE_AMOUNT", "DailyReceipts", "Amount", TODAY, null);
            assertThat(unsigned.approved()).isFalse();
            assertThat(unsigned.approvedBy()).isNull();
            assertThat(unsigned.approvedAt()).isNull();

            DeclareSourceOfTruthRequest signed =
                    request("REVENUE_TRANSACTION_DATE", "DailyReceipts", "ReceiptDate", TODAY, null);
            signed.setApproved(true);

            SourceOfTruthDeclarationResponse result = service.declareSourceOfTruth(source, signed);
            assertThat(result.approved()).isTrue();
            assertThat(result.approvedBy()).isEqualTo(1L);
            assertThat(result.approvedAt()).isNotNull();
        }

        @Test
        @DisplayName("Rejected alternatives are stored with their measurements and returned")
        void should_storeRejectedAlternatives_when_given() {
            Long source = registerFor("SoT alternatives");
            DeclareSourceOfTruthRequest request =
                    request("REVENUE_AMOUNT", "Header", "Amount", TODAY, null);

            DeclareSourceOfTruthRequest.RejectedAlternative rejected =
                    new DeclareSourceOfTruthRequest.RejectedAlternative();
            rejected.setObject("Detail");
            rejected.setField("TotalAmount");
            rejected.setMeasured("537753226 for FY2025-26");
            rejected.setReason("41% below the authoritative header total.");
            request.setRejectedAlternatives(List.of(rejected));

            SourceOfTruthDeclarationResponse declared = service.declareSourceOfTruth(source, request);

            assertThat(declared.rejectedAlternatives()).hasSize(1);
            assertThat(declared.rejectedAlternatives().get(0))
                    .containsEntry("object", "Detail")
                    .containsEntry("measured", "537753226 for FY2025-26");
        }

        /**
         * The declarations seeded for the first onboarded source carry their own measurement keys.
         * A reader expecting a fixed shape would drop exactly the evidence that gives them force.
         */
        @Test
        @DisplayName("Alternatives written by a migration are returned with whatever keys they carry")
        void should_returnUnknownKeys_when_alternativesWereSeeded() {
            Long source = registerFor("SoT seeded alternatives");
            declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                    .sourceSystemId(source)
                    .metric("REVENUE_AMOUNT")
                    .version(1)
                    .sourceObject("DailySevaNew")
                    .sourceField("Amount")
                    .rejectedAlternativesJson(
                            "[{\"object\":\"DailySevaNewOld\",\"measuredRows\":16982270,"
                                    + "\"reason\":\"Byte-exact duplicate of the archives.\"}]")
                    .build());

            SourceOfTruthDeclarationResponse declared = service.listSourceOfTruth(source).get(0);

            assertThat(declared.rejectedAlternatives().get(0))
                    .containsEntry("measuredRows", "16982270")
                    .containsEntry("object", "DailySevaNewOld");
        }

        /**
         * The column is MySQL {@code JSON}, so text that is not JSON never reaches the reader —
         * the database refuses the insert. What the lenient parser is actually for is valid JSON
         * of an unexpected shape, which a future seed could legitimately produce.
         */
        @Test
        @DisplayName("Alternatives of an unexpected shape are dropped rather than failing the list")
        void should_tolerateUnexpectedJsonShape_when_listing() {
            Long source = registerFor("SoT odd shape");
            declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                    .sourceSystemId(source)
                    .metric("REVENUE_AMOUNT")
                    .version(1)
                    .sourceObject("DailyReceipts")
                    .sourceField("Amount")
                    .rejectedAlternativesJson("[\"a bare sentence, not an object\"]")
                    .build());

            assertThat(service.listSourceOfTruth(source).get(0).rejectedAlternatives())
                    .as("cosmetic metadata; failing the whole administrative list over one cell "
                            + "is worse than showing the list without it")
                    .isEmpty();
        }

        @Test
        @DisplayName("The database refuses text that is not JSON in the alternatives column")
        void should_refuseNonJson_when_writtenDirectly() {
            Long source = registerFor("SoT non-json");

            assertThatThrownBy(() -> declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                    .sourceSystemId(source)
                    .metric("REVENUE_AMOUNT")
                    .version(1)
                    .sourceObject("DailyReceipts")
                    .sourceField("Amount")
                    .rejectedAlternativesJson("not json at all")
                    .build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("History is returned, superseded versions included")
        void should_returnEveryVersion_when_listing() {
            Long source = registerFor("SoT history");
            declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", YESTERDAY, null);
            declare(source, "REVENUE_AMOUNT", "NewTable", "Amount", TODAY, 1);
            declare(source, "REVENUE_TRANSACTION_DATE", "NewTable", "ReceiptDate", TODAY, null);

            List<SourceOfTruthDeclarationResponse> all = service.listSourceOfTruth(source);

            assertThat(all).hasSize(3);
            assertThat(all.stream().filter(d -> "REVENUE_AMOUNT".equals(d.metric())))
                    .extracting(SourceOfTruthDeclarationResponse::version)
                    .containsExactly(2, 1);
        }

        @Test
        @DisplayName("One source system never sees another's declarations")
        void should_isolateBySourceSystem_when_listing() {
            Temple templeA = fixture.temple("SoT isolation A", DISTRICT_A);
            Temple templeB = fixture.temple("SoT isolation B", DISTRICT_B);
            Long sourceA = service.register(registration(templeA.getId())).id();
            Long sourceB = service.register(templeBRegistration(templeB.getId())).id();

            declare(sourceA, "REVENUE_AMOUNT", "TableA", "Amount", TODAY, null);
            declare(sourceB, "REVENUE_AMOUNT", "TableB", "Amount", TODAY, null);

            assertThat(service.listSourceOfTruth(sourceA))
                    .extracting(SourceOfTruthDeclarationResponse::sourceObject)
                    .containsExactly("TableA");
        }

        @Test
        @DisplayName("A source system that does not exist is reported absent")
        void should_refuse_when_sourceSystemIsAbsent() {
            assertThatThrownBy(() -> service.listSourceOfTruth(9_999_999L))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("The change is audited beside it, naming no credential")
        void should_audit_when_declared() {
            Long source = registerFor("SoT audit");

            SourceOfTruthDeclarationResponse declared =
                    declare(source, "REVENUE_AMOUNT", "DailyReceipts", "Amount", TODAY, null);

            var events = auditEvents.findAll().stream()
                    .filter(event -> "FIN_SOURCE_OF_TRUTH_DECL".equals(event.getEntityType()))
                    .filter(event -> declared.id().equals(event.getEntityId()))
                    .toList();

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getAction()).isEqualTo("CREATE");
            assertThat(events.get(0).getActorRole()).isEqualTo(RoleConstants.SUPER_ADMIN);
            assertThat(events.get(0).getDetail())
                    .contains("REVENUE_AMOUNT v1")
                    .contains("sourceSystemId=" + source)
                    .doesNotContain("some-readonly-alias")
                    .doesNotContain("someFinanceConnector");
        }

        @Test
        @DisplayName("A supersession says in the audit trail which version it replaced")
        void should_auditWhichVersionWasClosed_when_superseding() {
            Long source = registerFor("SoT audit supersede");
            declare(source, "REVENUE_AMOUNT", "OldTable", "Amount", YESTERDAY, null);
            SourceOfTruthDeclarationResponse second =
                    declare(source, "REVENUE_AMOUNT", "NewTable", "Amount", TODAY, 1);

            var event = auditEvents.findAll().stream()
                    .filter(e -> "FIN_SOURCE_OF_TRUTH_DECL".equals(e.getEntityType()))
                    .filter(e -> second.id().equals(e.getEntityId()))
                    .findFirst()
                    .orElseThrow();

            assertThat(event.getDetail()).contains("superseding version 1");
        }

        @Test
        @DisplayName("Declaring both required metrics clears the blocking readiness finding")
        void should_clearReadinessFinding_when_requiredMetricsAreDeclared() {
            Temple temple = fixture.temple("SoT readiness", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.capability(temple.getId(), source, FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, "Receipts recorded in full.", YESTERDAY);
            fixture.declareRemainingAsNotApplicable(temple.getId(), source);
            fixture.rule(source, "SANNIDHI:DS", "SEVA", 100);

            assertThat(service.readiness(source).findings())
                    .extracting(ReadinessFinding::code)
                    .contains("SOURCE_OF_TRUTH_MISSING");

            declare(source, "REVENUE_AMOUNT", "DailyReceipts", "Amount", TODAY, null);
            declare(source, "REVENUE_TRANSACTION_DATE", "DailyReceipts", "ReceiptDate", TODAY, null);

            assertThat(service.readiness(source).findings())
                    .extracting(ReadinessFinding::code)
                    .doesNotContain("SOURCE_OF_TRUTH_MISSING");
        }

        /**
         * The distinction the whole slice turns on: a declaration that has been closed is history,
         * not configuration. Normalization reads the same {@code effective_to IS NULL} filter, so a
         * readiness verdict that counted a closed row would pass a source every row of which the
         * pipeline would then reject.
         */
        @Test
        @DisplayName("A superseded declaration does not satisfy readiness")
        void should_stillReportMissing_when_theOnlyDeclarationIsSuperseded() {
            Temple temple = fixture.temple("SoT superseded readiness", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.capability(temple.getId(), source, FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, "Receipts recorded in full.", YESTERDAY);
            fixture.declareRemainingAsNotApplicable(temple.getId(), source);
            fixture.rule(source, "SANNIDHI:DS", "SEVA", 100);

            declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                    .sourceSystemId(source)
                    .metric("REVENUE_AMOUNT")
                    .version(1)
                    .sourceObject("RetiredTable")
                    .sourceField("Amount")
                    .effectiveFrom(YESTERDAY)
                    .effectiveTo(TODAY)
                    .build());
            declare(source, "REVENUE_TRANSACTION_DATE", "DailyReceipts", "ReceiptDate", TODAY, null);

            assertThat(service.readiness(source).findings())
                    .extracting(ReadinessFinding::code, ReadinessFinding::subject)
                    .contains(tuple("SOURCE_OF_TRUTH_MISSING", "REVENUE_AMOUNT"));
        }

        @Test
        @DisplayName("Declaring a source of truth activates nothing")
        void should_notActivate_when_declared() {
            Temple temple = fixture.temple("SoT no activation", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();

            declare(source, "REVENUE_AMOUNT", "DailyReceipts", "Amount", TODAY, null);

            assertThat(sourceSystems.findById(source).orElseThrow().isSyncEnabled()).isFalse();
            assertThat(service.readiness(source).connectivityVerified()).isFalse();
        }

        @Test
        @DisplayName("The catalogue names the metrics a declaration may use, and which are required")
        void should_serveMetricVocabulary_when_catalogueIsRead() {
            CapabilityCatalogueResponse catalogue = service.capabilityCatalogue();

            assertThat(catalogue.metrics())
                    .extracting(CapabilityCatalogueResponse.MetricOption::metric)
                    .contains("REVENUE_AMOUNT", "REVENUE_TRANSACTION_DATE",
                            "REVENUE_CANCELLED_AMOUNT");
            assertThat(catalogue.metrics().stream()
                    .filter(CapabilityCatalogueResponse.MetricOption::required)
                    .map(CapabilityCatalogueResponse.MetricOption::metric))
                    .as("taken from RevenueField, which is what normalization actually refuses "
                            + "a batch without")
                    .containsExactlyInAnyOrder("REVENUE_AMOUNT", "REVENUE_TRANSACTION_DATE");
        }

        // ------------------------------------------------------------- helpers

        private Long registerFor(String templeName) {
            Temple temple = fixture.temple(templeName, DISTRICT_A);
            return service.register(registration(temple.getId())).id();
        }

        private SourceOfTruthDeclarationResponse declare(Long sourceSystemId, String metric,
                                                         String object, String field,
                                                         LocalDate effectiveFrom,
                                                         Integer supersedes) {
            return service.declareSourceOfTruth(sourceSystemId,
                    request(metric, object, field, effectiveFrom, supersedes));
        }

        private DeclareSourceOfTruthRequest request(String metric, String object, String field,
                                                     LocalDate effectiveFrom, Integer supersedes) {
            DeclareSourceOfTruthRequest request = new DeclareSourceOfTruthRequest();
            request.setMetric(metric);
            request.setSourceObject(object);
            request.setSourceField(field);
            request.setEffectiveFrom(effectiveFrom);
            request.setSupersedesVersion(supersedes);
            return request;
        }
    }

    // ---------------------------------------------- activation (FIN-140-D)

    @Nested
    @DisplayName("Activation")
    class Activation {

        @Test
        @DisplayName("A ready source system can be enabled, and sync_enabled changes")
        void should_enable_when_readinessIsClean() {
            Long source = fullyConfigured("Activate ready");

            SourceSystemActivationResponse result = service.setActivation(source, enable());

            assertThat(result.enabledForSync()).isTrue();
            assertThat(result.changed()).isTrue();
            assertThat(result.readinessStatus()).isNotEqualTo(ReadinessStatus.BLOCKED);
            assertThat(sourceSystems.findById(source).orElseThrow().isSyncEnabled()).isTrue();
        }

        /**
         * The single most important assertion in this slice. Enabling grants a permission; it does
         * not mean anything is running, and the response must not let a reader think otherwise.
         */
        @Test
        @DisplayName("Enabling states plainly that nothing is synchronising")
        void should_stateTheLimits_when_enabled() {
            Long source = fullyConfigured("Activate limits");

            SourceSystemActivationResponse result = service.setActivation(source, enable());

            assertThat(result.syncInfrastructureAvailable())
                    .as("nothing reachable from this runtime will synchronise this source: "
                            + "FIN-058's trigger is manual, worker-side, and has no caller here")
                    .isFalse();
            assertThat(result.connectorDeploymentVerified())
                    .as("the connector registry is a sync-worker bean this runtime cannot see")
                    .isFalse();
            // The wording changed in FIN-058 because the old wording stopped being true: a
            // connector IS now deployed and a trigger DOES now exist. What must stay true, and is
            // what this test has always been about, is that enabling starts nothing.
            assertThat(result.activationNote())
                    .contains("permission")
                    .contains("starts no synchronisation")
                    .contains("no scheduler");
        }

        @Test
        @DisplayName("A source with a blocking readiness finding cannot be enabled, and nothing changes")
        void should_refuse_when_readinessIsBlocked() {
            Temple temple = fixture.temple("Activate blocked", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();

            assertThatThrownBy(() -> service.setActivation(source, enable()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NO_CAPABILITY_DECLARED")
                    .hasMessageContaining("Nothing was changed");

            assertThat(sourceSystems.findById(source).orElseThrow().isSyncEnabled()).isFalse();
        }

        @Test
        @DisplayName("A refused activation writes no audit record")
        void should_writeNoAudit_when_refused() {
            Temple temple = fixture.temple("Activate no audit", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();

            assertThatThrownBy(() -> service.setActivation(source, enable()))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(activationEvents(source))
                    .as("a successful-looking audit line for a change that did not happen is worse "
                            + "than no line at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("Warnings alone never block activation")
        void should_enable_when_onlyWarningsArePresent() {
            Temple temple = fixture.temple("Activate warnings", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.capability(temple.getId(), source, FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, "Receipts recorded in full.", YESTERDAY);
            fixture.declaration(source, "REVENUE_AMOUNT");
            fixture.declaration(source, "REVENUE_TRANSACTION_DATE");
            fixture.rule(source, "SANNIDHI:DS", "SEVA", 100);
            // Eighteen capabilities left undeclared: CAPABILITY_NOT_DECLARED, a warning.

            SourceSystemActivationResponse result = service.setActivation(source, enable());

            assertThat(result.enabledForSync()).isTrue();
            assertThat(result.readinessStatus()).isEqualTo(ReadinessStatus.WARNING);
            assertThat(result.warnings()).isNotEmpty();
        }

        @Test
        @DisplayName("Enabling twice is a no-op, not an error and not a second audit line")
        void should_beIdempotent_when_enabledTwice() {
            Long source = fullyConfigured("Activate twice");
            service.setActivation(source, enable());

            SourceSystemActivationResponse second = service.setActivation(source, enable());

            assertThat(second.enabledForSync()).isTrue();
            assertThat(second.changed()).isFalse();
            assertThat(activationEvents(source)).hasSize(1);
        }

        /**
         * A no-op must not fail because the configuration drifted after it was enabled. Re-stating
         * a state nobody is changing is not the moment to refuse — the readiness screen is.
         */
        @Test
        @DisplayName("Re-confirming an enabled source succeeds even once its configuration has gone blocking")
        void should_notRefuseNoOp_when_configurationLaterBlocks() {
            Temple temple = fixture.temple("Activate then break", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.makeFullyConfigured(temple.getId(), source);
            service.setActivation(source, enable());

            capabilities.findBySourceSystemIdAndDeletedFalse(source).forEach(row -> {
                row.setDeleted(true);
                capabilities.saveAndFlush(row);
            });

            SourceSystemActivationResponse result = service.setActivation(source, enable());

            assertThat(result.changed()).isFalse();
            assertThat(result.readinessStatus()).isEqualTo(ReadinessStatus.BLOCKED);
            assertThat(result.enabledForSync()).isTrue();
        }

        @Test
        @DisplayName("Disabling is never blocked by readiness — a switch that only turns on is not a switch")
        void should_disable_when_readinessIsBlocked() {
            Temple temple = fixture.temple("Disable blocked", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.makeFullyConfigured(temple.getId(), source);
            service.setActivation(source, enable());

            capabilities.findBySourceSystemIdAndDeletedFalse(source).forEach(row -> {
                row.setDeleted(true);
                capabilities.saveAndFlush(row);
            });

            SourceSystemActivationResponse result = service.setActivation(source, disable("Source is being migrated."));

            assertThat(result.enabledForSync()).isFalse();
            assertThat(result.changed()).isTrue();
        }

        @Test
        @DisplayName("Disabling without a reason is refused")
        void should_refuse_when_disablingWithoutAReason() {
            Long source = fullyConfigured("Disable no reason");
            service.setActivation(source, enable());

            assertThatThrownBy(() -> service.setActivation(source, disable(null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("needs a reason");

            assertThat(sourceSystems.findById(source).orElseThrow().isSyncEnabled()).isTrue();
        }

        @Test
        @DisplayName("Disabling deletes no configuration")
        void should_retainEverything_when_disabled() {
            Temple temple = fixture.temple("Disable retains", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.makeFullyConfigured(temple.getId(), source);
            service.setActivation(source, enable());

            int capabilitiesBefore = capabilities.findBySourceSystemIdAndDeletedFalse(source).size();
            int declarationsBefore = service.listSourceOfTruth(source).size();
            int rulesBefore = rules.findBySourceSystemIdAndDeletedFalse(source).size();

            service.setActivation(source, disable("Switching supplier."));

            assertThat(capabilities.findBySourceSystemIdAndDeletedFalse(source))
                    .hasSize(capabilitiesBefore);
            assertThat(service.listSourceOfTruth(source)).hasSize(declarationsBefore);
            assertThat(rules.findBySourceSystemIdAndDeletedFalse(source)).hasSize(rulesBefore);
        }

        @Test
        @DisplayName("Disabling twice is a no-op")
        void should_beIdempotent_when_disabledTwice() {
            Long source = fullyConfigured("Disable twice");

            SourceSystemActivationResponse result = service.setActivation(source, disable("Not needed."));

            assertThat(result.changed())
                    .as("a freshly registered source is already disabled")
                    .isFalse();
            assertThat(activationEvents(source)).isEmpty();
        }

        @Test
        @DisplayName("Both directions are audited, with the reason and without a credential alias")
        void should_audit_when_stateChanges() {
            Long source = fullyConfigured("Activate audit");

            service.setActivation(source, enable());
            service.setActivation(source, disable("Migrating to a new server."));

            var events = activationEvents(source);
            assertThat(events).hasSize(2);
            assertThat(events).extracting(AuditDataEvent::getAction)
                    .containsExactlyInAnyOrder("ENABLE_SYNC", "DISABLE_SYNC");
            assertThat(events).allSatisfy(event -> {
                assertThat(event.getActorRole()).isEqualTo(RoleConstants.SUPER_ADMIN);
                assertThat(event.getDetail()).doesNotContain("some-readonly-alias");
            });
            assertThat(events).anySatisfy(event ->
                    assertThat(event.getDetail())
                            .contains("syncEnabled true -> false")
                            .contains("Migrating to a new server."));
        }

        /**
         * The runtime boundary, asserted at the only place it could plausibly be crossed.
         *
         * <p>Activation is the one onboarding operation whose name suggests it should reach a
         * temple. It must not, and the evidence is that no batch row appears and the registry
         * context still holds no connector — the latter enforced permanently by
         * {@code RegistryRuntimeContextTest}.
         */
        @Test
        @DisplayName("Activation creates no sync batch and contacts nothing")
        void should_createNoBatch_when_enabled() {
            Long source = fullyConfigured("Activate no batch");
            long batchesBefore = batches.count();

            service.setActivation(source, enable());

            assertThat(batches.count())
                    .as("enabling grants permission; it does not queue work")
                    .isEqualTo(batchesBefore);
            assertThat(errors.count()).isZero();
        }

        @Test
        @DisplayName("Unsigned source-of-truth declarations are warned about, never blocked on")
        void should_warnAboutUnapprovedDeclarations_when_enabling() {
            Long source = fullyConfigured("Activate unapproved");

            SourceSystemActivationResponse result = service.setActivation(source, enable());

            assertThat(result.enabledForSync())
                    .as("FIN-D-086: approval is not an activation gate")
                    .isTrue();
            assertThat(result.warnings())
                    .anySatisfy(warning -> assertThat(warning).contains("Awaiting sign-off"));
        }

        @Test
        @DisplayName("A signed-off declaration produces no sign-off warning")
        void should_notWarn_when_declarationsAreApproved() {
            Temple temple = fixture.temple("Activate approved", DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.capability(temple.getId(), source, FinanceCapability.REVENUE,
                    DataAvailability.AVAILABLE, "Receipts recorded in full.", YESTERDAY);
            fixture.declareRemainingAsNotApplicable(temple.getId(), source);
            fixture.rule(source, "SANNIDHI:DS", "SEVA", 100);
            approvedDeclaration(source, "REVENUE_AMOUNT");
            approvedDeclaration(source, "REVENUE_TRANSACTION_DATE");

            SourceSystemActivationResponse result = service.setActivation(source, enable());

            assertThat(result.warnings())
                    .noneSatisfy(warning -> assertThat(warning).contains("Awaiting sign-off"));
        }

        @Test
        @DisplayName("A source system that does not exist is reported absent")
        void should_refuse_when_sourceSystemIsAbsent() {
            assertThatThrownBy(() -> service.setActivation(9_999_999L, enable()))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Enabling one temple's source leaves another temple's alone")
        void should_isolateAcrossTemples_when_enabling() {
            Long sourceA = fullyConfigured("Activate isolation A");
            Temple templeB = fixture.temple("Activate isolation B", DISTRICT_B);
            Long sourceB = service.register(templeBRegistration(templeB.getId())).id();

            service.setActivation(sourceA, enable());

            assertThat(sourceSystems.findById(sourceB).orElseThrow().isSyncEnabled()).isFalse();
        }

        @Test
        @DisplayName("Readiness still reports the flag after activation, and still verifies no connectivity")
        void should_reportEnabled_when_readinessIsReadAfterwards() {
            Long source = fullyConfigured("Activate then readiness");

            service.setActivation(source, enable());
            SourceSystemReadinessResponse readiness = service.readiness(source);

            assertThat(readiness.syncEnabled()).isTrue();
            assertThat(readiness.connectivityVerified())
                    .as("enabling a source proves nothing about whether it answers")
                    .isFalse();
        }

        // ------------------------------------------------------------- helpers

        private Long fullyConfigured(String templeName) {
            Temple temple = fixture.temple(templeName, DISTRICT_A);
            Long source = service.register(registration(temple.getId())).id();
            fixture.makeFullyConfigured(temple.getId(), source);
            return source;
        }

        private void approvedDeclaration(Long sourceSystemId, String metric) {
            DeclareSourceOfTruthRequest request = new DeclareSourceOfTruthRequest();
            request.setMetric(metric);
            request.setSourceObject("DailyReceipts");
            request.setSourceField("Amount");
            request.setEffectiveFrom(TODAY);
            request.setApproved(true);
            service.declareSourceOfTruth(sourceSystemId, request);
        }

        private List<AuditDataEvent> activationEvents(Long sourceSystemId) {
            return auditEvents.findAll().stream()
                    .filter(event -> "FIN_SOURCE_SYSTEM".equals(event.getEntityType()))
                    .filter(event -> sourceSystemId.equals(event.getEntityId()))
                    .filter(event -> event.getAction().endsWith("_SYNC"))
                    .toList();
        }

        private SetSourceSystemActivationRequest enable() {
            SetSourceSystemActivationRequest request = new SetSourceSystemActivationRequest();
            request.setEnabled(true);
            return request;
        }

        private SetSourceSystemActivationRequest disable(String reason) {
            SetSourceSystemActivationRequest request = new SetSourceSystemActivationRequest();
            request.setEnabled(false);
            request.setReason(reason);
            return request;
        }
    }
}

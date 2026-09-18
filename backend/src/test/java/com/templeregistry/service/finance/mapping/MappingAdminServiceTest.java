package com.templeregistry.service.finance.mapping;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.finance.CreateMappingRuleRequest;
import com.templeregistry.dto.request.finance.MappingRuleStatusRequest;
import com.templeregistry.dto.request.finance.UpdateMappingRuleRequest;
import com.templeregistry.dto.response.finance.MappingRuleMutationResponse;
import com.templeregistry.dto.response.finance.MappingRuleResponse;
import com.templeregistry.dto.response.finance.NamespaceCatalogueResponse;
import com.templeregistry.dto.response.finance.UnresolvedValueResponse;
import com.templeregistry.entity.audit.AuditDataEvent;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.DuplicateResourceException;
import com.templeregistry.repository.audit.AuditDataEventRepository;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.service.finance.pipeline.MappingRuleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import com.templeregistry.security.ScopeHelper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.templeregistry.service.finance.mapping.MappingAdminTestFixture.DISTRICT_A;
import static com.templeregistry.service.finance.mapping.MappingAdminTestFixture.authenticateAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-054A behaviour, against real MySQL with the real migrations.
 *
 * <p>The assertions that matter here are the ones about what a mapping change is <em>not</em>
 * allowed to do — not touch a fact, not accept a rule the engine could never fire, not let one
 * administrator's edit vanish under another's. The CRUD is the easy part.
 */
@SpringBootTest
@ActiveProfiles("test")
class MappingAdminServiceTest extends FinanceApiTestBase {

    @Autowired private MappingAdminService service;
    @Autowired private TempleRepository temples;
    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinMappingRuleRepository rules;
    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository decisions;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private FinRevenueFactRepository facts;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private AuditDataEventRepository auditEvents;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
    private TransactionTemplate tx;

    private MappingAdminTestFixture fixture;
    private Temple temple;
    private FinSourceSystem source;

    @BeforeEach
    void setUp() {
        fixture = new MappingAdminTestFixture(
                temples, sourceSystems, rules, staging, decisions, batches);
        tx = new TransactionTemplate(txManager);
        authenticateAs("SUPER_ADMIN", 4001L, null, null);
        temple = fixture.temple("Mapping admin test temple", DISTRICT_A);
        source = fixture.sourceSystem(temple, "SYNTH-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- creating

    @Test
    @DisplayName("A created rule stores the namespace and the value as one namespaced string")
    void should_composeStoredValue_when_ruleIsCreated() {
        MappingRuleMutationResponse created = service.create(request("SEVA_CODE", "430", "SEVA"));

        assertThat(created.rule().storedValue()).isEqualTo("SEVA_CODE:430");
        assertThat(created.rule().namespace()).isEqualTo("SEVA_CODE");
        assertThat(created.rule().sourceValue()).isEqualTo("430");
        assertThat(created.rule().wellFormed()).isTrue();
        assertThat(rules.findById(created.rule().id()).orElseThrow().getSourceValue())
                .isEqualTo("SEVA_CODE:430");
    }

    @Test
    @DisplayName("A created rule is one the engine can actually read back and fire")
    void should_produceRuleTheResolverCanFire_when_ruleIsCreated() {
        MappingRuleMutationResponse created = service.create(request("SEVA_CODE", "430", "SEVA"));

        // The point of the shared SourceValueKey: what the API writes, the engine must match.
        MappingRuleResolver resolver = new MappingRuleResolver(
                MappingType.REVENUE_CATEGORY,
                List.of(rules.findById(created.rule().id()).orElseThrow()),
                Set.of("SEVA"));
        assertThat(resolver.configurationProblems()).isEmpty();
        assertThat(resolver.resolve(Map.of("SEVA_CODE", "430")).canonicalValue()).isEqualTo("SEVA");
    }

    @Test
    @DisplayName("Every write says plainly that published figures are not corrected by it")
    void should_stateHistoricalEffect_when_ruleIsWritten() {
        MappingRuleMutationResponse created = service.create(request("SEVA_CODE", "430", "SEVA"));

        assertThat(created.historicalEffect())
                .isEqualTo(MappingRuleMutationResponse.HISTORICAL_EFFECT)
                .contains("does not re-process or correct historical data");
    }

    @Test
    @DisplayName("A source value with no field name is refused, not saved as an inert rule")
    void should_refuseRule_when_namespaceIsMissing() {
        CreateMappingRuleRequest rq = request("", "430", "SEVA");

        assertThatThrownBy(() -> service.create(rq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs the name of the staged field");
        assertThat(rules.findBySourceSystemIdAndDeletedFalse(source.getId())).isEmpty();
    }

    @Test
    @DisplayName("A field name containing the separator is refused — it would split in the wrong place")
    void should_refuseRule_when_namespaceContainsSeparator() {
        CreateMappingRuleRequest rq = request("SEVA:CODE", "430", "SEVA");

        assertThatThrownBy(() -> service.create(rq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("may not contain");
    }

    @Test
    @DisplayName("A canonical value that is not a live category is refused at write, not at run time")
    void should_refuseRule_when_canonicalValueIsUnknown() {
        CreateMappingRuleRequest rq = request("SEVA_CODE", "430", "NO_SUCH_CATEGORY");

        assertThatThrownBy(() -> service.create(rq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an active revenue category");
    }

    @Test
    @DisplayName("A second rule for the same source value is refused, naming the rule that has it")
    void should_refuseRule_when_sourceValueIsAlreadyMapped() {
        MappingRuleMutationResponse first = service.create(request("SEVA_CODE", "430", "SEVA"));
        CreateMappingRuleRequest duplicate = request("SEVA_CODE", "430", "DONATION");

        assertThatThrownBy(() -> service.create(duplicate))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Rule " + first.rule().id());
    }

    @Test
    @DisplayName("A mapping type nothing in the pipeline reads cannot be created")
    void should_refuseRule_when_mappingTypeIsInert() {
        CreateMappingRuleRequest rq = request("METAL", "GOLD", "GOLD");
        rq.setMappingType(MappingType.METAL_TYPE);

        assertThatThrownBy(() -> service.create(rq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never takes effect");
    }

    // -------------------------------------------------------------- editing

    @Test
    @DisplayName("An edit changes the rule and increments its version")
    void should_incrementVersion_when_ruleIsEdited() {
        MappingRuleResponse created = service.create(request("SEVA_CODE", "430", "SEVA")).rule();

        MappingRuleResponse edited = service.update(created.id(),
                update(created.version(), "SEVA_CODE", "430", "DONATION")).rule();

        assertThat(edited.canonicalValue()).isEqualTo("DONATION");
        assertThat(edited.version()).isGreaterThan(created.version());
    }

    @Test
    @DisplayName("An edit written against a stale version is refused, so neither writer loses silently")
    void should_refuseEdit_when_versionIsStale() {
        MappingRuleResponse created = service.create(request("SEVA_CODE", "430", "SEVA")).rule();
        service.update(created.id(), update(created.version(), "SEVA_CODE", "430", "DONATION"));

        // The second administrator still holds the version they loaded before the first one saved.
        UpdateMappingRuleRequest stale = update(created.version(), "SEVA_CODE", "430", "HUNDI_DONATION");

        assertThatThrownBy(() -> service.update(created.id(), stale))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("has changed since it was loaded");
        assertThat(rules.findById(created.id()).orElseThrow().getCanonicalValue())
                .isEqualTo("DONATION");
    }

    @Test
    @DisplayName("Deactivating a rule leaves it visible and stops it mattering to the next run")
    void should_retainRule_when_deactivated() {
        MappingRuleResponse created = service.create(request("SEVA_CODE", "430", "SEVA")).rule();

        MappingRuleStatusRequest rq = new MappingRuleStatusRequest();
        rq.setActive(false);
        rq.setVersion(created.version());
        service.setActive(created.id(), rq);

        assertThat(rules.findById(created.id()).orElseThrow().isActive()).isFalse();
        assertThat(rules.findBySourceSystemIdAndMappingTypeAndActiveTrueAndDeletedFalse(
                source.getId(), MappingType.REVENUE_CATEGORY)).isEmpty();
        assertThat(service.getRule(created.id()).active()).isFalse();
    }

    @Test
    @DisplayName("A rule of an inert type can still be retired, because retiring it is the only use for it")
    void should_allowDeactivation_when_mappingTypeIsInert() {
        FinMappingRule metal = rules.saveAndFlush(FinMappingRule.builder()
                .sourceSystemId(source.getId())
                .mappingType(MappingType.METAL_TYPE)
                .sourceValue("METAL:GOLD")
                .canonicalValue("GOLD")
                .priority(100)
                .active(true)
                .build());

        MappingRuleStatusRequest rq = new MappingRuleStatusRequest();
        rq.setActive(false);
        rq.setVersion(metal.getVersion());
        service.setActive(metal.getId(), rq);

        assertThat(rules.findById(metal.getId()).orElseThrow().isActive()).isFalse();
    }

    // ----------------------------------------------- the financial integrity line

    @Test
    @DisplayName("Editing a rule changes no canonical fact — not the amount, not the category, not the row")
    void should_leaveFactsUntouched_when_ruleIsEdited() {
        FinSyncBatch batch = fixture.batch(temple, source);
        long categoryId = categories.findByCategoryCodeAndDeletedFalse("SEVA").orElseThrow().getId();
        tx.execute(status -> facts.upsert(temple.getId(), source.getId(), batch.getId(), 1,
                "rec-1", LocalDate.of(2025, 5, 1), "2025-26", null, categoryId,
                "UNRECORDED", "INFERRED", null, null, 3L, new BigDecimal("1500.00"),
                0L, BigDecimal.ZERO, null, "INR", LocalDateTime.now()));
        List<FinRevenueFact> before = facts.findBySyncBatchIdOrderByIdAsc(batch.getId());
        assertThat(before).hasSize(1);

        MappingRuleResponse created = service.create(request("SEVA_CODE", "430", "SEVA")).rule();
        service.update(created.id(), update(created.version(), "SEVA_CODE", "430", "DONATION"));

        List<FinRevenueFact> after = facts.findBySyncBatchIdOrderByIdAsc(batch.getId());
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getCategoryId()).isEqualTo(before.get(0).getCategoryId());
        assertThat(after.get(0).getGrossAmount())
                .usingComparator(BigDecimal::compareTo).isEqualTo(before.get(0).getGrossAmount());
        assertThat(after.get(0).getUpdatedAt()).isEqualTo(before.get(0).getUpdatedAt());
    }

    @Test
    @DisplayName("A failed edit leaves the facts alone too — nothing is written before validation runs")
    void should_leaveFactsUntouched_when_editIsRefused() {
        FinSyncBatch batch = fixture.batch(temple, source);
        long categoryId = categories.findByCategoryCodeAndDeletedFalse("SEVA").orElseThrow().getId();
        tx.execute(status -> facts.upsert(temple.getId(), source.getId(), batch.getId(), 1,
                "rec-1", LocalDate.of(2025, 5, 2), "2025-26", null, categoryId,
                "UNRECORDED", "INFERRED", null, null, 1L, new BigDecimal("42.00"),
                0L, BigDecimal.ZERO, null, "INR", LocalDateTime.now()));
        long factsBefore = facts.countByTempleId(temple.getId());

        CreateMappingRuleRequest rq = request("SEVA_CODE", "430", "NO_SUCH_CATEGORY");
        assertThatThrownBy(() -> service.create(rq)).isInstanceOf(IllegalStateException.class);

        assertThat(facts.countByTempleId(temple.getId())).isEqualTo(factsBefore);
    }

    @Test
    @DisplayName("Saving a rule does not re-map anything that is already staged")
    void should_leaveStagedDecisionsUntouched_when_ruleIsCreated() {
        FinSyncBatch batch = fixture.batch(temple, source);
        fixture.stagedRecord(batch, "rec-1", "{\"SEVA_CODE\":\"430\"}",
                "SEVA_CODE", "430", MappingOutcome.UNMAPPED);

        service.create(request("SEVA_CODE", "430", "SEVA"));

        assertThat(decisions.countBySyncBatchIdAndMappingTypeAndOutcome(
                batch.getId(), MappingType.REVENUE_CATEGORY, MappingOutcome.UNMAPPED))
                .as("the existing decision still says UNMAPPED until the batch is re-run")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- audit

    @Test
    @DisplayName("Every write leaves an audit row naming the actor, the rule and what changed")
    void should_writeAuditEvent_when_ruleIsCreatedAndEdited() {
        MappingRuleResponse created = service.create(request("SEVA_CODE", "430", "SEVA")).rule();
        service.update(created.id(), update(created.version(), "SEVA_CODE", "430", "DONATION"));

        List<AuditDataEvent> events = auditEvents
                .findAllByEntityTypeAndEntityId("FIN_MAPPING_RULE", created.id(),
                        PageRequest.of(0, 10))
                .getContent();
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getActorId()).isEqualTo(4001L);
            assertThat(event.getActorRole()).isEqualTo("SUPER_ADMIN");
        });
        assertThat(events).extracting(AuditDataEvent::getAction)
                .containsExactlyInAnyOrder("CREATE", "UPDATE");
        assertThat(events).filteredOn(e -> "UPDATE".equals(e.getAction()))
                .singleElement()
                .satisfies(e -> assertThat(e.getDetail()).contains("was ").contains("now ")
                        .contains("DONATION"));
    }

    @Test
    @DisplayName("A rule change and its audit row are one transaction — the rule does not survive alone")
    void should_rollBackRule_when_auditWriteFails() {
        // audit_data_events.actor_role is VARCHAR(32). A principal whose role claim is longer
        // makes the audit insert — and only the audit insert — fail, which is the one way to
        // observe from outside whether the rule change was tied to it. The granted authority is
        // a real role, so authorization still passes and the create gets as far as the audit.
        authenticateAsRoleClaim("SUPER_ADMIN", "A_ROLE_NAME_FAR_TOO_LONG_FOR_THE_AUDIT_COLUMN");

        assertThatThrownBy(() -> service.create(request("SEVA_CODE", "430", "SEVA")))
                .as("a refused audit row must refuse the change it was recording")
                .isInstanceOf(Exception.class);

        authenticateAs("SUPER_ADMIN", 4001L, null, null);
        assertThat(rules.findBySourceSystemIdAndDeletedFalse(source.getId()))
                .as("the rule must not outlive the audit row that was supposed to record it")
                .isEmpty();
        assertThat(auditEvents.findAllByActorIdOrderByOccurredAtDesc(4002L, PageRequest.of(0, 5))
                .getContent())
                .as("and no half-written audit row is left behind either")
                .isEmpty();
    }

    /** Authenticated with a real granted authority but an over-long role claim. */
    private void authenticateAsRoleClaim(String grantedRole, String roleClaim) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new ScopeHelper.Claims(4002L, roleClaim, null, null, "sa", "EDIT"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + grantedRole))));
    }

    // --------------------------------------------------------- listing

    @Test
    @DisplayName("The rule list is paged, filtered and scoped to one source system")
    void should_pageAndFilter_when_rulesAreListed() {
        FinSourceSystem other = fixture.sourceSystem(temple, "OTHER-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        fixture.rule(source.getId(), "SEVA_CODE:430", "SEVA", 100);
        fixture.rule(source.getId(), "SEVA_CODE:431", "DONATION", 100);
        fixture.rule(other.getId(), "SEVA_CODE:999", "SEVA", 100);

        PaginatedResponse<MappingRuleResponse> page =
                service.listRules(source.getId(), null, null, null, null, 0, 1, null);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);

        PaginatedResponse<MappingRuleResponse> filtered =
                service.listRules(source.getId(), null, null, "DONATION", null, 0, 20, null);
        assertThat(filtered.getContent()).singleElement()
                .satisfies(r -> assertThat(r.sourceValue()).isEqualTo("431"));

        PaginatedResponse<MappingRuleResponse> searched =
                service.listRules(source.getId(), null, null, null, "431", 0, 20, null);
        assertThat(searched.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("A malformed rule already in the database is listed and flagged, not hidden")
    void should_flagMalformedRule_when_listed() {
        fixture.rule(source.getId(), "430", "SEVA", 100);

        MappingRuleResponse listed = service
                .listRules(source.getId(), null, null, null, null, 0, 20, null)
                .getContent().get(0);

        assertThat(listed.wellFormed()).isFalse();
        assertThat(listed.namespace()).isNull();
        assertThat(listed.storedValue()).isEqualTo("430");
    }

    @Test
    @DisplayName("A sort key outside the allow-list is refused rather than passed into the query")
    void should_refuseSort_when_keyIsNotAllowListed() {
        assertThatThrownBy(() -> service.listRules(
                source.getId(), null, null, null, null, 0, 20, "(SELECT 1)"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a sortable field");
    }

    @Test
    @DisplayName("An allow-listed sort key is honoured in both directions")
    void should_sort_when_keyIsAllowListed() {
        fixture.rule(source.getId(), "SEVA_CODE:1", "SEVA", 10);
        fixture.rule(source.getId(), "SEVA_CODE:2", "SEVA", 900);

        assertThat(service.listRules(source.getId(), null, null, null, null, 0, 20, "priority,asc")
                .getContent()).extracting(MappingRuleResponse::priority)
                .containsExactly(10, 900);
        assertThat(service.listRules(source.getId(), null, null, null, null, 0, 20, "priority,desc")
                .getContent()).extracting(MappingRuleResponse::priority)
                .containsExactly(900, 10);
    }

    // ----------------------------------------------------------- unresolved

    @Test
    @DisplayName("Unresolved values come from the newest batch, worst first, with the field to key on")
    void should_reportUnresolvedValues_when_aBatchHasThem() {
        FinSyncBatch batch = fixture.batch(temple, source);
        fixture.stagedRecord(batch, "r1", "{\"SEVA_CODE\":\"430\"}", "SEVA_CODE", "430", MappingOutcome.UNMAPPED);
        fixture.stagedRecord(batch, "r2", "{\"SEVA_CODE\":\"430\"}", "SEVA_CODE", "430", MappingOutcome.UNMAPPED);
        fixture.stagedRecord(batch, "r3", "{\"SEVA_CODE\":\"431\"}", "SEVA_CODE", "431", MappingOutcome.UNMAPPED);

        UnresolvedValueResponse unresolved =
                service.listUnresolved(source.getId(), MappingOutcome.UNMAPPED);

        assertThat(unresolved.syncBatchId()).isEqualTo(batch.getId());
        assertThat(unresolved.values()).extracting(
                        UnresolvedValueResponse.UnresolvedValue::sourceValue,
                        UnresolvedValueResponse.UnresolvedValue::affected,
                        UnresolvedValueResponse.UnresolvedValue::namespace)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("430", 2L, "SEVA_CODE"),
                        org.assertj.core.api.Assertions.tuple("431", 1L, "SEVA_CODE"));
    }

    @Test
    @DisplayName("An older batch's unresolved values are not added to the newest batch's counts")
    void should_reportOnlyNewestBatch_when_severalBatchesHaveDecisions() {
        FinSyncBatch older = fixture.batch(temple, source);
        fixture.stagedRecord(older, "r1", "{\"SEVA_CODE\":\"430\"}", "SEVA_CODE", "430", MappingOutcome.UNMAPPED);
        FinSyncBatch newer = fixture.batch(temple, source);
        fixture.stagedRecord(newer, "r2", "{\"SEVA_CODE\":\"430\"}", "SEVA_CODE", "430", MappingOutcome.UNMAPPED);

        UnresolvedValueResponse unresolved =
                service.listUnresolved(source.getId(), MappingOutcome.UNMAPPED);

        assertThat(unresolved.syncBatchId()).isEqualTo(newer.getId());
        assertThat(unresolved.values()).singleElement()
                .satisfies(v -> assertThat(v.affected())
                        .as("re-staging the same record must not be counted twice")
                        .isEqualTo(1L));
    }

    @Test
    @DisplayName("A source with nothing mapped yet reports no batch, so empty is not read as clean")
    void should_reportNoBatch_when_nothingHasBeenMapped() {
        UnresolvedValueResponse unresolved =
                service.listUnresolved(source.getId(), MappingOutcome.UNMAPPED);

        assertThat(unresolved.syncBatchId()).isNull();
        assertThat(unresolved.values()).isEmpty();
    }

    // ------------------------------------------------------------ namespaces

    @Test
    @DisplayName("The namespace catalogue is the fields the staged payloads actually carry")
    void should_reportObservedFields_when_payloadsExist() {
        FinSyncBatch batch = fixture.batch(temple, source);
        fixture.stagedRecord(batch, "r1", "{\"SEVA_CODE\":\"430\",\"SANNIDHI\":\"KN\",\"amount\":\"10\"}",
                "SEVA_CODE", "430", MappingOutcome.UNMAPPED);
        fixture.rule(source.getId(), "BUCKET:DS", "SEVA", 100);

        NamespaceCatalogueResponse catalogue = service.namespaces(source.getId());

        assertThat(catalogue.observed()).containsExactly("SANNIDHI", "SEVA_CODE", "amount");
        assertThat(catalogue.inUseByRules()).containsExactly("BUCKET");
        assertThat(catalogue.sampledRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("A namespace no staged payload carries is saved, and the response says it will never match")
    void should_warn_when_namespaceIsNotObserved() {
        FinSyncBatch batch = fixture.batch(temple, source);
        fixture.stagedRecord(batch, "r1", "{\"SEVA_CODE\":\"430\"}",
                "SEVA_CODE", "430", MappingOutcome.UNMAPPED);

        MappingRuleMutationResponse created = service.create(request("SEVACODE", "430", "SEVA"));

        assertThat(created.rule().id()).isNotNull();
        assertThat(created.warnings()).singleElement()
                .asString().contains("never match").contains("SEVACODE");
    }

    @Test
    @DisplayName("A namespace the payloads do carry produces no warning")
    void should_notWarn_when_namespaceIsObserved() {
        FinSyncBatch batch = fixture.batch(temple, source);
        fixture.stagedRecord(batch, "r1", "{\"SEVA_CODE\":\"430\"}",
                "SEVA_CODE", "430", MappingOutcome.UNMAPPED);

        assertThat(service.create(request("SEVA_CODE", "430", "SEVA")).warnings()).isEmpty();
    }

    @Test
    @DisplayName("With nothing staged, the warning says the namespace cannot be confirmed either way")
    void should_warnDifferently_when_nothingHasBeenStaged() {
        MappingRuleMutationResponse created = service.create(request("SEVA_CODE", "430", "SEVA"));

        assertThat(created.warnings()).singleElement()
                .asString().contains("No records have been staged");
    }

    // --------------------------------------------------------------- helpers

    private CreateMappingRuleRequest request(String namespace, String value, String canonical) {
        CreateMappingRuleRequest rq = new CreateMappingRuleRequest();
        rq.setSourceSystemId(source.getId());
        rq.setMappingType(MappingType.REVENUE_CATEGORY);
        rq.setNamespace(namespace);
        rq.setSourceValue(value);
        rq.setCanonicalValue(canonical);
        rq.setPriority(100);
        rq.setActive(true);
        return rq;
    }

    private UpdateMappingRuleRequest update(Integer version, String namespace, String value,
                                            String canonical) {
        UpdateMappingRuleRequest rq = new UpdateMappingRuleRequest();
        rq.setVersion(version);
        rq.setNamespace(namespace);
        rq.setSourceValue(value);
        rq.setCanonicalValue(canonical);
        rq.setPriority(100);
        rq.setActive(true);
        return rq;
    }
}

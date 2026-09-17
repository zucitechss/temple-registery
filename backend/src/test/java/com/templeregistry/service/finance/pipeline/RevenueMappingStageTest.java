package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinStgRevenueMapping;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * FIN-054 against a real MySQL 8.0 container with the real migrations.
 *
 * <p>What is worth testing at this level is what commits: the idempotency constraint, the fact
 * that a re-run replaces a decision rather than adding one, that staging is not touched, and
 * that an undecided record never carries a canonical value. Pure resolution logic is tested
 * without a database in {@link MappingRuleResolverTest}.
 *
 * <p>Test methods are non-transactional on purpose — the stage commits each row separately, and
 * a test-managed rollback would hide the behaviour being verified.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class RevenueMappingStageTest {

    private static final long TEMPLE_A = 910001L;
    private static final long TEMPLE_B = 910002L;
    private static final long SOURCE_A = 8101L;
    private static final long SOURCE_B = 8102L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_mapping")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway owns the schema; "validate" would fail on the unrelated FIN-X-001 drift.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
    }

    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository mappings;
    @Autowired private FinMappingRuleRepository rules;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private FinSyncErrorRepository errors;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private RevenueMappingStage stage;
    private FinSyncBatch batch;

    @BeforeEach
    void setUp() {
        mappings.deleteAllInBatch();
        staging.deleteAllInBatch();
        errors.deleteAllInBatch();
        batches.deleteAllInBatch();
        rules.deleteAllInBatch();

        stage = newStage();
        batch = newBatch(TEMPLE_A, SOURCE_A);
    }

    // ---------------------------------------------------------------- the ordinary case

    @Test
    @DisplayName("A record whose value has a rule is mapped, and says which rule decided it")
    void should_recordMapping_when_ruleMatches() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"DS\",\"amount\":\"100.00\"}");

        RevenueMappingStage.Result result = stage.mapBatch(batch.getId());

        assertThat(result.count(MappingOutcome.MAPPED)).isEqualTo(1);
        FinStgRevenueMapping decision = decisionFor(id);
        assertThat(decision.getOutcome()).isEqualTo(MappingOutcome.MAPPED);
        assertThat(decision.getCanonicalValue()).isEqualTo("SEVA");
        assertThat(decision.getMappingRuleId()).isNotNull();
        assertThat(decision.getRulePriority()).isEqualTo(100);
    }

    /**
     * The decision must be investigable on its own. Staging retention is unresolved (Q7), so a
     * mapping row that could only be explained by joining to a staged record would become
     * unexplainable the day a purge is agreed.
     */
    @Test
    @DisplayName("A decision carries its own provenance, not a join to one")
    void should_preserveSourceIdentity_when_mapping() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        Long id = stage(batch, "receipt-99", "{\"BUCKET\":\"DS\"}");

        stage.mapBatch(batch.getId());

        FinStgRevenueMapping decision = decisionFor(id);
        assertThat(decision.getTempleId()).isEqualTo(TEMPLE_A);
        assertThat(decision.getSourceSystemId()).isEqualTo(SOURCE_A);
        assertThat(decision.getSyncBatchId()).isEqualTo(batch.getId());
        assertThat(decision.getSourceRecordRef()).isEqualTo("receipt-99");
        assertThat(decision.getSourceField()).isEqualTo("BUCKET");
        assertThat(decision.getSourceValue()).isEqualTo("DS");
    }

    /** Staging is evidence of what the source sent. An interpretation of it must not edit it. */
    @Test
    @DisplayName("Mapping does not touch the staged record")
    void should_leaveStagingUntouched_when_mapping() throws Exception {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        String payload = "{\"BUCKET\":\"DS\",\"amount\":\"9061629360.05\",\"note\":null}";
        Long id = stage(batch, "rec-1", payload);
        FinStgRevenue before = staging.findById(id).orElseThrow();
        LocalDateTime updatedBefore = before.getUpdatedAt();

        stage.mapBatch(batch.getId());

        FinStgRevenue after = staging.findById(id).orElseThrow();
        assertThat(after.getValidationStatus())
                .as("mapping is not a staging state change")
                .isEqualTo(StagingStatus.VALID);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(json.readTree(after.getRawJson()))
                .as("the payload must survive mapping exactly, field for field")
                .isEqualTo(json.readTree(payload));
    }

    @Test
    @DisplayName("Only VALID rows are mapped; unvalidated and rejected rows are left alone")
    void should_mapOnlyValidatedRows_when_batchIsMixed() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        Long valid = stage(batch, "ok", "{\"BUCKET\":\"DS\"}");
        Long received = stage(batch, "pending", "{\"BUCKET\":\"DS\"}", StagingStatus.RECEIVED);
        Long rejected = stage(batch, "bad", "{\"BUCKET\":\"DS\"}", StagingStatus.REJECTED);

        RevenueMappingStage.Result result = stage.mapBatch(batch.getId());

        assertThat(result.decided()).isEqualTo(1);
        assertThat(mappings.findByStgRevenueIdAndMappingType(valid, MappingType.REVENUE_CATEGORY))
                .isPresent();
        assertThat(mappings.findByStgRevenueIdAndMappingType(received, MappingType.REVENUE_CATEGORY))
                .as("a row nobody has validated must not be interpreted")
                .isEmpty();
        assertThat(mappings.findByStgRevenueIdAndMappingType(rejected, MappingType.REVENUE_CATEGORY))
                .isEmpty();
    }

    // ---------------------------------------------------------------- not decided

    @Test
    @DisplayName("An unmapped record is kept, routed to UNMAPPED, and never dropped")
    void should_keepRecord_when_valueIsUnmapped() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"ZZ\"}");

        RevenueMappingStage.Result result = stage.mapBatch(batch.getId());

        assertThat(result.count(MappingOutcome.UNMAPPED)).isEqualTo(1);
        FinStgRevenueMapping decision = decisionFor(id);
        assertThat(decision.getCanonicalValue()).isEqualTo("UNMAPPED");
        assertThat(decision.getSourceValue()).isEqualTo("ZZ");
        assertThat(decision.getMappingRuleId()).isNull();
    }

    /**
     * The guarantee that keeps a misclassification out of a published total: nothing undecided
     * may carry a canonical value, so FIN-056 has no way to load one by accident.
     */
    @Test
    @DisplayName("An undecided record carries no canonical value at all")
    void should_carryNoCanonicalValue_when_undecided() {
        seedRule(SOURCE_A, "BUCKET:KN", "DONATION", 100);
        seedRule(SOURCE_A, "CODE:430", "HUNDI_DONATION", 100);
        Long ambiguous = stage(batch, "amb", "{\"BUCKET\":\"KN\",\"CODE\":\"430\"}");
        Long inapplicable = stage(batch, "none", "{\"amount\":\"10.00\"}");

        stage.mapBatch(batch.getId());

        assertThat(decisionFor(ambiguous).getOutcome()).isEqualTo(MappingOutcome.AMBIGUOUS);
        assertThat(decisionFor(ambiguous).getCanonicalValue()).isNull();
        assertThat(decisionFor(inapplicable).getOutcome()).isEqualTo(MappingOutcome.NOT_APPLICABLE);
        assertThat(decisionFor(inapplicable).getCanonicalValue()).isNull();
    }

    @Test
    @DisplayName("A rule naming a category that does not exist is caught before loading")
    void should_reportInvalidConfiguration_when_categoryDoesNotExist() {
        seedRule(SOURCE_A, "BUCKET:DS", "NOT_A_CATEGORY", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");

        stage.mapBatch(batch.getId());

        assertThat(decisionFor(id).getOutcome()).isEqualTo(MappingOutcome.INVALID_CONFIGURATION);
        assertThat(decisionFor(id).getCanonicalValue()).isNull();
    }

    /** Every row NOT_APPLICABLE would look like clean data. An absent rule set is not. */
    @Test
    @DisplayName("A source with no usable rules fails loudly instead of mapping nothing")
    void should_fail_when_sourceHasNoUsableRules() {
        stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");

        assertThatThrownBy(() -> stage.mapBatch(batch.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no usable")
                .hasMessageContaining(String.valueOf(SOURCE_A));

        assertThat(mappings.count()).isZero();
    }

    @Test
    @DisplayName("Mapping a batch nobody recorded fails loudly")
    void should_fail_when_batchDoesNotExist() {
        assertThatThrownBy(() -> stage.mapBatch(987654L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("987654");
    }

    // ---------------------------------------------------------------- scope isolation

    /**
     * One temple's rules must never classify another's revenue. This is the mapping-stage form
     * of the isolation the fact table's grain enforces.
     */
    @Test
    @DisplayName("A rule belonging to another source system is not applied")
    void should_isolateSources_when_anotherSourceHasTheRule() {
        seedRule(SOURCE_B, "BUCKET:DS", "SEVA", 100);
        seedRule(SOURCE_A, "BUCKET:SS", "SPECIAL_SEVA", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");

        stage.mapBatch(batch.getId());

        assertThat(decisionFor(id).getOutcome())
                .as("source B's rule must not classify source A's revenue")
                .isEqualTo(MappingOutcome.UNMAPPED);
    }

    @Test
    @DisplayName("Two temples' batches are mapped independently")
    void should_isolateTemples_when_bothHaveRules() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        seedRule(SOURCE_B, "BUCKET:DS", "PRASADAM_SALE", 100);
        FinSyncBatch other = newBatch(TEMPLE_B, SOURCE_B);
        Long a = stage(batch, "a-1", "{\"BUCKET\":\"DS\"}");
        Long b = stage(other, "b-1", "{\"BUCKET\":\"DS\"}", StagingStatus.VALID, TEMPLE_B, SOURCE_B);

        stage.mapBatch(batch.getId());
        stage.mapBatch(other.getId());

        assertThat(decisionFor(a).getCanonicalValue()).isEqualTo("SEVA");
        assertThat(decisionFor(b).getCanonicalValue()).isEqualTo("PRASADAM_SALE");
        assertThat(decisionFor(a).getTempleId()).isEqualTo(TEMPLE_A);
        assertThat(decisionFor(b).getTempleId()).isEqualTo(TEMPLE_B);
    }

    @Test
    @DisplayName("A disabled rule is not applied")
    void should_ignoreRule_when_inactive() {
        FinMappingRule disabled = seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        disabled.setActive(false);
        rules.saveAndFlush(disabled);
        seedRule(SOURCE_A, "BUCKET:SS", "SPECIAL_SEVA", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");

        stage.mapBatch(batch.getId());

        assertThat(decisionFor(id).getOutcome()).isEqualTo(MappingOutcome.UNMAPPED);
    }

    @Test
    @DisplayName("A soft-deleted rule is not applied")
    void should_ignoreRule_when_softDeleted() {
        FinMappingRule removed = seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        removed.setDeleted(true);
        rules.saveAndFlush(removed);
        seedRule(SOURCE_A, "BUCKET:SS", "SPECIAL_SEVA", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");

        stage.mapBatch(batch.getId());

        assertThat(decisionFor(id).getOutcome()).isEqualTo(MappingOutcome.UNMAPPED);
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    @DisplayName("Running twice leaves one decision per record, not two")
    void should_beIdempotent_when_runTwice() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");

        stage.mapBatch(batch.getId());
        RevenueMappingStage.Result second = stage.mapBatch(batch.getId());

        assertThat(second.count(MappingOutcome.MAPPED)).isEqualTo(1);
        assertThat(mappings.findBySyncBatchIdAndMappingTypeOrderByIdAsc(
                batch.getId(), MappingType.REVENUE_CATEGORY)).hasSize(1);
        assertThat(decisionFor(id).getCanonicalValue()).isEqualTo("SEVA");
    }

    /**
     * The reason staging is retained at all: a misclassification is corrected by fixing the rule
     * and running again, with no second extraction and no contact with the source.
     */
    @Test
    @DisplayName("Correcting a rule and re-running replaces the decision")
    void should_replaceDecision_when_ruleIsCorrectedAndRerun() {
        seedRule(SOURCE_A, "BUCKET:KN", "DONATION", 100);
        Long id = stage(batch, "hundi-1", "{\"BUCKET\":\"KN\",\"CODE\":\"430\"}");
        stage.mapBatch(batch.getId());
        assertThat(decisionFor(id).getCanonicalValue()).isEqualTo("DONATION");

        // The override that separates donation-box collections from ordinary donations.
        seedRule(SOURCE_A, "CODE:430", "HUNDI_DONATION", 200);
        stage.mapBatch(batch.getId());

        assertThat(decisionFor(id).getCanonicalValue()).isEqualTo("HUNDI_DONATION");
        assertThat(decisionFor(id).getRulePriority()).isEqualTo(200);
        assertThat(mappings.findBySyncBatchIdAndMappingTypeOrderByIdAsc(
                batch.getId(), MappingType.REVENUE_CATEGORY)).hasSize(1);
    }

    @Test
    @DisplayName("The database refuses a second decision for one record and mapping type")
    void should_refuseDuplicate_when_secondDecisionIsWritten() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        Long id = stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");
        stage.mapBatch(batch.getId());

        assertThatThrownBy(() -> mappings.saveAndFlush(FinStgRevenueMapping.builder()
                .stgRevenueId(id)
                .templeId(TEMPLE_A)
                .sourceSystemId(SOURCE_A)
                .syncBatchId(batch.getId())
                .sourceRecordRef("rec-1")
                .mappingType(MappingType.REVENUE_CATEGORY)
                .outcome(MappingOutcome.MAPPED)
                .canonicalValue("DONATION")
                .mappedAt(LocalDateTime.now())
                .build()))
                .as("uk_fsrm_row_type is what makes a replay safe")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- errors

    /**
     * One error per distinct unresolved value rather than per row: a single missing rule can
     * account for a whole batch, and the actionable fact is which value and how many records.
     */
    @Test
    @DisplayName("Unmapped values are summarised once each, with the number of records affected")
    void should_summariseUnmapped_when_manyRecordsShareAValue() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        for (int i = 0; i < 5; i++) {
            stage(batch, "zz-" + i, "{\"BUCKET\":\"ZZ\"}");
        }
        stage(batch, "yy-1", "{\"BUCKET\":\"YY\"}");

        stage.mapBatch(batch.getId());

        List<FinSyncError> mapErrors = errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(
                batch.getId(), SyncStage.MAP);
        assertThat(mapErrors).hasSize(2);
        assertThat(mapErrors).allSatisfy(e ->
                assertThat(e.getErrorCode()).isEqualTo("UNMAPPED_REVENUE_CATEGORY"));
        assertThat(mapErrors.get(0).getErrorMessage()).contains("5 record(s)", "[ZZ]");
        assertThat(mapErrors.get(1).getErrorMessage()).contains("1 record(s)", "[YY]");
    }

    @Test
    @DisplayName("Re-running replaces this stage's errors instead of piling them up")
    void should_notAccumulateErrors_when_rerun() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        stage(batch, "rec-1", "{\"BUCKET\":\"ZZ\"}");

        stage.mapBatch(batch.getId());
        stage.mapBatch(batch.getId());
        stage.mapBatch(batch.getId());

        assertThat(errors.countBySyncBatchIdAndErrorStage(batch.getId(), SyncStage.MAP)).isEqualTo(1);
    }

    /**
     * The interaction that would corrupt a financial counter: {@code rows_rejected} is derived
     * from validation's errors, and mapping records against the same batch at a different grain.
     */
    @Test
    @DisplayName("Mapping errors do not disturb validation's rejected-row count")
    void should_leaveValidationErrorsAlone_when_mappingRecordsItsOwn() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        stage(batch, "rec-1", "{\"BUCKET\":\"ZZ\"}");
        errors.saveAndFlush(FinSyncError.builder()
                .syncBatchId(batch.getId())
                .sourceRecordRef("rejected-earlier")
                .errorStage(SyncStage.VALIDATE)
                .errorCode("BLANK_RECORD_REF")
                .errorMessage("recorded by FIN-053")
                .build());

        stage.mapBatch(batch.getId());

        assertThat(errors.countBySyncBatchIdAndErrorStage(batch.getId(), SyncStage.VALIDATE))
                .as("mapping must not clear or inflate what validation recorded")
                .isEqualTo(1);
        assertThat(errors.countBySyncBatchIdAndErrorStage(batch.getId(), SyncStage.MAP)).isEqualTo(1);
    }

    @Test
    @DisplayName("A rule that can never fire is reported rather than silently ignored")
    void should_reportUnusableRule_when_ruleHasNoNamespace() {
        seedRule(SOURCE_A, "DS", "SEVA", 100);
        seedRule(SOURCE_A, "BUCKET:SS", "SPECIAL_SEVA", 100);
        stage(batch, "rec-1", "{\"BUCKET\":\"SS\"}");

        stage.mapBatch(batch.getId());

        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.MAP))
                .anySatisfy(e -> assertThat(e.getErrorCode()).isEqualTo("UNUSABLE_MAPPING_RULE"));
    }

    // ---------------------------------------------------------------- termination and concurrency

    /**
     * The FIN-053 defect, guarded against here before it can recur: the loop advances an id
     * cursor, so it ends whatever happens to any individual row.
     */
    @Test
    @DisplayName("A batch whose rows cannot be written still ends instead of spinning")
    void should_terminate_when_noRowCanBeRecorded() {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        stage(batch, "rec-1", "{\"BUCKET\":\"DS\"}");
        stage(batch, "rec-2", "{\"BUCKET\":\"DS\"}");

        RevenueMappingStage contended = new RevenueMappingStage(staging, mappings, rules, categories,
                errors, batches, alwaysFailingTransaction(), json);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                assertThatThrownBy(() -> contended.mapBatch(batch.getId()))
                        .as("a write failure must stop the run, not be skipped past")
                        .isInstanceOf(RuntimeException.class));
    }

    @Test
    @DisplayName("Two mapping runs on one batch leave one decision per record")
    void should_recordEachRecordOnce_when_twoStagesRunTogether() throws Exception {
        seedRule(SOURCE_A, "BUCKET:DS", "SEVA", 100);
        seedRule(SOURCE_A, "BUCKET:ZZ", "DONATION", 100);
        for (int i = 0; i < 40; i++) {
            stage(batch, "conc-" + i, i % 2 == 0 ? "{\"BUCKET\":\"DS\"}" : "{\"BUCKET\":\"QQ\"}");
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<RevenueMappingStage.Result> run = () -> {
            start.await();
            return newStage().mapBatch(batch.getId());
        };

        try {
            Future<RevenueMappingStage.Result> first = pool.submit(run);
            Future<RevenueMappingStage.Result> second = pool.submit(run);
            start.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);

            assertThat(mappings.findBySyncBatchIdAndMappingTypeOrderByIdAsc(
                    batch.getId(), MappingType.REVENUE_CATEGORY))
                    .as("one decision per staged record, however many workers ran")
                    .hasSize(40);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- fixtures

    private RevenueMappingStage newStage() {
        TransactionTemplate perRow = new TransactionTemplate(transactionManager);
        perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueMappingStage(staging, mappings, rules, categories, errors, batches,
                perRow, json);
    }

    /** A transaction template whose every row write fails, to prove the run stops. */
    private TransactionTemplate alwaysFailingTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager) {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                throw new IllegalStateException("simulated persistence failure");
            }
        };
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private FinMappingRule seedRule(long sourceSystemId, String sourceValue,
                                    String canonicalValue, int priority) {
        return rules.saveAndFlush(FinMappingRule.builder()
                .sourceSystemId(sourceSystemId)
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue(sourceValue)
                .canonicalValue(canonicalValue)
                .priority(priority)
                .active(true)
                .build());
    }

    private FinSyncBatch newBatch(long templeId, long sourceSystemId) {
        return batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(SyncStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build());
    }

    private Long stage(FinSyncBatch target, String recordRef, String rawJson) {
        return stage(target, recordRef, rawJson, StagingStatus.VALID);
    }

    private Long stage(FinSyncBatch target, String recordRef, String rawJson, StagingStatus status) {
        return stage(target, recordRef, rawJson, status,
                target.getTempleId(), target.getSourceSystemId());
    }

    private Long stage(FinSyncBatch target, String recordRef, String rawJson, StagingStatus status,
                       long templeId, long sourceSystemId) {
        return staging.save(FinStgRevenue.builder()
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .syncBatchId(target.getId())
                .sourceRecordRef(recordRef)
                .rawJson(rawJson)
                .validationStatus(status)
                .extractedAt(LocalDateTime.now())
                .build()).getId();
    }

    private FinStgRevenueMapping decisionFor(Long stgRevenueId) {
        return mappings.findByStgRevenueIdAndMappingType(stgRevenueId, MappingType.REVENUE_CATEGORY)
                .orElseThrow(() -> new AssertionError("no mapping decision for staged row " + stgRevenueId));
    }
}

package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinStgRevenueMapping;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * FIN-055 against a real MySQL 8.0 container with the real migrations.
 *
 * <p>What is worth testing at this level is the collapse onto the daily grain, which needs a
 * whole batch, and the error records, which need a transaction. The per-record decisions are
 * tested without a database in {@link RevenueNormalizerTest}.
 *
 * <p>Test methods are non-transactional on purpose: the stage commits its error records
 * separately, and a test-managed rollback would hide the behaviour being verified.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class RevenueNormalizationStageTest {

    private static final long TEMPLE_A = 920001L;
    private static final long TEMPLE_B = 920002L;
    private static final long SOURCE_A = 8201L;
    private static final long SOURCE_B = 8202L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_normalize")
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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
    }

    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository mappings;
    @Autowired private FinSourceOfTruthDeclRepository declarations;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private FinSyncErrorRepository errors;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private RevenueNormalizationStage stage;
    private FinSyncBatch batch;

    @BeforeEach
    void setUp() {
        mappings.deleteAllInBatch();
        staging.deleteAllInBatch();
        errors.deleteAllInBatch();
        batches.deleteAllInBatch();
        declarations.deleteAllInBatch();

        stage = newStage();
        batch = newBatch(TEMPLE_A, SOURCE_A);
        declare(SOURCE_A, RevenueField.TRANSACTION_DATE, "ReceiptDate", 1);
        declare(SOURCE_A, RevenueField.GROSS_AMOUNT, "Amount", 3);
    }

    // ---------------------------------------------------------------- the collapse

    @Test
    @DisplayName("Records sharing a day and a category become one fact whose amounts add up")
    void should_collapseToOneFact_when_recordsShareTheGrain() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.50")), "SEVA");
        mapped(stage(batch, "rec-2", payload("2025-06-15", "200.25")), "SEVA");
        mapped(stage(batch, "rec-3", payload("2025-06-15", "0.25")), "SEVA");

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.recordsNormalized()).isEqualTo(3);
        assertThat(result.facts()).hasSize(1);
        RevenueNormalizationStage.NormalizedFact fact = result.facts().get(0);
        assertThat(fact.grossAmount()).isEqualByComparingTo("301.00");
        assertThat(fact.transactionDate()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(fact.financialYear()).isEqualTo("2025-26");
    }

    @Test
    @DisplayName("Amounts are summed exactly, with no floating-point drift")
    void should_sumExactly_when_amountsWouldDriftAsDoubles() {
        // 0.10 + 0.20 is 0.30000000000000004 in binary floating point. Over a temple's daily
        // volumes that drift becomes a reconciliation failure nobody can account for.
        mapped(stage(batch, "rec-1", payload("2025-06-15", "0.10")), "SEVA");
        mapped(stage(batch, "rec-2", payload("2025-06-15", "0.20")), "SEVA");

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.facts().get(0).grossAmount()).isEqualTo(new java.math.BigDecimal("0.30"));
    }

    @Test
    @DisplayName("A different day or category is a different fact")
    void should_keepFactsApart_when_grainDiffers() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "rec-2", payload("2025-06-16", "100.00")), "SEVA");
        mapped(stage(batch, "rec-3", payload("2025-06-15", "100.00")), "DONATION");

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.facts()).hasSize(3);
        assertThat(result.facts()).allSatisfy(fact ->
                assertThat(fact.grossAmount()).isEqualByComparingTo("100.00"));
    }

    @Test
    @DisplayName("A fact keeps a single record's reference, and drops it once several contribute")
    void should_carryRecordRef_when_oneRecordProducedTheFact() {
        mapped(stage(batch, "receipt-single", payload("2025-06-15", "10.00")), "SEVA");
        mapped(stage(batch, "receipt-a", payload("2025-06-16", "10.00")), "SEVA");
        mapped(stage(batch, "receipt-b", payload("2025-06-16", "10.00")), "SEVA");

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        RevenueNormalizationStage.NormalizedFact single = factOn(result, "2025-06-15");
        RevenueNormalizationStage.NormalizedFact several = factOn(result, "2025-06-16");
        assertThat(single.sourceRecordRef()).isEqualTo("receipt-single");
        // Naming one member of a group would point an investigator at an arbitrary record and
        // hide the rest; the contributing ids are the honest trace.
        assertThat(several.sourceRecordRef()).isNull();
        assertThat(several.stagedRowIds()).hasSize(2);
    }

    @Test
    @DisplayName("The declaration version in force is carried onto the fact")
    void should_stampDeclarationVersion_when_normalizing() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "10.00")), "SEVA");

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.facts().get(0).sourceOfTruthVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("Payment mode is UNRECORDED, and a fact carries its temple and batch")
    void should_carryProvenance_when_normalizing() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "10.00")), "SEVA");

        RevenueNormalizationStage.NormalizedFact fact =
                stage.normalizeBatch(batch.getId()).facts().get(0);

        assertThat(fact.templeId()).isEqualTo(TEMPLE_A);
        assertThat(fact.sourceSystemId()).isEqualTo(SOURCE_A);
        assertThat(fact.syncBatchId()).isEqualTo(batch.getId());
        assertThat(fact.paymentMode()).isEqualTo(PaymentMode.UNRECORDED);
        assertThat(fact.serviceId()).isNull();
    }

    // ---------------------------------------------------------------- absence stays absence

    @Test
    @DisplayName("An undeclared measure is null on the fact, never zero")
    void should_leaveUndeclaredMeasureNull_when_sourceDoesNotRecordIt() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "10.00")), "SEVA");

        RevenueNormalizationStage.NormalizedFact fact =
                stage.normalizeBatch(batch.getId()).facts().get(0);

        // NULL here is what makes the generated net_amount NULL too: with cancellations
        // unrecorded, net revenue is genuinely unknown and gross must not stand in for it.
        assertThat(fact.cancelledAmount()).isNull();
        assertThat(fact.cancelledCount()).isNull();
        assertThat(fact.transactionCount()).isNull();
        assertThat(fact.quantity()).isNull();
    }

    @Test
    @DisplayName("A declared measure is summed across the group")
    void should_sumDeclaredMeasures_when_collapsing() {
        declare(SOURCE_A, RevenueField.CANCELLED_AMOUNT, "CancelledAmount", 1);
        declare(SOURCE_A, RevenueField.TRANSACTION_COUNT, "Receipts", 1);
        mapped(stage(batch, "rec-1",
                "{\"ReceiptDate\":\"2025-06-15\",\"Amount\":\"100.00\","
                        + "\"CancelledAmount\":\"5.00\",\"Receipts\":\"3\"}"), "SEVA");
        mapped(stage(batch, "rec-2",
                "{\"ReceiptDate\":\"2025-06-15\",\"Amount\":\"50.00\","
                        + "\"CancelledAmount\":\"0\",\"Receipts\":\"1\"}"), "SEVA");

        RevenueNormalizationStage.NormalizedFact fact =
                stage.normalizeBatch(batch.getId()).facts().get(0);

        assertThat(fact.grossAmount()).isEqualByComparingTo("150.00");
        assertThat(fact.cancelledAmount()).isEqualByComparingTo("5.00");
        assertThat(fact.transactionCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("One unknown contributor makes the group's total unknown, not a partial sum")
    void should_reportNull_when_oneContributorLacksAMeasure() {
        declare(SOURCE_A, RevenueField.CANCELLED_AMOUNT, "CancelledAmount", 1);
        mapped(stage(batch, "rec-1",
                "{\"ReceiptDate\":\"2025-06-15\",\"Amount\":\"100.00\",\"CancelledAmount\":\"5.00\"}"),
                "SEVA");
        mapped(stage(batch, "rec-2",
                "{\"ReceiptDate\":\"2025-06-15\",\"Amount\":\"50.00\"}"), "SEVA");

        RevenueNormalizationStage.NormalizedFact fact =
                stage.normalizeBatch(batch.getId()).facts().get(0);

        // A partial sum looks like a complete figure. Unknown plus known is unknown.
        assertThat(fact.cancelledAmount()).isNull();
        assertThat(fact.grossAmount()).isEqualByComparingTo("150.00");
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("A record with no mapping decision is rejected, not normalized without a category")
    void should_reject_when_recordWasNeverMapped() {
        stage(batch, "rec-1", payload("2025-06-15", "10.00"));

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.recordsNormalized()).isZero();
        assertThat(result.facts()).isEmpty();
        assertThat(result.byErrorCode()).containsEntry("NOT_MAPPED", 1);
    }

    @Test
    @DisplayName("An undecided mapping outcome is rejected with its own code")
    void should_reject_when_mappingWasUndecided() {
        Long id = stage(batch, "rec-1", payload("2025-06-15", "10.00"));
        decision(id, MappingOutcome.AMBIGUOUS, null);

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.byErrorCode()).containsEntry("UNDECIDED_MAPPING", 1);
        assertThat(result.facts()).isEmpty();
    }

    @Test
    @DisplayName("A rejection is recorded with its reason and the payload that caused it")
    void should_recordError_when_recordCannotBeNormalized() {
        mapped(stage(batch, "bad-date", payload("15/06/2025", "10.00")), "SEVA");

        stage.normalizeBatch(batch.getId());

        List<FinSyncError> recorded =
                errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.NORMALIZE);
        assertThat(recorded).singleElement().satisfies(error -> {
            assertThat(error.getErrorCode()).isEqualTo("UNPARSEABLE_TRANSACTION_DATE");
            assertThat(error.getSourceRecordRef()).isEqualTo("bad-date");
            // The payload travels with the error so the batch can be replayed without going
            // back to a staging table whose retention nobody has agreed (Q7).
            assertThat(error.getRawPayloadJson()).contains("15/06/2025");
        });
    }

    @Test
    @DisplayName("A rejected record contributes nothing to any fact")
    void should_excludeRejected_when_buildingFacts() {
        mapped(stage(batch, "good", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "bad", payload("2025-06-15", "not-a-number")), "SEVA");

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.recordsRejected()).isEqualTo(1);
        // Never read as zero, and never quietly folded in at some other value.
        assertThat(result.facts().get(0).grossAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Without the amount declaration the batch is refused, and the reason recorded")
    void should_refuseBatch_when_aRequiredDeclarationIsMissing() {
        FinSyncBatch undeclared = newBatch(TEMPLE_B, SOURCE_B);
        mapped(stage(undeclared, "rec-1", payload("2025-06-15", "10.00")), "SEVA");

        assertThatThrownBy(() -> stage.normalizeBatch(undeclared.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be normalized");

        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(
                undeclared.getId(), SyncStage.NORMALIZE))
                .isNotEmpty()
                .allSatisfy(error ->
                        assertThat(error.getErrorCode()).isEqualTo("UNUSABLE_DECLARATION"));
    }

    @Test
    @DisplayName("An unknown batch is refused rather than silently doing nothing")
    void should_refuse_when_batchDoesNotExist() {
        assertThatThrownBy(() -> stage.normalizeBatch(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- isolation and scope

    @Test
    @DisplayName("Only VALID rows are normalized")
    void should_normalizeOnlyValidatedRows_when_batchIsMixed() {
        mapped(stage(batch, "valid", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "received", payload("2025-06-15", "999.00"), StagingStatus.RECEIVED), "SEVA");
        mapped(stage(batch, "rejected", payload("2025-06-15", "999.00"), StagingStatus.REJECTED), "SEVA");

        RevenueNormalizationStage.Result result = stage.normalizeBatch(batch.getId());

        assertThat(result.recordsNormalized()).isEqualTo(1);
        assertThat(result.facts().get(0).grossAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Another source's declaration is never used")
    void should_isolateSources_when_anotherSourceHasTheDeclaration() {
        declare(SOURCE_B, RevenueField.CANCELLED_AMOUNT, "CancelledAmount", 1);
        mapped(stage(batch, "rec-1",
                "{\"ReceiptDate\":\"2025-06-15\",\"Amount\":\"100.00\",\"CancelledAmount\":\"5.00\"}"),
                "SEVA");

        RevenueNormalizationStage.NormalizedFact fact =
                stage.normalizeBatch(batch.getId()).facts().get(0);

        // The field is in the payload, but this source has not declared it; reading it anyway
        // would let one temple's configuration decide another temple's published figures.
        assertThat(fact.cancelledAmount()).isNull();
    }

    @Test
    @DisplayName("Normalization does not touch the staged record")
    void should_leaveStagingUntouched_when_normalizing() throws Exception {
        String raw = payload("2025-06-15", "100.00");
        Long id = mapped(stage(batch, "rec-1", raw), "SEVA");
        LocalDateTime updatedBefore = staging.findById(id).orElseThrow().getUpdatedAt();

        stage.normalizeBatch(batch.getId());

        FinStgRevenue after = staging.findById(id).orElseThrow();
        // Not LOADED either: a record has not been loaded until FIN-056 writes the fact, and
        // marking it earlier would strand rows as loaded against a fact nobody wrote.
        assertThat(after.getValidationStatus()).isEqualTo(StagingStatus.VALID);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(json.readTree(after.getRawJson())).isEqualTo(json.readTree(raw));
    }

    @Test
    @DisplayName("Earlier stages' errors for the same batch survive")
    void should_leaveOtherStagesErrors_when_recordingItsOwn() {
        errors.save(FinSyncError.builder()
                .syncBatchId(batch.getId())
                .sourceRecordRef("rec-x")
                .errorStage(SyncStage.VALIDATE)
                .errorCode("MISSING_FIELD")
                .errorMessage("from validation")
                .build());
        mapped(stage(batch, "bad", payload("2025-06-15", "nope")), "SEVA");

        stage.normalizeBatch(batch.getId());

        // Three stages share fin_sync_error and count at different grains, so every delete has
        // to be scoped by stage (FIN-D-032).
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.VALIDATE))
                .hasSize(1);
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.NORMALIZE))
                .hasSize(1);
    }

    // ---------------------------------------------------------------- re-running

    @Test
    @DisplayName("Re-running produces the same facts and does not pile up errors")
    void should_beIdempotent_when_runTwice() {
        mapped(stage(batch, "good", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "bad", payload("2025-06-15", "nope")), "SEVA");

        RevenueNormalizationStage.Result first = stage.normalizeBatch(batch.getId());
        RevenueNormalizationStage.Result second = stage.normalizeBatch(batch.getId());

        assertThat(second.facts()).isEqualTo(first.facts());
        assertThat(second.recordsRejected()).isEqualTo(first.recordsRejected());
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.NORMALIZE))
                .hasSize(1);
    }

    @Test
    @DisplayName("A batch larger than one chunk terminates and normalizes every row")
    void should_terminate_when_batchExceedsOneChunk() {
        int rows = 1_200;
        for (int i = 0; i < rows; i++) {
            mapped(stage(batch, "rec-" + i, payload("2025-06-15", "1.00")), "SEVA");
        }

        RevenueNormalizationStage.Result result = assertTimeoutPreemptively(Duration.ofMinutes(4),
                () -> stage.normalizeBatch(batch.getId()));

        // The cursor has to advance past every chunk; a loop that re-read page zero would spin
        // here rather than fail, which is why this is a timeout and not an assertion.
        assertThat(result.recordsNormalized()).isEqualTo(rows);
        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).grossAmount()).isEqualByComparingTo("1200.00");
    }

    // ---------------------------------------------------------------- helpers

    private RevenueNormalizationStage newStage() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueNormalizationStage(batches, staging, mappings, declarations,
                categories, errors, template, json);
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

    private void declare(long sourceSystemId, RevenueField field, String sourceField, int version) {
        declarations.save(FinSourceOfTruthDecl.builder()
                .sourceSystemId(sourceSystemId)
                .metric(field.metric())
                .version(version)
                .sourceObject("TestObject")
                .sourceField(sourceField)
                .build());
    }

    private String payload(String date, String amount) {
        return "{\"ReceiptDate\":\"" + date + "\",\"Amount\":\"" + amount + "\"}";
    }

    private Long stage(FinSyncBatch target, String recordRef, String rawJson) {
        return stage(target, recordRef, rawJson, StagingStatus.VALID);
    }

    private Long stage(FinSyncBatch target, String recordRef, String rawJson, StagingStatus status) {
        return staging.save(FinStgRevenue.builder()
                .templeId(target.getTempleId())
                .sourceSystemId(target.getSourceSystemId())
                .syncBatchId(target.getId())
                .sourceRecordRef(recordRef)
                .rawJson(rawJson)
                .validationStatus(status)
                .extractedAt(LocalDateTime.now())
                .build()).getId();
    }

    private Long mapped(Long stgRevenueId, String canonicalValue) {
        return decision(stgRevenueId, MappingOutcome.MAPPED, canonicalValue);
    }

    private Long decision(Long stgRevenueId, MappingOutcome outcome, String canonicalValue) {
        FinStgRevenue row = staging.findById(stgRevenueId).orElseThrow();
        mappings.save(FinStgRevenueMapping.builder()
                .stgRevenueId(stgRevenueId)
                .templeId(row.getTempleId())
                .sourceSystemId(row.getSourceSystemId())
                .syncBatchId(row.getSyncBatchId())
                .sourceRecordRef(row.getSourceRecordRef())
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceField("BUCKET")
                .sourceValue("DS")
                .outcome(outcome)
                .canonicalValue(canonicalValue)
                .mappedAt(LocalDateTime.now())
                .build());
        return stgRevenueId;
    }

    private RevenueNormalizationStage.NormalizedFact factOn(
            RevenueNormalizationStage.Result result, String date) {
        return result.facts().stream()
                .filter(fact -> fact.transactionDate().equals(LocalDate.parse(date)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fact on " + date));
    }
}

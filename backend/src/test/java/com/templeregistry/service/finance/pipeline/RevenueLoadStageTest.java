package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinStgRevenueMapping;
import com.templeregistry.entity.finance.FinSyncBatch;
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
import com.templeregistry.repository.finance.FinRevenueFactRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * FIN-056 against a real MySQL 8.0 container with the real migrations.
 *
 * <p>Almost every test here is about not counting something twice. The load is the first point
 * at which this platform stores a figure it will stand behind, and the two ways to get it wrong
 * both produce a number that looks entirely reasonable: a retry that adds instead of replacing,
 * and a NULL grain column that makes one fact look like two.
 *
 * <p>The database is doing real work in these tests — the unique constraint, the generated grain
 * columns and the generated {@code net_amount} — so none of it can be verified without one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class RevenueLoadStageTest {

    private static final long TEMPLE_A = 930001L;
    private static final long TEMPLE_B = 930002L;
    private static final long SOURCE_A = 8301L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_load")
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

    @Autowired private FinRevenueFactRepository facts;
    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository mappings;
    @Autowired private FinSourceOfTruthDeclRepository declarations;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private FinSyncErrorRepository errors;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private RevenueLoadStage stage;
    private FinSyncBatch batch;
    private Long sevaCategoryId;

    @BeforeEach
    void setUp() {
        facts.deleteAllInBatch();
        mappings.deleteAllInBatch();
        staging.deleteAllInBatch();
        errors.deleteAllInBatch();
        batches.deleteAllInBatch();
        declarations.deleteAllInBatch();

        stage = newStage();
        batch = newBatch(TEMPLE_A);
        declare(RevenueField.TRANSACTION_DATE, "ReceiptDate", 1);
        declare(RevenueField.GROSS_AMOUNT, "Amount", 3);
        sevaCategoryId = categories.findByCategoryCodeAndDeletedFalse("SEVA").orElseThrow().getId();
    }

    // ---------------------------------------------------------------- the ordinary case

    @Test
    @DisplayName("A batch's facts are written at the canonical grain")
    void should_writeFacts_when_batchIsLoaded() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.50")), "SEVA");
        mapped(stage(batch, "rec-2", payload("2025-06-15", "200.50")), "SEVA");
        mapped(stage(batch, "rec-3", payload("2025-06-16", "50.00")), "SEVA");

        RevenueLoadStage.Result result = stage.loadBatch(batch.getId());

        assertThat(result.factsWritten()).isEqualTo(2);
        assertThat(result.rowsLoaded()).isEqualTo(3);
        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(2);
        assertThat(factOn("2025-06-15").getGrossAmount()).isEqualByComparingTo("301.00");
        assertThat(factOn("2025-06-15").getFinancialYear()).isEqualTo("2025-26");
    }

    @Test
    @DisplayName("Contributing staged rows become LOADED, and rows_loaded is derived from them")
    void should_markStagingLoaded_when_factIsWritten() {
        Long id = mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");

        stage.loadBatch(batch.getId());

        assertThat(staging.findById(id).orElseThrow().getValidationStatus())
                .isEqualTo(StagingStatus.LOADED);
        assertThat(batches.findById(batch.getId()).orElseThrow().getRowsLoaded()).isEqualTo(1L);
    }

    @Test
    @DisplayName("net_amount is computed by the database, and stays NULL where cancellations are unknown")
    void should_leaveNetNull_when_cancellationsAreNotRecorded() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");

        stage.loadBatch(batch.getId());

        FinRevenueFact fact = factOn("2025-06-15");
        assertThat(fact.getCancelledAmount()).isNull();
        // Reporting gross as net would assert that nothing was cancelled. NULL says the platform
        // does not know, which is the truth for a source that records no cancellations.
        assertThat(fact.getNetAmount()).isNull();
    }

    @Test
    @DisplayName("net_amount is gross minus cancelled where the source records both")
    void should_computeNet_when_cancellationsAreRecorded() {
        declare(RevenueField.CANCELLED_AMOUNT, "CancelledAmount", 1);
        mapped(stage(batch, "rec-1",
                "{\"ReceiptDate\":\"2025-06-15\",\"Amount\":\"100.00\",\"CancelledAmount\":\"25.00\"}"),
                "SEVA");

        stage.loadBatch(batch.getId());

        assertThat(factOn("2025-06-15").getNetAmount()).isEqualByComparingTo("75.00");
    }

    // ---------------------------------------------------------------- not counting twice

    @Test
    @DisplayName("Loading the same batch twice leaves the same totals")
    void should_beIdempotent_when_theSameBatchIsLoadedAgain() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "rec-2", payload("2025-06-15", "200.00")), "SEVA");

        stage.loadBatch(batch.getId());
        RevenueLoadStage.Result second = stage.loadBatch(batch.getId());

        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(1);
        assertThat(factOn("2025-06-15").getGrossAmount()).isEqualByComparingTo("300.00");
        // Nothing moved the second time: the staging transition is conditional on VALID, so
        // rows already LOADED are not claimed again and rows_loaded cannot drift upward.
        assertThat(second.rowsLoaded()).isZero();
        assertThat(batches.findById(batch.getId()).orElseThrow().getRowsLoaded()).isEqualTo(2L);
    }

    @Test
    @DisplayName("A later batch restates a day: it replaces the figure, it does not add to it")
    void should_replaceNotAccumulate_when_aLaterBatchCoversTheSameDay() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        stage.loadBatch(batch.getId());

        FinSyncBatch restatement = newBatch(TEMPLE_A);
        mapped(stage(restatement, "rec-1", payload("2025-06-15", "175.00")), "SEVA");
        stage.loadBatch(restatement.getId());

        // The single easiest way to double a temple's revenue is an upsert that adds. The
        // constraint would not catch it -- the row is unique either way -- so only this does.
        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(1);
        assertThat(factOn("2025-06-15").getGrossAmount())
                .as("the source was re-read; 175.00 is what it says now")
                .isEqualByComparingTo("175.00");
    }

    @Test
    @DisplayName("A restatement keeps the original created_at and records the newer batch")
    void should_preserveCreatedAt_when_restating() throws Exception {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        stage.loadBatch(batch.getId());
        FinRevenueFact first = factOn("2025-06-15");
        LocalDateTime createdAt = first.getCreatedAt();
        Long firstId = first.getId();
        Thread.sleep(1_100);

        FinSyncBatch restatement = newBatch(TEMPLE_A);
        mapped(stage(restatement, "rec-1", payload("2025-06-15", "175.00")), "SEVA");
        stage.loadBatch(restatement.getId());

        FinRevenueFact after = factOn("2025-06-15");
        // A delete-then-insert would have passed every other test here and failed this one:
        // it makes a restatement of an old day indistinguishable from a first load.
        assertThat(after.getId()).isEqualTo(firstId);
        assertThat(after.getCreatedAt()).isEqualTo(createdAt);
        assertThat(after.getUpdatedAt()).isAfter(createdAt);
        assertThat(after.getSyncBatchId())
                .as("which batch last wrote the figure the platform reports")
                .isEqualTo(restatement.getId());
    }

    @Test
    @DisplayName("A fact with no counter or operator is one row, not one per load")
    void should_notDuplicate_when_grainColumnsAreNull() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        stage.loadBatch(batch.getId());

        FinSyncBatch again = newBatch(TEMPLE_A);
        mapped(stage(again, "rec-2", payload("2025-06-15", "100.00")), "SEVA");
        stage.loadBatch(again.getId());

        // NULL is distinct from NULL in a unique index, so without the generated grain_* columns
        // (FIN-D-018) both loads would insert and the day would be reported twice. counter_ref
        // and operator_ref are NULL here because nothing declares them -- the ordinary case.
        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(1);
        assertThat(factOn("2025-06-15").getCounterRef()).isNull();
        assertThat(factOn("2025-06-15").getOperatorRef()).isNull();
    }

    @Test
    @DisplayName("A source deleting records leaves the earlier fact standing, and says so")
    void should_leaveStaleFact_when_aLaterBatchNoLongerCoversIt() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        stage.loadBatch(batch.getId());

        FinSyncBatch later = newBatch(TEMPLE_A);
        mapped(stage(later, "rec-2", payload("2025-06-16", "50.00")), "SEVA");
        stage.loadBatch(later.getId());

        // Documented behaviour, not an oversight: an incremental batch's window is a
        // modification window, not a business-date range, so "this day should now be empty"
        // cannot be inferred from it. Detecting a deletion needs a full reload of a range or
        // reconciliation against source totals (FIN-060). The test exists so the limitation is
        // visible rather than discovered.
        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(2);
        assertThat(factOn("2025-06-15").getGrossAmount()).isEqualByComparingTo("100.00");
    }

    // ---------------------------------------------------------------- the grain

    @Test
    @DisplayName("Different days, categories and temples are different facts")
    void should_keepFactsApart_when_grainDiffers() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "rec-2", payload("2025-06-16", "100.00")), "SEVA");
        mapped(stage(batch, "rec-3", payload("2025-06-15", "100.00")), "DONATION");
        FinSyncBatch otherTemple = newBatch(TEMPLE_B);
        declare(otherTemple.getSourceSystemId(), RevenueField.TRANSACTION_DATE, "ReceiptDate", 1);
        declare(otherTemple.getSourceSystemId(), RevenueField.GROSS_AMOUNT, "Amount", 1);
        mapped(stage(otherTemple, "rec-4", payload("2025-06-15", "100.00")), "SEVA");

        stage.loadBatch(batch.getId());
        stage.loadBatch(otherTemple.getId());

        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(3);
        assertThat(facts.countByTempleId(TEMPLE_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("A financial year total sums the gross, and one temple never sees another's")
    void should_totalPerTempleAndYear_when_summing() {
        mapped(stage(batch, "rec-1", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "rec-2", payload("2026-03-31", "50.00")), "SEVA");
        mapped(stage(batch, "rec-3", payload("2026-04-01", "999.00")), "SEVA");

        stage.loadBatch(batch.getId());

        assertThat(facts.sumGrossForFinancialYear(TEMPLE_A, "2025-26"))
                .get().isEqualTo(new BigDecimal("150.00"));
        assertThat(facts.sumGrossForFinancialYear(TEMPLE_A, "2026-27"))
                .get().isEqualTo(new BigDecimal("999.00"));
        assertThat(facts.sumGrossForFinancialYear(TEMPLE_B, "2025-26")).isEmpty();
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("A record normalization rejected contributes nothing and stays unloaded")
    void should_loadNothing_when_recordWasRejected() {
        Long good = mapped(stage(batch, "good", payload("2025-06-15", "100.00")), "SEVA");
        Long bad = mapped(stage(batch, "bad", payload("2025-06-15", "not-a-number")), "SEVA");

        RevenueLoadStage.Result result = stage.loadBatch(batch.getId());

        assertThat(result.recordsRejected()).isEqualTo(1);
        assertThat(factOn("2025-06-15").getGrossAmount()).isEqualByComparingTo("100.00");
        assertThat(staging.findById(good).orElseThrow().getValidationStatus())
                .isEqualTo(StagingStatus.LOADED);
        assertThat(staging.findById(bad).orElseThrow().getValidationStatus())
                .as("a record that produced no fact has not been loaded")
                .isEqualTo(StagingStatus.VALID);
    }

    @Test
    @DisplayName("An unknown batch is refused")
    void should_refuse_when_batchDoesNotExist() {
        assertThatThrownBy(() -> stage.loadBatch(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The failure path, exercised for real rather than assumed.
     *
     * <p>An amount with seventeen integer digits parses as an exact decimal and is refused by
     * {@code DECIMAL(18,2)} — a plausible corrupt source value that gets all the way to the
     * write before anything objects, which is exactly the shape this path exists for.
     */
    @Test
    @DisplayName("A batch that cannot write every fact fails, and says which fact failed")
    void should_failTheBatch_when_aFactCannotBeWritten() {
        mapped(stage(batch, "good", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "overflow", payload("2025-06-16", "99999999999999999.99")), "SEVA");

        assertThatThrownBy(() -> stage.loadBatch(batch.getId()))
                .isInstanceOf(RevenueLoadStage.LoadFailedException.class)
                .hasMessageContaining("loaded 1 of 2 facts");

        // A partially loaded batch reporting SUCCESS is worse than one reporting FAILED,
        // because the second is investigated and the first is believed (FIN-D-017).
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.LOAD))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo("FACT_WRITE_FAILED");
                    assertThat(error.getErrorMessage()).contains("2025-06-16");
                });
    }

    @Test
    @DisplayName("Facts written before a failure are kept, and a retry completes the batch")
    void should_keepWhatSucceeded_when_aLaterFactFails() {
        Long good = mapped(stage(batch, "good", payload("2025-06-15", "100.00")), "SEVA");
        Long bad = mapped(stage(batch, "overflow", payload("2025-06-16", "99999999999999999.99")), "SEVA");

        assertThatThrownBy(() -> stage.loadBatch(batch.getId()))
                .isInstanceOf(RevenueLoadStage.LoadFailedException.class);

        // Per-fact transactions: the good day is written and stays written. Discarding it would
        // make a retry redo work that had already succeeded, on the operation that handles the
        // most rows.
        assertThat(factOn("2025-06-15").getGrossAmount()).isEqualByComparingTo("100.00");
        assertThat(staging.findById(good).orElseThrow().getValidationStatus())
                .isEqualTo(StagingStatus.LOADED);
        assertThat(staging.findById(bad).orElseThrow().getValidationStatus())
                .isEqualTo(StagingStatus.VALID);
        assertThat(batches.findById(batch.getId()).orElseThrow().getRowsLoaded()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Normalization's errors survive the load recording its own")
    void should_leaveOtherStagesErrors_when_recordingItsOwn() {
        mapped(stage(batch, "good", payload("2025-06-15", "100.00")), "SEVA");
        mapped(stage(batch, "bad", payload("15/06/2025", "100.00")), "SEVA");

        stage.loadBatch(batch.getId());

        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.NORMALIZE))
                .hasSize(1);
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.LOAD))
                .isEmpty();
    }

    @Test
    @DisplayName("A batch with nothing to load succeeds and writes nothing")
    void should_succeedWritingNothing_when_batchHasNoValidRows() {
        stage(batch, "unvalidated", payload("2025-06-15", "100.00"), StagingStatus.RECEIVED);

        RevenueLoadStage.Result result = stage.loadBatch(batch.getId());

        assertThat(result.factsWritten()).isZero();
        assertThat(facts.countByTempleId(TEMPLE_A)).isZero();
        assertThat(batches.findById(batch.getId()).orElseThrow().getRowsLoaded()).isZero();
    }

    @Test
    @DisplayName("A batch larger than one chunk loads every row and terminates")
    void should_terminate_when_batchExceedsOneChunk() {
        int rows = 1_200;
        for (int i = 0; i < rows; i++) {
            mapped(stage(batch, "rec-" + i, payload("2025-06-15", "1.00")), "SEVA");
        }

        RevenueLoadStage.Result result = assertTimeoutPreemptively(Duration.ofMinutes(5),
                () -> stage.loadBatch(batch.getId()));

        assertThat(result.rowsLoaded()).isEqualTo(rows);
        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(1);
        assertThat(factOn("2025-06-15").getGrossAmount()).isEqualByComparingTo("1200.00");
    }

    // ---------------------------------------------------------------- helpers

    private RevenueLoadStage newStage() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        RevenueNormalizationStage normalization = new RevenueNormalizationStage(
                batches, staging, mappings, declarations, categories, errors, template, json);
        return new RevenueLoadStage(normalization, facts, staging, batches, errors, template);
    }

    private FinSyncBatch newBatch(long templeId) {
        return batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(templeId)
                .sourceSystemId(templeId == TEMPLE_A ? SOURCE_A : SOURCE_A + 1)
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(SyncStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build());
    }

    private void declare(RevenueField field, String sourceField, int version) {
        declare(SOURCE_A, field, sourceField, version);
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
                .outcome(MappingOutcome.MAPPED)
                .canonicalValue(canonicalValue)
                .mappedAt(LocalDateTime.now())
                .build());
        return stgRevenueId;
    }

    private FinRevenueFact factOn(String date) {
        List<FinRevenueFact> onDate =
                facts.findByTempleIdAndTransactionDateOrderByIdAsc(TEMPLE_A, LocalDate.parse(date));
        assertThat(onDate).as("facts on " + date).isNotEmpty();
        return onDate.stream()
                .filter(f -> f.getCategoryId().equals(sevaCategoryId))
                .findFirst()
                .orElse(onDate.get(0));
    }
}

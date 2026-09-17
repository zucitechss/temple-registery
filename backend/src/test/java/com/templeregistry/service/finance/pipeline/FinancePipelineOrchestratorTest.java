package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.connector.finance.ConnectorMetadata;
import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.DateRange;
import com.templeregistry.connector.finance.RawRow;
import com.templeregistry.connector.finance.SchemaFingerprint;
import com.templeregistry.connector.finance.SourceProbeResult;
import com.templeregistry.connector.finance.SourceSystemDescriptor;
import com.templeregistry.connector.finance.SourceTotals;
import com.templeregistry.connector.finance.SyncContext;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-057 against a real MySQL 8.0 container with the real migrations.
 *
 * <p><b>Synthetic, and labelled as such.</b> The connector is a fake that returns rows from
 * memory — it is the one external system that cannot be reached until Q4 is answered. Everything
 * downstream of it is the real production stage: the real extraction drain, the real validator,
 * the real mapper, the real normalizer, the real load, and the real database constraints. The
 * point of these tests is that the stages <em>compose</em>, which mocking them would prove
 * nothing about.
 *
 * <p>No temple's real data is involved and none is implied. The temple and source ids here are
 * test values in a range no seeded configuration uses.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class FinancePipelineOrchestratorTest {

    private static final long TEMPLE = 940001L;
    private static final String CONNECTOR_BEAN = "syntheticTestConnector";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_orchestrator")
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

    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository mappings;
    @Autowired private FinMappingRuleRepository rules;
    @Autowired private FinSourceOfTruthDeclRepository declarations;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private FinRevenueFactRepository facts;
    @Autowired private FinSyncErrorRepository errors;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private SyntheticConnector connector;
    private FinSourceSystem source;
    private FinancePipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        facts.deleteAllInBatch();
        mappings.deleteAllInBatch();
        staging.deleteAllInBatch();
        errors.deleteAllInBatch();
        batches.deleteAllInBatch();
        declarations.deleteAllInBatch();
        rules.deleteAllInBatch();
        sourceSystems.deleteAllInBatch();

        source = sourceSystems.save(FinSourceSystem.builder()
                .templeId(TEMPLE)
                .systemCode("SYNTHETIC")
                .systemName("Synthetic test source")
                .sourceTechnology(SourceTechnology.SQL_SERVER)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean(CONNECTOR_BEAN)
                .sourceTempleCode("TEST")
                .credentialRef("test.credential")
                .sourceTimezone("Asia/Kolkata")
                .build());

        declare("REVENUE_TRANSACTION_DATE", "ReceiptDate");
        declare("REVENUE_AMOUNT", "Amount");
        rules.save(FinMappingRule.builder()
                .sourceSystemId(source.getId())
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue("BUCKET:DS")
                .sourceLabel("Sevas")
                .canonicalValue("SEVA")
                .priority(100)
                .active(true)
                .build());

        connector = new SyntheticConnector();
        orchestrator = newOrchestrator();
    }

    // ---------------------------------------------------------------- the whole pipeline

    @Test
    @DisplayName("A batch runs every stage in order and ends SUCCESS")
    void should_runEveryStage_when_batchIsPending() {
        connector.delivers(
                row("r-1", "2025-06-15", "100.00"),
                row("r-2", "2025-06-15", "200.00"),
                row("r-3", "2025-06-16", "50.00"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        FinancePipelineOrchestrator.Result result = orchestrator.run(batch.getId());

        assertThat(result.extracted().rowsStaged()).isEqualTo(3);
        assertThat(result.validated().validated()).isEqualTo(3);
        assertThat(result.mapped().decided()).isEqualTo(3);
        assertThat(result.loaded().factsWritten()).isEqualTo(2);
        assertThat(result.duration()).isNotNull();

        // Every stage did real work against the real database, not a mock returning a number.
        assertThat(staging.countBySyncBatchId(batch.getId())).isEqualTo(3);
        assertThat(facts.countByTempleId(TEMPLE)).isEqualTo(2);
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, "2025-26"))
                .get().isEqualTo(new BigDecimal("350.00"));

        FinSyncBatch after = batches.findById(batch.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(after.getStartedAt()).isNotNull();
        assertThat(after.getFinishedAt()).isNotNull();
        assertThat(after.getDurationMs()).isNotNull();
    }

    @Test
    @DisplayName("Each stage's counter is written by that stage, and the orchestrator adds none")
    void should_leaveCountersToTheirOwners_when_running() {
        connector.delivers(
                row("r-1", "2025-06-15", "100.00"),
                row("r-2", "2025-06-15", "not-a-number"),
                row("r-3", "not-a-date", "10.00"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        orchestrator.run(batch.getId());

        FinSyncBatch after = batches.findById(batch.getId()).orElseThrow();
        // rows_extracted from extraction, rows_rejected from validation, rows_loaded from the
        // load -- each derived from the database by its owner, never incremented here (FIN-D-023).
        assertThat(after.getRowsExtracted()).isEqualTo(3L);
        assertThat(after.getRowsLoaded()).isEqualTo(1L);
        assertThat(after.getRowsRejected())
                .as("validation rejects nothing here: a bad amount is still structurally valid")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("The watermark is not advanced by a successful run")
    void should_notAdvanceWatermark_when_runSucceeds() {
        connector.delivers(row("r-1", "2025-06-15", "100.00"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        orchestrator.run(batch.getId());

        // Advancing it would mean a later incremental run skipping this window. Who sets it, and
        // from what, is undecided (FIN-D-049) -- so nothing sets it.
        assertThat(batches.findById(batch.getId()).orElseThrow().getWatermarkAfter()).isNull();
    }

    @Test
    @DisplayName("A record the source sent twice in one batch is refused and recorded")
    void should_refuseDuplicate_when_connectorRepeatsARecordRef() {
        connector.delivers(
                row("r-1", "2025-06-15", "100.00"),
                row("r-1", "2025-06-15", "100.00"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        FinancePipelineOrchestrator.Result result = orchestrator.run(batch.getId());

        assertThat(result.extracted().rowsStaged()).isEqualTo(1);
        assertThat(result.extracted().rowsRefused()).isEqualTo(1);
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.EXTRACT))
                .singleElement()
                .satisfies(e -> assertThat(e.getErrorCode()).isEqualTo("DUPLICATE_SOURCE_RECORD_REF"));
        // The duplicate did not stop the batch, and it did not get counted as revenue.
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, "2025-26"))
                .get().isEqualTo(new BigDecimal("100.00"));
    }

    // ---------------------------------------------------------------- claiming

    @Test
    @DisplayName("An unknown batch is refused")
    void should_refuse_when_batchDoesNotExist() {
        assertThatThrownBy(() -> orchestrator.run(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A batch that is not PENDING cannot be claimed, and says what it is")
    void should_refuse_when_batchIsNotPending() {
        for (SyncStatus status : List.of(SyncStatus.RUNNING, SyncStatus.SUCCESS,
                SyncStatus.FAILED, SyncStatus.CANCELLED)) {
            FinSyncBatch batch = newBatch(status);

            assertThatThrownBy(() -> orchestrator.run(batch.getId()))
                    .isInstanceOf(FinancePipelineOrchestrator.BatchNotClaimableException.class)
                    .hasMessageContaining(status.name());
        }
    }

    @Test
    @DisplayName("Two runners racing for one batch: exactly one claims it")
    void should_claimOnce_when_twoRunnersRaceForOneBatch() throws Exception {
        connector.delivers(row("r-1", "2025-06-15", "100.00"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<String> attempt = () -> {
            go.await(5, TimeUnit.SECONDS);
            try {
                orchestrator.run(batch.getId());
                return "claimed";
            } catch (FinancePipelineOrchestrator.BatchNotClaimableException refused) {
                return "refused";
            }
        };
        Future<String> first = pool.submit(attempt);
        Future<String> second = pool.submit(attempt);
        go.countDown();
        List<String> outcomes = List.of(first.get(60, TimeUnit.SECONDS),
                second.get(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        // A check-then-write would let both through and run every stage twice over the same rows.
        assertThat(outcomes).containsExactlyInAnyOrder("claimed", "refused");
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, "2025-26"))
                .get().isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Re-running a finished batch is refused rather than silently repeating it")
    void should_refuse_when_batchAlreadySucceeded() {
        connector.delivers(row("r-1", "2025-06-15", "100.00"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);
        orchestrator.run(batch.getId());

        assertThatThrownBy(() -> orchestrator.run(batch.getId()))
                .isInstanceOf(FinancePipelineOrchestrator.BatchNotClaimableException.class);
        assertThat(facts.countByTempleId(TEMPLE)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- failure

    @Test
    @DisplayName("A failing extract leaves the batch FAILED, names the stage, and rethrows")
    void should_failTheBatch_when_extractionThrows() {
        connector.failsWith(new IllegalStateException("source unreachable"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        assertThatThrownBy(() -> orchestrator.run(batch.getId()))
                .isInstanceOf(FinancePipelineOrchestrator.PipelineFailedException.class)
                .hasRootCauseMessage("source unreachable")
                .extracting(e -> ((FinancePipelineOrchestrator.PipelineFailedException) e).stage())
                .isEqualTo(SyncStage.EXTRACT);

        FinSyncBatch after = batches.findById(batch.getId()).orElseThrow();
        // Never left RUNNING: that is the state nothing can recover from automatically.
        assertThat(after.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(after.getFinishedAt()).isNotNull();
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.EXTRACT))
                .anySatisfy(e -> assertThat(e.getErrorCode()).isEqualTo("STAGE_FAILED"));
    }

    @Test
    @DisplayName("A failing extract stops the pipeline: nothing downstream runs")
    void should_runNoLaterStage_when_extractionFails() {
        connector.failsWith(new IllegalStateException("source unreachable"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        assertThatThrownBy(() -> orchestrator.run(batch.getId()))
                .isInstanceOf(FinancePipelineOrchestrator.PipelineFailedException.class);

        assertThat(staging.countBySyncBatchId(batch.getId())).isZero();
        assertThat(facts.countByTempleId(TEMPLE)).isZero();
        assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.LOAD))
                .isEmpty();
    }

    @Test
    @DisplayName("A failing load leaves the batch FAILED, not SUCCESS")
    void should_failTheBatch_when_loadThrows() {
        // Seventeen integer digits: parses as an exact decimal, refused by DECIMAL(18,2).
        connector.delivers(row("r-1", "2025-06-15", "99999999999999999.99"));
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        assertThatThrownBy(() -> orchestrator.run(batch.getId()))
                .isInstanceOf(FinancePipelineOrchestrator.PipelineFailedException.class)
                .extracting(e -> ((FinancePipelineOrchestrator.PipelineFailedException) e).stage())
                .isEqualTo(SyncStage.LOAD);

        assertThat(batches.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.FAILED);
        // Earlier stages did their work and it survives, which is what makes a retry cheap.
        assertThat(staging.countBySyncBatchId(batch.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("A source system the batch names but that does not exist fails the batch")
    void should_failTheBatch_when_sourceSystemIsMissing() {
        FinSyncBatch batch = batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(TEMPLE)
                .sourceSystemId(-99L)
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(SyncStatus.PENDING)
                .build());

        assertThatThrownBy(() -> orchestrator.run(batch.getId()))
                .isInstanceOf(FinancePipelineOrchestrator.PipelineFailedException.class);
        assertThat(batches.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.FAILED);
    }

    @Test
    @DisplayName("A source naming an unregistered connector fails loudly, not as an empty extract")
    void should_failTheBatch_when_connectorIsNotRegistered() {
        source.setConnectorBean("noSuchConnector");
        sourceSystems.save(source);
        FinSyncBatch batch = newBatch(SyncStatus.PENDING);

        assertThatThrownBy(() -> orchestrator.run(batch.getId()))
                .isInstanceOf(FinancePipelineOrchestrator.PipelineFailedException.class);

        // An empty extract would look like "the temple earned nothing", which is the single
        // most dangerous thing this platform could report.
        assertThat(batches.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.FAILED);
        assertThat(staging.countBySyncBatchId(batch.getId())).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private FinancePipelineOrchestrator newOrchestrator() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ConnectorRegistry registry = new ConnectorRegistry(Map.of(CONNECTOR_BEAN, connector));
        RevenueExtractionStage extraction = new RevenueExtractionStage(
                registry, sourceSystems, staging, batches, errors, template, json);
        RevenueStagingValidator validator = new RevenueStagingValidator(
                staging, errors, batches, template, json);
        RevenueMappingStage mapper = new RevenueMappingStage(
                staging, mappings, rules, categories, errors, batches, template, json);
        RevenueNormalizationStage normalization = new RevenueNormalizationStage(
                batches, staging, mappings, declarations, categories, errors, template, json);
        RevenueLoadStage loader = new RevenueLoadStage(
                normalization, facts, staging, batches, errors, template);
        return new FinancePipelineOrchestrator(
                extraction, validator, mapper, loader, batches, errors, template);
    }

    private FinSyncBatch newBatch(SyncStatus status) {
        return batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(TEMPLE)
                .sourceSystemId(source.getId())
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(status)
                .build());
    }

    private void declare(String metric, String sourceField) {
        declarations.save(FinSourceOfTruthDecl.builder()
                .sourceSystemId(source.getId())
                .metric(metric)
                .version(1)
                .sourceObject("SyntheticObject")
                .sourceField(sourceField)
                .build());
    }

    private RawRow row(String ref, String date, String amount) {
        return new RawRow(ref, Map.of("ReceiptDate", date, "Amount", amount, "BUCKET", "DS"));
    }

    /**
     * The one thing that is faked, because it is the one thing that cannot be reached: a temple's
     * source system. It returns rows from memory and never touches a network or a database.
     */
    private static final class SyntheticConnector implements TempleFinanceConnector {

        private final List<RawRow> rows = new ArrayList<>();
        private RuntimeException failure;

        private void delivers(RawRow... delivered) {
            rows.clear();
            rows.addAll(List.of(delivered));
            failure = null;
        }

        private void failsWith(RuntimeException cause) {
            rows.clear();
            failure = cause;
        }

        @Override
        public ConnectorMetadata metadata() {
            return new ConnectorMetadata(CONNECTOR_BEAN, ConnectorType.PULL_JDBC,
                    "synthetic in-memory source for pipeline tests");
        }

        @Override
        public Set<FinanceCapability> describeCapabilities(SourceSystemDescriptor source) {
            return Set.of(FinanceCapability.REVENUE);
        }

        @Override
        public SourceProbeResult probe(SourceSystemDescriptor source) {
            return SourceProbeResult.usable("synthetic");
        }

        @Override
        public Optional<SchemaFingerprint> fingerprintSchema(SourceSystemDescriptor source) {
            return Optional.empty();
        }

        @Override
        public Stream<RawRow> extract(FinanceCapability capability, SyncContext context) {
            requireCapability(capability, context.source());
            if (failure != null) {
                throw failure;
            }
            return rows.stream();
        }

        @Override
        public SourceTotals sourceTotals(FinanceCapability capability,
                                         SourceSystemDescriptor source,
                                         DateRange period) {
            requireCapability(capability, source);
            return SourceTotals.notAvailable();
        }
    }
}

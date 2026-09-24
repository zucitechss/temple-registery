package com.templeregistry.service.finance.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinTempleCapability;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.service.finance.aggregation.RevenueAggregationRebuilder;
import com.templeregistry.service.finance.aggregation.RevenueAggregationWriter;
import com.templeregistry.service.finance.onboarding.OnboardingConfigurationReader;
import com.templeregistry.service.finance.pipeline.FinancePipelineOrchestrator;
import com.templeregistry.service.finance.pipeline.RevenueExtractionStage;
import com.templeregistry.service.finance.pipeline.RevenueLoadStage;
import com.templeregistry.service.finance.pipeline.RevenueMappingStage;
import com.templeregistry.service.finance.pipeline.RevenueNormalizationStage;
import com.templeregistry.service.finance.pipeline.RevenueReconciliationStage;
import com.templeregistry.service.finance.pipeline.RevenueStagingValidator;
import com.templeregistry.service.finance.publication.ReconciliationGate;
import com.templeregistry.service.finance.sync.jdbc.DriverManagerJdbcConnectionFactory;
import com.templeregistry.service.finance.sync.jdbc.JdbcTableConnector;
import com.templeregistry.service.finance.sync.jdbc.PropertiesJdbcSourceSettingsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole platform executing, from a person asking for a run to a published figure (FIN-058).
 *
 * <h2>What is real here, and what is not</h2>
 *
 * <p>Real: the manual trigger, the batch lifecycle, {@code FinancePipelineOrchestrator}, all six
 * stages, the generic {@code JdbcTableConnector}, {@code ConnectorRegistry}, the property-backed
 * settings provider, the environment-backed credential provider, {@code DriverManager}, the
 * readiness validator, the registry schema built by the real Flyway migrations on a real MySQL 8.0,
 * and every database constraint on both sides.
 *
 * <p>Not real: the temple. There is no temple database, because Q4 is unresolved and no network
 * path to one exists. The source is an H2 schema invented for this test, holding three receipts
 * that resemble nothing any temple has ever recorded. That substitution is the point rather than a
 * shortcut: everything between "somebody asked for a run" and "a figure was published" is
 * production code exercised for the first time, and the one thing standing in is the one thing
 * that cannot yet be reached.
 *
 * <p><b>No temple credential is used.</b> The H2 database's own password is invented in this file,
 * resolved through the same {@code SourceCredentialProvider} a real run would use.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ManualSyncTriggerE2ETest {

    private static final String SOURCE_TABLE = "SOURCE_RECEIPTS";
    private static final String CREDENTIAL_REF = "synthetic-source";

    /** The synthetic source database's own password. Invented here; no temple uses it. */
    private static final String SOURCE_SECRET = "synthetic-not-a-real-secret";

    /** Financial year 2026-27 — the year the synthetic receipts fall in. */
    private static final String FY = "2026-27";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_trigger")
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

    @Autowired private TempleRepository temples;
    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinTempleCapabilityRepository capabilities;
    @Autowired private FinSourceOfTruthDeclRepository declarations;
    @Autowired private FinMappingRuleRepository rules;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinStgRevenueMappingRepository mappings;
    @Autowired private FinRevenueFactRepository facts;
    @Autowired private FinSyncErrorRepository errors;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private FinReconciliationResultRepository reconciliations;
    @Autowired private FinAggRevenuePeriodRepository aggregates;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();

    private String sourceUrl;
    private Connection keepAlive;
    private Temple temple;
    private FinSourceSystem source;
    private SyncWorkerProperties properties;
    private ManualSyncTrigger trigger;

    // ------------------------------------------------------------------ fixture

    @BeforeEach
    void setUp() throws SQLException {
        clearFinanceTables();
        createSyntheticSource();

        temple = temples.saveAndFlush(Temple.builder()
                .registrationNumber("FIN058-" + UUID.randomUUID())
                .name("Synthetic trigger temple")
                .primaryDeity("Test")
                .districtId(9_400_058L)
                .build());

        String systemCode = "synthsrc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        source = sourceSystems.saveAndFlush(FinSourceSystem.builder()
                .templeId(temple.getId())
                .systemCode(systemCode)
                .systemName("Synthetic receipts database")
                .sourceTechnology(SourceTechnology.MYSQL)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean(JdbcTableConnector.CONNECTOR_ID)
                .sourceTempleCode("SYN")
                .credentialRef(CREDENTIAL_REF)
                .sourceTimezone("Asia/Kolkata")
                .syncEnabled(true)
                .build());

        declareCapability();
        declare("REVENUE_TRANSACTION_DATE", "TRANSACTION_DATE");
        declare("REVENUE_AMOUNT", "AMOUNT");
        rules.saveAndFlush(FinMappingRule.builder()
                .sourceSystemId(source.getId())
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue("SERVICE_CODE:SVC001")
                .sourceLabel("Sevas")
                .canonicalValue("SEVA")
                .priority(100)
                .active(true)
                .build());

        properties = new SyncWorkerProperties();
        properties.setEnabled(true);
        trigger = newTrigger(systemCode);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    /**
     * The synthetic source: one table, three receipts, no temple's data and no temple's schema.
     *
     * <pre>
     *   ID | TRANSACTION_DATE | AMOUNT  | PAYMENT_MODE | SERVICE_CODE | UPDATED_AT
     *   R1 | 2026-04-01       | 1000.00 | CASH         | SVC001       | 2026-04-01 09:15
     *   R2 | 2026-04-01       |  500.00 | UPI          | SVC002       | 2026-04-01 11:40
     *   R3 | 2026-04-02       |  750.00 | CASH         | SVC001       | 2026-04-02 08:05
     * </pre>
     *
     * <p>A private in-memory database per test, held open by one connection so the schema survives
     * between the connector's own short-lived ones.
     */
    private void createSyntheticSource() throws SQLException {
        sourceUrl = "jdbc:h2:mem:fin058_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=0;MODE=LEGACY";
        keepAlive = DriverManager.getConnection(sourceUrl, "sa", SOURCE_SECRET);
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("""
                    CREATE TABLE SOURCE_RECEIPTS (
                        ID               VARCHAR(40) PRIMARY KEY,
                        TRANSACTION_DATE DATE,
                        AMOUNT           DECIMAL(18,2),
                        PAYMENT_MODE     VARCHAR(20),
                        SERVICE_CODE     VARCHAR(20),
                        UPDATED_AT       TIMESTAMP
                    )""");
            statement.execute("""
                    INSERT INTO SOURCE_RECEIPTS VALUES
                      ('R1', DATE '2026-04-01', 1000.00, 'CASH', 'SVC001', TIMESTAMP '2026-04-01 09:15:00'),
                      ('R2', DATE '2026-04-01',  500.00, 'UPI',  'SVC002', TIMESTAMP '2026-04-01 11:40:00'),
                      ('R3', DATE '2026-04-02',  750.00, 'CASH', 'SVC001', TIMESTAMP '2026-04-02 08:05:00')""");
        }
    }

    /**
     * The worker stack, assembled from the production classes.
     *
     * <p>Deliberately not a Spring context: {@code SyncWorkerConfig} is the same wiring, and
     * booting it here would need the worker profile on top of {@code @DataJpaTest}. What matters is
     * that every collaborator below is the real class, including the two that read configuration.
     */
    private ManualSyncTrigger newTrigger(String systemCode) {
        MockEnvironment environment = new MockEnvironment();
        String prefix = "trm.finance.jdbc." + systemCode + ".";
        environment.setProperty(prefix + "url", sourceUrl);
        environment.setProperty(prefix + "table", SOURCE_TABLE);
        environment.setProperty(prefix + "columns",
                "ID,TRANSACTION_DATE,AMOUNT,PAYMENT_MODE,SERVICE_CODE");
        environment.setProperty(prefix + "record-ref-column", "ID");
        environment.setProperty(prefix + "changed-at-column", "UPDATED_AT");
        environment.setProperty(prefix + "business-date-column", "TRANSACTION_DATE");
        environment.setProperty(prefix + "amount-column", "AMOUNT");
        environment.setProperty(prefix + "fetch-size", "100");
        environment.setProperty(prefix + "query-timeout-seconds", "30");
        // The synthetic source's own credential, resolved through the production provider.
        environment.setProperty("trm.finance.source." + CREDENTIAL_REF + ".principal", "sa");
        environment.setProperty("trm.finance.source." + CREDENTIAL_REF + ".secret", SOURCE_SECRET);

        JdbcTableConnector connector = new JdbcTableConnector(
                new PropertiesJdbcSourceSettingsProvider(environment),
                new EnvironmentSourceCredentialProvider(environment),
                new DriverManagerJdbcConnectionFactory());
        ConnectorRegistry registry =
                new ConnectorRegistry(Map.of(JdbcTableConnector.CONNECTOR_ID, connector));

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        RevenueNormalizationStage normalization = new RevenueNormalizationStage(
                batches, staging, mappings, declarations, categories, errors, template, json);
        FinancePipelineOrchestrator orchestrator = new FinancePipelineOrchestrator(
                new RevenueExtractionStage(registry, sourceSystems, staging, batches, errors, template, json),
                new RevenueStagingValidator(staging, errors, batches, template, json),
                new RevenueMappingStage(staging, mappings, rules, categories, errors, batches, template, json),
                new RevenueLoadStage(normalization, facts, staging, batches, errors, template),
                new RevenueReconciliationStage(registry, sourceSystems, batches, staging, errors,
                        facts, reconciliations, template),
                new RevenueAggregationRebuilder(batches, facts, new ReconciliationGate(reconciliations, facts),
                        new RevenueAggregationWriter(aggregates, template)),
                batches, errors, template);

        return new ManualSyncTrigger(sourceSystems, temples, batches,
                new OnboardingConfigurationReader(sourceSystems, capabilities, declarations, rules, categories),
                orchestrator, properties, template);
    }

    // ------------------------------------------------------------ case 1: success

    @Nested
    @DisplayName("A successful manual run")
    class Success {

        @Test
        @DisplayName("Reads the source, loads canonical facts, reconciles and publishes")
        void should_runTheWholePipeline_when_triggeredManually() {
            ManualSyncTrigger.Outcome outcome = trigger.runNow(source.getId());

            // Extraction really read the synthetic database: three rows, none invented.
            assertThat(outcome.pipeline().extracted().rowsStaged()).isEqualTo(3);
            assertThat(outcome.pipeline().validated().validated()).isEqualTo(3);
            assertThat(outcome.pipeline().mapped().decided()).isEqualTo(3);

            // Staging holds the source's own field names, uninterpreted, as the connector read them.
            assertThat(staging.countBySyncBatchId(outcome.syncBatchId())).isEqualTo(3);
            assertThat(staging.findAll())
                    .extracting(row -> row.getSourceRecordRef())
                    .containsExactlyInAnyOrder("R1", "R2", "R3");

            // Canonical facts: the daily grain, collapsed by date and category.
            //   2026-04-01  SEVA      1000.00   (SVC001, mapped)
            //   2026-04-01  UNMAPPED   500.00   (SVC002, no rule -- routed, never dropped)
            //   2026-04-02  SEVA       750.00
            assertThat(facts.countByTempleId(temple.getId())).isEqualTo(3);
            assertThat(facts.sumGrossForFinancialYear(temple.getId(), FY))
                    .get().isEqualTo(new BigDecimal("2250.00"));

            // Reconciliation asked the source for its own total and agreed with ours.
            assertThat(reconciliations.findAll()).isNotEmpty();
            assertThat(outcome.blocksPublication())
                    .as("source and canonical totals agree, so publication is not withheld")
                    .isFalse();

            // Publication: the period aggregate exists for the year this batch touched.
            assertThat(aggregates.findAll()).isNotEmpty();

            FinSyncBatch batch = batches.findById(outcome.syncBatchId()).orElseThrow();
            assertThat(batch.getStatus()).isEqualTo(SyncStatus.SUCCESS);
            assertThat(outcome.status()).isEqualTo(SyncStatus.SUCCESS);
            assertThat(batch.getTriggeredBy()).isEqualTo(SyncTrigger.MANUAL);
            assertThat(batch.getCapability()).isEqualTo(FinanceCapability.REVENUE);
            assertThat(batch.getRowsExtracted()).isEqualTo(3L);
            assertThat(batch.getRowsLoaded()).isEqualTo(3L);
            assertThat(batch.getStartedAt()).isNotNull();
            assertThat(batch.getFinishedAt()).isNotNull();
            assertThat(batch.getDurationMs()).isNotNull();
        }

        @Test
        @DisplayName("The first run is HISTORICAL and unbounded; the next resumes from it")
        void should_deriveTheWindow_when_noneIsGiven() {
            ManualSyncTrigger.Outcome first = trigger.runNow(source.getId());
            FinSyncBatch firstBatch = batches.findById(first.syncBatchId()).orElseThrow();

            assertThat(firstBatch.getSyncType())
                    .as("nothing has ever succeeded for this source, so everything it holds is wanted")
                    .isEqualTo(SyncType.HISTORICAL);
            assertThat(firstBatch.getWindowFrom()).isNull();
            assertThat(firstBatch.getWindowTo()).isNotNull();

            ManualSyncTrigger.Outcome second = trigger.runNow(source.getId());
            FinSyncBatch secondBatch = batches.findById(second.syncBatchId()).orElseThrow();

            assertThat(secondBatch.getSyncType()).isEqualTo(SyncType.INCREMENTAL);
            assertThat(secondBatch.getWindowFrom()).isEqualTo(firstBatch.getWindowTo());
        }

        @Test
        @DisplayName("Re-running the same source loads no duplicate facts")
        void should_stayIdempotent_when_runTwice() {
            trigger.runNow(source.getId(), SyncType.HISTORICAL, null, null);
            trigger.runNow(source.getId(), SyncType.BACKFILL, null, null);

            // The grain's unique constraint, not the trigger, is what makes this true.
            assertThat(facts.countByTempleId(temple.getId())).isEqualTo(3);
            assertThat(facts.sumGrossForFinancialYear(temple.getId(), FY))
                    .get().isEqualTo(new BigDecimal("2250.00"));
        }
    }

    // ------------------------------------------------------- case 2: source failure

    @Nested
    @DisplayName("A failing source")
    class Failure {

        @Test
        @DisplayName("Leaves the batch FAILED, publishes nothing, and records the stage")
        void should_failTheBatch_when_theSourceTableIsGone() throws SQLException {
            dropSourceTable();

            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(FinancePipelineOrchestrator.PipelineFailedException.class);

            FinSyncBatch batch = batches.findAll().get(0);
            assertThat(batch.getStatus()).isEqualTo(SyncStatus.FAILED);
            assertThat(batch.getFinishedAt())
                    .as("never left RUNNING: that is the state nothing recovers from automatically")
                    .isNotNull();
            assertThat(errors.findBySyncBatchIdAndErrorStageOrderByIdAsc(batch.getId(), SyncStage.EXTRACT))
                    .anySatisfy(error -> assertThat(error.getErrorCode()).isEqualTo("STAGE_FAILED"));

            // An empty extract presented as success would read as "the temple earned nothing".
            assertThat(staging.countBySyncBatchId(batch.getId())).isZero();
            assertThat(facts.countByTempleId(temple.getId())).isZero();
            assertThat(aggregates.findAll()).isEmpty();
        }

        @Test
        @DisplayName("A failed run does not become the position the next one resumes from")
        void should_notAdvanceTheSuccessfulWatermark_when_aLaterRunFails() throws SQLException {
            // A run that succeeds, establishing the resume position.
            ManualSyncTrigger.Outcome good = trigger.runNow(source.getId());
            LocalDateTime established = batches.findById(good.syncBatchId()).orElseThrow().getWindowTo();
            assertThat(good.status()).isEqualTo(SyncStatus.SUCCESS);

            // A later run, aiming past it, that fails.
            dropSourceTable();
            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(FinancePipelineOrchestrator.PipelineFailedException.class);
            FinSyncBatch failed = latestBatch();
            assertThat(failed.getStatus()).isEqualTo(SyncStatus.FAILED);
            assertThat(failed.getWindowTo())
                    .as("the failed run was aiming at a later position than the last good one")
                    .isAfter(established);

            // FIN-D-005: the next run resumes from the last SUCCESS, not from the last attempt.
            restoreSourceTable();
            ManualSyncTrigger.Outcome next = trigger.runNow(source.getId());
            FinSyncBatch resumed = batches.findById(next.syncBatchId()).orElseThrow();

            assertThat(resumed.getWindowFrom())
                    .as("resuming from the failed batch would silently skip every row between "
                            + "the last success and the failure")
                    .isEqualTo(established);
            assertThat(resumed.getWindowFrom()).isNotEqualTo(failed.getWindowTo());

            assertThat(batches.findFirstBySourceSystemIdAndCapabilityAndStatusOrderByIdDesc(
                    source.getId(), FinanceCapability.REVENUE, SyncStatus.SUCCESS))
                    .get()
                    .extracting(FinSyncBatch::getId)
                    .isNotEqualTo(failed.getId());
        }
    }

    // ------------------------------------------------- cases 3 and 4: refusals

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("A source not enabled for sync is refused, and no batch is written")
        void should_refuse_when_sourceIsNotEnabled() {
            source.setSyncEnabled(false);
            sourceSystems.saveAndFlush(source);

            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(SyncRefusedException.class)
                    .extracting(e -> ((SyncRefusedException) e).getReason())
                    .isEqualTo(SyncRefusedException.Reason.NOT_ENABLED_FOR_SYNC);

            // No attempt was made, so the audit spine records none.
            assertThat(batches.findAll()).isEmpty();
            assertThat(staging.findAll()).isEmpty();
        }

        @Test
        @DisplayName("A blocking readiness finding is refused, and no source is contacted")
        void should_refuse_when_readinessHasABlockingFinding() {
            // Deactivating the only revenue rule makes every figure load unclassified —
            // NO_ACTIVE_MAPPING_RULE, a blocking finding the readiness screen already shows.
            rules.findBySourceSystemIdAndDeletedFalse(source.getId())
                    .forEach(rule -> {
                        rule.setActive(false);
                        rules.saveAndFlush(rule);
                    });

            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(SyncRefusedException.class)
                    .hasMessageContaining("NO_ACTIVE_MAPPING_RULE")
                    .extracting(e -> ((SyncRefusedException) e).getReason())
                    .isEqualTo(SyncRefusedException.Reason.READINESS_BLOCKED);

            assertThat(batches.findAll()).isEmpty();
        }

        @Test
        @DisplayName("Readiness is recomputed at the moment of the run, not trusted from activation")
        void should_refuse_when_configurationDriftedAfterBeingEnabled() {
            // Enabled while correct, then a required declaration is retired underneath it.
            declarations.findBySourceSystemIdAndDeletedFalseOrderByMetricAscVersionDesc(source.getId())
                    .stream()
                    .filter(row -> "REVENUE_AMOUNT".equals(row.getMetric()))
                    .forEach(row -> {
                        row.setEffectiveTo(LocalDate.now().minusDays(1));
                        declarations.saveAndFlush(row);
                    });

            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(SyncRefusedException.class)
                    .hasMessageContaining("SOURCE_OF_TRUTH_MISSING");
            assertThat(batches.findAll()).isEmpty();
        }

        @Test
        @DisplayName("A source that does not exist is refused by name")
        void should_refuse_when_sourceIsUnknown() {
            assertThatThrownBy(() -> trigger.runNow(-1L))
                    .isInstanceOf(SyncRefusedException.class)
                    .extracting(e -> ((SyncRefusedException) e).getReason())
                    .isEqualTo(SyncRefusedException.Reason.NO_SUCH_SOURCE);
            assertThat(batches.findAll()).isEmpty();
        }

        @Test
        @DisplayName("A worker with extraction switched off contacts nothing at all")
        void should_refuse_when_theWorkerItselfIsDisabled() {
            properties.setEnabled(false);

            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(SyncRefusedException.class)
                    .extracting(e -> ((SyncRefusedException) e).getReason())
                    .isEqualTo(SyncRefusedException.Reason.WORKER_DISABLED);
            assertThat(batches.findAll()).isEmpty();
        }
    }

    // ------------------------------------------------------ case 5: duplicates

    @Nested
    @DisplayName("Duplicate execution")
    class Duplicates {

        @Test
        @DisplayName("A batch already in flight refuses a second request rather than queueing one")
        void should_refuse_when_aBatchIsAlreadyPending() {
            batches.saveAndFlush(FinSyncBatch.builder()
                    .batchRef(UUID.randomUUID().toString())
                    .templeId(temple.getId())
                    .sourceSystemId(source.getId())
                    .capability(FinanceCapability.REVENUE)
                    .syncType(SyncType.INCREMENTAL)
                    .status(SyncStatus.PENDING)
                    .triggeredBy(SyncTrigger.MANUAL)
                    .build());

            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(SyncRefusedException.class)
                    .extracting(e -> ((SyncRefusedException) e).getReason())
                    .isEqualTo(SyncRefusedException.Reason.ALREADY_IN_PROGRESS);

            assertThat(batches.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("A batch left RUNNING blocks a second request too")
        void should_refuse_when_aBatchIsAlreadyRunning() {
            batches.saveAndFlush(FinSyncBatch.builder()
                    .batchRef(UUID.randomUUID().toString())
                    .templeId(temple.getId())
                    .sourceSystemId(source.getId())
                    .capability(FinanceCapability.REVENUE)
                    .syncType(SyncType.INCREMENTAL)
                    .status(SyncStatus.RUNNING)
                    .triggeredBy(SyncTrigger.MANUAL)
                    .build());

            assertThatThrownBy(() -> trigger.runNow(source.getId()))
                    .isInstanceOf(SyncRefusedException.class);
            assertThat(batches.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("Two requests racing: exactly one run happens, and the source is read once")
        void should_admitOneRun_when_twoRequestsRace() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<String> attempt = () -> {
                go.await(10, TimeUnit.SECONDS);
                try {
                    trigger.runNow(source.getId());
                    return "ran";
                } catch (SyncRefusedException refused) {
                    return "refused:" + refused.getReason();
                }
            };
            Future<String> first = pool.submit(attempt);
            Future<String> second = pool.submit(attempt);
            go.countDown();
            List<String> outcomes = List.of(first.get(120, TimeUnit.SECONDS),
                    second.get(120, TimeUnit.SECONDS));
            pool.shutdownNow();

            // The write lock on the source row serialises the two decisions, so the loser sees the
            // winner's committed batch. Without it both read "nothing active" and both extract.
            assertThat(outcomes).containsExactlyInAnyOrder(
                    "ran", "refused:" + SyncRefusedException.Reason.ALREADY_IN_PROGRESS);
            assertThat(batches.findAll()).hasSize(1);
            assertThat(facts.sumGrossForFinancialYear(temple.getId(), FY))
                    .get().isEqualTo(new BigDecimal("2250.00"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private FinSyncBatch latestBatch() {
        return batches.findByTempleIdOrderByIdDesc(temple.getId()).get(0);
    }

    private void dropSourceTable() throws SQLException {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("DROP TABLE " + SOURCE_TABLE);
        }
    }

    private void restoreSourceTable() throws SQLException {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("""
                    CREATE TABLE SOURCE_RECEIPTS (
                        ID               VARCHAR(40) PRIMARY KEY,
                        TRANSACTION_DATE DATE,
                        AMOUNT           DECIMAL(18,2),
                        PAYMENT_MODE     VARCHAR(20),
                        SERVICE_CODE     VARCHAR(20),
                        UPDATED_AT       TIMESTAMP
                    )""");
            statement.execute("""
                    INSERT INTO SOURCE_RECEIPTS VALUES
                      ('R1', DATE '2026-04-01', 1000.00, 'CASH', 'SVC001', TIMESTAMP '2026-04-01 09:15:00'),
                      ('R2', DATE '2026-04-01',  500.00, 'UPI',  'SVC002', TIMESTAMP '2026-04-01 11:40:00'),
                      ('R3', DATE '2026-04-02',  750.00, 'CASH', 'SVC001', TIMESTAMP '2026-04-02 08:05:00')""");
        }
    }

    private void declareCapability() {
        capabilities.saveAndFlush(FinTempleCapability.builder()
                .templeId(temple.getId())
                .sourceSystemId(source.getId())
                .capability(FinanceCapability.REVENUE)
                .availability(DataAvailability.AVAILABLE)
                .coverageFrom(LocalDate.of(2026, 4, 1))
                .build());
    }

    private void declare(String metric, String sourceField) {
        declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                .sourceSystemId(source.getId())
                .metric(metric)
                .version(1)
                .sourceObject(SOURCE_TABLE)
                .sourceField(sourceField)
                .build());
    }

    private void clearFinanceTables() {
        aggregates.deleteAllInBatch();
        reconciliations.deleteAllInBatch();
        facts.deleteAllInBatch();
        mappings.deleteAllInBatch();
        staging.deleteAllInBatch();
        errors.deleteAllInBatch();
        batches.deleteAllInBatch();
        declarations.deleteAllInBatch();
        rules.deleteAllInBatch();
        capabilities.deleteAllInBatch();
        sourceSystems.deleteAllInBatch();
    }
}

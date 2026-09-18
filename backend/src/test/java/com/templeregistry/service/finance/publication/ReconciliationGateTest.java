package com.templeregistry.service.finance.publication;

import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationCheckType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * FIN-061 against a real MySQL 8.0 container with the real migrations.
 *
 * <p>The gate reads only central tables, so nothing here needs a connector, a credential or a
 * source system that could be reached. Temple and source ids are test values no seeded
 * configuration uses.
 *
 * <p>Most of these are about refusing to publish. The one that would matter most in production is
 * {@code should_block_when_aContributingBatchWasNeverReconciled}: it is the state a batch leaves
 * when it loads facts and then dies, and it is the one an "everything that ran agreed" gate would
 * wave through.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationGateTest {

    private static final long TEMPLE = 940003L;
    private static final String YEAR = "2019-20";
    private static final LocalDate IN_YEAR = LocalDate.of(2019, 6, 15);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_gate")
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
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private FinRevenueFactRepository facts;
    @Autowired private FinReconciliationResultRepository results;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private PlatformTransactionManager transactionManager;

    private FinSourceSystem source;
    private ReconciliationGate gate;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        results.deleteAllInBatch();
        facts.deleteAllInBatch();
        batches.deleteAllInBatch();
        sourceSystems.deleteAllInBatch();

        source = sourceSystems.save(FinSourceSystem.builder()
                .templeId(TEMPLE)
                .systemCode("GATE")
                .systemName("Synthetic gate source")
                .sourceTechnology(SourceTechnology.SQL_SERVER)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean("noConnectorNeeded")
                .sourceTempleCode("TEST")
                .credentialRef("test.credential")
                .sourceTimezone("Asia/Kolkata")
                .build());
        categoryId = categories.findByCategoryCodeAndDeletedFalse("SEVA")
                .map(FinRevenueCategory::getId).orElseThrow();
        gate = new ReconciliationGate(results, facts);
    }

    // ---------------------------------------------------------------- publishes

    @Test
    @DisplayName("Every check passed: the period publishes")
    void should_allow_when_everyCheckPassed() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.PASSED);

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        assertThat(decision.publishable()).isTrue();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.PASSED);
        assertThat(decision.contributingBatchIds()).containsExactly(batch.getId());
    }

    @Test
    @DisplayName("Checks that could not be made publish, flagged, rather than blocking forever")
    void should_allowFlagged_when_checksWereNotAvailable() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.NOT_AVAILABLE);

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        // This is the state of every real temple today: no connector implements sourceTotals().
        // Blocking on it would publish nothing, ever, while withholding correctly loaded figures.
        assertThat(decision.publishable()).isTrue();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
        assertThat(decision.reasons()).isNotEmpty();
    }

    // ---------------------------------------------------------------- blocks

    @Test
    @DisplayName("A failed period check blocks publication and names what failed")
    void should_block_when_aPeriodCheckFailed() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.FAILED);

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        assertThat(decision.publishable()).isFalse();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.FAILED);
        assertThat(decision.reasons()).anySatisfy(r -> assertThat(r).contains("GROSS_AMOUNT"));
    }

    @Test
    @DisplayName("A batch that lost rows blocks every period it fed, even one whose totals agree")
    void should_block_when_aContributingBatchWasIncomplete() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.FAILED);
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.PASSED);

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        // Partial extraction must never read as a clean period. The totals agreeing proves only
        // that what arrived adds up.
        assertThat(decision.publishable()).isFalse();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.FAILED);
        assertThat(decision.reasons()).anySatisfy(r -> assertThat(r).contains("STAGE_COMPLETENESS"));
    }

    @Test
    @DisplayName("A suspected deletion blocks publication without deleting anything")
    void should_blockAndDeleteNothing_when_deletionIsSuspected() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(batch, ReconciliationCheckType.SUSPECTED_SOURCE_DELETION, "RECORD_COUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.FAILED);
        long factsBefore = facts.countByTempleId(TEMPLE);

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        assertThat(decision.publishable()).isFalse();
        // FIN-060's guarantee survives the gate: a suspicion withholds a replacement, it does not
        // act on the canonical record.
        assertThat(facts.countByTempleId(TEMPLE)).isEqualTo(factsBefore);
        assertThat(facts.sumGrossForFinancialYear(TEMPLE, YEAR))
                .get().isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("A batch that loaded figures and was never reconciled blocks the period")
    void should_block_when_aContributingBatchWasNeverReconciled() {
        FinSyncBatch reconciled = batchWithFacts();
        result(reconciled, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(reconciled, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.PASSED);
        // A second batch loaded into the same year and then died before reconciliation.
        FinSyncBatch orphan = newBatch(SyncStatus.FAILED);
        fact(orphan, IN_YEAR.plusDays(1), "250.00");

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        // One batch's clean bill of health must not cover another batch's silence.
        assertThat(decision.publishable()).isFalse();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.PENDING);
        assertThat(decision.reasons()).anySatisfy(r -> assertThat(r)
                .contains(String.valueOf(orphan.getId())).contains("never reconciled"));
    }

    @Test
    @DisplayName("Figures with no reconciliation at all are PENDING, not PASSED")
    void should_block_when_nothingWasEverReconciled() {
        batchWithFacts();

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        assertThat(decision.publishable()).isFalse();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.PENDING);
    }

    @Test
    @DisplayName("A period with no facts publishes nothing and says so")
    void should_block_when_noFactsExist() {
        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        assertThat(decision.publishable()).isFalse();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.PENDING);
        assertThat(decision.contributingBatchIds()).isEmpty();
    }

    // ---------------------------------------------------------------- supersession and scope

    @Test
    @DisplayName("A later batch's clean result supersedes an earlier batch's variance")
    void should_allow_when_aLaterBatchClearedTheVariance() {
        FinSyncBatch first = batchWithFacts();
        result(first, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(first, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.FAILED);

        FinSyncBatch corrected = newBatch(SyncStatus.SUCCESS);
        fact(corrected, IN_YEAR, "100.00");
        result(corrected, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(corrected, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.PASSED);

        ReconciliationGate.Decision decision = gate.evaluate(TEMPLE, source.getId(), YEAR);

        // Otherwise a variance could never be cleared by fixing it, only by deleting history.
        assertThat(decision.publishable()).isTrue();
        assertThat(decision.status()).isEqualTo(ReconciliationStatus.PASSED);
    }

    @Test
    @DisplayName("Another source's failure does not block this source's period")
    void should_ignoreOtherSources_when_decidingAPeriod() {
        FinSyncBatch mine = batchWithFacts();
        result(mine, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(mine, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.PASSED);

        FinSourceSystem other = sourceSystems.save(FinSourceSystem.builder()
                .templeId(TEMPLE).systemCode("OTHER").systemName("Second source")
                .sourceTechnology(SourceTechnology.SQL_SERVER).connectorType(ConnectorType.PULL_JDBC)
                .connectorBean("noConnectorNeeded").sourceTempleCode("TEST")
                .credentialRef("test.credential").sourceTimezone("Asia/Kolkata").build());
        FinSyncBatch theirs = batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString()).templeId(TEMPLE)
                .sourceSystemId(other.getId()).capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL).triggeredBy(SyncTrigger.MANUAL)
                .status(SyncStatus.RECONCILE_FAILED).build());
        template().execute(tx -> results.record(TEMPLE, other.getId(), theirs.getId(),
                FinanceCapability.REVENUE.name(),
                ReconciliationCheckType.SOURCE_VS_CENTRAL.name(), "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR.name(), YEAR, new BigDecimal("1.00"),
                new BigDecimal("2.00"), new BigDecimal("1.00"), null, BigDecimal.ZERO,
                ReconciliationStatus.FAILED.name(), "other source variance", LocalDateTime.now()));

        assertThat(gate.evaluate(TEMPLE, source.getId(), YEAR).publishable()).isTrue();
        assertThat(gate.evaluate(TEMPLE, other.getId(), YEAR).publishable()).isFalse();
    }

    @Test
    @DisplayName("Another financial year's failure does not block this one")
    void should_ignoreOtherYears_when_decidingAPeriod() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.PASSED);
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, "2020-21", ReconciliationStatus.FAILED);

        assertThat(gate.evaluate(TEMPLE, source.getId(), YEAR).publishable()).isTrue();
    }

    // ---------------------------------------------------------------- guard, idempotency, races

    @Test
    @DisplayName("requirePublishable throws rather than returning a value a caller can ignore")
    void should_throw_when_guardingABlockedPeriod() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.FAILED);

        assertThatThrownBy(() -> gate.requirePublishable(TEMPLE, source.getId(), YEAR))
                .isInstanceOf(ReconciliationGate.PublicationBlockedException.class)
                .hasMessageContaining("BLOCKED")
                .extracting(e -> ((ReconciliationGate.PublicationBlockedException) e)
                        .decision().status())
                .isEqualTo(ReconciliationStatus.FAILED);
    }

    @Test
    @DisplayName("requirePublishable returns the decision when the period is clean")
    void should_returnDecision_when_guardingAPublishablePeriod() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.PASSED);

        assertThat(gate.requirePublishable(TEMPLE, source.getId(), YEAR).publishable()).isTrue();
    }

    @Test
    @DisplayName("Asking repeatedly gives the same answer and changes nothing")
    void should_beIdempotent_when_askedRepeatedly() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.FAILED);
        long resultRows = results.count();
        long factRows = facts.countByTempleId(TEMPLE);

        ReconciliationGate.Decision first = gate.evaluate(TEMPLE, source.getId(), YEAR);
        ReconciliationGate.Decision second = gate.evaluate(TEMPLE, source.getId(), YEAR);
        ReconciliationGate.Decision third = gate.evaluate(TEMPLE, source.getId(), YEAR);

        assertThat(first).isEqualTo(second).isEqualTo(third);
        // The decision is derived, so evaluating it cannot write anything -- there is no stored
        // verdict to drift from the evidence.
        assertThat(results.count()).isEqualTo(resultRows);
        assertThat(facts.countByTempleId(TEMPLE)).isEqualTo(factRows);
    }

    @Test
    @DisplayName("Two threads deciding at once reach the same verdict")
    void should_agree_when_twoThreadsDecideConcurrently() throws Exception {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", ReconciliationStatus.FAILED);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<ReconciliationGate.Decision> ask = () -> {
            go.await(5, TimeUnit.SECONDS);
            return gate.evaluate(TEMPLE, source.getId(), YEAR);
        };
        Future<ReconciliationGate.Decision> first = pool.submit(ask);
        Future<ReconciliationGate.Decision> second = pool.submit(ask);
        go.countDown();
        ReconciliationGate.Decision a = first.get(30, TimeUnit.SECONDS);
        ReconciliationGate.Decision b = second.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // No lock is needed because nothing is written; contradictory states are impossible
        // rather than merely unlikely.
        assertThat(a).isEqualTo(b);
        assertThat(a.publishable()).isFalse();
    }

    @Test
    @DisplayName("The decision explains itself in one line, without opening the database")
    void should_explainItself_when_blocked() {
        FinSyncBatch batch = batchWithFacts();
        result(batch, ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                PeriodType.FINANCIAL_YEAR, YEAR, ReconciliationStatus.FAILED);

        String explanation = gate.evaluate(TEMPLE, source.getId(), YEAR).explain();

        assertThat(explanation)
                .contains(String.valueOf(TEMPLE))
                .contains(YEAR)
                .contains("FAILED")
                .contains("BLOCKED");
    }

    // ---------------------------------------------------------------- helpers

    private FinSyncBatch batchWithFacts() {
        FinSyncBatch batch = newBatch(SyncStatus.SUCCESS);
        fact(batch, IN_YEAR, "100.00");
        return batch;
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

    private void fact(FinSyncBatch batch, LocalDate date, String gross) {
        template().execute(tx -> facts.upsert(TEMPLE, batch.getSourceSystemId(), batch.getId(), 1,
                "gate-" + date + "-" + batch.getId(), date, "2019-20", null, categoryId,
                PaymentMode.UNRECORDED.name(), PaymentModeConfidence.RECORDED.name(), null, null,
                1L, new BigDecimal(gross), null, null, null, "INR", LocalDateTime.now()));
    }

    private void result(FinSyncBatch batch, ReconciliationCheckType checkType, String metric,
                        PeriodType periodType, String periodKey, ReconciliationStatus status) {
        template().execute(tx -> results.record(TEMPLE, batch.getSourceSystemId(), batch.getId(),
                FinanceCapability.REVENUE.name(), checkType.name(), metric, periodType.name(),
                periodKey, null, null, null, null, BigDecimal.ZERO, status.name(),
                "fixture: " + checkType + " " + status, LocalDateTime.now()));
    }

    private TransactionTemplate template() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}

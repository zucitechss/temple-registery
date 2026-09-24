package com.templeregistry.service.finance.aggregation;

import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.entity.finance.FinAggRevenuePeriod;
import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.service.finance.pipeline.FinancialYear;
import com.templeregistry.service.finance.publication.ReconciliationGate;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FIN-070 against a real MySQL 8.0 container with the real migrations.
 *
 * <p>Three things can only be proved with a database, and all three are ways to double a reported
 * figure: the unique key that makes a rebuild replace rather than append, the generated
 * {@code net_amount} that no writer can contradict, and {@code DECIMAL} arithmetic that does not
 * drift. The arithmetic itself is proved without a container in {@code RevenueAggregatorTest}.
 *
 * <p>Schema assertions live here rather than in {@code com.templeregistry.migration} alongside the
 * other migration tests: they are about the one table this class writes to, and splitting them would
 * mean a second container and a second application context to assert two halves of one contract.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class RevenueAggregatePersistenceTest {

    private static final long TEMPLE_A = 940001L;
    private static final long TEMPLE_B = 940002L;
    private static final long SOURCE_A = 9401L;
    private static final long SOURCE_B = 9402L;
    private static final long SEVA = 1L;
    private static final String YEAR = "2025-26";
    private static final LocalDate JUNE = LocalDate.of(2025, 6, 15);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_agg")
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

    @Autowired private FinAggRevenuePeriodRepository aggregates;
    @Autowired private FinRevenueFactRepository facts;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private RevenueAggregationWriter writer;

    @BeforeEach
    void setUp() {
        writer = new RevenueAggregationWriter(aggregates, new TransactionTemplate(transactionManager));
        aggregates.deleteAllInBatch();
        facts.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- the schema

    @Nested
    @DisplayName("V119 schema")
    class Schema {

        @Test
        @DisplayName("uk_farp_grain covers the six grain columns, in order, and is unique")
        void should_defineGrain_when_v119HasRun() {
            assertThat(columnsOf("uk_farp_grain"))
                    .containsExactly("temple_id", "source_system_id", "period_type", "period_key",
                            "category_id", "payment_mode");
            assertThat(scalar("""
                    SELECT non_unique FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = 'fin_agg_revenue_period'
                       AND index_name = 'uk_farp_grain' AND seq_in_index = 1
                    """).toString()).isEqualTo("0");
        }

        /**
         * The whole reason category_id is a key column rather than a nullable "all" marker. MySQL
         * treats NULLs in a unique index as distinct, so a nullable total row would not be
         * constrained and every run would insert another one (FIN-070B D5, and FIN-D-018 before it).
         */
        @Test
        @DisplayName("Every grain column is NOT NULL, so no stand-in columns are needed")
        void should_rejectNullsInTheGrain_when_writing() {
            assertThat(valuesOf("""
                    SELECT is_nullable FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'fin_agg_revenue_period'
                       AND column_name IN ('temple_id','source_system_id','period_type','period_key',
                                           'category_id','payment_mode','financial_year','currency')
                    """)).containsOnly("NO");
        }

        @Test
        @DisplayName("net_amount is generated by the database, not by a writer")
        void should_generateNetAmount_when_columnInspected() {
            assertThat(valuesOf("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'fin_agg_revenue_period'
                       AND extra LIKE '%GENERATED%'
                    """)).containsExactly("net_amount");
        }

        @Test
        @DisplayName("The reporting indexes exist")
        void should_createIndexes_when_v119HasRun() {
            assertThat(columnsOf("idx_farp_temple_source_fy"))
                    .containsExactly("temple_id", "source_system_id", "financial_year");
            assertThat(columnsOf("idx_farp_report"))
                    .containsExactly("temple_id", "period_type", "period_key", "category_id",
                            "payment_mode");
        }

        /** DECIMAL end to end. A DOUBLE column would lose paisa on a large temple's year. */
        @Test
        @DisplayName("Money columns are DECIMAL, never floating point")
        void should_useDecimal_when_storingMoney() {
            assertThat(valuesOf("""
                    SELECT DISTINCT data_type FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'fin_agg_revenue_period'
                       AND column_name IN ('gross_amount','cancelled_amount','net_amount','quantity')
                    """)).containsExactly("decimal");
        }
    }

    // ---------------------------------------------------------------- idempotency

    @Nested
    @DisplayName("Writing")
    class Writing {

        @Test
        @DisplayName("Running the same aggregation twice leaves one row per period, not two")
        void should_replaceNotAppend_when_runRepeatedly() {
            List<FinRevenueFact> input = List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "100.00"));

            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(input));
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(input));
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(input));

            assertThat(aggregates.countByTempleId(TEMPLE_A))
                    .as("one FINANCIAL_YEAR row and one MONTH row, however many times it runs")
                    .isEqualTo(2);
            assertThat(aggregates.sumGrossForFinancialYear(TEMPLE_A, YEAR))
                    .as("an accumulating writer would report 300.00 here and satisfy the key perfectly")
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("A recomputed period replaces the figure rather than adding to it")
        void should_replaceFigure_when_factsChange() {
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "100.00"))));
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "150.00"))));

            assertThat(aggregates.sumGrossForFinancialYear(TEMPLE_A, YEAR))
                    .isEqualByComparingTo("150.00");
            assertThat(aggregates.countByTempleId(TEMPLE_A)).isEqualTo(2);
        }

        @Test
        @DisplayName("created_at survives a recomputation; computed_at moves")
        void should_keepFirstPublishedAt_when_recomputed() {
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "100.00"))));
            FinAggRevenuePeriod first = yearRow(TEMPLE_A, SOURCE_A);

            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "101.00"))));
            FinAggRevenuePeriod second = yearRow(TEMPLE_A, SOURCE_A);

            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(second.getCreatedAt())
                    .as("a recomputed period was still first published when it was")
                    .isEqualTo(first.getCreatedAt());
        }

        @Test
        @DisplayName("Two source systems hold one temple's same period independently")
        void should_keepSourcesApart_when_bothReportTheSamePeriod() {
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "100.00"))));
            writer.write(passed(TEMPLE_A, SOURCE_B), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_B, JUNE, "900.00"))));

            assertThat(aggregates.countByTempleId(TEMPLE_A)).isEqualTo(4);
            assertThat(aggregates.sumGrossForFinancialYear(TEMPLE_A, YEAR))
                    .as("a temple's figure is the sum of its sources, computed at read time")
                    .isEqualByComparingTo("1000.00");
            assertThat(aggregates.findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                    TEMPLE_A, SOURCE_A, YEAR)).hasSize(2);
        }

        @Test
        @DisplayName("One temple's aggregates are never visible in another's total")
        void should_isolateTemples_when_bothHaveTheSamePeriod() {
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "111.00"))));
            writer.write(passed(TEMPLE_B, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_B, SOURCE_A, JUNE, "222.00"))));

            assertThat(aggregates.sumGrossForFinancialYear(TEMPLE_A, YEAR)).isEqualByComparingTo("111.00");
            assertThat(aggregates.sumGrossForFinancialYear(TEMPLE_B, YEAR)).isEqualByComparingTo("222.00");
        }

        @Test
        @DisplayName("A duplicate grain cannot be inserted behind the writer's back")
        void should_rejectDuplicateGrain_when_insertedDirectly() {
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "100.00"))));

            FinAggRevenuePeriod duplicate = yearRow(TEMPLE_A, SOURCE_A);
            duplicate.setId(null);

            assertThatThrownBy(() -> {
                aggregates.save(duplicate);
                aggregates.flush();
            }).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ---------------------------------------------------------------- measures

    @Nested
    @DisplayName("Measures as stored")
    class Measures {

        @Test
        @DisplayName("net_amount is gross minus cancelled where every fact recorded both")
        void should_computeNet_when_cancellationsAreRecorded() {
            FinRevenueFact recorded = fact(TEMPLE_A, SOURCE_A, JUNE, "100.00");
            recorded.setCancelledAmount(new BigDecimal("10.00"));
            recorded.setCancelledCount(1L);

            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(List.of(recorded)));

            assertThat(yearRow(TEMPLE_A, SOURCE_A).getNetAmount()).isEqualByComparingTo("90.00");
        }

        @Test
        @DisplayName("net_amount stays NULL where cancellations are not recorded")
        void should_leaveNetNull_when_cancellationsAreUnknown() {
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "100.00"))));

            FinAggRevenuePeriod row = yearRow(TEMPLE_A, SOURCE_A);
            assertThat(row.getCancelledAmount()).isNull();
            assertThat(row.getNetAmount())
                    .as("reporting gross as net would assert that nothing was cancelled")
                    .isNull();
        }

        @Test
        @DisplayName("Decimal sums are exact across ten thousand facts")
        void should_keepPrecision_when_summingManyFacts() {
            List<FinRevenueFact> many = java.util.stream.IntStream.range(0, 10_000)
                    .mapToObj(i -> fact(TEMPLE_A, SOURCE_A, JUNE, "0.01"))
                    .toList();

            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(many));

            assertThat(yearRow(TEMPLE_A, SOURCE_A).getGrossAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("A month row records that its verdict was inherited; a year row does not")
        void should_recordInheritedVerdict_when_periodIsAMonth() {
            writer.write(passed(TEMPLE_A, SOURCE_A), RevenueAggregator.aggregate(
                    List.of(fact(TEMPLE_A, SOURCE_A, JUNE, "100.00"))));

            assertThat(yearRow(TEMPLE_A, SOURCE_A).getReconciliationInherited()).isFalse();
            assertThat(monthRow(TEMPLE_A, SOURCE_A).getReconciliationInherited())
                    .as("no reconciliation result exists at month scope")
                    .isTrue();
            assertThat(monthRow(TEMPLE_A, SOURCE_A).getFinancialYear())
                    .as("and the month still carries the year the gate decided on")
                    .isEqualTo(YEAR);
        }
    }

    // ---------------------------------------------------------------- the facts are read-only

    @Test
    @DisplayName("Aggregating changes no canonical fact")
    void should_leaveFactsUntouched_when_aggregating() {
        FinRevenueFact stored = persistFact(TEMPLE_A, SOURCE_A, JUNE, "100.00");
        LocalDateTime before = stored.getUpdatedAt();
        long countBefore = facts.countByTempleId(TEMPLE_A);

        writer.write(passed(TEMPLE_A, SOURCE_A),
                RevenueAggregator.aggregate(facts.findByTempleIdAndTransactionDateOrderByIdAsc(TEMPLE_A, JUNE)));

        FinRevenueFact after = facts.findById(stored.getId()).orElseThrow();
        assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(countBefore);
        assertThat(after.getUpdatedAt())
                .as("aggregation reads facts; nothing in this path may write one")
                .isEqualTo(before);
        assertThat(after.getGrossAmount()).isEqualByComparingTo("100.00");
    }


    // ---------------------------------------------------------------- FIN-072 rebuild

    @Nested
    @DisplayName("Rebuilding affected periods (FIN-072)")
    class Rebuilding {

        private static final LocalDate EARLY = LocalDate.of(2023, 6, 15);
        private static final LocalDate LATE = LocalDate.of(2025, 6, 15);
        private static final LocalDate UNTOUCHED = LocalDate.of(2024, 6, 15);
        private static final long BATCH = 1L;
        private static final long OTHER_BATCH = 2L;

        private RevenueAggregationRebuilder rebuilder;
        private ReconciliationGate gate;

        @BeforeEach
        void wireRebuilder() {
            gate = mock(ReconciliationGate.class);
            FinSyncBatchRepository batches = mock(FinSyncBatchRepository.class);
            when(batches.findById(BATCH)).thenReturn(Optional.of(
                    FinSyncBatch.builder().templeId(TEMPLE_A).sourceSystemId(SOURCE_A).build()));
            rebuilder = new RevenueAggregationRebuilder(batches, facts, gate, writer);
        }

        @Test
        @DisplayName("republishes exactly the years the batch touched, and no other")
        void should_rebuildOnlyAffectedYears_when_otherYearsExist() {
            persistFact(TEMPLE_A, SOURCE_A, BATCH, EARLY, "100.00");
            persistFact(TEMPLE_A, SOURCE_A, BATCH, LATE, "200.00");
            // A third year, published earlier by a different batch, which this rebuild must not touch.
            persistFact(TEMPLE_A, SOURCE_A, OTHER_BATCH, UNTOUCHED, "555.00");
            writer.write(passedFor(TEMPLE_A, SOURCE_A, "2024-25"), RevenueAggregator.aggregate(
                    facts.findByTempleIdAndTransactionDateOrderByIdAsc(TEMPLE_A, UNTOUCHED)));
            LocalDateTime untouchedComputedAt = rowFor("2024-25").getComputedAt();

            publishable("2023-24");
            publishable("2025-26");
            RevenueAggregationRebuilder.Result result = rebuilder.rebuildBatch(BATCH);

            assertThat(result.rebuilt()).containsExactly("2023-24", "2025-26");
            assertThat(rowFor("2023-24").getGrossAmount()).isEqualByComparingTo("100.00");
            assertThat(rowFor("2025-26").getGrossAmount()).isEqualByComparingTo("200.00");
            assertThat(rowFor("2024-25").getComputedAt())
                    .as("a year the batch never touched keeps the figure and the timestamp it had")
                    .isEqualTo(untouchedComputedAt);
            assertThat(rowFor("2024-25").getGrossAmount()).isEqualByComparingTo("555.00");
        }

        @Test
        @DisplayName("a rebuild changes no canonical fact")
        void should_leaveFactsUntouched_when_rebuilding() {
            FinRevenueFact stored = persistFact(TEMPLE_A, SOURCE_A, BATCH, LATE, "200.00");
            LocalDateTime before = stored.getUpdatedAt();
            long countBefore = facts.countByTempleId(TEMPLE_A);
            publishable("2025-26");

            rebuilder.rebuildBatch(BATCH);

            FinRevenueFact after = facts.findById(stored.getId()).orElseThrow();
            assertThat(facts.countByTempleId(TEMPLE_A)).isEqualTo(countBefore);
            assertThat(after.getUpdatedAt())
                    .as("FIN-070B section 12's acceptance test: a rebuild reads facts and writes none")
                    .isEqualTo(before);
            assertThat(after.getGrossAmount()).isEqualByComparingTo("200.00");
        }

        @Test
        @DisplayName("a blocked year leaves its previously published figure standing")
        void should_leavePreviousRow_when_yearIsBlocked() {
            persistFact(TEMPLE_A, SOURCE_A, BATCH, LATE, "200.00");
            publishable("2025-26");
            rebuilder.rebuildBatch(BATCH);
            LocalDateTime publishedAt = rowFor("2025-26").getComputedAt();

            // The facts now say something different, but the gate refuses the year.
            persistFact(TEMPLE_A, SOURCE_A, BATCH, LATE.plusDays(1), "999.00");
            when(gate.evaluate(TEMPLE_A, SOURCE_A, "2025-26")).thenReturn(
                    new ReconciliationGate.Decision(TEMPLE_A, SOURCE_A, "2025-26",
                            ReconciliationStatus.FAILED, false, List.of("a check failed"), List.of(BATCH)));

            RevenueAggregationRebuilder.Result result = rebuilder.rebuildBatch(BATCH);

            assertThat(result.blocked()).containsExactly("2025-26");
            assertThat(rowFor("2025-26").getGrossAmount())
                    .as("the old figure stays; a blocked year is not zeroed and not deleted")
                    .isEqualByComparingTo("200.00");
            assertThat(rowFor("2025-26").getComputedAt()).isEqualTo(publishedAt);
        }

        @Test
        @DisplayName("running the same rebuild three times does not triple the figure")
        void should_notAccumulate_when_rebuiltRepeatedly() {
            persistFact(TEMPLE_A, SOURCE_A, BATCH, LATE, "200.00");
            publishable("2025-26");

            rebuilder.rebuildBatch(BATCH);
            rebuilder.rebuildBatch(BATCH);
            rebuilder.rebuildBatch(BATCH);

            assertThat(aggregates.findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                    TEMPLE_A, SOURCE_A, "2025-26"))
                    .as("one FINANCIAL_YEAR row and one MONTH row, however many times it runs")
                    .hasSize(2);
            assertThat(rowFor("2025-26").getGrossAmount()).isEqualByComparingTo("200.00");
        }

        private void publishable(String year) {
            when(gate.evaluate(TEMPLE_A, SOURCE_A, year))
                    .thenReturn(passedFor(TEMPLE_A, SOURCE_A, year));
        }

        private FinAggRevenuePeriod rowFor(String year) {
            return aggregates.findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                            TEMPLE_A, SOURCE_A, year).stream()
                    .filter(a -> a.getPeriodType() == PeriodType.FINANCIAL_YEAR)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No FINANCIAL_YEAR row for " + year));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ReconciliationGate.Decision passed(long templeId, long sourceSystemId) {
        return new ReconciliationGate.Decision(templeId, sourceSystemId, YEAR,
                ReconciliationStatus.PASSED, true, List.of("Every check that ran agreed."), List.of(1L));
    }

    private FinAggRevenuePeriod yearRow(long templeId, long sourceSystemId) {
        return row(templeId, sourceSystemId, PeriodType.FINANCIAL_YEAR);
    }

    private FinAggRevenuePeriod monthRow(long templeId, long sourceSystemId) {
        return row(templeId, sourceSystemId, PeriodType.MONTH);
    }

    private FinAggRevenuePeriod row(long templeId, long sourceSystemId, PeriodType periodType) {
        return aggregates.findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                        templeId, sourceSystemId, YEAR).stream()
                .filter(a -> a.getPeriodType() == periodType)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + periodType + " row"));
    }

    private FinRevenueFact persistFact(long templeId, long sourceSystemId, LocalDate date, String gross) {
        return persistFact(templeId, sourceSystemId, 1L, date, gross);
    }

    /** Publishable verdict for any year, not only {@link #YEAR}. */
    private static ReconciliationGate.Decision passedFor(long templeId, long sourceSystemId,
                                                         String financialYear) {
        return new ReconciliationGate.Decision(templeId, sourceSystemId, financialYear,
                ReconciliationStatus.PASSED, true, List.of("Every check that ran agreed."), List.of(1L));
    }

    private FinRevenueFact persistFact(long templeId, long sourceSystemId, long syncBatchId,
                                       LocalDate date, String gross) {
        // The fact upsert is @Modifying, and these test methods deliberately run outside a
        // transaction so that what is asserted is what committed -- the same reason
        // RevenueReconciliationStageTest wraps it (FIN-056).
        new TransactionTemplate(transactionManager).execute(tx ->
                facts.upsert(templeId, sourceSystemId, syncBatchId, 1, null, date, FinancialYear.of(date), null, SEVA,
                        PaymentMode.CASH.name(), "RECORDED", null, null, null, new BigDecimal(gross),
                        null, null, null, "INR", LocalDateTime.now()));
        return facts.findByTempleIdAndTransactionDateOrderByIdAsc(templeId, date).get(0);
    }

    private static FinRevenueFact fact(long templeId, long sourceSystemId, LocalDate date, String gross) {
        return FinRevenueFact.builder()
                .id(1L)
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .syncBatchId(1L)
                .transactionDate(date)
                .financialYear(FinancialYear.of(date))
                .categoryId(SEVA)
                .paymentMode(PaymentMode.CASH)
                .grossAmount(new BigDecimal(gross))
                .build();
    }

    private List<String> columnsOf(String indexName) {
        return valuesOf("""
                SELECT column_name FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'fin_agg_revenue_period'
                   AND index_name = '%s'
                 ORDER BY seq_in_index
                """.formatted(indexName));
    }

    @SuppressWarnings("unchecked")
    private List<String> valuesOf(String sql) {
        return entityManager.createNativeQuery(sql).getResultList().stream()
                .map(String::valueOf)
                .toList();
    }

    private Object scalar(String sql) {
        return entityManager.createNativeQuery(sql).getSingleResult();
    }
}

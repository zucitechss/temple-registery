package com.templeregistry.repository.finance;

import com.templeregistry.config.JpaAuditConfig;
import com.templeregistry.entity.finance.*;
import com.templeregistry.entity.finance.enums.*;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Phase 0 schema contract (V130&ndash;V139), against a real MySQL 8.0 container with the real
 * migrations.
 *
 * <p>Phase 0 exists so that two people can build the ingestion write path and the reporting read
 * path in parallel against one agreed schema. This test is what makes that agreement enforceable:
 * every assertion below is an invariant one stream relies on and the other could break.
 *
 * <p><b>It has to be a container.</b> The {@code test} profile runs H2 with
 * {@code ddl-auto: create-drop} and Flyway disabled, which generates the schema from the entities
 * and so cannot disagree with them — a migration that forgot a unique key would pass. It also
 * carries no seed rows, and the expenditure taxonomy V132 seeds is part of the contract. Follows
 * {@code RevenueAggregatePersistenceTest}, which established this setup for the same reason.
 *
 * <p>Loading the JPA context is again half the value: Spring Data validates every derived finder
 * on eighteen new repositories against the entity model at startup, so a name that does not match
 * a column fails here rather than in whoever calls it first.
 *
 * <p>What is deliberately <b>not</b> tested: report output, load behaviour, alert evaluation. None
 * of it exists yet, and asserting it now would be asserting the plan rather than the code.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class FinancePhase0SchemaTest {

    /** Outside any seeded range, so the migrations own data is never disturbed. */
    private static final Long TEMPLE_ID = 950001L;
    private static final Long OTHER_TEMPLE_ID = 950002L;
    private static final String FY = "2025-26";
    private static final LocalDate APRIL_10 = LocalDate.of(2025, 4, 10);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_phase0")
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

    @Autowired private PlatformTransactionManager txManager;

    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinTempleCapabilityRepository capabilities;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private FinServiceDimRepository services;
    @Autowired private FinExpenseCategoryRepository expenseCategories;
    @Autowired private FinExpenseFactRepository expenseFacts;
    @Autowired private FinDcFundRepository funds;
    @Autowired private FinFundUtilisationRepository utilisations;
    @Autowired private FinPreciousItemFactRepository preciousItems;
    @Autowired private FinNirantaraSubscriptionRepository subscriptions;
    @Autowired private FinStgExpenseRepository stagedExpenses;
    @Autowired private FinDataExpectationRepository expectations;
    @Autowired private FinDailyDataStatusRepository dailyStatuses;
    @Autowired private FinDataAlertRepository alerts;
    @Autowired private FinUploadFileRepository uploads;

    private Long connectorSourceId;
    private Long manualSourceId;
    private Long batchId;
    private Long salariesCategoryId;

    /**
     * No transaction wraps these tests, so each one commits. Everything this class writes is
     * removed first rather than rolled back; the migrations own rows are left alone, which is why
     * the temple ids sit outside any seeded range.
     */
    @BeforeEach
    void reset() {
        alerts.deleteAllInBatch();
        dailyStatuses.deleteAllInBatch();
        expectations.deleteAllInBatch();
        uploads.deleteAllInBatch();
        stagedExpenses.deleteAllInBatch();
        expenseFacts.deleteAllInBatch();
        utilisations.deleteAllInBatch();
        funds.deleteAllInBatch();
        preciousItems.deleteAllInBatch();
        subscriptions.deleteAllInBatch();
        services.deleteAllInBatch();
        batches.deleteAllInBatch();
        capabilities.deleteAllInBatch();
        sourceSystems.deleteAllInBatch();

        connectorSourceId = sourceSystems.save(FinSourceSystem.builder()
                .templeId(TEMPLE_ID)
                .systemCode("PHASE0_CONNECTOR")
                .systemName("A temple operational system")
                .sourceTechnology(SourceTechnology.SQL_SERVER)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean("someFinanceConnector")
                .build()).getId();

        manualSourceId = sourceSystems.save(FinSourceSystem.builder()
                .templeId(TEMPLE_ID)
                .systemCode("PHASE0_TEMPLE_INPUT")
                .systemName("Temple management input")
                .sourceTechnology(SourceTechnology.MANUAL)
                .connectorType(ConnectorType.MANUAL_ENTRY)
                .connectorBean(null)
                .build()).getId();

        batchId = batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(TEMPLE_ID)
                .sourceSystemId(manualSourceId)
                .capability(FinanceCapability.EXPENSE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.TEMPLE_INPUT)
                .actorUserId(4412L)
                .build()).getId();

        salariesCategoryId = expenseCategories.findByCategoryCodeAndDeletedFalse("SALARIES")
                .orElseThrow(() -> new AssertionError("V132 seed missing: SALARIES"))
                .getId();
    }

    // ── V130: manual entry is a source system ───────────────────────────────

    /**
     * A manual channel has no connector bean and never will. Until V130 the column was NOT NULL,
     * which would have forced a placeholder bean name the registry could later try to resolve.
     */
    @Test
    void should_persistManualSource_when_connectorBeanIsNull() {
        FinSourceSystem manual = sourceSystems.findById(manualSourceId).orElseThrow();

        assertThat(manual.getConnectorBean()).isNull();
        assertThat(manual.getConnectorType().isAutomated()).isFalse();
        assertThat(manual.isSyncEnabled())
                .as("registering any source, manual included, must never start traffic")
                .isFalse();
    }

    /** FR17 picks the temples to alert with this test, so it must not need a separate flag. */
    @Test
    void should_distinguishAutomatedFromManual_when_askingConnectorType() {
        assertThat(ConnectorType.PULL_JDBC.isAutomated()).isTrue();
        assertThat(ConnectorType.PUSH_AGENT.isAutomated()).isTrue();
        assertThat(ConnectorType.SOURCE_API.isAutomated()).isTrue();
        assertThat(ConnectorType.FILE_DROP.isAutomated()).isTrue();
        assertThat(ConnectorType.MANUAL_ENTRY.isAutomated()).isFalse();
        assertThat(ConnectorType.FILE_UPLOAD.isAutomated()).isFalse();
    }

    /**
     * The V130 key widening. FR7 sources precious-metal counts from the temple software and value
     * from staff input — two sources, one temple, one subject. Before V130 the second declaration
     * was rejected.
     */
    @Test
    void should_allowTwoSourcesForOneCapability_when_capabilityIsDeclaredPerSource() {
        capabilities.save(FinTempleCapability.builder()
                .templeId(TEMPLE_ID).sourceSystemId(connectorSourceId)
                .capability(FinanceCapability.PRECIOUS_METAL_COUNT)
                .availability(DataAvailability.AVAILABLE).build());

        capabilities.save(FinTempleCapability.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId)
                .capability(FinanceCapability.PRECIOUS_METAL_COUNT)
                .availability(DataAvailability.NOT_AVAILABLE)
                .availabilityReason("Counts come from the temple software, not from staff entry.")
                .build());

        assertThat(capabilities.findAll()).hasSize(2);
    }

    /** The same source declaring one capability twice is still a configuration error. */
    @Test
    void should_rejectDuplicate_when_oneSourceDeclaresACapabilityTwice() {
        capabilities.save(FinTempleCapability.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId)
                .capability(FinanceCapability.EXPENSE)
                .availability(DataAvailability.AVAILABLE).build());

        assertThatThrownBy(() -> capabilities.save(FinTempleCapability.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId)
                .capability(FinanceCapability.EXPENSE)
                .availability(DataAvailability.AVAILABLE).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The one column that carries "who entered this" into the provenance chain. */
    @Test
    void should_recordActor_when_batchWasTriggeredByAPerson() {
        FinSyncBatch batch = batches.findById(batchId).orElseThrow();

        assertThat(batch.getTriggeredBy()).isEqualTo(SyncTrigger.TEMPLE_INPUT);
        assertThat(batch.getActorUserId()).isEqualTo(4412L);
    }

    /** Null is the honest value for a scheduled run: nobody entered it. */
    @Test
    void should_leaveActorNull_when_batchWasScheduled() {
        FinSyncBatch scheduled = batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(TEMPLE_ID).sourceSystemId(connectorSourceId)
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.SCHEDULER)
                .build());

        assertThat(scheduled.getActorUserId()).isNull();
    }

    // ── V131: the FR8 special-seva flag ─────────────────────────────────────

    /** Nothing is special until somebody says so, and that is not the same as no sevas. */
    @Test
    void should_defaultToNotSpecial_when_aServiceIsCreated() {
        FinServiceDim service = services.save(aService("ABHISHEKA"));

        assertThat(service.isSpecial()).isFalse();
        assertThat(services.findByTempleIdAndSpecialTrueAndActiveTrueAndDeletedFalse(TEMPLE_ID))
                .isEmpty();
    }

    /** FR8 is a per-temple checkbox over this catalogue, not a second list of the same sevas. */
    @Test
    void should_findOnlyFlaggedSevas_when_someAreMarkedSpecial() {
        services.save(aService("ABHISHEKA"));
        FinServiceDim special = services.save(aService("SAHASRANAMA"));
        special.setSpecial(true);
        services.save(special);

        assertThat(services.findByTempleIdAndSpecialTrueAndActiveTrueAndDeletedFalse(TEMPLE_ID))
                .singleElement()
                .extracting(FinServiceDim::getServiceCode)
                .isEqualTo("SAHASRANAMA");
    }

    // ── V132: the record grain every new fact shares ────────────────────────

    /**
     * The grain both streams build against. A re-run restates the record rather than adding a
     * second one — the same idempotency guarantee {@code uk_frf_grain} gives revenue, at a
     * different grain and for the reason set out in the V132 header.
     */
    @Test
    void should_rejectDuplicate_when_theSameSourceRecordIsLoadedTwice() {
        expenseFacts.save(anExpense("STG:90118", APRIL_10, "1500.00"));

        assertThatThrownBy(() -> expenseFacts.save(anExpense("STG:90118", APRIL_10, "1500.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Two channels may legitimately use the same local reference; the grain is per source. */
    @Test
    void should_allowSameRecordRef_when_itComesFromADifferentSource() {
        expenseFacts.save(anExpense("R42", APRIL_10, "100.00"));
        expenseFacts.save(FinExpenseFact.builder()
                .templeId(TEMPLE_ID).sourceSystemId(connectorSourceId).syncBatchId(batchId)
                .sourceRecordRef("R42").expenseDate(APRIL_10).financialYear(FY)
                .categoryId(salariesCategoryId).amount(new BigDecimal("999.00"))
                .build());

        assertThat(expenseFacts.findByTempleIdAndFinancialYear(TEMPLE_ID, FY)).hasSize(2);
    }

    /**
     * Three vouchers in one category on one day stay three rows. Collapsing them to a daily grain,
     * as revenue does, would destroy exactly the detail the FR4 grid exists to show.
     */
    @Test
    void should_keepSeparateRows_when_severalExpensesShareADayAndCategory() {
        expenseFacts.save(anExpense("STG:1", APRIL_10, "100.00"));
        expenseFacts.save(anExpense("STG:2", APRIL_10, "200.00"));
        expenseFacts.save(anExpense("STG:3", APRIL_10, "300.00"));

        assertThat(expenseFacts.findByTempleIdAndFinancialYear(TEMPLE_ID, FY)).hasSize(3);
    }

    /**
     * The importer supersede protocol. A second workbook covering a loaded day carries different
     * row numbers, so the grain alone cannot stop the double count — and the delete must be scoped
     * to the source that wrote them, or one channel would erase another figures.
     */
    @Test
    void should_deleteOnlyItsOwnSourceRows_when_supersedingADateRange() {
        expenseFacts.save(anExpense("Expenses!R42", APRIL_10, "100.00"));
        expenseFacts.save(FinExpenseFact.builder()
                .templeId(TEMPLE_ID).sourceSystemId(connectorSourceId).syncBatchId(batchId)
                .sourceRecordRef("CONNECTOR:1").expenseDate(APRIL_10).financialYear(FY)
                .categoryId(salariesCategoryId).amount(new BigDecimal("999.00"))
                .build());

        // Run in one transaction, the way the importer runs it. The repository deliberately does
        // not open one of its own: the delete is only safe as the first half of a
        // delete-then-insert, and committing it alone would empty the day if the insert failed.
        int deleted = new TransactionTemplate(txManager).execute(status ->
                expenseFacts.deleteBySourceAndDateRange(TEMPLE_ID, manualSourceId,
                        LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30)));

        assertThat(deleted).isEqualTo(1);
        assertThat(expenseFacts.findByTempleIdAndFinancialYear(TEMPLE_ID, FY))
                .singleElement()
                .extracting(FinExpenseFact::getSourceSystemId)
                .isEqualTo(connectorSourceId);
    }

    /** ADR-007 at the column level: absent is not zero, and the schema has to permit absent. */
    @Test
    void should_persistNullAmount_when_theSourceDoesNotRecordOne() {
        FinExpenseFact saved = expenseFacts.save(FinExpenseFact.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId).syncBatchId(batchId)
                .sourceRecordRef("STG:no-amount").expenseDate(APRIL_10).financialYear(FY)
                .categoryId(salariesCategoryId).amount(null)
                .build());

        assertThat(saved.getAmount()).isNull();
        assertThat(saved.getPaymentMode())
                .as("an unstated payment mode is UNRECORDED, which is not CASH")
                .isEqualTo(PaymentMode.UNRECORDED);
    }

    /** Unmapped spend must have somewhere visible to land, as unmapped revenue does. */
    @Test
    void should_seedTheExpenditureTaxonomy_when_migrationsRun() {
        assertThat(expenseCategories.findByCategoryCodeAndDeletedFalse("UNMAPPED")).isPresent();
        assertThat(expenseCategories.findByActiveTrueAndDeletedFalseOrderByDisplayOrderAsc())
                .extracting(FinExpenseCategory::getCategoryCode)
                .startsWith("SALARIES")
                .endsWith("UNMAPPED");
    }

    // ── V133, V134: funds, and the two-channel precious-item subject ────────

    /** FR6 is FR5 filtered. If this finder works, no work-project table is needed. */
    @Test
    void should_findOngoingWorksOnly_when_filteredByStatus() {
        funds.save(aFund("SL/2025/01", WorkStatus.IN_PROGRESS));
        funds.save(aFund("SL/2025/02", WorkStatus.COMPLETED));

        assertThat(funds.findByTempleIdAndWorkStatus(TEMPLE_ID, WorkStatus.IN_PROGRESS))
                .singleElement()
                .extracting(FinDcFund::getSanctionLetterRef)
                .isEqualTo("SL/2025/01");
    }

    /** A fund whose sanction letter is illegible is not a fund of zero rupees (ADR-007). */
    @Test
    void should_persistNullApprovedAmount_when_theLetterDoesNotStateOne() {
        FinDcFund fund = aFund("SL/2025/03", WorkStatus.NOT_STARTED);
        fund.setApprovedAmount(null);

        assertThat(funds.save(fund).getApprovedAmount()).isNull();
    }

    /**
     * FR7 in one test. Two channels write two rows for the same day and metal — counts and weight
     * from the source, purity and value from staff — and the report assembles them. Neither row
     * updates the other, which is why value can be absent while counts are present.
     */
    @Test
    void should_holdBothChannels_when_oneSubjectIsSourcedTwice() {
        preciousItems.save(FinPreciousItemFact.builder()
                .templeId(TEMPLE_ID).sourceSystemId(connectorSourceId).syncBatchId(batchId)
                .sourceRecordRef("CONNECTOR:gold-1").receivedDate(APRIL_10).financialYear(FY)
                .metalType(MetalType.GOLD)
                .itemCount(3).grossWeightG(new BigDecimal("48.500"))
                .build());

        preciousItems.save(FinPreciousItemFact.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId).syncBatchId(batchId)
                .sourceRecordRef("STG:valuation-1").receivedDate(APRIL_10).financialYear(FY)
                .metalType(MetalType.GOLD)
                .purityKarat(new BigDecimal("22.000"))
                .estimatedValue(new BigDecimal("410000.00"))
                .valuationSource(ValuationSource.DECLARED).valuedOn(LocalDate.of(2025, 4, 12))
                .build());

        var rows = preciousItems.findByTempleIdAndReceivedDateAndMetalType(
                TEMPLE_ID, APRIL_10, MetalType.GOLD);

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getItemCount()).isEqualTo(3);
            assertThat(row.getEstimatedValue()).as("the source does not value items").isNull();
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getEstimatedValue()).isNotNull();
            assertThat(row.getItemCount()).as("a valuation does not recount items").isNull();
        });
    }

    // ── V136, V137, V138: staging, freshness, uploads ───────────────────────

    /** The same record twice in one batch is impossible, whichever lane staged it. */
    @Test
    void should_rejectDuplicate_when_theSameRecordIsStagedTwiceInOneBatch() {
        stagedExpenses.save(aStagedExpense("Expenses!R42"));

        assertThatThrownBy(() -> stagedExpenses.save(aStagedExpense("Expenses!R42")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A staged row starts untrusted. Nothing may read it as data until validation says so. */
    @Test
    void should_defaultToReceived_when_aRowIsStaged() {
        FinStgExpense staged = stagedExpenses.save(aStagedExpense("Expenses!R1"));

        assertThat(staged.getValidationStatus()).isEqualTo(StagingStatus.RECEIVED);
        assertThat(stagedExpenses.countBySyncBatchIdAndValidationStatus(
                batchId, StagingStatus.RECEIVED)).isEqualTo(1);
    }

    /**
     * The cutoff is a local wall clock, not an instant. Every existing scheduler in this codebase
     * runs in UTC, and a 22:00 UTC deadline is 03:30 the next morning in Asia/Kolkata.
     */
    @Test
    void should_defaultTheCutoffToTenPmLocal_when_anExpectationIsCreated() {
        FinDataExpectation expectation = expectations.save(anExpectation());

        assertThat(expectation.getCutoffLocalTime()).hasToString("22:00");
        assertThat(expectation.getCadence()).isEqualTo("DAILY");
        assertThat(expectations.findActiveOn(APRIL_10)).hasSize(1);
    }

    /** An expectation that has ended is not in force, so its days are not owed. */
    @Test
    void should_excludeEndedExpectations_when_askingWhatIsInForce() {
        FinDataExpectation ended = anExpectation();
        ended.setActiveTo(LocalDate.of(2025, 3, 31));
        expectations.save(ended);

        assertThat(expectations.findActiveOn(APRIL_10)).isEmpty();
    }

    /**
     * A deliberate zero must be recordable, or a temple that complied gets alerted. This is the
     * absent-is-not-zero rule reaching the freshness model.
     */
    @Test
    void should_treatANilReturnAsFulfilled_when_theTempleHadNothingToReport() {
        assertThat(DataSubmissionStatus.NIL_RETURN.isFulfilled()).isTrue();
        assertThat(DataSubmissionStatus.NIL_RETURN.isOutstanding()).isFalse();
        assertThat(DataSubmissionStatus.WAIVED.isFulfilled()).isTrue();
        assertThat(DataSubmissionStatus.MISSED.isOutstanding()).isTrue();
        assertThat(DataSubmissionStatus.EXPECTED.isFulfilled())
                .as("a day nobody has accounted for yet is not fulfilled")
                .isFalse();
    }

    /** One day of one capability is accounted for exactly once. */
    @Test
    void should_rejectDuplicate_when_theSameDayIsAccountedTwice() {
        Long expectationId = expectations.save(anExpectation()).getId();
        dailyStatuses.save(aDay(expectationId, APRIL_10, DataSubmissionStatus.MISSED));

        assertThatThrownBy(() -> dailyStatuses.save(
                aDay(expectationId, APRIL_10, DataSubmissionStatus.SUBMITTED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The gap query that both severity and FR20 clearing are computed from. */
    @Test
    void should_returnOnlyMissedDaysInOrder_when_askingForTheGap() {
        Long expectationId = expectations.save(anExpectation()).getId();
        dailyStatuses.save(aDay(expectationId, LocalDate.of(2025, 4, 3), DataSubmissionStatus.MISSED));
        dailyStatuses.save(aDay(expectationId, LocalDate.of(2025, 4, 1), DataSubmissionStatus.MISSED));
        dailyStatuses.save(aDay(expectationId, LocalDate.of(2025, 4, 2), DataSubmissionStatus.SUBMITTED));
        dailyStatuses.save(aDay(expectationId, LocalDate.of(2025, 4, 4), DataSubmissionStatus.NIL_RETURN));

        assertThat(dailyStatuses.findGap(TEMPLE_ID, FinanceCapability.EXPENSE))
                .extracting(FinDailyDataStatus::getBusinessDate)
                .containsExactly(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 3));
    }

    /** FR18, in the one place the rule is written. Day 8 is the first HIGH day. */
    @Test
    void should_escalateOnDayEight_when_severityIsDerivedFromTheGap() {
        assertThat(AlertSeverity.forConsecutiveMissedDays(1)).isEqualTo(AlertSeverity.LOW);
        assertThat(AlertSeverity.forConsecutiveMissedDays(7)).isEqualTo(AlertSeverity.LOW);
        assertThat(AlertSeverity.forConsecutiveMissedDays(8)).isEqualTo(AlertSeverity.HIGH);
        assertThat(AlertSeverity.forConsecutiveMissedDays(40)).isEqualTo(AlertSeverity.HIGH);
    }

    /**
     * Severity is recomputed, never incremented, which is what lets a partial backfill
     * de-escalate a HIGH alert instead of leaving it stuck. If this ever stops being possible,
     * FR18 and FR20 contradict each other.
     */
    @Test
    void should_deEscalate_when_theGapShrinksAfterAPartialBackfill() {
        Long expectationId = expectations.save(anExpectation()).getId();
        FinDataAlert alert = alerts.save(FinDataAlert.builder()
                .templeId(TEMPLE_ID).expectationId(expectationId)
                .capability(FinanceCapability.EXPENSE)
                .firstMissedDate(LocalDate.of(2025, 4, 1))
                .lastMissedDate(LocalDate.of(2025, 4, 12))
                .consecutiveMissedDays(12)
                .severity(AlertSeverity.forConsecutiveMissedDays(12))
                .openedAt(LocalDateTime.now()).lastEvaluatedAt(LocalDateTime.now())
                .build());
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);

        alert.setConsecutiveMissedDays(2);
        alert.setSeverity(AlertSeverity.forConsecutiveMissedDays(2));
        alert.setFirstMissedDate(LocalDate.of(2025, 4, 11));
        alerts.save(alert);

        assertThat(alerts.findByTempleIdAndCapabilityAndStatus(
                TEMPLE_ID, FinanceCapability.EXPENSE, AlertStatus.OPEN))
                .get()
                .extracting(FinDataAlert::getSeverity)
                .isEqualTo(AlertSeverity.LOW);
    }

    /** The duplicate-upload guard, and the only one that can name the earlier file. */
    @Test
    void should_rejectDuplicate_when_anIdenticalWorkbookIsUploadedTwice() {
        uploads.save(anUpload(TEMPLE_ID, "abc123"));

        assertThatThrownBy(() -> uploads.save(anUpload(TEMPLE_ID, "abc123")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Two temples filing the same figures is a coincidence, not a duplicate. */
    @Test
    void should_allowTheSameHash_when_uploadedByADifferentTemple() {
        uploads.save(anUpload(TEMPLE_ID, "abc123"));
        uploads.save(anUpload(OTHER_TEMPLE_ID, "abc123"));

        assertThat(uploads.findAll()).hasSize(2);
    }

    /** An upload is waiting on its uploader, not on the pipeline. It starts owing a decision. */
    @Test
    void should_startAtReceived_when_aWorkbookIsStored() {
        FinUploadFile upload = uploads.save(anUpload(TEMPLE_ID, "def456"));

        assertThat(upload.getStatus()).isEqualTo(UploadStatus.RECEIVED);
        assertThat(upload.getCommittedAt()).isNull();
        assertThat(upload.getUploadedBy()).isEqualTo(4412L);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private FinServiceDim aService(String code) {
        return FinServiceDim.builder()
                .templeId(TEMPLE_ID).serviceCode(code).serviceNameEn(code)
                .categoryId(1L)
                .build();
    }

    private FinExpenseFact anExpense(String ref, LocalDate on, String amount) {
        return FinExpenseFact.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId).syncBatchId(batchId)
                .sourceRecordRef(ref).expenseDate(on).financialYear(FY)
                .categoryId(salariesCategoryId).amount(new BigDecimal(amount))
                .build();
    }

    private FinDcFund aFund(String sanctionRef, WorkStatus status) {
        return FinDcFund.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId).syncBatchId(batchId)
                .sourceRecordRef("STG:fund:" + sanctionRef)
                .fundName("Prakara renovation").sanctionLetterRef(sanctionRef)
                .approvalDate(LocalDate.of(2025, 4, 1))
                .approvedAmount(new BigDecimal("2500000.00"))
                .workStatus(status).financialYear(FY)
                .build();
    }

    private FinStgExpense aStagedExpense(String ref) {
        return FinStgExpense.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId).syncBatchId(batchId)
                .sourceRecordRef(ref).rawJson("{\"amount\": \"1500.00\"}")
                .extractedAt(LocalDateTime.now())
                .build();
    }

    private FinDataExpectation anExpectation() {
        return FinDataExpectation.builder()
                .templeId(TEMPLE_ID).sourceSystemId(manualSourceId)
                .capability(FinanceCapability.EXPENSE)
                .activeFrom(LocalDate.of(2025, 4, 1))
                .build();
    }

    private FinDailyDataStatus aDay(Long expectationId, LocalDate on, DataSubmissionStatus status) {
        return FinDailyDataStatus.builder()
                .templeId(TEMPLE_ID).expectationId(expectationId)
                .capability(FinanceCapability.EXPENSE).businessDate(on).status(status)
                .build();
    }

    private FinUploadFile anUpload(Long templeId, String sha) {
        return FinUploadFile.builder()
                .templeId(templeId).sourceSystemId(manualSourceId)
                .capability(FinanceCapability.EXPENSE)
                .originalFilename("april-expenses.xlsx").contentSha256(sha)
                .sizeBytes(10_240L).storageRef("local://uploads/april-expenses.xlsx")
                .uploadedBy(4412L).uploadedAt(LocalDateTime.now())
                .build();
    }
}

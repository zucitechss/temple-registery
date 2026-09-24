package com.templeregistry.service.impl.finance;

import com.templeregistry.dto.response.finance.CategoryBreakdownResponse;
import com.templeregistry.dto.response.finance.DataFreshnessBlock;
import com.templeregistry.dto.response.finance.FinanceCapabilityResponse;
import com.templeregistry.dto.response.finance.FinanceSummaryResponse;
import com.templeregistry.dto.response.finance.MonthlyRevenueResponse;
import com.templeregistry.dto.response.finance.ReconciliationResponse;
import com.templeregistry.dto.response.finance.RevenueTrendResponse;
import com.templeregistry.entity.finance.FinAggRevenuePeriod;
import com.templeregistry.entity.finance.FinReconciliationResult;
import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinTempleCapability;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationCheckType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.InvalidFinancialYearException;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.finance.reporting.FinanceReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-081, FIN-082, FIN-083 against real MySQL 8.0 — the capability gate, the reporting-layer
 * rollup wired to real aggregate rows, and the authorization rules FIN-070A's isolation matrix
 * requires (FIN-D-071).
 *
 * <p>A full {@code @SpringBootTest} rather than {@code @DataJpaTest}, for the same reason
 * {@code FinanceApiTestBase} is: {@code @PreAuthorize} on a service that is never proxied is an
 * annotation, not a control.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class FinanceReportServiceImplTest {

    private static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("temple_registry_fin_report")
                .withUsername("test")
                .withPassword("test");
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
        registry.add("app.jwt.private-key-path", () -> "classpath:keys/jwt-private.pem");
        registry.add("app.jwt.public-key-path", () -> "classpath:keys/jwt-public.pem");
        registry.add("cloud.aws.s3.bucket-name", () -> "test-bucket");
        registry.add("cloud.aws.region.static", () -> "ap-south-1");
        registry.add("app.encryption.aes-key", () -> "12345678901234567890123456789012");
        registry.add("spring.mail.enabled", () -> "false");
    }

    private static final long DISTRICT_A = 9_400_001L;
    private static final long DISTRICT_B = 9_400_002L;
    private static final String YEAR = "2025-26";

    @Autowired private FinanceReportService reportService;
    @Autowired private TempleRepository temples;
    @Autowired private FinSourceSystemRepository sourceSystems;
    @Autowired private FinTempleCapabilityRepository capabilities;
    @Autowired private FinRevenueCategoryRepository categories;
    @Autowired private FinAggRevenuePeriodRepository aggregates;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private FinReconciliationResultRepository reconciliationResults;

    private Temple temple;
    private FinSourceSystem source;
    private FinRevenueCategory seva;

    @BeforeEach
    void setUp() {
        temple = temples.saveAndFlush(Temple.builder()
                .registrationNumber("FIN081-" + UUID.randomUUID())
                .name("Reporting test temple")
                .primaryDeity("Test")
                .districtId(DISTRICT_A)
                .build());
        source = sourceSystems.saveAndFlush(FinSourceSystem.builder()
                .templeId(temple.getId())
                .systemCode("REPORTSRC")
                .systemName("Reporting test source")
                .sourceTechnology(SourceTechnology.SQL_SERVER)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean("syntheticFinanceConnector")
                .build());
        // fin_revenue_category is platform-wide (its own repository javadoc: "Deliberately not
        // temple-scoped"), so category_code carries a global unique constraint -- each test needs
        // its own code rather than colliding on a literal "SEVA" across every @BeforeEach.
        seva = categories.saveAndFlush(FinRevenueCategory.builder()
                .categoryCode("SEVA-" + UUID.randomUUID())
                .categoryName("Seva")
                .build());
        authenticateAs("SUPER_ADMIN", 1L, null, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- FIN-082 capabilities

    @Nested
    @DisplayName("Capabilities (FIN-082)")
    class Capabilities {

        @Test
        @DisplayName("returns every declared capability with its reason")
        void should_returnDeclaredCapabilities() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);
            declareCapability(FinanceCapability.EXPENSE, DataAvailability.NOT_AVAILABLE,
                    "The source system does not record expenditure.");

            FinanceCapabilityResponse.ForTemple response =
                    reportService.getCapabilities(temple.getId(), currentClaims());

            assertThat(response.capabilities()).hasSize(2);
            assertThat(response.capabilities())
                    .filteredOn(c -> c.capability() == FinanceCapability.EXPENSE)
                    .extracting(FinanceCapabilityResponse::reason)
                    .containsExactly("The source system does not record expenditure.");
        }

        @Test
        @DisplayName("a temple with no source system has no capabilities, not a 404")
        void should_returnEmptyList_when_noCapabilitiesDeclared() {
            FinanceCapabilityResponse.ForTemple response =
                    reportService.getCapabilities(temple.getId(), currentClaims());

            assertThat(response.capabilities()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- FIN-081 summary

    @Nested
    @DisplayName("Summary (FIN-081)")
    class Summary {

        @Test
        @DisplayName("NOT_AVAILABLE with the declared reason when the capability itself is unavailable")
        void should_reportNotAvailable_when_capabilityIsUnavailable() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.NOT_AVAILABLE,
                    "The source has not been onboarded yet.");

            FinanceSummaryResponse response = reportService.getSummary(temple.getId(), YEAR, currentClaims());

            assertThat(response.grossRevenue().availability()).isEqualTo(DataAvailability.NOT_AVAILABLE);
            assertThat(response.grossRevenue().reason()).isEqualTo("The source has not been onboarded yet.");
            assertThat(response.grossRevenue().value()).isNull();
        }

        @Test
        @DisplayName("NOT_AVAILABLE when the capability is available but nothing has been aggregated yet")
        void should_reportNotAvailable_when_noAggregateRowsExist() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);

            FinanceSummaryResponse response = reportService.getSummary(temple.getId(), YEAR, currentClaims());

            assertThat(response.grossRevenue().availability()).isEqualTo(DataAvailability.NOT_AVAILABLE);
            assertThat(response.grossRevenue().reason()).contains(YEAR);
        }

        @Test
        @DisplayName("AVAILABLE and summed correctly once aggregate rows exist")
        void should_reportAvailable_when_aggregatesArePublished() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);
            saveAggregateRow(PeriodType.FINANCIAL_YEAR, YEAR, "600.00", 4L, ReconciliationStatus.PASSED, 0);

            FinanceSummaryResponse response = reportService.getSummary(temple.getId(), YEAR, currentClaims());

            assertThat(response.grossRevenue().availability()).isEqualTo(DataAvailability.AVAILABLE);
            assertThat(response.grossRevenue().value()).isEqualByComparingTo("600.00");
            assertThat(response.transactionCount().value()).isEqualByComparingTo("4");
            assertThat(response.expenditure().availability()).isEqualTo(DataAvailability.NOT_AVAILABLE);
        }

        @Test
        @DisplayName("PARTIALLY_AVAILABLE with a floor reason when some facts had no recorded gross")
        void should_reportPartiallyAvailable_when_someFactsHaveUnknownGross() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);
            saveAggregateRow(PeriodType.FINANCIAL_YEAR, YEAR, "600.00", 4L, ReconciliationStatus.PASSED, 2);

            FinanceSummaryResponse response = reportService.getSummary(temple.getId(), YEAR, currentClaims());

            assertThat(response.grossRevenue().availability())
                    .isEqualTo(DataAvailability.PARTIALLY_AVAILABLE);
            assertThat(response.grossRevenue().value()).isEqualByComparingTo("600.00");
            assertThat(response.grossRevenue().reason()).contains("2");
        }

        @Test
        @DisplayName("reconciliation NOT_AVAILABLE surfaces on the envelope even when the figure is known")
        void should_surfaceReconciliationStatus_when_notPassed() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);
            saveAggregateRow(PeriodType.FINANCIAL_YEAR, YEAR, "600.00", 4L,
                    ReconciliationStatus.NOT_AVAILABLE, 0);

            FinanceSummaryResponse response = reportService.getSummary(temple.getId(), YEAR, currentClaims());

            assertThat(response.grossRevenue().reconciliation()).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
        }

        @Test
        @DisplayName("refuses a financial year that is not canonical yyyy-yy form")
        void should_refuse_when_financialYearIsMalformed() {
            assertThatThrownBy(() -> reportService.getSummary(temple.getId(), "2025", currentClaims()))
                    .isInstanceOf(InvalidFinancialYearException.class);
        }

        @Test
        @DisplayName("refuses a temple that does not exist")
        void should_refuse_when_templeDoesNotExist() {
            assertThatThrownBy(() -> reportService.getSummary(999_999_999L, YEAR, currentClaims()))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // ---------------------------------------------------------------- district scope (FIN-084)

    @Nested
    @DisplayName("Authorization and district scope (FIN-070A isolation matrix)")
    class Authorization {

        @Test
        @DisplayName("a DC in the same district may read")
        void should_allow_when_dcMatchesDistrict() {
            authenticateAs("DISTRICT_COLLECTOR", 2L, DISTRICT_A, null);

            FinanceCapabilityResponse.ForTemple response =
                    reportService.getCapabilities(temple.getId(), currentClaims());

            assertThat(response.templeId()).isEqualTo(temple.getId());
        }

        @Test
        @DisplayName("a DC in a different district is refused")
        void should_refuse_when_dcIsInAnotherDistrict() {
            authenticateAs("DISTRICT_COLLECTOR", 2L, DISTRICT_B, null);

            assertThatThrownBy(() -> reportService.getCapabilities(temple.getId(), currentClaims()))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("an AUDITOR with a null districtId reads statewide, never refused by the district guard")
        void should_allow_when_auditorHasNullDistrict() {
            authenticateAs("AUDITOR", 3L, null, null);

            FinanceCapabilityResponse.ForTemple response =
                    reportService.getCapabilities(temple.getId(), currentClaims());

            assertThat(response.templeId()).isEqualTo(temple.getId());
        }

        @Test
        @DisplayName("SUPER_ADMIN reads statewide regardless of district")
        void should_allow_when_superAdmin() {
            authenticateAs("SUPER_ADMIN", 4L, DISTRICT_B, null);

            FinanceCapabilityResponse.ForTemple response =
                    reportService.getCapabilities(temple.getId(), currentClaims());

            assertThat(response.templeId()).isEqualTo(temple.getId());
        }
    }

    // ---------------------------------------------------------------- trend / monthly / categories

    @Nested
    @DisplayName("Trend, monthly and category breakdown (FIN-081)")
    class Breakdowns {

        @BeforeEach
        void publishAggregates() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);
            saveAggregateRow(PeriodType.FINANCIAL_YEAR, "2024-25", "300.00", 2L, ReconciliationStatus.PASSED, 0);
            saveAggregateRow(PeriodType.FINANCIAL_YEAR, YEAR, "600.00", 4L, ReconciliationStatus.PASSED, 0);
            saveAggregateRow(PeriodType.MONTH, "2025-04", YEAR, "100.00", 1L, ReconciliationStatus.PASSED, 0);
            saveAggregateRow(PeriodType.MONTH, "2025-05", YEAR, "500.00", 3L, ReconciliationStatus.PASSED, 0);
        }

        @Test
        @DisplayName("trend returns one point per financial year, oldest first")
        void should_returnOnePointPerYear() {
            RevenueTrendResponse response = reportService.getRevenueTrend(temple.getId(), currentClaims());

            assertThat(response.years()).extracting(RevenueTrendResponse.YearPoint::financialYear)
                    .containsExactly("2024-25", YEAR);
            assertThat(response.years().get(1).grossRevenue().value()).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("monthly returns one point per month within the requested year only")
        void should_returnOnePointPerMonth() {
            MonthlyRevenueResponse response =
                    reportService.getMonthlyRevenue(temple.getId(), YEAR, currentClaims());

            assertThat(response.months()).extracting(MonthlyRevenueResponse.MonthPoint::month)
                    .containsExactly("2025-04", "2025-05");
        }

        @Test
        @DisplayName("categories returns the platform's own taxonomy, never a source label")
        void should_returnCanonicalCategoryNames() {
            CategoryBreakdownResponse response =
                    reportService.getCategoryBreakdown(temple.getId(), YEAR, currentClaims());

            assertThat(response.categories()).extracting(CategoryBreakdownResponse.CategoryPoint::categoryCode)
                    .containsExactly(seva.getCategoryCode());
            assertThat(response.categories().get(0).grossRevenue().value()).isEqualByComparingTo("600.00");
        }
    }

    // ---------------------------------------------------------------- reconciliation (FIN-083)

    @Nested
    @DisplayName("Reconciliation (FIN-083)")
    class Reconciliation {

        @Test
        @DisplayName("returns every recorded check for the temple's financial year")
        void should_returnRecordedChecks() {
            reconciliationResults.saveAndFlush(FinReconciliationResult.builder()
                    .templeId(temple.getId())
                    .sourceSystemId(source.getId())
                    .capability(FinanceCapability.REVENUE)
                    .checkType(ReconciliationCheckType.STAGE_COMPLETENESS)
                    .metric("GROSS_AMOUNT")
                    .periodType(PeriodType.FINANCIAL_YEAR)
                    .periodKey(YEAR)
                    .status(ReconciliationStatus.PASSED)
                    .checkedAt(LocalDateTime.now())
                    .build());

            ReconciliationResponse response =
                    reportService.getReconciliation(temple.getId(), YEAR, currentClaims());

            assertThat(response.checks()).hasSize(1);
            assertThat(response.checks().get(0).status()).isEqualTo(ReconciliationStatus.PASSED);
        }

        @Test
        @DisplayName("an empty result set is reported as no checks, not an error")
        void should_returnEmptyList_when_noChecksRecorded() {
            ReconciliationResponse response =
                    reportService.getReconciliation(temple.getId(), YEAR, currentClaims());

            assertThat(response.checks()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- data freshness

    @Nested
    @DisplayName("Data freshness")
    class Freshness {

        @Test
        @DisplayName("STALE with a reason when the temple has never synced")
        void should_reportStale_when_neverSynced() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);

            FinanceSummaryResponse response = reportService.getSummary(temple.getId(), YEAR, currentClaims());

            assertThat(response.dataFreshness().status()).isEqualTo(DataFreshnessBlock.Status.STALE);
            assertThat(response.dataFreshness().lastSyncedAt()).isNull();
        }

        @Test
        @DisplayName("FRESH when the last successful batch is inside the staleness window")
        void should_reportFresh_when_recentlySynced() {
            declareCapability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null);
            batches.saveAndFlush(FinSyncBatch.builder()
                    .batchRef(UUID.randomUUID().toString())
                    .templeId(temple.getId())
                    .sourceSystemId(source.getId())
                    .capability(FinanceCapability.REVENUE)
                    .syncType(SyncType.INCREMENTAL)
                    .status(SyncStatus.SUCCESS)
                    .triggeredBy(SyncTrigger.MANUAL)
                    .finishedAt(LocalDateTime.now().minusHours(1))
                    .build());

            FinanceSummaryResponse response = reportService.getSummary(temple.getId(), YEAR, currentClaims());

            assertThat(response.dataFreshness().status()).isEqualTo(DataFreshnessBlock.Status.FRESH);
            assertThat(response.dataFreshness().lastSyncedAt()).isNotNull();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void declareCapability(FinanceCapability capability, DataAvailability availability, String reason) {
        capabilities.saveAndFlush(FinTempleCapability.builder()
                .templeId(temple.getId())
                .sourceSystemId(source.getId())
                .capability(capability)
                .availability(availability)
                .availabilityReason(reason)
                .build());
    }

    private void saveAggregateRow(PeriodType periodType, String periodKey, String gross,
                                  long transactionCount, ReconciliationStatus status, int unknownGrossFacts) {
        saveAggregateRow(periodType, periodKey, periodKey, gross, transactionCount, status, unknownGrossFacts);
    }

    private void saveAggregateRow(PeriodType periodType, String periodKey, String financialYear,
                                  String gross, long transactionCount, ReconciliationStatus status,
                                  int unknownGrossFacts) {
        aggregates.saveAndFlush(FinAggRevenuePeriod.builder()
                .templeId(temple.getId())
                .sourceSystemId(source.getId())
                .periodType(periodType)
                .periodKey(periodKey)
                .categoryId(seva.getId())
                .paymentMode(PaymentMode.CASH)
                .financialYear(financialYear)
                .periodStart(LocalDate.of(2025, 4, 1))
                .periodEnd(LocalDate.of(2026, 3, 31))
                .factCount(1)
                .transactionCount(transactionCount)
                .factsWithUnknownCount(0)
                .grossAmount(new java.math.BigDecimal(gross))
                .factsWithUnknownGross(unknownGrossFacts)
                .currency("INR")
                .paymentModeInferredFacts(0)
                .reconciliationStatus(status)
                .reconciliationInherited(periodType == PeriodType.MONTH)
                .calcVersion((short) 1)
                .computedAt(LocalDateTime.now())
                .build());
    }

    // saveAggregateRow(periodType, periodKey, gross, ...) overload passes periodKey as financialYear
    // too, which is correct for FINANCIAL_YEAR rows and irrelevant to MONTH-row tests that pass
    // financialYear explicitly.

    private void authenticateAs(String role, long userId, Long districtId, Long templeId) {
        ScopeHelper.Claims claims =
                new ScopeHelper.Claims(userId, role, districtId, templeId, role.toLowerCase(), "VIEW");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private ScopeHelper.Claims currentClaims() {
        return (ScopeHelper.Claims) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}

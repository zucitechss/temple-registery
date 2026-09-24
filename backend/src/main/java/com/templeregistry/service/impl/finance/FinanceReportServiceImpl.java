package com.templeregistry.service.impl.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.dto.response.finance.CategoryBreakdownResponse;
import com.templeregistry.dto.response.finance.DataFreshnessBlock;
import com.templeregistry.dto.response.finance.FinanceCapabilityResponse;
import com.templeregistry.dto.response.finance.FinanceSummaryResponse;
import com.templeregistry.dto.response.finance.MetricEnvelope;
import com.templeregistry.dto.response.finance.MonthlyRevenueResponse;
import com.templeregistry.dto.response.finance.ReconciliationResponse;
import com.templeregistry.dto.response.finance.RevenueTrendResponse;
import com.templeregistry.entity.finance.FinAggRevenuePeriod;
import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinTempleCapability;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.InvalidFinancialYearException;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.finance.pipeline.FinancialYear;
import com.templeregistry.service.finance.reporting.FinanceReportService;
import com.templeregistry.service.finance.reporting.RevenueMetricRollup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Backs {@code /api/v1/dc/temples/{templeId}/finance} (FIN-081, FIN-082, FIN-083).
 *
 * <h2>Never touches a source</h2>
 *
 * <p>Every read here is {@code fin_temple_capability}, {@code fin_agg_revenue_period},
 * {@code fin_reconciliation_result} or {@code fin_revenue_fact} — the canonical and aggregate
 * tables API_CONTRACT §1.5 names, and nothing else. No repository capable of reaching a source
 * system is injected.
 *
 * <h2>Authorization</h2>
 *
 * <p>Route-level: {@code CAN_READ_FINANCE_CONFIG}, not {@code IS_DC_ROLE}. API_CONTRACT §0 names
 * {@code IS_DC_ROLE}, but FIN-070A's own isolation test matrix requires an {@code AUDITOR} to read
 * statewide — the same reader {@code CAN_READ_FINANCE_CONFIG} already admits for mapping
 * configuration. Excluding financial figures an auditor may see the mapping rules for would be
 * the more surprising inconsistency; this resolves it in favour of the plan's explicit test over
 * the contract's one-line mention (FIN-D-071).
 *
 * <p>District scope: only {@code DISTRICT_COLLECTOR} and {@code DC_STAFF} are scoped, the same
 * set {@link JurisdictionGuard#assertSameDistrict} scopes and the exact pattern
 * {@code MappingAdminServiceImpl.outOfScope} already uses — calling
 * {@link JurisdictionGuard#assertDistrictScope} unconditionally would treat an {@code AUDITOR}'s
 * legitimately null {@code districtId} as a corrupted token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinanceReportServiceImpl implements FinanceReportService {

    private static final String NO_SOURCE_DATA = "No contributing record recorded this for the requested period.";

    private final TempleRepository temples;
    private final JurisdictionGuard jurisdictionGuard;
    private final FinTempleCapabilityRepository capabilities;
    private final FinAggRevenuePeriodRepository aggregates;
    private final FinReconciliationResultRepository reconciliationResults;
    private final FinRevenueCategoryRepository categories;
    private final FinRevenueFactRepository facts;
    private final FinSourceSystemRepository sourceSystems;
    private final FinSyncBatchRepository batches;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------- FIN-082

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public FinanceCapabilityResponse.ForTemple getCapabilities(Long templeId, ScopeHelper.Claims claims) {
        loadAuthorizedTemple(templeId, claims);
        List<FinanceCapabilityResponse> rows = capabilities.findByTempleIdAndDeletedFalse(templeId).stream()
                .sorted(Comparator.comparing(FinTempleCapability::getCapability))
                .map(this::toCapabilityResponse)
                .toList();
        return new FinanceCapabilityResponse.ForTemple(templeId, rows);
    }

    private FinanceCapabilityResponse toCapabilityResponse(FinTempleCapability c) {
        return new FinanceCapabilityResponse(c.getCapability(), c.getAvailability(),
                c.getAvailabilityReason(), c.getCoverageFrom(), c.getCoverageTo(),
                parseKnownGaps(c.getKnownGapsJson(), c.getTempleId(), c.getCapability()));
    }

    private List<String> parseKnownGaps(String json, Long templeId, FinanceCapability capability) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception malformed) {
            // Cosmetic metadata, not a reported figure -- a malformed cell here must not fail the
            // whole capability read the way a bad reconciliation result must never be swallowed.
            log.warn("Unreadable known_gaps_json for temple {} capability {}: {}",
                    templeId, capability, malformed.getMessage());
            return List.of();
        }
    }

    // ---------------------------------------------------------------- FIN-081

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public FinanceSummaryResponse getSummary(Long templeId, String financialYear, ScopeHelper.Claims claims) {
        Temple temple = loadAuthorizedTemple(templeId, claims);
        validateFinancialYear(financialYear);

        MetricEnvelope gross;
        MetricEnvelope net;
        MetricEnvelope transactionCount;

        if (revenueUnavailable(templeId)) {
            String reason = revenueUnavailableReason(templeId);
            gross = MetricEnvelope.notAvailable(reason);
            net = MetricEnvelope.notAvailable(reason);
            transactionCount = MetricEnvelope.notAvailable(reason);
        } else {
            List<FinAggRevenuePeriod> rows = aggregates.findByTempleIdAndPeriodTypeAndPeriodKeyOrderByIdAsc(
                    templeId, PeriodType.FINANCIAL_YEAR, financialYear);
            if (rows.isEmpty()) {
                String reason = "No aggregated revenue has been published for financial year "
                        + financialYear + ".";
                gross = MetricEnvelope.notAvailable(reason);
                net = MetricEnvelope.notAvailable(reason);
                transactionCount = MetricEnvelope.notAvailable(reason);
            } else {
                RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(rows);
                gross = grossEnvelope(rollup);
                net = netEnvelope(rollup);
                transactionCount = transactionCountEnvelope(rollup);
            }
        }

        return new FinanceSummaryResponse(templeId, financialYear, gross, net, transactionCount,
                expenditureEnvelope(templeId), freshness(temple));
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public RevenueTrendResponse getRevenueTrend(Long templeId, ScopeHelper.Claims claims) {
        Temple temple = loadAuthorizedTemple(templeId, claims);

        List<RevenueTrendResponse.YearPoint> years;
        if (revenueUnavailable(templeId)) {
            years = List.of();
        } else {
            List<FinAggRevenuePeriod> rows = aggregates.findByTempleIdAndPeriodTypeOrderByPeriodKeyAscIdAsc(
                    templeId, PeriodType.FINANCIAL_YEAR);
            years = groupByPeriodKey(rows).entrySet().stream()
                    .map(e -> new RevenueTrendResponse.YearPoint(
                            e.getKey(), grossEnvelope(RevenueMetricRollup.rollUp(e.getValue()))))
                    .toList();
        }
        return new RevenueTrendResponse(templeId, years, freshness(temple));
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public MonthlyRevenueResponse getMonthlyRevenue(Long templeId, String financialYear,
                                                    ScopeHelper.Claims claims) {
        Temple temple = loadAuthorizedTemple(templeId, claims);
        validateFinancialYear(financialYear);

        List<MonthlyRevenueResponse.MonthPoint> months;
        if (revenueUnavailable(templeId)) {
            months = List.of();
        } else {
            List<FinAggRevenuePeriod> rows = aggregates
                    .findByTempleIdAndPeriodTypeAndFinancialYearOrderByPeriodKeyAscIdAsc(
                            templeId, PeriodType.MONTH, financialYear);
            months = groupByPeriodKey(rows).entrySet().stream()
                    .map(e -> new MonthlyRevenueResponse.MonthPoint(
                            e.getKey(), grossEnvelope(RevenueMetricRollup.rollUp(e.getValue()))))
                    .toList();
        }
        return new MonthlyRevenueResponse(templeId, financialYear, months, freshness(temple));
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public CategoryBreakdownResponse getCategoryBreakdown(Long templeId, String financialYear,
                                                          ScopeHelper.Claims claims) {
        Temple temple = loadAuthorizedTemple(templeId, claims);
        validateFinancialYear(financialYear);

        List<CategoryBreakdownResponse.CategoryPoint> points;
        if (revenueUnavailable(templeId)) {
            points = List.of();
        } else {
            List<FinAggRevenuePeriod> rows = aggregates.findByTempleIdAndPeriodTypeAndPeriodKeyOrderByIdAsc(
                    templeId, PeriodType.FINANCIAL_YEAR, financialYear);
            Map<Long, List<FinAggRevenuePeriod>> byCategory = rows.stream()
                    .collect(Collectors.groupingBy(FinAggRevenuePeriod::getCategoryId));
            Map<Long, FinRevenueCategory> categoryById = categories.findAllById(byCategory.keySet()).stream()
                    .collect(Collectors.toMap(FinRevenueCategory::getId, c -> c));
            points = byCategory.entrySet().stream()
                    .map(e -> {
                        FinRevenueCategory category = categoryById.get(e.getKey());
                        String code = category != null ? category.getCategoryCode() : "UNKNOWN";
                        String name = category != null ? category.getCategoryName() : "Unknown category";
                        return new CategoryBreakdownResponse.CategoryPoint(
                                code, name, grossEnvelope(RevenueMetricRollup.rollUp(e.getValue())));
                    })
                    .sorted(Comparator.comparing(CategoryBreakdownResponse.CategoryPoint::categoryCode))
                    .toList();
        }
        return new CategoryBreakdownResponse(templeId, financialYear, points, freshness(temple));
    }

    // ---------------------------------------------------------------- FIN-083

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public ReconciliationResponse getReconciliation(Long templeId, String financialYear,
                                                    ScopeHelper.Claims claims) {
        loadAuthorizedTemple(templeId, claims);
        validateFinancialYear(financialYear);

        List<ReconciliationResponse.CheckResult> checks = reconciliationResults
                .findByTempleIdAndPeriodTypeAndPeriodKeyOrderBySourceSystemIdAscIdAsc(
                        templeId, PeriodType.FINANCIAL_YEAR, financialYear)
                .stream()
                .map(r -> new ReconciliationResponse.CheckResult(
                        r.getSourceSystemId(), r.getCheckType(), r.getMetric(),
                        r.getSourceTotal(), r.getCentralTotal(), r.getDifference(), r.getDifferencePct(),
                        r.getStatus(), r.getStatusReason(), r.getCheckedAt()))
                .toList();
        return new ReconciliationResponse(templeId, financialYear, checks);
    }

    // ---------------------------------------------------------------- authorization

    /**
     * Loads the temple or refuses, the same way and for the same reason as every other
     * finance-reporting call.
     *
     * <p>{@code SUPER_ADMIN} and {@code AUDITOR} read statewide. Only {@code DISTRICT_COLLECTOR}
     * and {@code DC_STAFF} are district-scoped, checked here rather than by calling
     * {@link JurisdictionGuard#assertDistrictScope} unconditionally, which treats a null
     * {@code districtId} on any other role as a corrupted token — exactly the defect an
     * {@code AUDITOR}'s legitimately null one would trip.
     */
    private Temple loadAuthorizedTemple(Long templeId, ScopeHelper.Claims claims) {
        Temple temple = temples.findWithFullGeoById(templeId)
                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
        if (RoleConstants.DISTRICT_COLLECTOR.equals(claims.role())
                || RoleConstants.DC_STAFF.equals(claims.role())) {
            jurisdictionGuard.assertDistrictScope(temple, claims);
        }
        return temple;
    }

    private void validateFinancialYear(String financialYear) {
        try {
            FinancialYear.startOf(financialYear);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidFinancialYearException(financialYear);
        }
    }

    // ---------------------------------------------------------------- capability gate

    private boolean revenueUnavailable(Long templeId) {
        return capabilities.findByTempleIdAndCapabilityAndDeletedFalse(templeId, FinanceCapability.REVENUE)
                .map(c -> c.getAvailability() == DataAvailability.NOT_AVAILABLE
                        || c.getAvailability() == DataAvailability.NOT_APPLICABLE)
                .orElse(true);
    }

    private String revenueUnavailableReason(Long templeId) {
        return capabilities.findByTempleIdAndCapabilityAndDeletedFalse(templeId, FinanceCapability.REVENUE)
                .map(FinTempleCapability::getAvailabilityReason)
                .filter(reason -> reason != null && !reason.isBlank())
                .orElse("No revenue capability has been declared for this temple.");
    }

    /**
     * Expenditure is {@code NOT_AVAILABLE} for every temple today, regardless of what
     * {@code fin_temple_capability} declares — no expense pipeline or aggregate table exists on
     * this platform yet (FIN-100+). The temple's own declared reason is preferred when one
     * exists, so the copy shown stays temple-specific rather than a platform-wide disclaimer.
     */
    private MetricEnvelope expenditureEnvelope(Long templeId) {
        String reason = capabilities.findByTempleIdAndCapabilityAndDeletedFalse(templeId, FinanceCapability.EXPENSE)
                .map(FinTempleCapability::getAvailabilityReason)
                .filter(r -> r != null && !r.isBlank())
                .orElse("No expense capability has been declared for this temple.");
        return MetricEnvelope.notAvailable(reason);
    }

    // ---------------------------------------------------------------- rollup -> envelope

    private MetricEnvelope grossEnvelope(RevenueMetricRollup.Rollup rollup) {
        if (rollup.grossAmount() == null) {
            return MetricEnvelope.notAvailable(NO_SOURCE_DATA);
        }
        if (!rollup.grossFullyKnown()) {
            return MetricEnvelope.partiallyAvailable(rollup.grossAmount(), rollup.currency(),
                    rollup.asOfDate(), rollup.reconciliationStatus(),
                    rollup.factsWithUnknownGross()
                            + " underlying record(s) did not record a gross amount; the figure "
                            + "shown is a floor, not a total.");
        }
        return MetricEnvelope.available(rollup.grossAmount(), rollup.currency(), rollup.asOfDate(),
                rollup.reconciliationStatus());
    }

    private MetricEnvelope netEnvelope(RevenueMetricRollup.Rollup rollup) {
        if (rollup.netAmount() == null) {
            return MetricEnvelope.notAvailable(
                    "Cancellations are not fully recorded for this period, so net revenue cannot be computed.");
        }
        if (!rollup.netFullyKnown()) {
            return MetricEnvelope.partiallyAvailable(rollup.netAmount(), rollup.currency(),
                    rollup.asOfDate(), rollup.reconciliationStatus(),
                    "Cancellations are not fully recorded for part of this period; the figure "
                            + "shown is a floor, not a total.");
        }
        return MetricEnvelope.available(rollup.netAmount(), rollup.currency(), rollup.asOfDate(),
                rollup.reconciliationStatus());
    }

    private MetricEnvelope transactionCountEnvelope(RevenueMetricRollup.Rollup rollup) {
        if (rollup.transactionCount() == null) {
            return MetricEnvelope.notAvailable(NO_SOURCE_DATA);
        }
        BigDecimal value = BigDecimal.valueOf(rollup.transactionCount());
        if (!rollup.transactionCountFullyKnown()) {
            return MetricEnvelope.partiallyAvailable(value, "RECEIPTS", rollup.asOfDate(),
                    rollup.reconciliationStatus(),
                    "Some of this period's underlying records did not record a transaction "
                            + "count; the figure shown is a floor, not a total.");
        }
        return MetricEnvelope.available(value, "RECEIPTS", rollup.asOfDate(), rollup.reconciliationStatus());
    }

    /** Groups rows by {@code periodKey}, preserving the caller's sort order (already chronological). */
    private static Map<String, List<FinAggRevenuePeriod>> groupByPeriodKey(List<FinAggRevenuePeriod> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                FinAggRevenuePeriod::getPeriodKey, LinkedHashMap::new, Collectors.toList()));
    }

    // ---------------------------------------------------------------- data freshness

    /**
     * API_CONTRACT §3. Two independent dates: when the platform last synced, and separately, how
     * current the source's own data is — never merged, because a temple synced successfully every
     * night can still have no record newer than several weeks ago.
     */
    private DataFreshnessBlock freshness(Temple temple) {
        Long templeId = temple.getId();
        List<FinSourceSystem> sources = sourceSystems.findByTempleIdAndDeletedFalse(templeId);

        LocalDateTime lastSyncedAt = null;
        int thresholdHours = Integer.MAX_VALUE;
        for (FinSourceSystem source : sources) {
            LocalDateTime candidate = latestFinishedAt(source.getId());
            lastSyncedAt = laterOf(lastSyncedAt, candidate);
            if (source.getStalenessThresholdHours() != null) {
                thresholdHours = Math.min(thresholdHours, source.getStalenessThresholdHours());
            }
        }
        if (thresholdHours == Integer.MAX_VALUE) {
            thresholdHours = 48;
        }

        LocalDate sourceDataThrough = facts.findMaxTransactionDateByTempleId(templeId).orElse(null);

        boolean stale = lastSyncedAt == null
                || lastSyncedAt.isBefore(LocalDateTime.now().minusHours(thresholdHours));

        String reason = null;
        if (stale) {
            reason = lastSyncedAt == null
                    ? "This temple has not completed a sync yet."
                    : sourceDataThrough != null
                        ? "The source system has no records after " + sourceDataThrough + "."
                        : "The last successful sync is older than the " + thresholdHours
                            + "-hour freshness window for this source.";
        }

        return new DataFreshnessBlock(lastSyncedAt, sourceDataThrough,
                stale ? DataFreshnessBlock.Status.STALE : DataFreshnessBlock.Status.FRESH, reason);
    }

    private LocalDateTime latestFinishedAt(Long sourceSystemId) {
        LocalDateTime latest = null;
        for (SyncStatus syncedStatus : List.of(SyncStatus.SUCCESS, SyncStatus.RECONCILE_FAILED)) {
            Optional<FinSyncBatch> batch = batches.findFirstBySourceSystemIdAndCapabilityAndStatusOrderByIdDesc(
                    sourceSystemId, FinanceCapability.REVENUE, syncedStatus);
            latest = laterOf(latest, batch.map(FinSyncBatch::getFinishedAt).orElse(null));
        }
        return latest;
    }

    private static LocalDateTime laterOf(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}

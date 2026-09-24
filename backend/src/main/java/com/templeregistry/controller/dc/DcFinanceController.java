package com.templeregistry.controller.dc;

import com.templeregistry.common.ApiResponse;
import com.templeregistry.dto.response.finance.CategoryBreakdownResponse;
import com.templeregistry.dto.response.finance.FinanceCapabilityResponse;
import com.templeregistry.dto.response.finance.FinanceSummaryResponse;
import com.templeregistry.dto.response.finance.MonthlyRevenueResponse;
import com.templeregistry.dto.response.finance.ReconciliationResponse;
import com.templeregistry.dto.response.finance.RevenueTrendResponse;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.finance.reporting.FinanceReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finance reporting for one temple (FIN-081, FIN-082, FIN-083; API_CONTRACT §4).
 *
 * <p>Every response reads {@code fin_temple_capability}, {@code fin_agg_revenue_period} or
 * {@code fin_reconciliation_result} — never a source, and never a JPA entity: see
 * {@link com.templeregistry.service.impl.finance.FinanceReportServiceImpl}'s class javadoc for
 * why the route guard here is {@code CAN_READ_FINANCE_CONFIG} rather than the {@code IS_DC_ROLE}
 * API_CONTRACT.md names (FIN-D-071).
 *
 * <p>Not yet implemented: {@code /revenue/sevas} (blocked on FIN-071, FIN-D-069),
 * {@code /cancellations} and {@code /sync-status} — scoped for a later pass, not silently
 * dropped from the contract.
 */
@RestController
@RequestMapping("/api/v1/dc/temples/{templeId}/finance")
@RequiredArgsConstructor
@Tag(name = "Finance Reporting", description = "Temple financial figures, published from reconciled aggregates")
@PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
public class DcFinanceController {

    private final FinanceReportService financeReportService;

    @GetMapping("/capabilities")
    @Operation(summary = "What this temple can answer, with reasons (FIN-082).")
    public ResponseEntity<ApiResponse<FinanceCapabilityResponse.ForTemple>> getCapabilities(
            @PathVariable Long templeId) {
        var response = financeReportService.getCapabilities(templeId, currentClaims());
        return ResponseEntity.ok(ApiResponse.success("Finance capabilities retrieved.", response));
    }

    @GetMapping("/summary")
    @Operation(summary = "Headline metrics for a financial year (FIN-081).")
    public ResponseEntity<ApiResponse<FinanceSummaryResponse>> getSummary(
            @PathVariable Long templeId,
            @RequestParam String financialYear) {
        var response = financeReportService.getSummary(templeId, financialYear, currentClaims());
        return ResponseEntity.ok(ApiResponse.success("Finance summary retrieved.", response));
    }

    @GetMapping("/revenue/trend")
    @Operation(summary = "Revenue by financial year (FIN-081).")
    public ResponseEntity<ApiResponse<RevenueTrendResponse>> getRevenueTrend(
            @PathVariable Long templeId) {
        var response = financeReportService.getRevenueTrend(templeId, currentClaims());
        return ResponseEntity.ok(ApiResponse.success("Revenue trend retrieved.", response));
    }

    @GetMapping("/revenue/monthly")
    @Operation(summary = "Revenue by month within a financial year (FIN-081).")
    public ResponseEntity<ApiResponse<MonthlyRevenueResponse>> getMonthlyRevenue(
            @PathVariable Long templeId,
            @RequestParam String financialYear) {
        var response = financeReportService.getMonthlyRevenue(templeId, financialYear, currentClaims());
        return ResponseEntity.ok(ApiResponse.success("Monthly revenue retrieved.", response));
    }

    @GetMapping("/revenue/categories")
    @Operation(summary = "Revenue split by canonical category for a financial year (FIN-081).")
    public ResponseEntity<ApiResponse<CategoryBreakdownResponse>> getCategoryBreakdown(
            @PathVariable Long templeId,
            @RequestParam String financialYear) {
        var response = financeReportService.getCategoryBreakdown(templeId, financialYear, currentClaims());
        return ResponseEntity.ok(ApiResponse.success("Category breakdown retrieved.", response));
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Verification status per check, for a financial year (FIN-083).")
    public ResponseEntity<ApiResponse<ReconciliationResponse>> getReconciliation(
            @PathVariable Long templeId,
            @RequestParam String financialYear) {
        var response = financeReportService.getReconciliation(templeId, financialYear, currentClaims());
        return ResponseEntity.ok(ApiResponse.success("Reconciliation status retrieved.", response));
    }

    private ScopeHelper.Claims currentClaims() {
        return (ScopeHelper.Claims) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}

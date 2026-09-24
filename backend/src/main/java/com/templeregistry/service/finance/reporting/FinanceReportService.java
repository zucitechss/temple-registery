package com.templeregistry.service.finance.reporting;

import com.templeregistry.dto.response.finance.CategoryBreakdownResponse;
import com.templeregistry.dto.response.finance.FinanceCapabilityResponse;
import com.templeregistry.dto.response.finance.FinanceSummaryResponse;
import com.templeregistry.dto.response.finance.MonthlyRevenueResponse;
import com.templeregistry.dto.response.finance.ReconciliationResponse;
import com.templeregistry.dto.response.finance.RevenueTrendResponse;
import com.templeregistry.security.ScopeHelper;

/**
 * Reads canonical and aggregate finance tables only, never a source (FIN-081/082/083,
 * API_CONTRACT §1.5).
 */
public interface FinanceReportService {

    FinanceCapabilityResponse.ForTemple getCapabilities(Long templeId, ScopeHelper.Claims claims);

    FinanceSummaryResponse getSummary(Long templeId, String financialYear, ScopeHelper.Claims claims);

    RevenueTrendResponse getRevenueTrend(Long templeId, ScopeHelper.Claims claims);

    MonthlyRevenueResponse getMonthlyRevenue(Long templeId, String financialYear, ScopeHelper.Claims claims);

    CategoryBreakdownResponse getCategoryBreakdown(Long templeId, String financialYear, ScopeHelper.Claims claims);

    ReconciliationResponse getReconciliation(Long templeId, String financialYear, ScopeHelper.Claims claims);
}

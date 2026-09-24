package com.templeregistry.dto.response.finance;

import java.util.List;

/**
 * Revenue split by canonical category for one financial year (FIN-081, API_CONTRACT
 * §4 `revenue/categories`).
 *
 * <p>{@code categoryCode} and {@code categoryName} are the platform's own taxonomy
 * ({@code fin_revenue_category}), never a source's own category label — no source vocabulary
 * reaches this response (API_CONTRACT §1.1).
 */
public record CategoryBreakdownResponse(
        Long templeId,
        String financialYear,
        List<CategoryPoint> categories,
        DataFreshnessBlock dataFreshness) {

    public record CategoryPoint(String categoryCode, String categoryName, MetricEnvelope grossRevenue) {
    }
}

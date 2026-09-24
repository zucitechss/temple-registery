package com.templeregistry.dto.response.finance;

import java.util.List;

/** Revenue by financial year (FIN-081, API_CONTRACT §4 `revenue/trend`). */
public record RevenueTrendResponse(
        Long templeId,
        List<YearPoint> years,
        DataFreshnessBlock dataFreshness) {

    public record YearPoint(String financialYear, MetricEnvelope grossRevenue) {
    }
}

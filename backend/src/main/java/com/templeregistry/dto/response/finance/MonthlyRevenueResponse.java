package com.templeregistry.dto.response.finance;

import java.util.List;

/** Revenue by month within one financial year (FIN-081, API_CONTRACT §4 `revenue/monthly`). */
public record MonthlyRevenueResponse(
        Long templeId,
        String financialYear,
        List<MonthPoint> months,
        DataFreshnessBlock dataFreshness) {

    /** {@code periodKey} in {@code AggregationPeriod}'s own form, e.g. {@code 2025-04}. */
    public record MonthPoint(String month, MetricEnvelope grossRevenue) {
    }
}

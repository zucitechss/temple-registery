package com.templeregistry.dto.response.finance;

/**
 * Headline metrics for one temple, one financial year (FIN-081, API_CONTRACT §4.2).
 *
 * <p>{@code expenditure} is not a stub field kept permanently {@code NOT_AVAILABLE} for one
 * temple — it is resolved the same way every other metric is, through the temple's declared
 * {@code EXPENSE} capability. It reads {@code NOT_AVAILABLE} for Kollur today because no expense
 * pipeline exists, and would read differently the day a temple declares one.
 */
public record FinanceSummaryResponse(
        Long templeId,
        String financialYear,
        MetricEnvelope grossRevenue,
        MetricEnvelope netRevenue,
        MetricEnvelope transactionCount,
        MetricEnvelope expenditure,
        DataFreshnessBlock dataFreshness) {
}

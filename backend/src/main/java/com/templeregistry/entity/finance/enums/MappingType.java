package com.templeregistry.entity.finance.enums;

/**
 * Kind of source-value to canonical-value translation held in {@code fin_mapping_rule}.
 *
 * <p>Each value names the canonical field a rule decides. A rule set is scoped
 * to one source system, so the same raw value can mean different things at two
 * temples without either knowing about the other.
 *
 * <p>The three added for the Financial Dashboard subjects target the tables
 * created in V124&ndash;V127. {@link #METAL_TYPE} predates them and finally has
 * a target: {@code fin_precious_item_fact.metal_type}.
 */
public enum MappingType {
    REVENUE_CATEGORY,
    SERVICE,
    PAYMENT_MODE,
    STATUS,
    FINANCIAL_YEAR,
    METAL_TYPE,
    /** Source expense code to {@code fin_expense_category.category_code}. */
    EXPENSE_CATEGORY,
    /** Source fund classification to {@code fin_dc_fund.fund_category}. */
    FUND_CATEGORY,
    /** Source seva code to {@code fin_nirantara_subscription.seva_type}. */
    NIRANTARA_TYPE
}

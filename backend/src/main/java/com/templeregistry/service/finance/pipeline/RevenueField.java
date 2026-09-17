package com.templeregistry.service.finance.pipeline;

/**
 * The canonical fields normalization reads from a staged payload, each named by a
 * source-of-truth declaration rather than by code (FIN-055, ADR-008).
 *
 * <h2>Why a declaration and not a column</h2>
 *
 * <p>Normalization is the first stage entitled to look at a money value, and the last place a
 * source's own field names may legitimately appear — as data. Which field carries the
 * authoritative amount is exactly the kind of decision ADR-008 exists for: for the first
 * onboarded source three columns plausibly represent revenue and disagree by 41%, and the more
 * granular one is the wrong answer. That is not a judgement a connector should make silently,
 * and not one this class should hardcode.
 *
 * <p>Each constant names a {@code metric} in {@code fin_source_of_truth_decl}. The declaration
 * in force supplies {@code source_field}, which is the key to read from the staged payload —
 * the same vocabulary a mapping rule's namespace uses (FIN-D-028), so the two configuration
 * mechanisms agree about what a field is called.
 *
 * <h2>Required versus optional</h2>
 *
 * <p>A required field with no declaration is a configuration failure: the source system has
 * not been onboarded far enough to produce facts, and normalization refuses the batch rather
 * than emitting rows with a guessed date or amount.
 *
 * <p>An optional field with no declaration is NULL on every fact — which is the honest
 * statement that this source does not record it (ADR-007), and never a zero. Because the
 * declaration governs the whole source, a field is either declared for every row or absent
 * from every row; a group is therefore never summed from a mix of known and unknown
 * contributors.
 */
public enum RevenueField {

    /** The business date the revenue belongs to. Never the extraction or modification date. */
    TRANSACTION_DATE("REVENUE_TRANSACTION_DATE", true),

    /** Recognised gross amount for the record. */
    GROSS_AMOUNT("REVENUE_AMOUNT", true),

    /**
     * Amount cancelled. Undeclared means this source does not record cancellations at all, so
     * net revenue is genuinely unknown and the fact's generated {@code net_amount} stays NULL
     * rather than asserting that nothing was cancelled (FIN-D-020).
     */
    CANCELLED_AMOUNT("REVENUE_CANCELLED_AMOUNT", false),

    /** Count of cancelled transactions. NULL and 0 are different answers. */
    CANCELLED_COUNT("REVENUE_CANCELLED_COUNT", false),

    /**
     * Receipts represented by the record. Receipts are receipts, never devotees: one devotee
     * may buy several items on one receipt and one receipt may cover a family.
     */
    TRANSACTION_COUNT("REVENUE_TRANSACTION_COUNT", false),

    /** Items, where counting them is meaningful — prasadam, for instance. */
    QUANTITY("REVENUE_QUANTITY", false),

    /** Collection point. Part of the canonical grain, so a wrong one splits or merges facts. */
    COUNTER_REF("REVENUE_COUNTER_REF", false),

    /** Pseudonymous operator handle. Never a person's name. */
    OPERATOR_REF("REVENUE_OPERATOR_REF", false);

    private final String metric;
    private final boolean required;

    RevenueField(String metric, boolean required) {
        this.metric = metric;
        this.required = required;
    }

    /** The {@code fin_source_of_truth_decl.metric} value that declares this field. */
    public String metric() {
        return metric;
    }

    /** Whether a batch can be normalized at all without this field declared. */
    public boolean required() {
        return required;
    }
}

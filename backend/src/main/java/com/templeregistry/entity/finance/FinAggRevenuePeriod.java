package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One period total, as published (FIN-070).
 *
 * <p><b>Grain:</b> one row per
 * {@code (temple, source system, period type, period key, category, payment mode)}, enforced by
 * {@code uk_farp_grain}. Every column of it is {@code NOT NULL}, so unlike {@code fin_revenue_fact}
 * this table needs no generated stand-in columns: there is no nullable "all categories" total row to
 * defend against, because totals are a {@code SUM} at read time (FIN-070B D5).
 *
 * <p><b>Existing in this table is what publication means.</b> There is no draft row, no candidate row
 * and no withheld row — a row that exists but must not be read is a trap for the next reader. A
 * period the gate blocks is written not at all, and the previously published row stays exactly as it
 * was, which is ADR-011's "slightly old and correct beats fresh and wrong" implemented by doing
 * nothing. {@link #reconciliationStatus} therefore only ever holds {@code PASSED} or
 * {@code NOT_AVAILABLE}.
 *
 * <p><b>Never summed across sources.</b> {@code sourceSystemId} is in the grain because the gate
 * decides per (temple, source, financial year). A temple-level figure is the sum of its sources,
 * computed by whoever reads this table.
 *
 * <p><b>Every measure is nullable, and absence is not zero</b> (ADR-007). Each nullable measure has a
 * counter beside it saying how much of the period could not be measured, so a reader can tell a floor
 * from a total. {@link #netAmount} is computed by the database and is deliberately NULL wherever
 * cancellations were not fully recorded.
 *
 * <p>Follows the {@code FinRevenueFact} precedent rather than {@code BaseEntity}: this is computed,
 * replaced and recomputed by the pipeline, so soft-delete and authorship columns would describe a
 * person who was never involved.
 */
@Entity
// uk_farp_grain is declared in V119 and not here: Flyway owns the schema (ADR-002), and this
// annotation describes only the access paths. net_amount is likewise the database's to compute.
@Table(
    name = "fin_agg_revenue_period",
    indexes = {
        @Index(name = "idx_farp_temple_source_fy", columnList = "temple_id, source_system_id, financial_year"),
        @Index(name = "idx_farp_report",           columnList = "temple_id, period_type, period_key, category_id, payment_mode")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinAggRevenuePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // ---- the grain ---------------------------------------------------------

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** Which system's figures these are. Never aggregated across. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private PeriodType periodType;

    /** {@code 2025-26} for a financial year, {@code 2025-04} for a calendar month. */
    @Column(name = "period_key", nullable = false, length = 20)
    private String periodKey;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 30)
    private PaymentMode paymentMode;

    // ---- period identity ---------------------------------------------------

    /**
     * The parent financial year, carried on {@code MONTH} rows too.
     *
     * <p>Not redundant there: it is the scope {@code ReconciliationGate} is asked about, and it is
     * how FIN-072 will find every row a year's rebuild must replace.
     */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    // ---- measures ----------------------------------------------------------

    /** Contributing canonical facts. A roll-up count, never a receipt count. */
    @Column(name = "fact_count", nullable = false)
    private Integer factCount;

    /** NULL when any contributing fact recorded no count — a floor cannot verify agreement. */
    @Column(name = "transaction_count")
    private Long transactionCount;

    @Column(name = "facts_with_unknown_count", nullable = false)
    private Integer factsWithUnknownCount;

    @Column(name = "gross_amount", precision = 20, scale = 2)
    private BigDecimal grossAmount;

    /** How many contributing facts added nothing to {@link #grossAmount}. */
    @Column(name = "facts_with_unknown_gross", nullable = false)
    private Integer factsWithUnknownGross;

    /** NULL when any contributing fact did not record cancellations at all. */
    @Column(name = "cancelled_count")
    private Long cancelledCount;

    @Column(name = "cancelled_amount", precision = 20, scale = 2)
    private BigDecimal cancelledAmount;

    @Column(name = "quantity", precision = 20, scale = 3)
    private BigDecimal quantity;

    /** ISO 4217. Asserted single-valued at computation; a mixed group is refused, never summed. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * {@code gross - cancelled}, computed by the database so no writer can disagree with it.
     * NULL wherever cancellations were not fully recorded.
     */
    @Column(name = "net_amount", precision = 20, scale = 2, insertable = false, updatable = false)
    private BigDecimal netAmount;

    /** A flag, never a grouping dimension: confidence is not part of the fact grain. */
    @Column(name = "payment_mode_inferred_facts", nullable = false)
    private Integer paymentModeInferredFacts;

    // ---- why this row is visible -------------------------------------------

    /** {@code PASSED}, or {@code NOT_AVAILABLE} for published-but-flagged. Never FAILED or PENDING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 20)
    private ReconciliationStatus reconciliationStatus;

    /** True on month rows: the verdict is the parent year's, because no monthly evidence exists. */
    @Column(name = "reconciliation_inherited", nullable = false)
    private Boolean reconciliationInherited;

    /** Which aggregation formula produced this row. See {@code RevenueAggregator.CALC_VERSION}. */
    @Column(name = "calc_version", nullable = false)
    private Short calcVersion;

    // ---- computation axis, distinct from the business axis -----------------

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

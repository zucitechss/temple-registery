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
 * Expenditure rolled up per period (V131, FR1.3 and FR4).
 *
 * <p>Mirrors {@link FinAggRevenuePeriod} deliberately, so that a reader who
 * understands one understands all three aggregates.
 *
 * <p><b>Why it exists, honestly.</b> Not speed: expense volumes are orders of
 * magnitude below revenue and reading the facts directly would serve FR4 for a
 * long time. It exists for the reason ADR-011 gives — a query-time sum cannot
 * be withheld. It always reflects the newest facts, including facts the
 * publication gate says must not be published. A stored answer can lag the
 * facts on purpose, so a temple whose expenditure failed reconciliation keeps
 * showing its last good figures rather than gaining unverified ones.
 *
 * <p><b>Manual figures publish with a flag.</b> For a manual or file-upload
 * source, {@link #reconciliationStatus} is permanently
 * {@link ReconciliationStatus#NOT_AVAILABLE}: reconciliation compares a source
 * own total against the canonical total, and a self-reported figure has no
 * independent source to compare against. That value publishes rather than
 * blocking, but reports must render the flag rather than showing a
 * self-reported figure with the same badge as a source-verified one.
 */
@Entity
@Table(
    name = "fin_agg_expense_period",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_faep_grain",
        columnNames = {"temple_id", "source_system_id", "period_type", "period_key",
                       "category_id", "payment_mode"}),
    indexes = {
        @Index(name = "idx_faep_temple_source_fy", columnList = "temple_id, source_system_id, financial_year"),
        @Index(name = "idx_faep_report",           columnList = "temple_id, period_type, period_key, category_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinAggExpensePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** In the grain. Aggregates are never summed across sources by the aggregator. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private PeriodType periodType;

    @Column(name = "period_key", nullable = false, length = 20)
    private String periodKey;

    /** {@code fin_expense_category.id}. Never a null-means-all total row. */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** UNRECORDED is not CASH. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 30)
    private PaymentMode paymentMode;

    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "fact_count", nullable = false)
    private Integer factCount;

    /** Null only when no contributing fact recorded any. */
    @Column(name = "amount", precision = 20, scale = 2)
    private BigDecimal amount;

    /** The sum above is a floor by this many facts. */
    @Column(name = "facts_with_unknown_amount", nullable = false)
    private Integer factsWithUnknownAmount;

    /**
     * The part met from a DC-approved fund. Lets FR1 box 4 and FR4 agree
     * without a join at read time, and makes the two figures reconcilable.
     */
    @Column(name = "fund_backed_amount", precision = 20, scale = 2)
    private BigDecimal fundBackedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 20)
    private ReconciliationStatus reconciliationStatus;

    @Builder.Default
    @Column(name = "reconciliation_inherited", nullable = false)
    private Boolean reconciliationInherited = false;

    @Column(name = "calc_version", nullable = false)
    private Short calcVersion;

    /** When this figure was computed. NOT a business date. */
    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

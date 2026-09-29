package com.templeregistry.entity.finance;

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
 * Revenue rolled up per service and period (V131, FR8 and FR10).
 *
 * <p>Mirrors {@link FinAggRevenuePeriod} deliberately: same period vocabulary,
 * same source-system scope, same nullable measures with a companion count each,
 * same reconciliation columns, same {@code calc_version}. Re-grained by service
 * instead of by category and payment mode, because FR10 splits revenue by seva
 * and FR8 ranks the top five, and neither is answerable from a category grain.
 *
 * <p><b>This table cannot receive a row today, and that is recorded rather than
 * hidden.</b> {@code fin_revenue_fact.service_id} is nullable by design and null
 * in practice — always, not sometimes: the normalizer passes a literal null,
 * {@link FinServiceDim} has no writer, and no migration seeds it (FIN-D-069).
 * It exists now because it is part of the schema contract that reporting and
 * ingestion are being built against in parallel. Creating it does not claim it
 * works.
 *
 * <p><b>Facts with no service are excluded, not bucketed.</b> An unattributed
 * bucket would be indistinguishable from a real service in the FR8 ranking and
 * would top it. The report states the excluded share instead, which is the
 * ADR-007 reading: unattributed revenue is not a seva that earned nothing.
 */
@Entity
@Table(
    name = "fin_agg_revenue_service",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fars_grain",
        columnNames = {"temple_id", "source_system_id", "period_type", "period_key", "service_id"}),
    indexes = {
        @Index(name = "idx_fars_temple_source_fy", columnList = "temple_id, source_system_id, financial_year"),
        @Index(name = "idx_fars_rank",             columnList = "temple_id, period_type, period_key, net_amount"),
        @Index(name = "idx_fars_service",          columnList = "service_id, period_type, period_key")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinAggRevenueService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** Which system figures these are. Never aggregated across. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private PeriodType periodType;

    /** {@code 2025-26} for a financial year, {@code 2025-04} for a calendar month. */
    @Column(name = "period_key", nullable = false, length = 20)
    private String periodKey;

    /** {@code fin_service_dim.id}. Never a null-means-all row: unattributed facts are excluded. */
    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    /** Carried so FR8 can rank within a kind without joining the dimension. */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** Parent financial year, carried on month rows too: it is the scope the gate is asked about. */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** Canonical facts that contributed. A roll-up count, never a receipt count. */
    @Column(name = "fact_count", nullable = false)
    private Integer factCount;

    /** Null when any contributing fact did not record one. */
    @Column(name = "transaction_count")
    private Long transactionCount;

    /** Explains the null above. */
    @Column(name = "facts_with_unknown_count", nullable = false)
    private Integer factsWithUnknownCount;

    @Column(name = "gross_amount", precision = 20, scale = 2)
    private BigDecimal grossAmount;

    /** The sum above is a floor by this many facts. */
    @Column(name = "facts_with_unknown_gross", nullable = false)
    private Integer factsWithUnknownGross;

    /** Null when any contributing fact did not record cancellations at all. Zero means measured, and none. */
    @Column(name = "cancelled_amount", precision = 20, scale = 2)
    private BigDecimal cancelledAmount;

    @Column(name = "quantity", precision = 20, scale = 3)
    private BigDecimal quantity;

    /** Asserted single-valued across the facts rolled up here. A sum across currencies is nonsense. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Computed by the database so no writer can disagree with it. */
    @Column(name = "net_amount", precision = 20, scale = 2, insertable = false, updatable = false)
    private BigDecimal netAmount;

    /** The gate verdict this row was published under. FAILED and PENDING never reach this table. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 20)
    private ReconciliationStatus reconciliationStatus;

    /** True on month rows: the verdict is the parent year, because no monthly evidence exists. */
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

package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The result of comparing a total computed <em>by the source system</em> against
 * the same total computed from canonical facts, for one metric and period.
 *
 * <p>The comparison is only meaningful because the two sides are genuinely
 * independent: {@link #sourceTotal} comes from the connector asking the source to
 * aggregate, not from re-summing rows the platform already extracted. Re-summing
 * our own extract would confirm arithmetic, not fidelity.
 *
 * <p>A {@code FAILED} result <b>blocks publication</b> of the affected aggregates.
 * Prior good figures stay visible and the temple is marked stale, because on a
 * government oversight dashboard, slightly old and correct beats fresh and wrong.
 *
 * <p>Totals are nullable. When the source cannot produce a comparison figure the
 * status is {@link ReconciliationStatus#NOT_AVAILABLE} -- never a zero, and never
 * a silent pass.
 */
@Entity
@Table(
    name = "fin_reconciliation_result",
    indexes = {
        @Index(name = "idx_frr_temple_period", columnList = "temple_id, metric, period_type, period_key"),
        @Index(name = "idx_frr_batch",         columnList = "sync_batch_id"),
        @Index(name = "idx_frr_status",        columnList = "status, checked_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinReconciliationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** Null for scheduled re-verification of data that was loaded earlier. */
    @Column(name = "sync_batch_id")
    private Long syncBatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 50)
    private FinanceCapability capability;

    /** e.g. {@code REVENUE_AMOUNT}, {@code TRANSACTION_COUNT}. */
    @Column(name = "metric", nullable = false, length = 50)
    private String metric;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private PeriodType periodType;

    /** e.g. {@code 2025-26}, {@code 2025-04}, {@code 2025-04-01}, {@code ALL}. */
    @Column(name = "period_key", nullable = false, length = 20)
    private String periodKey;

    /** Computed by the source system itself. Null when it could not be obtained. */
    @Column(name = "source_total", precision = 20, scale = 2)
    private BigDecimal sourceTotal;

    /** Computed from canonical facts. */
    @Column(name = "central_total", precision = 20, scale = 2)
    private BigDecimal centralTotal;

    /** {@code centralTotal - sourceTotal}. */
    @Column(name = "difference", precision = 20, scale = 2)
    private BigDecimal difference;

    @Column(name = "difference_pct", precision = 9, scale = 4)
    private BigDecimal differencePct;

    /** Default zero: revenue is expected to match exactly, not approximately. */
    @Builder.Default
    @Column(name = "tolerance_pct", nullable = false, precision = 9, scale = 4)
    private BigDecimal tolerancePct = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReconciliationStatus status;

    @Column(name = "status_reason", columnDefinition = "TEXT")
    private String statusReason;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

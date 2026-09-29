package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.DataSubmissionStatus;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Whether one day of one capability arrived for one temple (V129, FR17, FR20).
 *
 * <p>Deriving this by scanning facts would make the question "was yesterday
 * submitted?" a table scan per temple per report, and — more importantly — it
 * could not distinguish a day that was submitted and happened to total zero
 * from a day nobody submitted. That is the conflation ADR-007 exists to
 * prevent, and it is why {@link DataSubmissionStatus#NIL_RETURN} is a value
 * here rather than a zero-amount fact.
 *
 * <p>FR19 depends on this table being per day rather than per submission: one
 * workbook covering twelve dates writes twelve rows, which is how a bulk upload
 * can report exactly which missed days it satisfied and how the alert knows
 * what remains.
 *
 * <p>FR20 falls out of it. The load stage marks the day fulfilled and the alert
 * is re-evaluated in the same transaction; clearing is a consequence of arrival,
 * not a separate action a person can take.
 */
@Entity
@Table(
    name = "fin_daily_data_status",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fdds_temple_capability_date",
        columnNames = {"temple_id", "capability", "business_date"}),
    indexes = {
        @Index(name = "idx_fdds_gap",   columnList = "temple_id, capability, status, business_date"),
        @Index(name = "idx_fdds_sweep", columnList = "status, business_date"),
        @Index(name = "idx_fdds_batch", columnList = "fulfilled_by_batch_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinDailyDataStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_data_expectation.id} — which obligation this day belongs to. */
    @Column(name = "expectation_id", nullable = false)
    private Long expectationId;

    /** Denormalised from the expectation so the dashboard query is one table deep. */
    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 50)
    private FinanceCapability capability;

    /** The day being accounted for. A business date, never the entry date. */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DataSubmissionStatus status = DataSubmissionStatus.EXPECTED;

    /** The batch that satisfied this day. Null while EXPECTED or MISSED. */
    @Column(name = "fulfilled_by_batch_id")
    private Long fulfilledByBatchId;

    /**
     * When it was satisfied. Later than {@link #businessDate} for a historical
     * upload, which is the normal case for FR19 and is why the two columns are
     * separate.
     */
    @Column(name = "fulfilled_at")
    private LocalDateTime fulfilledAt;

    /** Who declared this day not required, for WAIVED. */
    @Column(name = "waived_by")
    private Long waivedBy;

    @Column(name = "waiver_reason", length = 500)
    private String waiverReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

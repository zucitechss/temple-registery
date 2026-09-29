package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.NirantaraStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A perpetual seva booking (V135, FR11).
 *
 * <p>ADR-009 designs four tables for the Nirantara lifecycle so that the
 * platform can answer whether a booked seva is actually taking place. FR11 does
 * not ask that: it asks which sevas are booked, how many bookings each has, and
 * per booking the id, type, start date and income. That is this entity and
 * {@link FinNirantaraPayment}. The schedule and execution tables remain in
 * ADR-009 as the design to follow if execution tracking is ever required, and
 * are not created, because two tables no report reads and no source fills are
 * harder to remove later than to add.
 *
 * <p>{@link #subscriberRef} is a pseudonymous handle, consistent with the
 * revenue fact holding no devotee name, address, mobile or email. The temple
 * system keeps the identity; this platform keeps what it needs to count
 * bookings and trace one back.
 */
@Entity
@Table(
    name = "fin_nirantara_subscription",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fns_record", columnNames = {"source_system_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fns_temple_type",  columnList = "temple_id, seva_type, status"),
        @Index(name = "idx_fns_temple_start", columnList = "temple_id, start_date"),
        @Index(name = "idx_fns_booking",      columnList = "temple_id, booking_ref"),
        @Index(name = "idx_fns_batch",        columnList = "sync_batch_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinNirantaraSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    @Column(name = "source_of_truth_version")
    private Integer sourceOfTruthVersion;

    /** Row-exact provenance handle. In the grain. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /**
     * FR11 booking id. The requirement says to show it if available, so it is
     * nullable by specification. This is the source booking number, not the id
     * of this row.
     */
    @Column(name = "booking_ref", length = 100)
    private String bookingRef;

    /** {@code fin_service_dim.id}. Null until service resolution can place this seva. */
    @Column(name = "service_id")
    private Long serviceId;

    /**
     * FR11 type of Nirantara seva, canonical, resolved through
     * {@code mapping_type = NIRANTARA_TYPE}. Present even when
     * {@link #serviceId} is not, because FR11 groups by it.
     */
    @Column(name = "seva_type", nullable = false, length = 150)
    private String sevaType;

    /** Pseudonymous handle. Never a devotee name, address, mobile or email. */
    @Column(name = "subscriber_ref", length = 100)
    private String subscriberRef;

    /** FR11 start date. The business date the arrangement begins. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Null means perpetual, which is the usual case and the meaning of Nirantara. */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private NirantaraStatus status = NirantaraStatus.ACTIVE;

    /** FY the booking was made in. */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

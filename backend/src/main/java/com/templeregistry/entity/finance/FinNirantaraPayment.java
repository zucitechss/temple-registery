package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.PaymentMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Money received against a perpetual seva booking (V127, FR11).
 *
 * <p>Separate from {@link FinNirantaraSubscription} because a booking is made
 * once and paid many times. One table with an amount column would force a
 * choice between losing the payment history and repeating the booking on every
 * payment.
 *
 * <p><b>This money may also appear in {@link FinRevenueFact}</b>, because a
 * Nirantara receipt is a receipt like any other and a revenue connector will
 * see it. That is not double counting as long as nothing sums the two: FR11
 * reports per-booking income from this table, and FR2, FR3 and FR10 report
 * revenue from the revenue facts. No report adds them together, and none
 * should. The rule is written here because it is the mistake this shape makes
 * easy.
 */
@Entity
@Table(
    name = "fin_nirantara_payment",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fnp_record", columnNames = {"source_system_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fnp_subscription", columnList = "subscription_id, paid_on"),
        @Index(name = "idx_fnp_temple_fy",    columnList = "temple_id, financial_year"),
        @Index(name = "idx_fnp_batch",        columnList = "sync_batch_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinNirantaraPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Denormalised from the subscription so every isolation check is one table deep. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_nirantara_subscription.id}. */
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** Row-exact provenance handle. In the grain. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /** Business date of the payment. */
    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    /** FY of the payment. FR11 income is the year-to-date sum over this column. */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    /** Null means not recorded by this source (ADR-007). */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 30)
    private PaymentMode paymentMode = PaymentMode.UNRECORDED;

    /** Period this payment covers, where the source states one. Not the same as {@link #paidOn}. */
    @Column(name = "covers_from")
    private LocalDate coversFrom;

    @Column(name = "covers_to")
    private LocalDate coversTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

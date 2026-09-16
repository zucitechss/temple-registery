package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of canonical revenue -- the reporting boundary of the finance platform.
 *
 * <p><b>Grain:</b> one row per
 * {@code (temple, transaction_date, service, category, payment mode, counter, operator)},
 * not one row per receipt (ADR-003). On the first onboarded source, 22,348,125
 * receipts collapse to roughly 121 thousand rows, and every catalogued report is
 * still answerable. No devotee name, address, mobile or email column exists here,
 * because none is needed and a central platform holding personal data it cannot
 * use is a liability.
 *
 * <p>The grain is enforced by the database, not by the loader: {@code uk_frf_grain}
 * covers all seven columns, with generated key columns standing in for the three
 * that are nullable, because MySQL and TiDB treat NULLs in a unique index as
 * distinct and would otherwise accept the same fact twice (FIN-D-018). Re-running
 * a sync therefore updates a row rather than adding a second one.
 *
 * <p><b>Business date, not modification date.</b> {@link #transactionDate} is when
 * the revenue belongs financially. A source may edit a two-year-old receipt today;
 * that corrects an old day, it does not move money into today. The modification
 * axis is {@link #createdAt} / {@link #updatedAt} and the batch, and conflating the
 * two would silently restate history (FIN-D-012).
 *
 * <p><b>Every measure is nullable</b> (ADR-007). NULL means the source does not
 * record it; zero means it was measured and there was none. {@link #netAmount} is
 * computed by the database and is deliberately NULL when cancellations are
 * unrecorded -- reporting gross as net would assert that nothing was cancelled.
 *
 * <p>Follows the {@code EmailOutbox} / {@code FinSyncBatch} precedent rather than
 * {@code BaseEntity}: this is loaded, restated and replaced by the pipeline, so
 * soft-delete and authorship columns would describe a person who was never
 * involved.
 */
@Entity
// uk_frf_grain is declared in V112 and not here: two of its seven columns are
// database-generated, so an entity-side declaration would name columns this class
// does not map. Flyway owns the schema (ADR-002); this annotation describes only
// the access paths.
@Table(
    name = "fin_revenue_fact",
    indexes = {
        @Index(name = "idx_frf_temple_fy",      columnList = "temple_id, financial_year"),
        @Index(name = "idx_frf_temple_date",    columnList = "temple_id, transaction_date"),
        @Index(name = "idx_frf_temple_cat_fy",  columnList = "temple_id, category_id, financial_year"),
        @Index(name = "idx_frf_temple_service", columnList = "temple_id, service_id, financial_year"),
        @Index(name = "idx_frf_batch",          columnList = "sync_batch_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinRevenueFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // ---- identity and provenance -------------------------------------------

    /** Registry temple id. The isolation key; never a hardcoded value. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_source_system.id} -- which system produced this figure. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** {@code fin_sync_batch.id} -- which run produced it, and therefore when. */
    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** Version of the source-of-truth declaration in force at load (ADR-008). */
    @Column(name = "source_of_truth_version")
    private Integer sourceOfTruthVersion;

    /** Opaque group handle for tracing back to staging. Never SQL. */
    @Column(name = "source_record_ref", length = 200)
    private String sourceRecordRef;

    // ---- the grain ---------------------------------------------------------

    /** Business date. Never the source modification date. */
    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /** Canonical financial year string, e.g. {@code 2025-26}. */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    /** {@code fin_service_dim.id}; null where the revenue is not a service. */
    @Column(name = "service_id")
    private Long serviceId;

    /** {@code fin_revenue_category.id}. Unmappable values land in {@code UNMAPPED}. */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 30)
    private PaymentMode paymentMode;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode_confidence", nullable = false, length = 20)
    private PaymentModeConfidence paymentModeConfidence = PaymentModeConfidence.RECORDED;

    /** Collection point; null where the source records none. */
    @Column(name = "counter_ref", length = 50)
    private String counterRef;

    /** Pseudonymous operator handle. Never a person's name. */
    @Column(name = "operator_ref", length = 50)
    private String operatorRef;

    // ---- measures: null means unknown, zero means measured ------------------

    @Column(name = "transaction_count")
    private Long transactionCount;

    @Column(name = "gross_amount", precision = 18, scale = 2)
    private BigDecimal grossAmount;

    /** Null = this source does not record cancellations. Zero = it does, and there were none. */
    @Column(name = "cancelled_count")
    private Long cancelledCount;

    @Column(name = "cancelled_amount", precision = 18, scale = 2)
    private BigDecimal cancelledAmount;

    @Column(name = "quantity", precision = 18, scale = 3)
    private BigDecimal quantity;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    /**
     * {@code gross - cancelled}, computed by the database so no loader can
     * disagree with it. Null where cancellations are unrecorded.
     */
    @Column(name = "net_amount", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal netAmount;

    // ---- load axis, distinct from the business axis ------------------------

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

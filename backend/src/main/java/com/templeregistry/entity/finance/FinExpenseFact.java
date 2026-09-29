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
 * One canonical expenditure record (V132, FR1.3 and FR4).
 *
 * <p><b>Grain: one row per source record</b>, not per day. This is deliberately
 * unlike {@link FinRevenueFact}, and the full reasoning is in the V132 header.
 * In short: revenue collapses to a daily grain because one source holds 22
 * million receipts, whereas a temple records a handful of vouchers a day, each
 * with its own payee and its own receipt. Collapsing them would destroy exactly
 * the detail the FR4 grid exists to show.
 *
 * <p>{@link #sourceRecordRef} is therefore <b>never null</b> here, unlike on the
 * revenue fact where a grouped row has no single source record. Provenance is
 * row-exact: the trail reaches an individual form submission or spreadsheet
 * cell.
 *
 * <p>{@link #amount} is nullable (ADR-007). Null means the source does not
 * record it; zero means it was recorded and was zero. A nil return is a
 * {@link FinDailyDataStatus} row, not a zero-amount fact, because a fact asserts
 * a transaction happened.
 */
@Entity
@Table(
    name = "fin_expense_fact",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fef_record", columnNames = {"source_system_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fef_temple_fy",     columnList = "temple_id, financial_year"),
        @Index(name = "idx_fef_temple_date",   columnList = "temple_id, expense_date"),
        @Index(name = "idx_fef_temple_cat_fy", columnList = "temple_id, category_id, financial_year"),
        @Index(name = "idx_fef_batch",         columnList = "sync_batch_id"),
        @Index(name = "idx_fef_fund",          columnList = "fund_utilisation_id"),
        @Index(name = "idx_fef_supersede",     columnList = "temple_id, source_system_id, expense_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinExpenseFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Registry temple id. The isolation key; never a hardcoded value. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_source_system.id}. In the grain. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** {@code fin_sync_batch.id} that last wrote this row. */
    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** Version of the source-of-truth declaration in force at load (ADR-008). */
    @Column(name = "source_of_truth_version")
    private Integer sourceOfTruthVersion;

    /** Row-exact provenance handle, e.g. {@code STG:90118} or {@code Expenses!R42}. In the grain. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /** Business date the spend belongs to. Never the entry date. */
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    /** Canonical financial year string, e.g. {@code 2025-26}. */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    /** {@code fin_expense_category.id}. Unmappable values land in UNMAPPED, never OTHER_EXPENSE. */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** Set where this spend was met from a DC-approved fund. Null for ordinary expenditure. */
    @Column(name = "fund_utilisation_id")
    private Long fundUtilisationId;

    /** Null means not recorded by this source. Zero means recorded, and zero (ADR-007). */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 30)
    private PaymentMode paymentMode = PaymentMode.UNRECORDED;

    /** Payee as recorded. Not a person record, and never linked to one. */
    @Column(name = "vendor_ref", length = 200)
    private String vendorRef;

    /** The temple own voucher or bill number, for reconciliation against its books. */
    @Column(name = "voucher_ref", length = 100)
    private String voucherRef;

    @Column(name = "description", length = 500)
    private String description;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

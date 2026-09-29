package com.templeregistry.entity.finance;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One drawdown against a DC-approved fund (V125, FR5 and FR6).
 *
 * <p>The spend side of {@link FinDcFund}, and what FR5.7 receipts and FR5.8
 * photographic evidence attach to. Documents live in the existing document
 * store; this platform adds no second file store.
 *
 * <p>{@link #templeId} is denormalised from the fund so that every isolation
 * check is one table deep.
 *
 * <p>{@link #progressPercent} is advisory: it is what the temple reported, not
 * a figure derived from money spent. The two legitimately differ, and deriving
 * one from the other would assert a relationship nobody measured.
 */
@Entity
@Table(
    name = "fin_fund_utilisation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ffu_record", columnNames = {"source_system_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_ffu_fund",      columnList = "fund_id, utilised_on"),
        @Index(name = "idx_ffu_temple_fy", columnList = "temple_id, financial_year"),
        @Index(name = "idx_ffu_batch",     columnList = "sync_batch_id"),
        @Index(name = "idx_ffu_supersede", columnList = "temple_id, source_system_id, utilised_on")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinFundUtilisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Denormalised from the fund so every isolation check is one table deep. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_dc_fund.id}. */
    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** Row-exact provenance handle. In the grain. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /** Business date of the drawdown. */
    @Column(name = "utilised_on", nullable = false)
    private LocalDate utilisedOn;

    /**
     * FY of the drawdown, which may differ from the fund financial year: a fund
     * approved in March is often spent in April, and FR1 box 4 asks about the
     * spending year.
     */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    /** Null means not recorded (ADR-007). */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", length = 1000)
    private String description;

    /** FR6 progress as reported by the temple. Advisory, never derived from money spent. */
    @Column(name = "progress_percent")
    private Short progressPercent;

    /** FR5.7. Existing document store. */
    @Column(name = "receipt_document_id")
    private Long receiptDocumentId;

    /** FR5.8. Existing document store. */
    @Column(name = "photo_document_id")
    private Long photoDocumentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

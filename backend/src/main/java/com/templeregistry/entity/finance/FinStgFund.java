package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.FundStagingRecordKind;
import com.templeregistry.entity.finance.enums.StagingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Staged DC fund and utilisation rows, before they are judged (V128).
 *
 * <p>Same shape and rules as {@link FinStgRevenue}; see {@link FinStgExpense}
 * for why manual submissions are staged rather than written directly.
 *
 * <p>One staging table serves both fund tables because a fund and its drawdowns
 * arrive together, on one form and in one sheet. Splitting them here would make
 * a single submission span two batches, and neither half would be reconcilable
 * on its own. {@link #recordKind} says which target a row becomes.
 */
@Entity
@Table(
    name = "fin_stg_fund",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fsf_batch_record", columnNames = {"sync_batch_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fsf_batch_status", columnList = "sync_batch_id, validation_status, record_kind"),
        @Index(name = "idx_fsf_temple_date",  columnList = "temple_id, source_business_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinStgFund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /** Which of the two fund tables this row becomes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "record_kind", nullable = false, length = 20)
    private FundStagingRecordKind recordKind;

    @Column(name = "raw_json", nullable = false, columnDefinition = "JSON")
    private String rawJson;

    /** Advisory: approval date for a FUND row, utilisation date for a UTILISATION row. */
    @Column(name = "source_business_date")
    private LocalDate sourceBusinessDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 20)
    private StagingStatus validationStatus = StagingStatus.RECEIVED;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /** When the row was read or submitted. NOT a business date. */
    @Column(name = "extracted_at", nullable = false)
    private LocalDateTime extractedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.NirantaraStagingRecordKind;
import com.templeregistry.entity.finance.enums.StagingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Staged Nirantara subscription and payment rows, before they are judged (V136).
 *
 * <p>Same shape and rules as {@link FinStgRevenue}; see {@link FinStgExpense}
 * for why manual submissions are staged rather than written directly, and
 * {@link FinStgFund} for why one staging table serves two targets.
 */
@Entity
@Table(
    name = "fin_stg_nirantara",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fsn_batch_record", columnNames = {"sync_batch_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fsn_batch_status", columnList = "sync_batch_id, validation_status, record_kind"),
        @Index(name = "idx_fsn_temple_date",  columnList = "temple_id, source_business_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinStgNirantara {

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

    /** Which of the two Nirantara tables this row becomes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "record_kind", nullable = false, length = 20)
    private NirantaraStagingRecordKind recordKind;

    @Column(name = "raw_json", nullable = false, columnDefinition = "JSON")
    private String rawJson;

    /** Advisory: start date for a SUBSCRIPTION row, paid date for a PAYMENT row. */
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

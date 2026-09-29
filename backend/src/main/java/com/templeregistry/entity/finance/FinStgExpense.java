package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.StagingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Staged expenditure, before it is judged (V136).
 *
 * <p>Same shape and same rules as {@link FinStgRevenue}, and read that class
 * for the reasoning: nothing here is trusted, the payload stays loose so one
 * impossible value cannot lose a batch, and the three timestamps answer three
 * different questions of which none is a business date.
 *
 * <p><b>Why a form submission is staged at all.</b> It could be written
 * straight to its fact from a controller. Staging it instead is what makes the
 * manual lane use the same validator, the same mapping record, the same
 * idempotent load and the same error trail as a connector, and it means a
 * rejected submission leaves evidence of what was submitted.
 *
 * <p>Unlike revenue, {@link #sourceRecordRef} is also the fact grain for this
 * subject (V132), so the unique key that stops the same record being staged
 * twice in one batch is the same thing that stops two facts being written for
 * one input row.
 */
@Entity
@Table(
    name = "fin_stg_expense",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fse_batch_record", columnNames = {"sync_batch_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fse_batch_status", columnList = "sync_batch_id, validation_status"),
        @Index(name = "idx_fse_temple_date",  columnList = "temple_id, source_business_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinStgExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** A form submission handle, or a sheet and row such as {@code Expenses!R42}. Never SQL. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /** The submitted or extracted record in its own field names. Source vocabulary stops here. */
    @Column(name = "raw_json", nullable = false, columnDefinition = "JSON")
    private String rawJson;

    /** Advisory. Normalization derives the authoritative {@code expense_date}. */
    @Column(name = "source_business_date")
    private LocalDate sourceBusinessDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 20)
    private StagingStatus validationStatus = StagingStatus.RECEIVED;

    /** Human-readable; the coded, queryable form is {@code fin_sync_error}. */
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

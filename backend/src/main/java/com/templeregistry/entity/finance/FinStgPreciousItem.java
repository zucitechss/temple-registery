package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.StagingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Staged precious-item rows, before they are judged (V136).
 *
 * <p>Same shape and rules as {@link FinStgRevenue}; see {@link FinStgExpense}
 * for why manual submissions are staged rather than written directly.
 *
 * <p>Both channels of FR7 stage here: a connector delivering counts and weight,
 * and a manual valuation delivering purity and value. They are distinguished by
 * {@code source_system_id} on the batch, not by a column, because each is a
 * separate batch from a separate source.
 */
@Entity
@Table(
    name = "fin_stg_precious_item",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fspi_batch_record", columnNames = {"sync_batch_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fspi_batch_status", columnList = "sync_batch_id, validation_status"),
        @Index(name = "idx_fspi_temple_date",  columnList = "temple_id, source_business_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinStgPreciousItem {

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

    @Column(name = "raw_json", nullable = false, columnDefinition = "JSON")
    private String rawJson;

    /** Advisory: the received date, not the valuation date. */
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

package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.StagingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One record a connector delivered for the REVENUE capability, exactly as it arrived.
 *
 * <p><b>Grain:</b> one row per {@code RawRow} produced by {@code extract()} within one sync
 * batch. Staging never merges, splits or reinterprets what it was given. Connectors group at
 * the source (ADR-003), so a delivered record is usually already a source-level grouping
 * rather than a single receipt — but staging does not assume that, and several staged rows
 * may contribute to one canonical daily fact. That collapse belongs to normalization
 * (FIN-055), where it is visible and testable.
 *
 * <p><b>Nothing here is trusted.</b> A staged row is what a source said, kept so that what
 * the platform later publishes can be traced back to it and, if wrong, explained. No report
 * reads this table.
 *
 * <p><b>The payload stays loose.</b> {@link #rawJson} holds the connector's own field names
 * with values as strings, because real sources contain impossible dates, nulls where the
 * schema promises otherwise and text in numeric columns. Typed staging columns would turn
 * each of those into a failure during extraction and lose an entire batch over one bad row.
 * Source vocabulary stops at this field: nothing above normalization may read its keys.
 *
 * <p><b>Idempotency</b> is {@code uk_fsr_batch_record} — one record reference per batch. A
 * replay stages the same records again under a new batch, which is how a restatement is
 * investigated; what is forbidden is the same record twice inside one batch.
 *
 * <p>Follows the {@code FinSyncBatch} / {@code EmailOutbox} precedent rather than
 * {@code BaseEntity}: this is a pipeline row with a processing state, not authored
 * configuration, so soft-delete and authorship columns would describe nobody.
 */
@Entity
@Table(
    name = "fin_stg_revenue",
    uniqueConstraints = @UniqueConstraint(name = "uk_fsr_batch_record",
                                          columnNames = {"sync_batch_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fsr_batch_status",  columnList = "sync_batch_id, validation_status"),
        @Index(name = "idx_fsr_temple_date",   columnList = "temple_id, source_business_date"),
        @Index(name = "idx_fsr_source_record", columnList = "source_system_id, source_record_ref")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinStgRevenue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // ---- provenance, all mandatory ------------------------------------------

    /** Registry temple id. The isolation key; never a hardcoded value. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_source_system.id} — which system produced this record. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** {@code fin_sync_batch.id} — which extraction delivered it, over which window. */
    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /**
     * Opaque locator in the source, specific enough for a human to find the original.
     * Mandatory by the connector contract: a rejected row that cannot be located is not
     * diagnosable.
     */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    // ---- payload -------------------------------------------------------------

    /** The connector record as delivered: its own field names, values as raw strings. */
    @Column(name = "raw_json", nullable = false, columnDefinition = "JSON")
    private String rawJson;

    /**
     * Connector-declared business date, or null when the connector could not state one
     * without interpreting the payload. Advisory: normalization derives the authoritative
     * {@code transaction_date} from {@link #rawJson}. Null means "not declared at
     * extraction", never "no date".
     */
    @Column(name = "source_business_date")
    private LocalDate sourceBusinessDate;

    // ---- processing state ----------------------------------------------------

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 20)
    private StagingStatus validationStatus = StagingStatus.RECEIVED;

    /** Human-readable rejection reason; the coded, queryable form is {@code fin_sync_error}. */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    // ---- three timestamps, three different questions -------------------------

    /** When the connector read this record from the source. Never a business date. */
    @Column(name = "extracted_at", nullable = false)
    private LocalDateTime extractedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

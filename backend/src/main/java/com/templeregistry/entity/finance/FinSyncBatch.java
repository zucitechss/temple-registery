package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One extraction run for one capability of one source system -- the audit spine
 * of the finance platform.
 *
 * <p>Every attempt leaves a row, successful or not. That matters because a
 * published financial figure is only as defensible as the evidence that it was
 * loaded from a specific source, over a specific window, and verified. Batches
 * are retained indefinitely: they are small, and they are that evidence.
 *
 * <p>The state machine deliberately mirrors {@code email_outbox} (V105), which
 * this codebase already operates and monitors -- same exponential back-off, same
 * terminal dead-letter state, so the existing operational habits transfer:
 *
 * <pre>
 *   PENDING -&gt; RUNNING -&gt; SUCCESS
 *                      -&gt; FAILED            -&gt; (retry) -&gt; RUNNING
 *                      -&gt; RECONCILE_FAILED
 *                      -&gt; DEAD_LETTER
 * </pre>
 *
 * <p>{@link #watermarkAfter} is advanced <b>only</b> on {@code SUCCESS}. A failed
 * batch therefore re-reads the same window next time rather than skipping past
 * rows it never loaded.
 *
 * <p>This entity does not extend {@code BaseEntity}, following the precedent set
 * by {@code EmailOutbox}: it is an append-mostly operational log, not a domain
 * entity, and status transitions make soft-delete meaningless.
 */
@Entity
@Table(
    name = "fin_sync_batch",
    uniqueConstraints = @UniqueConstraint(name = "uk_fsb_batch_ref", columnNames = "batch_ref"),
    indexes = {
        @Index(name = "idx_fsb_temple_status", columnList = "temple_id, status"),
        @Index(name = "idx_fsb_retry",         columnList = "status, next_retry_at"),
        @Index(name = "idx_fsb_source_cap",    columnList = "source_system_id, capability, started_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinSyncBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID, safe to quote in a support conversation or an audit finding. */
    @Column(name = "batch_ref", nullable = false, length = 64)
    private String batchRef;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** One batch handles exactly one capability, so a partial failure is precisely scoped. */
    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 50)
    private FinanceCapability capability;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_type", nullable = false, length = 20)
    private SyncType syncType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SyncStatus status = SyncStatus.PENDING;

    @Column(name = "window_from")
    private LocalDateTime windowFrom;

    @Column(name = "window_to")
    private LocalDateTime windowTo;

    @Column(name = "watermark_before", length = 100)
    private String watermarkBefore;

    /** Advanced only on SUCCESS -- see class javadoc. */
    @Column(name = "watermark_after", length = 100)
    private String watermarkAfter;

    @Builder.Default
    @Column(name = "rows_extracted", nullable = false)
    private Long rowsExtracted = 0L;

    /** Rows refused by validation or mapping. Each has a {@link FinSyncError} row. */
    @Builder.Default
    @Column(name = "rows_rejected", nullable = false)
    private Long rowsRejected = 0L;

    @Builder.Default
    @Column(name = "rows_loaded", nullable = false)
    private Long rowsLoaded = 0L;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Builder.Default
    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 5;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_failure_reason", columnDefinition = "TEXT")
    private String lastFailureReason;

    /** Compared against {@code fin_source_system.schema_fingerprint} to detect drift. */
    @Column(name = "schema_fingerprint", length = 64)
    private String schemaFingerprint;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 50)
    private SyncTrigger triggeredBy = SyncTrigger.SCHEDULER;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** True when this batch may still be retried automatically. */
    public boolean isRetryable() {
        return status == SyncStatus.FAILED && retryCount < maxRetries;
    }
}

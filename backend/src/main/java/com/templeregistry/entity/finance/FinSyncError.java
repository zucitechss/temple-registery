package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.SyncStage;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One source row that the pipeline refused, and why.
 *
 * <p>A batch can succeed overall while individual rows are rejected. Those rows
 * are recorded here rather than dropped, so that
 * {@code fin_sync_batch.rows_rejected = 143} is always explainable down to the
 * individual record -- which is the difference between a defensible figure and a
 * figure with an unexplained shortfall.
 *
 * <p>{@link #rawPayloadJson} retains the offending row so it can be diagnosed and
 * replayed without re-contacting the temple source system.
 */
@Entity
@Table(
    name = "fin_sync_error",
    indexes = {
        @Index(name = "idx_fse_batch", columnList = "sync_batch_id, error_stage"),
        @Index(name = "idx_fse_code",  columnList = "error_code")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinSyncError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** Locator for the offending source row, e.g. {@code DailySevaNew|2025-04-01|83|1}. */
    @Column(name = "source_record_ref", length = 200)
    private String sourceRecordRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_stage", nullable = false, length = 30)
    private SyncStage errorStage;

    /** Stable machine-readable code, e.g. {@code UNMAPPED_SERVICE}, {@code NULL_DATE}. */
    @Column(name = "error_code", nullable = false, length = 60)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** The rejected row as extracted, for diagnosis and replay. */
    @Column(name = "raw_payload_json", columnDefinition = "JSON")
    private String rawPayloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

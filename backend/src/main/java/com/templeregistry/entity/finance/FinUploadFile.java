package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.UploadStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One uploaded workbook (V130, FR19).
 *
 * <p>An Excel import <em>is</em> a batch: {@link FinSyncBatch} already counts
 * rows extracted, rejected and loaded, holds a status, tracks retries and names
 * its trigger and actor, and {@link FinSyncError} already records a per-row
 * failure with a stage, a code and the offending payload — which is exactly the
 * error report FR19 needs. None of that is rebuilt here.
 *
 * <p>What a batch cannot hold is the file: the name a person refers to, the
 * content hash that detects a re-upload, and the reference to the stored bytes.
 * That is this entity, one to one with the batch it opened.
 *
 * <p><b>{@link #status} is not the batch status.</b> The batch says where the
 * pipeline got to; this says what the uploader is being asked to do next. An
 * upload sitting at {@link UploadStatus#AWAITING_COMMIT} with a perfectly
 * healthy batch is the normal state between the two calls FR19 requires.
 *
 * <p>The hash is over the uploaded bytes and scoped to
 * {@code (temple, capability)}: two temples filling the same template with the
 * same figures is a coincidence, not a duplicate. Two files differing only by a
 * spreadsheet recalculation are different files by this test, which is the safe
 * direction to be wrong in — the date-supersede protocol (V124) then prevents
 * the double count.
 */
@Entity
@Table(
    name = "fin_upload_file",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fuf_temple_capability_hash",
        columnNames = {"temple_id", "capability", "content_sha256"}),
    indexes = {
        @Index(name = "idx_fuf_temple_status", columnList = "temple_id, status, uploaded_at"),
        @Index(name = "idx_fuf_batch",         columnList = "sync_batch_id"),
        @Index(name = "idx_fuf_uploader",      columnList = "uploaded_by, uploaded_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinUploadFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** The FILE_UPLOAD channel this arrived through. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** What the workbook contains. One capability per file: a template is per capability. */
    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 50)
    private FinanceCapability capability;

    /** Null only between this row being written and the batch being opened. */
    @Column(name = "sync_batch_id")
    private Long syncBatchId;

    /** As uploaded. What the person will call it when they ask about it. */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    /** Over the uploaded bytes. The duplicate guard. */
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /**
     * Read from the workbook version marker. Null means it could not be read,
     * which is a structural rejection rather than an unknown version.
     */
    @Column(name = "template_version", length = 20)
    private String templateVersion;

    /** Handle in the existing file store. Never a path this application constructs. */
    @Column(name = "storage_ref", nullable = false, length = 500)
    private String storageRef;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UploadStatus status = UploadStatus.RECEIVED;

    /** Why the whole file was refused. Per-row reasons are {@code fin_sync_error}. */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /** Data rows found. Null until the structural check has read the file. */
    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "rows_valid")
    private Integer rowsValid;

    @Column(name = "rows_invalid")
    private Integer rowsInvalid;

    /** Earliest business date in the file. Drives the FR19 freshness update and the supersede preview. */
    @Column(name = "dates_covered_from")
    private LocalDate datesCoveredFrom;

    @Column(name = "dates_covered_to")
    private LocalDate datesCoveredTo;

    /** The person. Not null, because a file always has one. */
    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    /** Who confirmed the import. Usually the uploader, deliberately not assumed to be. */
    @Column(name = "committed_by")
    private Long committedBy;

    @Column(name = "committed_at")
    private LocalDateTime committedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

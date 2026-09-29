package com.templeregistry.entity.temple;

import com.templeregistry.entity.base.BaseEntity;
import com.templeregistry.util.AesEncryptionConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * Staging record for Temple Authority profile edits awaiting DC review.
 * Maps to V13 temple_profile_staging table.
 *
 * Status lifecycle (per DECISION-01, PENDING_REVIEW is displayed as SUBMITTED in API):
 *   DRAFT → PENDING_REVIEW → APPROVED → SUPERSEDED (on next approval)
 *                          → REJECTED  → new DRAFT (version+1)
 */
@Entity
@Table(name = "temple_profile_staging", indexes = {
        @Index(name = "idx_profile_staging_temple_status", columnList = "temple_id, status")
})
@SQLRestriction("is_deleted = false")
@SQLDelete(sql = "UPDATE temple_profile_staging SET is_deleted = true, updated_at = NOW(6) WHERE id = ?")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TempleProfileStaging extends BaseEntity {

    @Convert(converter = TempleProfileStagingStatusConverter.class)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private TempleProfileStagingStatus status = TempleProfileStagingStatus.DRAFT;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "version_number", nullable = false)
    @Builder.Default
    private Integer versionNumber = 1;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "phone", length = 15)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "website", length = 500)
    private String website;

    @Column(name = "contact_person_name", length = 255)
    private String contactPersonName;

    @Column(name = "contact_person_designation", length = 100)
    private String contactPersonDesignation;

    @Column(name = "photo_file_path", length = 1000)
    private String photoFilePath;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Convert(converter = AesEncryptionConverter.class)
    @Column(name = "bank_account_number_encrypted", columnDefinition = "TEXT")
    private String bankAccountNumberEncrypted;

    @Column(name = "bank_ifsc", length = 11)
    private String bankIfsc;

    @Column(name = "languages_of_worship", length = 500)
    private String languagesOfWorship;

    /** JSON array of linked mutt/sub-temple names, stored as raw JSON string. */
    @Column(name = "linked_institutions", columnDefinition = "JSON")
    private String linkedInstitutions;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "annual_festivals", columnDefinition = "TEXT")
    private String annualFestivals;

    @Column(name = "landmark", length = 500)
    private String landmark;

    @Column(name = "historical_significance", columnDefinition = "TEXT")
    private String historicalSignificance;

    // ── Identity fields (editable by TA, require DC approval — V93) ───────────

    @Column(name = "alias_name", length = 255)
    private String aliasName;

    @Column(name = "primary_deity", length = 150)
    private String primaryDeity;

    @Column(name = "grade", length = 1)
    private String grade;

    @Column(name = "tradition", length = 50)
    private String tradition;

    @Column(name = "hobli_id")
    private Long hobliId;

    /**
     * Independent, directly-settable column (V117) — NOT purely derived from hobliId.
     * A TA may pick District + Taluk with no Hobli yet available in the master geo
     * data for their location; that selection must still persist. When null,
     * read paths fall back to deriving it from hobliId (Hobli -> Taluk), matching
     * behavior for staging rows written before this column existed.
     */
    @Column(name = "taluk_id")
    private Long talukId;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "pin_code", length = 6)
    private String pinCode;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // Location metadata (V97)
    @Column(name = "place_id", length = 500)
    private String placeId;

    @Column(name = "formatted_address", length = 1000)
    private String formattedAddress;

    @Column(name = "year_established")
    private Integer yearEstablished;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * Compatibility field used by tests/DTO mapping that still reference submittedAt.
     * Canonical submission timestamp is tracked in workflow_instance.submitted_at.
     */
    @Transient
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;
}

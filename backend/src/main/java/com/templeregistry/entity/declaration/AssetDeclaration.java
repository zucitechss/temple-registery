package com.templeregistry.entity.declaration;

import com.templeregistry.entity.base.BaseEntity;
import com.templeregistry.entity.governance.PhysicalVerificationStatus;
import com.templeregistry.entity.governance.SystemVerificationStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "asset_declarations", indexes = {
        @Index(name = "idx_decl_temple_id",    columnList = "temple_id"),
        @Index(name = "idx_decl_status",       columnList = "status"),
        @Index(name = "idx_decl_district_id",  columnList = "district_id"),
        @Index(name = "idx_decl_temple_year",  columnList = "temple_id, financial_year"),
        @Index(name = "idx_decl_overdue",      columnList = "is_overdue, status, temple_id")
})
@SQLRestriction("is_deleted = false")
// The version predicate is required: with @Version present Hibernate binds id AND version to the
// soft-delete statement, so a single-placeholder SQL fails with "Parameter index out of range".
@SQLDelete(sql = "UPDATE asset_declarations SET is_deleted = true, updated_at = NOW(6) WHERE id = ? AND lock_version = ?")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class AssetDeclaration extends BaseEntity {

    /** JPA optimistic lock — column renamed lock_version per dc_e2e F11 (avoids clash with version_number). */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "temple_id",    nullable = false) private Long templeId;
    @Column(name = "district_id",  nullable = false) private Long districtId;

    /** Financial year in YYYY-YY format (e.g. 2025-26). */
    @Column(name = "financial_year", length = 7)     private String financialYear;

    /** Submission counter — increments when Temple Authority creates a new version after rejection. */
    @Builder.Default
    @Column(name = "version_number", nullable = false)
    private int versionNumber = 1;

    @Builder.Default
    @Convert(converter = DeclarationStatusConverter.class)
    @Column(name = "status", nullable = false, length = 40) private DeclarationStatus status = DeclarationStatus.DRAFT;

    // Immovable Assets
    @Column(name = "agricultural_land_acres", precision = 12, scale = 4) private BigDecimal agriculturalLandAcres;
    @Column(name = "agricultural_land_value", precision = 18, scale = 2) private BigDecimal agriculturalLandValue;
    @Column(name = "buildings_sqft", precision = 12, scale = 2) private BigDecimal buildingsSqft;
    @Column(name = "buildings_value", precision = 18, scale = 2) private BigDecimal buildingsValue;
    @Column(name = "leased_properties_count") private Integer leasedPropertiesCount;
    @Column(name = "leased_properties_value", precision = 18, scale = 2) private BigDecimal leasedPropertiesValue;
    @Column(name = "other_land_value", precision = 18, scale = 2) private BigDecimal otherLandValue;

    // Movable Assets
    @Column(name = "gold_grams", precision = 12, scale = 3) private BigDecimal goldGrams;
    @Column(name = "silver_grams", precision = 12, scale = 3) private BigDecimal silverGrams;
    @Column(name = "idols_count") private Integer idolsCount;
    @Column(name = "vehicles_count") private Integer vehiclesCount;
    @Column(name = "financial_assets_value", precision = 18, scale = 2) private BigDecimal financialAssetsValue;
    @Column(name = "other_movable_value", precision = 18, scale = 2) private BigDecimal otherMovableValue;

    // Workflow timestamps
    @Column(name = "submitted_at")  private LocalDateTime submittedAt;
    @Column(name = "submitted_by")  private Long submittedBy;
    @Column(name = "reviewed_at")   private LocalDateTime reviewedAt;
    @Column(name = "reviewed_by")   private Long reviewedBy;
    @Column(name = "review_comment", columnDefinition = "TEXT") private String reviewComment;
    @Column(name = "acknowledged_at") private LocalDateTime acknowledgedAt;

    // Workflow counters and flags
    @Builder.Default
    @Column(name = "clarification_round", nullable = false)
    private int clarificationRound = 0;

    @Builder.Default
    @Column(name = "is_overdue", nullable = false)
    private boolean isOverdue = false;

    @Column(name = "overdue_flagged_at") private LocalDateTime overdueFlaggedAt;

    // Acknowledgement
    @Column(name = "acknowledgement_number", length = 50)  private String acknowledgementNumber;
    @Column(name = "acknowledgement_doc_file_path", length = 1000) private String acknowledgementDocFilePath;

    // Snapshot frozen at PENDING_REVIEW — never modified after
    @Column(name = "snapshot_json", columnDefinition = "JSON")  private String snapshotJson;
    @Column(name = "snapshot_file_path", length = 1000)         private String snapshotFilePath;

    @Column(name = "due_date") private java.time.LocalDate dueDate;

    // Annual Income and Expenditure
    @Column(name = "annual_income", precision = 18, scale = 2)
    private BigDecimal annualIncome;

    @Column(name = "annual_expenditure", precision = 18, scale = 2)
    private BigDecimal annualExpenditure;

    // Note: Asset sub-table relationships are managed via repositories in the dc package
    // to avoid circular dependencies. Use repositories to fetch related assets.
    // ─── Governance Status Model ─────────────────────────────────────

    /**
     * INTERNAL ONLY — must NEVER be returned to Temple Authority.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "system_verification_status", length = 30)
    private SystemVerificationStatus systemVerificationStatus;

    /**
     * Free-text reason entered by DC on Send Back.
     * Mandatory when DC sends back. Visible to Temple Authority.
     */
    @Column(name = "send_back_reason", columnDefinition = "TEXT")
    private String sendBackReason;

    /**
     * Physical verification status — ASSET DECLARATIONS ONLY.
     * DC-ONLY field. Must NEVER be returned to Temple Authority.
     * Manually set by DC only. System must NEVER auto-set this.
     */
    @Builder.Default
    @Convert(converter = com.templeregistry.entity.declaration.PhysicalVerificationStatusConverter.class)
    @Column(name = "physical_verification_status", nullable = false, length = 50)
    private PhysicalVerificationStatus physicalVerificationStatus = PhysicalVerificationStatus.NOT_INITIATED;

    @Column(name = "physical_verification_ordered_at")
    private LocalDateTime physicalVerificationOrderedAt;

    @Column(name = "physical_verification_ordered_by")
    private Long physicalVerificationOrderedBy;

    @Column(name = "physical_verification_completed_at")
    private LocalDateTime physicalVerificationCompletedAt;
}

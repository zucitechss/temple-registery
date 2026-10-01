package com.templeregistry.dto.response.dc;

import com.templeregistry.entity.declaration.DeclarationStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Enriched declaration view for the DC workflow portal.
 *
 * Combines the flat asset declaration fields with:
 * - The full clarification exchange history
 * - All 8 granular asset sub-table line-item lists
 *
 * dc_e2e Section 3.5 — Declaration Detail.
 */
@Getter
@Builder
public class DeclarationDetailResponse {

    // Core declaration fields
    private Long id;
    private Long templeId;
    private Long districtId;
    private String financialYear;
    private int versionNumber;
    private DeclarationStatus status;

    // Flat asset totals
    private BigDecimal agriculturalLandAcres;
    private BigDecimal agriculturalLandValue;
    private BigDecimal buildingsSqft;
    private BigDecimal buildingsValue;
    private Integer leasedPropertiesCount;
    private BigDecimal leasedPropertiesValue;
    private BigDecimal otherLandValue;
    private BigDecimal goldGrams;
    private BigDecimal silverGrams;
    private Integer idolsCount;
    private Integer vehiclesCount;
    private BigDecimal financialAssetsValue;
    private BigDecimal otherMovableValue;

    // Workflow metadata
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private Long reviewedBy;
    private LocalDateTime acknowledgedAt;
    private String acknowledgementNumber;
    private LocalDate dueDate;
    private int clarificationRound;
    private boolean overdue;

    // Clarification exchange history (chronological)
    private List<ClarificationItemResponse> clarifications;

    // Granular sub-table line items
    private List<DeclImmovAgriLandResponse> agriculturalLands;
    private List<DeclImmovBuildingResponse> buildings;
    private List<DeclImmovLeasedResponse> leasedProperties;
    private List<DeclImmovOtherResponse> otherLands;
    private List<DeclMovPreciousMetalResponse> preciousMetals;
    private List<DeclMovArtifactResponse> artifacts;
    private List<DeclMovVehicleResponse> vehicles;
    private List<DeclMovEquipmentResponse> equipment;
}

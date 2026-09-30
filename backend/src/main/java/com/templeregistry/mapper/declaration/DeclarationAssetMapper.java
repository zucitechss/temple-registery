package com.templeregistry.mapper.declaration;

import com.templeregistry.dto.request.declaration.*;
import com.templeregistry.dto.response.declaration.*;
import com.templeregistry.entity.dc.*;
import org.springframework.stereotype.Component;

@Component
public class DeclarationAssetMapper {

    public DeclImmovAgriLand toAgriLandEntity(AgriLandItemRequest request, Long declarationId) {
        return DeclImmovAgriLand.builder()
                .declarationId(declarationId)
                .surveyNumber(request.getSurveyNumber())
                .location(request.getVillage())
                .areaAcres(request.getAreaAcres())
                .encumbrance(request.getOwnerOfRecord())
                .ownershipType(request.getPattaStatus())
                .build();
    }

    public AgriLandItemResponse toAgriLandResponse(DeclImmovAgriLand entity) {
        AgriLandItemResponse response = new AgriLandItemResponse();
        response.setId(entity.getId());
        response.setSurveyNumber(entity.getSurveyNumber());
        response.setVillage(entity.getLocation());
        response.setAreaAcres(entity.getAreaAcres());
        response.setOwnerOfRecord(entity.getEncumbrance());
        response.setPattaStatus(entity.getOwnershipType());
        return response;
    }

    public DeclImmovBuilding toBuildingEntity(BuildingItemRequest request, Long declarationId) {
        return DeclImmovBuilding.builder()
                .declarationId(declarationId)
                .location(request.getLocation())
                .areaSqft(request.getTotalAreaSqft())
                .yearOfConstruction(request.getYearBuilt())
                .structureType(request.getStructureType())
                .valuation(request.getValuationInr())
                .build();
    }

    public BuildingItemResponse toBuildingResponse(DeclImmovBuilding entity) {
        BuildingItemResponse response = new BuildingItemResponse();
        response.setId(entity.getId());
        response.setLocation(entity.getLocation());
        response.setTotalAreaSqft(entity.getAreaSqft());
        response.setYearBuilt(entity.getYearOfConstruction());
        response.setStructureType(entity.getStructureType());
        response.setValuationInr(entity.getValuation());
        return response;
    }

    public DeclImmovLeased toLeasedPropertyEntity(LeasedPropertyItemRequest request, Long declarationId) {
        return DeclImmovLeased.builder()
                .declarationId(declarationId)
                .location(request.getPropertyAddress())
                .lesseeName(request.getLesseeName())
                .leaseStartDate(request.getLeaseStartDate())
                .leaseExpiry(request.getLeaseEndDate())
                .monthlyRent(request.getMonthlyRent())
                .agreementDocumentId(request.getAgreementDocumentId())
                .build();
    }

    public LeasedPropertyItemResponse toLeasedPropertyResponse(DeclImmovLeased entity) {
        LeasedPropertyItemResponse response = new LeasedPropertyItemResponse();
        response.setId(entity.getId());
        response.setPropertyAddress(entity.getLocation());
        response.setLesseeName(entity.getLesseeName());
        response.setLeaseStartDate(entity.getLeaseStartDate());
        response.setLeaseEndDate(entity.getLeaseExpiry());
        response.setMonthlyRent(entity.getMonthlyRent() != null ? entity.getMonthlyRent() : entity.getAnnualRent());
        response.setAgreementDocumentId(entity.getAgreementDocumentId());
        return response;
    }

    public DeclMovPreciousMetal toPreciousMetalEntity(PreciousMetalItemRequest request, Long declarationId) {
        return DeclMovPreciousMetal.builder()
                .declarationId(declarationId)
                .itemDescription(request.getItemDescription())
                .itemType(request.getMetalType())
                .weightGrams(request.getWeightGrams())
                .purity(request.getPurity())
                .estimatedValue(request.getApproximateValueInr())
                .build();
    }

    public PreciousMetalItemResponse toPreciousMetalResponse(DeclMovPreciousMetal entity) {
        PreciousMetalItemResponse response = new PreciousMetalItemResponse();
        response.setId(entity.getId());
        response.setItemDescription(entity.getItemDescription());
        response.setMetalType(entity.getItemType());
        response.setWeightGrams(entity.getWeightGrams());
        response.setPurity(entity.getPurity());
        response.setApproximateValueInr(entity.getEstimatedValue());
        return response;
    }

    public DeclMovArtifact toArtifactEntity(ArtifactItemRequest request, Long declarationId) {
        return DeclMovArtifact.builder()
                .declarationId(declarationId)
                .description(request.getItemDescription())
                .name(request.getItemDescription())
                .artifactType(request.getMaterial())
                .material(request.getMaterial())
                .ageOrPeriod(request.getAgeOrPeriod())
                .provenance(request.getProvenance())
                .museumGradeClassification(request.getMuseumGradeClassification())
                .estimatedValue(request.getApproximateValueInr())
                .build();
    }

    public ArtifactItemResponse toArtifactResponse(DeclMovArtifact entity) {
        ArtifactItemResponse response = new ArtifactItemResponse();
        response.setId(entity.getId());
        response.setItemDescription(entity.getDescription());
        response.setMaterial(entity.getMaterial() != null ? entity.getMaterial() : entity.getArtifactType());
        response.setAgeOrPeriod(entity.getAgeOrPeriod());
        response.setProvenance(entity.getProvenance());
        response.setMuseumGradeClassification(entity.getMuseumGradeClassification());
        response.setApproximateValueInr(entity.getEstimatedValue());
        return response;
    }

    public DeclMovVehicle toVehicleEntity(VehicleItemRequest request, Long declarationId) {
        return DeclMovVehicle.builder()
                .declarationId(declarationId)
                .registrationNumber(request.getRegistrationNumber())
                .makeAndModel(request.getMakeModel())
                .yearOfPurchase(request.getYear())
                .usagePurpose(request.getPurpose())
                .currentValue(request.getApproximateValueInr())
                .build();
    }

    public VehicleItemResponse toVehicleResponse(DeclMovVehicle entity) {
        VehicleItemResponse response = new VehicleItemResponse();
        response.setId(entity.getId());
        response.setRegistrationNumber(entity.getRegistrationNumber());
        response.setMakeModel(entity.getMakeAndModel());
        response.setYear(entity.getYearOfPurchase());
        response.setPurpose(entity.getUsagePurpose());
        response.setApproximateValueInr(entity.getCurrentValue());
        return response;
    }

    public DeclMovEquipment toEquipmentEntity(EquipmentItemRequest request, Long declarationId) {
        return DeclMovEquipment.builder()
                .declarationId(declarationId)
                .description(request.getItemName())
                .serialNumber(request.getSerialNumber())
                .totalValue(request.getApproximateValueInr())
                .build();
    }

    public EquipmentItemResponse toEquipmentResponse(DeclMovEquipment entity) {
        EquipmentItemResponse response = new EquipmentItemResponse();
        response.setId(entity.getId());
        response.setItemName(entity.getDescription());
        response.setSerialNumber(entity.getSerialNumber());
        response.setApproximateValueInr(entity.getTotalValue());
        return response;
    }

    public DeclMovFinancial toFinancialAssetEntity(FinancialAssetItemRequest request, Long declarationId) {
        return DeclMovFinancial.builder()
                .declarationId(declarationId)
                .assetType(request.getAssetSubtype())
                .institutionName(request.getBankName())
                .description(request.getInvestmentType())
                .maturityDate(request.getMaturityDate())
                .currentValue(request.getAmount())
                .build();
    }

    public FinancialAssetItemResponse toFinancialAssetResponse(DeclMovFinancial entity) {
        FinancialAssetItemResponse response = new FinancialAssetItemResponse();
        response.setId(entity.getId());
        response.setAssetSubtype(entity.getAssetType());
        response.setBankName(entity.getInstitutionName());
        response.setInvestmentType(entity.getDescription());
        response.setAmount(entity.getCurrentValue());
        response.setMaturityDate(entity.getMaturityDate());
        return response;
    }

    public DeclImmovOther toOtherLandEntity(OtherLandItemRequest request, Long declarationId) {
        return DeclImmovOther.builder()
                .declarationId(declarationId)
                .location(request.getLocation())
                .area(request.getArea())
                .landType(request.getUsageType())
                .documentReference(request.getRevenueDepartmentReference())
                .build();
    }

    public OtherLandItemResponse toOtherLandResponse(DeclImmovOther entity) {
        OtherLandItemResponse response = new OtherLandItemResponse();
        response.setId(entity.getId());
        response.setLocation(entity.getLocation());
        response.setArea(entity.getArea());
        response.setUsageType(entity.getLandType());
        response.setRevenueDepartmentReference(entity.getDocumentReference());
        return response;
    }
}

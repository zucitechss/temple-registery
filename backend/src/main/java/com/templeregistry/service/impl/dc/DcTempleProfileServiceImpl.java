package com.templeregistry.service.impl.dc;

import com.templeregistry.dto.response.contractor.ContractorResponse;
import com.templeregistry.dto.response.dc.*;
import com.templeregistry.dto.response.declaration.DeclarationResponse;
import com.templeregistry.dto.response.employee.EmployeeResponse;
import com.templeregistry.dto.response.temple.TempleResponse;
import com.templeregistry.entity.contractor.Contractor;
import com.templeregistry.entity.dc.TempleProfileCurrent;
import com.templeregistry.entity.declaration.AssetDeclaration;
import com.templeregistry.entity.declaration.ClarificationDirection;
import com.templeregistry.entity.declaration.DeclarationClarification;
import com.templeregistry.entity.employee.Employee;
import com.templeregistry.entity.geo.City;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleProfileStaging;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowInstance;
import com.templeregistry.service.workflow.WorkflowEngine;
import com.templeregistry.entity.trust.BoardMember;
import com.templeregistry.entity.trust.BoardMeeting;
import com.templeregistry.entity.trust.Trust;
import com.templeregistry.entity.trust.TrustFinancial;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.contractor.ContractorRepository;
import com.templeregistry.repository.dc.*;
import com.templeregistry.repository.declaration.DeclarationClarificationRepository;
import com.templeregistry.repository.declaration.DeclarationRepository;
import com.templeregistry.repository.employee.EmployeeRepository;
import com.templeregistry.repository.geo.CityRepository;
import com.templeregistry.repository.geo.DistrictRepository;
import com.templeregistry.repository.geo.HobliRepository;
import com.templeregistry.repository.temple.TempleProfileStagingRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.temple.TempleSearchSummaryRepository;
import com.templeregistry.repository.trust.BoardMemberRepository;
import com.templeregistry.repository.trust.BoardMeetingRepository;
import com.templeregistry.repository.trust.TrustFinancialRepository;
import com.templeregistry.repository.workflow.WorkflowInstanceRepository;
import com.templeregistry.repository.trust.TrustRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.dc.DcTempleProfileService;
import com.templeregistry.service.document.FileStorageService;
import com.templeregistry.service.trust.TrustValidationService;
import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class DcTempleProfileServiceImpl implements DcTempleProfileService {
        private final FileStorageService fileStorageService;
        private final PaginationUtil paginationUtil;

        private final TempleRepository templeRepository;
        private final TempleSearchSummaryRepository summaryRepository;
        private final TrustRepository trustRepository;
        private final BoardMemberRepository boardMemberRepository;
        private final BoardMeetingRepository boardMeetingRepository;
        private final TrustFinancialRepository trustFinancialRepository;
        private final EmployeeRepository employeeRepository;
        private final ContractorRepository contractorRepository;
        private final DeclarationRepository declarationRepository;
        private final DeclarationClarificationRepository clarificationRepository;
        private final TempleProfileCurrentRepository profileCurrentRepository;
        private final TempleProfileStagingRepository profileStagingRepository;
        private final DeclImmovAgriLandRepository agriLandRepository;
        private final DeclImmovBuildingRepository buildingRepository;
        private final DeclImmovLeasedRepository leasedRepository;
        private final DeclImmovOtherRepository otherImmovRepository;
        private final DeclMovPreciousMetalRepository preciousMetalRepository;
        private final DeclMovArtifactRepository artifactRepository;
        private final DeclMovVehicleRepository vehicleRepository;
        private final DeclMovEquipmentRepository equipmentRepository;
        private final CityRepository cityRepository;
        private final DistrictRepository districtRepository;
        private final com.templeregistry.repository.geo.HobliRepository hobliRepository;
        private final JurisdictionGuard jurisdictionGuard;
        private final TrustValidationService trustValidationService;
        private final WorkflowEngine workflowEngine;
        private final WorkflowInstanceRepository workflowInstanceRepository;
        private final com.templeregistry.service.governance.GovernanceStatusResolver governanceStatusResolver;
        private final com.templeregistry.service.governance.TempleVisibilityPolicy visibilityPolicy;

        @Override
        @Transactional(readOnly = true)
        @PreAuthorize(RoleConstants.CAN_READ_TEMPLES)
        public TempleFullProfileResponse getFullProfile(Long templeId, ScopeHelper.Claims claims) {
                Temple temple = templeRepository.findWithGeoById(templeId)
                                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
                // DC/DC_STAFF have statewide read access — district scope is only enforced
                // on governance actions (approve, reject, verify, flag), not on profile reads.

                // Geo names from eagerly loaded chain — each hop null-checked to handle
                // incomplete seed data
                String hobliName = null;
                String talukName = null;
                String districtName = null;

                if (temple.getHobli() == null) {
                        log.warn("Incomplete geo data: hobli is null for templeId={}", templeId);
                        // Fall back to flat districtId scalar for auto-created temples
                        if (temple.getDistrictId() != null) {
                                districtName = districtRepository.findById(temple.getDistrictId())
                                        .map(d -> d.getName()).orElse(null);
                        }
                } else {
                        hobliName = temple.getHobli().getName();
                        if (temple.getHobli().getTaluk() == null) {
                                log.warn("Incomplete geo data: taluk is null for hobliId={}, templeId={}",
                                                temple.getHobliId(), templeId);
                        } else {
                                talukName = temple.getHobli().getTaluk().getName();
                                if (temple.getHobli().getTaluk().getDistrict() == null) {
                                        log.warn("Incomplete geo data: district is null for talukId={}, templeId={}",
                                                        temple.getHobli().getTaluk().getId(), templeId);
                                } else {
                                        districtName = temple.getHobli().getTaluk().getDistrict().getName();
                                }
                        }
                }

                // City name via summary — cityId guarded against null to prevent
                // IllegalArgumentException
                // city_id may be NULL in temple_search_summary rows seeded before V12 was
                // applied
                String cityName = summaryRepository.findByTempleId(templeId)
                                .filter(s -> s.getCityId() != null)
                                .flatMap(s -> cityRepository.findById(s.getCityId()))
                                .map(City::getName)
                                .orElse(null);

                // Geo fallback from pending staging: when the temple entity lacks hobliId
                // (first-time profile submissions set hobliId only in staging —
                // promoteToTemple writes it to the Temple entity only on approval),
                // resolve geo names from the latest SUBMITTED/UNDER_REVIEW/RESUBMITTED
                // staging so DC sees correct City and Hobli values before approving.
                if (hobliName == null) {
                        com.templeregistry.entity.geo.Hobli stagingHobli =
                                profileStagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                                        templeId,
                                        java.util.List.of(
                                                com.templeregistry.entity.workflow.WorkflowStatus.SUBMITTED,
                                                com.templeregistry.entity.workflow.WorkflowStatus.UNDER_REVIEW,
                                                com.templeregistry.entity.workflow.WorkflowStatus.RESUBMITTED))
                                .map(TempleProfileStaging::getHobliId)
                                .filter(Objects::nonNull)
                                .flatMap(hobliRepository::findWithGeoById)
                                .orElse(null);
                        if (stagingHobli != null) {
                                hobliName = stagingHobli.getName();
                                if (stagingHobli.getTaluk() != null) {
                                        talukName = stagingHobli.getTaluk().getName();
                                        if (stagingHobli.getTaluk().getDistrict() != null) {
                                                districtName = stagingHobli.getTaluk().getDistrict().getName();
                                                if (stagingHobli.getTaluk().getDistrict().getCity() != null) {
                                                        cityName = stagingHobli.getTaluk().getDistrict().getCity().getName();
                                                }
                                        }
                                }
                                log.debug("Geo names resolved from pending staging for templeId={}: hobli={}, taluk={}, district={}, city={}",
                                        templeId, hobliName, talukName, districtName, cityName);
                        }
                }

                // Governance visibility — TEMPLE_AUTHORITY may only see governance data for
                // their own temple. For any other temple, governance fields are stripped so
                // internal review state never leaks to TAs.
                boolean showGovernance = visibilityPolicy.canViewGovernance(claims, templeId);

                // Trust (use the first active trust registration)
                List<Trust> trusts = trustRepository.findAllByTempleId(templeId);
                Trust primaryTrust = trusts.isEmpty() ? null : trusts.get(0);

                TempleFullProfileResponse.DcTrustSummary trustSummary = null;
                TempleFullProfileResponse.BoardMemberSection boardMembers = TempleFullProfileResponse.BoardMemberSection.builder()
                                .current(List.of())
                                .past(List.of())
                                .validationIssues(List.of())
                                .build();
                List<TempleFullProfileResponse.TrustFinancialSummary> financials = List.of();
                List<TempleFullProfileResponse.BoardMeetingSummary> meetings = List.of();

                if (primaryTrust != null) {
                        trustSummary = toTrustSummary(primaryTrust, claims, showGovernance);
                        List<TempleFullProfileResponse.BoardMemberSummary> allMembers = boardMemberRepository
                                        .findAllByTrustIdOrderByAppointmentDateDescIdDesc(primaryTrust.getId())
                                        .stream().map(this::toBoardMemberResponse).toList();
                        boardMembers = TempleFullProfileResponse.BoardMemberSection.builder()
                                        .current(allMembers.stream().filter(TempleFullProfileResponse.BoardMemberSummary::isCurrent).toList())
                                        .past(allMembers.stream().filter(member -> !member.isCurrent()).toList())
                                        .validationIssues(buildBoardMemberValidationIssues(allMembers))
                                        .build();
                        financials = trustFinancialRepository
                                        .findAllByTrustIdOrderByFinancialYearDesc(primaryTrust.getId())
                                        .stream().map(this::toFinancialSummary).toList();
                        meetings = boardMeetingRepository
                                        .findAllByTrustIdOrderByMeetingDateDesc(primaryTrust.getId())
                                        .stream().map(this::toBoardMeetingSummary).toList();
                }

                // Employees (max 100 for DC view)
                List<EmployeeResponse> employees = employeeRepository
                                .findAllByTempleId(templeId, PageRequest.of(0, 100))
                                .stream().map(this::toEmployeeResponse).toList();

                // Contractors (max 100 for DC view)
                List<ContractorResponse> contractors = contractorRepository
                                .findAllByTempleId(templeId, PageRequest.of(0, 100))
                                .stream().map(this::toContractorResponse).toList();

                // Declarations (most recent 10, excluding DRAFT — DCs should not see TA workspace drafts)
                List<DeclarationResponse> declarations = declarationRepository
                                .findAllByTempleIdExcludingDraft(templeId, PageRequest.of(0, 10,
                                        Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("versionNumber"), Sort.Order.desc("id"))))
                                .stream().map(d -> toDeclarationResponse(d, showGovernance)).toList();

                // Current approved profile
                TempleFullProfileResponse.ProfileCurrentResponse currentProfile = profileCurrentRepository
                                .findByTempleId(templeId)
                                .map(this::toProfileCurrentResponse)
                                .orElse(null);

                // Latest profile staging (any status) — lets DC distinguish "never submitted"
                // from "recently rejected" without an extra round-trip.
                // Hidden from TEMPLE_AUTHORITY viewing other temples (governance data).
                TempleFullProfileResponse.LatestProfileStagingInfo latestProfileStaging = showGovernance
                                ? profileStagingRepository.findTopByTempleIdOrderByVersionNumberDesc(templeId)
                                        .map(s -> {
                                                WorkflowInstance wi = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, s.getId());
                                                return TempleFullProfileResponse.LatestProfileStagingInfo.builder()
                                                                .stagingId(s.getId())
                                                                .status(wi.getStatus().name())
                                                                .reviewComment(s.getReviewComment())
                                                                .versionNumber(s.getVersionNumber())
                                                                .reviewedAt(s.getReviewedAt())
                                                                .build();
                                        })
                                        .orElse(null)
                                : null;

                log.info("DC full profile loaded: templeId={} showGovernance={}", templeId, showGovernance);

                return TempleFullProfileResponse.builder()
                                .temple(toTempleResponse(temple, showGovernance))
                                .hobliName(hobliName)
                                .talukName(talukName)
                                .districtName(districtName)
                                .cityName(cityName)
                                .trust(trustSummary)
                                .boardMembers(boardMembers)
                                .trustFinancials(financials)
                                .boardMeetings(meetings)
                                .employees(employees)
                                .contractors(contractors)
                                .declarations(declarations)
                                .currentProfile(currentProfile)
                                .latestProfileStaging(latestProfileStaging)
                                .build();
        }

        @Override
        @Transactional(readOnly = true)
        @PreAuthorize(RoleConstants.CAN_READ_TEMPLES)
        public DeclarationDetailResponse getDeclarationDetail(Long declarationId, ScopeHelper.Claims claims) {
                AssetDeclaration d = declarationRepository.findById(declarationId)
                                .orElseThrow(() -> new EntityNotFoundException("AssetDeclaration", declarationId));

                Temple temple = templeRepository.findWithGeoById(d.getTempleId())
                                .orElseThrow(() -> new EntityNotFoundException("Temple", d.getTempleId()));

                List<ClarificationItemResponse> clarifications = clarificationRepository
                                .findAllByDeclarationIdOrderByCreatedAtAsc(declarationId)
                                .stream().map(this::toClarificationResponse).toList();

                log.info("DC declaration detail loaded: declarationId={}", declarationId);

                return DeclarationDetailResponse.builder()
                                .id(d.getId())
                                .templeId(d.getTempleId())
                                .districtId(d.getDistrictId())
                                .financialYear(d.getFinancialYear())
                                .versionNumber(d.getVersionNumber())
                                .status(d.getStatus())
                                .agriculturalLandAcres(d.getAgriculturalLandAcres())
                                .agriculturalLandValue(d.getAgriculturalLandValue())
                                .buildingsSqft(d.getBuildingsSqft())
                                .buildingsValue(d.getBuildingsValue())
                                .leasedPropertiesCount(d.getLeasedPropertiesCount())
                                .leasedPropertiesValue(d.getLeasedPropertiesValue())
                                .otherLandValue(d.getOtherLandValue())
                                .goldGrams(d.getGoldGrams())
                                .silverGrams(d.getSilverGrams())
                                .idolsCount(d.getIdolsCount())
                                .vehiclesCount(d.getVehiclesCount())
                                .financialAssetsValue(d.getFinancialAssetsValue())
                                .otherMovableValue(d.getOtherMovableValue())
                                .submittedAt(d.getSubmittedAt())
                                .reviewedAt(d.getReviewedAt())
                                .acknowledgementNumber(d.getAcknowledgementNumber())
                                .dueDate(d.getDueDate())
                                .clarificationRound(d.getClarificationRound())
                                .overdue(d.isOverdue())
                                .clarifications(clarifications)
                                .agriculturalLands(agriLandRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclImmovAgriLandResponse.builder()
                                                                .id(e.getId())
                                                                .surveyNumber(e.getSurveyNumber())
                                                                .village(e.getLocation())
                                                                .areaAcres(e.getAreaAcres())
                                                                .ownerOfRecord(e.getEncumbrance())
                                                                .pattaStatus(e.getOwnershipType())
                                                                .estimatedValueInr(e.getMarketValue())
                                                                .build())
                                                .toList())
                                .buildings(buildingRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclImmovBuildingResponse.builder()
                                                                .id(e.getId())
                                                                .location(e.getLocation())
                                                                .totalAreaSqft(e.getAreaSqft())
                                                                .yearBuilt(e.getYearOfConstruction())
                                                                .structureType(e.getStructureType())
                                                                .valuationInr(e.getValuation())
                                                                .build())
                                                .toList())
                                .leasedProperties(leasedRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclImmovLeasedResponse.builder()
                                                                .id(e.getId())
                                                                .propertyAddress(e.getLocation())
                                                                .lesseeName(e.getLesseeName())
                                                                .leaseStartDate(e.getLeaseStartDate())
                                                                .leaseEndDate(e.getLeaseExpiry())
                                                                .monthlyRent(e.getMonthlyRent())
                                                                .annualRent(e.getAnnualRent())
                                                                .agreementDocumentId(e.getAgreementDocumentId())
                                                                .build())
                                                .toList())
                                .otherLands(otherImmovRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclImmovOtherResponse.builder()
                                                                .id(e.getId())
                                                                .location(e.getLocation())
                                                                .description(e.getDescription())
                                                                .area(e.getArea())
                                                                .usageType(e.getLandType())
                                                                .revenueDepartmentReference(e.getDocumentReference())
                                                                .estimatedValueInr(e.getValuation())
                                                                .build())
                                                .toList())
                                .preciousMetals(preciousMetalRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclMovPreciousMetalResponse.builder()
                                                                .id(e.getId())
                                                                .itemDescription(e.getItemDescription())
                                                                .metalType(e.getItemType())
                                                                .weightGrams(e.getWeightGrams())
                                                                .purity(e.getPurity())
                                                                .approximateValueInr(e.getEstimatedValue())
                                                                .estimatedValueInr(e.getEstimatedValue())
                                                                .build())
                                                .toList())
                                .artifacts(artifactRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclMovArtifactResponse.builder()
                                                                .id(e.getId())
                                                                .itemDescription(e.getDescription() != null ? e.getDescription() : e.getName())
                                                                .material(e.getMaterial())
                                                                .ageOrPeriod(e.getAgeOrPeriod())
                                                                .provenance(e.getProvenance())
                                                                .museumGradeClassification(e.getMuseumGradeClassification())
                                                                .approximateValueInr(e.getEstimatedValue())
                                                                .estimatedValueInr(e.getEstimatedValue())
                                                                .build())
                                                .toList())
                                .vehicles(vehicleRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclMovVehicleResponse.builder()
                                                                .id(e.getId())
                                                                .registrationNumber(e.getRegistrationNumber())
                                                                .vehicleType(e.getVehicleType())
                                                                .year(e.getYearOfPurchase())
                                                                .estimatedValueInr(e.getCurrentValue())
                                                                .build())
                                                .toList())
                                .equipment(equipmentRepository.findAllByDeclarationId(declarationId)
                                                .stream().map(e -> DeclMovEquipmentResponse.builder()
                                                                .id(e.getId())
                                                                .itemName(e.getDescription())
                                                                .serialNumber(e.getSerialNumber())
                                                                .quantity(e.getQuantity())
                                                                .estimatedValueInr(e.getTotalValue() != null ? e.getTotalValue() : e.getUnitValue())
                                                                .build())
                                                .toList())
                                .build();
        }

        @Override
        @Transactional(readOnly = true)
        @PreAuthorize(RoleConstants.CAN_READ_ALL)
        public ProfileStagingResponse getPendingProfileStaging(Long templeId, ScopeHelper.Claims claims) {
                // Temple existence check only — district scope not required for reads.
                templeRepository.findWithGeoById(templeId)
                                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));

                return profileStagingRepository
                                .findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                                                templeId,
                                                java.util.List.of(
                                                        com.templeregistry.entity.workflow.WorkflowStatus.SUBMITTED,
                                                        com.templeregistry.entity.workflow.WorkflowStatus.UNDER_REVIEW,
                                                        com.templeregistry.entity.workflow.WorkflowStatus.RESUBMITTED))
                                .map(this::toProfileStagingResponse)
                                .orElse(null);
        }

        @Override
        @Transactional(readOnly = true)
        @PreAuthorize(RoleConstants.CAN_READ_TEMPLES)
        public PaginatedResponse<DcProfileHistoryEntry> getProfileHistory(Long templeId, ScopeHelper.Claims claims, int page, int size) {
                templeRepository.findWithGeoById(templeId)
                                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
                int clamped = paginationUtil.clampSize(size);

                // Fetch all workflow instances for TEMPLE_PROFILE entities linked to this temple.
                // This gives one row per version (including re-approvals of the same staging record).
                List<WorkflowInstance> allInstances = workflowInstanceRepository
                                .findByTempleIdAndEntityType(templeId, WorkflowEntityType.TEMPLE_PROFILE);

                // Pre-load staging records by entityId to avoid N+1 for reviewComment/reviewedAt.
                List<Long> stagingIds = allInstances.stream().map(WorkflowInstance::getEntityId)
                                .distinct().collect(Collectors.toList());
                Map<Long, TempleProfileStaging> stagingMap = profileStagingRepository.findAllById(stagingIds)
                                .stream().collect(Collectors.toMap(s -> s.getId(), s -> s));

                List<DcProfileHistoryEntry> entries = allInstances.stream().map(wi -> {
                        TempleProfileStaging s = stagingMap.get(wi.getEntityId());
                        return DcProfileHistoryEntry.builder()
                                        .stagingId(wi.getEntityId())
                                        .versionNumber(wi.getVersionNumber())
                                        .status(wi.getStatus().name())
                                        .submittedAt(wi.getSubmittedAt() != null
                                                        ? LocalDateTime.ofInstant(wi.getSubmittedAt(), ZoneId.systemDefault())
                                                        : null)
                                        .submittedBy(wi.getCreatedByUserId())
                                        .reviewedAt(s != null ? s.getReviewedAt() : null)
                                        .reviewedBy(s != null ? s.getReviewedBy() : null)
                                        .reviewComment(s != null ? s.getReviewComment() : null)
                                        .build();
                }).collect(Collectors.toList());

                // Manual pagination over in-memory list.
                int fromIndex = Math.min(page * clamped, entries.size());
                int toIndex = Math.min(fromIndex + clamped, entries.size());
                List<DcProfileHistoryEntry> pageContent = entries.subList(fromIndex, toIndex);
                Page<DcProfileHistoryEntry> mapped = new PageImpl<>(pageContent, PageRequest.of(page, clamped), entries.size());

                log.info("DC profile history loaded: templeId={} page={} size={} total={}", templeId, page, clamped, entries.size());
                return PaginatedResponse.of(mapped);
        }

        // ─── Mapping helpers ──────────────────────────────────────────────────────

        private TempleResponse toTempleResponse(Temple t, boolean showGovernance) {
                return TempleResponse.builder()
                                .id(t.getId())
                                .registrationNumber(t.getRegistrationNumber())
                                .name(t.getName())
                                .aliasName(t.getAliasName())
                                .grade(t.getGrade())
                                .primaryDeity(t.getPrimaryDeity())
                                .tradition(t.getTradition())
                                .yearEstablished(t.getYearEstablished())
                                .history(t.getHistory())
                                .doorNumber(t.getDoorNumber())
                                .street(t.getStreet())
                                .villageTown(t.getVillageTown())
                                .pinCode(t.getPinCode())
                                .hobliId(t.getHobliId())
                                .talukId(t.getTalukId())
                                .cityId(t.getCityId())
                                .districtId(t.getDistrictId())
                                .latitude(t.getLatitude())
                                .longitude(t.getLongitude())
                                .contactName(t.getContactName())
                                .contactDesignation(t.getContactDesignation())
                                .contactMobile(t.getContactMobile())
                                .contactEmail(t.getContactEmail())
                                .photoUrl(t.getPhotoUrl() != null && !t.getPhotoUrl().isBlank() ? "/api/v1/temples/" + t.getId() + "/profile-photo/serve" : null)
                                .website(t.getWebsite())
                                .languagesOfWorship(t.getLanguagesOfWorship())
                                .linkedInstitutions(t.getLinkedInstitutions())
                                .annualFestivals(t.getAnnualFestivals())
                                .landmark(t.getLandmark())
                                .historicalSignificance(t.getHistoricalSignificance())
                                .bankName(t.getBankName())
                                .bankIfsc(t.getBankIfsc())
                                .trustRegistered(t.isTrustRegistered())
                                .assetDeclarationStatus(t.getAssetDeclarationStatus())
                                .status(t.getStatus() != null ? t.getStatus().name() : null)
                                // Governance fields — only for callers permitted to see oversight data.
                                .verificationStatus(showGovernance && t.getVerificationStatus() != null
                                        ? t.getVerificationStatus().name() : null)
                                .dcRejectionReason(showGovernance ? t.getDcRejectionReason() : null)
                                .build();
        }

        private TempleFullProfileResponse.DcTrustSummary toTrustSummary(Trust t, ScopeHelper.Claims claims, boolean showGovernance) {
                String[] bankParts = splitBankNameAndBranch(t.getBankNameAndBranch());
                List<TrustFinancial> financials = trustFinancialRepository.findAllByTrustIdOrderByFinancialYearDesc(t.getId());
                // Use canonical WorkflowInstance status — NOT systemVerificationStatus which is never set by approveTrust.
                com.templeregistry.dto.response.governance.GovernanceStatusPayload governancePayload =
                        showGovernance
                                ? governanceStatusResolver.resolve(WorkflowEntityType.TRUST, t.getId())
                                : null;
                String workflowStatus = governancePayload != null ? governancePayload.getStatus() : null;
                boolean isVerified = "APPROVED".equals(workflowStatus) || "RE_APPROVED".equals(workflowStatus);
                // SEND_BACK action transitions to CLARIFICATION_REQUESTED in the workflow engine
                boolean isSentBack = "CLARIFICATION_REQUESTED".equals(workflowStatus)
                        || "CLARIFICATION_RESPONDED".equals(workflowStatus);
                String dcFlagReasonValue = (isSentBack && t.getSendBackReason() != null) ? t.getSendBackReason() : null;
                String reviewStatus = isVerified ? "APPROVED" : (isSentBack ? "FLAGGED" : "PENDING");
                return TempleFullProfileResponse.DcTrustSummary.builder()
                                .id(t.getId())
                                .trustName(t.getTrustName())
                                .trustType(t.getTrustType() != null ? t.getTrustType().name() : null)
                                .registrationNumber(t.getTrustRegistrationNumber())
                                .registeringAuthority(t.getRegisteringAuthority())
                                .dateOfRegistration(t.getDateOfRegistration())
                                .panNumberMasked(maskPan(t.getTrustPANNumber(), claims.role()))
                                .bankAccountMasked(maskBankAccount(t.getBankAccountNumber()))
                                .bankName(bankParts[0])
                                .bankBranch(bankParts[1])
                                .annualIncome(t.getAnnualIncome())
                                .dcFlagReason(showGovernance ? dcFlagReasonValue : null)
                                .governanceStatus(governancePayload)
                                .reviewStatus(showGovernance ? reviewStatus : null)
                                .workflowStatus(showGovernance ? workflowStatus : null)
                                .isVerifiedByDc(showGovernance && isVerified)
                                .validationIssues(showGovernance ? buildTrustValidationIssues(t) : List.of())
                                .financialStatus(financials.isEmpty() ? "MISSING" : "SUBMITTED")
                                .build();
        }

        /**
         * PAN masking: AB*****1Z for DC/DC_STAFF; unmasked for SUPER_ADMIN.
         * The stored value is encrypted; we get the plaintext from the entity
         * (decrypted via @Convert).
         */
        private String maskPan(String pan, String role) {
                if (pan == null)
                        return null;
                if (RoleConstants.SUPER_ADMIN.equals(role))
                        return pan;
                if (pan.length() < 2)
                        return "**";
                return pan.substring(0, 2) + "*****" + pan.charAt(pan.length() - 1);
        }

        /**
         * Bank account masking: always **XXXXX{last4} for all roles.
         * Robust against null/empty/whitespace.
         */
        private String maskBankAccount(String account) {
                if (account == null || account.trim().isBlank())
                        return null;
                String trimmed = account.trim();
                if (trimmed.length() <= 4)
                        return "****";
                return "**XXXXX" + trimmed.substring(trimmed.length() - 4);
        }

        private TempleFullProfileResponse.BoardMemberSummary toBoardMemberResponse(BoardMember m) {
                return TempleFullProfileResponse.BoardMemberSummary.builder()
                                .id(m.getId())
                                .fullName(m.getFullName())
                                .maskedAadhaar(m.getMaskedAadhaar())
                                .designation(m.getDesignation())
                                .appointmentDate(m.getAppointmentDate())
                                .tenureEndDate(m.getTenureEndDate())
                                .contactNumber(m.getContactNumber())
                                .address(m.getAddress())
                                .current(trustValidationService.isCurrentMember(m.getTenureEndDate()))
                                .dcFlagReason(null)
                                .build();
        }

        private TempleFullProfileResponse.TrustFinancialSummary toFinancialSummary(TrustFinancial f) {
                return TempleFullProfileResponse.TrustFinancialSummary.builder()
                                .financialYear(f.getFinancialYear())
                                .annualIncome(f.getAnnualIncome())
                                .annualExpenditure(f.getAnnualExpenditure())
                                .build();
        }

        private TempleFullProfileResponse.BoardMeetingSummary toBoardMeetingSummary(BoardMeeting m) {
                return TempleFullProfileResponse.BoardMeetingSummary.builder()
                                .id(m.getId())
                                .meetingDate(m.getMeetingDate())
                                .agenda(m.getAgenda())
                                .minutesDocumentId(m.getMinutesDocumentId())
                                .createdAt(m.getCreatedAt())
                                .build();
        }

        private EmployeeResponse toEmployeeResponse(Employee e) {
                return EmployeeResponse.builder()
                                .id(e.getId())
                                .templeId(e.getTempleId())
                                .employeeRef(e.getEmployeeRef())
                                .fullName(e.getFullName())
                                .employeeType(e.getEmployeeType())
                                .designation(e.getDesignation())
                                .dateOfJoining(e.getDateOfJoining())
                                .salaryGrade(e.getSalaryGrade())
                                .mobile(e.getMobile())
                                .address(e.getAddress())
                                .status(e.getStatus())
                                .hereditary(e.getHereditary())
                                .build();
        }

        private ContractorResponse toContractorResponse(Contractor c) {
                return ContractorResponse.builder()
                                .id(c.getId())
                                .templeId(c.getTempleId())
                                .companyName(c.getCompanyName())
                                .gstNumber(c.getGstNumber())
                                .serviceType(c.getServiceType())
                                .contractReference(c.getContractReference())
                                .workOrderDate(c.getWorkOrderDate())
                                .contractStartDate(c.getContractStartDate())
                                .contractEndDate(c.getContractEndDate())
                                .contractValue(c.getContractValue())
                                .paymentStatus(c.getPaymentStatus())
                                .documentIds(c.getDocumentIdList())
                                .build();
        }

        private DeclarationResponse toDeclarationResponse(AssetDeclaration d, boolean showGovernance) {
                return DeclarationResponse.builder()
                                .id(d.getId())
                                .templeId(d.getTempleId())
                                .districtId(d.getDistrictId())
                                .status(d.getStatus())
                                .agriculturalLandAcres(d.getAgriculturalLandAcres())
                                .agriculturalLandValue(d.getAgriculturalLandValue())
                                .buildingsSqft(d.getBuildingsSqft())
                                .buildingsValue(d.getBuildingsValue())
                                .leasedPropertiesCount(d.getLeasedPropertiesCount())
                                .leasedPropertiesValue(d.getLeasedPropertiesValue())
                                .otherLandValue(d.getOtherLandValue())
                                .goldGrams(d.getGoldGrams())
                                .silverGrams(d.getSilverGrams())
                                .idolsCount(d.getIdolsCount())
                                .vehiclesCount(d.getVehiclesCount())
                                .financialAssetsValue(d.getFinancialAssetsValue())
                                .otherMovableValue(d.getOtherMovableValue())
                                .submittedAt(d.getSubmittedAt())
                                .reviewedAt(d.getReviewedAt())
                                .acknowledgementNumber(d.getAcknowledgementNumber())
                                .dueDate(d.getDueDate())
                                .governanceStatus(showGovernance
                                        ? governanceStatusResolver.resolve(WorkflowEntityType.DECLARATION, d.getId())
                                        : null)
                                .build();
        }

        private TempleFullProfileResponse.ProfileCurrentResponse toProfileCurrentResponse(TempleProfileCurrent p) {
                return TempleFullProfileResponse.ProfileCurrentResponse.builder()
                                .phone(p.getPhone())
                                .email(p.getEmail())
                                .website(p.getWebsite())
                                .contactPersonName(p.getContactPersonName())
                                .contactPersonDesignation(p.getContactPersonDesignation())
                                .photoUrl(p.getPhotoFilePath() != null && !p.getPhotoFilePath().isBlank() ? "/api/v1/temples/" + p.getTempleId() + "/profile-photo/serve" : null)
                                .bankName(p.getBankName())
                                .bankAccountMasked(maskBankAccount(p.getBankAccountNumberEncrypted()))
                                .bankIfsc(p.getBankIfsc())
                                .languagesOfWorship(p.getLanguagesOfWorship())
                                .linkedInstitutions(p.getLinkedInstitutions())
                                .description(p.getDescription())
                                .annualFestivals(p.getAnnualFestivals())
                                .landmark(p.getLandmark())
                                .historicalSignificance(p.getHistoricalSignificance())
                                .build();
        }

        private ClarificationItemResponse toClarificationResponse(DeclarationClarification c) {
                return ClarificationItemResponse.builder()
                                .id(c.getId())
                                .direction(c.getDirection().name())
                                .message(c.getMessage())
                                .sectionName(c.getSectionName())
                                .fieldNamesJson(c.getFieldNamesJson())
                                .authorId(c.getAuthorId())
                                .createdAt(c.getCreatedAt())
                                .build();
        }

        private ProfileStagingResponse toProfileStagingResponse(TempleProfileStaging s) {
                WorkflowInstance instance = workflowEngine.getState(WorkflowEntityType.TEMPLE_PROFILE, s.getId());
                return ProfileStagingResponse.builder()
                                .id(s.getId())
                                .templeId(s.getTempleId())
                                .version(instance.getVersionNumber())
                                .status(instance.getStatus().name())
                                .governanceStatus(governanceStatusResolver.resolve(WorkflowEntityType.TEMPLE_PROFILE, s.getId()))
                                .contactPersonName(s.getContactPersonName())
                                .contactPersonDesignation(s.getContactPersonDesignation())
                                .phone(s.getPhone())
                                .email(s.getEmail())
                                .website(s.getWebsite())
                                .photoUrl(s.getPhotoFilePath() != null && !s.getPhotoFilePath().isBlank() ? "/api/v1/temples/" + s.getTempleId() + "/profile-photo/serve" : null)
                                .bankName(s.getBankName())
                                .bankAccountNumberMasked(maskBankAccount(s.getBankAccountNumberEncrypted()))
                                .bankIfsc(s.getBankIfsc())
                                .languagesOfWorship(s.getLanguagesOfWorship())
                                .linkedInstitutions(s.getLinkedInstitutions())
                                .description(s.getDescription())
                                .annualFestivals(s.getAnnualFestivals())
                                .landmark(s.getLandmark())
                                .historicalSignificance(s.getHistoricalSignificance())
                                .aliasName(s.getAliasName())
                                .primaryDeity(s.getPrimaryDeity())
                                .grade(s.getGrade())
                                .tradition(s.getTradition())
                                .hobliId(s.getHobliId())
                                .talukId(s.getHobliId() != null ? hobliRepository.findTalukIdById(s.getHobliId()).orElse(null) : null)
                                .addressLine1(s.getAddressLine1())
                                .pinCode(s.getPinCode())
                                .latitude(s.getLatitude())
                                .longitude(s.getLongitude())
                                .yearEstablished(s.getYearEstablished())
                                .submittedAt(instance.getSubmittedAt() != null ? java.time.LocalDateTime.ofInstant(instance.getSubmittedAt(), java.time.ZoneId.systemDefault()) : null)
                                .submittedBy(instance.getCreatedBy())
                                .reviewComment(s.getReviewComment())
                                .build();
        }

        private List<String> buildTrustValidationIssues(Trust trust) {
                return Stream.of(
                                trust.getDateOfRegistration() != null && trust.getDateOfRegistration().isAfter(java.time.LocalDate.now())
                                                ? "Registration date is in the future" : null,
                                isBlank(trust.getRegisteringAuthority()) ? "Registering authority is missing" : null,
                                isBlank(trust.getTrustPANNumber()) ? "PAN is missing" : null,
                                isBlank(trust.getBankAccountNumber()) ? "Bank account number is missing" : null,
                                isBlank(splitBankNameAndBranch(trust.getBankNameAndBranch())[1]) ? "Bank branch is missing" : null)
                                .filter(Objects::nonNull)
                                .toList();
        }

        private List<String> buildBoardMemberValidationIssues(List<TempleFullProfileResponse.BoardMemberSummary> members) {
                long missingAddress = members.stream().filter(member -> isBlank(member.getAddress())).count();
                if (missingAddress == 0) {
                        return List.of();
                }
                return List.of(missingAddress + " board member record(s) are missing address details.");
        }

        private String[] splitBankNameAndBranch(String bankNameAndBranch) {
                if (bankNameAndBranch == null || bankNameAndBranch.isBlank()) {
                        return new String[] {null, null};
                }
                String[] parts = bankNameAndBranch.split("\\|\\|", 2);
                if (parts.length == 2) {
                        return parts;
                }
                return new String[] {bankNameAndBranch, null};
        }

        private boolean isBlank(String value) {
                return value == null || value.trim().isBlank();
        }
}

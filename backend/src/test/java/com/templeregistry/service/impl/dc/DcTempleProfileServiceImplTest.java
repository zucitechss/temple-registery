package com.templeregistry.service.impl.dc;

import com.templeregistry.dto.response.dc.TempleFullProfileResponse;
import com.templeregistry.dto.response.governance.GovernanceStatusPayload;
import com.templeregistry.entity.geo.City;
import com.templeregistry.entity.geo.District;
import com.templeregistry.entity.geo.Hobli;
import com.templeregistry.entity.geo.Taluk;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleGrade;
import com.templeregistry.entity.temple.TempleSearchSummary;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.entity.trust.Trust;
import com.templeregistry.entity.trust.TrustStatus;
import com.templeregistry.entity.trust.TrustType;
import com.templeregistry.entity.workflow.WorkflowEntityType;
import com.templeregistry.entity.workflow.WorkflowStatus;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.contractor.ContractorRepository;
import com.templeregistry.repository.dc.*;
import com.templeregistry.repository.declaration.DeclarationClarificationRepository;
import com.templeregistry.repository.geo.DistrictRepository;
import com.templeregistry.repository.declaration.DeclarationRepository;
import com.templeregistry.repository.employee.EmployeeRepository;
import com.templeregistry.repository.geo.CityRepository;
import com.templeregistry.repository.geo.HobliRepository;
import com.templeregistry.entity.temple.TempleProfileStaging;
import com.templeregistry.repository.temple.TempleProfileStagingRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.temple.TempleSearchSummaryRepository;
import com.templeregistry.repository.trust.BoardMemberRepository;
import com.templeregistry.repository.trust.BoardMeetingRepository;
import com.templeregistry.repository.trust.TrustFinancialRepository;
import com.templeregistry.repository.trust.TrustRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.document.FileStorageService;
import com.templeregistry.service.governance.GovernanceStatusResolver;
import com.templeregistry.service.governance.TempleVisibilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class DcTempleProfileServiceImplTest {

    // â”€â”€ Repositories â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Mock private TempleRepository templeRepository;
    @Mock private TempleSearchSummaryRepository summaryRepository;
    @Mock private TrustRepository trustRepository;
    @Mock private BoardMemberRepository boardMemberRepository;
    @Mock private BoardMeetingRepository boardMeetingRepository;
    @Mock private TrustFinancialRepository trustFinancialRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ContractorRepository contractorRepository;
    @Mock private DeclarationRepository declarationRepository;
    @Mock private DeclarationClarificationRepository clarificationRepository;
    @Mock private TempleProfileCurrentRepository profileCurrentRepository;
    @Mock private TempleProfileStagingRepository profileStagingRepository;
    @Mock private DeclImmovAgriLandRepository agriLandRepository;
    @Mock private DeclImmovBuildingRepository buildingRepository;
    @Mock private DeclImmovLeasedRepository leasedRepository;
    @Mock private DeclImmovOtherRepository otherImmovRepository;
    @Mock private DeclMovPreciousMetalRepository preciousMetalRepository;
    @Mock private DeclMovArtifactRepository artifactRepository;
    @Mock private DeclMovVehicleRepository vehicleRepository;
    @Mock private DeclMovEquipmentRepository equipmentRepository;
    @Mock private CityRepository cityRepository;
    @Mock private DistrictRepository districtRepository;
    @Mock private HobliRepository hobliRepository;
    @Mock private JurisdictionGuard jurisdictionGuard;
    @Mock private FileStorageService fileStorageService;
    @Mock private GovernanceStatusResolver governanceStatusResolver;
    @Mock private TempleVisibilityPolicy visibilityPolicy;

    @InjectMocks
    private DcTempleProfileServiceImpl service;

    // â”€â”€ Shared test data â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private final ScopeHelper.Claims SUPER_ADMIN_CLAIMS =
            new ScopeHelper.Claims(1L, RoleConstants.SUPER_ADMIN, null, null, "admin", "EDIT");

    private final ScopeHelper.Claims DC_CLAIMS =
            new ScopeHelper.Claims(2L, RoleConstants.DISTRICT_COLLECTOR, 10L, null, "dc", "EDIT");

    // â”€â”€ Helper builders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Temple templeWithFullGeo() {
        District district = District.builder().name("Mysuru").build();
        district.setId(10L);

        Taluk taluk = Taluk.builder().district(district).name("Mysuru Taluk").build();
        taluk.setId(20L);

        Hobli hobli = Hobli.builder().taluk(taluk).name("Alanahalli Hobli").build();
        hobli.setId(30L);

        return Temple.builder()
                .registrationNumber("KA-MYS-001")
                .name("Sri Chamundeshwari Temple")
                .grade(TempleGrade.A)
                .primaryDeity("Chamundeshwari")
                .hobliId(30L)
                .hobli(hobli)
                .talukId(20L)
                .districtId(10L)
                .status(TempleStatus.ACTIVE)
                .build();
    }

    private Temple templeWithoutHobli() {
        return Temple.builder()
                .registrationNumber("KA-MYS-002")
                .name("Sri Venkataramana Temple")
                .grade(TempleGrade.B)
                .primaryDeity("Venkataramana")
                .hobliId(null)
                .hobli(null)
                .districtId(10L)
                .status(TempleStatus.ACTIVE)
                .build();
    }

    private Temple templeWithHobliButNoTaluk() {
        Hobli hobliNoTaluk = Hobli.builder().name("Orphan Hobli").build();
        hobliNoTaluk.setId(99L);

        return Temple.builder()
                .registrationNumber("KA-MYS-003")
                .name("Sri Someswara Temple")
                .grade(TempleGrade.C)
                .primaryDeity("Someswara")
                .hobliId(99L)
                .hobli(hobliNoTaluk)
                .districtId(10L)
                .status(TempleStatus.ACTIVE)
                .build();
    }

    private Temple templeWithTalukButNoDistrict() {
        Taluk talukNoDistrict = Taluk.builder().name("Orphan Taluk").build();
        talukNoDistrict.setId(88L);

        Hobli hobli = Hobli.builder().taluk(talukNoDistrict).name("Good Hobli").build();
        hobli.setId(77L);

        return Temple.builder()
                .registrationNumber("KA-MYS-004")
                .name("Sri Brahmi Temple")
                .grade(TempleGrade.B)
                .primaryDeity("Brahmi")
                .hobliId(77L)
                .hobli(hobli)
                .districtId(10L)
                .status(TempleStatus.ACTIVE)
                .build();
    }

    private void stubMinimumForGetFullProfile(Temple temple) {
        when(templeRepository.findWithGeoById(temple.getId())).thenReturn(Optional.of(temple));
        when(trustRepository.findAllByTempleId(temple.getId())).thenReturn(List.of());
        when(employeeRepository.findAllByTempleId(eq(temple.getId()), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(contractorRepository.findAllByTempleId(eq(temple.getId()), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(declarationRepository.findAllByTempleIdExcludingDraft(eq(temple.getId()), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(profileCurrentRepository.findByTempleId(temple.getId())).thenReturn(Optional.empty());
    }

    // â”€â”€ Test: full geo data â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_returnFullProfile_when_templeHasCompleteGeoAndCityData() {
        Temple temple = templeWithFullGeo();
        temple.setId(1L);
        stubMinimumForGetFullProfile(temple);

        City city = City.builder().name("Mysuru Division").build();
        TempleSearchSummary summary = TempleSearchSummary.builder()
                .templeId(1L).cityId(5L).build();
        when(summaryRepository.findByTempleId(1L)).thenReturn(Optional.of(summary));
        when(cityRepository.findById(5L)).thenReturn(Optional.of(city));

        TempleFullProfileResponse result = service.getFullProfile(1L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getHobliName()).isEqualTo("Alanahalli Hobli");
        assertThat(result.getTalukName()).isEqualTo("Mysuru Taluk");
        assertThat(result.getDistrictName()).isEqualTo("Mysuru");
        assertThat(result.getCityName()).isEqualTo("Mysuru Division");
        assertThat(result.getTemple()).isNotNull();
        assertThat(result.getTemple().getName()).isEqualTo("Sri Chamundeshwari Temple");
    }

    @Test
    void should_allowCrossDistrictRead_when_roleIsDc() {
        // DC has statewide read access — assertDistrictScope must NOT be called on profile reads.
        // District scope is only enforced on governance actions (approve, reject, verify, flag).
        Temple temple = templeWithFullGeo();
        temple.setId(7L);
        stubMinimumForGetFullProfile(temple);
        when(summaryRepository.findByTempleId(7L)).thenReturn(Optional.empty());

        service.getFullProfile(7L, DC_CLAIMS);

        verify(jurisdictionGuard, never()).assertDistrictScope(any(), any());
    }

    // â”€â”€ Test: hobli is null (partial geo) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_returnPartialProfile_when_hobliIsNull() {
        Temple temple = templeWithoutHobli();
        temple.setId(2L);
        stubMinimumForGetFullProfile(temple);
        when(summaryRepository.findByTempleId(2L)).thenReturn(Optional.empty());

        TempleFullProfileResponse result = service.getFullProfile(2L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getHobliName()).isNull();
        assertThat(result.getTalukName()).isNull();
        assertThat(result.getDistrictName()).isNull();
        assertThat(result.getCityName()).isNull();
        assertThat(result.getTemple()).isNotNull();
    }

    // â”€â”€ Test: hobli present, taluk is null â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_returnPartialProfile_when_talukIsNull() {
        Temple temple = templeWithHobliButNoTaluk();
        temple.setId(3L);
        stubMinimumForGetFullProfile(temple);
        when(summaryRepository.findByTempleId(3L)).thenReturn(Optional.empty());

        TempleFullProfileResponse result = service.getFullProfile(3L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getHobliName()).isEqualTo("Orphan Hobli");
        assertThat(result.getTalukName()).isNull();
        assertThat(result.getDistrictName()).isNull();
    }

    // â”€â”€ Test: taluk present, district is null â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_returnPartialProfile_when_districtIsNull() {
        Temple temple = templeWithTalukButNoDistrict();
        temple.setId(4L);
        stubMinimumForGetFullProfile(temple);
        when(summaryRepository.findByTempleId(4L)).thenReturn(Optional.empty());

        TempleFullProfileResponse result = service.getFullProfile(4L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getHobliName()).isEqualTo("Good Hobli");
        assertThat(result.getTalukName()).isEqualTo("Orphan Taluk");
        assertThat(result.getDistrictName()).isNull();
    }

    // â”€â”€ Test: null city_id in search summary (the primary 500 bug) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_returnProfileWithNullCityName_when_cityIdIsNullInSearchSummary() {
        Temple temple = templeWithFullGeo();
        temple.setId(5L);
        stubMinimumForGetFullProfile(temple);

        // Summary row exists but city_id is NULL â€” this caused IllegalArgumentException before the fix
        TempleSearchSummary summaryWithNullCity = TempleSearchSummary.builder()
                .templeId(5L)
                .cityId(null)
                .build();
        when(summaryRepository.findByTempleId(5L)).thenReturn(Optional.of(summaryWithNullCity));

        // Should NOT throw â€” cityRepository.findById must never be called with null
        TempleFullProfileResponse result = service.getFullProfile(5L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getCityName()).isNull();
        assertThat(result.getTemple()).isNotNull();
    }

    // â”€â”€ Test: no search summary row at all â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_returnProfileWithNullCityName_when_searchSummaryAbsent() {
        Temple temple = templeWithFullGeo();
        temple.setId(6L);
        stubMinimumForGetFullProfile(temple);
        when(summaryRepository.findByTempleId(6L)).thenReturn(Optional.empty());

        TempleFullProfileResponse result = service.getFullProfile(6L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getCityName()).isNull();
    }

    // â”€â”€ Test: temple not found â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_throwEntityNotFoundException_when_templeDoesNotExist() {
        when(templeRepository.findWithGeoById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFullProfile(999L, SUPER_ADMIN_CLAIMS))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void should_returnNullPendingProfile_when_onlyRejectedHistoryExists() {
        Temple temple = templeWithFullGeo();
        temple.setId(11L);
        when(templeRepository.findWithGeoById(11L)).thenReturn(Optional.of(temple));
        when(profileStagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(11L),
                eq(List.of(WorkflowStatus.SUBMITTED, WorkflowStatus.UNDER_REVIEW, WorkflowStatus.RESUBMITTED))))
                .thenReturn(Optional.empty());

        assertThat(service.getPendingProfileStaging(11L, DC_CLAIMS)).isNull();

        verify(profileStagingRepository).findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(11L),
                eq(List.of(WorkflowStatus.SUBMITTED, WorkflowStatus.UNDER_REVIEW, WorkflowStatus.RESUBMITTED)));
        verify(profileStagingRepository, never()).findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(11L),
                eq(List.of(WorkflowStatus.REJECTED)));
    }

    // â”€â”€ Test: DcTrustSummary canonical governanceStatus â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void should_includeCanonicalGovernanceStatus_when_trustIsSummarised() {
        Temple temple = templeWithFullGeo();
        temple.setId(10L);

        Trust trust = Trust.builder()
                .trustName("Test Trust")
                .trustRegistrationNumber("TRN-TEST")
                .dateOfRegistration(java.time.LocalDate.now())
                .registeringAuthority("Test Authority")
                .trustType(TrustType.SINGLE_TRUSTEE)
                .trustPANNumber("AABCT1234Z")
                .bankAccountNumber("1234567890")
                .bankNameAndBranch("SBI, Test Branch")
                .status(TrustStatus.ACTIVE)
                .templeId(10L)
                .build();
        trust.setId(200L);

        GovernanceStatusPayload payload = GovernanceStatusPayload.builder()
                .status("SUBMITTED")
                .workflowInstanceId(300L)
                .label("Submitted")
                .severity("INFO")
                .actionableBy("DC")
                .build();

        when(templeRepository.findWithGeoById(10L)).thenReturn(Optional.of(temple));
        when(trustRepository.findAllByTempleId(10L)).thenReturn(List.of(trust));
        when(governanceStatusResolver.resolve(WorkflowEntityType.TRUST, 200L)).thenReturn(payload);
        when(visibilityPolicy.canViewGovernance(SUPER_ADMIN_CLAIMS, 10L)).thenReturn(true);
        when(trustFinancialRepository.findAllByTrustIdOrderByFinancialYearDesc(200L)).thenReturn(List.of());
        when(boardMeetingRepository.findAllByTrustIdOrderByMeetingDateDesc(200L)).thenReturn(List.of());
        when(boardMemberRepository.findAllByTrustIdOrderByAppointmentDateDescIdDesc(200L)).thenReturn(List.of());
        when(employeeRepository.findAllByTempleId(eq(10L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(contractorRepository.findAllByTempleId(eq(10L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(declarationRepository.findAllByTempleIdExcludingDraft(eq(10L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(profileCurrentRepository.findByTempleId(10L)).thenReturn(Optional.empty());
        when(summaryRepository.findByTempleId(10L)).thenReturn(Optional.empty());

        TempleFullProfileResponse result = service.getFullProfile(10L, SUPER_ADMIN_CLAIMS);

        TempleFullProfileResponse.DcTrustSummary summary = result.getTrust();
        assertThat(summary).isNotNull();
        assertThat(summary.getGovernanceStatus()).isNotNull();
        assertThat(summary.getGovernanceStatus().getStatus()).isEqualTo("SUBMITTED");
        assertThat(summary.getGovernanceStatus().getWorkflowInstanceId()).isEqualTo(300L);
        // Legacy fields still populated for backward compatibility
        assertThat(summary.getWorkflowStatus()).isEqualTo("SUBMITTED");
        assertThat(summary.getReviewStatus()).isEqualTo("PENDING");
        assertThat(summary.isVerifiedByDc()).isFalse();
    }

    // ── Test: geo fallback from pending staging (the fix for first-time submissions) ──

    @Test
    void should_resolveGeoNamesFromPendingStaging_when_templeHobliIsNullAndStagingIsSubmitted() {
        // Scenario: TA submits profile for the first time. Temple entity has no hobliId yet
        // (promoteToTemple only writes it on approval). The pending staging carries hobliId.
        // DC must see correct City, Hobli, Taluk, District before approving.
        Temple temple = templeWithoutHobli();
        temple.setId(20L);
        stubMinimumForGetFullProfile(temple);
        when(summaryRepository.findByTempleId(20L)).thenReturn(Optional.empty());

        City city = City.builder().name("Mysuru Division").build();
        District district = District.builder().name("Mysuru").city(city).build();
        district.setId(10L);
        Taluk taluk = Taluk.builder().district(district).name("Mysuru Taluk").build();
        taluk.setId(20L);
        Hobli stagingHobli = Hobli.builder().taluk(taluk).name("Alanahalli Hobli").build();
        stagingHobli.setId(30L);

        TempleProfileStaging staging = TempleProfileStaging.builder()
                .templeId(20L)
                .hobliId(30L)
                .build();

        when(profileStagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                eq(20L),
                eq(List.of(WorkflowStatus.SUBMITTED, WorkflowStatus.UNDER_REVIEW, WorkflowStatus.RESUBMITTED))))
                .thenReturn(Optional.of(staging));
        when(hobliRepository.findWithGeoById(30L)).thenReturn(Optional.of(stagingHobli));

        TempleFullProfileResponse result = service.getFullProfile(20L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getHobliName()).isEqualTo("Alanahalli Hobli");
        assertThat(result.getTalukName()).isEqualTo("Mysuru Taluk");
        assertThat(result.getDistrictName()).isEqualTo("Mysuru");
        assertThat(result.getCityName()).isEqualTo("Mysuru Division");
    }

    @Test
    void should_notInvokeStagingFallback_when_templeHobliIsAlreadyPopulated() {
        // Regression: when the temple entity already has hobliId set (post-approval state),
        // geo names must come from the temple's own hobli chain and the staging fallback
        // must not be entered.
        Temple temple = templeWithFullGeo();
        temple.setId(21L);
        stubMinimumForGetFullProfile(temple);

        City city = City.builder().name("Mysuru Division").build();
        TempleSearchSummary summary = TempleSearchSummary.builder()
                .templeId(21L).cityId(5L).build();
        when(summaryRepository.findByTempleId(21L)).thenReturn(Optional.of(summary));
        when(cityRepository.findById(5L)).thenReturn(Optional.of(city));

        TempleFullProfileResponse result = service.getFullProfile(21L, SUPER_ADMIN_CLAIMS);

        assertThat(result.getHobliName()).isEqualTo("Alanahalli Hobli");
        assertThat(result.getTalukName()).isEqualTo("Mysuru Taluk");
        assertThat(result.getDistrictName()).isEqualTo("Mysuru");
        assertThat(result.getCityName()).isEqualTo("Mysuru Division");
        // Fallback must not be triggered — hobliRepository is never consulted
        verify(hobliRepository, never()).findWithGeoById(any());
    }
}

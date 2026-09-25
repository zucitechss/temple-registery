package com.templeregistry.service.impl.temple;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.temple.*;
import com.templeregistry.dto.response.temple.*;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TemplePhoto;
import com.templeregistry.entity.temple.TempleProfileStaging;
import com.templeregistry.entity.temple.TempleSearchSummary;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.exception.DuplicateResourceException;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.FileValidationException;
import com.templeregistry.mapper.temple.TempleMapper;
import com.templeregistry.repository.temple.TemplePhotoRepository;
import com.templeregistry.repository.temple.TempleProfileStagingRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.temple.TempleSearchSummaryRepository;
import com.templeregistry.repository.geo.DistrictRepository;
import com.templeregistry.repository.geo.HobliRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.OwnershipGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.document.FileStorageService;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.service.temple.TempleService;
import com.templeregistry.util.PaginationUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ByteArrayResource;

@Service
@RequiredArgsConstructor
@Slf4j
public class TempleServiceImpl implements TempleService {

    /**
     * VAL-006: "Temple profile photograph | JPEG or PNG only; max 5 MB; enforced client +
     * server".
     *
     * <p>Server-side enforcement was previously absent — only the browser checked — and was
     * partly masked by Boot's undeclared 1 MB multipart default. Now that H-4 raises that
     * default to 10 MB so documents can reach their VAL-005 size, photos need their own,
     * smaller check: these bytes are read into a byte[] and persisted into a database
     * column.</p>
     */
    private static final long MAX_PHOTO_SIZE_BYTES = 5 * 1024 * 1024L; // 5 MB (VAL-006)
    private static final Set<String> ALLOWED_PHOTO_MIME = Set.of("image/jpeg", "image/png");

    private final TempleRepository templeRepository;
    private final TempleSearchSummaryRepository summaryRepository;
    private final TempleMapper templeMapper;
    private final TemplePhotoRepository templePhotoRepository;
    private final TempleProfileStagingRepository stagingRepository;
    private final DistrictRepository districtRepository;
    private final HobliRepository hobliRepository;
    private final FileStorageService fileStorageService;
    private final PaginationUtil paginationUtil;
    private final JurisdictionGuard jurisdictionGuard;
    private final OwnershipGuard ownershipGuard;
    private final TempleSearchSummaryService summaryService;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("permitAll()")
    public PaginatedResponse<TempleSearchResultResponse> search(TempleSearchFilterRequest filter) {
        int size = paginationUtil.clampSize(filter.getSize());
        Long scopedDistrictId = jurisdictionGuard.enforceDistrictId(filter.getDistrictId());

        Specification<TempleSearchSummary> spec = buildSpec(filter, scopedDistrictId);

        Sort sort = Sort.by(Sort.Direction.ASC, "name");
        Page<TempleSearchSummary> page = summaryRepository.findAll(
            spec, PageRequest.of(filter.getPage(), size, sort));

        Page<TempleSearchResultResponse> mapped = page.map(summary -> {
            TempleSearchResultResponse dto = templeMapper.toSearchResult(summary);
            if (dto.getPhotoUrl() != null && !dto.getPhotoUrl().isBlank()) {
            dto = TempleSearchResultResponse.builder()
                .id(dto.getId())
                .registrationNumber(dto.getRegistrationNumber())
                .name(dto.getName())
                .grade(dto.getGrade())
                .primaryDeity(dto.getPrimaryDeity())
                .tradition(dto.getTradition())
                .districtId(dto.getDistrictId())
                .trustRegistered(dto.isTrustRegistered())
                .assetDeclarationStatus(dto.getAssetDeclarationStatus())
                .photoUrl("/api/v1/temples/" + dto.getId() + "/profile-photo/serve")
                .build();
            }
            return dto;
        });
        return PaginatedResponse.of(mapped);
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_SUBMIT)
    @Transactional
    public TempleResponse create(CreateTempleRequest request) {
        if (templeRepository.existsByRegistrationNumber(request.getRegistrationNumber())) {
            throw new DuplicateResourceException(
                    "Temple with registration number [" + request.getRegistrationNumber() + "] already exists.");
        }
        Temple temple = templeMapper.fromCreateRequest(request);
        Temple saved = templeRepository.save(temple);
        summaryService.scheduleRefresh(saved.getId());
        log.info("Temple created: id=[{}], reg=[{}]", saved.getId(), saved.getRegistrationNumber());
        return templeMapper.toTempleResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public TempleResponse getById(Long id) {
        Temple temple = findOrThrow(id);
        jurisdictionGuard.assertSameDistrict(temple.getDistrictId());
        ownershipGuard.assertOwnsTemple(id);
        TempleResponse dto = enrichTempleResponse(templeMapper.toTempleResponse(temple));
        if (dto.getPhotoUrl() != null && !dto.getPhotoUrl().isBlank()) {
            dto = TempleResponse.builder()
                .id(dto.getId())
                .registrationNumber(dto.getRegistrationNumber())
                .name(dto.getName())
                .aliasName(dto.getAliasName())
                .grade(dto.getGrade())
                .primaryDeity(dto.getPrimaryDeity())
                .tradition(dto.getTradition())
                .yearEstablished(dto.getYearEstablished())
                .history(dto.getHistory())
                .doorNumber(dto.getDoorNumber())
                .street(dto.getStreet())
                .villageTown(dto.getVillageTown())
                .pinCode(dto.getPinCode())
                .hobliId(dto.getHobliId())
                .talukId(dto.getTalukId())
                .cityId(dto.getCityId())
                .cityName(dto.getCityName())
                .districtId(dto.getDistrictId())
                .districtName(dto.getDistrictName())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .contactName(dto.getContactName())
                .contactDesignation(dto.getContactDesignation())
                .contactMobile(dto.getContactMobile())
                .contactEmail(dto.getContactEmail())
                .photoUrl("/api/v1/temples/" + id + "/profile-photo/serve")
                .website(dto.getWebsite())
                .languagesOfWorship(dto.getLanguagesOfWorship())
                .linkedInstitutions(dto.getLinkedInstitutions())
                .annualFestivals(dto.getAnnualFestivals())
                .landmark(dto.getLandmark())
                .historicalSignificance(dto.getHistoricalSignificance())
                .bankName(dto.getBankName())
                .bankIfsc(dto.getBankIfsc())
                .trustRegistered(dto.isTrustRegistered())
                .assetDeclarationStatus(dto.getAssetDeclarationStatus())
                .status(dto.getStatus())
                .verificationStatus(dto.getVerificationStatus())
                .dcRejectionReason(dto.getDcRejectionReason())
                .build();
        }
        return dto;
    }

    @Override
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public TempleResponse update(Long id, UpdateTempleRequest request) {
        Temple temple = findOrThrow(id);
        ownershipGuard.assertOwnsTemple(id);
        applyUpdates(temple, request);
        Temple saved = templeRepository.save(temple);
        summaryService.scheduleRefresh(saved.getId());
        log.info("Temple updated: id=[{}]", saved.getId());
        return templeMapper.toTempleResponse(saved);
    }

    private Temple findOrThrow(Long id) {
        return templeRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Temple", id));
    }

    private void applyUpdates(Temple temple, UpdateTempleRequest rq) {
        if (rq.getName() != null)               temple.setName(rq.getName());
        if (rq.getAliasName() != null)          temple.setAliasName(rq.getAliasName());
        if (rq.getGrade() != null)              temple.setGrade(rq.getGrade());
        if (rq.getPrimaryDeity() != null)       temple.setPrimaryDeity(rq.getPrimaryDeity());
        if (rq.getTradition() != null)          temple.setTradition(com.templeregistry.entity.temple.ReligiousTradition.valueOf(rq.getTradition()));
        if (rq.getYearEstablished() != null)    temple.setYearEstablished(rq.getYearEstablished());
        if (rq.getHistory() != null)            temple.setHistory(rq.getHistory());
        if (rq.getDoorNumber() != null)         temple.setDoorNumber(rq.getDoorNumber());
        if (rq.getStreet() != null)             temple.setStreet(rq.getStreet());
        if (rq.getVillageTown() != null)        temple.setVillageTown(rq.getVillageTown());
        if (rq.getPinCode() != null)            temple.setPinCode(rq.getPinCode());
        if (rq.getHobliId() != null)            temple.setHobliId(rq.getHobliId());
        if (rq.getTalukId() != null)            temple.setTalukId(rq.getTalukId());
        if (rq.getLatitude() != null)           temple.setLatitude(rq.getLatitude());
        if (rq.getLongitude() != null)          temple.setLongitude(rq.getLongitude());
        if (rq.getContactName() != null)        temple.setContactName(rq.getContactName());
        if (rq.getContactDesignation() != null) temple.setContactDesignation(rq.getContactDesignation());
        if (rq.getContactMobile() != null)      temple.setContactMobile(rq.getContactMobile());
        if (rq.getContactEmail() != null)       temple.setContactEmail(rq.getContactEmail());
        if (rq.getLanguagesOfWorship() != null) temple.setLanguagesOfWorship(rq.getLanguagesOfWorship());
    }

    private Specification<TempleSearchSummary> buildSpec(TempleSearchFilterRequest f, Long districtId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (districtId != null) predicates.add(cb.equal(root.get("districtId"), districtId));
            if (f.getTalukId() != null) predicates.add(cb.equal(root.get("talukId"), f.getTalukId()));
            if (f.getHobliId() != null) predicates.add(cb.equal(root.get("hobliId"), f.getHobliId()));
            if (f.getGrade() != null && !f.getGrade().isEmpty())
                predicates.add(root.get("grade").in(f.getGrade()));
            if (f.getKeyword() != null && !f.getKeyword().isBlank())
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + f.getKeyword().toLowerCase() + "%"));
            if (f.getDeityName() != null && !f.getDeityName().isBlank())
                predicates.add(cb.like(cb.lower(root.get("primaryDeity")), "%" + f.getDeityName().toLowerCase() + "%"));
            if (f.getTradition() != null) predicates.add(cb.equal(root.get("tradition"), f.getTradition()));
            if (f.getTrustRegistered() != null) predicates.add(cb.equal(root.get("trustRegistered"), f.getTrustRegistered()));
            if (f.getDeclarationStatus() != null) predicates.add(cb.equal(root.get("assetDeclarationStatus"), f.getDeclarationStatus()));
            if (f.getEstablishedYearFrom() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("yearEstablished"), f.getEstablishedYearFrom()));
            if (f.getEstablishedYearTo() != null) predicates.add(cb.lessThanOrEqualTo(root.get("yearEstablished"), f.getEstablishedYearTo()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public TempleResponse getCurrentProfile(Long templeId) {
        Temple temple = templeRepository.findById(templeId).orElse(null);
        if (temple != null) {
            jurisdictionGuard.assertSameDistrict(temple.getDistrictId());
            ownershipGuard.assertOwnsTemple(templeId);
            return enrichTempleResponse(templeMapper.toTempleResponse(temple));
        }

        TempleProfileStaging staging = stagingRepository.findTopByTempleIdAndStatusInOrderByVersionNumberDesc(
                        templeId,
                        List.of(
                                com.templeregistry.entity.workflow.WorkflowStatus.DRAFT,
                                com.templeregistry.entity.workflow.WorkflowStatus.SUBMITTED,
                                com.templeregistry.entity.workflow.WorkflowStatus.APPROVED))
                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));

        return TempleResponse.builder()
                .id(templeId)
                .contactName(staging.getContactPersonName())
                .contactDesignation(staging.getContactPersonDesignation())
                .contactMobile(staging.getPhone())
                .contactEmail(staging.getEmail())
                .photoUrl(staging.getPhotoFilePath() != null && !staging.getPhotoFilePath().isBlank() ? "/api/v1/temples/" + templeId + "/profile-photo/serve" : null)
                .website(staging.getWebsite())
                .languagesOfWorship(staging.getLanguagesOfWorship())
                .linkedInstitutions(staging.getLinkedInstitutions())
                .annualFestivals(staging.getAnnualFestivals())
                .landmark(staging.getLandmark())
                .historicalSignificance(staging.getHistoricalSignificance())
                .history(staging.getDescription() != null ? staging.getDescription() : staging.getHistoricalSignificance())
                .bankName(staging.getBankName())
                .bankIfsc(staging.getBankIfsc())
                .build();
    }

    private TempleResponse enrichTempleResponse(TempleResponse dto) {
        if (dto == null || dto.getDistrictId() == null) {
            return dto;
        }

        // Single query: fetch district, then lazily resolve its city within the same transaction
        final String[] districtAndCity = { null, null, null }; // [districtName, cityName, cityId]
        districtRepository.findById(dto.getDistrictId()).ifPresent(d -> {
            districtAndCity[0] = d.getName();
            if (d.getCity() != null) {
                districtAndCity[1] = d.getCity().getName();
                districtAndCity[2] = d.getCity().getId() != null ? d.getCity().getId().toString() : null;
            }
        });

        Long resolvedCityId = dto.getCityId() != null ? dto.getCityId()
                : (districtAndCity[2] != null ? Long.parseLong(districtAndCity[2]) : null);

        // Resolve talukId from hobliId when talukId is not stored on the temple entity.
        Long resolvedTalukId = dto.getTalukId() != null ? dto.getTalukId()
                : (dto.getHobliId() != null ? hobliRepository.findTalukIdById(dto.getHobliId()).orElse(null) : null);

        return TempleResponse.builder()
                .id(dto.getId())
                .registrationNumber(dto.getRegistrationNumber())
                .name(dto.getName())
                .aliasName(dto.getAliasName())
                .grade(dto.getGrade())
                .primaryDeity(dto.getPrimaryDeity())
                .tradition(dto.getTradition())
                .yearEstablished(dto.getYearEstablished())
                .history(dto.getHistory())
                .doorNumber(dto.getDoorNumber())
                .street(dto.getStreet())
                .villageTown(dto.getVillageTown())
                .pinCode(dto.getPinCode())
                .hobliId(dto.getHobliId())
                .talukId(resolvedTalukId)
                .cityId(resolvedCityId)
                .cityName(districtAndCity[1])
                .districtId(dto.getDistrictId())
                .districtName(districtAndCity[0])
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .contactName(dto.getContactName())
                .contactDesignation(dto.getContactDesignation())
                .contactMobile(dto.getContactMobile())
                .contactEmail(dto.getContactEmail())
                .photoUrl(dto.getPhotoUrl())
                .website(dto.getWebsite())
                .languagesOfWorship(dto.getLanguagesOfWorship())
                .linkedInstitutions(dto.getLinkedInstitutions())
                .annualFestivals(dto.getAnnualFestivals())
                .landmark(dto.getLandmark())
                .historicalSignificance(dto.getHistoricalSignificance())
                .bankName(dto.getBankName())
                .bankIfsc(dto.getBankIfsc())
                .trustRegistered(dto.isTrustRegistered())
                .assetDeclarationStatus(dto.getAssetDeclarationStatus())
                .status(dto.getStatus())
                .verificationStatus(dto.getVerificationStatus())
                .dcRejectionReason(dto.getDcRejectionReason())
                .build();
    }

    private static void validatePhoto(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("Photograph must not be empty.");
        }
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_PHOTO_MIME.contains(mimeType.toLowerCase())) {
            throw new FileValidationException("Unsupported image type. Allowed: JPEG, PNG.");
        }
        if (file.getSize() > MAX_PHOTO_SIZE_BYTES) {
            throw new FileValidationException("Photograph exceeds maximum allowed size of 5 MB.");
        }
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public String uploadPrimaryPhoto(Long templeId, MultipartFile file) {
        return uploadTemplePhotos(templeId, List.of(file)).stream().findFirst().orElse(null);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public List<String> uploadTemplePhotos(Long templeId, List<MultipartFile> files) {
        Temple temple = findOrThrow(templeId);
        ownershipGuard.assertOwnsTemple(templeId);
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        // Validate the whole batch before writing any of it, so one bad photo cannot leave
        // its predecessors already persisted to storage and the database.
        files.forEach(TempleServiceImpl::validatePhoto);

        List<TemplePhoto> existing = templePhotoRepository.findByTempleIdOrderByDisplayOrderAsc(templeId);
        int nextOrder = existing.size();
        boolean hasPrimary = existing.stream().anyMatch(TemplePhoto::isPrimary);
        List<String> uploadedUrls = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);

            // Read bytes before upload so the InputStream is not consumed twice.
            byte[] imageBytes;
            try {
                imageBytes = file.getBytes();
            } catch (IOException e) {
                throw new com.templeregistry.exception.FileValidationException("Failed to read uploaded file: " + e.getMessage());
            }

            String path = fileStorageService.upload("temples/" + templeId + "/photos", file);
            boolean makePrimary = !hasPrimary && i == 0;

            TemplePhoto photo = TemplePhoto.builder()
                    .temple(temple)
                    .filePath(path)
                    .originalFilename(file.getOriginalFilename())
                    .imageData(imageBytes)
                    .contentType(file.getContentType())
                    .isPrimary(makePrimary)
                    .displayOrder(nextOrder++)
                    .build();
            templePhotoRepository.save(photo);

            if (makePrimary) {
                temple.setPhotoUrl(path);
                hasPrimary = true;
            }
            uploadedUrls.add(fileStorageService.presignedUrl(path));
        }

        templeRepository.save(temple);
        return uploadedUrls;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<TemplePhotoDto> getTemplePhotos(Long templeId) {
        findOrThrow(templeId);
        ownershipGuard.assertOwnsTemple(templeId);
        return templePhotoRepository.findByTempleIdOrderByDisplayOrderAsc(templeId).stream()
                .map(photo -> new TemplePhotoDto(
                        photo.getId(),
                        // Return a stable serve URL that bypasses the documents table.
                        // temple_photos is the canonical source — this endpoint reads directly
                        // from FileStorageService without requiring a Document entity record.
                        "/api/v1/temples/" + templeId + "/photos/" + photo.getId() + "/serve",
                        photo.isPrimary(),
                        photo.getOriginalFilename(),
                        photo.getCreatedAt(),
                        photo.getWidth(),
                        photo.getHeight()
                ))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Resource serveProfilePhoto(Long templeId) {
        Temple temple = findOrThrow(templeId);

        // Try DB-stored bytes first (available on every machine that shares the database).
        TemplePhoto primaryPhoto = templePhotoRepository
                .findFirstByTempleIdAndIsPrimaryTrue(templeId).orElse(null);
        if (primaryPhoto != null && primaryPhoto.getImageData() != null) {
            byte[] data = primaryPhoto.getImageData();
            String filename = primaryPhoto.getOriginalFilename();
            return new ByteArrayResource(data) {
                @Override
                public String getFilename() { return filename; }
            };
        }

        // Fallback: serve from local filesystem (for photos uploaded before V103).
        if (temple.getPhotoUrl() == null || temple.getPhotoUrl().isBlank()) {
            throw new EntityNotFoundException("ProfilePhoto", templeId);
        }
        return fileStorageService.loadAsResource(temple.getPhotoUrl());
    }

    @Override
    @Transactional(readOnly = true)
    public Resource serveTemplePhoto(Long templeId, Long photoId) {
        findOrThrow(templeId);
        TemplePhoto photo = templePhotoRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("TemplePhoto", photoId));
        // Verify the photo belongs to the requested temple
        if (!photo.getTemple().getId().equals(templeId)) {
            throw new EntityNotFoundException("TemplePhoto", photoId);
        }

        // Serve from DB if bytes are stored (works on every machine sharing the database).
        if (photo.getImageData() != null) {
            byte[] data = photo.getImageData();
            String filename = photo.getOriginalFilename();
            return new ByteArrayResource(data) {
                @Override
                public String getFilename() { return filename; }
            };
        }

        // Fallback: serve from local filesystem (for photos uploaded before V103).
        return fileStorageService.loadAsResource(photo.getFilePath());
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void deleteTemplePhoto(Long templeId, Long photoId) {
        Temple temple = findOrThrow(templeId);
        ownershipGuard.assertOwnsTemple(templeId);
        TemplePhoto target = templePhotoRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("TemplePhoto", photoId));
        if (!target.getTemple().getId().equals(templeId)) {
            throw new EntityNotFoundException("TemplePhoto", photoId);
        }

        boolean wasPrimary = target.isPrimary();
        templePhotoRepository.delete(target);
        fileStorageService.delete(target.getFilePath());

        List<TemplePhoto> remaining = templePhotoRepository.findByTempleIdOrderByDisplayOrderAsc(templeId);
        if (remaining.isEmpty()) {
            temple.setPhotoUrl(null);
        } else if (wasPrimary) {
            TemplePhoto newPrimary = remaining.get(0);
            newPrimary.setPrimary(true);
            templePhotoRepository.save(newPrimary);
            temple.setPhotoUrl(newPrimary.getFilePath());
        }
        templeRepository.save(temple);
    }

    // ─── SUPER_ADMIN lifecycle ─────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    public void suspendTemple(Long templeId, String reason, Long actorUserId) {
        Temple temple = findOrThrow(templeId);
        assertLifecycleTransition(temple.getStatus(), TempleStatus.SUSPENDED, templeId);
        temple.setStatus(TempleStatus.SUSPENDED);
        templeRepository.save(temple);
        auditService.logDataEvent(actorUserId, "SUPER_ADMIN", "SUSPEND_TEMPLE", "TEMPLE", templeId, reason);
        log.info("Temple [{}] SUSPENDED by userId={} — reason: {}", templeId, actorUserId, reason);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    public void reactivateTemple(Long templeId, String reason, Long actorUserId) {
        Temple temple = findOrThrow(templeId);
        assertLifecycleTransition(temple.getStatus(), TempleStatus.ACTIVE, templeId);
        temple.setStatus(TempleStatus.ACTIVE);
        templeRepository.save(temple);
        auditService.logDataEvent(actorUserId, "SUPER_ADMIN", "REACTIVATE_TEMPLE", "TEMPLE", templeId, reason);
        log.info("Temple [{}] REACTIVATED by userId={} — reason: {}", templeId, actorUserId, reason);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    public void freezeTemple(Long templeId, String reason, Long actorUserId) {
        Temple temple = findOrThrow(templeId);
        assertLifecycleTransition(temple.getStatus(), TempleStatus.FROZEN, templeId);
        temple.setStatus(TempleStatus.FROZEN);
        templeRepository.save(temple);
        auditService.logDataEvent(actorUserId, "SUPER_ADMIN", "FREEZE_TEMPLE", "TEMPLE", templeId, reason);
        log.info("Temple [{}] FROZEN by userId={} — reason: {}", templeId, actorUserId, reason);
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    public void archiveTemple(Long templeId, String reason, Long actorUserId) {
        Temple temple = findOrThrow(templeId);
        assertLifecycleTransition(temple.getStatus(), TempleStatus.ARCHIVED, templeId);
        temple.setStatus(TempleStatus.ARCHIVED);
        templeRepository.save(temple);
        auditService.logDataEvent(actorUserId, "SUPER_ADMIN", "ARCHIVE_TEMPLE", "TEMPLE", templeId, reason);
        log.info("Temple [{}] ARCHIVED by userId={} — reason: {}", templeId, actorUserId, reason);
    }

    private void assertLifecycleTransition(TempleStatus current, TempleStatus target, Long templeId) {
        if (current == TempleStatus.ARCHIVED) {
            throw new IllegalStateException("Temple [" + templeId + "] is ARCHIVED and cannot transition to " + target);
        }
        if (current == target) {
            throw new IllegalStateException("Temple [" + templeId + "] is already in status " + target);
        }
    }
}

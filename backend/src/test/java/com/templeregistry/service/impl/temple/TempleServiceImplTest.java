package com.templeregistry.service.impl.temple;

import com.templeregistry.dto.request.temple.CreateTempleRequest;
import com.templeregistry.entity.temple.TempleStatus;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.dto.request.temple.TempleSearchFilterRequest;
import com.templeregistry.dto.response.temple.TempleResponse;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleGrade;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.mapper.temple.TempleMapper;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.temple.TempleSearchSummaryRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.service.temple.TempleSearchSummaryService;
import com.templeregistry.util.PaginationUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentMatchers;

@ExtendWith(MockitoExtension.class)
class TempleServiceImplTest {

    @Mock TempleRepository templeRepository;
    @Mock TempleSearchSummaryRepository searchSummaryRepository;
    @Mock TempleSearchSummaryService searchSummaryService;
    @Mock TempleMapper templeMapper;
    @Mock JurisdictionGuard jurisdictionGuard;
    @Mock PaginationUtil paginationUtil;
    @Mock com.templeregistry.service.notification.NotificationHelper notificationHelper;
    @Mock AuditService auditService;
    @Mock com.templeregistry.repository.temple.TemplePhotoRepository templePhotoRepository;
    @Mock com.templeregistry.security.OwnershipGuard ownershipGuard;
    @Mock com.templeregistry.service.document.FileStorageService fileStorageService;

    @InjectMocks TempleServiceImpl templeService;

    @Test
    void should_createTemple_and_triggerSearchSummaryRefresh() {
        CreateTempleRequest rq = CreateTempleRequest.builder()
                .name("Shiva Temple").districtId(10L).grade(TempleGrade.A).build();
        Temple saved = Temple.builder().name("Shiva Temple").districtId(10L).grade(TempleGrade.A).build();
        TempleResponse response = TempleResponse.builder().name("Shiva Temple").build();

        when(templeRepository.save(any())).thenReturn(saved);
        when(templeMapper.toTempleResponse(saved)).thenReturn(response);

        TempleResponse result = templeService.create(rq);

        assertThat(result.getName()).isEqualTo("Shiva Temple");
        verify(searchSummaryService).scheduleRefresh(any());
    }

    @Test
    void should_throw_EntityNotFoundException_when_temple_not_found() {
        when(templeRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> templeService.getById(99L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void should_enforceDistrictId_when_DC_calls_search() {
        TempleSearchFilterRequest filter = TempleSearchFilterRequest.builder().districtId(10L).build();
        when(jurisdictionGuard.enforceDistrictId(10L)).thenReturn(10L);
        when(paginationUtil.clampSize(10)).thenReturn(10);
        when(searchSummaryRepository.findAll(
                ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<com.templeregistry.entity.temple.TempleSearchSummary>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        templeService.search(filter);

        verify(jurisdictionGuard).enforceDistrictId(10L);
    }

    // ─── Lifecycle tests ─────────────────────────────────────────────────────

    @Test
    void should_suspendTemple_when_templeIsActive() {
        Temple temple = Temple.builder().name("Test Temple").districtId(1L).grade(TempleGrade.A).build();
        temple.setId(1L);
        temple.setStatus(TempleStatus.ACTIVE);

        when(templeRepository.findById(1L)).thenReturn(Optional.of(temple));
        when(templeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        templeService.suspendTemple(1L, "Violation found", 5L);

        assertThat(temple.getStatus()).isEqualTo(TempleStatus.SUSPENDED);
        verify(auditService).logDataEvent(eq(5L), anyString(), eq("SUSPEND_TEMPLE"), eq("TEMPLE"), eq(1L), anyString());
    }

    @Test
    void should_throwException_when_suspendingAlreadySuspendedTemple() {
        Temple temple = Temple.builder().name("Test Temple").districtId(1L).grade(TempleGrade.A).build();
        temple.setId(1L);
        temple.setStatus(TempleStatus.SUSPENDED);

        when(templeRepository.findById(1L)).thenReturn(Optional.of(temple));

        assertThatThrownBy(() -> templeService.suspendTemple(1L, "Duplicate suspend", 5L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_throwException_when_transitioningArchivedTemple() {
        Temple temple = Temple.builder().name("Test Temple").districtId(1L).grade(TempleGrade.A).build();
        temple.setId(1L);
        temple.setStatus(TempleStatus.ARCHIVED);

        when(templeRepository.findById(1L)).thenReturn(Optional.of(temple));

        assertThatThrownBy(() -> templeService.reactivateTemple(1L, "Cannot reactivate archived", 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");
    }

    /**
     * H-4 / VAL-006 — "Temple profile photograph | JPEG or PNG only; max 5 MB; enforced client
     * + server".
     *
     * <p>Server-side enforcement was missing entirely: only the browser checked. That was
     * partly masked by Boot's undeclared 1 MB multipart default, which rejected almost
     * everything before it reached this service. Raising that default to 10 MB for documents
     * would otherwise have widened an unchecked path from ~1 MB to 10 MB per photo, and these
     * bytes are read into a byte[] and persisted into a database column.</p>
     */
    @Nested
    class PhotoUploadValidation {

        private static final long ONE_MB = 1024L * 1024L;

        private org.springframework.web.multipart.MultipartFile photo(String mimeType, long sizeBytes) {
            var file = mock(org.springframework.web.multipart.MultipartFile.class);
            lenient().when(file.isEmpty()).thenReturn(false);
            lenient().when(file.getContentType()).thenReturn(mimeType);
            lenient().when(file.getSize()).thenReturn(sizeBytes);
            lenient().when(file.getOriginalFilename()).thenReturn("gopuram.jpg");
            return file;
        }

        private void templeExists() {
            when(templeRepository.findById(1L)).thenReturn(Optional.of(
                    Temple.builder().name("Shiva Temple").districtId(10L).build()));
        }

        @Test
        void should_acceptPhoto_when_exactlyAtTheFiveMegabyteLimit() throws Exception {
            templeExists();
            var file = photo("image/jpeg", 5 * ONE_MB);
            when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});
            when(templePhotoRepository.findByTempleIdOrderByDisplayOrderAsc(1L))
                    .thenReturn(new java.util.ArrayList<>());
            when(fileStorageService.upload(anyString(), ArgumentMatchers.any())).thenReturn("temples/1/photos/a.jpg");
            when(fileStorageService.presignedUrl(anyString())).thenReturn("https://cdn/a.jpg");

            assertThatCode(() -> templeService.uploadTemplePhotos(1L, java.util.List.of(file)))
                    .doesNotThrowAnyException();

            verify(templePhotoRepository).save(ArgumentMatchers.any());
        }

        @Test
        void should_rejectPhoto_when_oneByteOverTheFiveMegabyteLimit() {
            templeExists();

            assertThatThrownBy(() -> templeService.uploadTemplePhotos(
                    1L, java.util.List.of(photo("image/jpeg", 5 * ONE_MB + 1))))
                    .isInstanceOf(com.templeregistry.exception.FileValidationException.class)
                    .hasMessageContaining("5 MB");

            verify(fileStorageService, never()).upload(anyString(), ArgumentMatchers.any());
            verify(templePhotoRepository, never()).save(ArgumentMatchers.any());
        }

        @Test
        void should_rejectPhoto_when_mimeTypeIsNeitherJpegNorPng() {
            templeExists();

            assertThatThrownBy(() -> templeService.uploadTemplePhotos(
                    1L, java.util.List.of(photo("application/pdf", ONE_MB))))
                    .isInstanceOf(com.templeregistry.exception.FileValidationException.class);

            verify(fileStorageService, never()).upload(anyString(), ArgumentMatchers.any());
        }

        @Test
        void should_rejectPhoto_when_fileIsEmpty() {
            templeExists();
            var file = mock(org.springframework.web.multipart.MultipartFile.class);
            when(file.isEmpty()).thenReturn(true);

            assertThatThrownBy(() -> templeService.uploadTemplePhotos(1L, java.util.List.of(file)))
                    .isInstanceOf(com.templeregistry.exception.FileValidationException.class);

            verify(fileStorageService, never()).upload(anyString(), ArgumentMatchers.any());
        }

        @Test
        void should_storeNothing_when_oneFileInABatchIsInvalid() {
            // The batch is validated up front so a rejected photo cannot leave earlier photos
            // of the same request half-written to storage and the database.
            templeExists();

            assertThatThrownBy(() -> templeService.uploadTemplePhotos(1L, java.util.List.of(
                    photo("image/png", ONE_MB),
                    photo("image/png", 6 * ONE_MB))))
                    .isInstanceOf(com.templeregistry.exception.FileValidationException.class);

            verify(fileStorageService, never()).upload(anyString(), ArgumentMatchers.any());
            verify(templePhotoRepository, never()).save(ArgumentMatchers.any());
        }
    }
}

package com.templeregistry.service.impl.document;

import com.templeregistry.entity.declaration.AssetDeclaration;
import com.templeregistry.entity.declaration.DeclarationStatus;
import com.templeregistry.entity.document.Document;
import com.templeregistry.exception.FileValidationException;
import com.templeregistry.exception.ImmutableResourceException;
import com.templeregistry.repository.declaration.DeclarationRepository;
import com.templeregistry.repository.document.DocumentAccessLogRepository;
import com.templeregistry.repository.document.DocumentRepository;
import com.templeregistry.service.document.FileStorageService;
import com.templeregistry.util.PaginationUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    private static final long ONE_MB = 1024L * 1024L;

    @Mock DocumentRepository documentRepository;
    @Mock DocumentAccessLogRepository accessLogRepository;
    @Mock FileStorageService fileStorageService;
    @Mock PaginationUtil paginationUtil;
    @Mock DeclarationRepository declarationRepository;

    @InjectMocks DocumentServiceImpl documentService;

    @Test
    void should_blockSoftDelete_when_documentAttachedToApprovedDeclaration() {
        Document doc = Document.builder()
                .ownerType("DECLARATION")
                .ownerId(1L)
                .referenceId(99L)
                .originalFilename("a.pdf")
                .s3Key("k")
                .mimeType("application/pdf")
                .fileSizeBytes(10L)
                .build();
        doc.setId(7L);

        AssetDeclaration approved = AssetDeclaration.builder()
                .status(DeclarationStatus.APPROVED)
                .build();
        approved.setId(99L);

        when(documentRepository.findById(7L)).thenReturn(Optional.of(doc));
        when(declarationRepository.findById(99L)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> documentService.softDelete(7L))
                .isInstanceOf(ImmutableResourceException.class);

        verify(documentRepository, never()).deleteById(anyLong());
    }

    /**
     * H-4 / VAL-005 — "Document upload — file size | Max 10 MB per file; enforced client +
     * server".
     *
     * <p>The multipart path used to stop at 5 MB while {@code registerExternalUpload} allowed
     * 10 MB for the very same document, and the SPA's document page offers 10 MB. A 7 MB PDF
     * was accepted by the browser and then rejected by the server.</p>
     */
    @Nested
    class FileSizeValidation {

        private MultipartFile pdfOfSize(long sizeBytes) {
            MultipartFile file = mock(MultipartFile.class);
            lenient().when(file.isEmpty()).thenReturn(false);
            lenient().when(file.getContentType()).thenReturn("application/pdf");
            lenient().when(file.getSize()).thenReturn(sizeBytes);
            lenient().when(file.getOriginalFilename()).thenReturn("deed.pdf");
            return file;
        }

        @Test
        void should_acceptDocument_when_exactlyAtTheTenMegabyteLimit() {
            Document saved = Document.builder()
                    .ownerType("TEMPLE").ownerId(1L).originalFilename("deed.pdf")
                    .s3Key("k").mimeType("application/pdf").fileSizeBytes(10 * ONE_MB).build();
            saved.setId(1L);
            when(fileStorageService.upload(anyString(), any(MultipartFile.class))).thenReturn("k");
            when(documentRepository.save(any(Document.class))).thenReturn(saved);

            assertThatCode(() -> documentService.upload(
                    "TEMPLE", 1L, null, "Deed", pdfOfSize(10 * ONE_MB)))
                    .doesNotThrowAnyException();
        }

        @Test
        void should_acceptDocument_when_aboveTheOldFiveMegabyteCeiling() {
            Document saved = Document.builder()
                    .ownerType("TEMPLE").ownerId(1L).originalFilename("deed.pdf")
                    .s3Key("k").mimeType("application/pdf").fileSizeBytes(7 * ONE_MB).build();
            saved.setId(2L);
            when(fileStorageService.upload(anyString(), any(MultipartFile.class))).thenReturn("k");
            when(documentRepository.save(any(Document.class))).thenReturn(saved);

            assertThatCode(() -> documentService.upload(
                    "TEMPLE", 1L, null, "Deed", pdfOfSize(7 * ONE_MB)))
                    .doesNotThrowAnyException();
        }

        @Test
        void should_rejectDocument_when_oneByteOverTheTenMegabyteLimit() {
            assertThatThrownBy(() -> documentService.upload(
                    "TEMPLE", 1L, null, "Deed", pdfOfSize(10 * ONE_MB + 1)))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("10 MB");

            verify(fileStorageService, never()).upload(anyString(), any(MultipartFile.class));
            verify(documentRepository, never()).save(any(Document.class));
        }

        @Test
        void should_applyTheSameLimit_when_registeringAnExternallyUploadedFile() {
            // The multipart path and the pre-signed-upload path describe the same document
            // kind, so they must not disagree about how large it may be.
            assertThatThrownBy(() -> documentService.registerExternalUpload(
                    "TEMPLE", 1L, "Deed", "k", "application/pdf", 10 * ONE_MB + 1, "deed.pdf"))
                    .isInstanceOf(FileValidationException.class)
                    .hasMessageContaining("10 MB");
        }

        @Test
        void should_rejectDocument_when_mimeTypeIsNotAllowed() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getContentType()).thenReturn("application/zip");

            assertThatThrownBy(() -> documentService.upload("TEMPLE", 1L, null, "Deed", file))
                    .isInstanceOf(FileValidationException.class);
        }
    }
}

package com.templeregistry.service.impl.document;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.response.document.DocumentResponse;
import com.templeregistry.dto.response.document.DocumentUrlResponse;
import com.templeregistry.entity.declaration.AssetDeclaration;
import com.templeregistry.entity.declaration.DeclarationStatus;
import com.templeregistry.entity.document.Document;
import com.templeregistry.entity.document.DocumentAccessLog;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.trust.Trust;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.FileValidationException;
import com.templeregistry.exception.ImmutableResourceException;
import com.templeregistry.repository.declaration.DeclarationRepository;
import com.templeregistry.repository.document.DocumentAccessLogRepository;
import com.templeregistry.repository.document.DocumentRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.trust.TrustRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.document.DocumentService;
import com.templeregistry.service.document.FileStorageService;
import com.templeregistry.service.timeline.TempleTimelineService;
import com.templeregistry.entity.timeline.TimelineEventCode;
import com.templeregistry.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements DocumentService {

    /**
     * VAL-005: "Document upload — file size | Max 10 MB per file; enforced client + server".
     *
     * <p>This used to be 5 MB on the multipart path while {@link #validateExternalUpload}
     * allowed 10 MB for the same document kind, so which limit applied depended only on
     * whether the bytes arrived through this service or through a pre-signed upload (H-4).</p>
     */
    private static final long MAX_SIZE_BYTES = 10 * 1024 * 1024L; // 10 MB (VAL-005)
    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/png", "application/pdf");

    private final DocumentRepository documentRepository;
    private final DocumentAccessLogRepository accessLogRepository;
    private final FileStorageService fileStorageService;
    private final PaginationUtil paginationUtil;
    private final DeclarationRepository declarationRepository;
    private final TempleRepository templeRepository;
    private final TrustRepository trustRepository;
    private final JurisdictionGuard jurisdictionGuard;

    // Optional — Spring will inject null if no bean present (safe for existing tests).
    // Lazily injected to break any potential circular dependency.
    private final TempleTimelineService templeTimelineService;

    @Override
    @Transactional
    public DocumentResponse upload(String ownerType, Long ownerId, Long referenceId, String label,
                                   MultipartFile file) {
        validateFile(file);
        String folder = ownerType.toLowerCase() + "/" + ownerId + "/docs";
        String s3Key = fileStorageService.upload(folder, file);
        Document doc = Document.builder()
                .ownerType(ownerType).ownerId(ownerId).referenceId(referenceId)
                .originalFilename(file.getOriginalFilename())
                .s3Key(s3Key).mimeType(file.getContentType())
                .fileSizeBytes(file.getSize()).documentLabel(label).build();
        Document saved = documentRepository.save(doc);
        log.info("Document saved: id=[{}] key=[{}]", saved.getId(), s3Key);

        // ── Timeline hook (additive, non-fatal) ──────────────────────────────
        try {
            Long templeId = resolveTempleId(saved);
            ScopeHelper.Claims claims = currentClaimsSafe();
            Long actorId   = claims != null ? claims.userId() : 0L;
            String actorRole = claims != null ? claims.role() : "SYSTEM";
            templeTimelineService.logDocumentEvent(
                TimelineEventCode.DOCUMENT_UPLOADED, templeId,
                saved.getId(), label, ownerType, actorId, actorRole);
        } catch (Exception ex) {
            log.warn("[Timeline] Document upload event failed: docId={} error={}", saved.getId(), ex.getMessage());
        }

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    @Transactional
    public DocumentUrlResponse getPresignedUrl(Long id) {
        Document doc = findOrThrow(id);
        assertAccess(doc);
        // Instead of using fileStorageService.presignedUrl which returns a /download?key= URL,
        // we'll return the spec-compliant /{id}/download URL.
        String url = "/api/v1/documents/" + id + "/download";
        recordAccess(id, "DOWNLOAD_URL_GEN");
        return DocumentUrlResponse.builder()
                .documentId(id).url(url).expiresIn("15 minutes")
                .generatedAt(LocalDateTime.now()).build();
    }

    @Override
    @Transactional
    public Resource download(Long id) {
        Document doc = findOrThrow(id);
        assertAccess(doc);
        recordAccess(id, "DOWNLOAD");
        return fileStorageService.loadAsResource(doc.getS3Key());
    }

    @Override
    @Transactional
    public Resource downloadByKey(String key) {
        Document doc = documentRepository.findByS3Key(key)
                .orElseThrow(() -> new EntityNotFoundException("Document not found for key", "DOC_NOT_FOUND"));
        assertAccess(doc);
        recordAccess(doc.getId(), "DOWNLOAD");
        return fileStorageService.loadAsResource(doc.getS3Key());
    }

    private void assertAccess(Document doc) {
        ScopeHelper.Claims claims = currentClaims();
        String role = claims.role();

        // SUPER_ADMIN has full access
        if (RoleConstants.SUPER_ADMIN.equals(role)) return;

        // TEMPLE_AUTHORITY can only access their own temple's documents
        if (RoleConstants.TEMPLE_AUTHORITY.equals(role)) {
            Long templeId = resolveTempleId(doc);
            if (!claims.templeId().equals(templeId)) {
                throw new AccessDeniedException("Access denied to this document.");
            }
            return;
        }

        // DC/DC_STAFF must be in the same district
        if (RoleConstants.DISTRICT_COLLECTOR.equals(role) || RoleConstants.DC_STAFF.equals(role)) {
            // R11/R16.5 — DC_STAFF receives 403 for download
            if (RoleConstants.DC_STAFF.equals(role)) {
                throw new AccessDeniedException("DC Staff are not authorized to download documents.");
            }
            
            Long templeId = resolveTempleId(doc);
            Temple temple = templeRepository.findWithGeoById(templeId)
                    .orElseThrow(() -> new EntityNotFoundException("Temple linked to document not found", "TEMPLE_NOT_FOUND"));
            jurisdictionGuard.assertDistrictScope(temple, claims);
            return;
        }

        // Default: deny
        throw new AccessDeniedException("Unauthorized access.");
    }

    private Long resolveTempleId(Document doc) {
        String type = doc.getOwnerType().toUpperCase();
        return switch (type) {
            case "TEMPLE" -> doc.getOwnerId();
            case "DECLARATION" -> declarationRepository.findById(doc.getOwnerId())
                    .map(AssetDeclaration::getTempleId)
                    .orElseThrow(() -> new EntityNotFoundException("Declaration linked to document not found", "DECLARATION_NOT_FOUND"));
            case "TRUST" -> trustRepository.findById(doc.getOwnerId())
                    .map(Trust::getTempleId)
                    .orElseThrow(() -> new EntityNotFoundException("Trust linked to document not found", "TRUST_NOT_FOUND"));
            case "CONTRACTOR" -> doc.getOwnerId(); // For contractors, ownerId is the templeId
            case "EMPLOYEE" -> doc.getOwnerId(); // For employees, ownerId is the templeId
            default -> doc.getOwnerId();
        };
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<DocumentResponse> listByOwner(String ownerType, Long ownerId,
                                                            int page, int size) {
        Page<Document> result = documentRepository.findAllByOwnerTypeAndOwnerId(
                ownerType, ownerId, PageRequest.of(page, paginationUtil.clampSize(size)));
        return PaginatedResponse.of(result.map(this::toResponse));
    }

    @Override
    @Transactional
    public void softDelete(Long id) {
        Document doc = findOrThrow(id);
        if ("DECLARATION".equalsIgnoreCase(doc.getOwnerType()) && doc.getReferenceId() != null) {
            declarationRepository.findById(doc.getReferenceId()).ifPresent(d -> {
                if (d.getStatus() == DeclarationStatus.APPROVED) {
                    throw new ImmutableResourceException(
                            "Documents attached to an APPROVED declaration cannot be removed.");
                }
            });
        }
        // ── Timeline hook: capture metadata BEFORE the soft-delete ─────────
        String docLabel = doc.getDocumentLabel();
        String ownerType = doc.getOwnerType();
        Long ownerId = doc.getOwnerId();

        documentRepository.deleteById(id); // @SQLDelete intercepts → UPDATE is_deleted = true

        // ── Timeline hook (additive, non-fatal) ──────────────────────────────
        try {
            Long templeId = resolveTempleIdForOwner(ownerType, ownerId);
            ScopeHelper.Claims claims = currentClaimsSafe();
            Long actorId   = claims != null ? claims.userId() : 0L;
            String actorRole = claims != null ? claims.role() : "SYSTEM";
            templeTimelineService.logDocumentEvent(
                TimelineEventCode.DOCUMENT_DELETED, templeId,
                id, docLabel, ownerType, actorId, actorRole);
        } catch (Exception ex) {
            log.warn("[Timeline] Document delete event failed: docId={} error={}", id, ex.getMessage());
        }
    }

    @Override
    @Transactional
    public DocumentResponse registerExternalUpload(String ownerType, Long ownerId, String label,
                                                    String s3Key, String mimeType,
                                                    long fileSizeBytes, String originalFilename) {
        validateExternalUpload(mimeType, fileSizeBytes);
        Document doc = Document.builder()
                .ownerType(ownerType).ownerId(ownerId).referenceId(null)
                .originalFilename(originalFilename)
                .s3Key(s3Key).mimeType(mimeType)
                .fileSizeBytes(fileSizeBytes).documentLabel(label).build();
        Document saved = documentRepository.save(doc);
        log.info("External document registered: id=[{}] owner=[{}/{}]", saved.getId(), ownerType, ownerId);
        return toResponse(saved);
    }

    private void validateExternalUpload(String mimeType, long fileSizeBytes) {
        if (!ALLOWED_MIME.contains(mimeType)) {
            throw new FileValidationException("Unsupported file type. Allowed: PDF, JPEG, PNG.");
        }
        if (fileSizeBytes > MAX_SIZE_BYTES) {
            throw new FileValidationException("File exceeds maximum allowed size of 10 MB.");
        }
        if (fileSizeBytes <= 0) {
            throw new FileValidationException("File size must be positive.");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("File must not be empty.");
        }
        if (!ALLOWED_MIME.contains(file.getContentType())) {
            throw new FileValidationException("Unsupported file type. Allowed: PDF, JPEG, PNG.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new FileValidationException("File exceeds maximum allowed size of 10 MB.");
        }
    }

    private void recordAccess(Long documentId, String accessType) {
        ScopeHelper.Claims claims = currentClaims();
        accessLogRepository.save(DocumentAccessLog.builder()
                .documentId(documentId).accessorId(claims.userId())
                .accessorRole(claims.role()).accessType(accessType).build());
    }

    private ScopeHelper.Claims currentClaims() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof ScopeHelper.Claims c) return c;
        throw new IllegalStateException("No authenticated claims in security context.");
    }

    /**
     * Non-throwing variant for timeline hooks — returns null when no authenticated
     * principal is available (e.g. system-initiated operations).
     */
    private ScopeHelper.Claims currentClaimsSafe() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return null;
            Object principal = auth.getPrincipal();
            return (principal instanceof ScopeHelper.Claims c) ? c : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Resolve temple ID from owner type + owner ID (used by delete timeline hook
     * after the document row is no longer accessible for resolveTempleId(doc)).
     */
    private Long resolveTempleIdForOwner(String ownerType, Long ownerId) {
        if (ownerType == null) return ownerId;
        return switch (ownerType.toUpperCase()) {
            case "TEMPLE"      -> ownerId;
            case "DECLARATION" -> declarationRepository.findById(ownerId)
                .map(d -> d.getTempleId()).orElse(ownerId);
            case "TRUST"       -> trustRepository.findById(ownerId)
                .map(t -> t.getTempleId()).orElse(ownerId);
            default            -> ownerId;
        };
    }

    private Document findOrThrow(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document", id));
    }

    private DocumentResponse toResponse(Document d) {
        return DocumentResponse.builder()
                .id(d.getId()).ownerType(d.getOwnerType()).ownerId(d.getOwnerId())
                .originalFilename(d.getOriginalFilename()).mimeType(d.getMimeType())
                .fileSizeBytes(d.getFileSizeBytes()).documentLabel(d.getDocumentLabel())
                .createdAt(d.getCreatedAt()).build();
    }
}

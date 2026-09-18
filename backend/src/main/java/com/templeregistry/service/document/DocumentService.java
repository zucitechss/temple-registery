package com.templeregistry.service.document;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.response.document.DocumentResponse;
import com.templeregistry.dto.response.document.DocumentUrlResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    /**
     * Validate, upload to S3, and persist document metadata.
     * @param ownerType  "TEMPLE", "TRUST", "EMPLOYEE", "CONTRACTOR", "DECLARATION"
     * @param ownerId    ID of the owning entity
     * @param referenceId optional secondary reference (e.g. declarationId)
     * @param label      human-readable label
     * @param file       multipart file (validated: PDF/JPG/PNG, max 10 MB — VAL-005)
     */
    DocumentResponse upload(String ownerType, Long ownerId, Long referenceId, String label, MultipartFile file);

    DocumentResponse getById(Long id);

    /**
     * Generate a pre-signed URL. Records access in {@code document_access_logs}.
     */
    DocumentUrlResponse getPresignedUrl(Long id);

    /**
     * Load the document as a resource for download.
     * Enforces security checks and records access.
     */
    org.springframework.core.io.Resource download(Long id);

    /**
     * Load the document as a resource for download using the s3Key.
     * Enforces security checks and records access.
     */
    org.springframework.core.io.Resource downloadByKey(String key);

    PaginatedResponse<DocumentResponse> listByOwner(String ownerType, Long ownerId, int page, int size);

    /** Soft-deletes the DB record; does NOT delete the S3 object (retain for audit). */
    void softDelete(Long id);

    /**
     * Register metadata for a file the client has already uploaded directly to S3.
     * Server validates MIME type and file size before persisting the reference.
     *
     * @param ownerType       "TEMPLE", "TRUST", "EMPLOYEE", etc.
     * @param ownerId         ID of the owning entity
     * @param label           human-readable document label
     * @param s3Key           S3 object key supplied by the client
     * @param mimeType        MIME type declared by client (validated: PDF/JPEG/PNG, max 10 MB)
     * @param fileSizeBytes   file size in bytes (validated server-side against VAL-005)
     * @param originalFilename original filename for display
     */
    DocumentResponse registerExternalUpload(String ownerType, Long ownerId, String label,
                                            String s3Key, String mimeType,
                                            long fileSizeBytes, String originalFilename);
}

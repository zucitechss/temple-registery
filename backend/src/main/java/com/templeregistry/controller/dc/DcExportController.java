package com.templeregistry.controller.dc;

import com.templeregistry.common.ApiResponse;
import com.templeregistry.dto.request.export.ExportDeclarationsRequest;
import com.templeregistry.dto.request.export.ExportTemplesRequest;
import com.templeregistry.dto.response.dc.ExportJobResponse;
import com.templeregistry.entity.dc.ExportJobRecord;
import com.templeregistry.repository.dc.ExportJobRecordRepository;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.dc.DcExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/dc/export")
@RequiredArgsConstructor
@Tag(name = "DC Export", description = "District-scoped export of temples and declarations")
@PreAuthorize(RoleConstants.CAN_READ_ALL)
public class DcExportController {

    private final DcExportService dcExportService;
    private final ExportJobRecordRepository exportJobRecordRepository;

    @Value("${trm.export.base-dir:./exports}")
    private String exportBaseDir;

    @PostMapping("/temples")
    @Operation(summary = "Export district-scoped temple list as CSV or PDF. Returns 200 for sync (< 500 rows) or 202 for async (>= 500 rows).")
    public ResponseEntity<ApiResponse<ExportJobResponse>> exportTemples(
            @Valid @RequestBody ExportTemplesRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ExportJobResponse result = dcExportService.exportTemples(request, idempotencyKey, currentClaims());
        return buildExportResponse(result);
    }

    @PostMapping("/declarations")
    @Operation(summary = "Export district-scoped declaration list as CSV or PDF. Returns 200 for sync (< 500 rows) or 202 for async (>= 500 rows).")
    public ResponseEntity<ApiResponse<ExportJobResponse>> exportDeclarations(
            @Valid @RequestBody ExportDeclarationsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ExportJobResponse result = dcExportService.exportDeclarations(request, idempotencyKey, currentClaims());
        return buildExportResponse(result);
    }

    @GetMapping("/{jobId}/download")
    @Operation(summary = "Download a completed export file by its job ID.")
    @PreAuthorize(RoleConstants.CAN_READ_ALL)
    public ResponseEntity<Resource> download(@PathVariable String jobId) {
        // Sanitize jobId to prevent path traversal
        if (!jobId.matches("^[a-zA-Z0-9\\-]+$")) {
            return ResponseEntity.badRequest().build();
        }

        // Enforce ownership (or SA) based on persisted job record.
        // This prevents guessing/stealing a jobId and downloading another user's exports.
        ScopeHelper.Claims claims = currentClaims();
        ExportJobRecord record = exportJobRecordRepository.findById(jobId).orElse(null);
        if (record == null || record.getExpiresAt() == null || record.getExpiresAt().isBefore(LocalDateTime.now())) {
            // Do not reveal whether a jobId exists.
            return ResponseEntity.notFound().build();
        }
        if (!RoleConstants.SUPER_ADMIN.equals(claims.role()) && !claims.userId().equals(record.getActorUserId())) {
            return ResponseEntity.notFound().build();
        }

        // Determine file extension from format (default to csv if not set)
        String extension = (record.getFormat() != null && record.getFormat().equalsIgnoreCase("PDF")) ? "pdf" : "csv";
        Path filePath = Paths.get(exportBaseDir, jobId + "." + extension);
        FileSystemResource resource = new FileSystemResource(filePath);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        // Set correct content type based on format
        MediaType contentType = "pdf".equals(extension) 
                ? MediaType.APPLICATION_PDF 
                : MediaType.parseMediaType("text/csv");

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export-" + jobId + "." + extension + "\"")
                .body(resource);
    }

    private ResponseEntity<ApiResponse<ExportJobResponse>> buildExportResponse(ExportJobResponse result) {
        if ("ASYNC_ACCEPTED".equals(result.getStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success("Export queued. You will be notified via inbox on completion.", result));
        }
        return ResponseEntity.ok(ApiResponse.success("Export complete. Download is ready.", result));
    }

    private ScopeHelper.Claims currentClaims() {
        return (ScopeHelper.Claims) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}

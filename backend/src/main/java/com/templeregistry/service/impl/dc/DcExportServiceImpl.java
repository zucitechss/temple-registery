package com.templeregistry.service.impl.dc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.dto.request.export.ExportDeclarationsRequest;
import com.templeregistry.dto.request.export.ExportTemplesRequest;
import com.templeregistry.dto.response.dc.ExportJobResponse;
import com.templeregistry.entity.dc.ExportJobRecord;
import com.templeregistry.entity.dc.IdempotencyRecord;
import com.templeregistry.exception.ExportQueueFullException;
import com.templeregistry.exception.RateLimitExceededException;
import com.templeregistry.repository.dc.ExportJobRecordRepository;
import com.templeregistry.repository.dc.DcIdempotencyRecordRepository;
import com.templeregistry.repository.dc.RateRequestLogRepository;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.audit.AuditService;
import com.templeregistry.service.dc.DcExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * DC module export service.
 *
 * Applies three guards before execution:
 *  1. Idempotency check (5-min TTL, per actor+key)
 *  2. Rate limit check (max 5 exports per 10-min window per user)
 *  3. Sync vs Async dispatch (< 500 rows = sync, >= 500 rows = async)
 *
 * S10: @Async lives on AsyncExportBean (separate bean), not here.
 * R1: Calls repository directly — never calls DcTempleSearchService (would bypass @PreAuthorize).
 *
 * dc_e2e Sections 6.10, 2.9, 4.12b, 4.12c.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DcExportServiceImpl implements DcExportService {

    private static final int ASYNC_THRESHOLD    = 500;
    private static final int RATE_LIMIT_MAX     = 5;
    private static final String RATE_ENDPOINT   = "export";

    private final AsyncExportBean asyncExportBean;
    private final RateRequestLogRepository rateRepository;
    private final DcIdempotencyRecordRepository idempotencyRepository;
    private final ExportJobRecordRepository exportJobRecordRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Value("${trm.export.base-dir:./exports}")
    private String exportBaseDir;

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_READ_ALL)
    public ExportJobResponse exportTemples(ExportTemplesRequest request, String idempotencyKey,
                                           ScopeHelper.Claims claims) {
        Long districtId = resolveDistrictId(request.getDistrictId(), claims);

        // Idempotency guard (cache hit check)
        if (StringUtils.hasText(idempotencyKey)) {
            ExportJobResponse cached = checkIdempotency(idempotencyKey, claims);
            if (cached != null) return cached;
        }

        enforceRateLimit(claims);

        String jobId    = UUID.randomUUID().toString();
        long rowCount   = asyncExportBean.countTemples(districtId);
        storeExportJobRecord(jobId, claims.userId(), districtId, request.getFormat());

        ExportJobResponse response;

        if (rowCount < ASYNC_THRESHOLD) {
            response = runSyncTempleExport(jobId, districtId, rowCount, claims, request.getFormat());
        } else {
            response = submitAsyncTempleExport(jobId, districtId, rowCount, claims, request.getFormat());
        }

        storeIdempotencyResult(idempotencyKey, claims.userId(), response);
        auditService.logExportEvent(claims.userId(), claims.role(),
                "TEMPLES_" + request.getFormat(), "districtId=" + districtId, (int) rowCount);

        return response;
    }

    @Override
    @Transactional
    @PreAuthorize(RoleConstants.CAN_READ_ALL)
    public ExportJobResponse exportDeclarations(ExportDeclarationsRequest request, String idempotencyKey,
                                                ScopeHelper.Claims claims) {
        Long districtId = resolveDistrictId(request.getDistrictId(), claims);

        if (StringUtils.hasText(idempotencyKey)) {
            ExportJobResponse cached = checkIdempotency(idempotencyKey, claims);
            if (cached != null) return cached;
        }

        enforceRateLimit(claims);

        String jobId    = UUID.randomUUID().toString();
        long rowCount   = asyncExportBean.countDeclarations(districtId);
        storeExportJobRecord(jobId, claims.userId(), districtId, request.getFormat());

        ExportJobResponse response;

        if (rowCount < ASYNC_THRESHOLD) {
            response = runSyncDeclarationExport(jobId, districtId, rowCount, claims, request.getFormat());
        } else {
            response = submitAsyncDeclarationExport(jobId, districtId, rowCount, claims, request.getFormat());
        }

        storeIdempotencyResult(idempotencyKey, claims.userId(), response);
        auditService.logExportEvent(claims.userId(), claims.role(),
                "DECLARATIONS_" + request.getFormat(), "districtId=" + districtId, (int) rowCount);

        return response;
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private Long resolveDistrictId(Long requested, ScopeHelper.Claims claims) {
        if (RoleConstants.SUPER_ADMIN.equals(claims.role())) {
            return requested; // SUPER_ADMIN may provide or omit
        }
        return claims.districtId(); // JWT always wins for DC roles
    }

    private void enforceRateLimit(ScopeHelper.Claims claims) {
        LocalDateTime windowStart = currentWindowStart();
        rateRepository.upsertCount(claims.userId(), RATE_ENDPOINT, windowStart);

        rateRepository.findByUserIdAndEndpointKeyAndWindowStart(
                        claims.userId(), RATE_ENDPOINT, windowStart)
                .filter(log -> log.getRequestCount() > RATE_LIMIT_MAX)
                .ifPresent(log -> { throw new RateLimitExceededException(60); });
    }

    private LocalDateTime currentWindowStart() {
        LocalDateTime now = LocalDateTime.now();
        int truncatedMinute = (now.getMinute() / 10) * 10;
        return now.truncatedTo(ChronoUnit.HOURS).plusMinutes(truncatedMinute);
    }

    private ExportJobResponse checkIdempotency(String key, ScopeHelper.Claims claims) {
        return idempotencyRepository
                .findByActorUserIdAndIdempotencyKey(claims.userId(), key)
                .filter(r -> r.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(r -> {
                    try {
                        return objectMapper.readValue(r.getResponseBody(), ExportJobResponse.class);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize cached idempotency response for key={}", key);
                        return null;
                    }
                })
                .orElse(null);
    }

    private void storeIdempotencyResult(String key, Long userId, ExportJobResponse response) {
        if (!StringUtils.hasText(key)) return;
        try {
            String json = objectMapper.writeValueAsString(response);
            IdempotencyRecord record = IdempotencyRecord.builder()
                    .actorUserId(userId)
                    .idempotencyKey(key)
                    .responseBody(json)
                    .responseStatus(200)
                    .build();
            idempotencyRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate insert — cache hit on the concurrent request; safe to ignore
            log.debug("Idempotency record already exists for userId={} key={}", userId, key);
        } catch (Exception e) {
            log.warn("Failed to store idempotency record for key={}: {}", key, e.getMessage());
        }
    }

    private void storeExportJobRecord(String jobId, Long actorUserId, Long districtId, String format) {
        // Keep downloads possible for a reasonable window; file retention policy is handled at ops layer.
        LocalDateTime now = LocalDateTime.now();
        ExportJobRecord record = ExportJobRecord.builder()
                .jobId(jobId)
                .actorUserId(actorUserId)
                .districtId(districtId)
                .format(format != null ? format : "CSV")
                .createdAt(now)
                .expiresAt(now.plusDays(7))
                .build();
        exportJobRecordRepository.save(record);
    }

    private ExportJobResponse runSyncTempleExport(String jobId, Long districtId,
                                                   long rowCount, ScopeHelper.Claims claims, String format) {
        String extension = "PDF".equalsIgnoreCase(format) ? "pdf" : "csv";
        java.nio.file.Path outputPath = asyncExportBean.resolveOutputPath(jobId, extension);
        try {
            if ("PDF".equalsIgnoreCase(format)) {
                asyncExportBean.writeTemplesPdf(outputPath, districtId);
            } else {
                asyncExportBean.writeTemplesCsv(outputPath, districtId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Sync temple export failed: jobId=" + jobId, e);
        }
        String downloadUrl = "/api/v1/dc/export/" + jobId + "/download";
        log.info("Sync temple export complete: jobId={} rows={}", jobId, rowCount);
        return ExportJobResponse.builder()
                .jobId(jobId).format(format != null ? format : "CSV").status("SYNC_COMPLETE")
                .downloadUrl(downloadUrl).recordCount((int) rowCount)
                .build();
    }

    private ExportJobResponse runSyncDeclarationExport(String jobId, Long districtId,
                                                        long rowCount, ScopeHelper.Claims claims, String format) {
        String extension = "PDF".equalsIgnoreCase(format) ? "pdf" : "csv";
        java.nio.file.Path outputPath = asyncExportBean.resolveOutputPath(jobId, extension);
        try {
            if ("PDF".equalsIgnoreCase(format)) {
                asyncExportBean.writeDeclarationsPdf(outputPath, districtId);
            } else {
                asyncExportBean.writeDeclarationsCsv(outputPath, districtId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Sync declaration export failed: jobId=" + jobId, e);
        }
        String downloadUrl = "/api/v1/dc/export/" + jobId + "/download";
        log.info("Sync declaration export complete: jobId={} rows={}", jobId, rowCount);
        return ExportJobResponse.builder()
                .jobId(jobId).format(format != null ? format : "CSV").status("SYNC_COMPLETE")
                .downloadUrl(downloadUrl).recordCount((int) rowCount)
                .build();
    }

    private ExportJobResponse submitAsyncTempleExport(String jobId, Long districtId,
                                                       long rowCount, ScopeHelper.Claims claims, String format) {
        try {
            asyncExportBean.exportTemplesAsync(jobId, districtId, claims.userId(), format);
        } catch (RejectedExecutionException e) {
            throw new ExportQueueFullException();
        }
        log.info("Async temple export queued: jobId={} rows={} format={}", jobId, rowCount, format);
        return ExportJobResponse.builder()
                .jobId(jobId).format(format != null ? format : "CSV").status("ASYNC_ACCEPTED")
                .downloadUrl(null).recordCount((int) rowCount)
                .build();
    }

    private ExportJobResponse submitAsyncDeclarationExport(String jobId, Long districtId,
                                                            long rowCount, ScopeHelper.Claims claims, String format) {
        try {
            asyncExportBean.exportDeclarationsAsync(jobId, districtId, claims.userId(), format);
        } catch (RejectedExecutionException e) {
            throw new ExportQueueFullException();
        }
        log.info("Async declaration export queued: jobId={} rows={} format={}", jobId, rowCount, format);
        return ExportJobResponse.builder()
                .jobId(jobId).format(format != null ? format : "CSV").status("ASYNC_ACCEPTED")
                .downloadUrl(null).recordCount((int) rowCount)
                .build();
    }
}

package com.templeregistry.exception;

import com.templeregistry.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    /**
     * District scope violations always return HTTP 404 (never 403) with a FIXED
     * non-informative body to prevent information leakage about entity existence
     * in other districts. A random 0-20ms delay prevents timing-oracle attacks
     * that could distinguish "entity not found" from "entity out of district".
     * dc_e2e Section 2.4 (R7, R8).
     */
    @ExceptionHandler(DistrictScopeViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDistrictScopeViolation(DistrictScopeViolationException ex) {
        applyTimingDelay();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("The requested resource was not found.", null));
    }

    private void applyTimingDelay() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(0, 21));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A financial-year request parameter that is not canonical form (FIN-081).
     */
    @ExceptionHandler(InvalidFinancialYearException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidFinancialYear(InvalidFinancialYearException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), "INVALID_FINANCIAL_YEAR"));
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalTransition(IllegalStatusTransitionException ex) {
        log.warn("Illegal status transition: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "ILLEGAL_STATUS_TRANSITION"));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidStateTransition(InvalidStateTransitionException ex) {
        log.warn("Invalid state transition: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "INVALID_STATE_TRANSITION"));
    }

    @ExceptionHandler(DeclarationImmutableException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeclarationImmutable(DeclarationImmutableException ex) {
        log.warn("Declaration immutable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "DECLARATION_IMMUTABLE"));
    }

    @ExceptionHandler(AcknowledgementNotAvailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAcknowledgementNotAvailable(AcknowledgementNotAvailableException ex) {
        log.warn("Acknowledgement not available: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ex.getMessage(), "ACKNOWLEDGEMENT_NOT_AVAILABLE"));
    }

    @ExceptionHandler(ClarificationLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleClarificationLimit(ClarificationLimitExceededException ex) {
        log.warn("Clarification limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ex.getMessage(), "TRM-DECL-009"));
    }

    @ExceptionHandler(DeclarationAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeclarationAlreadyExists(DeclarationAlreadyExistsException ex) {
        log.warn("Declaration already exists: FY={}, existingId={}", ex.getFinancialYear(), ex.getExistingDeclarationId());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "DECLARATION_ALREADY_EXISTS"));
    }

    @ExceptionHandler(ImmutableResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleImmutable(ImmutableResourceException ex) {
        log.warn("Immutable resource mutation blocked: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "IMMUTABLE_RESOURCE"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ex.getMessage(), "ILLEGAL_STATE"));
    }

    @ExceptionHandler(JurisdictionAccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleJurisdiction(JurisdictionAccessDeniedException ex) {
        log.warn("Jurisdiction violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage(), "JURISDICTION_DENIED"));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "DUPLICATE_RESOURCE"));
    }

    @ExceptionHandler(FileValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleFileValidation(FileValidationException ex) {
        log.warn("File validation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), "FILE_VALIDATION_ERROR"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("File size exceeds the allowed limit of 5 MB.", "FILE_TOO_LARGE"));
    }

    @ExceptionHandler(MfaVerificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMfa(MfaVerificationException ex) {
        log.warn("MFA verification failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage(), "MFA_VERIFICATION_FAILED"));
    }

    @ExceptionHandler(AadhaarVerificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAadhaar(AadhaarVerificationException ex) {
        log.warn("Aadhaar verification failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), "AADHAAR_VERIFICATION_FAILED"));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccountLocked(AccountLockedException ex) {
        log.warn("Account locked attempt: retryAfter={}", ex.getRetryAfterEpochSeconds());
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ApiResponse.error(ex.getMessage(), "ACCOUNT_LOCKED"));
    }

    @ExceptionHandler(AcknowledgementNumberConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleAckConflict(AcknowledgementNumberConflictException ex) {
        log.error("Acknowledgement sequence conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Acknowledgement generation failed. Please retry.", "TRM-ACK-001"));
    }

    @ExceptionHandler(ExportQueueFullException.class)
    public ResponseEntity<ApiResponse<Void>> handleExportQueueFull(ExportQueueFullException ex) {
        log.warn("Export queue full: retryAfter={}s", ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiResponse.error(ex.getMessage(), "TRM-EXPORT-QUEUE-FULL"));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: retryAfter={}s", ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiResponse.error(ex.getMessage(), "TRM-RATE-LIMIT"));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "This record was modified by another request. Please refresh and try again.",
                        "OPTIMISTIC_LOCK_CONFLICT"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to perform this action.", "ACCESS_DENIED"));
    }

    /**
     * Handles java.lang.SecurityException thrown by TokenRevocationGuard and AuthServiceImpl
     * for revoked / expired / not-found refresh tokens.
     * Must map to 401 — NOT 500 — so the frontend re-auth flow is triggered correctly.
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(SecurityException ex) {
        log.warn("Security violation (token/auth): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication failed. Please log in again.", "AUTH_FAILED"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        log.warn("Validation failed: {} errors", errors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.validationError("Request validation failed.", errors));
    }

    @ExceptionHandler(com.templeregistry.exception.WorkflowException.class)
    public ResponseEntity<ApiResponse<Void>> handleWorkflowException(com.templeregistry.exception.WorkflowException ex) {
        log.warn("Workflow transition error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "WORKFLOW_TRANSITION_ERROR"));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("Message not readable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Invalid request body: " + ex.getMostSpecificCause().getMessage(), "INVALID_REQUEST"));
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(org.springframework.web.context.request.async.AsyncRequestTimeoutException ex) {
        // SSE / long-poll connections time out normally — swallow silently, do NOT write a JSON body
        // because the response Content-Type is already text/event-stream and Jackson cannot serialize into it.
        log.debug("Async request timed out (SSE/long-poll keep-alive — expected)");
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(org.springframework.web.context.request.async.AsyncRequestNotUsableException ex) {
        // Client disconnected mid-stream (browser tab closed, navigation, network drop).
        // The SSE emitter's onCompletion/onError callback handles cleanup.
        // Do NOT attempt to write a response body — the connection is already gone.
        log.debug("SSE client disconnected (expected): {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please contact support.", "INTERNAL_ERROR"));
    }
}

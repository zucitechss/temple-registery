package com.templeregistry.exception;

import com.templeregistry.common.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(DataSize.ofMegabytes(10));
    }

    // ─── EntityNotFoundException ──────────────────────────────────────────────

    @Nested
    class EntityNotFoundExceptionHandling {
        @Test
        void should_return404_when_entityNotFound_byMessage() {
            EntityNotFoundException ex = new EntityNotFoundException("Temple not found", "TEMPLE_NOT_FOUND");

            ResponseEntity<ApiResponse<Void>> response = handler.handleEntityNotFound(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getErrorCode()).isEqualTo("TEMPLE_NOT_FOUND");
            assertThat(response.getBody().getMessage()).isEqualTo("Temple not found");
        }

        @Test
        void should_return404_when_entityNotFound_byId() {
            EntityNotFoundException ex = new EntityNotFoundException("Temple", 42L);

            ResponseEntity<ApiResponse<Void>> response = handler.handleEntityNotFound(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().getErrorCode()).isEqualTo("TEMPLE_NOT_FOUND");
        }
    }

    // ─── DistrictScopeViolationException ─────────────────────────────────────

    @Nested
    class DistrictScopeViolationHandling {
        @Test
        void should_return404_notInfoLeaking_when_districtScopeViolated() {
            DistrictScopeViolationException ex = mock(DistrictScopeViolationException.class);

            ResponseEntity<ApiResponse<Void>> response = handler.handleDistrictScopeViolation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            // Must NOT expose actual reason — fixed non-informative message
            assertThat(response.getBody().getMessage()).isEqualTo("The requested resource was not found.");
            assertThat(response.getBody().getErrorCode()).isNull();
        }
    }

    // ─── IllegalStatusTransitionException ────────────────────────────────────

    @Nested
    class IllegalStatusTransitionHandling {
        @Test
        void should_return409_when_illegalStatusTransition() {
            IllegalStatusTransitionException ex = mock(IllegalStatusTransitionException.class);
            when(ex.getMessage()).thenReturn("Cannot transition from APPROVED to DRAFT");

            ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalTransition(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("ILLEGAL_STATUS_TRANSITION");
        }
    }

    // ─── InvalidStateTransitionException ─────────────────────────────────────

    @Nested
    class InvalidStateTransitionHandling {
        @Test
        void should_return409_when_invalidStateTransition() {
            InvalidStateTransitionException ex = mock(InvalidStateTransitionException.class);
            when(ex.getMessage()).thenReturn("Invalid state transition");

            ResponseEntity<ApiResponse<Void>> response = handler.handleInvalidStateTransition(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("INVALID_STATE_TRANSITION");
        }
    }

    // ─── DeclarationImmutableException ───────────────────────────────────────

    @Nested
    class DeclarationImmutableHandling {
        @Test
        void should_return409_when_declarationIsImmutable() {
            DeclarationImmutableException ex = mock(DeclarationImmutableException.class);
            when(ex.getMessage()).thenReturn("Declaration is immutable");

            ResponseEntity<ApiResponse<Void>> response = handler.handleDeclarationImmutable(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("DECLARATION_IMMUTABLE");
        }
    }

    // ─── AcknowledgementNotAvailableException ────────────────────────────────

    @Nested
    class AcknowledgementNotAvailableHandling {
        @Test
        void should_return422_when_acknowledgementNotAvailable() {
            AcknowledgementNotAvailableException ex = mock(AcknowledgementNotAvailableException.class);
            when(ex.getMessage()).thenReturn("Acknowledgement not available");

            ResponseEntity<ApiResponse<Void>> response = handler.handleAcknowledgementNotAvailable(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().getErrorCode()).isEqualTo("ACKNOWLEDGEMENT_NOT_AVAILABLE");
        }
    }

    // ─── ClarificationLimitExceededException ─────────────────────────────────

    @Nested
    class ClarificationLimitHandling {
        @Test
        void should_return422_when_clarificationLimitExceeded() {
            ClarificationLimitExceededException ex = mock(ClarificationLimitExceededException.class);
            when(ex.getMessage()).thenReturn("Max clarification rounds reached");

            ResponseEntity<ApiResponse<Void>> response = handler.handleClarificationLimit(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().getErrorCode()).isEqualTo("TRM-DECL-009");
        }
    }

    // ─── DeclarationAlreadyExistsException ───────────────────────────────────

    @Nested
    class DeclarationAlreadyExistsHandling {
        @Test
        void should_return409_when_declarationAlreadyExists() {
            DeclarationAlreadyExistsException ex = mock(DeclarationAlreadyExistsException.class);
            when(ex.getMessage()).thenReturn("Declaration already exists");
            when(ex.getFinancialYear()).thenReturn("2024-25");
            when(ex.getExistingDeclarationId()).thenReturn(7L);

            ResponseEntity<ApiResponse<Void>> response = handler.handleDeclarationAlreadyExists(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("DECLARATION_ALREADY_EXISTS");
        }
    }

    // ─── ImmutableResourceException ──────────────────────────────────────────

    @Nested
    class ImmutableResourceHandling {
        @Test
        void should_return409_when_immutableResourceMutationBlocked() {
            ImmutableResourceException ex = mock(ImmutableResourceException.class);
            when(ex.getMessage()).thenReturn("Resource is immutable");

            ResponseEntity<ApiResponse<Void>> response = handler.handleImmutable(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("IMMUTABLE_RESOURCE");
        }
    }

    // ─── IllegalStateException ────────────────────────────────────────────────

    @Nested
    class IllegalStateHandling {
        @Test
        void should_return422_when_illegalState() {
            IllegalStateException ex = new IllegalStateException("Invalid operation in current state");

            ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalState(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().getErrorCode()).isEqualTo("ILLEGAL_STATE");
            assertThat(response.getBody().getMessage()).isEqualTo("Invalid operation in current state");
        }
    }

    // ─── JurisdictionAccessDeniedException ───────────────────────────────────

    @Nested
    class JurisdictionHandling {
        @Test
        void should_return403_when_jurisdictionDenied() {
            JurisdictionAccessDeniedException ex = mock(JurisdictionAccessDeniedException.class);
            when(ex.getMessage()).thenReturn("Outside jurisdiction");

            ResponseEntity<ApiResponse<Void>> response = handler.handleJurisdiction(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getErrorCode()).isEqualTo("JURISDICTION_DENIED");
        }
    }

    // ─── DuplicateResourceException ──────────────────────────────────────────

    @Nested
    class DuplicateResourceHandling {
        @Test
        void should_return409_when_duplicateResource() {
            DuplicateResourceException ex = mock(DuplicateResourceException.class);
            when(ex.getMessage()).thenReturn("Resource already exists");

            ResponseEntity<ApiResponse<Void>> response = handler.handleDuplicate(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("DUPLICATE_RESOURCE");
        }
    }

    // ─── FileValidationException ──────────────────────────────────────────────

    @Nested
    class FileValidationHandling {
        @Test
        void should_return400_when_fileValidationFails() {
            FileValidationException ex = mock(FileValidationException.class);
            when(ex.getMessage()).thenReturn("Invalid file type");

            ResponseEntity<ApiResponse<Void>> response = handler.handleFileValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getErrorCode()).isEqualTo("FILE_VALIDATION_ERROR");
        }
    }

    // ─── MaxUploadSizeExceededException ──────────────────────────────────────

    @Nested
    class MaxUploadSizeHandling {
        @Test
        void should_return400_withTheConfiguredLimit_when_fileTooLarge() {
            // The message used to be hardcoded to "5 MB" while the servlet actually rejected
            // at 1 MB and the domain allowed 10 MB, so it told the user a number that was
            // true nowhere. It now reports whatever spring.servlet.multipart is configured to.
            MaxUploadSizeExceededException ex = mock(MaxUploadSizeExceededException.class);

            ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSize(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getErrorCode()).isEqualTo("FILE_TOO_LARGE");
            assertThat(response.getBody().getMessage()).contains("10 MB");
        }

        @Test
        void should_reportTheLimitItIsGiven_when_configurationChanges() {
            ResponseEntity<ApiResponse<Void>> response =
                    new GlobalExceptionHandler(DataSize.ofMegabytes(25))
                            .handleMaxUploadSize(mock(MaxUploadSizeExceededException.class));

            assertThat(response.getBody().getMessage()).contains("25 MB");
        }
    }

    // ─── MfaVerificationException ─────────────────────────────────────────────

    @Nested
    class MfaVerificationHandling {
        @Test
        void should_return401_when_mfaFails() {
            MfaVerificationException ex = mock(MfaVerificationException.class);
            when(ex.getMessage()).thenReturn("Invalid OTP");

            ResponseEntity<ApiResponse<Void>> response = handler.handleMfa(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().getErrorCode()).isEqualTo("MFA_VERIFICATION_FAILED");
        }
    }

    // ─── AccountLockedException ───────────────────────────────────────────────

    @Nested
    class AccountLockedHandling {
        @Test
        void should_return423_when_accountLocked() {
            AccountLockedException ex = mock(AccountLockedException.class);
            when(ex.getMessage()).thenReturn("Account locked until 2026-05-21T18:00:00Z");
            when(ex.getRetryAfterEpochSeconds()).thenReturn(1716315600L);

            ResponseEntity<ApiResponse<Void>> response = handler.handleAccountLocked(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
            assertThat(response.getBody().getErrorCode()).isEqualTo("ACCOUNT_LOCKED");
        }
    }

    // ─── AcknowledgementNumberConflictException ───────────────────────────────

    @Nested
    class AcknowledgementConflictHandling {
        @Test
        void should_return500_withSafeMessage_when_ackConflict() {
            AcknowledgementNumberConflictException ex = mock(AcknowledgementNumberConflictException.class);
            when(ex.getMessage()).thenReturn("Sequence conflict");

            ResponseEntity<ApiResponse<Void>> response = handler.handleAckConflict(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getErrorCode()).isEqualTo("TRM-ACK-001");
            // Must not expose internal message - use safe message
            assertThat(response.getBody().getMessage()).contains("retry");
        }
    }

    // ─── ExportQueueFullException ─────────────────────────────────────────────

    @Nested
    class ExportQueueFullHandling {
        @Test
        void should_return503_withRetryAfterHeader_when_exportQueueFull() {
            ExportQueueFullException ex = mock(ExportQueueFullException.class);
            when(ex.getMessage()).thenReturn("Export queue is full");
            when(ex.getRetryAfterSeconds()).thenReturn(30);

            ResponseEntity<ApiResponse<Void>> response = handler.handleExportQueueFull(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().getErrorCode()).isEqualTo("TRM-EXPORT-QUEUE-FULL");
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        }
    }

    // ─── RateLimitExceededException ───────────────────────────────────────────

    @Nested
    class RateLimitHandling {
        @Test
        void should_return429_withRetryAfterHeader_when_rateLimitExceeded() {
            RateLimitExceededException ex = mock(RateLimitExceededException.class);
            when(ex.getMessage()).thenReturn("Too many requests");
            when(ex.getRetryAfterSeconds()).thenReturn(60);

            ResponseEntity<ApiResponse<Void>> response = handler.handleRateLimit(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getBody().getErrorCode()).isEqualTo("TRM-RATE-LIMIT");
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        }
    }

    // ─── OptimisticLockingFailureException ────────────────────────────────────

    @Nested
    class OptimisticLockingHandling {
        @Test
        void should_return409_when_optimisticLockConflict() {
            OptimisticLockingFailureException ex = new OptimisticLockingFailureException("Version mismatch");

            ResponseEntity<ApiResponse<Void>> response = handler.handleOptimisticLock(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
            assertThat(response.getBody().getMessage()).contains("refresh");
        }
    }

    // ─── AccessDeniedException ────────────────────────────────────────────────

    @Nested
    class AccessDeniedHandling {
        @Test
        void should_return403_when_accessDenied() {
            AccessDeniedException ex = new AccessDeniedException("Access denied");

            ResponseEntity<ApiResponse<Void>> response = handler.handleAccessDenied(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getErrorCode()).isEqualTo("ACCESS_DENIED");
            assertThat(response.getBody().getMessage()).isEqualTo("You do not have permission to perform this action.");
        }
    }

    // ─── SecurityException ────────────────────────────────────────────────────

    @Nested
    class SecurityExceptionHandling {
        @Test
        void should_return401_when_tokenRevoked() {
            SecurityException ex = new SecurityException("Token revoked");

            ResponseEntity<ApiResponse<Void>> response = handler.handleSecurityException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().getErrorCode()).isEqualTo("AUTH_FAILED");
        }
    }

    // ─── MethodArgumentNotValidException ─────────────────────────────────────

    @Nested
    class ValidationHandling {
        @Test
        void should_return400_withFieldErrors_when_validationFails() {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("request", "name", "Name is required");
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
            when(ex.getBindingResult()).thenReturn(bindingResult);

            ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getErrorCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().getErrors()).containsExactly("Name is required");
        }

        @Test
        void should_return400_withMultipleErrors_when_multipleFieldsFail() {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            List<FieldError> errors = List.of(
                new FieldError("request", "name", "Name is required"),
                new FieldError("request", "email", "Email is invalid")
            );
            when(bindingResult.getFieldErrors()).thenReturn(errors);
            when(ex.getBindingResult()).thenReturn(bindingResult);

            ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getErrors()).hasSize(2);
        }
    }

    // ─── WorkflowException ───────────────────────────────────────────────────

    @Nested
    class WorkflowExceptionHandling {
        @Test
        void should_return409_when_workflowTransitionFails() {
            WorkflowException ex = new WorkflowException("Action not allowed in current workflow state");

            ResponseEntity<ApiResponse<Void>> response = handler.handleWorkflowException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getErrorCode()).isEqualTo("WORKFLOW_TRANSITION_ERROR");
            assertThat(response.getBody().getMessage()).isEqualTo("Action not allowed in current workflow state");
        }
    }

    // ─── HttpMessageNotReadableException ─────────────────────────────────────

    @Nested
    class MessageNotReadableHandling {
        @Test
        void should_return400_when_requestBodyInvalid() {
            HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Could not read document"
            );

            ResponseEntity<ApiResponse<Void>> response = handler.handleMessageNotReadable(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getErrorCode()).isEqualTo("INVALID_REQUEST");
        }
    }

    // ─── Generic Exception (fallback) ────────────────────────────────────────

    @Nested
    class GenericExceptionHandling {
        @Test
        void should_return500_withGenericMessage_when_unexpectedExceptionThrown() {
            Exception ex = new RuntimeException("NPE during processing");

            ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
            // Must NOT expose internal error details - use fixed safe message
            assertThat(response.getBody().getMessage()).doesNotContain("NPE during processing");
            assertThat(response.getBody().getErrorCode()).isEqualTo("INTERNAL_ERROR");
        }
    }
}

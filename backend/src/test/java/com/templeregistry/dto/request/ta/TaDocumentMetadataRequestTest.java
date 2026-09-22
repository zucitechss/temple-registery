package com.templeregistry.dto.request.ta;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VAL-004 — "Document upload — file type | PDF only (MIME: application/pdf); enforced client +
 * server". This DTO's {@code mimeType} pattern used to also accept image/jpeg and image/png,
 * conflated from VAL-006 (temple/TA profile photograph, a separate upload path). TA document
 * registration must accept PDF only, matching every frontend document-upload UI.
 */
class TaDocumentMetadataRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static TaDocumentMetadataRequest.TaDocumentMetadataRequestBuilder validRequest() {
        return TaDocumentMetadataRequest.builder()
                .s3Key("temple/10/docs/trust-deed.pdf")
                .mimeType("application/pdf")
                .fileSizeBytes(1024L)
                .originalFilename("trust-deed.pdf")
                .documentLabel("Trust Deed");
    }

    @Test
    void should_haveNoViolations_when_mimeTypeIsPdf() {
        Set<ConstraintViolation<TaDocumentMetadataRequest>> violations =
                validator.validate(validRequest().mimeType("application/pdf").build());

        assertThat(violations).isEmpty();
    }

    @Test
    void should_rejectMimeType_when_itIsJpeg() {
        Set<ConstraintViolation<TaDocumentMetadataRequest>> violations =
                validator.validate(validRequest().mimeType("image/jpeg").build());

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("mimeType"));
    }

    @Test
    void should_rejectMimeType_when_itIsPng() {
        Set<ConstraintViolation<TaDocumentMetadataRequest>> violations =
                validator.validate(validRequest().mimeType("image/png").build());

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("mimeType"));
    }
}

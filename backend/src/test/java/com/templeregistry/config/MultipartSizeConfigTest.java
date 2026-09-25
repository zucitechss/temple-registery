package com.templeregistry.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-4 — the servlet multipart limits must match what the application actually accepts.
 *
 * <p>Spring Boot defaults to {@code max-file-size=1MB} / {@code max-request-size=10MB}. Those
 * defaults are applied during multipart parsing, i.e. before any controller or service runs,
 * so an unconfigured deployment rejects every upload above 1 MB no matter what the domain
 * validation permits. The spec requires 10 MB documents (VAL-005) and 5 MB temple photos
 * (VAL-006), both "enforced client + server".</p>
 *
 * <p>These assertions read the shipped YAML rather than standing up a servlet container.
 * MockMvc performs its own multipart assembly and never consults {@link MultipartProperties},
 * so a MockMvc test cannot observe these limits at all — the configuration itself is the only
 * thing that can be verified automatically. An end-to-end oversized upload against a running
 * container is the manual complement, recorded in the H-4 report.</p>
 */
class MultipartSizeConfigTest {

    /** VAL-005: the largest single file the application legitimately accepts. */
    private static final DataSize LARGEST_ALLOWED_FILE = DataSize.ofMegabytes(10);

    /** Temple photos are uploaded as one batched multipart request (see MultipleImageUpload). */
    private static final DataSize LARGEST_ALLOWED_REQUEST = DataSize.ofMegabytes(25);

    private static StandardEnvironment environmentOf(String... yamlResources) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : yamlResources) {
            for (PropertySource<?> source : loader.load(resource, new ClassPathResource(resource))) {
                // addFirst: later resources win, so profile YAML overrides the base.
                environment.getPropertySources().addFirst(source);
            }
        }
        return environment;
    }

    private static MultipartProperties multipartPropertiesOf(String... yamlResources) throws IOException {
        return Binder.get(environmentOf(yamlResources))
                .bind("spring.servlet.multipart", MultipartProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "spring.servlet.multipart is not configured — Boot's 1MB default applies."));
    }

    @Nested
    class DefaultConfiguration {

        @Test
        void should_acceptFilesUpToTheDocumentLimit_when_baseConfigurationIsUsed() throws IOException {
            MultipartProperties properties = multipartPropertiesOf("application.yml");

            assertThat(properties.getMaxFileSize()).isEqualTo(LARGEST_ALLOWED_FILE);
        }

        @Test
        void should_acceptABatchedPhotoRequest_when_baseConfigurationIsUsed() throws IOException {
            MultipartProperties properties = multipartPropertiesOf("application.yml");

            assertThat(properties.getMaxRequestSize()).isEqualTo(LARGEST_ALLOWED_REQUEST);
        }

        @Test
        void should_allowAWholeFileWithinTheRequestBudget_when_uploadingOneDocument() throws IOException {
            MultipartProperties properties = multipartPropertiesOf("application.yml");

            // A single-file upload must never be rejected by the request limit instead of the
            // file limit — that produces a confusing error for a legitimate document.
            assertThat(properties.getMaxRequestSize().toBytes())
                    .isGreaterThan(properties.getMaxFileSize().toBytes());
        }
    }

    @Nested
    class ProductionConfiguration {

        @Test
        void should_applyTheSameFileLimit_when_productionProfileIsActive() throws IOException {
            MultipartProperties properties =
                    multipartPropertiesOf("application.yml", "application-prod.yml");

            assertThat(properties.getMaxFileSize()).isEqualTo(LARGEST_ALLOWED_FILE);
        }

        @Test
        void should_applyTheSameRequestLimit_when_productionProfileIsActive() throws IOException {
            MultipartProperties properties =
                    multipartPropertiesOf("application.yml", "application-prod.yml");

            assertThat(properties.getMaxRequestSize()).isEqualTo(LARGEST_ALLOWED_REQUEST);
        }
    }

    @Nested
    class RejectionPath {

        @Test
        void should_swallowAnOversizedBody_so_theClientReceivesTheJsonError() throws IOException {
            // Tomcat aborts the connection once it has discarded max-swallow-size bytes of a
            // rejected request (default 2 MB). The client then sees a reset instead of the
            // FILE_TOO_LARGE body, so this must comfortably exceed the request limit.
            DataSize maxSwallowSize = Binder.get(environmentOf("application.yml"))
                    .bind("server.tomcat.max-swallow-size", DataSize.class)
                    .orElseThrow(() -> new AssertionError(
                            "server.tomcat.max-swallow-size is not configured — oversized "
                                    + "uploads are answered with a connection reset."));

            assertThat(maxSwallowSize.toBytes())
                    .isGreaterThan(LARGEST_ALLOWED_REQUEST.toBytes());
        }
    }
}

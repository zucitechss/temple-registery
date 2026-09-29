package com.templeregistry.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-6 — the OpenAPI document and Swagger UI must not be served in production.
 *
 * <p>Verified against a running server before this test was written: unauthenticated
 * {@code GET /v3/api-docs} returned 200 with a 233 KB document describing every endpoint of
 * all 39 controllers, and {@code /swagger-ui.html} served the UI. {@code application-prod.yml}
 * inherits {@code springdoc.*} from {@code application.yml} and carried a comment deferring
 * the fix to this task.</p>
 *
 * <p>Springdoc's own behaviour cannot be exercised here — it needs a servlet container, and
 * MockMvc does not run its auto-configuration. This test pins the configuration; the slice
 * tests pin who may reach the paths; a live probe covers the rest.</p>
 */
class SwaggerExposureConfigTest {

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

    private static boolean flag(StandardEnvironment environment, String key) {
        return Binder.get(environment).bind(key, Boolean.class).orElse(true);
    }

    @Nested
    class Production {

        @Test
        void should_notServeTheOpenApiDocument_when_productionProfileIsActive() throws IOException {
            StandardEnvironment environment =
                    environmentOf("application.yml", "application-prod.yml");

            assertThat(flag(environment, "springdoc.api-docs.enabled"))
                    .as("springdoc.api-docs.enabled in production")
                    .isFalse();
        }

        @Test
        void should_notServeSwaggerUi_when_productionProfileIsActive() throws IOException {
            StandardEnvironment environment =
                    environmentOf("application.yml", "application-prod.yml");

            assertThat(flag(environment, "springdoc.swagger-ui.enabled"))
                    .as("springdoc.swagger-ui.enabled in production")
                    .isFalse();
        }
    }

    @Nested
    class Development {

        @Test
        void should_keepApiDocsAvailable_when_developmentProfileIsActive() throws IOException {
            // Disabling these globally would remove a tool the team uses daily; H-6 is about
            // production exposure, not about removing the documentation altogether.
            StandardEnvironment environment =
                    environmentOf("application.yml", "application-dev.yml");

            assertThat(flag(environment, "springdoc.api-docs.enabled")).isTrue();
            assertThat(flag(environment, "springdoc.swagger-ui.enabled")).isTrue();
        }
    }
}

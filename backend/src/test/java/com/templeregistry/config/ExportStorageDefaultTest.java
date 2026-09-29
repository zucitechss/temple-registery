package com.templeregistry.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-3 — every class that resolves {@code trm.export.base-dir} must agree on where an
 * export lands if the property is ever absent.
 *
 * <p>{@code AsyncExportBean}, {@code DcExportController} and {@code DcExportServiceImpl}
 * each read the property independently via {@code @Value}, and each carried its own inline
 * fallback of {@code /data/exports} — silently different from {@code application.yml}'s
 * actual default of {@code ./exports}. It never fired: {@code application.yml} sets the
 * property unconditionally in every profile, so YAML always won. But it was a landmine —
 * removing that one YAML line would silently change the effective path in three files at
 * once, to a directory that does not exist in this container (H-8 creates
 * {@code /app/exports}, not {@code /data/exports}).</p>
 *
 * <p>This reads the actual YAML resolution (what Spring really does with no profile
 * active) rather than asserting against the source of any one {@code @Value} fallback
 * string, so it fails if the YAML default and the Java fallback ever diverge again,
 * regardless of which side changes.</p>
 */
class ExportStorageDefaultTest {

    /** Surefire's working directory is the backend module itself. */
    private static final Path BACKEND = Path.of(".").toAbsolutePath().normalize();

    private static String resolvedExportBaseDir(String... yamlResources) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : yamlResources) {
            for (PropertySource<?> source : loader.load(resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addFirst(source);
            }
        }
        return Binder.get(environment).bind("trm.export.base-dir", String.class).orElse(null);
    }

    @Test
    void should_matchTheYamlDefault_when_noPropertyOverrideIsSupplied() throws IOException {
        // application.yml alone (no profile) is the floor every environment inherits.
        assertThat(resolvedExportBaseDir("application.yml")).isEqualTo("./exports");
    }

    /**
     * The three @Value fallbacks, read from source so this fails loudly if any one of
     * them drifts from the YAML default again, rather than only from the assertion above.
     */
    @Test
    void should_haveTheSameFallbackDefault_acrossEveryClassThatResolvesIt() throws IOException {
        List<String> relativePaths = List.of(
                "src/main/java/com/templeregistry/service/impl/dc/AsyncExportBean.java",
                "src/main/java/com/templeregistry/controller/dc/DcExportController.java",
                "src/main/java/com/templeregistry/service/impl/dc/DcExportServiceImpl.java"
        );
        String yamlDefault = resolvedExportBaseDir("application.yml");
        String expectedFallback = "trm.export.base-dir:" + yamlDefault + "}";

        for (String relativePath : relativePaths) {
            Path path = BACKEND.resolve(relativePath);
            assertThat(path).as("%s must exist", relativePath).exists();
            String source = Files.readString(path);

            assertThat(source)
                    .as("%s must fall back to the same default as application.yml (%s)",
                            path.getFileName(), yamlDefault)
                    .contains(expectedFallback);
        }
    }
}

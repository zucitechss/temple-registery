package com.templeregistry.service.finance.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for the finance integration boundary.
 *
 * <p>{@link SyncWorkerProfileBoundaryTest} proves the beans that exist today are wired
 * correctly. This test protects the boundary against what is added tomorrow: a connector
 * annotated {@code @Component} would be picked up by the application-wide component scan
 * and silently placed in the registry runtime, which is risk R12 -- the most likely way
 * this architecture degrades without anyone intending it.
 */
class FinanceIntegrationBoundaryTest {

    /** Packages that may contain code capable of reaching a temple source system. */
    private static final List<String> INTEGRATION_PACKAGES = List.of(
            "com.templeregistry.service.finance.sync",
            "com.templeregistry.connector");

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    @DisplayName("No integration class is component-scannable under the normal application profile")
    void should_findNoScannableComponents_when_syncWorkerProfileInactive() {
        var provider = new ClassPathScanningCandidateComponentProvider(true, new StandardEnvironment());

        for (String pkg : INTEGRATION_PACKAGES) {
            Set<BeanDefinition> found = provider.findCandidateComponents(pkg);
            assertThat(found)
                    .as("Package %s must contribute no beans to the registry runtime. "
                            + "Register worker beans as @Bean methods in SyncWorkerConfig instead of "
                            + "annotating them @Component.", pkg)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Every component in an integration package declares the sync-worker profile")
    void should_requireSyncWorkerProfile_when_integrationClassIsAComponent() {
        MockEnvironment workerEnvironment = new MockEnvironment();
        workerEnvironment.setActiveProfiles("sync-worker");
        var provider = new ClassPathScanningCandidateComponentProvider(true, workerEnvironment);

        for (String pkg : INTEGRATION_PACKAGES) {
            for (BeanDefinition definition : provider.findCandidateComponents(pkg)) {
                Class<?> type = classFor(definition);
                Profile profile = type.getAnnotation(Profile.class);

                assertThat(profile)
                        .as("%s is component-scannable and must be annotated @Profile(FinanceProfiles.SYNC_WORKER)",
                                type.getName())
                        .isNotNull();
                assertThat(Arrays.asList(profile.value()))
                        .as("%s must be restricted to the sync-worker profile", type.getName())
                        .contains("sync-worker");
            }
        }
    }

    @Test
    @DisplayName("No web endpoint is declared inside an integration package")
    void should_declareNoControllers_when_scanningIntegrationPackages() {
        MockEnvironment workerEnvironment = new MockEnvironment();
        workerEnvironment.setActiveProfiles("sync-worker");

        var provider = new ClassPathScanningCandidateComponentProvider(false, workerEnvironment);
        provider.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Controller.class, true));

        for (String pkg : INTEGRATION_PACKAGES) {
            assertThat(provider.findCandidateComponents(pkg))
                    .as("Package %s must not expose HTTP endpoints. The worker holds temple "
                            + "credentials and does not serve web traffic.", pkg)
                    .isEmpty();
        }
    }

    /**
     * No committed YAML may carry a source credential.
     *
     * <p>{@code application.yml} already contains fallback defaults for the registry
     * database username and password. That is a known issue on its own; what this test
     * prevents is temple source credentials joining them, which would put a usable route
     * into a government temple database into version control.
     */
    @Test
    @DisplayName("No committed configuration file declares a temple source credential")
    void should_containNoSourceCredentials_when_scanningCommittedConfig() throws IOException {
        try (var files = Files.list(RESOURCES)) {
            List<Path> configs = files
                    .filter(p -> p.getFileName().toString().matches("application.*\\.(yml|yaml|properties)"))
                    .toList();

            assertThat(configs).as("expected to find committed application config to inspect").isNotEmpty();

            for (Path config : configs) {
                String contents = Files.readString(config, StandardCharsets.UTF_8);
                String withoutComments = contents.lines()
                        .filter(line -> !line.strip().startsWith("#"))
                        .reduce("", (a, b) -> a + "\n" + b);

                assertThat(withoutComments)
                        .as("%s must not declare source credential properties", config.getFileName())
                        .doesNotContain(EnvironmentSourceCredentialProvider.PROPERTY_PREFIX);
            }
        }
    }

    /** The worker profile must disable the web layer declaratively, not only by guard. */
    @Test
    @DisplayName("sync-worker profile disables the web application type")
    void should_disableWebLayer_when_syncWorkerProfileConfigured() throws IOException {
        String workerConfig = Files.readString(
                RESOURCES.resolve("application-sync-worker.yml"), StandardCharsets.UTF_8);

        assertThat(workerConfig).contains("web-application-type: none");
        // The registry owns schema; two processes performing DDL is a race with no upside.
        assertThat(workerConfig).contains("ddl-auto: none");
    }

    private static Class<?> classFor(BeanDefinition definition) {
        try {
            return Class.forName(definition.getBeanClassName());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Scanned class not loadable: " + definition.getBeanClassName(), e);
        }
    }

    /** Guards the guard: the scanner must actually be capable of finding a component. */
    @Test
    @DisplayName("Scanner sanity check")
    void should_findComponents_when_scanningAPackageThatHasThem() {
        var provider = new ClassPathScanningCandidateComponentProvider(false, new StandardEnvironment());
        provider.addIncludeFilter(new AnnotationTypeFilter(Component.class, true));

        assertThat(provider.findCandidateComponents("com.templeregistry.config"))
                .as("scanner is misconfigured if it cannot find known configuration classes")
                .isNotEmpty();
    }
}

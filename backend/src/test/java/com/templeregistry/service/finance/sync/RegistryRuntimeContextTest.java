package com.templeregistry.service.finance.sync;

import com.templeregistry.TempleRegistryApplication;
import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.service.finance.pipeline.FinancePipelineOrchestrator;
import com.templeregistry.service.finance.pipeline.RevenueExtractionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mirror of {@link SyncWorkerRuntimeContextTest}, and the central regression test for
 * ADR-001: the registry runtime, assembled in full, contains nothing that can reach a
 * temple source system.
 *
 * <p>This is the assertion that matters most in the whole finance platform. Every other
 * safeguard -- the absent credential columns, the profile annotations, the classpath guard
 * -- exists to make this one true. If it ever fails, the Temple Registry has become a
 * process that can query every temple database directly.
 *
 * <p>Property overrides are the same pre-existing test-environment workarounds documented
 * in {@link SyncWorkerRuntimeContextTest}; they concern the {@code test} profile, not the
 * finance boundary.
 */
@SpringBootTest(
        classes = TempleRegistryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.jwt.public-key-path=classpath:keys/jwt-public.pem",
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RegistryRuntimeContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Registry runtime holds no credential resolution of any kind")
    void should_loadNoCredentialProvider_when_registryProfileActive() {
        assertThat(context.getBeanNamesForType(SourceCredentialProvider.class))
                .as("No bean in the registry runtime may resolve a temple source credential")
                .isEmpty();
    }

    @Test
    @DisplayName("Registry runtime loads no sync worker infrastructure")
    void should_loadNoWorkerInfrastructure_when_registryProfileActive() {
        assertThat(context.getBeanNamesForType(SyncWorkerConfig.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SyncWorkerBoundaryGuard.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SyncWorkerProperties.class)).isEmpty();
        assertThat(context.containsBean("financeSyncScheduler")).isFalse();

        // FIN-031. Connector resolution is worker infrastructure: if the registry runtime could
        // resolve a connector, an HTTP request would have a path to one.
        assertThat(context.getBeanNamesForType(ConnectorRegistry.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TempleFinanceConnector.class)).isEmpty();
        assertThat(context.containsBean("connectorRegistry")).isFalse();
    }

    /**
     * FIN-058. Until this slice nothing could start a run, so "the registry cannot execute the
     * pipeline" was true because nothing could. It is now a property that has to be asserted.
     *
     * <p>Neither of these beans lives in a package the scan below covers — the orchestrator is in
     * {@code service.finance.pipeline} — so they are named explicitly. Either one in this runtime
     * would mean an HTTP request could reach a temple database and write a canonical figure.
     */
    @Test
    @DisplayName("Registry runtime cannot start or run a synchronisation")
    void should_loadNoExecutionPath_when_registryProfileActive() {
        assertThat(context.getBeanNamesForType(ManualSyncTrigger.class))
                .as("Nothing in the registry runtime may create an execution batch")
                .isEmpty();
        assertThat(context.getBeanNamesForType(FinancePipelineOrchestrator.class))
                .as("Nothing in the registry runtime may run the ingestion pipeline")
                .isEmpty();
        assertThat(context.getBeanNamesForType(RevenueExtractionStage.class))
                .as("Extraction is the stage that touches a connector")
                .isEmpty();
        assertThat(context.containsBean("manualSyncTrigger")).isFalse();

        // FIN-059. The operator entry point is a thin caller of ManualSyncTrigger and nothing
        // more, but a thin caller in the registry runtime would still be a path from an HTTP
        // request to a temple database -- the trigger it calls just happens to not exist here.
        assertThat(context.getBeanNamesForType(ManualSyncCommandRunner.class))
                .as("Nothing in the registry runtime may invoke the operator entry point either")
                .isEmpty();
        assertThat(context.containsBean("manualSyncCommandRunner")).isFalse();
    }

    /**
     * Catches a future connector that is added without a profile guard, which is how this
     * boundary would most plausibly be lost (risk R12).
     */
    @Test
    @DisplayName("No bean in the registry runtime comes from an integration package")
    void should_registerNoIntegrationBeans_when_registryProfileActive() {
        var offenders = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    if (type == null || type.getPackageName() == null) return false;
                    String pkg = type.getPackageName();
                    return pkg.startsWith("com.templeregistry.service.finance.sync")
                            || pkg.startsWith("com.templeregistry.connector");
                })
                .toList();

        assertThat(offenders)
                .as("These beans can reach a temple source system and must exist only in the "
                        + "sync-worker runtime. Register them via @Bean in SyncWorkerConfig.")
                .isEmpty();
    }

    /** The registry keeps its background jobs; only the worker withholds scheduling. */
    @Test
    @DisplayName("Registry runtime still schedules its background jobs")
    void should_keepSchedulingEnabled_when_registryProfileActive() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .as("Moving @EnableScheduling to SchedulingConfig must not disable registry jobs")
                .isNotEmpty();
    }

    /** The finance foundation repositories are registry-side and remain available. */
    @Test
    @DisplayName("Finance repositories remain available to the registry runtime")
    void should_loadFinanceRepositories_when_registryProfileActive() {
        assertThat(context.getBeanNamesForType(
                com.templeregistry.repository.finance.FinSourceSystemRepository.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(
                com.templeregistry.repository.finance.FinTempleCapabilityRepository.class)).isNotEmpty();
    }
}

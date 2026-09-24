package com.templeregistry.service.finance.sync;

import com.templeregistry.TempleRegistryApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the <em>real</em> application under the sync-worker profile.
 *
 * <p>{@link SyncWorkerProfileBoundaryTest} tests the configuration classes in isolation.
 * This test proves the whole artifact -- every controller, service and repository -- comes
 * up correctly in worker mode, which is the thing actually deployed.
 *
 * <p>Profile order matters: {@code sync-worker} is listed last so its property overrides
 * win over {@code application-test.yml}.
 *
 * <p>The JWT public key is overridden here because {@code application-test.properties}
 * points {@code app.jwt.public-key-path} at {@code classpath:jwt-test.pub}, which is a
 * placeholder rather than a real RSA key and fails to parse. That breaks <em>any</em>
 * full-context boot on the {@code test} profile and is unrelated to the finance platform
 * (see FIN-X-002). The override is test-local and introduces no key material: it points
 * at the real public key already in {@code src/main/resources/keys}.
 */
@SpringBootTest(
        classes = TempleRegistryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "sync-worker"})
@TestPropertySource(properties = {
        "app.jwt.public-key-path=classpath:keys/jwt-public.pem",
        // application.yml carries a TiDB-only session setting that H2 rejects.
        // ApplicationContextIntegrationTest overrides it the same way for plain MySQL.
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        // The worker profile correctly sets ddl-auto=none: in production it connects to a
        // registry database the registry runtime has already migrated. This H2 context has
        // no such schema, so it is created here. The production setting stays asserted by
        // FinanceIntegrationBoundaryTest.
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SyncWorkerRuntimeContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Worker runtime starts as a non-web application")
    void should_startWithoutWebLayer_when_syncWorkerProfileActive() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBeanNamesForType(
                org.springframework.web.servlet.DispatcherServlet.class)).isEmpty();
    }

    @Test
    @DisplayName("Worker runtime loads its integration infrastructure")
    void should_loadWorkerBeans_when_syncWorkerProfileActive() {
        assertThat(context.getBean(SourceCredentialProvider.class)).isNotNull();
        assertThat(context.getBean(SyncWorkerBoundaryGuard.class)).isNotNull();
        assertThat(context.getBean(SyncWorkerProperties.class).isEnabled()).isFalse();
        assertThat(context.containsBean("financeSyncScheduler")).isTrue();
    }

    /**
     * No registry background job may run in the worker. {@code EmailDeliveryService}
     * claims outbox rows without locking every ten seconds, so a second scheduler would
     * deliver duplicate emails to real recipients.
     */
    @Test
    @DisplayName("Worker runtime runs no scheduled registry jobs")
    void should_notScheduleRegistryJobs_when_syncWorkerProfileActive() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    /**
     * Beans such as {@code EmailDeliveryService} remain present and injectable -- only
     * their scheduling is inert. That is the whole reason scheduling was gated centrally
     * instead of excluding scheduler beans, which would have cascaded through the
     * notification subsystem.
     */
    @Test
    @DisplayName("Withholding scheduling does not remove the beans that carry it")
    void should_keepSchedulerBeansInjectable_when_schedulingDisabled() {
        assertThat(context.getBeanNamesForType(
                com.templeregistry.service.notification.impl.EmailDeliveryService.class)).isNotEmpty();
    }

    /**
     * FIN-058: the worker, and only the worker, can start a run — and it starts none by itself.
     *
     * <p>The two halves matter equally. The trigger must exist here, because this is the runtime
     * that is allowed to reach a temple database. Nothing must be scheduled, because the whole
     * point of a manual-first slice is that a fully configured platform generates no traffic to a
     * government temple's live finance database until a person asks it to.
     */
    @Test
    @DisplayName("Worker runtime can start a run, and schedules none")
    void should_loadTheManualTrigger_when_syncWorkerProfileActive() {
        assertThat(context.getBean(ManualSyncTrigger.class)).isNotNull();
        assertThat(context.getBean(
                com.templeregistry.service.finance.pipeline.FinancePipelineOrchestrator.class))
                .isNotNull();

        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .as("No automatic finance synchronisation exists: a scheduled run is a later, "
                        + "deliberate design decision, not a side effect of registering a trigger")
                .isEmpty();
    }

    /**
     * FIN-059. This whole context has just booted with no {@code trm.finance.sync.command}
     * property set anywhere in this test's configuration -- exactly the state a deployed worker
     * starts in. {@link ManualSyncCommandRunner} is an {@code ApplicationRunner}, so it already
     * ran once as part of that boot; this test's mere completion is part of the proof that doing
     * so touched nothing. The explicit assertion is the other half: the bean that could invoke a
     * run exists, ready for an operator, having invoked nothing on its own.
     */
    @Test
    @DisplayName("The operator entry point exists, and starting the worker alone never used it")
    void should_loadTheCommandRunner_when_syncWorkerProfileActive() {
        assertThat(context.getBean(ManualSyncCommandRunner.class)).isNotNull();
    }

    /** No credential exists unless the operator supplied one; nothing falls back. */
    @Test
    @DisplayName("No credential resolves without explicit configuration")
    void should_resolveNoCredentials_when_noneConfigured() {
        SourceCredentialProvider provider = context.getBean(SourceCredentialProvider.class);

        assertThat(provider.isConfigured("kollur-readonly")).isFalse();
        assertThatThrownBy(() -> provider.resolve("kollur-readonly"))
                .isInstanceOf(CredentialNotConfiguredException.class);
    }
}

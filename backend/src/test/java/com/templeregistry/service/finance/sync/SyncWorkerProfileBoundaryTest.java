package com.templeregistry.service.finance.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.SchedulingConfig;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * FIN-016 regression tests for the registry / sync-worker boundary.
 *
 * <p>These use {@link ApplicationContextRunner} rather than {@code @SpringBootTest} for two
 * reasons: it exercises profile-conditional wiring directly without a database, and every
 * existing full-context test in this project routes through {@code MySQLContainerBase},
 * which is currently red for an unrelated pre-existing reason (FIN-X-001). A boundary test
 * that could not run while an unrelated defect existed would not be much of a guard.
 */
class SyncWorkerProfileBoundaryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, SyncWorkerConfig.class);

    @Nested
    @DisplayName("Registry runtime (normal application profile)")
    class RegistryRuntime {

        /**
         * The central guarantee of ADR-001. If any of these beans existed in the registry
         * runtime, an HTTP request would have a reachable path to temple credentials and
         * from there to a temple database.
         */
        @Test
        void should_loadNoSourceIntegrationBeans_when_syncWorkerProfileInactive() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SourceCredentialProvider.class);
                assertThat(context).doesNotHaveBean(SyncWorkerConfig.class);
                assertThat(context).doesNotHaveBean(SyncWorkerBoundaryGuard.class);
                assertThat(context).doesNotHaveBean(SyncWorkerProperties.class);
                assertThat(context).doesNotHaveBean("financeSyncScheduler");
            });
        }

        /** Registry background jobs keep working exactly as before the split. */
        @Test
        void should_enableScheduling_when_syncWorkerProfileInactive() {
            runner.run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
        }
    }

    @Nested
    @DisplayName("Sync worker runtime")
    class SyncWorkerRuntime {

        /**
         * The worker's pipeline beans need persistence collaborators, which this runner
         * deliberately does not provide for real: the question here is which beans each
         * profile creates, and answering it should not require a database. Mocks stand in so
         * that a pipeline stage gaining a dependency stays a change to this list rather than a
         * boundary test that goes red for a reason unrelated to the boundary.
         */
        private final ApplicationContextRunner workerRunner =
                runner.withPropertyValues("spring.profiles.active=sync-worker")
                        .withBean(FinStgRevenueRepository.class, () -> mock(FinStgRevenueRepository.class))
                        .withBean(FinStgRevenueMappingRepository.class, () -> mock(FinStgRevenueMappingRepository.class))
                        .withBean(FinMappingRuleRepository.class, () -> mock(FinMappingRuleRepository.class))
                        .withBean(FinRevenueCategoryRepository.class, () -> mock(FinRevenueCategoryRepository.class))
                        .withBean(FinRevenueFactRepository.class, () -> mock(FinRevenueFactRepository.class))
                        .withBean(FinAggRevenuePeriodRepository.class, () -> mock(FinAggRevenuePeriodRepository.class))
                        .withBean(FinReconciliationResultRepository.class, () -> mock(FinReconciliationResultRepository.class))
                        .withBean(FinSourceOfTruthDeclRepository.class, () -> mock(FinSourceOfTruthDeclRepository.class))
                        .withBean(FinSourceSystemRepository.class, () -> mock(FinSourceSystemRepository.class))
                        .withBean(FinSyncErrorRepository.class, () -> mock(FinSyncErrorRepository.class))
                        .withBean(FinSyncBatchRepository.class, () -> mock(FinSyncBatchRepository.class))
                        // FIN-058: the manual trigger recomputes readiness before starting a run,
                        // which is what brought these two into the worker's dependency list.
                        .withBean(FinTempleCapabilityRepository.class, () -> mock(FinTempleCapabilityRepository.class))
                        .withBean(TempleRepository.class, () -> mock(TempleRepository.class))
                        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                        .withBean(ObjectMapper.class, ObjectMapper::new);

        @Test
        void should_loadWorkerInfrastructure_when_syncWorkerProfileActive() {
            workerRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SourceCredentialProvider.class);
                assertThat(context).hasSingleBean(SyncWorkerBoundaryGuard.class);
                assertThat(context).hasSingleBean(SyncWorkerProperties.class);
                assertThat(context).hasBean("financeSyncScheduler");
                assertThat(context.getBean("financeSyncScheduler")).isInstanceOf(TaskScheduler.class);
                assertThat(context)
                        .as("a stage that moves rows between pipeline states belongs to the "
                                + "worker, not to a process serving HTTP")
                        .hasBean("revenueStagingValidator");
                assertThat(context).hasBean("revenueMappingStage");
                assertThat(context)
                        .as("normalization reads the source-of-truth declarations that decide "
                                + "what a payload means; that belongs to the worker too")
                        .hasBean("revenueNormalizationStage");
                assertThat(context)
                        .as("the load is the only writer of the canonical revenue table; "
                                + "nothing serving HTTP should be able to write a figure")
                        .hasBean("revenueLoadStage");
                assertThat(context)
                        .as("FIN-058: the one thing that can start a run reaches a temple database "
                                + "and writes a canonical figure in a single call, so it belongs "
                                + "here and nowhere else")
                        .hasBean("manualSyncTrigger");
                assertThat(context)
                        .as("extraction is the one stage that touches a connector, so it could "
                                + "never belong anywhere but the worker")
                        .hasBean("revenueExtractionStage");
                assertThat(context)
                        .as("reconciliation asks a connector for the source's own totals, so it "
                                + "can reach a temple system exactly as extraction can")
                        .hasBean("revenueReconciliationStage");

                assertThat(context)
                        .as("the aggregate writer publishes a financial figure -- existing in "
                                + "fin_agg_revenue_period is what publication means -- so it "
                                + "belongs to the worker for the same reason the load does")
                        .hasBean("revenueAggregationWriter");

                assertThat(context)
                        .as("the orchestrator can reach a source system and write a canonical "
                                + "figure in a single call -- the strongest reason of any bean "
                                + "here to be worker-only")
                        .hasBean("financePipelineOrchestrator");
            });
        }

        /**
         * The worker must not run the registry background jobs.
         *
         * <p>{@code EmailDeliveryService.processQueue()} claims pending rows every ten
         * seconds with no row locking. A second process running it would deliver duplicate
         * emails to real recipients, so the worker withholds the scheduling infrastructure
         * entirely rather than trying to exclude each scheduler bean.
         */
        @Test
        void should_notEnableScheduling_when_syncWorkerProfileActive() {
            workerRunner.run(context ->
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
        }

        /** Extraction is off unless explicitly enabled, even inside the worker. */
        @Test
        void should_defaultExtractionDisabled_when_workerStarts() {
            workerRunner.run(context ->
                    assertThat(context.getBean(SyncWorkerProperties.class).isEnabled()).isFalse());
        }

        @Test
        void should_bindConfiguredProperties_when_supplied() {
            workerRunner
                    .withPropertyValues("trm.finance.sync.enabled=true",
                                        "trm.finance.sync.instance-id=worker-a")
                    .run(context -> {
                        SyncWorkerProperties props = context.getBean(SyncWorkerProperties.class);
                        assertThat(props.isEnabled()).isTrue();
                        assertThat(props.getInstanceId()).isEqualTo("worker-a");
                    });
        }
    }

    @Nested
    @DisplayName("Worker refuses to serve HTTP")
    class WebRuntimeRefusal {

        /**
         * If {@code spring.main.web-application-type=none} were lost to a deployment
         * override, the worker would serve every controller in the artifact from a process
         * holding temple credentials. It fails closed instead.
         */
        @Test
        void should_failStartup_when_syncWorkerRunsAsWebApplication() {
            new WebApplicationContextRunner()
                    .withUserConfiguration(SyncWorkerConfig.class)
                    .withPropertyValues("spring.profiles.active=sync-worker")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalStateException.class);
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .hasMessageContaining("must not serve HTTP");
                    });
        }
    }
}

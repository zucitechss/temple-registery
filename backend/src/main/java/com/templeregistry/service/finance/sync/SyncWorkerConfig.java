package com.templeregistry.service.finance.sync;

import com.templeregistry.config.FinanceProfiles;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The single point at which the finance ingestion runtime is assembled.
 *
 * <p>Everything capable of reaching a temple source system is registered here and nowhere
 * else, under {@code @Profile("sync-worker")}. In the registry/API runtime this class is
 * not processed, so none of these beans exist and there is no path from an HTTP request to
 * a temple database (ADR-001).
 *
 * <p><b>Why explicit {@code @Bean} methods rather than {@code @Component} plus
 * {@code @Profile}.</b> A stereotype-annotated class is visible to the application-wide
 * component scan, so its absence from the registry runtime depends on every author
 * remembering the profile annotation. A plain class registered here cannot be picked up by
 * accident, and the worker's entire bean inventory is auditable in one file. The same
 * reasoning produced FIN-D-002: prefer a structure that cannot be misused over a rule that
 * must be remembered.
 *
 * <p>{@code FinanceIntegrationBoundaryTest} enforces this by scanning the compiled
 * classpath and failing if a stereotype annotation appears in this package.
 *
 * <p><b>Scheduling.</b> The worker does not use {@code @Scheduled} -- see
 * {@link com.templeregistry.config.SchedulingConfig} for why enabling it would start a
 * second copy of every registry background job. Sync jobs are scheduled explicitly against
 * {@link #financeSyncScheduler()}, which runs only what it is given.
 *
 * <p><b>Connectors are not registered yet.</b> FIN-016 establishes the boundary; the
 * connector framework (FIN-030) and the Kollur connector (FIN-040) arrive later and will
 * be registered as {@code @Bean} methods here. The bean name is what
 * {@code fin_source_system.connector_bean} refers to, which is why explicit naming matters.
 */
@Configuration
@Profile(FinanceProfiles.SYNC_WORKER)
@EnableConfigurationProperties(SyncWorkerProperties.class)
public class SyncWorkerConfig {

    /**
     * Resolves {@code credential_ref} aliases to real credentials.
     *
     * <p>Interim environment-backed implementation pending the Q5 decision. Replacing it
     * with a secrets-manager implementation is a change to this one method.
     */
    @Bean
    public SourceCredentialProvider sourceCredentialProvider(Environment environment) {
        return new EnvironmentSourceCredentialProvider(environment);
    }

    /**
     * Fails startup if the worker came up as a web application.
     */
    @Bean
    public SyncWorkerBoundaryGuard syncWorkerBoundaryGuard(ApplicationContext applicationContext,
                                                           SyncWorkerProperties properties) {
        return new SyncWorkerBoundaryGuard(applicationContext, properties);
    }

    /**
     * Scheduler for finance sync jobs.
     *
     * <p>Small pool on purpose: batches are per temple and per capability, and extraction
     * pressure on a temple's production database is a cost borne by the temple. Widening
     * this should be a deliberate decision with a reason, not a default.
     */
    @Bean
    public TaskScheduler financeSyncScheduler(SyncWorkerProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("fin-sync-");
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setBeanName(properties.getInstanceId() + "-scheduler");
        return scheduler;
    }
}

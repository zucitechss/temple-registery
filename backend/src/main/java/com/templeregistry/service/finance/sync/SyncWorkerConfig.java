package com.templeregistry.service.finance.sync;

import com.templeregistry.config.FinanceProfiles;
import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.TempleFinanceConnector;
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
 * <p><b>No connector is registered yet.</b> FIN-016 established the boundary, FIN-030 the
 * contract and FIN-031 the registry; the first connector implementation (FIN-040) arrives
 * later and will be registered as a {@code @Bean} method here. The bean name <em>is</em> the
 * value {@code fin_source_system.connector_bean} refers to, which is why explicit naming
 * matters -- and why {@link #connectorRegistry} fails loudly for a name nobody registered.
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
     * Resolves the {@code connector_bean} recorded against a source system.
     *
     * <p>Built from the connector beans declared in this class, so a connector reaches the
     * registry by being registered here and by no other route. The map is empty today --
     * no connector implementation exists yet -- so every configured source currently fails
     * resolution explicitly rather than appearing to synchronize nothing.
     */
    @Bean
    public ConnectorRegistry connectorRegistry(ApplicationContext applicationContext) {
        return new ConnectorRegistry(applicationContext.getBeansOfType(TempleFinanceConnector.class));
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

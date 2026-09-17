package com.templeregistry.service.finance.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.config.FinanceProfiles;
import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import com.templeregistry.service.finance.pipeline.FinancePipelineOrchestrator;
import com.templeregistry.service.finance.pipeline.RevenueExtractionStage;
import com.templeregistry.service.finance.pipeline.RevenueLoadStage;
import com.templeregistry.service.finance.pipeline.RevenueMappingStage;
import com.templeregistry.service.finance.pipeline.RevenueNormalizationStage;
import com.templeregistry.service.finance.pipeline.RevenueReconciliationStage;
import com.templeregistry.service.finance.pipeline.RevenueStagingValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
     * Validates staged revenue rows (FIN-053).
     *
     * <p>Reaches no source system — it reads and writes the registry database only — but it
     * lives here because the pipeline runs in one process, and a stage that mutates pipeline
     * state should not be reachable from an HTTP request in the registry runtime.
     *
     * <p>Transactions are explicit rather than annotation-driven: each row commits in its own
     * {@code REQUIRES_NEW} transaction, and doing that through {@code @Transactional} on a
     * self-invoked method silently would not apply.
     */
    @Bean
    public RevenueStagingValidator revenueStagingValidator(FinStgRevenueRepository stagingRepository,
                                                           FinSyncErrorRepository errorRepository,
                                                           FinSyncBatchRepository batchRepository,
                                                           PlatformTransactionManager transactionManager,
                                                           ObjectMapper objectMapper) {
        TransactionTemplate perRow = new TransactionTemplate(transactionManager);
        perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueStagingValidator(
                stagingRepository, errorRepository, batchRepository, perRow, objectMapper);
    }

    /**
     * Translates validated source values into canonical ones (FIN-054).
     *
     * <p>Holds no source vocabulary: the fields it reads come from {@code fin_mapping_rule}
     * rows, so onboarding a temple adds configuration rather than code (ADR-004). It is here
     * rather than in the registry runtime for the same reason as the validator — it moves
     * pipeline state, and nothing serving HTTP should be able to.
     */
    @Bean
    public RevenueMappingStage revenueMappingStage(FinStgRevenueRepository stagingRepository,
                                                   FinStgRevenueMappingRepository mappingRepository,
                                                   FinMappingRuleRepository ruleRepository,
                                                   FinRevenueCategoryRepository categoryRepository,
                                                   FinSyncErrorRepository errorRepository,
                                                   FinSyncBatchRepository batchRepository,
                                                   PlatformTransactionManager transactionManager,
                                                   ObjectMapper objectMapper) {
        TransactionTemplate perRow = new TransactionTemplate(transactionManager);
        perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueMappingStage(stagingRepository, mappingRepository, ruleRepository,
                categoryRepository, errorRepository, batchRepository, perRow, objectMapper);
    }

    /**
     * Normalization: a batch's mapped records collapsed onto the canonical daily grain
     * (FIN-055).
     *
     * <p>A worker bean for the same reason as the validator and the mapper — it reads the
     * pipeline's state and the source-of-truth declarations that decide what a payload means,
     * and nothing serving HTTP should be able to. It writes no canonical row: {@code
     * fin_revenue_fact} has no writer until FIN-056, so the facts are returned rather than
     * persisted.
     */
    @Bean
    public RevenueNormalizationStage revenueNormalizationStage(
            FinSyncBatchRepository batchRepository,
            FinStgRevenueRepository stagingRepository,
            FinStgRevenueMappingRepository mappingRepository,
            FinSourceOfTruthDeclRepository declarationRepository,
            FinRevenueCategoryRepository categoryRepository,
            FinSyncErrorRepository errorRepository,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueNormalizationStage(batchRepository, stagingRepository, mappingRepository,
                declarationRepository, categoryRepository, errorRepository, template, objectMapper);
    }

    /**
     * The load: a batch's normalized facts written to {@code fin_revenue_fact} (FIN-056).
     *
     * <p>The only writer of the canonical revenue table, and the reason it belongs to the worker
     * is sharper than for the earlier stages: this is the process that decides what the platform
     * will report. Nothing serving HTTP should be able to write a figure.
     */
    @Bean
    public RevenueLoadStage revenueLoadStage(RevenueNormalizationStage normalizationStage,
                                             FinRevenueFactRepository factRepository,
                                             FinStgRevenueRepository stagingRepository,
                                             FinSyncBatchRepository batchRepository,
                                             FinSyncErrorRepository errorRepository,
                                             PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueLoadStage(normalizationStage, factRepository, stagingRepository,
                batchRepository, errorRepository, template);
    }

    /**
     * Extraction: a connector's rows landed in staging (FIN-057).
     *
     * <p>The first stage, and the only one that touches a connector — which is why it could not
     * exist in the registry runtime under any circumstances. It names no source table or column;
     * what to read is the connector's, and what a value means is configuration's.
     */
    @Bean
    public RevenueExtractionStage revenueExtractionStage(ConnectorRegistry connectorRegistry,
                                                         FinSourceSystemRepository sourceSystemRepository,
                                                         FinStgRevenueRepository stagingRepository,
                                                         FinSyncBatchRepository batchRepository,
                                                         FinSyncErrorRepository errorRepository,
                                                         PlatformTransactionManager transactionManager,
                                                         ObjectMapper objectMapper) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueExtractionStage(connectorRegistry, sourceSystemRepository,
                stagingRepository, batchRepository, errorRepository, template, objectMapper);
    }

    /**
     * Reconciliation: does the batch add up, and does anyone outside agree (FIN-060)?
     *
     * <p>Worker-only for the same reason extraction is — it asks a connector for the source's own
     * totals, which means it can reach a temple system. It writes only
     * {@code fin_reconciliation_result}; a reconciler able to touch the facts could make its own
     * checks pass.
     */
    @Bean
    public RevenueReconciliationStage revenueReconciliationStage(
            ConnectorRegistry connectorRegistry,
            FinSourceSystemRepository sourceSystemRepository,
            FinSyncBatchRepository batchRepository,
            FinStgRevenueRepository stagingRepository,
            FinSyncErrorRepository errorRepository,
            FinRevenueFactRepository revenueFactRepository,
            FinReconciliationResultRepository reconciliationResultRepository,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueReconciliationStage(connectorRegistry, sourceSystemRepository,
                batchRepository, stagingRepository, errorRepository, revenueFactRepository,
                reconciliationResultRepository, template);
    }

    /**
     * The orchestrator: one batch, six stages, one status (FIN-057, FIN-060).
     *
     * <p>The piece that turns stages which each ran alone into a pipeline. It belongs to the
     * worker for the strongest reason of any bean here — it is the thing that can reach a temple
     * source system and write a canonical figure in one call.
     */
    @Bean
    public FinancePipelineOrchestrator financePipelineOrchestrator(
            RevenueExtractionStage extractionStage,
            RevenueStagingValidator stagingValidator,
            RevenueMappingStage mappingStage,
            RevenueLoadStage loadStage,
            RevenueReconciliationStage reconciliationStage,
            FinSyncBatchRepository batchRepository,
            FinSyncErrorRepository errorRepository,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new FinancePipelineOrchestrator(extractionStage, stagingValidator, mappingStage,
                loadStage, reconciliationStage, batchRepository, errorRepository, template);
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

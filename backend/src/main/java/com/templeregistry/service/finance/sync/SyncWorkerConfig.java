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
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.service.finance.onboarding.OnboardingConfigurationReader;
import com.templeregistry.service.finance.pipeline.FinancePipelineOrchestrator;
import com.templeregistry.service.finance.pipeline.RevenueExtractionStage;
import com.templeregistry.service.finance.sync.jdbc.DriverManagerJdbcConnectionFactory;
import com.templeregistry.service.finance.sync.jdbc.JdbcConnectionFactory;
import com.templeregistry.service.finance.sync.jdbc.JdbcSourceSettingsProvider;
import com.templeregistry.service.finance.sync.jdbc.JdbcTableConnector;
import com.templeregistry.service.finance.sync.jdbc.PropertiesJdbcSourceSettingsProvider;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.service.finance.aggregation.RevenueAggregationRebuilder;
import com.templeregistry.service.finance.aggregation.RevenueAggregationWriter;
import com.templeregistry.service.finance.publication.ReconciliationGate;
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
 * <p><b>One connector is registered: {@link #jdbcTableConnector}</b>, the generic
 * configuration-driven JDBC reader ADR-004 promised (FIN-040). FIN-016 established the
 * boundary, FIN-030 the contract, FIN-031 the registry, and this is the first implementation.
 * The bean name <em>is</em> the value {@code fin_source_system.connector_bean} refers to, which
 * is why explicit naming matters -- and why {@link #connectorRegistry} fails loudly for a name
 * nobody registered.
 *
 * <p><b>One thing can start a run: {@link #manualSyncTrigger}</b> (FIN-058). It is manual, and
 * only manual -- no {@code @Scheduled} method exists anywhere in this runtime and
 * {@link #financeSyncScheduler} is given no job, so the platform contacts a temple system when a
 * person asks it to and at no other time. Automatic scheduling is a later design task, deliberately
 * after the pipeline has been proven to run end to end.
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
     * registry by being registered here and by no other route. Since FIN-040 the map holds
     * {@code jdbcTableConnector}; any other configured name still fails resolution explicitly
     * rather than appearing to synchronize nothing.
     */
    @Bean
    public ConnectorRegistry connectorRegistry(ApplicationContext applicationContext) {
        return new ConnectorRegistry(applicationContext.getBeansOfType(TempleFinanceConnector.class));
    }

    // --------------------------------------------- generic JDBC connector (FIN-040)

    /**
     * Where a JDBC source lives and which table to read, from worker configuration.
     *
     * <p>Not from {@code fin_source_system}: it carries a JDBC URL, and the registry runtime must
     * have no path to a temple's connection details (FIN-D-002, ADR-001). Replacing this with a
     * better-governed source of the same settings is a change to this one method.
     */
    @Bean
    public JdbcSourceSettingsProvider jdbcSourceSettingsProvider(Environment environment) {
        return new PropertiesJdbcSourceSettingsProvider(environment);
    }

    /** Opens source connections. Substituted in tests so the connector is exercised without a temple. */
    @Bean
    public JdbcConnectionFactory jdbcConnectionFactory() {
        return new DriverManagerJdbcConnectionFactory();
    }

    /**
     * The generic JDBC connector ADR-004 promised (FIN-040) — the first connector implementation.
     *
     * <p><b>The method name is the contract.</b> It is the Spring bean name, it is what
     * {@link #connectorRegistry} keys the registry by, and it is the value a source system stores
     * in {@code fin_source_system.connector_bean}. {@code ConnectorRegistry} refuses a connector
     * whose declared {@code connectorId} differs from the name it is registered under, so this
     * method name and {@link JdbcTableConnector#CONNECTOR_ID} must stay equal.
     *
     * <p>Registered here rather than annotated {@code @Component} for the reason this whole class
     * exists (FIN-D-008): a stereotype annotation is visible to the application-wide component
     * scan, and a connector that drifted into the registry runtime would put a path from an HTTP
     * request to a temple database into the one process that must never have one.
     *
     * <p>Registering it starts nothing. It makes a name resolvable; a source system still has to
     * name it, be configured, be enabled, and be run by a trigger that does not exist yet.
     */
    @Bean
    public JdbcTableConnector jdbcTableConnector(JdbcSourceSettingsProvider jdbcSourceSettingsProvider,
                                                 SourceCredentialProvider sourceCredentialProvider,
                                                 JdbcConnectionFactory jdbcConnectionFactory) {
        return new JdbcTableConnector(
                jdbcSourceSettingsProvider, sourceCredentialProvider, jdbcConnectionFactory);
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
            RevenueAggregationRebuilder revenueAggregationRebuilder,
            FinSyncBatchRepository batchRepository,
            FinSyncErrorRepository errorRepository,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new FinancePipelineOrchestrator(extractionStage, stagingValidator, mappingStage,
                loadStage, reconciliationStage, revenueAggregationRebuilder, batchRepository,
                errorRepository, template);
    }

    // ------------------------------------------------- manual sync trigger (FIN-058)

    /**
     * Reads the registry facts readiness is computed from, for the worker (FIN-058).
     *
     * <p>The same class the registry's onboarding service uses, registered here so the trigger can
     * recompute readiness before it runs anything. It reaches only registry tables -- no connector,
     * no credential -- so it is safe in either runtime; what would not be safe is a second copy of
     * the assembly, because then the screen an administrator reads and the gate that admits a run
     * could disagree about whether a source is configured correctly.
     */
    @Bean
    public OnboardingConfigurationReader onboardingConfigurationReader(
            FinSourceSystemRepository sourceSystemRepository,
            FinTempleCapabilityRepository capabilityRepository,
            FinSourceOfTruthDeclRepository declarationRepository,
            FinMappingRuleRepository mappingRuleRepository,
            FinRevenueCategoryRepository categoryRepository) {
        return new OnboardingConfigurationReader(sourceSystemRepository, capabilityRepository,
                declarationRepository, mappingRuleRepository, categoryRepository);
    }

    /**
     * The first thing that can actually start a synchronisation run (FIN-058).
     *
     * <p>FIN-057 built the pipeline, FIN-033 the connector and FIN-140-D the switch, and nothing
     * called any of them. This bean is the caller -- and it is a <em>manual</em> caller on purpose.
     * Nothing here is scheduled, nothing polls {@code sync_enabled}, and
     * {@link #financeSyncScheduler} is given no job: the platform still generates no traffic to a
     * temple system unless a person asks it to, which is the only responsible order in which to
     * prove a pipeline that has never run end to end.
     *
     * <p>Worker-side for the strongest reason in this file. It is the thing that, in one call,
     * reaches a temple database and writes a canonical figure. An equivalent bean in the registry
     * runtime would put that behind an HTTP request, which is the whole of ADR-001.
     *
     * <p>{@code REQUIRES_NEW}, and used only for batch creation: validating and inserting the batch
     * is one short transaction that commits before extraction begins, so no database transaction is
     * held open across a read of a remote system.
     */
    @Bean
    public ManualSyncTrigger manualSyncTrigger(FinSourceSystemRepository sourceSystemRepository,
                                               TempleRepository templeRepository,
                                               FinSyncBatchRepository batchRepository,
                                               OnboardingConfigurationReader onboardingConfigurationReader,
                                               FinancePipelineOrchestrator financePipelineOrchestrator,
                                               SyncWorkerProperties properties,
                                               PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new ManualSyncTrigger(sourceSystemRepository, templeRepository, batchRepository,
                onboardingConfigurationReader, financePipelineOrchestrator, properties, template);
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

    /**
     * Writes period aggregates, and refuses to write ungated ones (FIN-070).
     *
     * <p>Worker-side because it writes a published financial figure, which is the same reason the
     * load stage lives here. It has no caller yet: deciding when to recompute a period is FIN-072's,
     * and the reporting API that reads what this writes is Phase 8's. Registered now so that both
     * are written against a boundary that already exists, exactly as every pipeline stage was
     * registered here before {@code FinancePipelineOrchestrator} existed to call it.
     *
     * <p>{@code REQUIRES_NEW}, like the orchestrator's template: a scope's rows are written all or
     * nothing, and a caller's already-doomed transaction must not be able to take a correct aggregate
     * write down with it.
     */
    @Bean
    public RevenueAggregationWriter revenueAggregationWriter(
            FinAggRevenuePeriodRepository aggregateRepository,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RevenueAggregationWriter(aggregateRepository, template);
    }


    /**
     * The publication gate (FIN-061), registered as a worker bean for the first time here.
     *
     * <p>FIN-061 built this class and nothing ever constructed it — it had no caller, so it had no
     * bean either. FIN-072 gives it its first one. Registered rather than annotated, like every other
     * finance worker collaborator (FIN-D-008), which also makes its {@code @Transactional(readOnly)}
     * effective: a hand-constructed instance would silently lose the proxy and the annotation with it.
     *
     * <p>It lives on the sync worker because that is where publication is decided. A read API that
     * later needs a verdict gets its own registration under its own profile; sharing this one would
     * put a worker-profile bean on the API path.
     */
    @Bean
    public ReconciliationGate reconciliationGate(
            FinReconciliationResultRepository reconciliationResultRepository,
            FinRevenueFactRepository factRepository) {
        return new ReconciliationGate(reconciliationResultRepository, factRepository);
    }

    /**
     * Rebuilds the period aggregates a batch's facts affected (FIN-072).
     *
     * <p>An explicit {@code @Bean} like every other worker collaborator (FIN-D-008), so it exists on
     * the sync worker and nowhere else. It holds no transaction template of its own: each scope's
     * write is already all-or-nothing inside {@code revenueAggregationWriter}, and wrapping the loop
     * in a second transaction would make one blocked year roll back the years that published cleanly.
     */
    @Bean
    public RevenueAggregationRebuilder revenueAggregationRebuilder(
            FinSyncBatchRepository batchRepository,
            FinRevenueFactRepository factRepository,
            ReconciliationGate reconciliationGate,
            RevenueAggregationWriter revenueAggregationWriter) {
        return new RevenueAggregationRebuilder(batchRepository, factRepository,
                reconciliationGate, revenueAggregationWriter);
    }
}

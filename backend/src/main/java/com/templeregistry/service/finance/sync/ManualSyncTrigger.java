package com.templeregistry.service.finance.sync;

import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.service.finance.onboarding.OnboardingConfigurationReader;
import com.templeregistry.service.finance.onboarding.OnboardingReadinessValidator;
import com.templeregistry.service.finance.onboarding.ReadinessFinding;
import com.templeregistry.service.finance.onboarding.ReadinessStatus;
import com.templeregistry.service.finance.pipeline.FinancePipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Starts one synchronisation run, on purpose, because a person asked for it (FIN-058).
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Every piece of the ingestion pipeline existed and none of it had a caller. FIN-057 composed
 * six stages into {@code FinancePipelineOrchestrator}; FIN-033 registered a real connector;
 * FIN-140-D gave an administrator a switch to authorise synchronisation. Nothing read that switch,
 * nothing created a {@code fin_sync_batch}, and nothing invoked the orchestrator -- so the platform
 * could be fully configured and still never read a single row. This is the first production code
 * path from "somebody decided" to "the source was read".
 *
 * <h2>Manual, deliberately, and only manual</h2>
 *
 * <p>There is no {@code @Scheduled} here, no cron, no polling loop and no scan of
 * {@code sync_enabled}. The worker's {@code financeSyncScheduler} is not used. Automatic
 * synchronisation means the platform generating traffic against a government temple's live finance
 * database with nobody watching, and the first time that happens should not also be the first time
 * the whole pipeline runs end to end. Proving execution comes first; deciding when to repeat it is
 * a later design task with its own failure modes -- overlapping runs, back-off, extraction pressure,
 * and what a schedule means for a temple that closes its books at month end.
 *
 * <p>The consequence is that this class has no production caller either. That is the honest state
 * of the slice rather than an oversight: the worker is not a web application (a startup guard fails
 * it if it ever becomes one), so a manual request has nowhere to arrive from yet. What exists is
 * the executable capability and the tests that drive it; the operator-facing entry point -- a CLI
 * argument, or a registry-to-worker request mechanism nobody has yet designed -- is its own task.
 *
 * <h2>Five refusals, one run</h2>
 *
 * <pre>
 *   worker enabled?           no -&gt; WORKER_DISABLED        no batch
 *   source exists, live?      no -&gt; NO_SUCH_SOURCE         no batch
 *   sync_enabled?             no -&gt; NOT_ENABLED_FOR_SYNC   no batch
 *   readiness blocking?      yes -&gt; READINESS_BLOCKED      no batch
 *   already PENDING/RUNNING? yes -&gt; ALREADY_IN_PROGRESS    no batch
 *                             otherwise: create a PENDING batch, run the orchestrator
 * </pre>
 *
 * <p>None of the refusals writes a batch row, because none of them attempted anything. See
 * {@link SyncRefusedException}.
 *
 * <h2>Enabled is not ready, and ready is not connected</h2>
 *
 * <p>Both gates are required and they answer different questions. {@code sync_enabled} is a
 * person's authorisation, recorded by FIN-140-D and meaningless about correctness. Readiness is the
 * configuration's coherence, recomputed <em>here and now</em> rather than trusted from whenever the
 * source was switched on -- a declaration can be superseded, a mapping rule deactivated or a
 * canonical category retired in the interval, and each of those turns a correct configuration into
 * one that would load wrong figures. Neither gate establishes that the source is reachable; nothing
 * in the registry's data can (ADR-001), which is why the first thing a run proves is exactly that.
 */
@RequiredArgsConstructor
@Slf4j
public class ManualSyncTrigger {

    /** The only capability the pipeline extracts. One batch is one capability, by design. */
    private static final FinanceCapability CAPABILITY = FinanceCapability.REVENUE;

    /** A batch in either state owns this source: one is running, or one is about to. */
    private static final Set<SyncStatus> ACTIVE = EnumSet.of(SyncStatus.PENDING, SyncStatus.RUNNING);

    private final FinSourceSystemRepository sourceSystems;
    private final TempleRepository temples;
    private final FinSyncBatchRepository batches;
    private final OnboardingConfigurationReader configurationReader;
    private final FinancePipelineOrchestrator orchestrator;
    private final SyncWorkerProperties properties;
    private final TransactionTemplate transactionTemplate;

    /**
     * Runs a source system now, with the window derived from its last successful run.
     *
     * <p>{@code HISTORICAL} when this source has never succeeded, {@code INCREMENTAL} afterwards --
     * which is what {@link SyncType}'s own definitions say those two words mean, not a new policy.
     */
    public Outcome runNow(long sourceSystemId) {
        Optional<FinSyncBatch> lastSuccess = lastSuccessfulBatch(sourceSystemId);
        return runNow(sourceSystemId,
                lastSuccess.isPresent() ? SyncType.INCREMENTAL : SyncType.HISTORICAL,
                lastSuccess.map(FinSyncBatch::getWindowTo).orElse(null),
                null);
    }

    /**
     * Runs a source system now over an explicit window.
     *
     * @param windowFrom change-axis lower bound, or null for "everything the source holds".
     *                   Absent is a real answer, not a missing one -- it is what a historical load
     *                   asks for, and {@code RevenueExtractionStage} already reads it that way
     * @param windowTo   change-axis upper bound; null means now
     * @throws SyncRefusedException nothing was attempted and no batch exists
     * @throws FinancePipelineOrchestrator.PipelineFailedException
     *         a stage failed. The batch is already {@code FAILED} with the failing stage recorded,
     *         so the exception is the signal and the batch row is the record
     */
    public Outcome runNow(long sourceSystemId,
                          SyncType syncType,
                          LocalDateTime windowFrom,
                          LocalDateTime windowTo) {

        // Checked before anything is read, because the master switch is about this process rather
        // than about this source: a worker deployed for observation must generate no traffic at
        // all, and finding that out after locking a row would be the same answer more expensively.
        if (!properties.isEnabled()) {
            throw refuse(SyncRefusedException.Reason.WORKER_DISABLED,
                    "This worker has extraction disabled (trm.finance.sync.enabled is false), so it "
                            + "will contact no source system. Starting the worker and permitting it "
                            + "to read temple databases are separate decisions.");
        }

        LocalDateTime requestedAt = LocalDateTime.now();
        FinSyncBatch batch = createBatch(sourceSystemId, syncType, windowFrom,
                windowTo == null ? requestedAt : windowTo);

        log.info("[FinanceSync] MANUAL trigger accepted: source {}, batch {} ({}), {} window {} to {}",
                sourceSystemId, batch.getId(), batch.getBatchRef(), batch.getSyncType(),
                batch.getWindowFrom(), batch.getWindowTo());

        // Outside every transaction. An extraction can run for minutes against a remote system,
        // and a transaction held open across it pins a connection and a row lock for the whole
        // read. The orchestrator opens its own short REQUIRES_NEW transactions per stage, which is
        // also what lets it record a failure after the stage's own transaction is already doomed.
        try {
            FinancePipelineOrchestrator.Result result = orchestrator.run(batch.getId());
            SyncStatus finalStatus = statusOf(batch.getId());
            log.info("[FinanceSync] MANUAL run finished: source {}, batch {}, status {}, {} ms",
                    sourceSystemId, batch.getId(), finalStatus,
                    Duration.between(requestedAt, LocalDateTime.now()).toMillis());
            return new Outcome(batch.getId(), batch.getBatchRef(), finalStatus, result);

        } catch (RuntimeException failure) {
            // The orchestrator has already written FAILED and the failing stage. Rethrowing rather
            // than returning an outcome keeps a caller from mistaking a failed run for a completed
            // one; the batch row, not this exception, is the record.
            log.error("[FinanceSync] MANUAL run failed: source {}, batch {}, status {}, category {}",
                    sourceSystemId, batch.getId(), statusOf(batch.getId()),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /**
     * Validates and creates the batch, in one short transaction that commits before extraction.
     *
     * <h2>Duplicate protection is the row lock, not the check</h2>
     *
     * <p>The source system row is taken with {@code SELECT ... FOR UPDATE} first, and every
     * subsequent read happens while it is held. Two triggers racing -- in one worker or in two --
     * serialise on that lock, so the second sees the first's committed batch and is refused.
     * Checking for an active batch without the lock would leave both callers reading "none", both
     * inserting, and both extracting the same window into two batches; the stages would survive it,
     * being idempotent, but at twice the load on a temple's database and with two sets of counters
     * describing one window.
     *
     * <p>The lock is on {@code fin_source_system} rather than on the batch table because the thing
     * being made exclusive is the source, and the row that would otherwise be locked does not exist
     * yet. It is held for three small reads and one insert, never across extraction. It is also the
     * database's lock rather than the JVM's, which is what makes it still correct when a second
     * worker process is deployed.
     */
    private FinSyncBatch createBatch(long sourceSystemId, SyncType syncType,
                                     LocalDateTime windowFrom, LocalDateTime windowTo) {
        return transactionTemplate.execute(tx -> {
            FinSourceSystem source = sourceSystems.findByIdForUpdate(sourceSystemId)
                    .filter(row -> !row.isDeleted())
                    .orElseThrow(() -> refuse(SyncRefusedException.Reason.NO_SUCH_SOURCE,
                            "Source system " + sourceSystemId + " does not exist, or has been "
                                    + "removed from the registry."));

            if (!source.isSyncEnabled()) {
                throw refuse(SyncRefusedException.Reason.NOT_ENABLED_FOR_SYNC,
                        "Source system " + sourceSystemId + " (" + source.getSystemCode() + ") is "
                                + "not enabled for synchronisation. Enabling it is an "
                                + "administrator's decision and is recorded as one; until it is "
                                + "taken, the platform contacts nothing.");
            }

            requireNoBlockingFindings(source);

            long active = batches.countBySourceSystemIdAndCapabilityAndStatusIn(
                    sourceSystemId, CAPABILITY, ACTIVE);
            if (active > 0) {
                throw refuse(SyncRefusedException.Reason.ALREADY_IN_PROGRESS,
                        "Source system " + sourceSystemId + " already has " + active + " "
                                + CAPABILITY + " batch(es) pending or running. Two runs over one "
                                + "window would read a temple's database twice and produce two sets "
                                + "of counters describing one window.");
            }

            return batches.save(FinSyncBatch.builder()
                    .batchRef(UUID.randomUUID().toString())
                    .templeId(source.getTempleId())
                    .sourceSystemId(source.getId())
                    .capability(CAPABILITY)
                    .syncType(syncType)
                    .status(SyncStatus.PENDING)
                    .triggeredBy(SyncTrigger.MANUAL)
                    .windowFrom(windowFrom)
                    .windowTo(windowTo)
                    // Read from the last SUCCESSFUL batch, never the last batch. A failed run
                    // never becomes the position a later one resumes from (FIN-D-005).
                    .watermarkBefore(lastSuccessfulBatch(source.getId())
                            .map(FinSyncBatch::getWatermarkAfter).orElse(null))
                    .build());
        });
    }

    /**
     * Refuses a source whose configuration would load wrong figures.
     *
     * <p>Recomputed now, from the same validator and the same assembly the readiness screen uses
     * (one implementation, two runtimes -- {@link OnboardingConfigurationReader}). Warnings do not
     * block: they are things that are legal and probably wrong, and a source left half-configured
     * overnight is a legitimate state. A blocking finding is something that would certainly fail or
     * would publish a wrong figure, and running anyway would mean choosing to publish it.
     */
    private void requireNoBlockingFindings(FinSourceSystem source) {
        boolean templeExists = temples.findWithFullGeoById(source.getTempleId()).isPresent();
        List<ReadinessFinding> blocking =
                OnboardingReadinessValidator.validate(configurationReader.read(source, templeExists))
                        .stream()
                        .filter(finding -> finding.severity() == ReadinessStatus.BLOCKED)
                        .toList();
        if (blocking.isEmpty()) {
            return;
        }
        throw refuse(SyncRefusedException.Reason.READINESS_BLOCKED,
                "Source system " + source.getId() + " (" + source.getSystemCode() + ") has "
                        + blocking.size() + " blocking readiness finding(s): "
                        + blocking.stream().map(ReadinessFinding::code).toList()
                        + ". Each would either fail the run or publish a figure that is wrong; the "
                        + "readiness screen explains them in full.");
    }

    /**
     * The last batch that actually succeeded, which is a different question from the last batch.
     *
     * <p>Keeping those two separate is the whole of FIN-D-005. If a failed run could be read as the
     * resume point, the next window would begin where the failed one was aiming rather than where
     * the last good one finished, and every row in between would be skipped silently -- a gap in a
     * temple's published income with nothing anywhere recording that it exists.
     */
    private Optional<FinSyncBatch> lastSuccessfulBatch(long sourceSystemId) {
        return batches.findFirstBySourceSystemIdAndCapabilityAndStatusOrderByIdDesc(
                sourceSystemId, CAPABILITY, SyncStatus.SUCCESS);
    }

    private SyncStatus statusOf(long syncBatchId) {
        return batches.findById(syncBatchId).map(FinSyncBatch::getStatus).orElse(null);
    }

    private SyncRefusedException refuse(SyncRefusedException.Reason reason, String message) {
        log.warn("[FinanceSync] MANUAL trigger refused [{}]: {}", reason, message);
        return new SyncRefusedException(reason, message);
    }

    /**
     * What a run did.
     *
     * @param status the batch's persisted terminal status, read back rather than inferred:
     *               {@code SUCCESS}, or {@code RECONCILE_FAILED} when the data loaded but the
     *               figures disagree and publication of the affected years is withheld
     */
    public record Outcome(long syncBatchId,
                          String batchRef,
                          SyncStatus status,
                          FinancePipelineOrchestrator.Result pipeline) {

        /** True when reconciliation found a disagreement the aggregates must not be built on. */
        public boolean blocksPublication() {
            return pipeline != null && pipeline.blocksPublication();
        }
    }
}

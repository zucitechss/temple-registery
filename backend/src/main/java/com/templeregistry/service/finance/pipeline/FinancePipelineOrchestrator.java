package com.templeregistry.service.finance.pipeline;

import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.service.finance.aggregation.RevenueAggregationRebuilder;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Runs one {@code fin_sync_batch} through the revenue pipeline (FIN-057).
 *
 * <pre>
 *   PENDING --claim--> RUNNING --> EXTRACT --> VALIDATE --> MAP --> LOAD --> RECONCILE
 *                                                                               |
 *                    SUCCESS          &lt;--------------- every check passed or was unavailable
 *                    RECONCILE_FAILED &lt;--------------- a check found a real disagreement
 *                    FAILED           &lt;--------------- any stage threw
 * </pre>
 *
 * <p>{@code RECONCILE_FAILED} is not a worse {@code FAILED}. The rows are loaded and
 * inspectable; what is blocked is publication of the affected aggregates. Sending such a batch
 * down the retry path would re-extract and reach the same figures and the same disagreement.
 *
 * <p>Five stages existed before this class and none of them had a caller. Each takes a batch id
 * and returns a result; none takes a payload from another. That is what makes composition this
 * small — the orchestrator passes an id and a status, and the database carries the data between
 * stages.
 *
 * <h2>It owns status, and nothing else</h2>
 *
 * <p>Every counter belongs to the stage that can derive it: {@code rows_extracted} to extraction,
 * {@code rows_rejected} to validation, {@code rows_loaded} to the load. The orchestrator
 * increments none of them, because a counter written in two places is a counter nobody can
 * reconcile after a partial run (FIN-D-023).
 *
 * <p>It also does not advance the watermark. {@code watermark_after} is the change-axis position
 * a future incremental sync would resume from, and advancing it here — before anyone has agreed
 * what a source's watermark column even is — would mean a later run silently skipping a window
 * this one only partly processed (FIN-D-049).
 *
 * <h2>Claiming, not checking</h2>
 *
 * <p>A batch is claimed with a conditional update: {@code SET status = RUNNING WHERE status =
 * PENDING}. Two callers racing cannot both claim it, because the second update matches nothing.
 * Checking the status and then writing it would leave a window between the two in which both
 * callers believe they own the batch, and both would run the whole pipeline over the same rows.
 *
 * <h2>Failure is persisted in its own transaction</h2>
 *
 * <p>A stage that throws leaves the batch {@code FAILED} with the failing stage recorded in
 * {@code fin_sync_error}, and the exception is rethrown rather than swallowed. The status write
 * happens in a fresh transaction because the one the stage was using may already be doomed —
 * writing the failure inside it would roll back with everything else and leave the batch
 * {@code RUNNING} forever, which is precisely the state nothing can recover from automatically.
 */
public class FinancePipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FinancePipelineOrchestrator.class);

    private final RevenueExtractionStage extraction;
    private final RevenueStagingValidator validation;
    private final RevenueMappingStage mapping;
    private final RevenueLoadStage load;
    private final RevenueReconciliationStage reconciliation;
    private final RevenueAggregationRebuilder rebuilder;
    private final FinSyncBatchRepository batches;
    private final FinSyncErrorRepository errors;
    private final TransactionTemplate transactionTemplate;

    public FinancePipelineOrchestrator(RevenueExtractionStage extraction,
                                       RevenueStagingValidator validation,
                                       RevenueMappingStage mapping,
                                       RevenueLoadStage load,
                                       RevenueReconciliationStage reconciliation,
                                       RevenueAggregationRebuilder rebuilder,
                                       FinSyncBatchRepository batches,
                                       FinSyncErrorRepository errors,
                                       TransactionTemplate transactionTemplate) {
        this.extraction = extraction;
        this.validation = validation;
        this.mapping = mapping;
        this.load = load;
        this.reconciliation = reconciliation;
        this.rebuilder = rebuilder;
        this.batches = batches;
        this.errors = errors;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Runs a batch end to end.
     *
     * @throws IllegalArgumentException if the batch does not exist
     * @throws BatchNotClaimableException if the batch is not {@code PENDING} — already running,
     *                                    already finished, or cancelled. Never a silent no-op.
     * @throws PipelineFailedException    if a stage failed; the batch is {@code FAILED} and the
     *                                    original cause is attached
     */
    public Result run(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch " + syncBatchId));

        claim(batch);

        LocalDateTime startedAt = LocalDateTime.now();
        SyncStage stage = SyncStage.EXTRACT;
        try {
            log.info("[FinanceSync] Batch {} starting: temple {}, source {}, {}",
                    syncBatchId, batch.getTempleId(), batch.getSourceSystemId(), batch.getSyncType());

            RevenueExtractionStage.Result extracted = extraction.extractBatch(syncBatchId);

            stage = SyncStage.VALIDATE;
            RevenueStagingValidator.Result validated = validation.validateBatch(syncBatchId);

            stage = SyncStage.MAP;
            RevenueMappingStage.Result mapped = mapping.mapBatch(syncBatchId);

            // The load normalizes as its first step, so NORMALIZE has no separate call here.
            // Reported as LOAD because that is the stage whose failure an operator would see.
            stage = SyncStage.LOAD;
            RevenueLoadStage.Result loaded = load.loadBatch(syncBatchId);

            // Reconciliation runs against the batch this run just finished loading.
            // It is a check, not a step: it never repairs, deletes or restates anything, so a
            // disagreement it finds leaves the data exactly where the load put it.
            stage = SyncStage.RECONCILE;
            RevenueReconciliationStage.Result reconciled = reconciliation.reconcileBatch(syncBatchId);

            // Aggregation runs last, and runs unconditionally. It is not conditional on the
            // reconciliation verdict because the verdict is not one answer: a batch can touch three
            // financial years and be publishable in two of them. ReconciliationGate is asked per
            // year inside the rebuild, which is the only place with the scope to ask correctly.
            stage = SyncStage.AGGREGATE;
            RevenueAggregationRebuilder.Result aggregated = rebuilder.rebuildBatch(syncBatchId);

            Result result = new Result(syncBatchId, extracted, validated, mapped, loaded, reconciled,
                    aggregated, Duration.between(startedAt, LocalDateTime.now()));

            // RECONCILE_FAILED, not FAILED: the rows are present and inspectable, and what is
            // blocked is publication of the affected aggregates rather than the load itself.
            // Collapsing the two would send a batch whose data is fine into the retry path,
            // where re-extracting would produce the same figures and the same disagreement.
            SyncStatus outcome = reconciled.blocksPublication()
                    ? SyncStatus.RECONCILE_FAILED
                    : SyncStatus.SUCCESS;
            finish(syncBatchId, outcome, startedAt);
            log.info("[FinanceSync] Batch {} finished {} in {} ms: {} staged, {} validated, "
                            + "{} mapped, {} facts written, {} checks ({} failed, {} not available), "
                            + "{} year(s) republished ({} withheld), {} aggregate row(s)",
                    syncBatchId, outcome, result.duration().toMillis(), extracted.rowsStaged(),
                    validated.validated(), mapped.decided(), loaded.factsWritten(),
                    reconciled.checksRun(), reconciled.failed(), reconciled.notAvailable(),
                    aggregated.rebuilt().size(), aggregated.blocked().size(),
                    aggregated.rowsWritten());
            return result;

        } catch (RuntimeException failure) {
            // Recorded first, so that a batch is never left RUNNING with nothing explaining why.
            recordFailure(syncBatchId, stage, failure);
            finish(syncBatchId, SyncStatus.FAILED, startedAt);
            log.error("[FinanceSync] Batch {} failed at stage {} after {} ms: {}: {}",
                    syncBatchId, stage, Duration.between(startedAt, LocalDateTime.now()).toMillis(),
                    failure.getClass().getSimpleName(), failure.getMessage());
            throw new PipelineFailedException(
                    "Batch " + syncBatchId + " failed at stage " + stage, stage, failure);
        }
    }

    /**
     * Takes ownership of the batch, or refuses.
     *
     * <p>Conditional on {@code PENDING}, so exactly one of two racing callers wins. A batch in
     * any other state is refused by name rather than skipped: a caller that asked for a run and
     * got silence would have no way to tell "already done" from "did nothing".
     */
    private void claim(FinSyncBatch batch) {
        Integer claimed = transactionTemplate.execute(tx ->
                batches.claimForRun(batch.getId(), SyncStatus.PENDING, SyncStatus.RUNNING,
                        LocalDateTime.now()));
        if (claimed == null || claimed == 0) {
            SyncStatus actual = batches.findById(batch.getId())
                    .map(FinSyncBatch::getStatus).orElse(null);
            throw new BatchNotClaimableException(
                    "Batch " + batch.getId() + " is " + actual + ", not PENDING. Another runner "
                            + "holds it, or it has already finished. Re-processing means a new "
                            + "batch, not a status reset.");
        }
    }

    /** Terminal status, finish time and duration, in one transaction of its own. */
    private void finish(long syncBatchId, SyncStatus status, LocalDateTime startedAt) {
        transactionTemplate.execute(tx -> {
            FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow();
            LocalDateTime finishedAt = LocalDateTime.now();
            batch.setStatus(status);
            batch.setFinishedAt(finishedAt);
            batch.setDurationMs(Duration.between(startedAt, finishedAt).toMillis());
            // watermark_after is deliberately not set. See the class javadoc and FIN-D-049.
            return batches.save(batch);
        });
    }

    /**
     * Records which stage failed and why.
     *
     * <p>Its own transaction, and best effort: if this write fails too, the original exception
     * still propagates rather than being replaced by a bookkeeping error, and the log carries
     * the context needed to find the batch by hand.
     */
    private void recordFailure(long syncBatchId, SyncStage stage, RuntimeException failure) {
        try {
            transactionTemplate.execute(tx -> errors.save(FinSyncError.builder()
                    .syncBatchId(syncBatchId)
                    .errorStage(stage)
                    .errorCode("STAGE_FAILED")
                    .errorMessage(failure.getClass().getName() + ": " + failure.getMessage())
                    .build()));
        } catch (RuntimeException couldNotRecord) {
            log.error("[FinanceSync] Batch {} failed at stage {} AND its failure could not be "
                            + "recorded. Original: {}. Recording error: {}",
                    syncBatchId, stage, failure.toString(), couldNotRecord.toString());
        }
    }

    /** What a run did, stage by stage. */
    public record Result(long syncBatchId,
                         RevenueExtractionStage.Result extracted,
                         RevenueStagingValidator.Result validated,
                         RevenueMappingStage.Result mapped,
                         RevenueLoadStage.Result loaded,
                         RevenueReconciliationStage.Result reconciled,
                         RevenueAggregationRebuilder.Result aggregated,
                         Duration duration) {

        /** True when reconciliation found a disagreement the aggregates must not be built on. */
        public boolean blocksPublication() {
            return reconciled.blocksPublication();
        }
    }

    /** The batch was not available to run. Never thrown for a batch that does not exist. */
    public static class BatchNotClaimableException extends RuntimeException {
        public BatchNotClaimableException(String message) {
            super(message);
        }
    }

    /** A stage failed. The batch is {@code FAILED} and the failing stage is named. */
    public static class PipelineFailedException extends RuntimeException {
        private final transient SyncStage stage;

        public PipelineFailedException(String message, SyncStage stage, Throwable cause) {
            super(message, cause);
            this.stage = stage;
        }

        public SyncStage stage() {
            return stage;
        }
    }
}

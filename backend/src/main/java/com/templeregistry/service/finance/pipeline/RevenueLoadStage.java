package com.templeregistry.service.finance.pipeline;

import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a batch's normalized facts to {@code fin_revenue_fact} (FIN-056).
 *
 * <p>The last stage of the revenue pipeline, and the first point at which this platform stores a
 * figure it will stand behind. Everything before it is evidence or interpretation; this is the
 * reporting boundary (ADR-002).
 *
 * <pre>
 *   normalized fact --+--> upserted through uk_frf_grain, its staged rows marked LOADED
 *                     +--> recorded as a LOAD-stage error, batch fails
 * </pre>
 *
 * <h2>Idempotency, and the difference between a retry and a restatement</h2>
 *
 * <p>Both are handled by the same upsert, and they are not the same thing.
 *
 * <p>A <b>retry</b> — loading the same batch again after a partial failure — must leave the same
 * totals. It does, because each fact is keyed on the grain and its measures are assigned rather
 * than accumulated, and because the staging transition is conditional so a row already {@code
 * LOADED} is not counted a second time.
 *
 * <p>A <b>restatement</b> — a later batch re-reading days that are already loaded — must replace
 * those days, not add to them. It does, for the same reason. This is the single easiest way to
 * double a temple's reported revenue, and the constraint alone does not prevent it: a loader
 * that wrote {@code gross_amount = gross_amount + VALUES(gross_amount)} would satisfy every
 * unique key and still be wrong on every replay.
 *
 * <h2>What a restatement cannot do</h2>
 *
 * <p>If a source <em>deletes</em> records, this stage will not notice. A later batch produces no
 * fact for the grain those records made, so the earlier fact survives untouched and overstates.
 * Detecting that needs either a full reload of a date range or a reconciliation against source
 * totals (FIN-060), and it cannot be inferred from an incremental batch, whose window is a
 * modification window and not a business-date range (ADR-006). Nothing here guesses at it.
 *
 * <h2>Failure</h2>
 *
 * <p>A batch that cannot write every fact fails. It does not report success having written
 * some of them: a partially loaded batch whose status says {@code SUCCESS} is worse than one
 * that says {@code FAILED}, because the second is investigated and the first is believed.
 */
public class RevenueLoadStage {

    private static final Logger log = LoggerFactory.getLogger(RevenueLoadStage.class);

    private final RevenueNormalizationStage normalization;
    private final FinRevenueFactRepository facts;
    private final FinStgRevenueRepository staging;
    private final FinSyncBatchRepository batches;
    private final FinSyncErrorRepository errors;
    private final TransactionTemplate transactionTemplate;

    public RevenueLoadStage(RevenueNormalizationStage normalization,
                            FinRevenueFactRepository facts,
                            FinStgRevenueRepository staging,
                            FinSyncBatchRepository batches,
                            FinSyncErrorRepository errors,
                            TransactionTemplate transactionTemplate) {
        this.normalization = normalization;
        this.facts = facts;
        this.staging = staging;
        this.batches = batches;
        this.errors = errors;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Normalizes a batch and loads what it produced.
     *
     * @throws IllegalArgumentException if the batch does not exist
     * @throws IllegalStateException    if the source cannot be normalized at all
     * @throws LoadFailedException      if any fact could not be written; the batch's errors are
     *                                  recorded first, at stage {@link SyncStage#LOAD}
     */
    public Result loadBatch(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch " + syncBatchId));

        RevenueNormalizationStage.Result normalized = normalization.normalizeBatch(syncBatchId);

        List<Failure> failures = new ArrayList<>();
        int factsWritten = 0;
        int rowsLoaded = 0;

        for (RevenueNormalizationStage.NormalizedFact fact : normalized.facts()) {
            try {
                // One transaction per fact, so a failure late in a batch does not discard the
                // facts already written. They are correct, they are idempotent, and losing them
                // would make a retry redo work that had succeeded.
                Integer moved = transactionTemplate.execute(tx -> write(fact));
                factsWritten++;
                rowsLoaded += moved == null ? 0 : moved;
            } catch (RuntimeException failed) {
                log.warn("[FinanceSync] Batch {} could not load the fact for {} / category {}: {}",
                        syncBatchId, fact.transactionDate(), fact.categoryId(),
                        failed.getMessage());
                failures.add(new Failure(fact, failed));
            }
        }

        recordErrors(batch, failures);
        updateCounters(syncBatchId);

        if (!failures.isEmpty()) {
            throw new LoadFailedException(
                    "Batch " + syncBatchId + " loaded " + factsWritten + " of "
                            + normalized.facts().size() + " facts; " + failures.size()
                            + " failed and are recorded at stage LOAD");
        }

        log.info("[FinanceSync] Batch {} loaded: {} facts from {} staged rows",
                syncBatchId, factsWritten, rowsLoaded);
        return new Result(syncBatchId, factsWritten, rowsLoaded, normalized.recordsRejected());
    }

    /** Writes one fact and claims its staged rows. Returns how many rows this call moved. */
    private int write(RevenueNormalizationStage.NormalizedFact fact) {
        LocalDateTime now = LocalDateTime.now();
        facts.upsert(fact.templeId(), fact.sourceSystemId(), fact.syncBatchId(),
                fact.sourceOfTruthVersion(), fact.sourceRecordRef(), fact.transactionDate(),
                fact.financialYear(), fact.serviceId(), fact.categoryId(),
                fact.paymentMode().name(), fact.paymentModeConfidence().name(),
                fact.counterRef(), fact.operatorRef(), fact.transactionCount(),
                fact.grossAmount(), fact.cancelledCount(), fact.cancelledAmount(),
                fact.quantity(), "INR", now);

        // Only after the fact exists (FIN-D-038). The transition is conditional on VALID, so a
        // row loaded by an earlier run is not counted again.
        return staging.markLoaded(fact.stagedRowIds(), StagingStatus.VALID, StagingStatus.LOADED, now);
    }

    /** Replaces this stage's errors for the batch, scoped to {@code LOAD} (FIN-D-032). */
    private void recordErrors(FinSyncBatch batch, List<Failure> failures) {
        transactionTemplate.execute(tx -> {
            errors.deleteBySyncBatchIdAndErrorStage(batch.getId(), SyncStage.LOAD);
            for (Failure failure : failures) {
                errors.save(FinSyncError.builder()
                        .syncBatchId(batch.getId())
                        .sourceRecordRef(failure.fact().sourceRecordRef())
                        .errorStage(SyncStage.LOAD)
                        .errorCode("FACT_WRITE_FAILED")
                        .errorMessage("Could not write the fact for " + failure.fact().transactionDate()
                                + ", category " + failure.fact().categoryId() + ": "
                                + failure.cause().getClass().getSimpleName() + " — "
                                + failure.cause().getMessage())
                        .build());
            }
            return null;
        });
    }

    /**
     * Sets {@code rows_loaded} from the staged rows that are actually {@code LOADED}.
     *
     * <p>Derived, never incremented — the rule FIN-D-023 imposed on {@code rows_rejected}, for
     * the same reason: a counter a loop adds to drifts the moment the loop is retried, and a
     * batch reporting more rows loaded than it holds is a number nobody can reconcile.
     */
    private void updateCounters(long syncBatchId) {
        transactionTemplate.execute(tx -> {
            FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow();
            batch.setRowsLoaded(
                    staging.countBySyncBatchIdAndValidationStatus(syncBatchId, StagingStatus.LOADED));
            batches.save(batch);
            return null;
        });
    }

    /** What a load did. Rejections are normalization's, carried through for the batch summary. */
    public record Result(long syncBatchId, int factsWritten, int rowsLoaded, int recordsRejected) {
    }

    private record Failure(RevenueNormalizationStage.NormalizedFact fact, RuntimeException cause) {
    }

    /**
     * A batch that could not write every fact.
     *
     * <p>Unchecked and deliberately fatal to the batch: the orchestrator must let it mark the
     * batch {@code FAILED} rather than catching it into a "partially loaded" outcome, which
     * would be exactly the silence FIN-D-017 exists to prevent.
     */
    public static class LoadFailedException extends RuntimeException {
        public LoadFailedException(String message) {
            super(message);
        }
    }
}

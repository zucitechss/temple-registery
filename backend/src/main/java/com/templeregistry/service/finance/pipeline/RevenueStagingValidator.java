package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Decides whether a staged revenue record is structurally usable, and records the answer.
 *
 * <pre>
 *   RECEIVED --+--> VALID
 *              |
 *              +--> REJECTED  (+ one fin_sync_error, stage VALIDATE)
 * </pre>
 *
 * <h2>What VALID does and does not mean</h2>
 *
 * <p>It means only that these rules passed: the record can be located, its provenance agrees
 * with its batch, and its payload is a readable set of source fields. It does <b>not</b> mean
 * the category is mapped, the service is resolved, the amount is authoritative, the figure is
 * reconciled, or the row is ready to report. Those belong to FIN-054, FIN-055, FIN-056 and
 * FIN-060, and nothing here should be read as having settled them.
 *
 * <h2>What is deliberately not validated</h2>
 *
 * <p>Dates and amounts. Their location inside {@code raw_json} is the connector's own field
 * naming, so the only way to check them here would be to teach this class the field names of
 * one temple's source system — which is exactly the knowledge that stops at the connector.
 * Normalization (FIN-055) reads them against the source-of-truth declaration, which is where
 * the platform learns which field is authoritative for whom. A rule that cannot be stated
 * generically is deferred, not approximated.
 *
 * <h2>Rejection is a record, not a deletion</h2>
 *
 * <p>A rejected row keeps its payload and gains a reason; a {@code fin_sync_error} carries the
 * coded, queryable form. One error per rejected row, so {@code rows_rejected = 143} means 143
 * error rows, traceable one to one. Where a row breaks several rules the message names all of
 * them and the code names the first in a fixed order, so the same defect always produces the
 * same code.
 *
 * <h2>Concurrency and failure</h2>
 *
 * <p>Each row is processed in its own transaction, and its status transition is a conditional
 * claim: a second validator running the same batch matches nothing and does no work. The
 * status change and the error insert commit together, so the one outcome this class must never
 * produce — a row rejected but not recorded, or recorded but left {@code RECEIVED} — cannot
 * occur. A persistence failure aborts the run and leaves the remaining rows {@code RECEIVED}
 * for a re-run, rather than skipping them.
 *
 * <h2>Why the run is guaranteed to end</h2>
 *
 * <p>Rows are read through an advancing id cursor, so each one is offered exactly once and the
 * query runs out whether or not this validator manages to claim anything. The earlier shape of
 * this loop repeatedly asked for the first page of {@code RECEIVED} rows and relied on every
 * row it read leaving that state; a row that stayed — the ordinary outcome of losing a race to
 * a second validator — was handed back on the next pass for ever. That is worth stating
 * plainly because of how the failure presents: not an exception and not a failed batch, but a
 * worker quietly consuming a database connection while a dashboard shows stale figures and
 * nothing anywhere reports a problem. A hang is harder to notice than a crash, which is why
 * termination here is structural rather than a check somebody has to remember.
 */
public class RevenueStagingValidator {

    private static final Logger log = LoggerFactory.getLogger(RevenueStagingValidator.class);

    /** Bounded so a first historical load cannot be pulled into memory at once. */
    static final int CHUNK_SIZE = 500;

    private final FinStgRevenueRepository staging;
    private final FinSyncErrorRepository errors;
    private final FinSyncBatchRepository batches;
    private final TransactionTemplate perRowTransaction;
    private final ObjectMapper objectMapper;

    public RevenueStagingValidator(FinStgRevenueRepository staging,
                                   FinSyncErrorRepository errors,
                                   FinSyncBatchRepository batches,
                                   TransactionTemplate perRowTransaction,
                                   ObjectMapper objectMapper) {
        this.staging = staging;
        this.errors = errors;
        this.batches = batches;
        this.perRowTransaction = perRowTransaction;
        this.objectMapper = objectMapper;
    }

    /**
     * What one validation run did.
     *
     * <p>{@code alreadyClaimed} counts rows another validator took first. Each row is offered
     * once, so it is a count of rows and not of attempts, and the three figures sum to the
     * rows this run looked at. Only {@link #processed()} is a claim about work done: a row
     * somebody else claimed was not validated by this run and must not be reported as though
     * it were.
     */
    public record Result(long syncBatchId, int validated, int rejected, int alreadyClaimed) {
        public int processed() {
            return validated + rejected;
        }
    }

    /**
     * Validates every {@code RECEIVED} row of one batch.
     *
     * @throws IllegalArgumentException if the batch does not exist -- a validation run against
     *         a batch nobody recorded would produce results that cannot be explained
     */
    public Result validateBatch(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch [" + syncBatchId
                        + "]. Staged rows are only meaningful against the run that produced them."));

        int validated = 0;
        int rejected = 0;
        int alreadyClaimed = 0;

        // Cursor, not an offset. See the repository method for why the difference is the
        // difference between a loop that ends and one that does not.
        long afterId = 0L;

        while (true) {
            List<FinStgRevenue> chunk =
                    staging.findBySyncBatchIdAndValidationStatusAndIdGreaterThanOrderByIdAsc(
                            syncBatchId, StagingStatus.RECEIVED, afterId, PageRequest.of(0, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }

            for (FinStgRevenue row : chunk) {
                // Not caught: a persistence failure must stop the run with the remaining rows
                // still RECEIVED. Continuing past it would silently skip records, which is the
                // one outcome worse than failing.
                Outcome outcome = perRowTransaction.execute(status -> process(batch, row));

                switch (Objects.requireNonNull(outcome)) {
                    case VALIDATED -> validated++;
                    case REJECTED -> rejected++;
                    // Somebody else claimed it. Counted, never processed, and deliberately
                    // not retried: retrying is what would spin.
                    case ALREADY_CLAIMED -> alreadyClaimed++;
                }
            }

            // Advanced once per chunk, after every row in it has been offered. Strictly
            // increasing, so the query is guaranteed to run out.
            afterId = chunk.get(chunk.size() - 1).getId();
        }

        // Derived, never incremented: re-running validation cannot inflate it, and every unit
        // it counts is an error row somebody can open.
        long errorRows = errors.countBySyncBatchId(syncBatchId);
        batch.setRowsRejected(errorRows);
        batches.save(batch);

        log.info("[FinanceSync] Validation of batch {}: {} valid, {} rejected, {} already claimed",
                syncBatchId, validated, rejected, alreadyClaimed);

        return new Result(syncBatchId, validated, rejected, alreadyClaimed);
    }

    private Outcome process(FinSyncBatch batch, FinStgRevenue row) {
        List<Failure> failures = check(batch, row);
        LocalDateTime now = LocalDateTime.now();

        if (failures.isEmpty()) {
            int claimed = staging.transition(
                    row.getId(), StagingStatus.RECEIVED, StagingStatus.VALID, null, now);
            return claimed == 1 ? Outcome.VALIDATED : Outcome.ALREADY_CLAIMED;
        }

        String reason = failures.stream().map(Failure::message).collect(Collectors.joining("; "));
        int claimed = staging.transition(
                row.getId(), StagingStatus.RECEIVED, StagingStatus.REJECTED, reason, now);
        if (claimed != 1) {
            return Outcome.ALREADY_CLAIMED;
        }

        errors.save(FinSyncError.builder()
                .syncBatchId(batch.getId())
                .sourceRecordRef(row.getSourceRecordRef())
                .errorStage(SyncStage.VALIDATE)
                .errorCode(failures.get(0).code())
                .errorMessage(reason)
                // raw_payload_json is left null on purpose: the staged row still holds the
                // payload, and copying source data into a second table would duplicate
                // whatever personal information a temple's records happen to contain. The
                // batch and source_record_ref locate the original. See the Q7 note in
                // HANDOFF.md -- if staging is ever purged on a schedule, this needs revisiting.
                .build());

        return Outcome.REJECTED;
    }

    /**
     * The rule set, in a fixed order so that one defect always produces one code.
     *
     * <p>Every rule here can be stated without knowing anything about any particular source
     * system. That is the whole test for whether a rule belongs in this class.
     */
    private List<Failure> check(FinSyncBatch batch, FinStgRevenue row) {
        List<Failure> failures = new ArrayList<>();

        // 1. A record that cannot be located cannot be investigated, which is the only reason
        //    a rejected row is worth keeping. The column is NOT NULL but permits whitespace.
        if (row.getSourceRecordRef() == null || row.getSourceRecordRef().isBlank()) {
            failures.add(new Failure("BLANK_RECORD_REF",
                    "source_record_ref is blank, so the original record cannot be located in the source"));
        }

        // 2. Provenance must agree with the batch. The database cannot check this, and getting
        //    it wrong attributes one temple's money to another.
        if (!Objects.equals(row.getTempleId(), batch.getTempleId())) {
            failures.add(new Failure("PROVENANCE_MISMATCH",
                    "staged temple_id " + row.getTempleId() + " does not match batch temple_id "
                            + batch.getTempleId()));
        }
        if (!Objects.equals(row.getSourceSystemId(), batch.getSourceSystemId())) {
            failures.add(new Failure("PROVENANCE_MISMATCH",
                    "staged source_system_id " + row.getSourceSystemId()
                            + " does not match batch source_system_id " + batch.getSourceSystemId()));
        }

        // 3-5. The payload must be the shape the connector contract promises: a flat object of
        //      source field names to string values. Anything else means the record cannot be
        //      read later, and finding that out at normalization would be finding it out with
        //      the evidence a stage further away.
        if (row.getRawJson() == null || row.getRawJson().isBlank()) {
            failures.add(new Failure("MISSING_PAYLOAD", "raw_json is absent"));
            return failures;
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(row.getRawJson());
        } catch (JsonProcessingException e) {
            // The location, not the parser's message: Jackson quotes the offending source text,
            // and an error table is not a place to copy a temple's records into.
            failures.add(new Failure("MALFORMED_PAYLOAD",
                    "raw_json is not parseable as JSON at line " + e.getLocation().getLineNr()
                            + ", column " + e.getLocation().getColumnNr()));
            return failures;
        }

        if (payload == null || !payload.isObject()) {
            failures.add(new Failure("PAYLOAD_NOT_OBJECT",
                    "raw_json is not a JSON object of source fields"));
            return failures;
        }
        if (payload.isEmpty()) {
            failures.add(new Failure("EMPTY_PAYLOAD",
                    "raw_json contains no source fields, so the record carries no information"));
            return failures;
        }

        List<String> nonScalar = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : payload.properties()) {
            JsonNode value = field.getValue();
            // A JSON null is allowed and meaningful: the source had no value for this field.
            // It is not an error, and it must never become "" or 0 later.
            if (value.isContainerNode()) {
                nonScalar.add(field.getKey());
            }
        }
        if (!nonScalar.isEmpty()) {
            failures.add(new Failure("NON_SCALAR_FIELD",
                    "raw_json fields " + nonScalar + " hold nested structures; the connector "
                            + "contract delivers flat source fields"));
        }

        return failures;
    }

    private record Failure(String code, String message) {
    }

    private enum Outcome {
        VALIDATED,
        REJECTED,
        /** Another validator moved the row out of RECEIVED first. */
        ALREADY_CLAIMED
    }
}

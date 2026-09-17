package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.DateRange;
import com.templeregistry.connector.finance.RawRow;
import com.templeregistry.connector.finance.SourceSystemDescriptor;
import com.templeregistry.connector.finance.SyncContext;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Drains a connector's extract into {@code fin_stg_revenue} (FIN-057).
 *
 * <p>The step nobody owned. Limitation 11 recorded extraction as FIN-043's, but FIN-043 is
 * scoped as the <em>Kollur</em> connector's {@code extract()} — its live receipt table and six
 * financial-year archives. Taking a {@code Stream<RawRow>} and landing it in staging is generic,
 * belongs to no temple, and without it the pipeline has no first step in production at all.
 *
 * <h2>It stores; it does not interpret</h2>
 *
 * <p>A {@link RawRow}'s values go to {@code raw_json} exactly as delivered, as strings. Nothing
 * here parses a date, reads an amount, or looks at a field name — those are FIN-055's and they
 * happen after validation, against a declaration. That is also what keeps this class the same
 * code for every temple and every transport: it names no source table and no source column.
 *
 * <h2>One bad row does not lose a batch</h2>
 *
 * <p>Rows are staged in chunks, each chunk in its own transaction, and a chunk that fails is
 * retried row by row so that the one offending record is recorded and the rest land. The
 * alternative — one transaction for the extract — means a single duplicate reference at row
 * 40,000 discards everything, which is exactly the failure mode staging's loose payload exists
 * to prevent.
 *
 * <p>A repeated {@code sourceRecordRef} inside one batch is refused by {@code
 * uk_fsr_batch_record} and recorded at stage {@code EXTRACT}. It is either a connector defect or
 * a reference that does not identify what it claims to, and both double-count downstream.
 */
public class RevenueExtractionStage {

    private static final Logger log = LoggerFactory.getLogger(RevenueExtractionStage.class);

    private static final FinanceCapability CAPABILITY = FinanceCapability.REVENUE;
    private static final int CHUNK_SIZE = 500;

    private final ConnectorRegistry connectors;
    private final FinSourceSystemRepository sourceSystems;
    private final FinStgRevenueRepository staging;
    private final FinSyncBatchRepository batches;
    private final FinSyncErrorRepository errors;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public RevenueExtractionStage(ConnectorRegistry connectors,
                                  FinSourceSystemRepository sourceSystems,
                                  FinStgRevenueRepository staging,
                                  FinSyncBatchRepository batches,
                                  FinSyncErrorRepository errors,
                                  TransactionTemplate transactionTemplate,
                                  ObjectMapper objectMapper) {
        this.connectors = connectors;
        this.sourceSystems = sourceSystems;
        this.staging = staging;
        this.batches = batches;
        this.errors = errors;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts a batch's window into staging.
     *
     * @throws IllegalArgumentException if the batch or its source system does not exist
     * @throws com.templeregistry.connector.finance.ConnectorConfigurationException
     *         if the source names a connector nobody registered — never a silent empty extract
     */
    public Result extractBatch(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch " + syncBatchId));
        FinSourceSystem source = sourceSystems.findById(batch.getSourceSystemId()).orElseThrow(() ->
                new IllegalArgumentException(
                        "Batch " + syncBatchId + " names source system " + batch.getSourceSystemId()
                                + ", which does not exist"));

        SourceSystemDescriptor descriptor = describe(source);
        TempleFinanceConnector connector = connectors.resolve(source.getConnectorBean(), descriptor);
        connector.requireCapability(CAPABILITY, descriptor);

        SyncContext context = contextFor(batch, descriptor);

        int staged = 0;
        int refused = 0;
        List<RawRow> chunk = new ArrayList<>(CHUNK_SIZE);

        // try-with-resources: a connector's stream may hold a cursor or a file handle, and the
        // contract says the caller closes it.
        try (Stream<RawRow> rows = connector.extract(CAPABILITY, context)) {
            for (RawRow row : (Iterable<RawRow>) rows::iterator) {
                chunk.add(row);
                if (chunk.size() == CHUNK_SIZE) {
                    int[] outcome = flush(batch, chunk);
                    staged += outcome[0];
                    refused += outcome[1];
                    chunk = new ArrayList<>(CHUNK_SIZE);
                }
            }
        }
        if (!chunk.isEmpty()) {
            int[] outcome = flush(batch, chunk);
            staged += outcome[0];
            refused += outcome[1];
        }

        updateCounters(syncBatchId);
        log.info("[FinanceSync] Batch {} extracted: {} rows staged, {} refused",
                syncBatchId, staged, refused);
        return new Result(syncBatchId, staged, refused);
    }

    /** @return {staged, refused} */
    private int[] flush(FinSyncBatch batch, List<RawRow> chunk) {
        try {
            transactionTemplate.execute(tx -> staging.saveAll(toStagedRows(batch, chunk)));
            return new int[]{chunk.size(), 0};
        } catch (DataIntegrityViolationException | UnexpectedRollbackException rejected) {
            // The chunk is doomed by one row; find out which rather than losing 500.
            return flushIndividually(batch, chunk);
        }
    }

    /**
     * Retries a doomed chunk one row at a time, building fresh entities.
     *
     * <p>Rebuilding matters more than it looks. The failed {@code saveAll} assigned generated ids
     * to the entity instances it was given, and the rollback did not take them back — so
     * re-saving those same instances makes Hibernate treat each as detached and attempt a
     * <em>merge</em> against a row that was never inserted, which fails with a stale-object
     * error rather than the constraint violation the caller is trying to diagnose. Building from
     * the {@link RawRow} again gives every retry a genuinely new entity.
     */
    private int[] flushIndividually(FinSyncBatch batch, List<RawRow> chunk) {
        int staged = 0;
        int refused = 0;
        for (RawRow raw : chunk) {
            FinStgRevenue row = toStagedRow(batch, raw);
            try {
                transactionTemplate.execute(tx -> staging.save(row));
                staged++;
            } catch (DataIntegrityViolationException | UnexpectedRollbackException rejected) {
                refused++;
                recordRefusal(row, rejected);
            }
        }
        return new int[]{staged, refused};
    }

    private List<FinStgRevenue> toStagedRows(FinSyncBatch batch, List<RawRow> chunk) {
        return chunk.stream().map(raw -> toStagedRow(batch, raw)).toList();
    }

    private void recordRefusal(FinStgRevenue row, RuntimeException cause) {
        transactionTemplate.execute(tx -> errors.save(FinSyncError.builder()
                .syncBatchId(row.getSyncBatchId())
                .sourceRecordRef(row.getSourceRecordRef())
                .errorStage(SyncStage.EXTRACT)
                .errorCode("DUPLICATE_SOURCE_RECORD_REF")
                .errorMessage("The connector delivered this reference twice within one batch, or "
                        + "the reference does not identify a single source record. Refused by "
                        + "uk_fsr_batch_record: " + cause.getClass().getSimpleName())
                .rawPayloadJson(row.getRawJson())
                .build()));
    }

    private FinStgRevenue toStagedRow(FinSyncBatch batch, RawRow row) {
        return FinStgRevenue.builder()
                .templeId(batch.getTempleId())
                .sourceSystemId(batch.getSourceSystemId())
                .syncBatchId(batch.getId())
                .sourceRecordRef(row.sourceRecordRef())
                .rawJson(toJson(row))
                .validationStatus(StagingStatus.RECEIVED)
                .extractedAt(LocalDateTime.now())
                .build();
    }

    private String toJson(RawRow row) {
        try {
            return objectMapper.writeValueAsString(row.values());
        } catch (Exception unserialisable) {
            // RawRow values are Map<String, String>, so this cannot happen without a bug in the
            // mapper itself. Failing loudly is right: a payload we cannot store is not a row we
            // can silently drop.
            throw new IllegalStateException(
                    "Could not serialise the payload for " + row.sourceRecordRef(), unserialisable);
        }
    }

    /**
     * Sets {@code rows_extracted} from what is actually in staging for this batch.
     *
     * <p>Derived, never incremented — the rule FIN-D-023 imposed on {@code rows_rejected} and
     * FIN-056 on {@code rows_loaded}. A counter a loop adds to drifts the moment the loop is
     * retried.
     */
    private void updateCounters(long syncBatchId) {
        transactionTemplate.execute(tx -> {
            FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow();
            batch.setRowsExtracted(staging.countBySyncBatchId(syncBatchId));
            return batches.save(batch);
        });
    }

    /**
     * The window this batch covers, taken from the batch rather than invented here.
     *
     * <p>{@code windowFrom} is the change-axis lower bound and is genuinely optional: absent
     * means "everything the source holds", which is what a historical load asks for. Reading it
     * from the batch is what keeps ADR-006's rule intact — the batch owns the window, and a
     * connector is told it rather than deciding it.
     *
     * <p>The business-date range stays unbounded. It is a different axis from the change window
     * (FIN-D-012), nothing currently sets it, and narrowing it here would silently drop records
     * whose business date falls outside a range nobody asked for.
     */
    private SyncContext contextFor(FinSyncBatch batch, SourceSystemDescriptor descriptor) {
        return new SyncContext(
                descriptor,
                batch.getBatchRef(),
                batch.getSyncType(),
                Optional.ofNullable(batch.getWindowFrom()).map(from -> from.toInstant(ZoneOffset.UTC)),
                Optional.ofNullable(batch.getWindowTo()).orElse(LocalDateTime.now())
                        .toInstant(ZoneOffset.UTC),
                DateRange.unbounded());
    }

    private SourceSystemDescriptor describe(FinSourceSystem source) {
        return new SourceSystemDescriptor(
                source.getTempleId(),
                source.getId(),
                source.getSystemCode(),
                source.getConnectorType(),
                source.getSourceTechnology(),
                source.getSourceTempleCode(),
                source.getCredentialRef(),
                source.getSourceTimezone());
    }

    /** What an extract landed. {@code refused} rows are recorded at stage {@code EXTRACT}. */
    public record Result(long syncBatchId, int rowsStaged, int rowsRefused) {
    }
}

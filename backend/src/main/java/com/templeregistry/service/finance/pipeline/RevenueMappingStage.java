package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinStgRevenueMapping;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates a batch's validated source values into canonical ones, and records how (FIN-054).
 *
 * <pre>
 *   VALID staged row --+--> MAPPED                (one rule won)
 *                      +--> UNMAPPED              (value present, no rule -- routed to UNMAPPED)
 *                      +--> AMBIGUOUS             (rules disagree, configuration must decide)
 *                      +--> NOT_APPLICABLE        (nothing to map)
 *                      +--> INVALID_CONFIGURATION (a rule names a category that does not exist)
 * </pre>
 *
 * <h2>Which of the two mappings this is</h2>
 *
 * <p>ADR-004: which rows and which columns is connector code; what a value means is
 * configuration. This is the second. Nothing here names a source table or column, and the
 * fields it reads from a staged payload are derived from the mapping rules themselves — see
 * {@link MappingRuleResolver}. That is what makes it the same code for every temple.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No amounts, no dates, no financial year, no service resolution, no canonical row. Mapping
 * answers one question — what kind of income is this — and stops. Amounts and dates are
 * FIN-055's, keyed on the source-of-truth declaration; writing {@code fin_revenue_fact} is
 * FIN-056's. Staging itself is not touched at all: what the source sent is evidence, and this
 * stage's opinion of it belongs beside it rather than inside it.
 *
 * <h2>Re-running</h2>
 *
 * <p>Deliberately safe and deliberately expected: correcting a mapping rule and running again
 * is the supported way to fix a misclassification without re-contacting the source. Each
 * decision is keyed on {@code (staged row, mapping type)}, so a re-run replaces the decision
 * rather than adding a second one, and this stage's own errors are cleared before they are
 * rewritten so a batch's error list cannot grow on every attempt.
 *
 * <h2>Termination</h2>
 *
 * <p>Rows are read through an advancing id cursor, so each is offered exactly once and the
 * query runs out regardless of what happens to any individual row (FIN-D-026). The offset form
 * this replaces could not terminate when a row failed to leave the state being queried.
 */
public class RevenueMappingStage {

    private static final Logger log = LoggerFactory.getLogger(RevenueMappingStage.class);

    /** The question this stage answers. The resolver itself is generic over the others. */
    static final MappingType MAPPING_TYPE = MappingType.REVENUE_CATEGORY;

    /** Bounded so a first historical load cannot be pulled into memory at once. */
    static final int CHUNK_SIZE = 500;

    private final FinStgRevenueRepository staging;
    private final FinStgRevenueMappingRepository mappings;
    private final FinMappingRuleRepository rules;
    private final FinRevenueCategoryRepository categories;
    private final FinSyncErrorRepository errors;
    private final FinSyncBatchRepository batches;
    private final TransactionTemplate perRowTransaction;
    private final ObjectMapper objectMapper;

    public RevenueMappingStage(FinStgRevenueRepository staging,
                               FinStgRevenueMappingRepository mappings,
                               FinMappingRuleRepository rules,
                               FinRevenueCategoryRepository categories,
                               FinSyncErrorRepository errors,
                               FinSyncBatchRepository batches,
                               TransactionTemplate perRowTransaction,
                               ObjectMapper objectMapper) {
        this.staging = staging;
        this.mappings = mappings;
        this.rules = rules;
        this.categories = categories;
        this.errors = errors;
        this.batches = batches;
        this.perRowTransaction = perRowTransaction;
        this.objectMapper = objectMapper;
    }

    /** What one mapping run did, by outcome. */
    public record Result(long syncBatchId, Map<MappingOutcome, Integer> byOutcome, int contended) {

        public int count(MappingOutcome outcome) {
            return byOutcome.getOrDefault(outcome, 0);
        }

        /** Rows this run decided, whether the answer was a category or the UNMAPPED category. */
        public int decided() {
            return count(MappingOutcome.MAPPED) + count(MappingOutcome.UNMAPPED);
        }

        /** Rows this run could not decide. None of these may be loaded by FIN-056. */
        public int undecided() {
            return count(MappingOutcome.AMBIGUOUS)
                    + count(MappingOutcome.NOT_APPLICABLE)
                    + count(MappingOutcome.INVALID_CONFIGURATION);
        }
    }

    /**
     * Maps every {@code VALID} row of one batch.
     *
     * @throws IllegalArgumentException if the batch does not exist
     * @throws IllegalStateException    if the source system has no usable rules -- mapping a
     *                                  batch with no rule set would mark every row
     *                                  {@code NOT_APPLICABLE}, which reads like clean data
     */
    public Result mapBatch(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch [" + syncBatchId
                        + "]. Staged rows are only meaningful against the run that produced them."));

        List<FinMappingRule> active = rules
                .findBySourceSystemIdAndMappingTypeAndActiveTrueAndDeletedFalse(
                        batch.getSourceSystemId(), MAPPING_TYPE);
        Set<String> knownCategories = categories.findByActiveTrueAndDeletedFalse().stream()
                .map(FinRevenueCategory::getCategoryCode)
                .collect(Collectors.toSet());

        MappingRuleResolver resolver = new MappingRuleResolver(MAPPING_TYPE, active, knownCategories);

        if (!resolver.isUsable()) {
            // Loud, because the quiet alternative is worse: every row NOT_APPLICABLE, a batch
            // that looks processed, and a revenue report with nothing in it.
            throw new IllegalStateException("Source system " + batch.getSourceSystemId()
                    + " has no usable " + MAPPING_TYPE + " rules"
                    + (resolver.configurationProblems().isEmpty()
                            ? ", so no staged record can be classified."
                            : ": " + String.join("; ", resolver.configurationProblems())));
        }

        Map<MappingOutcome, Integer> tally = new EnumMap<>(MappingOutcome.class);
        int contended = 0;
        long afterId = 0L;

        while (true) {
            List<FinStgRevenue> chunk =
                    staging.findBySyncBatchIdAndValidationStatusAndIdGreaterThanOrderByIdAsc(
                            syncBatchId, StagingStatus.VALID, afterId, PageRequest.of(0, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }

            for (FinStgRevenue row : chunk) {
                try {
                    // Only contention is caught. Any other persistence failure must stop the
                    // run rather than leave rows quietly unmapped.
                    MappingOutcome outcome =
                            perRowTransaction.execute(tx -> map(resolver, batch, row));
                    tally.merge(outcome, 1, Integer::sum);
                } catch (DataIntegrityViolationException | UnexpectedRollbackException contention) {
                    // uk_fsrm_row_type: another run recorded a decision for this row between
                    // this one's read and its write. Caught out here rather than inside the
                    // callback because a constraint violation has already marked the
                    // transaction rollback-only -- swallowing it within the callback would
                    // still fail at commit, which is how this was first written and why the
                    // concurrency test failed. The other run's decision is as good as this
                    // one: same rules, same record.
                    contended++;
                }
            }

            afterId = chunk.get(chunk.size() - 1).getId();
        }

        // One transaction: the summary clears this stage's previous errors before rewriting
        // them, and a half-applied clear would leave a batch looking cleaner than it is.
        perRowTransaction.execute(tx -> {
            // Clear first, then write both kinds. Writing the configuration problems before the
            // clear would delete the ones just recorded.
            errors.deleteBySyncBatchIdAndErrorStage(syncBatchId, SyncStage.MAP);
            recordConfigurationProblems(batch, resolver);
            summariseUnresolved(syncBatchId);
            return null;
        });

        log.info("[FinanceSync] Mapping of batch {} ({}): {} mapped, {} unmapped, {} ambiguous, "
                        + "{} not applicable, {} invalid configuration, {} contended",
                syncBatchId, MAPPING_TYPE,
                tally.getOrDefault(MappingOutcome.MAPPED, 0),
                tally.getOrDefault(MappingOutcome.UNMAPPED, 0),
                tally.getOrDefault(MappingOutcome.AMBIGUOUS, 0),
                tally.getOrDefault(MappingOutcome.NOT_APPLICABLE, 0),
                tally.getOrDefault(MappingOutcome.INVALID_CONFIGURATION, 0),
                contended);

        return new Result(syncBatchId, Map.copyOf(tally), contended);
    }

    /**
     * Decides one row and records the decision.
     *
     * @return the outcome, or {@code null} if another run recorded a decision for this row first
     */
    private MappingOutcome map(MappingRuleResolver resolver, FinSyncBatch batch, FinStgRevenue row) {
        MappingRuleResolver.Decision decision = resolver.resolve(readFields(row));

        FinStgRevenueMapping existing = mappings
                .findByStgRevenueIdAndMappingType(row.getId(), MAPPING_TYPE)
                .orElse(null);

        FinStgRevenueMapping record = existing != null ? existing : FinStgRevenueMapping.builder()
                .stgRevenueId(row.getId())
                .mappingType(MAPPING_TYPE)
                .build();

        // Provenance comes from the staged row, never from the batch: FIN-053 has already
        // established the two agree, and copying from the row keeps the decision attached to
        // the record it is about.
        record.setTempleId(row.getTempleId());
        record.setSourceSystemId(row.getSourceSystemId());
        record.setSyncBatchId(row.getSyncBatchId());
        record.setSourceRecordRef(row.getSourceRecordRef());

        record.setSourceField(decision.sourceField());
        record.setSourceValue(decision.sourceValue());
        record.setOutcome(decision.outcome());
        record.setCanonicalValue(decision.canonicalValue());
        record.setMappingRuleId(decision.ruleId());
        record.setRulePriority(decision.priority());
        record.setReason(decision.reason());
        record.setMappedAt(LocalDateTime.now());

        // Not wrapped in a try here: a unique-constraint violation dooms the transaction, so it
        // has to be handled by the caller outside it. See the loop in mapBatch.
        mappings.saveAndFlush(record);

        return decision.outcome();
    }

    /**
     * The staged payload as flat source fields.
     *
     * <p>Only scalars are returned. FIN-053 has already rejected anything else, so a container
     * here would mean a row that never passed validation; ignoring it rather than failing keeps
     * this stage's contract to the one question it answers.
     */
    private Map<String, String> readFields(FinStgRevenue row) {
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            JsonNode payload = objectMapper.readTree(row.getRawJson());
            if (payload == null || !payload.isObject()) {
                return fields;
            }
            for (Map.Entry<String, JsonNode> field : payload.properties()) {
                JsonNode value = field.getValue();
                if (value.isContainerNode()) {
                    continue;
                }
                // A JSON null stays null: the source had no value for this field, which is not
                // the same as an empty string and must never become one.
                fields.put(field.getKey(), value.isNull() ? null : value.asText());
            }
        } catch (Exception unreadable) {
            // Unreachable for a VALID row, and deliberately not fatal: treated as a record with
            // no fields, which resolves to NOT_APPLICABLE rather than to a guess.
            log.warn("[FinanceSync] Staged row {} is VALID but its payload could not be read",
                    row.getId());
        }
        return fields;
    }

    /** Rules that can never fire are a configuration defect, recorded once per batch. */
    private void recordConfigurationProblems(FinSyncBatch batch, MappingRuleResolver resolver) {
        for (String problem : resolver.configurationProblems()) {
            errors.save(FinSyncError.builder()
                    .syncBatchId(batch.getId())
                    .errorStage(SyncStage.MAP)
                    .errorCode("UNUSABLE_MAPPING_RULE")
                    .errorMessage(problem)
                    .build());
        }
    }

    /**
     * One error per distinct unresolved source value, not one per row.
     *
     * <p>A single absent rule can account for an entire batch, and forty thousand identical
     * row-level errors would bury the one fact an operator needs — which value, and how much it
     * costs. The per-row record is in {@code fin_stg_revenue_mapping}; this is the summary that
     * makes it actionable. Stage-scoped deletion first, so re-running replaces this list rather
     * than appending to it, and validation's own errors are untouched.
     */
    private void summariseUnresolved(long syncBatchId) {
        for (MappingOutcome outcome : List.of(MappingOutcome.UNMAPPED,
                                              MappingOutcome.AMBIGUOUS,
                                              MappingOutcome.INVALID_CONFIGURATION)) {
            for (Object[] row : mappings.summariseBySourceValue(syncBatchId, MAPPING_TYPE, outcome)) {
                String sourceValue = (String) row[0];
                long affected = ((Number) row[1]).longValue();
                errors.save(FinSyncError.builder()
                        .syncBatchId(syncBatchId)
                        .errorStage(SyncStage.MAP)
                        .errorCode(outcome.name() + "_" + MAPPING_TYPE.name())
                        .errorMessage(affected + " record(s) with source value ["
                                + Objects.toString(sourceValue, "(none)") + "] ended as " + outcome
                                + ". See fin_stg_revenue_mapping for the records themselves.")
                        .build());
            }
        }
    }
}

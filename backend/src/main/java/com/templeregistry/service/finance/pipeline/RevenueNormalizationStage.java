package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinStgRevenueMapping;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Collapses a batch's mapped records onto the canonical daily grain (FIN-055).
 *
 * <pre>
 *   VALID staged row + its mapping decision --+--> contributes to a daily fact
 *                                             +--> rejected, with a coded reason
 * </pre>
 *
 * <h2>The two things every earlier stage deferred</h2>
 *
 * <p>Amounts and dates. This is the first stage entitled to read a money value, and it reads it
 * where {@code fin_source_of_truth_decl} says to (ADR-008) rather than where a connector felt
 * like putting it. For the first onboarded source that declaration is the difference between
 * the authoritative figure and one 41% short of it.
 *
 * <p>It is also the first stage that must not be wrong about <em>when</em>. The business date
 * comes from the payload, never from the batch: a source editing a two-year-old receipt today
 * corrects an old day, it does not move money into this one (FIN-D-012). {@code
 * fin_stg_revenue.source_business_date} is advisory and is deliberately not consulted.
 *
 * <h2>Why the collapse happens here</h2>
 *
 * <p>Staging keeps one row per record a connector delivered, exactly as delivered, because that
 * is what makes a published figure traceable. The canonical grain is coarser — a day, a
 * category, a payment mode, a counter, an operator (ADR-003) — so several staged records become
 * one fact. Doing that here rather than in a connector means it is visible, testable and
 * identical for every temple; doing it in staging would destroy the evidence.
 *
 * <p>The grain key used to group is {@link RevenueNormalizer.GrainKey}, which mirrors {@code
 * uk_frf_grain}. If the two ever disagree, the same fact lands twice and revenue doubles, so
 * anything added to one belongs in the other.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It writes no canonical row: {@code fin_revenue_fact} has no writer until FIN-056, and the
 * facts computed here are returned rather than persisted. It does not move staged rows to
 * {@code LOADED} either — a record has not been loaded until the load happens, and marking it
 * earlier would strand rows as loaded against a fact that was never written.
 */
public class RevenueNormalizationStage {

    private static final Logger log = LoggerFactory.getLogger(RevenueNormalizationStage.class);

    private static final MappingType MAPPING_TYPE = MappingType.REVENUE_CATEGORY;
    private static final int CHUNK_SIZE = 500;

    private final FinSyncBatchRepository batches;
    private final FinStgRevenueRepository staging;
    private final FinStgRevenueMappingRepository mappings;
    private final FinSourceOfTruthDeclRepository declarations;
    private final FinRevenueCategoryRepository categories;
    private final FinSyncErrorRepository errors;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public RevenueNormalizationStage(FinSyncBatchRepository batches,
                                     FinStgRevenueRepository staging,
                                     FinStgRevenueMappingRepository mappings,
                                     FinSourceOfTruthDeclRepository declarations,
                                     FinRevenueCategoryRepository categories,
                                     FinSyncErrorRepository errors,
                                     TransactionTemplate transactionTemplate,
                                     ObjectMapper objectMapper) {
        this.batches = batches;
        this.staging = staging;
        this.mappings = mappings;
        this.declarations = declarations;
        this.categories = categories;
        this.errors = errors;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Normalizes one batch.
     *
     * @throws IllegalArgumentException if the batch does not exist
     * @throws IllegalStateException    if the source's declarations cannot support normalization;
     *                                  the reasons are recorded against the batch first
     */
    public Result normalizeBatch(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch " + syncBatchId));

        RevenueNormalizer normalizer = new RevenueNormalizer(
                declaredFields(batch.getSourceSystemId()),
                declaredVersion(batch.getSourceSystemId(), RevenueField.GROSS_AMOUNT),
                categoryIdsByCode());

        List<Rejection> rejections = new ArrayList<>();
        if (!normalizer.isUsable()) {
            for (String problem : normalizer.configurationProblems()) {
                rejections.add(new Rejection(null, "UNUSABLE_DECLARATION", problem, null));
            }
            recordErrors(batch, rejections);
            throw new IllegalStateException(
                    "Batch " + syncBatchId + " cannot be normalized: "
                            + String.join("; ", normalizer.configurationProblems()));
        }

        Map<RevenueNormalizer.GrainKey, Accumulator> groups = new LinkedHashMap<>();
        int normalized = 0;
        long afterId = 0L;

        while (true) {
            List<FinStgRevenue> chunk =
                    staging.findBySyncBatchIdAndValidationStatusAndIdGreaterThanOrderByIdAsc(
                            syncBatchId, StagingStatus.VALID, afterId, PageRequest.of(0, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }
            Map<Long, FinStgRevenueMapping> decisions = decisionsFor(chunk);

            for (FinStgRevenue row : chunk) {
                FinStgRevenueMapping decision = decisions.get(row.getId());
                if (decision == null) {
                    rejections.add(new Rejection(row.getSourceRecordRef(), "NOT_MAPPED",
                            "The record has no mapping decision, so its revenue category is "
                                    + "unknown. Run mapping before normalization.",
                            row.getRawJson()));
                    continue;
                }
                RevenueNormalizer.Outcome outcome = normalizer.normalize(
                        StagedPayload.read(objectMapper, row.getRawJson()),
                        decision.getOutcome(),
                        decision.getCanonicalValue());
                if (!outcome.normalized()) {
                    rejections.add(new Rejection(row.getSourceRecordRef(), outcome.errorCode(),
                            outcome.errorMessage(), row.getRawJson()));
                    continue;
                }
                groups.computeIfAbsent(outcome.row().grain(), key -> new Accumulator(batch))
                        .add(outcome.row(), row);
                normalized++;
            }
            afterId = chunk.get(chunk.size() - 1).getId();
        }

        recordErrors(batch, rejections);

        List<NormalizedFact> facts = groups.values().stream().map(Accumulator::toFact).toList();
        Map<String, Integer> byErrorCode = rejections.stream().collect(Collectors.groupingBy(
                Rejection::errorCode, LinkedHashMap::new, Collectors.summingInt(r -> 1)));

        log.info("[FinanceSync] Batch {} normalized: {} records into {} daily facts, {} rejected",
                syncBatchId, normalized, facts.size(), rejections.size());
        return new Result(syncBatchId, facts, normalized, rejections.size(), byErrorCode);
    }

    /** The staged field naming each canonical field, from the declarations currently in force. */
    private Map<RevenueField, String> declaredFields(Long sourceSystemId) {
        Map<RevenueField, String> declared = new EnumMap<>(RevenueField.class);
        for (RevenueField field : RevenueField.values()) {
            inForce(sourceSystemId, field)
                    .ifPresent(decl -> declared.put(field, decl.getSourceField()));
        }
        return declared;
    }

    private Integer declaredVersion(Long sourceSystemId, RevenueField field) {
        return inForce(sourceSystemId, field).map(FinSourceOfTruthDecl::getVersion).orElse(null);
    }

    private Optional<FinSourceOfTruthDecl> inForce(Long sourceSystemId, RevenueField field) {
        return declarations
                .findFirstBySourceSystemIdAndMetricAndEffectiveToIsNullAndDeletedFalseOrderByVersionDesc(
                        sourceSystemId, field.metric());
    }

    private Map<String, Long> categoryIdsByCode() {
        return categories.findByActiveTrueAndDeletedFalse().stream()
                .collect(Collectors.toMap(FinRevenueCategory::getCategoryCode,
                        FinRevenueCategory::getId, (first, second) -> first, LinkedHashMap::new));
    }

    private Map<Long, FinStgRevenueMapping> decisionsFor(List<FinStgRevenue> chunk) {
        List<Long> ids = chunk.stream().map(FinStgRevenue::getId).toList();
        return mappings.findByStgRevenueIdInAndMappingType(ids, MAPPING_TYPE).stream()
                .collect(Collectors.toMap(FinStgRevenueMapping::getStgRevenueId,
                        Function.identity(), (first, second) -> first));
    }

    /**
     * Replaces this stage's errors for the batch. Scoped to {@link SyncStage#NORMALIZE} so that
     * validation's and mapping's records of the same batch survive untouched — the three stages
     * share one table and count at different grains (FIN-D-032).
     */
    private void recordErrors(FinSyncBatch batch, List<Rejection> rejections) {
        transactionTemplate.execute(tx -> {
            errors.deleteBySyncBatchIdAndErrorStage(batch.getId(), SyncStage.NORMALIZE);
            for (int from = 0; from < rejections.size(); from += CHUNK_SIZE) {
                List<FinSyncError> page = rejections
                        .subList(from, Math.min(from + CHUNK_SIZE, rejections.size())).stream()
                        .map(rejection -> FinSyncError.builder()
                                .syncBatchId(batch.getId())
                                .sourceRecordRef(rejection.sourceRecordRef())
                                .errorStage(SyncStage.NORMALIZE)
                                .errorCode(rejection.errorCode())
                                .errorMessage(rejection.errorMessage())
                                .rawPayloadJson(rejection.rawPayloadJson())
                                .build())
                        .toList();
                errors.saveAll(page);
            }
            return null;
        });
    }

    /** What normalization produced for a batch. No canonical row is written here. */
    public record Result(long syncBatchId,
                         List<NormalizedFact> facts,
                         int recordsNormalized,
                         int recordsRejected,
                         Map<String, Integer> byErrorCode) {
    }

    /**
     * One canonical daily fact, and the staged rows that made it.
     *
     * <p>{@code sourceRecordRef} is carried only where one record produced the fact. A group of
     * several has no single source handle, and inventing one — the first, say — would point an
     * investigator at an arbitrary member and hide the rest. Null there means "several", and
     * {@code stagedRowIds} is how the group is actually traced.
     */
    public record NormalizedFact(Long templeId,
                                 Long sourceSystemId,
                                 Long syncBatchId,
                                 Integer sourceOfTruthVersion,
                                 String sourceRecordRef,
                                 LocalDate transactionDate,
                                 String financialYear,
                                 Long serviceId,
                                 Long categoryId,
                                 PaymentMode paymentMode,
                                 PaymentModeConfidence paymentModeConfidence,
                                 String counterRef,
                                 String operatorRef,
                                 Long transactionCount,
                                 BigDecimal grossAmount,
                                 Long cancelledCount,
                                 BigDecimal cancelledAmount,
                                 BigDecimal quantity,
                                 List<Long> stagedRowIds) {
    }

    private record Rejection(String sourceRecordRef, String errorCode, String errorMessage,
                             String rawPayloadJson) {
    }

    /**
     * Sums the records sharing one grain key.
     *
     * <p>Unknown plus known is unknown. If any contributing record leaves a measure null, the
     * group's total for that measure is null rather than the sum of the ones that were present:
     * a partial sum looks like a complete figure and would understate revenue with nothing to
     * show for it. Gross amount cannot reach this state — it is required, and a record without
     * one is rejected rather than contributing.
     */
    private static final class Accumulator {
        private final FinSyncBatch batch;
        private final List<Long> stagedRowIds = new ArrayList<>();
        private RevenueNormalizer.Row first;
        private String singleSourceRecordRef;
        private BigDecimal grossAmount = BigDecimal.ZERO;
        private BigDecimal cancelledAmount;
        private BigDecimal quantity;
        private Long transactionCount;
        private Long cancelledCount;
        private boolean cancelledAmountUnknown;
        private boolean quantityUnknown;
        private boolean transactionCountUnknown;
        private boolean cancelledCountUnknown;

        private Accumulator(FinSyncBatch batch) {
            this.batch = batch;
        }

        private void add(RevenueNormalizer.Row row, FinStgRevenue staged) {
            if (first == null) {
                first = row;
                singleSourceRecordRef = staged.getSourceRecordRef();
            } else {
                singleSourceRecordRef = null;
            }
            stagedRowIds.add(staged.getId());

            grossAmount = grossAmount.add(row.grossAmount());

            if (row.cancelledAmount() == null) {
                cancelledAmountUnknown = true;
            } else {
                cancelledAmount = cancelledAmount == null
                        ? row.cancelledAmount() : cancelledAmount.add(row.cancelledAmount());
            }
            if (row.quantity() == null) {
                quantityUnknown = true;
            } else {
                quantity = quantity == null ? row.quantity() : quantity.add(row.quantity());
            }
            if (row.transactionCount() == null) {
                transactionCountUnknown = true;
            } else {
                transactionCount = transactionCount == null
                        ? row.transactionCount() : transactionCount + row.transactionCount();
            }
            if (row.cancelledCount() == null) {
                cancelledCountUnknown = true;
            } else {
                cancelledCount = cancelledCount == null
                        ? row.cancelledCount() : cancelledCount + row.cancelledCount();
            }
        }

        private NormalizedFact toFact() {
            return new NormalizedFact(
                    batch.getTempleId(),
                    batch.getSourceSystemId(),
                    batch.getId(),
                    first.sourceOfTruthVersion(),
                    singleSourceRecordRef,
                    first.transactionDate(),
                    first.financialYear(),
                    first.serviceId(),
                    first.categoryId(),
                    first.paymentMode(),
                    first.paymentModeConfidence(),
                    first.counterRef(),
                    first.operatorRef(),
                    transactionCountUnknown ? null : transactionCount,
                    grossAmount,
                    cancelledCountUnknown ? null : cancelledCount,
                    cancelledAmountUnknown ? null : cancelledAmount,
                    quantityUnknown ? null : quantity,
                    List.copyOf(stagedRowIds));
        }
    }
}

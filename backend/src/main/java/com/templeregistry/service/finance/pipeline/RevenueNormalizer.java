package com.templeregistry.service.finance.pipeline;

import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one mapped staged record into the canonical daily shape (FIN-055).
 *
 * <p>Pure: no Spring, no repository, no clock, no database. Everything it needs — which staged
 * field carries which canonical value, and which category codes exist — is handed to the
 * constructor, so every branch below is reachable from a plain unit test and the same input
 * always produces the same output.
 *
 * <h2>What it refuses to do</h2>
 *
 * <ul>
 *   <li><b>Never substitutes zero for a missing amount.</b> An absent or unparseable amount is
 *       a rejection with a reason. Zero asserts that something was measured and found to be
 *       nothing, which is a different and much stronger claim (ADR-007).
 *   <li><b>Never guesses a date format.</b> ISO, and ISO with a time part, are accepted because
 *       both are unambiguous. {@code 03/04/2025} is not: it is 3 April or 4 March depending on
 *       a convention nobody declared, and picking one would silently move revenue between
 *       months and, in the first week of April, between financial years.
 *   <li><b>Never rounds money.</b> An amount carrying more precision than the canonical column
 *       holds is rejected, not rounded. Rounding here would be a silent write-down repeated
 *       across every affected row, and the difference would surface as a reconciliation failure
 *       nobody could explain from the fact table.
 *   <li><b>Never assumes a payment mode.</b> Undeclared means {@link PaymentMode#UNRECORDED},
 *       which is what the source actually said. Cash is the tempting default and would be a
 *       fabrication.
 *   <li><b>Never invents a category.</b> Only a mapping decision that reached
 *       {@link MappingOutcome#MAPPED} or {@link MappingOutcome#UNMAPPED} carries one; the
 *       other three outcomes are undecided by design (FIN-D-031) and are refused here rather
 *       than resolved.
 * </ul>
 *
 * <h2>What it does not own</h2>
 *
 * <p>The collapse of several records onto one daily fact, which needs the whole batch and
 * belongs to {@link RevenueNormalizationStage}. Writing anything, which is FIN-056's.
 * {@code service_id} is always null: canonical service identity needs {@code fin_service_dim}
 * rows and a {@code SERVICE} mapping type, neither of which exists yet, and a fact with a
 * guessed service is worse than one with none.
 */
public final class RevenueNormalizer {

    /** Scale of {@code fin_revenue_fact} money columns; more precision than this is refused. */
    static final int MONEY_SCALE = 2;

    /** Scale of {@code fin_revenue_fact.quantity}. */
    static final int QUANTITY_SCALE = 3;

    private final Map<RevenueField, String> declaredFields;
    private final Integer sourceOfTruthVersion;
    private final Map<String, Long> categoryIdsByCode;
    private final List<String> configurationProblems = new ArrayList<>();

    /**
     * @param declaredFields       canonical field to the staged key naming it, from the
     *                             source-of-truth declarations in force
     * @param sourceOfTruthVersion version of the amount declaration, stamped on every fact so a
     *                             restatement can be explained later (ADR-008)
     * @param categoryIdsByCode    the canonical taxonomy: code to {@code fin_revenue_category.id}
     */
    public RevenueNormalizer(Map<RevenueField, String> declaredFields,
                             Integer sourceOfTruthVersion,
                             Map<String, Long> categoryIdsByCode) {
        this.declaredFields = new EnumMap<>(Objects.requireNonNull(declaredFields));
        this.sourceOfTruthVersion = sourceOfTruthVersion;
        this.categoryIdsByCode = Map.copyOf(Objects.requireNonNull(categoryIdsByCode));

        for (RevenueField field : RevenueField.values()) {
            String staged = this.declaredFields.get(field);
            if (field.required() && (staged == null || staged.isBlank())) {
                configurationProblems.add(
                        "No source-of-truth declaration in force for metric " + field.metric()
                                + ", which names the staged field carrying " + field
                                + ". Normalization cannot proceed without it.");
            } else if (staged != null && staged.isBlank()) {
                configurationProblems.add(
                        "The declaration for metric " + field.metric()
                                + " names a blank source field, so nothing can be read for " + field);
            }
        }
    }

    /**
     * Configuration faults that stop this source being normalized at all. Reported rather than
     * thrown so a caller can record them against the batch before failing.
     */
    public List<String> configurationProblems() {
        return List.copyOf(configurationProblems);
    }

    /** Whether the declarations in force are sufficient to normalize anything. */
    public boolean isUsable() {
        return configurationProblems.isEmpty();
    }

    /**
     * Normalizes one staged record.
     *
     * @param stagedFields    the record's flattened payload
     * @param mappingOutcome  what mapping decided for its revenue category
     * @param canonicalCategory the canonical category code the decision carried, if any
     */
    public Outcome normalize(Map<String, String> stagedFields,
                             MappingOutcome mappingOutcome,
                             String canonicalCategory) {
        if (!isUsable()) {
            throw new IllegalStateException(
                    "Normalizer is not usable: " + String.join("; ", configurationProblems));
        }
        if (mappingOutcome == null || !mappingOutcome.isDecided()) {
            return rejected("UNDECIDED_MAPPING",
                    "Mapping did not decide a revenue category for this record (outcome "
                            + mappingOutcome + "), and normalization does not choose one.");
        }
        Long categoryId = categoryIdsByCode.get(canonicalCategory);
        if (categoryId == null) {
            return rejected("UNKNOWN_CANONICAL_CATEGORY",
                    "Mapping produced category '" + canonicalCategory
                            + "', which is not in the canonical taxonomy.");
        }

        LocalDate transactionDate;
        try {
            String raw = required(stagedFields, RevenueField.TRANSACTION_DATE);
            transactionDate = parseDate(raw);
        } catch (FieldProblem problem) {
            return rejected(problem.code, problem.getMessage());
        }

        BigDecimal grossAmount;
        BigDecimal cancelledAmount;
        BigDecimal quantity;
        Long cancelledCount;
        Long transactionCount;
        try {
            grossAmount = parseDecimal(required(stagedFields, RevenueField.GROSS_AMOUNT),
                    RevenueField.GROSS_AMOUNT, MONEY_SCALE);
            cancelledAmount = optionalDecimal(stagedFields, RevenueField.CANCELLED_AMOUNT, MONEY_SCALE);
            quantity = optionalDecimal(stagedFields, RevenueField.QUANTITY, QUANTITY_SCALE);
            cancelledCount = optionalCount(stagedFields, RevenueField.CANCELLED_COUNT);
            transactionCount = optionalCount(stagedFields, RevenueField.TRANSACTION_COUNT);
        } catch (FieldProblem problem) {
            return rejected(problem.code, problem.getMessage());
        }

        Row row = new Row(
                transactionDate,
                FinancialYear.of(transactionDate),
                categoryId,
                null,
                // The source does not state a payment mode until a PAYMENT_MODE mapping exists.
                // UNRECORDED is that fact, not an inference, so the confidence stays RECORDED.
                PaymentMode.UNRECORDED,
                PaymentModeConfidence.RECORDED,
                optionalText(stagedFields, RevenueField.COUNTER_REF),
                optionalText(stagedFields, RevenueField.OPERATOR_REF),
                transactionCount,
                grossAmount,
                cancelledCount,
                cancelledAmount,
                quantity,
                sourceOfTruthVersion);
        return new Outcome(true, row, null, null);
    }

    private String required(Map<String, String> stagedFields, RevenueField field) {
        String key = declaredFields.get(field);
        if (!stagedFields.containsKey(key)) {
            throw new FieldProblem("MISSING_" + field,
                    "The payload has no field '" + key + "', which the declaration for "
                            + field.metric() + " names as authoritative. A connector that stopped "
                            + "emitting it would otherwise be invisible.");
        }
        String value = stagedFields.get(key);
        if (value == null || value.isBlank()) {
            throw new FieldProblem("EMPTY_" + field,
                    "Field '" + key + "' is present but empty, and " + field
                            + " cannot be assumed.");
        }
        return value.trim();
    }

    private LocalDate parseDate(String raw) {
        // A trailing time part is normal for a JDBC DATETIME rendered as text; the date half is
        // still unambiguous, so it is taken rather than rejected.
        String datePart = raw.length() > 10 && (raw.charAt(10) == ' ' || raw.charAt(10) == 'T')
                ? raw.substring(0, 10)
                : raw;
        try {
            return LocalDate.parse(datePart);
        } catch (DateTimeParseException notIso) {
            throw new FieldProblem("UNPARSEABLE_TRANSACTION_DATE",
                    "Business date '" + raw + "' is not an unambiguous ISO date (yyyy-MM-dd). "
                            + "Ambiguous forms are refused rather than guessed: the wrong reading "
                            + "moves revenue between months, and in early April between financial "
                            + "years.");
        }
    }

    private BigDecimal optionalDecimal(Map<String, String> stagedFields, RevenueField field, int scale) {
        String key = declaredFields.get(field);
        if (key == null) {
            return null;
        }
        String value = stagedFields.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseDecimal(value.trim(), field, scale);
    }

    private BigDecimal parseDecimal(String raw, RevenueField field, int scale) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw);
        } catch (NumberFormatException notANumber) {
            throw new FieldProblem("UNPARSEABLE_" + field,
                    "Value '" + raw + "' for " + field + " is not a number. It is rejected, "
                            + "never read as zero.");
        }
        if (parsed.scale() > scale) {
            throw new FieldProblem("PRECISION_LOSS_" + field,
                    "Value '" + raw + "' for " + field + " carries more precision than the "
                            + "canonical column holds (" + scale + " places). Rounding it would be "
                            + "a silent write-down, so it is rejected for a decision.");
        }
        return parsed.setScale(scale, java.math.RoundingMode.UNNECESSARY);
    }

    private Long optionalCount(Map<String, String> stagedFields, RevenueField field) {
        String key = declaredFields.get(field);
        if (key == null) {
            return null;
        }
        String value = stagedFields.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException notACount) {
            throw new FieldProblem("UNPARSEABLE_" + field,
                    "Value '" + value.trim() + "' for " + field + " is not a whole number.");
        }
    }

    private String optionalText(Map<String, String> stagedFields, RevenueField field) {
        String key = declaredFields.get(field);
        if (key == null) {
            return null;
        }
        String value = stagedFields.get(key);
        // Blank collapses to null so that "" and absent do not split one day's fact in two
        // through the grain key.
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Outcome rejected(String code, String message) {
        return new Outcome(false, null, code, message);
    }

    /** Either a canonical row, or a coded reason there is none. Never both, never neither. */
    public record Outcome(boolean normalized, Row row, String errorCode, String errorMessage) {
    }

    /**
     * One record in canonical shape, before the collapse to daily grain.
     *
     * <p>Every measure is nullable and none of them is defaulted: absence is carried through as
     * absence so that a report can say "this source does not record cancellations" rather than
     * "nothing was cancelled".
     */
    public record Row(LocalDate transactionDate,
                      String financialYear,
                      Long categoryId,
                      Long serviceId,
                      PaymentMode paymentMode,
                      PaymentModeConfidence paymentModeConfidence,
                      String counterRef,
                      String operatorRef,
                      Long transactionCount,
                      BigDecimal grossAmount,
                      Long cancelledCount,
                      BigDecimal cancelledAmount,
                      BigDecimal quantity,
                      Integer sourceOfTruthVersion) {

        /**
         * The canonical grain of {@code uk_frf_grain}, minus the temple and the source system,
         * both of which the batch fixes.
         */
        public GrainKey grain() {
            return new GrainKey(transactionDate, serviceId, categoryId, paymentMode,
                    counterRef, operatorRef);
        }
    }

    /**
     * The columns {@code uk_frf_grain} is unique over. Records sharing one become a single fact.
     *
     * <p>It is a record, so equality is by value and null is a legitimate part of the key here —
     * unlike in the database, where NULL-distinct index semantics forced the generated
     * {@code grain_*} columns (FIN-D-018). The two must agree: anything added to one belongs in
     * the other, or the same fact lands twice.
     *
     * <p>Two of the constraint's eight columns are absent here and correctly so: normalization
     * runs over one batch, and a batch has exactly one temple and exactly one source system, so
     * both are constant across every key this class builds. {@code source_system_id} joining the
     * constraint in V118 (FIN-052A) therefore needed no change here — but a future change that
     * let one normalization run span batches would break that assumption and would have to add
     * both.
     */
    public record GrainKey(LocalDate transactionDate,
                           Long serviceId,
                           Long categoryId,
                           PaymentMode paymentMode,
                           String counterRef,
                           String operatorRef) {
    }

    /** Internal control flow for a field that cannot be read. Never escapes this class. */
    private static final class FieldProblem extends RuntimeException {
        private final String code;

        private FieldProblem(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}

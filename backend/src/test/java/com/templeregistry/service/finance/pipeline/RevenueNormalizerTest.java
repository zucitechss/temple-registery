package com.templeregistry.service.finance.pipeline;

import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-055. What normalization produces, and what it refuses to produce.
 *
 * <p>No database and no Spring: turning one record into canonical shape is a pure function of
 * the declarations, the record and the mapping decision. Almost every test here is about a
 * refusal, because this is the first stage that touches money and a date, and every plausible
 * shortcut at this point — zero for a missing amount, a guessed date format, cash for an
 * unstated payment mode — produces a figure that looks correct and is not.
 *
 * <p>The grouping, the database and the error records are in
 * {@link RevenueNormalizationStageTest}.
 */
class RevenueNormalizerTest {

    private static final Map<String, Long> CATEGORIES = Map.of(
            "SEVA", 1L, "DONATION", 3L, "HUNDI_DONATION", 4L, "UNMAPPED", 99L);

    // ---------------------------------------------------------------- the ordinary case

    @Test
    @DisplayName("A declared date and amount produce a canonical row")
    void should_normalize_when_declaredFieldsArePresent() {
        RevenueNormalizer normalizer = normalizer();

        RevenueNormalizer.Outcome outcome = normalizer.normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "1500.50"),
                MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.normalized()).isTrue();
        assertThat(outcome.errorCode()).isNull();
        RevenueNormalizer.Row row = outcome.row();
        assertThat(row.transactionDate()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(row.financialYear()).isEqualTo("2025-26");
        assertThat(row.categoryId()).isEqualTo(1L);
        assertThat(row.grossAmount()).isEqualByComparingTo("1500.50");
    }

    @Test
    @DisplayName("The financial year comes from the business date, not from today")
    void should_deriveFinancialYear_when_normalizing() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2019-04-01", "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA");

        // A source editing a six-year-old receipt today corrects an old day; it does not move
        // money into the current year (FIN-D-012).
        assertThat(outcome.row().financialYear()).isEqualTo("2019-20");
    }

    @Test
    @DisplayName("An amount is an exact decimal, never a double")
    void should_keepExactPrecision_when_parsingAnAmount() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "906162936.07"),
                MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.row().grossAmount()).isEqualTo(new BigDecimal("906162936.07"));
        assertThat(outcome.row().grossAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("A whole-rupee amount is scaled without inventing precision")
    void should_scaleToTwoPlaces_when_amountHasNone() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "1500"),
                MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.row().grossAmount()).isEqualByComparingTo("1500.00");
    }

    // ---------------------------------------------------------------- dates

    @Test
    @DisplayName("A date carrying a time part keeps its date half")
    void should_acceptIsoDateTime_when_sourceRendersATimestamp() {
        // A JDBC DATETIME stringified is the normal shape here, and its date half is still
        // unambiguous, so rejecting it would fail whole batches for nothing.
        assertThat(dateOf("2025-06-15 14:32:07")).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(dateOf("2025-06-15T14:32:07")).isEqualTo(LocalDate.of(2025, 6, 15));
    }

    @ParameterizedTest(name = "{0} is refused")
    @DisplayName("An ambiguous date is refused rather than guessed")
    @ValueSource(strings = {"03/04/2025", "04-03-2025", "15-06-2025", "June 15 2025", "20250615"})
    void should_refuse_when_dateFormatIsAmbiguous(String ambiguous) {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", ambiguous, "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA");

        // 03/04/2025 is 3 April or 4 March depending on a convention nobody declared. Guessing
        // moves revenue between months, and in the first week of April between financial years.
        assertThat(outcome.normalized()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("UNPARSEABLE_TRANSACTION_DATE");
        assertThat(outcome.row()).isNull();
    }

    @Test
    @DisplayName("A payload missing the declared date field is refused, not dated from the batch")
    void should_refuse_when_dateFieldIsAbsent() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("Amount", "10.00"), MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.normalized()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("MISSING_TRANSACTION_DATE");
        assertThat(outcome.errorMessage()).contains("ReceiptDate");
    }

    @Test
    @DisplayName("A present but empty date is refused")
    void should_refuse_when_dateIsBlank() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "   ", "Amount", "10.00"), MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.errorCode()).isEqualTo("EMPTY_TRANSACTION_DATE");
    }

    // ---------------------------------------------------------------- amounts

    @Test
    @DisplayName("A missing amount is never read as zero")
    void should_refuse_when_amountIsAbsent() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15"), MappingOutcome.MAPPED, "SEVA");

        // Zero asserts that something was measured and found to be nothing. A missing amount
        // asserts nothing at all, and the two must not be confused (ADR-007).
        assertThat(outcome.normalized()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("MISSING_GROSS_AMOUNT");
        assertThat(outcome.row()).isNull();
    }

    @Test
    @DisplayName("A blank amount is refused rather than defaulted")
    void should_refuse_when_amountIsBlank() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", ""), MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.errorCode()).isEqualTo("EMPTY_GROSS_AMOUNT");
    }

    @ParameterizedTest(name = "{0} is refused")
    @DisplayName("Text in an amount field is refused")
    @ValueSource(strings = {"NULL", "n/a", "1,500.00", "Rs 1500", "--5"})
    void should_refuse_when_amountIsNotANumber(String notANumber) {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", notANumber),
                MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.normalized()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("UNPARSEABLE_GROSS_AMOUNT");
    }

    @Test
    @DisplayName("An amount too precise for the canonical column is refused, never rounded")
    void should_refuse_when_amountWouldLosePrecision() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "1500.505"),
                MappingOutcome.MAPPED, "SEVA");

        // Rounding here is a silent write-down repeated across every affected row, and the
        // difference resurfaces later as a reconciliation failure nobody can explain.
        assertThat(outcome.normalized()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("PRECISION_LOSS_GROSS_AMOUNT");
    }

    @Test
    @DisplayName("A negative amount is carried, not silently dropped")
    void should_carryNegativeAmount_when_sourceReportsOne() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "-250.00"),
                MappingOutcome.MAPPED, "SEVA");

        // A reversal is a real thing a source can record. Dropping it would overstate revenue,
        // and judging whether it is legitimate belongs to reconciliation, not to a parser.
        assertThat(outcome.normalized()).isTrue();
        assertThat(outcome.row().grossAmount()).isEqualByComparingTo("-250.00");
    }

    // ---------------------------------------------------------------- the mapping decision

    @ParameterizedTest(name = "{0} cannot be normalized")
    @DisplayName("An undecided mapping outcome is refused, not resolved here")
    @EnumSource(value = MappingOutcome.class,
            names = {"AMBIGUOUS", "NOT_APPLICABLE", "INVALID_CONFIGURATION"})
    void should_refuse_when_mappingDidNotDecide(MappingOutcome undecided) {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"), undecided, null);

        // These three carry no canonical value by design (FIN-D-031). Substituting one here
        // would undo the whole point of distinguishing them.
        assertThat(outcome.normalized()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("UNDECIDED_MAPPING");
    }

    @Test
    @DisplayName("An unmapped record still becomes a fact, under the UNMAPPED category")
    void should_normalize_when_categoryIsUnmapped() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "75000.00"),
                MappingOutcome.UNMAPPED, "UNMAPPED");

        // The money is real; only its kind is unknown. Discarding it would understate revenue,
        // and hiding it in OTHER_INCOME would stop anyone noticing the missing rule.
        assertThat(outcome.normalized()).isTrue();
        assertThat(outcome.row().categoryId()).isEqualTo(99L);
        assertThat(outcome.row().grossAmount()).isEqualByComparingTo("75000.00");
    }

    @Test
    @DisplayName("A category outside the canonical taxonomy is refused")
    void should_refuse_when_categoryIsNotCanonical() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                MappingOutcome.MAPPED, "ARCHAKA_FEE");

        assertThat(outcome.normalized()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("UNKNOWN_CANONICAL_CATEGORY");
    }

    // ---------------------------------------------------------------- absence stays absence

    @Test
    @DisplayName("Undeclared measures are null on the row, never zero")
    void should_leaveUndeclaredMeasuresNull_when_sourceDoesNotRecordThem() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA");

        RevenueNormalizer.Row row = outcome.row();
        // NULL cancelled_amount means this source does not record cancellations at all, so net
        // revenue is genuinely unknown. Zero would assert that nothing was cancelled.
        assertThat(row.cancelledAmount()).isNull();
        assertThat(row.cancelledCount()).isNull();
        assertThat(row.transactionCount()).isNull();
        assertThat(row.quantity()).isNull();
        assertThat(row.counterRef()).isNull();
        assertThat(row.operatorRef()).isNull();
    }

    @Test
    @DisplayName("A declared zero is kept as a measured zero")
    void should_keepZero_when_sourceRecordsIt() {
        Map<RevenueField, String> declared = declaredFields();
        declared.put(RevenueField.CANCELLED_AMOUNT, "CancelledAmount");
        declared.put(RevenueField.CANCELLED_COUNT, "CancelledCount");

        RevenueNormalizer.Outcome outcome = new RevenueNormalizer(declared, 1, CATEGORIES)
                .normalize(fields("ReceiptDate", "2025-06-15", "Amount", "10.00",
                        "CancelledAmount", "0", "CancelledCount", "0"),
                        MappingOutcome.MAPPED, "SEVA");

        // Declared-and-zero and undeclared are different answers: this source counts
        // cancellations and there were none (FIN-D-020).
        assertThat(outcome.row().cancelledAmount()).isEqualByComparingTo("0.00");
        assertThat(outcome.row().cancelledCount()).isZero();
    }

    @Test
    @DisplayName("A payment mode nobody recorded is UNRECORDED, never CASH")
    void should_reportUnrecorded_when_noPaymentModeIsKnown() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA");

        assertThat(outcome.row().paymentMode()).isEqualTo(PaymentMode.UNRECORDED);
        // UNRECORDED is what the source said, not something derived from other evidence.
        assertThat(outcome.row().paymentModeConfidence()).isEqualTo(PaymentModeConfidence.RECORDED);
    }

    @Test
    @DisplayName("A service is never guessed")
    void should_leaveServiceNull_when_noServiceDimensionExists() {
        RevenueNormalizer.Outcome outcome = normalizer().normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA");

        // Canonical service identity needs fin_service_dim rows and a SERVICE mapping type,
        // neither of which exists. A fact with a guessed service is worse than one with none.
        assertThat(outcome.row().serviceId()).isNull();
    }

    @Test
    @DisplayName("A blank grain field collapses to null so one day does not split into two facts")
    void should_collapseBlankToNull_when_grainFieldIsEmpty() {
        Map<RevenueField, String> declared = declaredFields();
        declared.put(RevenueField.COUNTER_REF, "Counter");

        RevenueNormalizer normalizer = new RevenueNormalizer(declared, 1, CATEGORIES);
        RevenueNormalizer.Row blank = normalizer.normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00", "Counter", "  "),
                MappingOutcome.MAPPED, "SEVA").row();
        RevenueNormalizer.Row absent = normalizer.normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA").row();

        assertThat(blank.counterRef()).isNull();
        // Otherwise "" and absent would be two grain keys, two facts and one day's revenue
        // reported twice over.
        assertThat(blank.grain()).isEqualTo(absent.grain());
    }

    @Test
    @DisplayName("The grain key is the six columns the unique constraint is built on")
    void should_groupOnTheCanonicalGrain_when_keying() {
        RevenueNormalizer normalizer = normalizer();
        RevenueNormalizer.Row cheap = normalizer.normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA").row();
        RevenueNormalizer.Row dear = normalizer.normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "99999.00"),
                MappingOutcome.MAPPED, "SEVA").row();
        RevenueNormalizer.Row otherDay = normalizer.normalize(
                fields("ReceiptDate", "2025-06-16", "Amount", "10.00"),
                MappingOutcome.MAPPED, "SEVA").row();
        RevenueNormalizer.Row otherCategory = normalizer.normalize(
                fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                MappingOutcome.MAPPED, "DONATION").row();

        // The amount is a measure, not part of the grain: two records on the same day in the
        // same category are one fact whose amounts add up.
        assertThat(cheap.grain()).isEqualTo(dear.grain());
        assertThat(cheap.grain()).isNotEqualTo(otherDay.grain());
        assertThat(cheap.grain()).isNotEqualTo(otherCategory.grain());
    }

    // ---------------------------------------------------------------- configuration

    @Test
    @DisplayName("Without a declaration for the amount, nothing can be normalized")
    void should_beUnusable_when_aRequiredDeclarationIsMissing() {
        Map<RevenueField, String> declared = new EnumMap<>(RevenueField.class);
        declared.put(RevenueField.TRANSACTION_DATE, "ReceiptDate");

        RevenueNormalizer normalizer = new RevenueNormalizer(declared, null, CATEGORIES);

        assertThat(normalizer.isUsable()).isFalse();
        assertThat(normalizer.configurationProblems())
                .singleElement().asString().contains("REVENUE_AMOUNT");
    }

    @Test
    @DisplayName("An unusable normalizer refuses to run rather than producing a guess")
    void should_refuseToRun_when_unusable() {
        RevenueNormalizer normalizer =
                new RevenueNormalizer(new EnumMap<>(RevenueField.class), null, CATEGORIES);

        assertThatThrownBy(() -> normalizer.normalize(
                fields("Amount", "10.00"), MappingOutcome.MAPPED, "SEVA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not usable");
    }

    @Test
    @DisplayName("The declaration version in force is stamped on the row")
    void should_carryDeclarationVersion_when_normalizing() {
        RevenueNormalizer.Outcome outcome = new RevenueNormalizer(declaredFields(), 7, CATEGORIES)
                .normalize(fields("ReceiptDate", "2025-06-15", "Amount", "10.00"),
                        MappingOutcome.MAPPED, "SEVA");

        // Which declaration was in force is what lets a restatement be explained later (ADR-008).
        assertThat(outcome.row().sourceOfTruthVersion()).isEqualTo(7);
    }

    @Test
    @DisplayName("Normalization is deterministic")
    void should_beDeterministic_when_runTwice() {
        RevenueNormalizer normalizer = normalizer();
        Map<String, String> payload = fields("ReceiptDate", "2025-06-15", "Amount", "1500.50");

        assertThat(normalizer.normalize(payload, MappingOutcome.MAPPED, "SEVA"))
                .isEqualTo(normalizer.normalize(payload, MappingOutcome.MAPPED, "SEVA"));
    }

    // ---------------------------------------------------------------- helpers

    private LocalDate dateOf(String raw) {
        return normalizer()
                .normalize(fields("ReceiptDate", raw, "Amount", "10.00"),
                        MappingOutcome.MAPPED, "SEVA")
                .row().transactionDate();
    }

    private RevenueNormalizer normalizer() {
        return new RevenueNormalizer(declaredFields(), 1, CATEGORIES);
    }

    private Map<RevenueField, String> declaredFields() {
        Map<RevenueField, String> declared = new EnumMap<>(RevenueField.class);
        declared.put(RevenueField.TRANSACTION_DATE, "ReceiptDate");
        declared.put(RevenueField.GROSS_AMOUNT, "Amount");
        return declared;
    }

    /** A staged payload, already flattened. Null values are legitimate and are kept. */
    private Map<String, String> fields(String... keysAndValues) {
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return fields;
    }
}

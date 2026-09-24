package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.service.finance.pipeline.FinancialYear;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-070 aggregation arithmetic, with no database anywhere.
 *
 * <p>That is the design paying off: every rule that could silently produce a wrong financial figure —
 * a floor presented as a total, a null read as a zero, one source's money landing in another's row —
 * is exercised here in milliseconds, and the container tests are left to prove only what a container
 * can prove.
 */
class RevenueAggregatorTest {

    private static final long TEMPLE_A = 300001L;
    private static final long TEMPLE_B = 300002L;
    private static final long SOURCE_A = 9001L;
    private static final long SOURCE_B = 9002L;
    private static final long SEVA = 1L;
    private static final long DONATION = 2L;
    private static final LocalDate JUNE = LocalDate.of(2025, 6, 15);

    // ------------------------------------------------------------------ the ordinary case

    @Test
    @DisplayName("Each fact produces one financial-year row and one month row")
    void should_produceBothPeriods_when_factIsAggregated() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(fact(JUNE, "100.00")));

        assertThat(aggregates).hasSize(2);
        assertThat(aggregates).extracting(RevenueAggregate::periodType, RevenueAggregate::periodKey)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(PeriodType.FINANCIAL_YEAR, "2025-26"),
                        org.assertj.core.groups.Tuple.tuple(PeriodType.MONTH, "2025-06"));
    }

    @Test
    @DisplayName("Facts in one period and grain are summed into one row")
    void should_sumFacts_when_grainMatches() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(
                fact(LocalDate.of(2025, 6, 1), "100.50"),
                fact(LocalDate.of(2025, 6, 2), "200.25"),
                fact(LocalDate.of(2025, 6, 3), "0.25")));

        assertThat(year(aggregates, "2025-26").grossAmount()).isEqualByComparingTo("301.00");
        assertThat(year(aggregates, "2025-26").factCount()).isEqualTo(3);
        assertThat(month(aggregates, "2025-06").grossAmount()).isEqualByComparingTo("301.00");
    }

    @Test
    @DisplayName("Months inside a year sum to the year")
    void should_agreeBetweenGranularities_when_yearSpansMonths() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(
                fact(LocalDate.of(2025, 4, 10), "100.00"),
                fact(LocalDate.of(2025, 9, 10), "250.00"),
                fact(LocalDate.of(2026, 3, 31), "75.50")));

        BigDecimal monthsTotal = aggregates.stream()
                .filter(a -> a.periodType() == PeriodType.MONTH)
                .map(RevenueAggregate::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(year(aggregates, "2025-26").grossAmount())
                .as("a reader who adds the months must get the year, or the table contradicts itself")
                .isEqualByComparingTo(monthsTotal)
                .isEqualByComparingTo("425.50");
    }

    /** 31 March and 1 April are one day apart and in different years. The classic off-by-one. */
    @Test
    @DisplayName("A financial-year boundary splits facts into different years")
    void should_splitYears_when_factsStraddleThirtyFirstOfMarch() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(
                fact(LocalDate.of(2026, 3, 31), "10.00"),
                fact(LocalDate.of(2026, 4, 1), "20.00")));

        assertThat(year(aggregates, "2025-26").grossAmount()).isEqualByComparingTo("10.00");
        assertThat(year(aggregates, "2026-27").grossAmount()).isEqualByComparingTo("20.00");
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("Two source systems never share a row, however identical everything else is")
    void should_isolateSources_when_onlyTheSourceDiffers() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.CASH, JUNE, "100.00"),
                factOf(TEMPLE_A, SOURCE_B, SEVA, PaymentMode.CASH, JUNE, "900.00")));

        List<RevenueAggregate> years = aggregates.stream()
                .filter(a -> a.periodType() == PeriodType.FINANCIAL_YEAR).toList();

        assertThat(years).as("summing across sources would make the gate inapplicable").hasSize(2);
        assertThat(years).extracting(RevenueAggregate::sourceSystemId)
                .containsExactly(SOURCE_A, SOURCE_B);
        assertThat(years.get(0).grossAmount()).isEqualByComparingTo("100.00");
        assertThat(years.get(1).grossAmount()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("Two temples never share a row")
    void should_isolateTemples_when_onlyTheTempleDiffers() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.CASH, JUNE, "111.00"),
                factOf(TEMPLE_B, SOURCE_A, SEVA, PaymentMode.CASH, JUNE, "222.00")));

        assertThat(aggregates.stream().filter(a -> a.templeId() == TEMPLE_A)
                .filter(a -> a.periodType() == PeriodType.FINANCIAL_YEAR).findFirst().orElseThrow()
                .grossAmount()).isEqualByComparingTo("111.00");
        assertThat(aggregates.stream().filter(a -> a.templeId() == TEMPLE_B)
                .filter(a -> a.periodType() == PeriodType.FINANCIAL_YEAR).findFirst().orElseThrow()
                .grossAmount()).isEqualByComparingTo("222.00");
    }

    @Test
    @DisplayName("Categories stay apart, and UNMAPPED is a category like any other")
    void should_separateCategories_when_factsDiffer() {
        long unmapped = 99L;
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.CASH, JUNE, "100.00"),
                factOf(TEMPLE_A, SOURCE_A, DONATION, PaymentMode.CASH, JUNE, "200.00"),
                factOf(TEMPLE_A, SOURCE_A, unmapped, PaymentMode.CASH, JUNE, "300.00")));

        assertThat(aggregates.stream().filter(a -> a.periodType() == PeriodType.FINANCIAL_YEAR))
                .as("folding UNMAPPED into another category would hide revenue whose kind is unknown")
                .hasSize(3)
                .extracting(RevenueAggregate::categoryId)
                .containsExactly(SEVA, DONATION, unmapped);
    }

    @Test
    @DisplayName("Payment modes stay apart, and UNRECORDED is not CASH")
    void should_separatePaymentModes_when_factsDiffer() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.CASH, JUNE, "100.00"),
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.UPI, JUNE, "200.00"),
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.UNRECORDED, JUNE, "300.00")));

        assertThat(aggregates.stream().filter(a -> a.periodType() == PeriodType.FINANCIAL_YEAR))
                .hasSize(3)
                .extracting(RevenueAggregate::paymentMode)
                .containsExactly(PaymentMode.CASH, PaymentMode.UNRECORDED, PaymentMode.UPI);
    }

    // ------------------------------------------------------------------ absence is not zero

    @Test
    @DisplayName("One unknown transaction count makes the period's count unknown, and says how many")
    void should_nullCount_when_anyFactLacksOne() {
        FinRevenueFact counted = fact(JUNE, "100.00");
        counted.setTransactionCount(40L);
        FinRevenueFact uncounted = fact(JUNE.plusDays(1), "100.00");
        uncounted.setTransactionCount(null);

        RevenueAggregate aggregate = year(RevenueAggregator.aggregate(List.of(counted, uncounted)), "2025-26");

        assertThat(aggregate.transactionCount())
                .as("a floor compared against a source's total produces a false shortfall")
                .isNull();
        assertThat(aggregate.factsWithUnknownCount()).isEqualTo(1);
        assertThat(aggregate.factCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A count is reported only when every contributing fact had one")
    void should_sumCount_when_everyFactHasOne() {
        FinRevenueFact first = fact(JUNE, "100.00");
        first.setTransactionCount(40L);
        FinRevenueFact second = fact(JUNE.plusDays(1), "100.00");
        second.setTransactionCount(2L);

        RevenueAggregate aggregate = year(RevenueAggregator.aggregate(List.of(first, second)), "2025-26");

        assertThat(aggregate.transactionCount()).isEqualTo(42L);
        assertThat(aggregate.factsWithUnknownCount()).isZero();
    }

    /**
     * Gross is treated the opposite way from the count, and deliberately: nulling a period because
     * one fact of many lacked an amount would hide real revenue.
     */
    @Test
    @DisplayName("An unknown gross makes the sum a floor, and the row says by how many facts")
    void should_sumKnownGross_when_someFactsLackIt() {
        FinRevenueFact known = fact(JUNE, "100.00");
        FinRevenueFact unknown = fact(JUNE.plusDays(1), null);

        RevenueAggregate aggregate = year(RevenueAggregator.aggregate(List.of(known, unknown)), "2025-26");

        assertThat(aggregate.grossAmount()).isEqualByComparingTo("100.00");
        assertThat(aggregate.factsWithUnknownGross()).isEqualTo(1);
    }

    @Test
    @DisplayName("Gross is unknown only when no contributing fact recorded any")
    void should_nullGross_when_noFactRecordedIt() {
        RevenueAggregate aggregate = year(RevenueAggregator.aggregate(
                List.of(fact(JUNE, null), fact(JUNE.plusDays(1), null))), "2025-26");

        assertThat(aggregate.grossAmount()).as("zero would assert the temple took nothing").isNull();
        assertThat(aggregate.factsWithUnknownGross()).isEqualTo(2);
    }

    @Test
    @DisplayName("Unrecorded cancellations leave the period's cancellations unknown, never zero")
    void should_nullCancellations_when_sourceDoesNotRecordThem() {
        RevenueAggregate aggregate = year(RevenueAggregator.aggregate(
                List.of(fact(JUNE, "100.00"))), "2025-26");

        assertThat(aggregate.cancelledAmount())
                .as("zero would assert that nothing was cancelled, which nobody measured")
                .isNull();
        assertThat(aggregate.cancelledCount()).isNull();
    }

    @Test
    @DisplayName("One fact without cancellations makes the whole period's cancellations unknown")
    void should_nullCancellations_when_onlySomeFactsRecordThem() {
        FinRevenueFact records = fact(JUNE, "100.00");
        records.setCancelledAmount(new BigDecimal("5.00"));
        records.setCancelledCount(1L);
        FinRevenueFact silent = fact(JUNE.plusDays(1), "100.00");

        RevenueAggregate aggregate = year(RevenueAggregator.aggregate(List.of(records, silent)), "2025-26");

        assertThat(aggregate.cancelledAmount())
                .as("a partial cancellation total understates cancellations and overstates net")
                .isNull();
        assertThat(aggregate.cancelledCount()).isNull();
    }

    @Test
    @DisplayName("Cancellations are summed when every fact records them, including a measured zero")
    void should_sumCancellations_when_everyFactRecordsThem() {
        FinRevenueFact some = fact(JUNE, "100.00");
        some.setCancelledAmount(new BigDecimal("5.00"));
        some.setCancelledCount(1L);
        FinRevenueFact none = fact(JUNE.plusDays(1), "100.00");
        none.setCancelledAmount(BigDecimal.ZERO);
        none.setCancelledCount(0L);

        RevenueAggregate aggregate = year(RevenueAggregator.aggregate(List.of(some, none)), "2025-26");

        assertThat(aggregate.cancelledAmount())
                .as("a measured zero is data, unlike a NULL")
                .isEqualByComparingTo("5.00");
        assertThat(aggregate.cancelledCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Inferred payment modes are counted as a flag, not split into their own row")
    void should_countInferredFacts_when_modeWasDerived() {
        FinRevenueFact recorded = fact(JUNE, "100.00");
        FinRevenueFact inferred = fact(JUNE.plusDays(1), "100.00");
        inferred.setPaymentModeConfidence(PaymentModeConfidence.INFERRED);

        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(List.of(recorded, inferred));
        RevenueAggregate aggregate = year(aggregates, "2025-26");

        assertThat(aggregates.stream().filter(a -> a.periodType() == PeriodType.FINANCIAL_YEAR))
                .as("confidence is not in the fact grain, so it must not split an aggregate either")
                .hasSize(1);
        assertThat(aggregate.paymentModeInferredFacts()).isEqualTo(1);
        assertThat(aggregate.grossAmount()).isEqualByComparingTo("200.00");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("A group spanning two currencies is refused, never summed")
    void should_refuse_when_currenciesDiffer() {
        FinRevenueFact rupees = fact(JUNE, "100.00");
        FinRevenueFact dollars = fact(JUNE.plusDays(1), "100.00");
        dollars.setCurrency("USD");

        assertThatThrownBy(() -> RevenueAggregator.aggregate(List.of(rupees, dollars)))
                .isInstanceOf(RevenueAggregator.AggregationRefusedException.class)
                .hasMessageContaining("INR")
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("A fact that disagrees with itself about its financial year is refused")
    void should_refuse_when_storedFinancialYearContradictsTheDate() {
        FinRevenueFact wrong = fact(LocalDate.of(2025, 6, 15), "100.00");
        wrong.setFinancialYear("2024-25");

        assertThatThrownBy(() -> RevenueAggregator.aggregate(List.of(wrong)))
                .as("aggregation is the first place the two columns are read together")
                .isInstanceOf(RevenueAggregator.AggregationRefusedException.class)
                .hasMessageContaining("2025-26")
                .hasMessageContaining("2024-25");
    }

    // ------------------------------------------------------------------ determinism

    @Test
    @DisplayName("Empty input produces no rows, not a row of zeroes")
    void should_produceNothing_when_thereAreNoFacts() {
        assertThat(RevenueAggregator.aggregate(List.of())).isEmpty();
        assertThat(RevenueAggregator.aggregate(null)).isEmpty();
    }

    @Test
    @DisplayName("The same facts in a different order produce identical output, in identical order")
    void should_beDeterministic_when_inputOrderChanges() {
        List<FinRevenueFact> facts = List.of(
                factOf(TEMPLE_B, SOURCE_B, DONATION, PaymentMode.UPI, JUNE, "5.00"),
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.CASH, JUNE, "1.00"),
                factOf(TEMPLE_A, SOURCE_B, SEVA, PaymentMode.CASH, JUNE, "2.00"),
                factOf(TEMPLE_A, SOURCE_A, DONATION, PaymentMode.CASH, JUNE, "3.00"),
                factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.UPI, JUNE, "4.00"));

        List<RevenueAggregate> first = RevenueAggregator.aggregate(facts);
        List<RevenueAggregate> reversed = RevenueAggregator.aggregate(facts.reversed());

        assertThat(reversed)
                .as("a rebuild that depends on row order is not a deterministic rebuild")
                .isEqualTo(first);
        assertThat(first).extracting(RevenueAggregate::templeId)
                .as("and the order is stable, not merely equal")
                .isSorted();
    }

    @Test
    @DisplayName("Running twice over the same facts changes nothing")
    void should_beIdempotent_when_runRepeatedly() {
        List<FinRevenueFact> facts = List.of(fact(JUNE, "100.00"), fact(JUNE.plusDays(1), "50.00"));

        assertThat(RevenueAggregator.aggregate(facts)).isEqualTo(RevenueAggregator.aggregate(facts));
    }

    /**
     * The arithmetic is BigDecimal end to end. A double would have turned this into 0.30000000000000004
     * and nobody would have noticed until a reconciliation failed by a paisa.
     */
    @Test
    @DisplayName("Money is exact, and stays exact across many small amounts")
    void should_keepMonetaryPrecision_when_summingManyFacts() {
        List<FinRevenueFact> facts = IntStream.range(0, 3)
                .mapToObj(i -> fact(JUNE.plusDays(i), "0.10"))
                .toList();

        assertThat(year(RevenueAggregator.aggregate(facts), "2025-26").grossAmount())
                .isEqualByComparingTo("0.30");

        List<FinRevenueFact> many = IntStream.range(0, 10_000)
                .mapToObj(i -> fact(JUNE, "0.01"))
                .toList();

        assertThat(year(RevenueAggregator.aggregate(many), "2025-26").grossAmount())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Every aggregate carries a non-null category and a resolved parent financial year")
    void should_carryCompleteKey_when_aggregating() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(
                List.of(fact(LocalDate.of(2026, 1, 20), "100.00")));

        assertThat(aggregates).allSatisfy(aggregate -> {
            assertThat(aggregate.categoryId()).isNotNull();
            assertThat(aggregate.currency()).isEqualTo("INR");
            assertThat(aggregate.financialYear())
                    .as("January 2026 belongs to FY2025-26, month row included")
                    .isEqualTo("2025-26");
        });
        assertThat(month(aggregates, "2026-01").reconciliationInherited())
                .as("no reconciliation result exists at month scope, so a month never claims its own")
                .isTrue();
        assertThat(year(aggregates, "2025-26").reconciliationInherited()).isFalse();
    }

    @Test
    @DisplayName("Period bounds match the period the row claims to cover")
    void should_carryPeriodBounds_when_aggregating() {
        List<RevenueAggregate> aggregates = RevenueAggregator.aggregate(
                List.of(fact(LocalDate.of(2025, 6, 15), "100.00")));

        assertThat(year(aggregates, "2025-26").periodStart()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(year(aggregates, "2025-26").periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(month(aggregates, "2025-06").periodStart()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(month(aggregates, "2025-06").periodEnd()).isEqualTo(LocalDate.of(2025, 6, 30));
    }

    // ------------------------------------------------------------------ helpers

    private static RevenueAggregate year(List<RevenueAggregate> aggregates, String key) {
        return pick(aggregates, PeriodType.FINANCIAL_YEAR, key);
    }

    private static RevenueAggregate month(List<RevenueAggregate> aggregates, String key) {
        return pick(aggregates, PeriodType.MONTH, key);
    }

    private static RevenueAggregate pick(List<RevenueAggregate> aggregates, PeriodType type, String key) {
        return aggregates.stream()
                .filter(a -> a.periodType() == type && a.periodKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " aggregate for " + key
                        + " in " + aggregates));
    }

    private static FinRevenueFact fact(LocalDate date, String gross) {
        return factOf(TEMPLE_A, SOURCE_A, SEVA, PaymentMode.CASH, date, gross);
    }

    private static FinRevenueFact factOf(long templeId, long sourceSystemId, long categoryId,
                                         PaymentMode paymentMode, LocalDate date, String gross) {
        return FinRevenueFact.builder()
                .id(1L)
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .syncBatchId(1L)
                .transactionDate(date)
                .financialYear(FinancialYear.of(date))
                .categoryId(categoryId)
                .paymentMode(paymentMode)
                .grossAmount(gross == null ? null : new BigDecimal(gross))
                .build();
    }
}

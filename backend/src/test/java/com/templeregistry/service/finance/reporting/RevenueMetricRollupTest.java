package com.templeregistry.service.finance.reporting;

import com.templeregistry.entity.finance.FinAggRevenuePeriod;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-081 — the reporting-layer sum, and every null-propagation rule it must not get wrong.
 */
@DisplayName("RevenueMetricRollup")
class RevenueMetricRollupTest {

    private static final long TEMPLE = 1L;
    private static final long SOURCE = 2L;
    private static final long CATEGORY_A = 10L;
    private static final long CATEGORY_B = 20L;

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("refuses a null list")
        void should_refuse_when_listIsNull() {
            assertThatThrownBy(() -> RevenueMetricRollup.rollUp(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses an empty list rather than silently reporting zero")
        void should_refuse_when_listIsEmpty() {
            assertThatThrownBy(() -> RevenueMetricRollup.rollUp(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses rows of two different currencies")
        void should_refuse_when_currenciesDiffer() {
            List<FinAggRevenuePeriod> rows = List.of(
                    row(CATEGORY_A, "100.00", 5L, "INR"),
                    row(CATEGORY_B, "50.00", 2L, "USD"));

            assertThatThrownBy(() -> RevenueMetricRollup.rollUp(rows))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Summing")
    class Summing {

        @Test
        @DisplayName("sums gross, net and transaction count across categories")
        void should_sum_when_allRowsKnown() {
            List<FinAggRevenuePeriod> rows = List.of(
                    row(CATEGORY_A, "100.00", 5L, "INR"),
                    row(CATEGORY_B, "50.00", 2L, "INR"));

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(rows);

            assertThat(rollup.grossAmount()).isEqualByComparingTo("150.00");
            assertThat(rollup.grossFullyKnown()).isTrue();
            assertThat(rollup.transactionCount()).isEqualTo(7L);
            assertThat(rollup.transactionCountFullyKnown()).isTrue();
            assertThat(rollup.currency()).isEqualTo("INR");
        }

        @Test
        @DisplayName("a single row's own figures pass through unchanged")
        void should_passThrough_when_oneRow() {
            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(
                    List.of(row(CATEGORY_A, "100.00", 5L, "INR")));

            assertThat(rollup.grossAmount()).isEqualByComparingTo("100.00");
            assertThat(rollup.transactionCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("asOfDate is the latest period_end among the rows")
        void should_takeLatestPeriodEnd_when_rowsSpanPeriods() {
            FinAggRevenuePeriod early = row(CATEGORY_A, "100.00", 5L, "INR");
            early.setPeriodEnd(LocalDate.of(2025, 4, 30));
            FinAggRevenuePeriod late = row(CATEGORY_B, "50.00", 2L, "INR");
            late.setPeriodEnd(LocalDate.of(2025, 5, 31));

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(early, late));

            assertThat(rollup.asOfDate()).isEqualTo(LocalDate.of(2025, 5, 31));
        }
    }

    @Nested
    @DisplayName("Gross: NULL propagation")
    class GrossNulls {

        @Test
        @DisplayName("gross is null when every row's gross is null")
        void should_beNull_when_noRowKnowsGross() {
            FinAggRevenuePeriod a = row(CATEGORY_A, "100.00", 5L, "INR");
            a.setGrossAmount(null);
            FinAggRevenuePeriod b = row(CATEGORY_B, "50.00", 2L, "INR");
            b.setGrossAmount(null);

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(a, b));

            assertThat(rollup.grossAmount()).isNull();
            assertThat(rollup.grossFullyKnown()).isFalse();
        }

        @Test
        @DisplayName("a floor is reported, not hidden, when one bucket's gross is wholly unknown")
        void should_sumKnownRows_when_oneRowsGrossIsNull() {
            FinAggRevenuePeriod knownRow = row(CATEGORY_A, "100.00", 5L, "INR");
            FinAggRevenuePeriod unknownRow = row(CATEGORY_B, "50.00", 2L, "INR");
            unknownRow.setGrossAmount(null);

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(knownRow, unknownRow));

            assertThat(rollup.grossAmount()).isEqualByComparingTo("100.00");
            assertThat(rollup.grossFullyKnown())
                    .as("100.00 is a floor -- category B's bucket contributed nothing known")
                    .isFalse();
        }

        @Test
        @DisplayName("facts_with_unknown_gross alone marks the sum as a floor, even with a non-null total")
        void should_markPartial_when_factsWithUnknownGrossIsPositive() {
            FinAggRevenuePeriod partial = row(CATEGORY_A, "100.00", 5L, "INR");
            partial.setFactsWithUnknownGross(3);

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(partial));

            assertThat(rollup.grossAmount()).isEqualByComparingTo("100.00");
            assertThat(rollup.grossFullyKnown()).isFalse();
            assertThat(rollup.factsWithUnknownGross()).isEqualTo(3);
        }

        @Test
        @DisplayName("facts_with_unknown_gross sums across every row, not just the first")
        void should_sumUnknownFactCount_when_multipleRowsPartial() {
            FinAggRevenuePeriod a = row(CATEGORY_A, "100.00", 5L, "INR");
            a.setFactsWithUnknownGross(3);
            FinAggRevenuePeriod b = row(CATEGORY_B, "50.00", 2L, "INR");
            b.setFactsWithUnknownGross(4);

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(a, b));

            assertThat(rollup.factsWithUnknownGross()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("Net and transaction count: the same rule, independently")
    class OtherMeasures {

        @Test
        @DisplayName("net is null when it is null on any contributing row")
        void should_beNull_when_anyRowsNetIsNull() {
            FinAggRevenuePeriod a = row(CATEGORY_A, "100.00", 5L, "INR");
            FinAggRevenuePeriod b = row(CATEGORY_B, "50.00", 2L, "INR");
            b.setNetAmount(null);

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(a, b));

            assertThat(rollup.netFullyKnown()).isFalse();
        }

        @Test
        @DisplayName("transaction count is null when it is null on any contributing row, independently of gross")
        void should_beIndependentOfGross_when_transactionCountIsNull() {
            FinAggRevenuePeriod a = row(CATEGORY_A, "100.00", 5L, "INR");
            FinAggRevenuePeriod b = row(CATEGORY_B, "50.00", 2L, "INR");
            b.setTransactionCount(null);

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(a, b));

            assertThat(rollup.grossFullyKnown())
                    .as("gross is unaffected by the missing transaction count")
                    .isTrue();
            assertThat(rollup.transactionCountFullyKnown()).isFalse();
        }
    }

    @Nested
    @DisplayName("Reconciliation status")
    class Reconciliation {

        @Test
        @DisplayName("PASSED only when every row PASSED")
        void should_bePassed_when_everyRowPassed() {
            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(
                    List.of(row(CATEGORY_A, "100.00", 5L, "INR"), row(CATEGORY_B, "50.00", 2L, "INR")));

            assertThat(rollup.reconciliationStatus()).isEqualTo(ReconciliationStatus.PASSED);
        }

        @Test
        @DisplayName("NOT_AVAILABLE when any row is not PASSED -- a blend cannot claim a full pass")
        void should_beNotAvailable_when_oneRowIsNotAvailable() {
            FinAggRevenuePeriod a = row(CATEGORY_A, "100.00", 5L, "INR");
            FinAggRevenuePeriod b = row(CATEGORY_B, "50.00", 2L, "INR");
            b.setReconciliationStatus(ReconciliationStatus.NOT_AVAILABLE);

            RevenueMetricRollup.Rollup rollup = RevenueMetricRollup.rollUp(List.of(a, b));

            assertThat(rollup.reconciliationStatus()).isEqualTo(ReconciliationStatus.NOT_AVAILABLE);
        }
    }

    private static FinAggRevenuePeriod row(long categoryId, String gross, long transactionCount,
                                           String currency) {
        return FinAggRevenuePeriod.builder()
                .templeId(TEMPLE)
                .sourceSystemId(SOURCE)
                .periodType(PeriodType.FINANCIAL_YEAR)
                .periodKey("2025-26")
                .categoryId(categoryId)
                .paymentMode(PaymentMode.CASH)
                .financialYear("2025-26")
                .periodStart(LocalDate.of(2025, 4, 1))
                .periodEnd(LocalDate.of(2026, 3, 31))
                .factCount(1)
                .transactionCount(transactionCount)
                .factsWithUnknownCount(0)
                .grossAmount(new BigDecimal(gross))
                .factsWithUnknownGross(0)
                .netAmount(new BigDecimal(gross))
                .currency(currency)
                .paymentModeInferredFacts(0)
                .reconciliationStatus(ReconciliationStatus.PASSED)
                .reconciliationInherited(false)
                .calcVersion((short) 1)
                .build();
    }
}

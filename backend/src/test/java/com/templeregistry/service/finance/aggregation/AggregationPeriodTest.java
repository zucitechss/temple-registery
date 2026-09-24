package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.enums.PeriodType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-070 period keys. Pure arithmetic, no database, no container.
 *
 * <p>The boundaries are the whole point. A month key that is off by one day at a financial-year edge
 * moves a day's revenue into the wrong year, and both the aggregate and the fact it came from still
 * look perfectly plausible on their own.
 */
class AggregationPeriodTest {

    @Nested
    @DisplayName("Financial year")
    class FinancialYearKeys {

        @ParameterizedTest(name = "{0} falls in FY {1}")
        @CsvSource({
                "2025-04-01, 2025-26",  // first day of the year
                "2025-06-15, 2025-26",
                "2026-03-31, 2025-26",  // last day of the year
                "2026-04-01, 2026-27",  // first day of the next
                "2025-03-31, 2024-25",  // last day of the previous
                "2024-02-29, 2023-24"   // a leap day, which must not confuse the year start
        })
        void should_deriveFinancialYear_when_dateGiven(LocalDate date, String expected) {
            assertThat(AggregationPeriod.keyOf(PeriodType.FINANCIAL_YEAR, date)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Bounds are 1 April to 31 March, inclusive and leap-year correct")
        void should_boundFinancialYear_when_keyGiven() {
            assertThat(AggregationPeriod.startOf(PeriodType.FINANCIAL_YEAR, "2023-24"))
                    .isEqualTo(LocalDate.of(2023, 4, 1));
            assertThat(AggregationPeriod.endOf(PeriodType.FINANCIAL_YEAR, "2023-24"))
                    .as("2024 is a leap year, and 29 February is inside FY2023-24")
                    .isEqualTo(LocalDate.of(2024, 3, 31));
        }
    }

    @Nested
    @DisplayName("Month")
    class MonthKeys {

        @ParameterizedTest(name = "{0} falls in month {1}")
        @CsvSource({
                "2025-04-01, 2025-04",
                "2025-04-30, 2025-04",
                "2025-05-01, 2025-05",
                "2025-12-31, 2025-12",
                "2026-01-01, 2026-01",
                "2024-02-29, 2024-02"
        })
        void should_deriveMonth_when_dateGiven(LocalDate date, String expected) {
            assertThat(AggregationPeriod.keyOf(PeriodType.MONTH, date)).isEqualTo(expected);
        }

        /**
         * April is 2025-04, not "month 1 of FY2025-26". Numbering months from April would produce a
         * key that only sorts correctly for a reader who already knew the convention.
         */
        @Test
        @DisplayName("A month key is the calendar month, not an offset into the financial year")
        void should_useCalendarMonth_when_yearStartsInApril() {
            assertThat(AggregationPeriod.keyOf(PeriodType.MONTH, LocalDate.of(2025, 4, 10)))
                    .isEqualTo("2025-04");
        }

        @Test
        @DisplayName("Month bounds are leap-year and month-length correct")
        void should_boundMonth_when_keyGiven() {
            assertThat(AggregationPeriod.startOf(PeriodType.MONTH, "2024-02"))
                    .isEqualTo(LocalDate.of(2024, 2, 1));
            assertThat(AggregationPeriod.endOf(PeriodType.MONTH, "2024-02"))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
            assertThat(AggregationPeriod.endOf(PeriodType.MONTH, "2025-02"))
                    .isEqualTo(LocalDate.of(2025, 2, 28));
            assertThat(AggregationPeriod.endOf(PeriodType.MONTH, "2025-04"))
                    .isEqualTo(LocalDate.of(2025, 4, 30));
        }

        /**
         * Load-bearing: every monthly aggregate carries its parent financial year, because the gate
         * decides per year and there is no month-scoped reconciliation evidence.
         */
        @ParameterizedTest(name = "month {0} belongs to FY {1}")
        @CsvSource({
                "2025-04, 2025-26",  // first month of the year
                "2025-12, 2025-26",
                "2026-01, 2025-26",  // January is still the same financial year
                "2026-03, 2025-26",  // last month of the year
                "2026-04, 2026-27"   // and over the edge
        })
        void should_resolveParentFinancialYear_when_monthGiven(String monthKey, String expected) {
            assertThat(AggregationPeriod.financialYearOf(PeriodType.MONTH, monthKey))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("A financial year is its own parent")
        void should_returnItself_when_periodIsAFinancialYear() {
            assertThat(AggregationPeriod.financialYearOf(PeriodType.FINANCIAL_YEAR, "2025-26"))
                    .isEqualTo("2025-26");
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @ParameterizedTest(name = "{0} is not a period revenue is aggregated by")
        @EnumSource(value = PeriodType.class, names = {"DAY", "FULL_HISTORY"})
        void should_refuse_when_periodTypeIsNotAggregated(PeriodType unsupported) {
            assertThatThrownBy(() -> AggregationPeriod.keyOf(unsupported, LocalDate.of(2025, 6, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(unsupported.name());
            assertThatThrownBy(() -> AggregationPeriod.startOf(unsupported, "2025-26"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AggregationPeriod.endOf(unsupported, "2025-26"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("A date is required")
        void should_refuse_when_dateIsNull() {
            assertThatThrownBy(() -> AggregationPeriod.keyOf(PeriodType.MONTH, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Strict on purpose. Each of these parses "well enough" to produce a month somewhere, and an
         * aggregate built from a misread key totals the wrong weeks.
         */
        @ParameterizedTest(name = "month key {0} is refused")
        @CsvSource({"2025-4", "2025-004", "202504", "2025/04", "2025-13", "2025-00", "abcd-ef", "2025-04-01"})
        void should_refuse_when_monthKeyIsNotCanonical(String malformed) {
            assertThatThrownBy(() -> AggregationPeriod.startOf(PeriodType.MONTH, malformed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("yyyy-MM");
        }

        @Test
        @DisplayName("A null month key is refused rather than defaulting")
        void should_refuse_when_monthKeyIsNull() {
            assertThatThrownBy(() -> AggregationPeriod.startOf(PeriodType.MONTH, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

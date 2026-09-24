package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.service.finance.pipeline.FinancialYear;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The period a business date falls in, in the one canonical spelling per {@link PeriodType} (FIN-070).
 *
 * <p>Aggregation keys periods by string, so one spelling has to win — the same reason
 * {@link FinancialYear} exists, and this class delegates to it rather than repeating the year-start
 * rule. A second place that decided when a financial year begins would eventually disagree with the
 * first, and both columns would look plausible in isolation.
 *
 * <p>Two period types, and no {@code DAY}: no catalogued report reads a daily aggregate, and
 * drill-down to one day goes to the facts (ADR-011). {@code FULL_HISTORY} is a reconciliation scope,
 * not a reporting period, and is refused here rather than silently producing a row nobody asked for.
 *
 * <pre>
 *   FINANCIAL_YEAR  2025-26   1 April 2025 .. 31 March 2026
 *   MONTH           2025-04   1 April 2025 .. 30 April 2025
 * </pre>
 *
 * <p>A month key is a <em>calendar</em> month, not an offset into the financial year: April 2025 is
 * {@code 2025-04}, never {@code 2025-01} for "first month of FY2025-26". Numbering months from April
 * would produce a key that sorts correctly only if the reader already knew the convention, and that
 * reader does not exist.
 */
public final class AggregationPeriod {

    private AggregationPeriod() {
    }

    /**
     * The key of the period of {@code periodType} containing {@code businessDate}.
     *
     * @param businessDate the date the revenue belongs to financially — never an extraction or
     *                     modification date (FIN-D-012)
     * @throws IllegalArgumentException if the date is null, or the period type is not one this
     *                                  layer aggregates by
     */
    public static String keyOf(PeriodType periodType, LocalDate businessDate) {
        require(businessDate != null, "A period cannot be derived from no date");
        return switch (periodType) {
            case FINANCIAL_YEAR -> FinancialYear.of(businessDate);
            case MONTH -> "%d-%02d".formatted(businessDate.getYear(), businessDate.getMonthValue());
            case DAY, FULL_HISTORY -> throw new IllegalArgumentException(
                    "Revenue is not aggregated by " + periodType + ". Supported: FINANCIAL_YEAR, MONTH.");
        };
    }

    /** First day of the period, inclusive. */
    public static LocalDate startOf(PeriodType periodType, String periodKey) {
        return switch (periodType) {
            case FINANCIAL_YEAR -> FinancialYear.startOf(periodKey);
            case MONTH -> monthOf(periodKey).atDay(1);
            case DAY, FULL_HISTORY -> throw new IllegalArgumentException(
                    "Revenue is not aggregated by " + periodType + ". Supported: FINANCIAL_YEAR, MONTH.");
        };
    }

    /** Last day of the period, inclusive, and leap-year correct. */
    public static LocalDate endOf(PeriodType periodType, String periodKey) {
        return switch (periodType) {
            case FINANCIAL_YEAR -> FinancialYear.endOf(periodKey);
            case MONTH -> monthOf(periodKey).atEndOfMonth();
            case DAY, FULL_HISTORY -> throw new IllegalArgumentException(
                    "Revenue is not aggregated by " + periodType + ". Supported: FINANCIAL_YEAR, MONTH.");
        };
    }

    /**
     * The financial year a period belongs to.
     *
     * <p>Every aggregate row carries this, including the monthly ones, because it is the scope
     * {@code ReconciliationGate} is asked about and no reconciliation result exists at month
     * granularity. Derived from the period's first day, which for a month is unambiguous: a calendar
     * month never straddles a financial-year boundary, because the year starts on the first of April.
     */
    public static String financialYearOf(PeriodType periodType, String periodKey) {
        return periodType == PeriodType.FINANCIAL_YEAR
                ? FinancialYear.of(FinancialYear.startOf(periodKey))
                : FinancialYear.of(startOf(periodType, periodKey));
    }

    /**
     * Parses {@code yyyy-MM}, rejecting anything else.
     *
     * <p>Strict for the reason {@link FinancialYear} is strict: {@code 2025-4} and {@code 2025-04-01}
     * would both parse "well enough" to yield a month, and an aggregate silently built from a misread
     * key would total the wrong weeks and report a variance nobody could explain.
     */
    private static YearMonth monthOf(String periodKey) {
        require(periodKey != null && periodKey.length() == 7 && periodKey.charAt(4) == '-',
                "Not a canonical month key: " + periodKey + ". Expected yyyy-MM.");
        try {
            int year = Integer.parseInt(periodKey.substring(0, 4));
            int month = Integer.parseInt(periodKey.substring(5));
            return YearMonth.of(year, month);
        } catch (RuntimeException notAMonth) {
            throw new IllegalArgumentException(
                    "Not a canonical month key: " + periodKey + ". Expected yyyy-MM.", notAMonth);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}

package com.templeregistry.service.finance.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIN-055. The boundary that decides which year a day's revenue is reported in.
 *
 * <p>Worth its own test class because the failure is invisible: {@code financial_year} is stored
 * beside {@code transaction_date} rather than generated from it, so a wrong computation produces
 * facts that disagree with their own dates while both columns look entirely plausible. The
 * error would surface as a district total that moves when nobody changed anything.
 */
class FinancialYearTest {

    @ParameterizedTest(name = "{0} falls in {1}")
    @DisplayName("The year turns on 1 April, not 1 January")
    @CsvSource({
            // The two days that matter: the last of one year and the first of the next.
            "2025-03-31, 2024-25",
            "2025-04-01, 2025-26",
            // Either side of the calendar year, which is the boundary people expect and is wrong.
            "2025-12-31, 2025-26",
            "2026-01-01, 2025-26",
            "2026-03-31, 2025-26",
            "2026-04-01, 2026-27",
            // Ordinary days in each half.
            "2025-06-15, 2025-26",
            "2026-02-14, 2025-26",
    })
    void should_startInApril_when_derivingTheYear(String date, String expected) {
        assertThat(FinancialYear.of(LocalDate.parse(date))).isEqualTo(expected);
    }

    @Test
    @DisplayName("Early April lands in the new year, not the one that just ended")
    void should_useTheNewYear_when_dateIsEarlyApril() {
        // The handoff singles this out: an off-by-one in the month comparison moves the first
        // days of April into the closing year, which is exactly when a year-end total is being
        // scrutinised and least likely to be forgiven.
        assertThat(FinancialYear.of(LocalDate.of(2025, 4, 1))).isEqualTo("2025-26");
        assertThat(FinancialYear.of(LocalDate.of(2025, 4, 2))).isEqualTo("2025-26");
        assertThat(FinancialYear.of(LocalDate.of(2025, 3, 31))).isEqualTo("2024-25");
    }

    @Test
    @DisplayName("The canonical form is 2025-26, never 20252026 or 2025-2026")
    void should_useCanonicalForm_when_rendering() {
        String rendered = FinancialYear.of(LocalDate.of(2025, 5, 1));

        assertThat(rendered).isEqualTo("2025-26");
        assertThat(rendered).hasSize(7);
        // fin_revenue_fact.financial_year is VARCHAR(10) and is grouped and compared as text
        // across the whole reporting layer, so a second spelling would silently split totals.
        assertThat(rendered).matches("\\d{4}-\\d{2}");
    }

    @Test
    @DisplayName("A century boundary renders its second part as two digits")
    void should_padToTwoDigits_when_yearEndsACentury() {
        assertThat(FinancialYear.of(LocalDate.of(2099, 4, 1))).isEqualTo("2099-00");
        assertThat(FinancialYear.of(LocalDate.of(2100, 3, 31))).isEqualTo("2099-00");
        assertThat(FinancialYear.of(LocalDate.of(2000, 4, 1))).isEqualTo("2000-01");
    }

    @Test
    @DisplayName("No date means no financial year, and that is a defect rather than a default")
    void should_refuse_when_dateIsNull() {
        assertThatThrownBy(() -> FinancialYear.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be derived");
    }
}

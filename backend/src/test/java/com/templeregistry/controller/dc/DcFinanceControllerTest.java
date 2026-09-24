package com.templeregistry.controller.dc;

import com.templeregistry.dto.response.finance.DataFreshnessBlock;
import com.templeregistry.dto.response.finance.FinanceCapabilityResponse;
import com.templeregistry.dto.response.finance.FinanceSummaryResponse;
import com.templeregistry.dto.response.finance.MetricEnvelope;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.exception.InvalidFinancialYearException;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.finance.reporting.FinanceReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of finance reporting (FIN-081/082/083; API_CONTRACT §4).
 *
 * <p>Security auto-configuration is excluded, as in every other controller test in this project
 * ({@code FinanceMappingControllerTest}'s own javadoc explains why): a mocked service behind a
 * disabled {@code @PreAuthorize} would make a 403 assertion here test the mock, not the guard.
 * Authorization and district scope are proven where they are enforced, in
 * {@code FinanceReportServiceImplTest}. What this class covers is that the service's return value
 * and its refusals arrive as the documented status codes and JSON shape.
 */
@WebMvcTest(value = DcFinanceController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class DcFinanceControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean FinanceReportService financeReportService;

    // JwtAuthenticationFilter is a Filter, so @WebMvcTest includes it regardless of the excluded
    // security auto-configuration, and it needs a ScopeHelper bean to construct at all -- the same
    // dependency FinanceMappingControllerTest mocks for the same reason.
    @MockBean ScopeHelper scopeHelper;

    private static final long TEMPLE = 300001L;

    // DcFinanceController reads ScopeHelper.Claims from the SecurityContext itself, unlike
    // FinanceMappingController which leaves that to the (here mocked) service. With the security
    // filter chain excluded, nothing populates it, so the test sets it directly -- the exact
    // pattern FinanceReportServiceImplTest already uses at the service layer.
    @BeforeEach
    void authenticate() {
        var claims = new ScopeHelper.Claims(1L, "SUPER_ADMIN", null, null, "super_admin", "VIEW");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null,
                        List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class Capabilities {

        @Test
        void should_return200AndTheEnvelope_when_capabilitiesExist() throws Exception {
            when(financeReportService.getCapabilities(eq(TEMPLE), any(ScopeHelper.Claims.class)))
                    .thenReturn(new FinanceCapabilityResponse.ForTemple(TEMPLE, List.of()));

            mockMvc.perform(get("/api/v1/dc/temples/{templeId}/finance/capabilities", TEMPLE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.templeId").value(TEMPLE));
        }
    }

    @Nested
    class Summary {

        @Test
        void should_return200_when_summaryIsAvailable() throws Exception {
            MetricEnvelope gross = MetricEnvelope.available(
                    new BigDecimal("906162936.00"), "INR", LocalDate.of(2026, 7, 26), ReconciliationStatus.PASSED);
            MetricEnvelope notAvailable = MetricEnvelope.notAvailable("Not available.");
            FinanceSummaryResponse response = new FinanceSummaryResponse(
                    TEMPLE, "2025-26", gross, notAvailable, notAvailable, notAvailable,
                    new DataFreshnessBlock(null, null, DataFreshnessBlock.Status.STALE,
                            "This temple has not completed a sync yet."));
            when(financeReportService.getSummary(eq(TEMPLE), eq("2025-26"), any(ScopeHelper.Claims.class)))
                    .thenReturn(response);

            mockMvc.perform(get("/api/v1/dc/temples/{templeId}/finance/summary", TEMPLE)
                            .param("financialYear", "2025-26"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.grossRevenue.value").value(906162936.00))
                    .andExpect(jsonPath("$.data.grossRevenue.availability").value("AVAILABLE"))
                    .andExpect(jsonPath("$.data.expenditure.value").doesNotExist())
                    .andExpect(jsonPath("$.data.expenditure.availability").value("NOT_AVAILABLE"));
        }

        @Test
        void should_return404_when_templeDoesNotExist() throws Exception {
            when(financeReportService.getSummary(eq(999L), eq("2025-26"), any(ScopeHelper.Claims.class)))
                    .thenThrow(new EntityNotFoundException("Temple", 999L));

            mockMvc.perform(get("/api/v1/dc/temples/{templeId}/finance/summary", 999L)
                            .param("financialYear", "2025-26"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void should_return400_when_financialYearIsMalformed() throws Exception {
            when(financeReportService.getSummary(eq(TEMPLE), eq("2025"), any(ScopeHelper.Claims.class)))
                    .thenThrow(new InvalidFinancialYearException("2025"));

            mockMvc.perform(get("/api/v1/dc/temples/{templeId}/finance/summary", TEMPLE)
                            .param("financialYear", "2025"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_FINANCIAL_YEAR"));
        }
    }
}

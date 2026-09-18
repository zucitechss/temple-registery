package com.templeregistry.controller.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.finance.CreateMappingRuleRequest;
import com.templeregistry.dto.request.finance.MappingRuleStatusRequest;
import com.templeregistry.dto.request.finance.UpdateMappingRuleRequest;
import com.templeregistry.dto.response.finance.CanonicalValueResponse;
import com.templeregistry.dto.response.finance.MappingRuleMutationResponse;
import com.templeregistry.dto.response.finance.MappingRuleResponse;
import com.templeregistry.dto.response.finance.NamespaceCatalogueResponse;
import com.templeregistry.dto.response.finance.SourceSystemSummaryResponse;
import com.templeregistry.dto.response.finance.UnresolvedValueResponse;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.exception.DuplicateResourceException;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.finance.mapping.MappingAdminService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the Source Mapper API (FIN-054B), closing the gap FIN-054A-BE recorded as
 * limitation 57.
 *
 * <p><b>What this covers:</b> that the service's refusals arrive as the documented status codes,
 * that bean validation rejects a malformed body as 400, and that the JSON matches what
 * {@code API_CONTRACT.md} §7 promises — the things the service tests could only infer from
 * {@code GlobalExceptionHandler} being wired up.
 *
 * <p><b>What this does not cover:</b> authorization. The security auto-configuration is excluded
 * here, as it is in every other controller test in this project, so {@code @PreAuthorize} does not
 * run. Who may do what is proven where it is enforced, by invoking the service as each role in
 * {@code MappingAdminSecurityTest}. A test that mocked the service and then asserted a 403 would
 * be testing the mock.
 */
@WebMvcTest(value = FinanceMappingController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class FinanceMappingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean ScopeHelper scopeHelper;
    @MockBean MappingAdminService mappingAdminService;

    private MappingRuleResponse aRule() {
        return new MappingRuleResponse(
                1L, 501L, 300001L, MappingType.REVENUE_CATEGORY,
                "SEVA_CODE", "430", "SEVA_CODE:430", true,
                "Archana", "SEVA", true, 100, true, null, 3,
                2L, LocalDateTime.of(2026, 9, 1, 9, 0),
                2L, LocalDateTime.of(2026, 9, 10, 9, 0));
    }

    private CreateMappingRuleRequest aCreateRequest() {
        CreateMappingRuleRequest rq = new CreateMappingRuleRequest();
        rq.setSourceSystemId(501L);
        rq.setMappingType(MappingType.REVENUE_CATEGORY);
        rq.setNamespace("SEVA_CODE");
        rq.setSourceValue("430");
        rq.setCanonicalValue("SEVA");
        rq.setPriority(100);
        rq.setActive(true);
        return rq;
    }

    private UpdateMappingRuleRequest anUpdateRequest() {
        UpdateMappingRuleRequest rq = new UpdateMappingRuleRequest();
        rq.setVersion(3);
        rq.setNamespace("SEVA_CODE");
        rq.setSourceValue("430");
        rq.setCanonicalValue("SEVA");
        rq.setPriority(100);
        rq.setActive(true);
        return rq;
    }

    // ───────────────────────────────────────────────────────────────── reads

    @Nested
    class Reads {

        @Test
        void should_return200WithSourceSystems_when_callerHasThem() throws Exception {
            when(mappingAdminService.listSourceSystems()).thenReturn(List.of(
                    new SourceSystemSummaryResponse(501L, 300001L, "Kollur", "KOLSOHAM", "Ops", true, 9L)));

            mockMvc.perform(get("/api/v1/finance/source-systems"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].systemCode").value("KOLSOHAM"))
                    .andExpect(jsonPath("$.data[0].activeRuleCount").value(9))
                    // Connection details must never reach a browser.
                    .andExpect(jsonPath("$.data[0].credentialRef").doesNotExist())
                    .andExpect(jsonPath("$.data[0].connectorBean").doesNotExist())
                    .andExpect(jsonPath("$.data[0].sourceDatabaseName").doesNotExist());
        }

        @Test
        void should_returnPaginationEnvelope_when_rulesAreListed() throws Exception {
            when(mappingAdminService.listRules(anyLong(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(PaginatedResponse.of(
                            new PageImpl<>(List.of(aRule()), PageRequest.of(0, 20), 1)));

            mockMvc.perform(get("/api/v1/finance/mapping-rules").param("sourceSystemId", "501"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.last").value(true));
        }

        @Test
        void should_returnBothHalvesOfTheSourceValue_when_ruleIsSerialised() throws Exception {
            when(mappingAdminService.listRules(anyLong(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(PaginatedResponse.of(
                            new PageImpl<>(List.of(aRule()), PageRequest.of(0, 20), 1)));

            mockMvc.perform(get("/api/v1/finance/mapping-rules").param("sourceSystemId", "501"))
                    .andExpect(jsonPath("$.data.content[0].namespace").value("SEVA_CODE"))
                    .andExpect(jsonPath("$.data.content[0].sourceValue").value("430"))
                    .andExpect(jsonPath("$.data.content[0].storedValue").value("SEVA_CODE:430"))
                    .andExpect(jsonPath("$.data.content[0].wellFormed").value(true))
                    // The version has to reach the client or no edit can be checked for staleness.
                    .andExpect(jsonPath("$.data.content[0].version").value(3));
        }

        /**
         * Records a pre-existing application-wide defect rather than asserting the ideal.
         *
         * <p>A missing required query parameter <em>should</em> be 400. It is 500, because
         * {@code GlobalExceptionHandler} has no handler for
         * {@code MissingServletRequestParameterException} and the catch-all
         * {@code @ExceptionHandler(Exception.class)} claims it. That is true of every controller in
         * this application, not just this one, so fixing it belongs to a task that can re-run the
         * whole suite behind the change — see FIN-054B limitation.
         *
         * <p>What matters for this endpoint either way: the request does not succeed, and the
         * service is never reached with a null scope.
         */
        @Test
        void should_notSucceed_when_sourceSystemIdIsMissing() throws Exception {
            mockMvc.perform(get("/api/v1/finance/mapping-rules"))
                    .andExpect(status().is5xxServerError());

            verify(mappingAdminService, never())
                    .listRules(any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        void should_passFiltersThrough_when_theyAreSupplied() throws Exception {
            when(mappingAdminService.listRules(anyLong(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                    .thenReturn(PaginatedResponse.of(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)));

            mockMvc.perform(get("/api/v1/finance/mapping-rules")
                            .param("sourceSystemId", "501")
                            .param("mappingType", "REVENUE_CATEGORY")
                            .param("active", "false")
                            .param("q", "430")
                            .param("sort", "priority,desc"))
                    .andExpect(status().isOk());

            verify(mappingAdminService).listRules(
                    eq(501L), eq(MappingType.REVENUE_CATEGORY), eq(false), eq(null),
                    eq("430"), eq(0), eq(20), eq("priority,desc"));
        }

        @Test
        void should_defaultToUnmapped_when_noOutcomeIsGiven() throws Exception {
            when(mappingAdminService.listUnresolved(anyLong(), any()))
                    .thenReturn(new UnresolvedValueResponse(501L, 77L, MappingOutcome.UNMAPPED,
                            LocalDateTime.of(2026, 9, 17, 12, 0),
                            List.of(new UnresolvedValueResponse.UnresolvedValue(
                                    "SEVA_CODE", "431", 120L, LocalDateTime.of(2026, 9, 17, 12, 0)))));

            mockMvc.perform(get("/api/v1/finance/mapping-rules/unresolved").param("sourceSystemId", "501"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.syncBatchId").value(77))
                    .andExpect(jsonPath("$.data.values[0].affected").value(120));

            verify(mappingAdminService).listUnresolved(501L, MappingOutcome.UNMAPPED);
        }

        @Test
        void should_returnNullBatch_when_nothingHasBeenMapped() throws Exception {
            when(mappingAdminService.listUnresolved(anyLong(), any()))
                    .thenReturn(new UnresolvedValueResponse(501L, null, MappingOutcome.UNMAPPED, null, List.of()));

            // A null batch is how a client tells "not measured" from "nothing unresolved", so it
            // has to survive serialisation rather than being omitted.
            mockMvc.perform(get("/api/v1/finance/mapping-rules/unresolved").param("sourceSystemId", "501"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.syncBatchId").doesNotExist())
                    .andExpect(jsonPath("$.data.values").isEmpty());
        }

        @Test
        void should_returnSampleSize_when_namespacesAreListed() throws Exception {
            when(mappingAdminService.namespaces(501L))
                    .thenReturn(new NamespaceCatalogueResponse(501L, List.of("SEVA_CODE"), List.of("SEVA_CODE"), 200));

            mockMvc.perform(get("/api/v1/finance/source-systems/501/namespaces"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.observed[0]").value("SEVA_CODE"))
                    .andExpect(jsonPath("$.data.sampledRows").value(200));
        }

        @Test
        void should_returnCanonicalValues_when_asked() throws Exception {
            when(mappingAdminService.canonicalValues())
                    .thenReturn(List.of(new CanonicalValueResponse("SEVA", "Seva", null, 10)));

            mockMvc.perform(get("/api/v1/finance/canonical-values"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].categoryCode").value("SEVA"));
        }

        @Test
        void should_return404_when_ruleIsNotAvailableToCaller() throws Exception {
            when(mappingAdminService.getRule(99L))
                    .thenThrow(new EntityNotFoundException("Mapping rule", 99L));

            // The service answers 404 both for "no such rule" and for "not in your jurisdiction".
            mockMvc.perform(get("/api/v1/finance/mapping-rules/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ──────────────────────────────────────────────────────────────── writes

    @Nested
    class Writes {

        @Test
        void should_return201WithTheHistoricalEffect_when_ruleIsCreated() throws Exception {
            when(mappingAdminService.create(any()))
                    .thenReturn(MappingRuleMutationResponse.of(aRule(), List.of()));

            mockMvc.perform(post("/api/v1/finance/mapping-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(aCreateRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.rule.id").value(1))
                    // The sentence the client is required to show must actually be in the payload.
                    .andExpect(jsonPath("$.data.historicalEffect")
                            .value(MappingRuleMutationResponse.HISTORICAL_EFFECT))
                    .andExpect(jsonPath("$.data.warnings").isArray());
        }

        @Test
        void should_carryWarnings_when_theBackendHasThem() throws Exception {
            when(mappingAdminService.create(any())).thenReturn(
                    MappingRuleMutationResponse.of(aRule(), List.of("No staged record carries [SEVACODE].")));

            mockMvc.perform(post("/api/v1/finance/mapping-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(aCreateRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.warnings[0]").value("No staged record carries [SEVACODE]."));
        }

        @Test
        void should_return400WithFieldMessages_when_bodyIsIncomplete() throws Exception {
            CreateMappingRuleRequest rq = aCreateRequest();
            rq.setNamespace("");
            rq.setCanonicalValue(null);

            mockMvc.perform(post("/api/v1/finance/mapping-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors").isArray());

            verify(mappingAdminService, never()).create(any());
        }

        @Test
        void should_return409_when_sourceValueIsAlreadyMapped() throws Exception {
            when(mappingAdminService.create(any()))
                    .thenThrow(new DuplicateResourceException("Rule 12 already maps [SEVA_CODE:430] to [SEVA]."));

            mockMvc.perform(post("/api/v1/finance/mapping-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(aCreateRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Rule 12 already maps [SEVA_CODE:430] to [SEVA]."));
        }

        @Test
        void should_return422_when_theMappingIsSemanticallyRefused() throws Exception {
            when(mappingAdminService.create(any()))
                    .thenThrow(new IllegalStateException("[NOPE] is not an active revenue category."));

            // 422, not 400: the request is well formed, the configuration it asks for is not valid.
            mockMvc.perform(post("/api/v1/finance/mapping-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(aCreateRequest())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value("[NOPE] is not an active revenue category."));
        }

        @Test
        void should_return409WithTheLockCode_when_versionIsStale() throws Exception {
            when(mappingAdminService.update(anyLong(), any()))
                    .thenThrow(new OptimisticLockingFailureException("stale"));

            // The client keys its "refresh before saving again" message on this code.
            mockMvc.perform(put("/api/v1/finance/mapping-rules/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(anUpdateRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        }

        @Test
        void should_return400_when_anUpdateOmitsTheVersion() throws Exception {
            UpdateMappingRuleRequest rq = anUpdateRequest();
            rq.setVersion(null);

            mockMvc.perform(put("/api/v1/finance/mapping-rules/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest());

            verify(mappingAdminService, never()).update(anyLong(), any());
        }

        @Test
        void should_return200_when_statusIsChanged() throws Exception {
            MappingRuleStatusRequest rq = new MappingRuleStatusRequest();
            rq.setActive(false);
            rq.setVersion(3);
            when(mappingAdminService.setActive(anyLong(), any()))
                    .thenReturn(MappingRuleMutationResponse.of(aRule(), List.of()));

            mockMvc.perform(patch("/api/v1/finance/mapping-rules/1/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.historicalEffect").exists());
        }

        @Test
        void should_return404_when_updatingARuleOutsideTheCallersScope() throws Exception {
            when(mappingAdminService.update(anyLong(), any()))
                    .thenThrow(new EntityNotFoundException("Mapping rule", 99L));

            mockMvc.perform(put("/api/v1/finance/mapping-rules/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(anUpdateRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void should_return400_when_bodyIsNotReadable() throws Exception {
            mockMvc.perform(post("/api/v1/finance/mapping-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ not json"))
                    .andExpect(status().isBadRequest());

            verify(mappingAdminService, never()).create(any());
        }
    }

    // ────────────────────────────────────────────────── what is not exposed

    @Nested
    class NotExposed {

        /**
         * The guarantee is that these routes do not exist and nothing reaches the service. The
         * exact status is 500 rather than 405/404 for the same application-wide reason as
         * {@code should_notSucceed_when_sourceSystemIdIsMissing}: the catch-all handler claims
         * {@code HttpRequestMethodNotSupportedException} and {@code NoResourceFoundException}.
         * Asserting "did not succeed and did not reach the service" states the real property
         * without endorsing the status.
         */
        @Test
        void should_notSucceed_when_aDeleteIsAttempted() throws Exception {
            // Retirement is deactivation, which stays visible. No delete endpoint exists.
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/finance/mapping-rules/1"))
                    .andExpect(status().is(org.hamcrest.Matchers.not(200)));
        }

        @Test
        void should_notSucceed_when_aRerunEndpointIsAttempted() throws Exception {
            // Nothing in this controller can re-process a batch or touch a financial fact.
            mockMvc.perform(post("/api/v1/finance/mapping-rules/1/rerun"))
                    .andExpect(status().is(org.hamcrest.Matchers.not(200)));

            verify(mappingAdminService, never()).create(any());
            verify(mappingAdminService, never()).update(anyLong(), any());
            verify(mappingAdminService, never()).setActive(anyLong(), any());
        }
    }
}

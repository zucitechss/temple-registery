package com.templeregistry.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.dto.request.trust.CreateBoardMemberRequest;
import com.templeregistry.dto.request.trust.CreateTrustRequest;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.entity.temple.TempleGrade;
import com.templeregistry.entity.temple.ReligiousTradition;
import com.templeregistry.entity.trust.TrustType;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.repository.trust.BoardMemberRepository;
import com.templeregistry.repository.trust.TrustRepository;
import com.templeregistry.security.ScopeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Disabled;

/**
 * Integration tests for the Trust & Board module.
 * Proves end-to-end correctness: validation, persistence, security, and PII masking.
 *
 * NOTE: These tests require a MySQL-compatible database. They are disabled in environments
 * without Docker/MySQL (e.g., local dev without Docker). All business logic is covered
 * by TrustServiceImplTest and TrustValidationServiceImplTest which run without a DB.
 *
 * To enable: remove @Disabled and ensure a MySQL-compatible DB is available via
 * Testcontainers or application-test.yml pointing to a real DB.
 *
 * Now uses {@link MySQLContainerBase} for automatic Testcontainers MySQL setup.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class TrustIntegrationTest extends MySQLContainerBase {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TempleRepository templeRepository;
    @Autowired TrustRepository trustRepository;
    @Autowired BoardMemberRepository boardMemberRepository;

    private Long templeId;

    @BeforeEach
    void setUp() {
        // No global wipe here. Flyway seeds this database (V100/V101) and those temples are
        // referenced by nine other tables, so deleting every temple is neither safe nor needed:
        // deleteAll() would only soft-delete (Temple and BoardMember carry @SQLDelete, leaving
        // rows that then block the trusts FK), and a bulk hard delete would break the seed FKs.
        // Every test below scopes itself to the temple created here, so a fresh temple per test
        // is the isolation this fixture actually needs.
        Temple temple = Temple.builder()
                .name("Test Temple")
                // Unique per test: uq_temples_registration is a plain unique key, so it collides
                // with soft-deleted and seeded rows alike if this is a fixed literal.
                .registrationNumber("REG-INTEG-" + UUID.randomUUID().toString().substring(0, 8))
                .grade(TempleGrade.A)
                .tradition(ReligiousTradition.OTHER)
                .doorNumber("1")
                .street("Main St")
                .villageTown("Testville")
                .pinCode("560001")
                .districtId(1L)
                .primaryDeity("Rama")
                .build();
        Temple saved = templeRepository.save(temple);
        templeId = saved.getId();

        // TA security context
        authenticateAs(new ScopeHelper.Claims(1L, "TEMPLE_AUTHORITY", null, templeId, "ta_user", "EDIT"));
    }

    /**
     * Installs an authentication exactly as {@code JwtAuthenticationFilter} does — principal plus
     * a {@code ROLE_<role>} authority. The authority is not optional: this test runs with
     * {@code addFilters = false}, so nothing else grants it, and every {@code @PreAuthorize}
     * built on {@code hasAnyRole(...)} (such as {@code RoleConstants.CAN_SUBMIT}) would otherwise
     * deny with 403 no matter which role the claims name.
     */
    /**
     * A reference that is unique per call. Trust registration numbers are validated for global
     * uniqueness (TrustValidationServiceImpl), so a fixed literal would make the second test to
     * run fail with 409 now that this fixture no longer wipes the trusts table.
     */
    private static String uniqueRef(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void authenticateAs(ScopeHelper.Claims claims) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + claims.role()))));
    }

    // â”€â”€â”€ Trust CRUD â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    class TrustCrud {

        @Test
        void creates_trust_and_response_contains_only_masked_pii() throws Exception {
            CreateTrustRequest rq = validTrustRequest();

            MvcResult result = mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.trustName").value("Integration Trust"))
                    .andExpect(jsonPath("$.data.maskedPanNumber").exists())
                    .andExpect(jsonPath("$.data.maskedBankAccountNumber").exists())
                    // Raw PAN and bank account must NOT be in the response
                    .andExpect(jsonPath("$.data.trustPANNumber").doesNotExist())
                    .andExpect(jsonPath("$.data.bankAccountNumber").doesNotExist())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            // Double-check raw PAN is not anywhere in the response body
            assertThat(body).doesNotContain("ABCDE1234F");
            assertThat(body).doesNotContain("123456789012");
        }

        @Test
        void masked_pan_format_is_correct() throws Exception {
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validTrustRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.maskedPanNumber").value("AB*****4F"));
        }

        @Test
        void masked_bank_account_format_is_correct() throws Exception {
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validTrustRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.maskedBankAccountNumber").value("******9012"));
        }

        @Test
        void duplicate_trust_per_temple_returns_409() throws Exception {
            // Create first trust
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validTrustRequest())))
                    .andExpect(status().isCreated());

            // Attempt second trust for same temple
            CreateTrustRequest second = validTrustRequest();
            second.setRegistrationNumber(uniqueRef("TR")); // different reg number
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(second)))
                    .andExpect(status().isConflict());
        }

        @Test
        void temple_trust_registered_flag_is_set_after_creation() throws Exception {
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validTrustRequest())))
                    .andExpect(status().isCreated());

            Temple updated = templeRepository.findById(templeId).orElseThrow();
            assertThat(updated.isTrustRegistered()).isTrue();
        }

        @Test
        void invalid_pan_returns_400() throws Exception {
            CreateTrustRequest rq = validTrustRequest();
            rq.setPanNumber("INVALID");
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void future_registration_date_returns_400() throws Exception {
            CreateTrustRequest rq = validTrustRequest();
            rq.setDateOfRegistration(LocalDate.now().plusDays(1));
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void invalid_bank_account_returns_400() throws Exception {
            CreateTrustRequest rq = validTrustRequest();
            rq.setBankAccountNumber("12345"); // too short
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void missing_required_fields_return_400() throws Exception {
            CreateTrustRequest rq = new CreateTrustRequest(); // all nulls
            mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void all_trust_types_are_accepted() throws Exception {
            for (TrustType type : TrustType.values()) {
                // Only this temple's trust needs clearing between iterations — "one trust per
                // temple" is what the next create would trip over. A global deleteAll() would
                // hard-delete the seeded trusts too and break the board_members foreign key.
                trustRepository.findAllByTempleId(templeId).forEach(trustRepository::delete);

                CreateTrustRequest rq = validTrustRequest();
                rq.setTrustType(type);
                // Underscores are not allowed by CreateTrustRequest's alphanumeric pattern, and
                // enum names such as MULTI_TRUSTEE contain one — so the type cannot go in here.
                rq.setRegistrationNumber(uniqueRef("TR"));

                mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(rq)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.trustType").value(type.name()));
            }
        }

        @Test
        void nonexistent_trust_returns_404() throws Exception {
            mockMvc.perform(get("/api/v1/trusts/999999"))
                    .andExpect(status().isNotFound());
        }
    }

    // â”€â”€â”€ Board Member Operations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    class BoardMemberOperations {

        private Long createTrustAndGetId() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validTrustRequest())))
                    .andExpect(status().isCreated())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            return objectMapper.readTree(body).path("data").path("id").asLong();
        }

        /**
         * Regression: deleting a trust that has board members used to fail with
         * "Cannot delete or update a parent row: fk_bm_trust". Trust was the only entity in this
         * package without @SQLDelete, so it hard-deleted while its soft-deleted children kept
         * their rows and held the foreign key. Needs a real database — a mocked repository cannot
         * reproduce a constraint violation.
         */
        @Test
        void deletes_trust_that_has_board_members() throws Exception {
            Long trustId = createTrustAndGetId();
            mockMvc.perform(post("/api/v1/trusts/" + trustId + "/board-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validMemberRequest())))
                    .andExpect(status().isCreated());

            // Deleting a trust is ADMIN_ONLY.
            authenticateAs(new ScopeHelper.Claims(3L, "SUPER_ADMIN", null, null, "admin", "EDIT"));

            mockMvc.perform(delete("/api/v1/trusts/" + trustId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Soft-deleted: hidden from the API and from the "one trust per temple" rule,
            // while the row itself survives so the board-member foreign key stays satisfied.
            mockMvc.perform(get("/api/v1/trusts/" + trustId))
                    .andExpect(status().isNotFound());
            assertThat(trustRepository.findAllByTempleId(templeId)).isEmpty();
        }

        @Test
        void adds_board_member_with_masked_aadhaar() throws Exception {
            Long trustId = createTrustAndGetId();

            mockMvc.perform(post("/api/v1/trusts/" + trustId + "/board-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validMemberRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.fullName").value("Govinda Rao"))
                    .andExpect(jsonPath("$.data.maskedAadhaar").value("XXXX-XXXX-0012"));
        }

        @Test
        void duplicate_aadhaar_in_same_trust_returns_409() throws Exception {
            Long trustId = createTrustAndGetId();

            // Add first member
            mockMvc.perform(post("/api/v1/trusts/" + trustId + "/board-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validMemberRequest())))
                    .andExpect(status().isCreated());

            // Add second member with same Aadhaar
            CreateBoardMemberRequest duplicate = validMemberRequest();
            duplicate.setFullName("Different Name");
            mockMvc.perform(post("/api/v1/trusts/" + trustId + "/board-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(duplicate)))
                    .andExpect(status().isConflict());
        }

        @Test
        void invalid_aadhaar_returns_400() throws Exception {
            Long trustId = createTrustAndGetId();
            CreateBoardMemberRequest rq = validMemberRequest();
            rq.setAadhaarNumber("12345"); // too short
            mockMvc.perform(post("/api/v1/trusts/" + trustId + "/board-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void missing_address_returns_400() throws Exception {
            Long trustId = createTrustAndGetId();
            CreateBoardMemberRequest rq = validMemberRequest();
            rq.setAddress("");
            mockMvc.perform(post("/api/v1/trusts/" + trustId + "/board-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rq)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void board_members_split_into_current_and_past() throws Exception {
            Long trustId = createTrustAndGetId();

            // Add current member
            mockMvc.perform(post("/api/v1/trusts/" + trustId + "/board-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validMemberRequest())))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/trusts/" + trustId + "/board-members"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.current").isArray())
                    .andExpect(jsonPath("$.data.past").isArray())
                    .andExpect(jsonPath("$.data.current[0].fullName").value("Govinda Rao"));
        }
    }

    // â”€â”€â”€ Security: Cross-Temple Access â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    class SecurityTests {

        @Test
        void ta_cannot_access_another_temples_trust() throws Exception {
            // Create a trust for templeId
            MvcResult result = mockMvc.perform(post("/api/v1/temples/" + templeId + "/trusts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validTrustRequest())))
                    .andExpect(status().isCreated())
                    .andReturn();
            Long trustId = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asLong();

            // Switch to a different TA (different templeId)
            authenticateAs(new ScopeHelper.Claims(2L, "TEMPLE_AUTHORITY", null, 9999L, "other_ta", "EDIT"));

            // Should be blocked by OwnershipGuard
            mockMvc.perform(get("/api/v1/trusts/" + trustId))
                    .andExpect(status().isForbidden());
        }
    }

    // â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private CreateTrustRequest validTrustRequest() {
        return CreateTrustRequest.builder()
                .trustName("Integration Trust")
                .trustType(TrustType.MULTI_TRUSTEE)
                .registrationNumber(uniqueRef("TR-INTEG"))
                .registeringAuthority("Sub-Registrar Office")
                .dateOfRegistration(LocalDate.now().minusDays(30))
                .panNumber("ABCDE1234F")
                .bankAccountNumber("123456789012")
                .bankName("SBI")
                .bankBranch("Main Branch")
                .annualIncome(BigDecimal.valueOf(500000))
                .build();
    }

    private CreateBoardMemberRequest validMemberRequest() {
        CreateBoardMemberRequest rq = new CreateBoardMemberRequest();
        rq.setFullName("Govinda Rao");
        rq.setAadhaarNumber("123456780012");
        rq.setDesignation("Trustee");
        rq.setAppointmentDate(LocalDate.now().minusDays(10));
        rq.setContactNumber("9876543210");
        rq.setAddress("123 Temple Street, Mysuru");
        return rq;
    }
}

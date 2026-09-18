package com.templeregistry.controller.finance;

import com.templeregistry.common.ApiResponse;
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
import com.templeregistry.security.RoleConstants;
import com.templeregistry.service.finance.mapping.MappingAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Source Mapper's HTTP surface (FIN-054A) — the finance pipeline's first endpoints.
 *
 * <p>Thin on purpose. Every rule about who may do what, whose data this is and what gets audited
 * lives on {@code MappingAdminServiceImpl}, because a guard written here protects one route and a
 * guard written there protects the operation. The class-level annotation is the coarse gate; the
 * service is the one that decides.
 *
 * <p>No endpoint accepts SQL, a table name, a column name or an order-by fragment. {@code sort}
 * takes an allow-listed property name and nothing else.
 */
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
@Tag(name = "Finance — source mapping",
     description = "Administer how a source system's values translate to canonical revenue categories")
@PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
public class FinanceMappingController {

    private final MappingAdminService mappingAdminService;

    @GetMapping("/source-systems")
    @Operation(summary = "Source systems this caller may administer mappings for")
    public ResponseEntity<ApiResponse<List<SourceSystemSummaryResponse>>> sourceSystems() {
        return ResponseEntity.ok(ApiResponse.success("Source systems retrieved.",
                mappingAdminService.listSourceSystems()));
    }

    @GetMapping("/source-systems/{sourceSystemId}/namespaces")
    @Operation(summary = "Staged field names this source has been observed to emit")
    public ResponseEntity<ApiResponse<NamespaceCatalogueResponse>> namespaces(
            @PathVariable Long sourceSystemId) {
        return ResponseEntity.ok(ApiResponse.success("Namespaces retrieved.",
                mappingAdminService.namespaces(sourceSystemId)));
    }

    @GetMapping("/canonical-values")
    @Operation(summary = "The revenue categories a rule may name")
    public ResponseEntity<ApiResponse<List<CanonicalValueResponse>>> canonicalValues() {
        return ResponseEntity.ok(ApiResponse.success("Canonical values retrieved.",
                mappingAdminService.canonicalValues()));
    }

    @GetMapping("/mapping-rules")
    @Operation(summary = "One source system's mapping rules, paged and filtered")
    public ResponseEntity<ApiResponse<PaginatedResponse<MappingRuleResponse>>> listRules(
            @RequestParam Long sourceSystemId,
            @RequestParam(required = false) MappingType mappingType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String canonicalValue,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(ApiResponse.success("Mapping rules retrieved.",
                mappingAdminService.listRules(sourceSystemId, mappingType, active,
                        canonicalValue, q, page, size, sort)));
    }

    @GetMapping("/mapping-rules/unresolved")
    @Operation(summary = "Source values the most recent batch could not classify, worst first")
    public ResponseEntity<ApiResponse<UnresolvedValueResponse>> unresolved(
            @RequestParam Long sourceSystemId,
            @RequestParam(defaultValue = "UNMAPPED") MappingOutcome outcome) {
        return ResponseEntity.ok(ApiResponse.success("Unresolved values retrieved.",
                mappingAdminService.listUnresolved(sourceSystemId, outcome)));
    }

    @GetMapping("/mapping-rules/{id}")
    @Operation(summary = "One mapping rule")
    public ResponseEntity<ApiResponse<MappingRuleResponse>> getRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Mapping rule retrieved.",
                mappingAdminService.getRule(id)));
    }

    @PostMapping("/mapping-rules")
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    @Operation(summary = "Create a mapping rule. Affects future runs only — never historical figures")
    public ResponseEntity<ApiResponse<MappingRuleMutationResponse>> create(
            @Valid @RequestBody CreateMappingRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Mapping rule created.",
                        mappingAdminService.create(request)));
    }

    @PutMapping("/mapping-rules/{id}")
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    @Operation(summary = "Update a mapping rule. Affects future runs only — never historical figures")
    public ResponseEntity<ApiResponse<MappingRuleMutationResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMappingRuleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Mapping rule updated.",
                mappingAdminService.update(id, request)));
    }

    @PatchMapping("/mapping-rules/{id}/status")
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    @Operation(summary = "Retire or reinstate a mapping rule")
    public ResponseEntity<ApiResponse<MappingRuleMutationResponse>> setStatus(
            @PathVariable Long id,
            @Valid @RequestBody MappingRuleStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Mapping rule status updated.",
                mappingAdminService.setActive(id, request)));
    }
}

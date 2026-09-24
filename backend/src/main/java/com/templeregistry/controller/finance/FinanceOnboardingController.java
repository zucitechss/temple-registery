package com.templeregistry.controller.finance;

import com.templeregistry.common.ApiResponse;
import com.templeregistry.dto.request.finance.DeclareCapabilityRequest;
import com.templeregistry.dto.request.finance.DeclareSourceOfTruthRequest;
import com.templeregistry.dto.request.finance.RegisterSourceSystemRequest;
import com.templeregistry.dto.request.finance.SetSourceSystemActivationRequest;
import com.templeregistry.dto.request.finance.UpdateCapabilityDeclarationRequest;
import com.templeregistry.dto.request.finance.UpdateSourceSystemRequest;
import com.templeregistry.dto.response.finance.CapabilityCatalogueResponse;
import com.templeregistry.dto.response.finance.CapabilityDeclarationResponse;
import com.templeregistry.dto.response.finance.SourceOfTruthDeclarationResponse;
import com.templeregistry.dto.response.finance.SourceSystemActivationResponse;
import com.templeregistry.dto.response.finance.SourceSystemDetailResponse;
import com.templeregistry.dto.response.finance.SourceSystemReadinessResponse;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.service.finance.onboarding.SourceSystemAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Onboarding a temple's finance source system (FIN-140 slices 140-A through 140-D).
 *
 * <h2>Why this is a separate controller from the Source Mapper</h2>
 *
 * <p>Both administer finance configuration and both sit under {@code /api/v1/finance}, but they
 * are read by different people under different rules. {@code FinanceMappingController} is open to
 * four roles and returns nothing that describes how a temple's live system is reached — its own
 * summary type excludes the connector bean, the database name and the credential reference for
 * that reason. This one returns exactly those fields, so it is SUPER_ADMIN throughout. Putting
 * both behind one class annotation would mean the stricter rule depended on every future method
 * remembering to override the looser one.
 *
 * <p>Paths do not collide: the Source Mapper owns {@code GET /source-systems} and
 * {@code GET /source-systems/{id}/namespaces}; this owns {@code POST /source-systems},
 * {@code GET|PUT /source-systems/{id}}, {@code GET /source-systems/{id}/readiness}, the capability
 * endpoints under {@code /source-systems/{id}/capabilities}, the source-of-truth endpoints under
 * {@code /source-systems/{id}/source-of-truth}, {@code POST /source-systems/{id}/activation} and
 * {@code GET /capability-catalogue}.
 *
 * <h2>What activation is, and is not</h2>
 *
 * <p>{@code POST …/activation} sets {@code sync_enabled} — the plan's state model calls it "may
 * the platform contact the source?" It is a <b>permission</b>, gated on readiness carrying no
 * blocking finding. It contacts nothing, resolves no credential, creates no batch and schedules
 * nothing. FIN-058 gave the worker a trigger that reads the flag, and it is manual: a run starts
 * only when one is explicitly started, and no scheduler and no endpoint here starts one — so
 * enabling a source still has no immediate operational effect. The response says all of that
 * rather than returning a bare
 * boolean a reader would take for "synchronising". Declaring a capability or a source of truth
 * still activates nothing.
 *
 * <p><b>A probe.</b> There is no "test connection". The runtime serving this request holds no
 * connector and no credential and a test asserts it never will (ADR-001), so a synchronous
 * connectivity check is not a feature that was skipped — it is one this process cannot perform.
 *
 * <p><b>A delete.</b> Nothing described by these slices needs one, and a source system with facts
 * behind it is not a row anybody should be able to remove from a form. A capability declaration has
 * no delete for a stronger reason: withdrawing one is a statement, not an absence, and the
 * vocabulary already has the words for it — {@code NOT_AVAILABLE} when the source does not record
 * something, {@code NOT_APPLICABLE} when the question does not arise. Deleting the row instead
 * would leave a reader unable to tell either from "nobody has looked yet", which is the distinction
 * the availability model exists to preserve.
 *
 * <p><b>An update to a source-of-truth declaration.</b> There is a {@code POST} and no
 * {@code PUT}. Every change is a new version; the previous one is closed and kept. Facts already
 * loaded carry the version that produced them, so editing a declaration in place would leave that
 * stamp pointing at a row which no longer says what it said when the figure was published —
 * exactly the silent restatement ADR-008 exists to make impossible.
 */
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
@Tag(name = "Finance — source onboarding",
     description = "Register a temple's finance source system and check whether its configuration is coherent")
@PreAuthorize(RoleConstants.ADMIN_ONLY)
public class FinanceOnboardingController {

    private final SourceSystemAdminService sourceSystemAdminService;

    @PostMapping("/source-systems")
    @Operation(summary = "Register a source system for a temple. Always created switched off")
    public ResponseEntity<ApiResponse<SourceSystemDetailResponse>> register(
            @Valid @RequestBody RegisterSourceSystemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Source system registered. Synchronisation is disabled until it is activated.",
                        sourceSystemAdminService.register(request)));
    }

    @GetMapping("/source-systems/{id}")
    @Operation(summary = "One source system's administrative detail. Never returns the credential alias")
    public ResponseEntity<ApiResponse<SourceSystemDetailResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Source system retrieved.",
                sourceSystemAdminService.get(id)));
    }

    @PutMapping("/source-systems/{id}")
    @Operation(summary = "Update a source system's metadata. Temple, code and the sync switch are not editable here")
    public ResponseEntity<ApiResponse<SourceSystemDetailResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSourceSystemRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Source system updated.",
                sourceSystemAdminService.update(id, request)));
    }

    @GetMapping("/source-systems/{id}/readiness")
    @Operation(summary = "Whether this source system's configuration is coherent. Checks configuration only, never connectivity")
    public ResponseEntity<ApiResponse<SourceSystemReadinessResponse>> readiness(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Readiness evaluated.",
                sourceSystemAdminService.readiness(id)));
    }

    // ----------------------------------------------- capabilities (FIN-140-B)

    @GetMapping("/capability-catalogue")
    @Operation(summary = "The canonical capabilities and availabilities a declaration may name")
    public ResponseEntity<ApiResponse<CapabilityCatalogueResponse>> capabilityCatalogue() {
        return ResponseEntity.ok(ApiResponse.success("Capability catalogue retrieved.",
                sourceSystemAdminService.capabilityCatalogue()));
    }

    @GetMapping("/source-systems/{id}/capabilities")
    @Operation(summary = "What this source system has been declared able to answer")
    public ResponseEntity<ApiResponse<List<CapabilityDeclarationResponse>>> capabilities(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Capability declarations retrieved.",
                sourceSystemAdminService.listCapabilities(id)));
    }

    @PostMapping("/source-systems/{id}/capabilities")
    @Operation(summary = "Declare what this source system can answer for one capability")
    public ResponseEntity<ApiResponse<CapabilityDeclarationResponse>> declareCapability(
            @PathVariable Long id,
            @Valid @RequestBody DeclareCapabilityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Capability declared.",
                        sourceSystemAdminService.declareCapability(id, request)));
    }

    @PutMapping("/source-systems/{id}/capabilities/{declarationId}")
    @Operation(summary = "Revise a capability declaration. The capability itself is identity and cannot change")
    public ResponseEntity<ApiResponse<CapabilityDeclarationResponse>> updateCapability(
            @PathVariable Long id,
            @PathVariable Long declarationId,
            @Valid @RequestBody UpdateCapabilityDeclarationRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Capability declaration updated.",
                sourceSystemAdminService.updateCapability(id, declarationId, request)));
    }

    // ------------------------------------------- source of truth (FIN-140-C)

    @GetMapping("/source-systems/{id}/source-of-truth")
    @Operation(summary = "Every source-of-truth declaration this source has had, superseded versions included")
    public ResponseEntity<ApiResponse<List<SourceOfTruthDeclarationResponse>>> sourceOfTruth(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Source-of-truth declarations retrieved.",
                sourceSystemAdminService.listSourceOfTruth(id)));
    }

    @PostMapping("/source-systems/{id}/source-of-truth")
    @Operation(summary = "Declare the authoritative field for a metric. Always a new version; never an edit")
    public ResponseEntity<ApiResponse<SourceOfTruthDeclarationResponse>> declareSourceOfTruth(
            @PathVariable Long id,
            @Valid @RequestBody DeclareSourceOfTruthRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Source-of-truth declared. The previous version was kept and closed.",
                        sourceSystemAdminService.declareSourceOfTruth(id, request)));
    }

    // ---------------------------------------------- activation (FIN-140-D)

    @PostMapping("/source-systems/{id}/activation")
    @Operation(summary = "Enable or disable a source system for future synchronisation. "
            + "Sets a permission; contacts nothing and starts nothing")
    public ResponseEntity<ApiResponse<SourceSystemActivationResponse>> setActivation(
            @PathVariable Long id,
            @Valid @RequestBody SetSourceSystemActivationRequest request) {
        SourceSystemActivationResponse result = sourceSystemAdminService.setActivation(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                result.changed()
                        ? (result.enabledForSync()
                                ? "Enabled for future synchronisation. Nothing has been contacted "
                                        + "and no synchronisation has started."
                                : "Disabled. Configuration and declaration history are retained.")
                        : "No change — this source system was already "
                                + (result.enabledForSync() ? "enabled." : "disabled."),
                result));
    }
}

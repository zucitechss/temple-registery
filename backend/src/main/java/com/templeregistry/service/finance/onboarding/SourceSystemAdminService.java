package com.templeregistry.service.finance.onboarding;

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

import java.util.List;

/**
 * Registering and configuring a temple's finance source system (FIN-140 slice 140-A).
 *
 * <p>Onboarding a temple was, until this, a hand-written Flyway migration. This is the first
 * piece of that turned into product, and it covers the configuration half only: what the platform
 * knows about a source, and whether that knowledge is coherent.
 *
 * <h2>What this deliberately cannot do</h2>
 *
 * <p><b>Contact anything.</b> Every method reads registry-owned tables. No connector is resolved,
 * no credential is looked up and no temple system is reached, because the runtime this runs in
 * holds neither a connector bean nor a credential provider and a test asserts it never will
 * (ADR-001).
 *
 * <p><b>Switch a source on.</b> There is no activation method. Readiness reports whether
 * activation would be permitted; performing it is a later slice, and building the button before
 * the check it depends on is how a check ends up bypassed.
 *
 * <p><b>Deploy a connector.</b> A source names a connector bean; a bean exists because a release
 * put it there. No row in any table can create one.
 */
public interface SourceSystemAdminService {

    /**
     * Registers a source system against a temple, switched off.
     *
     * @throws com.templeregistry.exception.EntityNotFoundException      temple absent, or outside
     *                                                                   the caller's jurisdiction
     * @throws com.templeregistry.exception.DuplicateResourceException   this temple already has a
     *                                                                   source with that code
     * @throws IllegalStateException                                     the temple already has a
     *                                                                   source system at all (D9)
     */
    SourceSystemDetailResponse register(RegisterSourceSystemRequest request);

    /**
     * One source system's administrative detail. Never includes the credential alias.
     *
     * @throws com.templeregistry.exception.EntityNotFoundException absent or out of scope — the
     *                                                              same answer for both, so ids
     *                                                              cannot be probed one at a time
     */
    SourceSystemDetailResponse get(Long sourceSystemId);

    /**
     * Changes a source system's metadata. Identity and the kill switch are not editable here.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException the caller's version is
     *                                                                   no longer current
     */
    SourceSystemDetailResponse update(Long sourceSystemId, UpdateSourceSystemRequest request);

    /**
     * Whether this source system's configuration is coherent, computed fresh on every call.
     *
     * <p>Never persisted. A stored verdict goes stale the moment a mapping rule changes, and the
     * one thing this must never do is report a configuration clean because it was clean earlier.
     */
    SourceSystemReadinessResponse readiness(Long sourceSystemId);

    // ------------------------------------------------------ capabilities (FIN-140-B)

    /**
     * The canonical vocabularies a declaration may name.
     *
     * <p>Derived from the enums, so a client never keeps its own copy of either list.
     */
    CapabilityCatalogueResponse capabilityCatalogue();

    /**
     * This source system's declarations, in canonical capability order.
     *
     * <p>Scoped to the source system rather than the temple. Every other reader of this table is
     * temple-scoped, which is the single-source assumption open decision D9 records; onboarding
     * administers one source, and attributing another source's declarations to it is exactly the
     * confusion D9 is about.
     */
    List<CapabilityDeclarationResponse> listCapabilities(Long sourceSystemId);

    /**
     * Declares what this source system can answer for one capability.
     *
     * @throws com.templeregistry.exception.EntityNotFoundException    source system absent or out
     *                                                                 of scope
     * @throws com.templeregistry.exception.DuplicateResourceException this temple already has a
     *                                                                 declaration for that
     *                                                                 capability — possibly a
     *                                                                 retired one, or one owned by
     *                                                                 a different source system
     * @throws IllegalStateException                                   the declaration is incoherent:
     *                                                                 a non-available capability
     *                                                                 with no reason, or a coverage
     *                                                                 window that ends before it
     *                                                                 begins
     */
    CapabilityDeclarationResponse declareCapability(Long sourceSystemId,
                                                    DeclareCapabilityRequest request);

    /**
     * Revises an existing declaration. The capability itself is identity and cannot change.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException the caller's version is no
     *                                                                   longer current
     */
    CapabilityDeclarationResponse updateCapability(Long sourceSystemId,
                                                    Long declarationId,
                                                    UpdateCapabilityDeclarationRequest request);

    // ------------------------------------------- source of truth (FIN-140-C)

    /**
     * Every source-of-truth declaration this source system has ever had, superseded versions
     * included, grouped by metric with the newest version of each first.
     *
     * <p>History is returned rather than only the row in force because that is what the versioning
     * is for: a fact carries the version that produced it, so explaining why a published figure
     * changed means reading the declaration that used to be right.
     */
    List<SourceOfTruthDeclarationResponse> listSourceOfTruth(Long sourceSystemId);

    /**
     * Declares which source field is authoritative for one metric, as a new version.
     *
     * <p>Never an edit. The previous version in force is closed with an { effective_to} and
     * kept; this is the operation ADR-008 describes as a restatement.
     *
     * @throws com.templeregistry.exception.EntityNotFoundException      source system absent or
     *                                                                   out of scope
     * @throws IllegalStateException                                     the declaration is
     *                                                                   incoherent: an unknown
     *                                                                   metric, a future
     *                                                                   {@code effectiveFrom}, or
     *                                                                   one earlier than the
     *                                                                   version it supersedes
     * @throws org.springframework.dao.OptimisticLockingFailureException the caller named a version
     *                                                                   that is not the one in
     *                                                                   force
     * @throws com.templeregistry.exception.DuplicateResourceException   another administrator
     *                                                                   created the same version
     *                                                                   concurrently
     */
    SourceOfTruthDeclarationResponse declareSourceOfTruth(Long sourceSystemId,
                                                          DeclareSourceOfTruthRequest request);

    // ---------------------------------------------- activation (FIN-140-D)

    /**
     * Grants or withdraws permission for the platform to contact this source in future.
     *
     * <p><b>This starts nothing.</b> It sets {@code sync_enabled} and writes an audit row. No
     * connector is resolved, no credential is read, no {@code fin_sync_batch} is created and
     * nothing is scheduled — and nothing in production reads the flag yet, so enabling a source
     * today has no operational effect at all. The response states that rather than implying
     * otherwise.
     *
     * <p>Idempotent: asking for the state a source is already in changes nothing, writes no audit
     * row and is not an error.
     *
     * @throws com.templeregistry.exception.EntityNotFoundException source system absent or out of
     *                                                              scope
     * @throws IllegalStateException                                enabling while a readiness
     *                                                              check is blocking, or disabling
     *                                                              without a reason
     */
    SourceSystemActivationResponse setActivation(Long sourceSystemId,
                                                 SetSourceSystemActivationRequest request);
}

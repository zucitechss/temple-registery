package com.templeregistry.service.impl.finance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.templeregistry.entity.audit.AuditDataEvent;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinTempleCapability;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.DuplicateResourceException;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.audit.AuditDataEventRepository;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.finance.onboarding.OnboardingConfiguration;
import com.templeregistry.service.finance.onboarding.OnboardingConfigurationReader;
import com.templeregistry.service.finance.onboarding.OnboardingReadinessValidator;
import com.templeregistry.service.finance.onboarding.ReadinessFinding;
import com.templeregistry.service.finance.onboarding.ReadinessStatus;
import com.templeregistry.service.finance.onboarding.SourceSystemAdminService;
import com.templeregistry.service.finance.pipeline.RevenueField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Source system registration, capability declarations, source-of-truth declarations and
 * readiness (FIN-140 slices 140-A, 140-B and 140-C).
 *
 * <p>Capability and source-of-truth administration live here rather than in services of their own
 * because they are the same operation on the same aggregate: a declaration is reached through its
 * source system, scoped
 * by the same jurisdiction rule and audited the same way. A second class would have had to copy
 * {@code requireInScope}, {@code outOfScope}, {@code currentClaims} and the audit helper — roughly
 * sixty lines of security-critical code whose two copies would drift.
 *
 * <h2>Four things this class is careful about</h2>
 *
 * <p><b>It reaches nothing outside the registry database.</b> The repositories injected here read
 * {@code fin_source_system}, the capability, source-of-truth and mapping-rule tables, the
 * canonical category list, and {@code temples}. No connector, no credential provider, no
 * {@code ConnectorRegistry} — none of which exists in this runtime, by test-enforced design
 * (ADR-001). That is also why readiness can say the configuration is coherent and cannot say the
 * source works, and why the response carries that distinction rather than implying it.
 *
 * <p><b>Scope is resolved from the server's own data.</b> A caller names a source system id. The
 * temple, and through it the district, is looked up here. Registration is the one exception —
 * it necessarily takes a {@code templeId} — and that temple is scope-checked before anything is
 * written.
 *
 * <p><b>The credential alias is write-only.</b> It is accepted, stored and audited as
 * set-or-cleared, and no response type this class returns has a field to put it in.
 *
 * <p><b>Audit is written in the caller's transaction</b>, following FIN-D-063. The reasoning
 * there was that a change to how revenue is classified, recorded nowhere, is worse than a change
 * refused. It applies with more force here: this row decides <i>which external database</i> a
 * temple's published figures come from.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceSystemAdminServiceImpl implements SourceSystemAdminService {

    /**
     * Stated in every readiness response, because a reader who takes READY for "connected" has
     * been misled by us rather than by their own inattention.
     */
    /** Stated on every activation response, because "enabled" is the word most likely to be read as "running". */
    private static final String ENABLED_NOTE =
            "Enabled for future synchronisation. This is a permission, not an activity: enabling a "
                    + "source starts no synchronisation, and nothing has contacted the source "
                    + "system. A run happens only when one is explicitly started, and there is no "
                    + "scheduler and no control on this screen that starts one. No data is being "
                    + "synchronised as a result of this.";

    private static final String DISABLED_NOTE =
            "Not enabled. The platform will not contact this source system: a synchronisation run "
                    + "refuses to start while this is off, whoever asks for it. Configuration, "
                    + "capability declarations and source-of-truth history are all retained.";

    private static final String CONNECTIVITY_NOTE =
            "These checks read platform configuration only. Nothing here has contacted the source "
                    + "system, resolved a credential or confirmed that the named connector is "
                    + "deployed — the reporting runtime cannot do any of those by design.";

    private final FinSourceSystemRepository sourceSystems;
    private final FinTempleCapabilityRepository capabilities;
    private final FinSourceOfTruthDeclRepository declarations;
    private final FinMappingRuleRepository rules;
    private final FinRevenueCategoryRepository categories;
    private final TempleRepository temples;
    private final AuditDataEventRepository auditEvents;
    private final JurisdictionGuard jurisdictionGuard;
    private final ObjectMapper objectMapper;

    // --------------------------------------------------------------- register

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public SourceSystemDetailResponse register(RegisterSourceSystemRequest request) {
        Temple temple = requireTempleInScope(request.getTempleId());

        // Deleted rows included on purpose: uk_fss_temple_system is (temple_id, system_code) and
        // ignores is_deleted, so a retired source still holds its code. Filtering deleted rows out
        // here would let the insert reach the constraint and return an opaque server error instead
        // of a refusal naming the row in the way.
        sourceSystems.findByTempleIdAndSystemCode(temple.getId(), request.getSystemCode())
                .ifPresent(existing -> {
                    throw new DuplicateResourceException(
                            "Source system " + existing.getId() + " already uses code ["
                                    + request.getSystemCode() + "] for this temple"
                                    + (existing.isDeleted() ? " and is retired — a retired source "
                                            + "keeps its code, so choose another" : "")
                                    + ". One code names one source; edit that row instead.");
                });

        refuseSecondSourceForTemple(temple);

        FinSourceSystem saved = sourceSystems.saveAndFlush(FinSourceSystem.builder()
                .templeId(temple.getId())
                .systemCode(request.getSystemCode())
                .systemName(request.getSystemName())
                .sourceTechnology(request.getSourceTechnology())
                .connectorType(request.getConnectorType())
                .connectorBean(request.getConnectorBean())
                .sourceTempleCode(request.getSourceTempleCode())
                .sourceDatabaseName(request.getSourceDatabaseName())
                .credentialRef(blankToNull(request.getCredentialRef()))
                .syncScheduleCron(request.getSyncScheduleCron())
                // Never from the request. Registering a source must not start traffic to a temple.
                .syncEnabled(false)
                .stalenessThresholdHours(request.getStalenessThresholdHours() == null
                        ? 48 : request.getStalenessThresholdHours())
                .sourceTimezone(blankToNull(request.getSourceTimezone()) == null
                        ? "Asia/Kolkata" : request.getSourceTimezone())
                .notes(request.getNotes())
                .build());

        audit("CREATE", saved, "registered " + describe(saved));
        log.info("[FinanceOnboarding] Source system {} registered for temple {} as [{}], sync disabled",
                saved.getId(), temple.getId(), saved.getSystemCode());
        return toDetail(saved, temple);
    }

    /**
     * D9, enforced at the only moment it can be enforced cheaply.
     *
     * <p>{@code uk_ftc_temple_capability} is {@code (temple_id, capability)} and the capability
     * read path filters by temple alone, so a temple's second source could not declare a
     * capability the first had declared, and nothing anywhere decides whose answer is the
     * temple's when two sources disagree. Refusing here is reversible in one commit; widening the
     * constraints to allow it would bake an unanswered question into published data, where a
     * reader would find it rather than a developer.
     *
     * <p>{@code IllegalStateException} rather than a duplicate: the request is well formed and the
     * code is free. What is refused is the shape, which is a 422 rather than a 409.
     */
    private void refuseSecondSourceForTemple(Temple temple) {
        List<FinSourceSystem> existing = sourceSystems.findByTempleIdAndDeletedFalse(temple.getId());
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    "Temple " + temple.getId() + " already has source system " + existing.get(0).getId()
                            + " [" + existing.get(0).getSystemCode() + "]. The platform supports one "
                            + "finance source per temple today: capabilities and services are unique "
                            + "per temple rather than per source, so a second source could not "
                            + "declare what this temple can answer and nothing would decide which "
                            + "source to believe (open decision D9). Retire the existing source "
                            + "before registering a replacement.");
        }
    }

    // ------------------------------------------------------------------- read

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional(readOnly = true)
    public SourceSystemDetailResponse get(Long sourceSystemId) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        return toDetail(source, temples.findWithFullGeoById(source.getTempleId()).orElse(null));
    }

    // ----------------------------------------------------------------- update

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public SourceSystemDetailResponse update(Long sourceSystemId, UpdateSourceSystemRequest request) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        requireCurrentVersion(source, request.getVersion());

        String before = describe(source);
        boolean credentialWasSet = source.getCredentialRef() != null;

        source.setSystemName(request.getSystemName());
        source.setSourceTechnology(request.getSourceTechnology());
        source.setConnectorType(request.getConnectorType());
        source.setConnectorBean(request.getConnectorBean());
        source.setSourceTempleCode(request.getSourceTempleCode());
        source.setSourceDatabaseName(request.getSourceDatabaseName());
        if (request.getCredentialRef() != null) {
            // Tri-state: absent keeps, "" clears, a value replaces. The stored alias is never
            // returned, so a client cannot echo it and "absent" cannot be read as "clear".
            source.setCredentialRef(blankToNull(request.getCredentialRef()));
        }
        source.setSyncScheduleCron(request.getSyncScheduleCron());
        if (request.getStalenessThresholdHours() != null) {
            source.setStalenessThresholdHours(request.getStalenessThresholdHours());
        }
        if (blankToNull(request.getSourceTimezone()) != null) {
            source.setSourceTimezone(request.getSourceTimezone());
        }
        source.setNotes(request.getNotes());

        FinSourceSystem saved = sourceSystems.saveAndFlush(source);

        audit("UPDATE", saved, "was " + before + "; now " + describe(saved)
                + credentialChange(credentialWasSet, saved.getCredentialRef() != null));
        log.info("[FinanceOnboarding] Source system {} updated", saved.getId());
        return toDetail(saved, temples.findWithFullGeoById(saved.getTempleId()).orElse(null));
    }

    // -------------------------------------------------------------- readiness

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional(readOnly = true)
    public SourceSystemReadinessResponse readiness(Long sourceSystemId) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        Temple temple = temples.findWithFullGeoById(source.getTempleId()).orElse(null);

        List<ReadinessFinding> findings =
                OnboardingReadinessValidator.validate(snapshot(source, temple));
        ReadinessStatus status = OnboardingReadinessValidator.statusOf(findings);

        long blocking = findings.stream()
                .filter(f -> f.severity() == ReadinessStatus.BLOCKED).count();

        return new SourceSystemReadinessResponse(
                source.getId(),
                source.getTempleId(),
                temple == null ? null : temple.getName(),
                source.getSystemCode(),
                status,
                blocking == 0,
                source.isSyncEnabled(),
                false,
                CONNECTIVITY_NOTE,
                (int) blocking,
                findings.size() - (int) blocking,
                findings,
                LocalDateTime.now());
    }

    /**
     * Every registry-side fact the validator is allowed to see.
     *
     * <p>Delegated to {@link OnboardingConfigurationReader} since FIN-058, which gave readiness a
     * second caller in the sync worker. The assembly moved rather than being copied: two runtimes
     * asking the same question must not be able to get different answers.
     */
    private OnboardingConfiguration snapshot(FinSourceSystem source, Temple temple) {
        return new OnboardingConfigurationReader(
                sourceSystems, capabilities, declarations, rules, categories)
                .read(source, temple != null);
    }

    // ----------------------------------------------- capabilities (FIN-140-B)

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    public CapabilityCatalogueResponse capabilityCatalogue() {
        List<CapabilityCatalogueResponse.CapabilityOption> options =
                Arrays.stream(FinanceCapability.values())
                        .map(capability -> new CapabilityCatalogueResponse.CapabilityOption(
                                capability,
                                OnboardingReadinessValidator.drivesRevenueRequirements(capability)))
                        .toList();
        List<CapabilityCatalogueResponse.MetricOption> metrics = Arrays.stream(RevenueField.values())
                .map(field -> new CapabilityCatalogueResponse.MetricOption(
                        field.metric(), field.name(), field.required()))
                .toList();
        return new CapabilityCatalogueResponse(options,
                List.of(DataAvailability.values()), metrics);
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional(readOnly = true)
    public List<CapabilityDeclarationResponse> listCapabilities(Long sourceSystemId) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        return capabilities.findBySourceSystemIdAndDeletedFalse(source.getId()).stream()
                .sorted(Comparator.comparing(FinTempleCapability::getCapability))
                .map(this::toCapabilityDeclaration)
                .toList();
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public CapabilityDeclarationResponse declareCapability(Long sourceSystemId,
                                                           DeclareCapabilityRequest request) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        requireCoherentDeclaration(request.getAvailability(), request.getAvailabilityReason(),
                request.getCoverageFrom(), request.getCoverageTo());
        refuseDuplicateDeclaration(source, request.getCapability());

        FinTempleCapability saved = capabilities.saveAndFlush(FinTempleCapability.builder()
                // Resolved from the source system, never taken from the request: a declaration
                // that could name its own temple could attribute one temple's capability to
                // another, and no check downstream would notice.
                .templeId(source.getTempleId())
                .sourceSystemId(source.getId())
                .capability(request.getCapability())
                .availability(request.getAvailability())
                .availabilityReason(blankToNull(request.getAvailabilityReason()))
                .coverageFrom(request.getCoverageFrom())
                .coverageTo(request.getCoverageTo())
                .knownGapsJson(serialiseKnownGaps(request.getKnownGaps()))
                .lastReviewedAt(LocalDateTime.now())
                .build());

        auditCapability("CREATE", saved, "declared " + describe(saved));
        log.info("[FinanceOnboarding] Capability {} declared {} for source {} (temple {})",
                saved.getCapability(), saved.getAvailability(), source.getId(), source.getTempleId());
        return toCapabilityDeclaration(saved);
    }

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public CapabilityDeclarationResponse updateCapability(Long sourceSystemId,
                                                           Long declarationId,
                                                           UpdateCapabilityDeclarationRequest request) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        FinTempleCapability declaration = requireDeclarationOf(source, declarationId);
        requireCurrentCapabilityVersion(declaration, request.getVersion());
        requireCoherentDeclaration(request.getAvailability(), request.getAvailabilityReason(),
                request.getCoverageFrom(), request.getCoverageTo());

        String before = describe(declaration);
        declaration.setAvailability(request.getAvailability());
        declaration.setAvailabilityReason(blankToNull(request.getAvailabilityReason()));
        declaration.setCoverageFrom(request.getCoverageFrom());
        declaration.setCoverageTo(request.getCoverageTo());
        declaration.setKnownGapsJson(serialiseKnownGaps(request.getKnownGaps()));
        declaration.setLastReviewedAt(LocalDateTime.now());

        FinTempleCapability saved = capabilities.saveAndFlush(declaration);

        auditCapability("UPDATE", saved, "was " + before + "; now " + describe(saved));
        log.info("[FinanceOnboarding] Capability declaration {} updated to {}",
                saved.getId(), saved.getAvailability());
        return toCapabilityDeclaration(saved);
    }

    /**
     * The declaration behind an id, confirmed to belong to this source system.
     *
     * <p>A declaration reached through the wrong source system is reported absent rather than
     * refused, matching every other scope answer here: a caller who can tell "not yours" from "no
     * such row" can enumerate another temple's configuration one id at a time.
     */
    private FinTempleCapability requireDeclarationOf(FinSourceSystem source, Long declarationId) {
        FinTempleCapability declaration = capabilities.findById(declarationId)
                .filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException("Capability declaration", declarationId));
        if (!Objects.equals(declaration.getSourceSystemId(), source.getId())) {
            throw new EntityNotFoundException("Capability declaration", declarationId);
        }
        return declaration;
    }

    /**
     * Refuses a capability this temple has already had declared.
     *
     * <p>Deleted rows and other source systems are both included, because
     * {@code uk_ftc_temple_capability} is {@code (temple_id, capability)} and excludes neither.
     * Filtering either out would let the insert reach the constraint and return an opaque server
     * error instead of a refusal naming the row in the way — the defect slice 140-A hit on the
     * equivalent key for source system codes.
     */
    private void refuseDuplicateDeclaration(FinSourceSystem source, FinanceCapability capability) {
        capabilities.findByTempleIdAndCapability(source.getTempleId(), capability)
                .ifPresent(existing -> {
                    String qualifier;
                    if (existing.isDeleted()) {
                        qualifier = " and is retired — a retired declaration keeps its slot, so "
                                + "reinstate it rather than declaring a second";
                    } else if (!Objects.equals(existing.getSourceSystemId(), source.getId())) {
                        qualifier = " through source system " + existing.getSourceSystemId()
                                + ". Capability declarations are unique per temple rather than per "
                                + "source, so two sources cannot both declare it (open decision D9)";
                    } else {
                        qualifier = "";
                    }
                    throw new DuplicateResourceException(
                            "Temple " + source.getTempleId() + " already has a declaration for "
                                    + capability + qualifier + ". Edit declaration "
                                    + existing.getId() + " instead.");
                });
    }

    /**
     * The two ways a declaration can be internally incoherent, refused on write.
     *
     * <p>Readiness reports both as well, and that is not duplication with a different purpose:
     * readiness grades rows that already exist, including any a migration seeded, while this stops
     * the API creating a new one that readiness would immediately fault. Saving a row known to be
     * wrong and then reporting it wrong is a worse experience than refusing it with the same
     * sentence.
     */
    private void requireCoherentDeclaration(DataAvailability availability, String reason,
                                            LocalDate coverageFrom, LocalDate coverageTo) {
        if (availability != DataAvailability.AVAILABLE && blankToNull(reason) == null) {
            throw new IllegalStateException(
                    "A capability declared " + availability + " needs a reason. It is shown to a "
                            + "reader verbatim where a figure would otherwise appear, so an empty "
                            + "one renders an empty explanation.");
        }
        if (coverageFrom != null && coverageTo != null && coverageTo.isBefore(coverageFrom)) {
            throw new IllegalStateException(
                    "Coverage ends (" + coverageTo + ") before it begins (" + coverageFrom
                            + "), so the window describes no period at all.");
        }
    }

    private void requireCurrentCapabilityVersion(FinTempleCapability declaration, Integer expected) {
        if (!Objects.equals(declaration.getVersion(), expected)) {
            throw new OptimisticLockingFailureException(
                    "Capability declaration " + declaration.getId() + " has changed since it was "
                            + "loaded (version " + declaration.getVersion() + ", you sent " + expected
                            + "). Reload it and reapply your change.");
        }
    }

    /**
     * Known gaps as stored JSON, or null.
     *
     * <p>Blank entries are dropped rather than stored: each entry is shown to a reader, and an
     * empty bullet is not a documented hole. An empty list becomes null so that "no gaps recorded"
     * has one representation in the column rather than two.
     */
    private String serialiseKnownGaps(List<String> knownGaps) {
        if (knownGaps == null) {
            return null;
        }
        List<String> cleaned = knownGaps.stream()
                .filter(gap -> gap != null && !gap.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (JsonProcessingException impossible) {
            // A list of strings cannot fail to serialise; refusing loudly beats storing something
            // the reader would later parse leniently into silence.
            throw new IllegalStateException("Known gaps could not be stored.", impossible);
        }
    }

    /**
     * Known gaps as a list, tolerating an unreadable column.
     *
     * <p>Lenient on read and strict on write, matching the dashboard's own reader: a malformed cell
     * is cosmetic metadata rather than a reported figure, and failing the whole administrative list
     * over one is worse than showing it without its gaps. Writes are strict so this path stays
     * unreachable for anything this API created.
     */
    private List<String> parseKnownGaps(FinTempleCapability declaration) {
        String json = declaration.getKnownGapsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception malformed) {
            log.warn("Unreadable known_gaps_json on capability declaration {}: {}",
                    declaration.getId(), malformed.getMessage());
            return List.of();
        }
    }

    private CapabilityDeclarationResponse toCapabilityDeclaration(FinTempleCapability declaration) {
        return new CapabilityDeclarationResponse(
                declaration.getId(),
                declaration.getSourceSystemId(),
                declaration.getTempleId(),
                declaration.getCapability(),
                declaration.getAvailability(),
                declaration.getAvailabilityReason(),
                declaration.getCoverageFrom(),
                declaration.getCoverageTo(),
                parseKnownGaps(declaration),
                declaration.getLastReviewedAt(),
                declaration.getVersion(),
                declaration.getCreatedAt(),
                declaration.getUpdatedAt());
    }

    /** What a declaration says, for an audit line. Configuration only — no temple figure. */
    private String describe(FinTempleCapability declaration) {
        return declaration.getCapability() + "=" + declaration.getAvailability()
                + " coverage=" + declaration.getCoverageFrom() + ".." + declaration.getCoverageTo()
                + " reasonSet=" + (declaration.getAvailabilityReason() != null);
    }

    private void auditCapability(String action, FinTempleCapability declaration, String detail) {
        ScopeHelper.Claims claims = currentClaims();
        auditEvents.save(AuditDataEvent.builder()
                .actorId(claims.userId())
                .actorRole(claims.role())
                .action(action)
                .entityType("FIN_TEMPLE_CAPABILITY")
                .entityId(declaration.getId())
                .detail("templeId=" + declaration.getTempleId()
                        + " sourceSystemId=" + declaration.getSourceSystemId() + " " + detail)
                .build());
    }

    // -------------------------------------------------------------- authority

    /**
     * The source system behind an id, or a 404 — for absence and for out of scope alike.
     *
     * <p>Both answers are the same answer on purpose, following the Source Mapper: a caller who
     * can tell "no such source system" from "not yours" can enumerate every temple's integrations
     * one id at a time.
     *
     * <p>A source whose temple has vanished is a data defect rather than a scope question. Only a
     * platform administrator may see one, because a district-scoped caller cannot be shown a row
     * whose district cannot be established. Readiness then reports it as a blocking finding,
     * which is more useful on an onboarding screen than a bare 404.
     */
    private FinSourceSystem requireInScope(Long sourceSystemId) {
        if (sourceSystemId == null) {
            throw new IllegalStateException("sourceSystemId is required.");
        }
        FinSourceSystem source = sourceSystems.findById(sourceSystemId)
                .filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException("Source system", sourceSystemId));

        ScopeHelper.Claims claims = currentClaims();
        Temple temple = temples.findWithFullGeoById(source.getTempleId()).orElse(null);
        if (temple == null) {
            if (!RoleConstants.SUPER_ADMIN.equals(claims.role())) {
                throw new EntityNotFoundException("Source system", sourceSystemId);
            }
            return source;
        }
        if (outOfScope(temple, claims)) {
            throw new EntityNotFoundException("Source system", sourceSystemId);
        }
        return source;
    }

    private Temple requireTempleInScope(Long templeId) {
        Temple temple = temples.findWithFullGeoById(templeId)
                .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
        if (outOfScope(temple, currentClaims())) {
            throw new EntityNotFoundException("Temple", templeId);
        }
        return temple;
    }

    /**
     * Whether this caller's jurisdiction excludes this temple.
     *
     * <p>Copied from the Source Mapper deliberately, including the reason it is written this way:
     * {@code assertDistrictScope} treats a null {@code districtId} on any role other than
     * SUPER_ADMIN, TEMPLE_AUTHORITY or VIEWER as a corrupted token, and an AUDITOR is statewide
     * and legitimately carries none. Calling it only for the two district-scoped roles is what
     * keeps that defect from becoming this endpoint's.
     *
     * <p>Every method here is SUPER_ADMIN-only today, so this always returns false in practice.
     * It is present so that widening the role later is a change to one annotation rather than a
     * silent cross-district leak.
     */
    private boolean outOfScope(Temple temple, ScopeHelper.Claims claims) {
        if (!RoleConstants.DISTRICT_COLLECTOR.equals(claims.role())
                && !RoleConstants.DC_STAFF.equals(claims.role())) {
            return false;
        }
        try {
            jurisdictionGuard.assertDistrictScope(temple, claims);
            return false;
        } catch (RuntimeException outside) {
            return true;
        }
    }

    private void requireCurrentVersion(FinSourceSystem source, Integer expected) {
        if (!Objects.equals(source.getVersion(), expected)) {
            throw new OptimisticLockingFailureException(
                    "Source system " + source.getId() + " has changed since it was loaded (version "
                            + source.getVersion() + ", you sent " + expected
                            + "). Reload it and reapply your change.");
        }
    }

    private ScopeHelper.Claims currentClaims() {
        return ScopeHelper.Claims.fromContext();
    }

    // ------------------------------------------------------------------ audit

    /**
     * Records the change beside it, in the same transaction.
     *
     * <p>No try/catch. If this write fails the configuration change is rolled back, which is the
     * intended behaviour and the reason this does not go through {@code AuditService}
     * (FIN-D-063).
     */
    private void audit(String action, FinSourceSystem source, String detail) {
        ScopeHelper.Claims claims = currentClaims();
        auditEvents.save(AuditDataEvent.builder()
                .actorId(claims.userId())
                .actorRole(claims.role())
                .action(action)
                .entityType("FIN_SOURCE_SYSTEM")
                .entityId(source.getId())
                .detail("templeId=" + source.getTempleId() + " " + detail)
                .build());
    }

    /**
     * What a source system says, for an audit line.
     *
     * <p>Names no credential alias — only whether one is set. An audit trail is read by more
     * people than the screen is, and the alias names where the secret lives.
     */
    private String describe(FinSourceSystem source) {
        return "[" + source.getSystemCode() + "] " + source.getSystemName()
                + " tech=" + source.getSourceTechnology()
                + " connector=" + source.getConnectorType() + "/" + source.getConnectorBean()
                + " syncEnabled=" + source.isSyncEnabled()
                + " credentialRefSet=" + (source.getCredentialRef() != null);
    }

    private String credentialChange(boolean was, boolean now) {
        return was == now ? "" : "; credential alias " + (now ? "set" : "cleared");
    }

    // ---------------------------------------------------------------- mapping

    private SourceSystemDetailResponse toDetail(FinSourceSystem source, Temple temple) {
        return new SourceSystemDetailResponse(
                source.getId(),
                source.getTempleId(),
                temple == null ? null : temple.getName(),
                source.getSystemCode(),
                source.getSystemName(),
                source.getSourceTechnology(),
                source.getConnectorType(),
                source.getConnectorBean(),
                source.getSourceTempleCode(),
                source.getSourceDatabaseName(),
                source.getCredentialRef() != null,
                source.getSyncScheduleCron(),
                source.isSyncEnabled(),
                source.getStalenessThresholdHours(),
                source.getSourceTimezone(),
                source.getNotes(),
                source.getVersion(),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ------------------------------------------- source of truth (FIN-140-C)

    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional(readOnly = true)
    public List<SourceOfTruthDeclarationResponse> listSourceOfTruth(Long sourceSystemId) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        return declarations.findBySourceSystemIdAndDeletedFalseOrderByMetricAscVersionDesc(
                        source.getId()).stream()
                .map(this::toSourceOfTruth)
                .toList();
    }

    /**
     * A new version, never an edit.
     *
     * <p>Two writes in one transaction: the version in force is closed with an
     * {@code effective_to}, and the new one is inserted. If either fails both roll back, which
     * matters more here than almost anywhere else in this class — a half-applied restatement
     * leaves either two declarations in force or none, and the pipeline reads whichever it finds.
     */
    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public SourceOfTruthDeclarationResponse declareSourceOfTruth(Long sourceSystemId,
                                                                  DeclareSourceOfTruthRequest request) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        String metric = requireKnownMetric(request.getMetric());
        requireUsableEffectiveFrom(request.getEffectiveFrom());

        FinSourceOfTruthDecl inForce = declarations
                .findFirstBySourceSystemIdAndMetricAndEffectiveToIsNullAndDeletedFalseOrderByVersionDesc(
                        source.getId(), metric)
                .orElse(null);
        requireSupersedesTheVersionInForce(metric, inForce, request.getSupersedesVersion());

        if (inForce != null) {
            requireNotBackdatedBehind(inForce, request.getEffectiveFrom());
            inForce.setEffectiveTo(request.getEffectiveFrom());
            declarations.saveAndFlush(inForce);
        }

        int nextVersion = declarations
                .findFirstBySourceSystemIdAndMetricOrderByVersionDesc(source.getId(), metric)
                .map(FinSourceOfTruthDecl::getVersion)
                .orElse(0) + 1;

        boolean approved = Boolean.TRUE.equals(request.getApproved());
        ScopeHelper.Claims claims = currentClaims();

        FinSourceOfTruthDecl saved;
        try {
            saved = declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                    .sourceSystemId(source.getId())
                    .metric(metric)
                    .version(nextVersion)
                    .sourceObject(request.getSourceObject().trim())
                    .sourceField(request.getSourceField().trim())
                    .filterPredicate(blankToNull(request.getFilterPredicate()))
                    .rejectedAlternativesJson(serialiseRejectedAlternatives(
                            request.getRejectedAlternatives()))
                    .rationale(blankToNull(request.getRationale()))
                    .approvedBy(approved ? claims.userId() : null)
                    .approvedAt(approved ? LocalDateTime.now() : null)
                    .effectiveFrom(request.getEffectiveFrom())
                    // Never from the request. A declaration is created in force; it stops being
                    // in force only by being superseded, which is the write above.
                    .effectiveTo(null)
                    .build());
        } catch (DataIntegrityViolationException raced) {
            // uk_fsotd_source_metric_version is (source_system_id, metric, version). Reaching it
            // means another administrator committed this same version between the read above and
            // this insert. The whole transaction rolls back, so their supersession stands and
            // this one leaves nothing behind.
            throw new DuplicateResourceException(
                    "Version " + nextVersion + " of " + metric + " was created by somebody else "
                            + "while this one was being written. Nothing here was saved — reload "
                            + "the declarations and reapply the change on top of theirs.");
        }

        auditSourceOfTruth("CREATE", source, saved,
                "declared " + describe(saved)
                        + (inForce == null ? " (first version)"
                                : " superseding version " + inForce.getVersion()
                                        + ", closed at " + inForce.getEffectiveTo()));
        log.info("[FinanceOnboarding] Source-of-truth {} v{} declared for source {} (temple {}), approved={}",
                metric, nextVersion, source.getId(), source.getTempleId(), approved);
        return toSourceOfTruth(saved);
    }

    /**
     * The metric must be one the pipeline reads.
     *
     * <p>{@link RevenueField} names them, and it is the authority — normalization refuses a batch
     * without the required ones and reads the optional ones by the same names. A declaration for
     * a metric nothing reads would be an inert row: accepted, displayed, and silently without
     * effect, which is the failure this validates against rather than creates.
     */
    private String requireKnownMetric(String metric) {
        String trimmed = metric == null ? "" : metric.trim();
        for (RevenueField field : RevenueField.values()) {
            if (field.metric().equals(trimmed)) {
                return trimmed;
            }
        }
        throw new IllegalStateException(
                "[" + trimmed + "] is not a metric anything reads, so a declaration naming it "
                        + "would be accepted and then never consulted. Known metrics: "
                        + Arrays.stream(RevenueField.values()).map(RevenueField::metric)
                                .collect(Collectors.joining(", ")) + ".");
    }

    /**
     * A declaration cannot start in the future.
     *
     * <p>The pipeline asks which declaration is in force by looking for a null
     * {@code effective_to} and nothing else — it does not compare {@code effective_from} to today.
     * A future-dated version would therefore take effect the moment it was saved while claiming
     * not to, which is a silent restatement of exactly the kind ADR-008 exists to prevent.
     */
    private void requireUsableEffectiveFrom(LocalDate effectiveFrom) {
        if (effectiveFrom.isAfter(LocalDate.now())) {
            throw new IllegalStateException(
                    "effectiveFrom (" + effectiveFrom + ") is in the future. A declaration takes "
                            + "effect as soon as it is saved — nothing waits for the date — so a "
                            + "future one would apply while claiming not to. Save it on the day it "
                            + "starts applying.");
        }
    }

    /**
     * The caller must have been looking at the version they are replacing.
     *
     * <p>Not an optimistic lock: nothing about the row they read is overwritten, so Hibernate's
     * own check would pass for both of two administrators who each read version 1 and each wrote
     * "the next one". What that costs is the earlier writer's restatement being superseded by
     * somebody who never saw it, on a row that decides which source field a temple's published
     * revenue comes from.
     */
    private void requireSupersedesTheVersionInForce(String metric, FinSourceOfTruthDecl inForce,
                                                     Integer supersedesVersion) {
        Integer actual = inForce == null ? null : inForce.getVersion();
        if (Objects.equals(actual, supersedesVersion)) {
            return;
        }
        throw new OptimisticLockingFailureException(
                actual == null
                        ? "No declaration is in force for " + metric + ", but this request "
                                + "supersedes version " + supersedesVersion + ". Reload the "
                                + "declarations: the one you were looking at has been withdrawn."
                        : "Version " + actual + " of " + metric + " is in force, and this request "
                                + "supersedes version " + supersedesVersion + ". Somebody has "
                                + "declared a newer one since this was loaded — reload it and "
                                + "reapply the change on top of theirs.");
    }

    /**
     * A new version cannot start before the one it supersedes.
     *
     * <p>Its start date becomes the previous version's {@code effective_to}, so an earlier one
     * would close a window before it opened and describe a period that never existed. This is why
     * {@code effectiveTo} is not a field a caller can send: it is derived, and derived values
     * cannot be made inconsistent.
     */
    private void requireNotBackdatedBehind(FinSourceOfTruthDecl inForce, LocalDate effectiveFrom) {
        LocalDate previousStart = inForce.getEffectiveFrom();
        if (previousStart != null && effectiveFrom.isBefore(previousStart)) {
            throw new IllegalStateException(
                    "effectiveFrom (" + effectiveFrom + ") is before version "
                            + inForce.getVersion() + " began (" + previousStart + "). Superseding "
                            + "it would close that version before it opened.");
        }
    }

    /**
     * The rejected candidates as stored JSON, or null.
     *
     * <p>Blank fields are dropped rather than stored as empty strings, so a renderer showing
     * whatever keys are present does not show empty ones.
     */
    private String serialiseRejectedAlternatives(
            List<DeclareSourceOfTruthRequest.RejectedAlternative> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) {
            return null;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (DeclareSourceOfTruthRequest.RejectedAlternative alternative : alternatives) {
            Map<String, String> row = new LinkedHashMap<>();
            putIfPresent(row, "object", alternative.getObject());
            putIfPresent(row, "field", alternative.getField());
            putIfPresent(row, "measured", alternative.getMeasured());
            putIfPresent(row, "reason", alternative.getReason());
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Rejected alternatives could not be stored.", impossible);
        }
    }

    private void putIfPresent(Map<String, String> row, String key, String value) {
        if (blankToNull(value) != null) {
            row.put(key, value.trim());
        }
    }

    /**
     * The stored candidates, key by key, tolerating a column this API did not write.
     *
     * <p>Lenient on read and strict on write, as with capability gaps. The declarations seeded for
     * the first onboarded source carry their own measurement keys — {@code measuredRows},
     * {@code measuredAmountFy2025_26} — and reading them into a fixed shape would drop exactly the
     * measured evidence that gives those rows their force, so whatever keys are present are
     * returned.
     */
    private List<Map<String, String>> parseRejectedAlternatives(FinSourceOfTruthDecl declaration) {
        String json = declaration.getRejectedAlternativesJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                Map<String, String> row = new LinkedHashMap<>();
                entry.forEach((key, value) -> {
                    if (value != null) {
                        row.put(key, String.valueOf(value));
                    }
                });
                rows.add(row);
            }
            return rows;
        } catch (Exception malformed) {
            log.warn("Unreadable rejected_alternatives_json on source-of-truth declaration {}: {}",
                    declaration.getId(), malformed.getMessage());
            return List.of();
        }
    }

    private SourceOfTruthDeclarationResponse toSourceOfTruth(FinSourceOfTruthDecl declaration) {
        return new SourceOfTruthDeclarationResponse(
                declaration.getId(),
                declaration.getSourceSystemId(),
                declaration.getMetric(),
                declaration.getVersion(),
                // Computed, never stored. The pipeline decides what is in force by this same
                // column, so a stored flag could disagree with the row that is actually read.
                declaration.getEffectiveTo() == null,
                declaration.getSourceObject(),
                declaration.getSourceField(),
                declaration.getFilterPredicate(),
                parseRejectedAlternatives(declaration),
                declaration.getRationale(),
                declaration.getApprovedAt() != null,
                declaration.getApprovedBy(),
                declaration.getApprovedAt(),
                declaration.getEffectiveFrom(),
                declaration.getEffectiveTo(),
                declaration.getCreatedAt(),
                declaration.getUpdatedAt());
    }

    /** What a declaration says, for an audit line. Names a source field, never a credential. */
    private String describe(FinSourceOfTruthDecl declaration) {
        return declaration.getMetric() + " v" + declaration.getVersion()
                + " from " + declaration.getSourceObject() + "." + declaration.getSourceField()
                + " effectiveFrom=" + declaration.getEffectiveFrom()
                + " approved=" + (declaration.getApprovedAt() != null)
                + " filterSet=" + (declaration.getFilterPredicate() != null)
                + " rejectedAlternatives=" + parseRejectedAlternatives(declaration).size();
    }

    private void auditSourceOfTruth(String action, FinSourceSystem source,
                                    FinSourceOfTruthDecl declaration, String detail) {
        ScopeHelper.Claims claims = currentClaims();
        auditEvents.save(AuditDataEvent.builder()
                .actorId(claims.userId())
                .actorRole(claims.role())
                .action(action)
                .entityType("FIN_SOURCE_OF_TRUTH_DECL")
                .entityId(declaration.getId())
                .detail("templeId=" + source.getTempleId()
                        + " sourceSystemId=" + source.getId() + " " + detail)
                .build());
    }

    // ---------------------------------------------- activation (FIN-140-D)

    /**
     * Grants or withdraws permission for the platform to contact this source.
     *
     * <h3>What this does not do</h3>
     *
     * <p>It writes one boolean and one audit row. It resolves no credential, builds no connector,
     * creates no {@code fin_sync_batch}, schedules nothing and sends nothing to the worker — none
     * of which this runtime could do in any case (ADR-001). Nothing in production reads
     * {@code sync_enabled} yet, so enabling a source today starts precisely nothing; the response
     * says so rather than letting the caller assume otherwise.
     *
     * <h3>Readiness gates enabling, and only enabling</h3>
     *
     * <p>The verdict comes from the same validator the readiness endpoint uses, computed fresh
     * inside this transaction. Restating the rules here would create a second copy, and the
     * failure mode of the two disagreeing is a source enabled against a check that would have
     * refused it.
     *
     * <p>Disabling is never gated. A switch that can only be turned on is not a switch, and the
     * moment an administrator most needs to turn a source off is the moment its configuration has
     * gone wrong — which is exactly when readiness would refuse them.
     */
    @Override
    @PreAuthorize(RoleConstants.ADMIN_ONLY)
    @Transactional
    public SourceSystemActivationResponse setActivation(Long sourceSystemId,
                                                        SetSourceSystemActivationRequest request) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        Temple temple = temples.findWithFullGeoById(source.getTempleId()).orElse(null);
        boolean wanted = Boolean.TRUE.equals(request.getEnabled());

        // Already in the requested state: no write, no audit row, and — deliberately — no
        // readiness check. Re-confirming a state nobody is changing must not fail because the
        // configuration drifted since it was enabled; that is what the readiness screen is for.
        if (source.isSyncEnabled() == wanted) {
            return activationResponse(source, temple, false);
        }

        if (wanted) {
            requireReadyToEnable(source, temple);
        } else if (blankToNull(request.getReason()) == null) {
            throw new IllegalStateException(
                    "Disabling a source system needs a reason. It stops a temple's financial data "
                            + "flowing once the worker path exists, and an audit line saying only "
                            + "that somebody switched it off is not something anyone can act on.");
        }

        source.setSyncEnabled(wanted);
        FinSourceSystem saved = sourceSystems.saveAndFlush(source);

        audit(wanted ? "ENABLE_SYNC" : "DISABLE_SYNC", saved,
                "syncEnabled " + !wanted + " -> " + wanted
                        + (blankToNull(request.getReason()) == null
                                ? "" : "; reason=" + request.getReason().trim())
                        + "; " + describe(saved));
        log.info("[FinanceOnboarding] Source system {} syncEnabled set to {} (temple {}). "
                        + "No connector was resolved and no batch was created.",
                saved.getId(), wanted, saved.getTempleId());
        return activationResponse(saved, temple, true);
    }

    /**
     * Refuses to enable a source whose configuration would not survive its first run.
     *
     * <p>Blocking findings only. A warning is legitimate configuration somebody has reason for —
     * capabilities left undeclared overnight, mapping rules at equal priority that genuinely never
     * overlap — and refusing those would make the warning level meaningless.
     *
     * <p>The message names the findings rather than pointing at another screen, because the
     * caller may be a script rather than the person looking at the readiness panel.
     */
    private void requireReadyToEnable(FinSourceSystem source, Temple temple) {
        List<ReadinessFinding> blocking =
                OnboardingReadinessValidator.validate(snapshot(source, temple)).stream()
                        .filter(finding -> finding.severity() == ReadinessStatus.BLOCKED)
                        .toList();
        if (blocking.isEmpty()) {
            return;
        }
        String detail = blocking.stream()
                .map(finding -> finding.code()
                        + (finding.subject() == null ? "" : " (" + finding.subject() + ")"))
                .collect(Collectors.joining(", "));
        throw new IllegalStateException(
                "This source system cannot be enabled while " + blocking.size()
                        + (blocking.size() == 1 ? " check is" : " checks are") + " blocking: "
                        + detail + ". Nothing was changed. Resolve them and try again — the "
                        + "readiness screen explains each one in full.");
    }

    /**
     * The state after the call, with every limitation stated rather than implied.
     *
     * <p>Readiness is recomputed here even on a no-op, because the response reports it and a
     * stale verdict beside a live flag is the pairing most likely to be misread.
     */
    private SourceSystemActivationResponse activationResponse(FinSourceSystem source,
                                                              Temple temple, boolean changed) {
        List<ReadinessFinding> findings =
                OnboardingReadinessValidator.validate(snapshot(source, temple));
        int blocking = (int) findings.stream()
                .filter(finding -> finding.severity() == ReadinessStatus.BLOCKED).count();

        return new SourceSystemActivationResponse(
                source.getId(),
                source.getTempleId(),
                temple == null ? null : temple.getName(),
                source.getSystemCode(),
                source.isSyncEnabled(),
                changed,
                OnboardingReadinessValidator.statusOf(findings),
                blocking,
                findings.size() - blocking,
                // Constants, in the same discipline as connectivityVerified. See the response type.
                false,
                false,
                source.isSyncEnabled() ? ENABLED_NOTE : DISABLED_NOTE,
                activationWarnings(source, findings),
                LocalDateTime.now());
    }

    /**
     * Non-blocking things worth knowing at the moment somebody switches a source on.
     *
     * <p>The unsigned-declaration warning is FIN-D-086 honoured without becoming a gate. Readiness
     * deliberately says nothing about approval — making it a finding would change the verdict on
     * configuration that predates this API — but the moment of enabling is exactly when an
     * administrator should be told that the field a temple's revenue will be read from has not
     * been confirmed by anybody who runs the source.
     */
    private List<String> activationWarnings(FinSourceSystem source,
                                            List<ReadinessFinding> findings) {
        List<String> warnings = new ArrayList<>();

        List<String> unapproved = declarations
                .findBySourceSystemIdAndDeletedFalseOrderByMetricAscVersionDesc(source.getId())
                .stream()
                .filter(declaration -> declaration.getEffectiveTo() == null)
                .filter(declaration -> declaration.getApprovedAt() == null)
                .map(declaration -> declaration.getMetric() + " v" + declaration.getVersion())
                .toList();
        if (!unapproved.isEmpty()) {
            warnings.add("Awaiting sign-off: " + String.join(", ", unapproved)
                    + ". These declarations are in force and will be used; nobody has confirmed "
                    + "them against the source. Not a blocker, and not recorded as a readiness "
                    + "finding (FIN-D-086).");
        }

        long readinessWarnings = findings.stream()
                .filter(finding -> finding.severity() == ReadinessStatus.WARNING).count();
        if (readinessWarnings > 0) {
            warnings.add(readinessWarnings + " readiness warning"
                    + (readinessWarnings == 1 ? " was" : "s were")
                    + " present and did not block this. Each is legitimate configuration, but "
                    + "worth reading before this source is relied on.");
        }

        return List.copyOf(warnings);
    }
}

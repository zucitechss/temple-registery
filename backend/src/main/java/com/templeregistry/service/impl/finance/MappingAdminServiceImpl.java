package com.templeregistry.service.impl.finance;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.templeregistry.entity.audit.AuditDataEvent;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.exception.DuplicateResourceException;
import com.templeregistry.exception.EntityNotFoundException;
import com.templeregistry.repository.audit.AuditDataEventRepository;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.JurisdictionGuard;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.finance.mapping.MappingAdminService;
import com.templeregistry.service.finance.pipeline.SourceValueKey;
import com.templeregistry.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The Source Mapper's backend (FIN-054A). See {@link MappingAdminService} for the contract.
 *
 * <h2>Three things this class is careful about</h2>
 *
 * <p><b>Scope is resolved from the server's own data.</b> A caller names a {@code sourceSystemId}
 * and nothing else. The temple, and through it the district, is looked up here; a client-supplied
 * temple id is never accepted, because a request that carries its own answer to "whose data is
 * this" is not a request that has been authorized.
 *
 * <p><b>Audit is written in the caller's transaction.</b> {@code AuditService} exists and would
 * have been the obvious reuse, but it is {@code @Async} with {@code REQUIRES_NEW} and it swallows
 * its own failures — deliberately, because a lost audit line must never fail a temple's
 * declaration. That trade is wrong here: a change to how revenue is classified, recorded nowhere,
 * is worse than a change refused. So this writes {@link AuditDataEvent} through its repository in
 * the same transaction, and a failed audit takes the rule change down with it (FIN-D-063).
 *
 * <p><b>Nothing here writes a financial fact.</b> No repository capable of it is injected. The
 * fields this class can reach are the rule's own; {@code fin_revenue_fact} and {@code
 * fin_stg_revenue} are read-only inputs and staging is not even read except to sample field names.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MappingAdminServiceImpl implements MappingAdminService {

    /**
     * The only mapping type a rule may be created or edited as.
     *
     * <p>{@code MappingType} declares six; {@code RevenueMappingStage} reads one. A screen that
     * accepted a {@code PAYMENT_MODE} rule would let an administrator configure payment mode,
     * see it listed as active, and conclude it had taken effect — when nothing anywhere reads it.
     * Existing rules of the other types stay visible and editable through the list, because
     * hiding the two seeded {@code METAL_TYPE} rules would hide a known defect rather than fix it.
     */
    private static final MappingType WRITABLE_TYPE = MappingType.REVENUE_CATEGORY;

    /**
     * Sort keys a caller may name, mapped to entity properties.
     *
     * <p>An allow-list rather than a pass-through: {@code Sort.by(userInput)} puts a caller's
     * string into a generated ORDER BY, and the set of things worth sorting a rule list by is
     * seven, all known in advance.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "sourceValue", "sourceValue",
            "canonicalValue", "canonicalValue",
            "mappingType", "mappingType",
            "priority", "priority",
            "active", "active",
            "updatedAt", "updatedAt",
            "createdAt", "createdAt");

    /**
     * How many staged rows to read when working out which fields a source emits.
     *
     * <p>ponytail: fixed sample, raise it or replace it with a stored schema fingerprint if a
     * source turns out to emit fields only on rare record types. The distinct keys of a revenue
     * payload stabilise within a handful of rows, and a first historical load has millions.
     */
    private static final int PAYLOAD_SAMPLE_ROWS = 200;

    private final FinMappingRuleRepository rules;
    private final FinSourceSystemRepository sourceSystems;
    private final FinRevenueCategoryRepository categories;
    private final FinStgRevenueMappingRepository decisions;
    private final FinStgRevenueRepository staging;
    private final TempleRepository temples;
    private final AuditDataEventRepository auditEvents;
    private final JurisdictionGuard jurisdictionGuard;
    private final PaginationUtil paginationUtil;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------- reads

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public List<SourceSystemSummaryResponse> listSourceSystems() {
        ScopeHelper.Claims claims = currentClaims();
        List<SourceSystemSummaryResponse> visible = new ArrayList<>();
        for (FinSourceSystem source : sourceSystems.findByDeletedFalse()) {
            Temple temple = temples.findWithFullGeoById(source.getTempleId()).orElse(null);
            if (temple == null || outOfScope(temple, claims)) {
                // Silently dropped rather than refused: this is a list, and telling a caller that
                // a source system exists but is not theirs is the enumeration this avoids.
                continue;
            }
            visible.add(new SourceSystemSummaryResponse(
                    source.getId(), source.getTempleId(), temple.getName(),
                    source.getSystemCode(), source.getSystemName(), !source.isDeleted(),
                    rules.findBySourceSystemIdAndMappingTypeAndActiveTrueAndDeletedFalse(
                            source.getId(), WRITABLE_TYPE).size()));
        }
        return visible;
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public PaginatedResponse<MappingRuleResponse> listRules(Long sourceSystemId,
                                                            MappingType mappingType,
                                                            Boolean active,
                                                            String canonicalValue,
                                                            String search,
                                                            int page,
                                                            int size,
                                                            String sort) {
        FinSourceSystem source = requireInScope(sourceSystemId);
        Set<String> known = knownCategoryCodes();
        Page<FinMappingRule> found = rules.search(
                sourceSystemId, mappingType, active, blankToNull(canonicalValue), blankToNull(search),
                PageRequest.of(Math.max(page, 0), paginationUtil.clampSize(size), sortOf(sort)));
        return PaginatedResponse.of(found.map(rule -> toResponse(rule, source, known)));
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public MappingRuleResponse getRule(Long id) {
        FinMappingRule rule = requireRule(id);
        return toResponse(rule, requireInScope(rule.getSourceSystemId()), knownCategoryCodes());
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public UnresolvedValueResponse listUnresolved(Long sourceSystemId, MappingOutcome outcome) {
        requireInScope(sourceSystemId);
        if (outcome == MappingOutcome.MAPPED) {
            throw new IllegalStateException(
                    "MAPPED is not an unresolved outcome. Ask for UNMAPPED, AMBIGUOUS, "
                            + "NOT_APPLICABLE or INVALID_CONFIGURATION.");
        }
        Long batchId = decisions.findLatestDecidedBatch(sourceSystemId, WRITABLE_TYPE).orElse(null);
        if (batchId == null) {
            // Nothing has been mapped for this source yet. An empty list here means "not measured",
            // not "nothing unresolved", and the null batch id is how a caller tells the two apart.
            return new UnresolvedValueResponse(sourceSystemId, null, outcome, null, List.of());
        }

        List<UnresolvedValueResponse.UnresolvedValue> values =
                decisions.summariseByFieldAndSourceValue(batchId, WRITABLE_TYPE, outcome).stream()
                        .map(row -> new UnresolvedValueResponse.UnresolvedValue(
                                (String) row[0],
                                (String) row[1],
                                ((Number) row[2]).longValue(),
                                (LocalDateTime) row[3]))
                        .toList();
        LocalDateTime observedAt = values.stream()
                .map(UnresolvedValueResponse.UnresolvedValue::lastSeenAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new UnresolvedValueResponse(sourceSystemId, batchId, outcome, observedAt, values);
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public NamespaceCatalogueResponse namespaces(Long sourceSystemId) {
        requireInScope(sourceSystemId);
        NamespaceCatalogueResponse observed = namespaceCatalogue(sourceSystemId);
        Set<String> inUse = rules.findBySourceSystemIdAndDeletedFalse(sourceSystemId).stream()
                .map(rule -> SourceValueKey.parse(rule.getSourceValue()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(SourceValueKey::namespace)
                .collect(Collectors.toCollection(TreeSet::new));
        return new NamespaceCatalogueResponse(sourceSystemId, observed.observed(),
                List.copyOf(inUse), observed.sampledRows());
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_READ_FINANCE_CONFIG)
    @Transactional(readOnly = true)
    public List<CanonicalValueResponse> canonicalValues() {
        return categories.findByActiveTrueAndDeletedFalse().stream()
                .sorted(Comparator.comparing(FinRevenueCategory::getDisplayOrder)
                        .thenComparing(FinRevenueCategory::getCategoryCode))
                .map(c -> new CanonicalValueResponse(c.getCategoryCode(), c.getCategoryName(),
                        c.getDescription(), c.getDisplayOrder()))
                .toList();
    }

    // --------------------------------------------------------------- writes

    @Override
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    @Transactional
    public MappingRuleMutationResponse create(CreateMappingRuleRequest request) {
        FinSourceSystem source = requireInScope(request.getSourceSystemId());
        requireWritableType(request.getMappingType());

        String storedValue = compose(request.getNamespace(), request.getSourceValue());
        requireKnownCanonicalValue(request.getCanonicalValue());
        refuseDuplicate(source.getId(), request.getMappingType(), storedValue, null);

        FinMappingRule rule = rules.save(FinMappingRule.builder()
                .sourceSystemId(source.getId())
                .mappingType(request.getMappingType())
                .sourceValue(storedValue)
                .sourceLabel(request.getSourceLabel())
                .canonicalValue(request.getCanonicalValue())
                .priority(request.getPriority() == null ? 100 : request.getPriority())
                .active(request.getActive() == null || request.getActive())
                .notes(request.getNotes())
                .build());

        audit("CREATE", rule, "created " + describe(rule));
        log.info("[FinanceMapping] Rule {} created for source {} [{}] -> {}",
                rule.getId(), source.getId(), storedValue, rule.getCanonicalValue());
        return mutation(rule, source);
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    @Transactional
    public MappingRuleMutationResponse update(Long id, UpdateMappingRuleRequest request) {
        FinMappingRule rule = requireRule(id);
        FinSourceSystem source = requireInScope(rule.getSourceSystemId());
        requireWritableType(rule.getMappingType());
        requireCurrentVersion(rule, request.getVersion());

        String storedValue = compose(request.getNamespace(), request.getSourceValue());
        requireKnownCanonicalValue(request.getCanonicalValue());
        refuseDuplicate(rule.getSourceSystemId(), rule.getMappingType(), storedValue, rule.getId());

        String before = describe(rule);
        rule.setSourceValue(storedValue);
        rule.setSourceLabel(request.getSourceLabel());
        rule.setCanonicalValue(request.getCanonicalValue());
        rule.setPriority(request.getPriority() == null ? rule.getPriority() : request.getPriority());
        rule.setActive(request.getActive() == null ? rule.isActive() : request.getActive());
        rule.setNotes(request.getNotes());
        rules.saveAndFlush(rule);

        audit("UPDATE", rule, "was " + before + "; now " + describe(rule));
        log.info("[FinanceMapping] Rule {} updated: {} -> {}", rule.getId(), before, describe(rule));
        return mutation(rule, source);
    }

    @Override
    @PreAuthorize(RoleConstants.CAN_ACT_DC)
    @Transactional
    public MappingRuleMutationResponse setActive(Long id, MappingRuleStatusRequest request) {
        FinMappingRule rule = requireRule(id);
        FinSourceSystem source = requireInScope(rule.getSourceSystemId());
        requireCurrentVersion(rule, request.getVersion());

        // No writable-type check. Deactivating a rule of an inert type is the one useful thing that
        // can be done with one, and refusing it would leave the two unreachable METAL_TYPE rules
        // permanently listed as active.
        rule.setActive(request.getActive());
        rules.saveAndFlush(rule);

        audit(request.getActive() ? "ACTIVATE" : "DEACTIVATE", rule, describe(rule));
        log.info("[FinanceMapping] Rule {} {}", rule.getId(),
                request.getActive() ? "activated" : "deactivated");
        return mutation(rule, source);
    }

    // ------------------------------------------------------------ authority

    /**
     * The source system behind an id, or a 404 — for absence and for out of scope alike.
     *
     * <p>Both answers are the same answer on purpose. A caller who can tell "no such source system"
     * from "not yours" can enumerate every temple's integrations one id at a time.
     */
    private FinSourceSystem requireInScope(Long sourceSystemId) {
        if (sourceSystemId == null) {
            throw new IllegalStateException("sourceSystemId is required.");
        }
        FinSourceSystem source = sourceSystems.findById(sourceSystemId)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException("Source system", sourceSystemId));
        Temple temple = temples.findWithFullGeoById(source.getTempleId())
                .orElseThrow(() -> new EntityNotFoundException("Temple", source.getTempleId()));
        ScopeHelper.Claims claims = currentClaims();
        if (outOfScope(temple, claims)) {
            throw new EntityNotFoundException("Source system", sourceSystemId);
        }
        return source;
    }

    /**
     * Whether this caller's jurisdiction excludes this temple.
     *
     * <p>Only {@code DISTRICT_COLLECTOR} and {@code DC_STAFF} are district-scoped, which is the
     * same set {@code JurisdictionGuard.assertSameDistrict} scopes. The heavier
     * {@code assertDistrictScope} is used for the traversal it does — temple to hobli to taluk to
     * district, with the flat-scalar fallback — but only for those two roles: it treats a null
     * {@code districtId} on any other role as a corrupted token, and an {@code AUDITOR} is
     * statewide and legitimately carries none.
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

    private ScopeHelper.Claims currentClaims() {
        return ScopeHelper.Claims.fromContext();
    }

    // ----------------------------------------------------------- validation

    private void requireWritableType(MappingType type) {
        if (type != WRITABLE_TYPE) {
            throw new IllegalStateException(
                    "Only " + WRITABLE_TYPE + " rules can be created or edited. Nothing in the "
                            + "pipeline reads " + type + " rules, so saving one would configure "
                            + "something that never takes effect.");
        }
    }

    /** Composes the stored value, refusing anything the engine could not later read back. */
    private String compose(String namespace, String value) {
        try {
            return SourceValueKey.compose(namespace, value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(malformed.getMessage(), malformed);
        }
    }

    private void requireKnownCanonicalValue(String canonicalValue) {
        Set<String> known = knownCategoryCodes();
        if (!known.contains(canonicalValue)) {
            throw new IllegalStateException(
                    "[" + canonicalValue + "] is not an active revenue category. A rule naming one "
                            + "that does not exist is accepted by the database and fails at mapping "
                            + "time, on a batch, long after whoever wrote it has gone. Known values: "
                            + new TreeSet<>(known));
        }
    }

    private void refuseDuplicate(Long sourceSystemId, MappingType type, String storedValue,
                                 Long excludingId) {
        rules.findConflicting(sourceSystemId, type, storedValue, excludingId)
                .ifPresent(existing -> {
                    throw new DuplicateResourceException(
                            "Rule " + existing.getId() + " already maps [" + storedValue + "] to ["
                                    + existing.getCanonicalValue() + "] for this source system. "
                                    + "One source value has one meaning; edit that rule instead.");
                });
    }

    /**
     * Refuses an edit written against a version that is no longer current.
     *
     * <p>Checked here rather than left to Hibernate because the race this feature actually has is
     * not two simultaneous commits — it is two administrators who opened the same rule list
     * minutes apart. The entity loaded in this transaction is fresh, so Hibernate's own check
     * would pass and the earlier reader's change would vanish without either of them being told.
     */
    private void requireCurrentVersion(FinMappingRule rule, Integer expected) {
        if (!java.util.Objects.equals(rule.getVersion(), expected)) {
            throw new OptimisticLockingFailureException(
                    "Rule " + rule.getId() + " has changed since it was loaded (version "
                            + rule.getVersion() + ", you sent " + expected
                            + "). Reload it and reapply your change.");
        }
    }

    // ---------------------------------------------------------------- audit

    /**
     * Records the change beside it, in the same transaction.
     *
     * <p>No try/catch. If this write fails the rule change is rolled back, which is the intended
     * behaviour and the reason this does not go through {@code AuditService}.
     */
    private void audit(String action, FinMappingRule rule, String detail) {
        ScopeHelper.Claims claims = currentClaims();
        auditEvents.save(AuditDataEvent.builder()
                .actorId(claims.userId())
                .actorRole(claims.role())
                .action(action)
                .entityType("FIN_MAPPING_RULE")
                .entityId(rule.getId())
                .detail("sourceSystemId=" + rule.getSourceSystemId() + " " + detail)
                .build());
    }

    /** What a rule says, for an audit line. Configuration only — no temple data. */
    private String describe(FinMappingRule rule) {
        return rule.getMappingType() + " [" + rule.getSourceValue() + "] -> "
                + rule.getCanonicalValue() + " priority=" + rule.getPriority()
                + " active=" + rule.isActive();
    }

    // -------------------------------------------------------------- mapping

    private MappingRuleMutationResponse mutation(FinMappingRule rule, FinSourceSystem source) {
        return MappingRuleMutationResponse.of(
                toResponse(rule, source, knownCategoryCodes()), warningsFor(rule));
    }

    /**
     * What is legal but probably wrong about a saved rule.
     *
     * <p>Only the namespace, and only as a warning. The set of fields a source emits is not
     * declared anywhere — the connector chooses them — so the only evidence is what has been
     * staged, and a source system that has never run has staged nothing. Refusing an unrecognised
     * namespace would make a source impossible to configure before its first extraction, and
     * would start refusing correct namespaces the moment staging is purged. Accepting it silently
     * is the failure in the other direction, so it is accepted and said out loud.
     */
    private List<String> warningsFor(FinMappingRule rule) {
        Optional<SourceValueKey> key = SourceValueKey.parse(rule.getSourceValue());
        if (key.isEmpty()) {
            return List.of();
        }
        NamespaceCatalogueResponse catalogue = namespaceCatalogue(rule.getSourceSystemId());
        if (catalogue.sampledRows() == 0) {
            return List.of("No records have been staged for this source system yet, so it cannot "
                    + "be confirmed that it emits a field called [" + key.get().namespace()
                    + "]. A rule whose field name the source does not emit never matches anything.");
        }
        if (!catalogue.observed().contains(key.get().namespace())) {
            return List.of("No staged record from this source carries a field called ["
                    + key.get().namespace() + "]. This rule will never match anything until it "
                    + "does. Fields seen in the most recent " + catalogue.sampledRows()
                    + " staged records: " + catalogue.observed());
        }
        return List.of();
    }

    /** The catalogue without the scope check, for callers that have already made one. */
    private NamespaceCatalogueResponse namespaceCatalogue(Long sourceSystemId) {
        List<String> payloads = staging.samplePayloads(
                sourceSystemId, PageRequest.of(0, PAYLOAD_SAMPLE_ROWS));
        Set<String> observed = new TreeSet<>();
        for (String payload : payloads) {
            observed.addAll(fieldNamesOf(payload));
        }
        return new NamespaceCatalogueResponse(
                sourceSystemId, List.copyOf(observed), List.of(), payloads.size());
    }

    /** The scalar field names of one staged payload. Containers are skipped, as in mapping. */
    private Set<String> fieldNamesOf(String rawJson) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode payload = objectMapper.readTree(rawJson);
            if (payload == null || !payload.isObject()) {
                return names;
            }
            for (Map.Entry<String, JsonNode> field : payload.properties()) {
                if (!field.getValue().isContainerNode()) {
                    names.add(field.getKey());
                }
            }
        } catch (Exception unreadable) {
            log.debug("[FinanceMapping] A staged payload could not be read while listing field names");
        }
        return names;
    }

    private MappingRuleResponse toResponse(FinMappingRule rule, FinSourceSystem source,
                                           Set<String> knownCategories) {
        Optional<SourceValueKey> key = SourceValueKey.parse(rule.getSourceValue());
        return new MappingRuleResponse(
                rule.getId(),
                rule.getSourceSystemId(),
                source.getTempleId(),
                rule.getMappingType(),
                key.map(SourceValueKey::namespace).orElse(null),
                key.map(SourceValueKey::value).orElse(null),
                rule.getSourceValue(),
                key.isPresent(),
                rule.getSourceLabel(),
                rule.getCanonicalValue(),
                knownCategories.contains(rule.getCanonicalValue()),
                rule.getPriority(),
                rule.isActive(),
                rule.getNotes(),
                rule.getVersion(),
                rule.getCreatedBy(),
                rule.getCreatedAt(),
                rule.getUpdatedBy(),
                rule.getUpdatedAt());
    }

    private FinMappingRule requireRule(Long id) {
        return rules.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new EntityNotFoundException("Mapping rule", id));
    }

    private Set<String> knownCategoryCodes() {
        return categories.findByActiveTrueAndDeletedFalse().stream()
                .map(FinRevenueCategory::getCategoryCode)
                .collect(Collectors.toSet());
    }

    /**
     * Turns {@code priority,desc} into a {@code Sort}, refusing anything not allow-listed.
     *
     * <p>Ties break on id so that paging is stable: two rules of equal priority returned in a
     * different order on page two than on page one would show one of them twice and neither on
     * the page it belonged to.
     */
    private Sort sortOf(String sort) {
        Sort byId = Sort.by(Sort.Direction.ASC, "id");
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "priority").and(byId);
        }
        String[] parts = sort.split(",", 2);
        String property = SORTABLE.get(parts[0].trim());
        if (property == null) {
            throw new IllegalStateException("[" + parts[0].trim() + "] is not a sortable field. "
                    + "Sortable: " + new TreeSet<>(SORTABLE.keySet()));
        }
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(direction, property).and(byId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

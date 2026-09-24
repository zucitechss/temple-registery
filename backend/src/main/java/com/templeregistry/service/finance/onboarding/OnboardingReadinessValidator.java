package com.templeregistry.service.finance.onboarding;

import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.service.finance.pipeline.RevenueField;
import com.templeregistry.service.finance.pipeline.SourceValueKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether a source system's configuration is coherent enough to be switched on (FIN-140, FIN-032
 * Class A).
 *
 * <h2>What this can and cannot establish</h2>
 *
 * <p>Every check reads registry-owned tables only. None of them contacts a temple, resolves a
 * credential or asks a connector anything, because the registry runtime holds no connector bean
 * and no credential provider and a test asserts it never will (ADR-001,
 * {@code RegistryRuntimeContextTest}).
 *
 * <p>So this answers "is the configuration internally coherent", never "does the source work".
 * The distinction is not pedantry: a configuration can pass every check here and still fail on
 * its first run because the connector was never deployed, and an administrator who reads
 * {@code READY} as "connected" has been misled by us. The response states the boundary explicitly
 * rather than leaving it to be inferred from this javadoc.
 *
 * <h2>Why the valuable checks are the mapping ones</h2>
 *
 * <p>{@code MAPPING_CANONICAL_UNKNOWN} and {@code MAPPING_RULE_MALFORMED} are failures the mapping
 * engine already detects — as {@code INVALID_CONFIGURATION} and a rule that silently never
 * matches, per row, during a run, long after whoever wrote the rule has gone. Detecting them here
 * costs one set lookup and moves the discovery to the moment the configuration is still a draft.
 *
 * <h2>Pure</h2>
 *
 * <p>Static methods over a value record. No Spring, no repository, no clock, no security context.
 */
public final class OnboardingReadinessValidator {

    /**
     * The mapping type the revenue pipeline actually reads.
     *
     * <p>Matches {@code MappingAdminServiceImpl.WRITABLE_TYPE}. Rules of any other type are
     * reported as inert rather than hidden: the two seeded {@code METAL_TYPE} rules are a known
     * defect, and a validator that ignored them would conceal it.
     */
    private static final MappingType READ_BY_PIPELINE = MappingType.REVENUE_CATEGORY;

    private OnboardingReadinessValidator() {
    }

    /**
     * Every finding, blocking ones first, then by code.
     *
     * <p>Order is deterministic so a test can assert a list and a screen does not reshuffle
     * between polls.
     */
    public static List<ReadinessFinding> validate(OnboardingConfiguration config) {
        List<ReadinessFinding> findings = new ArrayList<>();

        checkTemple(config, findings);
        checkSingleSourcePerTemple(config, findings);
        checkCapabilities(config, findings);
        checkSourceOfTruth(config, findings);
        checkMappingRules(config, findings);
        checkCredentialAlias(config, findings);

        findings.sort(Comparator
                .comparing((ReadinessFinding f) -> f.severity() == ReadinessStatus.BLOCKED ? 0 : 1)
                .thenComparing(ReadinessFinding::code)
                .thenComparing(f -> f.subject() == null ? "" : f.subject()));
        return List.copyOf(findings);
    }

    /** The worst finding present, or {@code READY} when there are none. */
    public static ReadinessStatus statusOf(List<ReadinessFinding> findings) {
        ReadinessStatus status = ReadinessStatus.READY;
        for (ReadinessFinding finding : findings) {
            status = status.worseOf(finding.severity());
        }
        return status;
    }

    // ------------------------------------------------------------------ temple

    private static void checkTemple(OnboardingConfiguration config, List<ReadinessFinding> out) {
        if (!config.templeExists()) {
            out.add(ReadinessFinding.blocking("TEMPLE_MISSING", null,
                    "This source system names a temple that no longer exists in the registry. "
                            + "Every figure it loaded would be attributed to nothing."));
        }
    }

    /**
     * D9, enforced rather than assumed.
     *
     * <p>{@code uk_ftc_temple_capability} is {@code (temple_id, capability)} and the capability
     * read path filters by temple alone, so a second source for one temple cannot declare a
     * capability the first already declared, and no code anywhere decides whose answer wins when
     * two sources disagree. Registration refuses the second source; this catches rows that
     * predate the rule, so the limitation is visible where it bites instead of surfacing as a
     * constraint violation during a run.
     */
    private static void checkSingleSourcePerTemple(OnboardingConfiguration config,
                                                    List<ReadinessFinding> out) {
        if (config.sourceSystemsForTemple() > 1) {
            out.add(ReadinessFinding.blocking("MULTIPLE_SOURCE_SYSTEMS_FOR_TEMPLE", null,
                    "This temple has " + config.sourceSystemsForTemple() + " source systems. The "
                            + "capability and service tables are unique per temple, not per source, "
                            + "so two sources cannot both declare what this temple can answer and "
                            + "nothing decides which one is right (open decision D9)."));
        }
    }

    // ------------------------------------------------------------ capabilities

    private static void checkCapabilities(OnboardingConfiguration config,
                                          List<ReadinessFinding> out) {
        if (config.capabilities().isEmpty()) {
            out.add(ReadinessFinding.blocking("NO_CAPABILITY_DECLARED", null,
                    "No capability has been declared. Every metric on this temple's dashboard "
                            + "would read NOT_AVAILABLE with no reason beside it, which is the "
                            + "one thing the availability model exists to prevent."));
            return;
        }

        for (OnboardingConfiguration.CapabilityDeclaration declaration : config.capabilities()) {
            String subject = declaration.capability().name();

            if (!declaration.reportable() && isBlank(declaration.reason())) {
                out.add(ReadinessFinding.blocking("CAPABILITY_REASON_MISSING", subject,
                        "This capability is " + declaration.availability() + " but carries no "
                                + "reason. The reason is shown to a reader verbatim where a figure "
                                + "would otherwise appear, so an empty one renders an empty "
                                + "explanation."));
            }

            if (declaration.coverageFrom() != null && declaration.coverageTo() != null
                    && declaration.coverageTo().isBefore(declaration.coverageFrom())) {
                out.add(ReadinessFinding.blocking("COVERAGE_WINDOW_INVERTED", subject,
                        "Coverage ends (" + declaration.coverageTo() + ") before it begins ("
                                + declaration.coverageFrom() + "), so the window describes no "
                                + "period at all."));
            }

            if (declaration.reportable() && declaration.coverageFrom() == null) {
                out.add(ReadinessFinding.warning("COVERAGE_START_MISSING", subject,
                        "This capability is reportable but declares no coverage start, so the "
                                + "platform cannot state how far back its figures go and a reader "
                                + "may take a partial history for a complete one."));
            }

            if (!declaration.reportable()
                    && (declaration.coverageFrom() != null || declaration.coverageTo() != null)) {
                out.add(ReadinessFinding.warning("COVERAGE_DECLARED_WHEN_UNAVAILABLE", subject,
                        "This capability is " + declaration.availability() + " yet declares a "
                                + "coverage window. A window over data the source does not record "
                                + "describes nothing."));
            }
        }

        checkUndeclaredCapabilities(config, out);
    }

    /**
     * Capabilities nobody has said anything about — a warning, and the one that tells an
     * administrator what work is left (FIN-140-B).
     *
     * <p>An undeclared capability and one declared {@code NOT_AVAILABLE} look identical to every
     * reader downstream, and they are not the same statement: the second says the source does not
     * record this, the first says nobody has looked. `V111` declared all nineteen for the first
     * onboarded source precisely so that *"'not declared' never has to be guessed at"*, and this is
     * that intent enforced rather than left as a convention one migration happened to follow.
     *
     * <p>A warning and not a blocker, because a half-configured source is a legitimate state to
     * leave overnight, and because the dashboard already renders an absent capability honestly. One
     * finding listing all of them rather than one per capability: nineteen separate warnings on a
     * newly registered source would bury the blocking findings underneath them.
     */
    private static void checkUndeclaredCapabilities(OnboardingConfiguration config,
                                                     List<ReadinessFinding> out) {
        Set<FinanceCapability> declared = new LinkedHashSet<>();
        config.capabilities().forEach(declaration -> declared.add(declaration.capability()));

        Set<String> missing = new TreeSet<>();
        for (FinanceCapability capability : FinanceCapability.values()) {
            if (!declared.contains(capability)) {
                missing.add(capability.name());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        out.add(ReadinessFinding.warning("CAPABILITY_NOT_DECLARED", missing.size() + " of "
                + FinanceCapability.values().length,
                "Nothing has been declared for " + missing + ". An undeclared capability and one "
                        + "declared NOT_AVAILABLE look the same to a reader, but they are different "
                        + "statements — the second says the source does not record it, the first "
                        + "says nobody has checked. Declare each one, using NOT_APPLICABLE where "
                        + "the question does not arise for this temple."));
    }

    // --------------------------------------------------------- source of truth

    /**
     * Required declarations, taken from {@link RevenueField} rather than listed here.
     *
     * <p>The pipeline already names which metrics it cannot run without, and it is the authority:
     * normalization refuses a batch when a required declaration is missing. Restating the list
     * would create a second one to keep in step, and the failure mode of the two disagreeing is a
     * source that passes onboarding and rejects every row on its first run.
     *
     * <p>Only checked when revenue is actually reportable. A source that declares revenue
     * {@code NOT_AVAILABLE} — one that supplies only expenses, say — needs none of these, and
     * demanding them would make such a source impossible to onboard.
     */
    private static void checkSourceOfTruth(OnboardingConfiguration config,
                                            List<ReadinessFinding> out) {
        if (!revenueIsReportable(config)) {
            return;
        }
        for (RevenueField field : RevenueField.values()) {
            if (field.required() && !config.declaredMetrics().contains(field.metric())) {
                out.add(ReadinessFinding.blocking("SOURCE_OF_TRUTH_MISSING", field.metric(),
                        "Revenue is reportable for this source but no declaration in force names "
                                + "the field carrying " + field.metric() + ". Normalization refuses "
                                + "a batch without it, so every extracted row would be rejected."));
            }
        }
    }

    // ----------------------------------------------------------- mapping rules

    private static void checkMappingRules(OnboardingConfiguration config,
                                           List<ReadinessFinding> out) {
        List<OnboardingConfiguration.MappingRuleView> active = config.mappingRules().stream()
                .filter(OnboardingConfiguration.MappingRuleView::active)
                .toList();

        List<OnboardingConfiguration.MappingRuleView> revenueRules = active.stream()
                .filter(rule -> rule.mappingType() == READ_BY_PIPELINE)
                .toList();

        if (revenueIsReportable(config) && revenueRules.isEmpty()) {
            out.add(ReadinessFinding.blocking("NO_ACTIVE_MAPPING_RULE", null,
                    "Revenue is reportable but no active " + READ_BY_PIPELINE + " rule exists. "
                            + "Every source value would route to UNMAPPED, so the temple's income "
                            + "would be loaded without ever being classified."));
        }

        for (OnboardingConfiguration.MappingRuleView rule : revenueRules) {
            String subject = "rule " + rule.id();

            if (SourceValueKey.parse(rule.storedValue()).isEmpty()) {
                out.add(ReadinessFinding.blocking("MAPPING_RULE_MALFORMED", subject,
                        "[" + rule.storedValue() + "] cannot be read as <field>:<value>, so this "
                                + "rule names no staged field and can never match anything. It "
                                + "will show as active for ever while doing nothing."));
            }

            if (!config.activeCanonicalValues().contains(rule.canonicalValue())) {
                out.add(ReadinessFinding.blocking("MAPPING_CANONICAL_UNKNOWN", subject,
                        "[" + rule.canonicalValue() + "] is not an active revenue category. The "
                                + "database accepts this rule and the mapping engine fails on it "
                                + "at run time, per row. Known values: "
                                + new TreeSet<>(config.activeCanonicalValues())));
            }
        }

        checkPossibleAmbiguity(revenueRules, out);
        checkInertRules(active, out);
    }

    /**
     * Rules that could resolve ambiguously — reported as a warning, deliberately.
     *
     * <p>The engine resolves by priority and calls it {@code AMBIGUOUS} when two matches tie. Two
     * rules tie only if one record carries both of their staged fields, and no registry table
     * records which fields a source emits together, so this cannot be established here.
     *
     * <p>Making it blocking would refuse the first onboarded source's own configuration, whose
     * four {@code SANNIDHI} and two {@code STREAM} rules sit at equal priority precisely because
     * — as the migration that set those priorities records — they do not overlap. A check that
     * refuses a configuration its own author reasoned through and documented is a false positive,
     * and false blockers are how a validator gets switched off.
     */
    private static void checkPossibleAmbiguity(List<OnboardingConfiguration.MappingRuleView> rules,
                                                List<ReadinessFinding> out) {
        Map<Integer, Set<String>> namespacesByPriority = new LinkedHashMap<>();
        for (OnboardingConfiguration.MappingRuleView rule : rules) {
            SourceValueKey.parse(rule.storedValue()).ifPresent(key ->
                    namespacesByPriority
                            .computeIfAbsent(rule.priority(), p -> new LinkedHashSet<>())
                            .add(key.namespace()));
        }
        namespacesByPriority.forEach((priority, namespaces) -> {
            if (namespaces.size() > 1) {
                out.add(ReadinessFinding.warning("MAPPING_RULE_POSSIBLY_AMBIGUOUS",
                        "priority " + priority,
                        "Rules reading " + new TreeSet<>(namespaces) + " all sit at priority "
                                + priority + ". If one record ever carries more than one of those "
                                + "fields the engine cannot choose between them and rejects the "
                                + "row rather than guessing. Legitimate when the fields never "
                                + "appear together; give the more specific rule a higher priority "
                                + "if they can."));
            }
        });
    }

    private static void checkInertRules(List<OnboardingConfiguration.MappingRuleView> active,
                                         List<ReadinessFinding> out) {
        Set<MappingType> inert = new TreeSet<>();
        for (OnboardingConfiguration.MappingRuleView rule : active) {
            if (rule.mappingType() != READ_BY_PIPELINE) {
                inert.add(rule.mappingType());
            }
        }
        for (MappingType type : inert) {
            out.add(ReadinessFinding.warning("MAPPING_RULE_INERT_TYPE", type.name(),
                    "Active " + type + " rules exist, but nothing in the pipeline reads them. They "
                            + "are listed as active and take no effect."));
        }
    }

    // ------------------------------------------------------------- credentials

    /**
     * A missing credential alias is a warning, not a blocker.
     *
     * <p>Whether one is needed depends on the connector mechanism, and the registry cannot know:
     * a file drop or a push agent may legitimately have none. What it can say is that if the
     * connector does resolve a credential, an absent alias fails at extraction time.
     */
    private static void checkCredentialAlias(OnboardingConfiguration config,
                                              List<ReadinessFinding> out) {
        if (!config.credentialRefSet()) {
            out.add(ReadinessFinding.warning("CREDENTIAL_REF_MISSING", null,
                    "No credential alias is set. If this source's connector needs a credential, "
                            + "the worker has no key to look one up by and extraction will fail. "
                            + "Expected for a push or file-drop source that needs none."));
        }
    }

    // ------------------------------------------------------------------ shared

    /**
     * Whether declaring this capability reportable makes further configuration mandatory.
     *
     * <p>Public because the capability catalogue reports it to clients, and a screen that restated
     * which capability pulls source-of-truth declarations and mapping rules in would be a second
     * copy of a rule enforced here. One predicate, both callers.
     */
    public static boolean drivesRevenueRequirements(FinanceCapability capability) {
        return capability == FinanceCapability.REVENUE;
    }

    private static boolean revenueIsReportable(OnboardingConfiguration config) {
        return config.capabilities().stream()
                .anyMatch(declaration -> drivesRevenueRequirements(declaration.capability())
                        && declaration.reportable());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

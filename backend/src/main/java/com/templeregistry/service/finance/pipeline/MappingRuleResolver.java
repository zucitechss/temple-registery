package com.templeregistry.service.finance.pipeline;

import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Decides what one staged source value means canonically, from configuration alone.
 *
 * <h2>Where the source vocabulary is, and is not</h2>
 *
 * <p>ADR-004 puts the line here: which rows and which columns is connector code, what a value
 * means is configuration. This class is the second half, and it holds no source vocabulary at
 * all — not a table name, not a column name, not a seva code. Everything it knows comes from
 * {@code fin_mapping_rule} rows handed to its constructor.
 *
 * <h2>How a rule finds its value without knowing any source schema</h2>
 *
 * <p>A rule's {@code source_value} is namespaced: {@code SANNIDHI:DS}, {@code SEVA_CODE:430}
 * (FIN-D-015). <b>The namespace is the name of the staged field the rule reads.</b> So the set
 * of fields this resolver consults is derived from the rules themselves — add a rule in a new
 * namespace and a new field starts being read, with no code change and nothing here learning
 * a temple's schema. The connector's side of that bargain is to emit its payload under those
 * logical names; it is the one place the two vocabularies have to agree.
 *
 * <h2>Determinism</h2>
 *
 * <p>Highest {@code priority} wins. Two or more winners at the same priority produce
 * {@link MappingOutcome#AMBIGUOUS} and no canonical value, because taking whichever row the
 * database returned first would make a temple's published revenue depend on a query plan.
 *
 * <p>Values are matched exactly. Nothing is trimmed, case-folded or otherwise adjusted to
 * find a match: a value the source pads or capitalises differently is a real difference, and
 * surfacing it as unmapped is how somebody finds out. Silently absorbing it would be the kind
 * of helpful guess this pipeline exists to avoid.
 *
 * <p>Immutable after construction and safe for concurrent use.
 */
public final class MappingRuleResolver {

    /** Separates a rule's field namespace from the value it matches. */
    static final char NAMESPACE_SEPARATOR = ':';

    private final MappingType mappingType;
    /** Namespaced key -> rules keyed on it. A list, because several may share a key. */
    private final Map<String, List<FinMappingRule>> rulesByKey;
    private final Set<String> readableFields;
    private final Set<String> knownCanonicalValues;
    private final List<String> configurationProblems;

    /**
     * @param mappingType          which question these rules answer
     * @param rules                active rules for one source system and this mapping type
     * @param knownCanonicalValues the canonical vocabulary a rule is allowed to name, so a rule
     *                             naming something that does not exist is caught here rather
     *                             than as a constraint violation at load
     */
    public MappingRuleResolver(MappingType mappingType,
                               Collection<FinMappingRule> rules,
                               Set<String> knownCanonicalValues) {
        this.mappingType = Objects.requireNonNull(mappingType, "mappingType is required");
        this.knownCanonicalValues = Set.copyOf(knownCanonicalValues);

        List<String> problems = new ArrayList<>();
        Set<String> fields = new LinkedHashSet<>();
        Map<String, List<FinMappingRule>> byKey = new java.util.LinkedHashMap<>();

        for (FinMappingRule rule : rules == null ? List.<FinMappingRule>of() : rules) {
            String sourceValue = rule.getSourceValue();
            int separator = sourceValue == null ? -1 : sourceValue.indexOf(NAMESPACE_SEPARATOR);

            if (separator <= 0 || separator == sourceValue.length() - 1) {
                // Nothing to read it from. Recorded rather than ignored: a rule that can never
                // fire is a mistake somebody should be told about, not a silent no-op.
                problems.add("rule " + rule.getId() + " has source_value [" + sourceValue
                        + "], which names no field. A " + mappingType
                        + " rule must be written as <field>" + NAMESPACE_SEPARATOR + "<value>");
                continue;
            }

            fields.add(sourceValue.substring(0, separator));
            byKey.computeIfAbsent(sourceValue, k -> new ArrayList<>()).add(rule);
        }

        this.rulesByKey = Map.copyOf(byKey);
        this.readableFields = new LinkedHashSet<>(fields);
        this.configurationProblems = List.copyOf(problems);
    }

    /** The staged field names these rules read, derived entirely from the rules. */
    public Set<String> readableFields() {
        return Set.copyOf(readableFields);
    }

    /** Rules that can never fire. Empty for a well-formed rule set. */
    public List<String> configurationProblems() {
        return configurationProblems;
    }

    /** Whether any rule in this set is capable of matching anything. */
    public boolean isUsable() {
        return !rulesByKey.isEmpty();
    }

    /**
     * Resolves one staged record.
     *
     * @param stagedFields the record's fields as staged, under the connector's own names
     */
    public Decision resolve(Map<String, String> stagedFields) {
        Map<String, String> fields = stagedFields == null ? Map.of() : stagedFields;

        // Candidates, in a stable order so that an ambiguous message reads the same every run.
        List<Candidate> candidates = new ArrayList<>();
        String firstFieldSeen = null;
        boolean anyFieldPresent = false;

        for (String field : readableFields) {
            if (!fields.containsKey(field)) {
                continue;
            }
            anyFieldPresent = true;
            String value = fields.get(field);
            if (value == null || value.isBlank()) {
                // Present but empty. The source recorded nothing here, which is a measurement,
                // not a value to look up and not a reason to guess.
                if (firstFieldSeen == null) {
                    firstFieldSeen = field;
                }
                continue;
            }
            firstFieldSeen = firstFieldSeen == null ? field : firstFieldSeen;
            candidates.add(new Candidate(field, value, field + NAMESPACE_SEPARATOR + value));
        }

        if (candidates.isEmpty()) {
            String reason = anyFieldPresent
                    ? "the record carries " + describe(readableFields) + " but with no value, so "
                            + "there is nothing to map; the source recorded no " + mappingType
                    : "the record carries none of " + describe(readableFields)
                            + ", the fields this source's " + mappingType + " rules read";
            return new Decision(MappingOutcome.NOT_APPLICABLE, firstFieldSeen, null,
                    null, null, null, reason);
        }

        List<Matched> matches = candidates.stream()
                .flatMap(c -> rulesByKey.getOrDefault(c.key(), List.of()).stream()
                        .map(rule -> new Matched(c, rule)))
                .toList();

        if (matches.isEmpty()) {
            Candidate unknown = candidates.get(0);
            String reason = "no " + mappingType + " rule matches [" + unknown.key()
                    + "]. The record is real revenue whose kind is not yet established; add a "
                    + "mapping rule for this value rather than letting it be classified by default";
            return new Decision(MappingOutcome.UNMAPPED, unknown.field(), unknown.value(),
                    FinMappingRule.UNMAPPED, null, null, reason);
        }

        int topPriority = matches.stream()
                .mapToInt(m -> m.rule().getPriority())
                .max()
                .orElseThrow();
        List<Matched> winners = matches.stream()
                .filter(m -> m.rule().getPriority() == topPriority)
                .toList();

        if (winners.size() > 1) {
            String competing = winners.stream()
                    .map(m -> m.candidate().key() + " -> " + m.rule().getCanonicalValue()
                            + " (rule " + m.rule().getId() + ")")
                    .sorted()
                    .collect(Collectors.joining(", "));
            Candidate first = winners.stream()
                    .map(Matched::candidate)
                    .min(Comparator.comparing(Candidate::key))
                    .orElseThrow();
            String reason = "several " + mappingType + " rules match at priority " + topPriority
                    + " and the configuration does not say which wins: " + competing
                    + ". Give the more specific rule a higher priority";
            return new Decision(MappingOutcome.AMBIGUOUS, first.field(), first.value(),
                    null, null, topPriority, reason);
        }

        Matched winner = winners.get(0);
        String canonical = winner.rule().getCanonicalValue();

        if (!knownCanonicalValues.contains(canonical)) {
            String reason = "rule " + winner.rule().getId() + " maps [" + winner.candidate().key()
                    + "] to [" + canonical + "], which is not a canonical " + mappingType
                    + " value. Known values: " + new TreeSet<>(knownCanonicalValues);
            return new Decision(MappingOutcome.INVALID_CONFIGURATION, winner.candidate().field(),
                    winner.candidate().value(), null, winner.rule().getId(), topPriority, reason);
        }

        return new Decision(MappingOutcome.MAPPED, winner.candidate().field(),
                winner.candidate().value(), canonical, winner.rule().getId(), topPriority, null);
    }

    private static String describe(Set<String> fields) {
        return fields.isEmpty() ? "[no fields]" : new TreeSet<>(fields).toString();
    }

    /** One resolution, carrying everything needed to explain it afterwards. */
    public record Decision(MappingOutcome outcome,
                           String sourceField,
                           String sourceValue,
                           String canonicalValue,
                           Long ruleId,
                           Integer priority,
                           String reason) {
    }

    private record Candidate(String field, String value, String key) {
    }

    private record Matched(Candidate candidate, FinMappingRule rule) {
    }
}

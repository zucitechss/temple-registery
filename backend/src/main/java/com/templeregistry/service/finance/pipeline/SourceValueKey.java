package com.templeregistry.service.finance.pipeline;

import java.util.Optional;

/**
 * The two halves of a mapping rule's {@code source_value} (FIN-054A).
 *
 * <p>A rule matches {@code SEVA_CODE:430}, where {@code SEVA_CODE} names the staged field the rule
 * reads and {@code 430} is the value in it. {@link MappingRuleResolver} has always understood this,
 * but it understood it privately: the rule that decides whether a stored string can ever fire lived
 * inside the resolver's constructor, so an administrative API validating a rule before saving it
 * would have had to restate that rule and hope the two stayed in agreement.
 *
 * <p>They would not have. A rule the API accepted and the resolver rejected is exactly the failure
 * this feature exists to prevent: the row is saved, it shows as Active, and it silently never
 * matches anything. So the definition lives here once, and both sides use it.
 */
public record SourceValueKey(String namespace, String value) {

    /** Separates a rule's field namespace from the value it matches. */
    public static final char SEPARATOR = ':';

    /**
     * Splits a stored {@code source_value}, or reports that it cannot fire.
     *
     * <p>The rejection cases are the resolver's, unchanged: no separator, nothing before it, or
     * nothing after it. Each means the string names no field, or names a field but no value.
     *
     * @return empty when the value is malformed — the caller decides whether that is a warning
     *         (an existing row, already saved) or a refusal (a new one)
     */
    public static Optional<SourceValueKey> parse(String sourceValue) {
        int separator = sourceValue == null ? -1 : sourceValue.indexOf(SEPARATOR);
        if (separator <= 0 || separator == sourceValue.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new SourceValueKey(
                sourceValue.substring(0, separator), sourceValue.substring(separator + 1)));
    }

    /**
     * Composes a {@code source_value} from a namespace and a value supplied separately.
     *
     * <p>Nothing is trimmed or case-folded, because matching is exact (see
     * {@link MappingRuleResolver}). A namespace with a stray space is a different field name and a
     * value with one is a different value; silently correcting either here would make the rule the
     * user saved differ from the rule they wrote.
     *
     * @throws IllegalArgumentException if either half is absent, blank, or would produce a string
     *                                  {@link #parse} could not read back
     */
    public static String compose(String namespace, String value) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException(
                    "A mapping rule needs the name of the staged field it reads.");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "A mapping rule needs the source value it matches.");
        }
        if (namespace.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException(
                    "A staged field name may not contain '" + SEPARATOR + "': [" + namespace
                            + "]. The first '" + SEPARATOR + "' separates the field from the value,"
                            + " so a name containing one would split in the wrong place.");
        }
        String composed = namespace + SEPARATOR + value;
        // Belt and braces: whatever is composed here must be readable by the engine that will
        // later match it. If these two ever disagree, the rule is dead on arrival.
        if (parse(composed).isEmpty()) {
            throw new IllegalArgumentException(
                    "[" + composed + "] cannot be read as <field>" + SEPARATOR + "<value>.");
        }
        return composed;
    }
}

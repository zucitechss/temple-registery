package com.templeregistry.entity.finance.enums;

/**
 * What happened when one staged source value was looked up against the mapping rules.
 *
 * <p>Five answers rather than a boolean, because "we did not produce a canonical value" has
 * several causes with different owners and different fixes. Collapsing them would hide a broken
 * extraction behind a configuration gap, or an operator error behind a missing rule.
 */
public enum MappingOutcome {

    /** Exactly one rule won, and the canonical value it names exists. */
    MAPPED,

    /**
     * The source supplied a value and no rule matches it.
     *
     * <p>A real income stream whose kind nobody has established yet — a seva code added at the
     * temple last week, say. It routes to the seeded {@code UNMAPPED} category rather than to
     * {@code OTHER_INCOME}, whose own description forbids that use, and it is never dropped.
     * The fix is a new mapping rule, and this outcome is how somebody finds out one is needed.
     */
    UNMAPPED,

    /**
     * Two or more rules matched at the same priority.
     *
     * <p>A configuration contradiction, not a data problem: somebody has said two different
     * things about one value and nothing in the rules says which wins. Resolving it by taking
     * whichever row the database returned first would make published revenue depend on query
     * plans, so no canonical value is produced at all.
     */
    AMBIGUOUS,

    /**
     * There was nothing to map: the record carries no field any rule reads, or carries one that
     * is empty.
     *
     * <p>Distinct from {@link #UNMAPPED} in the way that matters — an unknown value needs a new
     * rule, whereas nothing at all usually means the extraction stopped supplying a field. The
     * first is expected during onboarding; the second is a defect in the connector.
     */
    NOT_APPLICABLE,

    /**
     * A rule won, and names a canonical category that does not exist.
     *
     * <p>A typo in configuration. Caught here rather than at load, where it would surface as a
     * foreign-key failure on a batch of tens of thousands of rows with nothing saying which rule
     * caused it.
     */
    INVALID_CONFIGURATION;

    /** Whether this outcome produced a canonical value a later stage may use. */
    public boolean isDecided() {
        return this == MAPPED || this == UNMAPPED;
    }
}

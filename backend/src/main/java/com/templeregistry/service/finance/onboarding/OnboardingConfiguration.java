package com.templeregistry.service.finance.onboarding;

import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingType;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Everything the readiness validator is allowed to see, gathered in one place (FIN-140).
 *
 * <p>Plain values, no JPA entity, no repository, no {@code SecurityContext}. That is what lets
 * {@link OnboardingReadinessValidator} be a pure function tested in milliseconds without a
 * database — the same split {@code RevenueAggregator} and {@code RevenueMetricRollup} use, and
 * for the same reason: every rule that could silently mislead an administrator about whether a
 * temple's integration is safe to switch on should be testable without infrastructure.
 *
 * <p>Note what is <b>absent</b> and cannot be added: anything a connector would have to answer.
 * There is no reachability flag, no schema fingerprint comparison and no source row count,
 * because the registry runtime holds no connector and no credential (ADR-001). A validator that
 * accepted such a field would be one refactor away from someone trying to populate it.
 */
public record OnboardingConfiguration(

        Long sourceSystemId,
        String systemCode,

        /** Whether the temple this source claims to serve still exists in the registry. */
        boolean templeExists,

        /** Current kill-switch state. Reported, never changed by validation. */
        boolean syncEnabled,

        /** Whether a credential <em>alias</em> is set. Never the alias itself, never the secret. */
        boolean credentialRefSet,

        /** How many live source systems this temple has, including this one. See D9. */
        int sourceSystemsForTemple,

        List<CapabilityDeclaration> capabilities,

        /** Metrics with a declaration currently in force ({@code effective_to IS NULL}). */
        Set<String> declaredMetrics,

        List<MappingRuleView> mappingRules,

        /** Category codes a rule may legally name — the active rows of the canonical taxonomy. */
        Set<String> activeCanonicalValues) {

    /** One row of the capability matrix, flattened. */
    public record CapabilityDeclaration(FinanceCapability capability,
                                        DataAvailability availability,
                                        String reason,
                                        LocalDate coverageFrom,
                                        LocalDate coverageTo) {

        /**
         * Whether this capability will produce figures a reader is expected to act on.
         *
         * <p>{@code PARTIALLY_AVAILABLE} counts: a partial figure is still a published figure, and
         * it needs the same declarations and mappings behind it as a complete one.
         */
        public boolean reportable() {
            return availability == DataAvailability.AVAILABLE
                    || availability == DataAvailability.PARTIALLY_AVAILABLE;
        }
    }

    /** One mapping rule, as the validator needs to see it. */
    public record MappingRuleView(Long id,
                                  MappingType mappingType,
                                  String storedValue,
                                  String canonicalValue,
                                  int priority,
                                  boolean active) {
    }
}

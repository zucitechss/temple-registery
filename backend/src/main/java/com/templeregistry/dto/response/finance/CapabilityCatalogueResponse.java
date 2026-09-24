package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;

import java.util.List;

/**
 * The canonical vocabularies an onboarding form may offer (FIN-140-B, extended in FIN-140-C).
 *
 * <p>Served so that no client has to keep its own copy of any of them. A frontend constant
 * enumerating nineteen capabilities is a second source of truth that drifts the first time one is
 * added, and it drifts silently: the screen simply stops offering the new one, and nobody finds out
 * until a temple that needs it is onboarded without it. The same argument covers the metrics a
 * source-of-truth declaration may name, which is why they were added here rather than given an
 * endpoint of their own — one request, one cache, one place a vocabulary comes from.
 *
 * <p>Every list is derived from an enum at call time, so adding a value to
 * {@link FinanceCapability} or to {@code RevenueField} is sufficient to make it appear.
 *
 * @param capabilities   every declarable capability, in declaration order
 * @param availabilities every availability a declaration may carry
 * @param metrics        every metric a source-of-truth declaration may name (FIN-140-C)
 */
public record CapabilityCatalogueResponse(List<CapabilityOption> capabilities,
                                          List<DataAvailability> availabilities,
                                          List<MetricOption> metrics) {

    /**
     * One capability, and whether declaring it reportable pulls further configuration in.
     *
     * @param drivesRevenueRequirements true when declaring this capability {@code AVAILABLE} or
     *                                  {@code PARTIALLY_AVAILABLE} makes source-of-truth
     *                                  declarations and mapping rules mandatory for readiness.
     *                                  Supplied by the validator that enforces the rule rather than
     *                                  restated here, so a screen can explain the consequence
     *                                  without a second copy of which capability causes it
     */
    public record CapabilityOption(FinanceCapability capability, boolean drivesRevenueRequirements) {
    }

    /**
     * One metric a source-of-truth declaration may name (FIN-140-C).
     *
     * @param metric   the {@code fin_source_of_truth_decl.metric} value
     * @param field    the canonical field it supplies, as the pipeline names it
     * @param required true when normalization refuses a batch without it. Taken from
     *                 {@code RevenueField}, which is the authority: restating which metrics are
     *                 mandatory would create a second list, and the failure mode of the two
     *                 disagreeing is a source that passes onboarding and rejects every row on its
     *                 first run
     */
    public record MetricOption(String metric, String field, boolean required) {
    }
}

package com.templeregistry.service.finance.onboarding;

import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinRevenueCategory;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinTempleCapability;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.service.finance.pipeline.RevenueField;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gathers every registry-side fact {@link OnboardingReadinessValidator} is allowed to see
 * (FIN-140-A, extracted in FIN-058).
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>It was a private method on the registry's {@code SourceSystemAdminServiceImpl}, which was
 * correct while readiness was only ever asked about by a screen. FIN-058 gives it a second caller
 * in a different runtime: the manual sync trigger must refuse to execute a source whose
 * configuration has a blocking finding, and it runs on the sync worker where no registry service
 * exists.
 *
 * <p>Copying the assembly into the worker was the alternative and is the failure this class
 * prevents. Readiness would then have had two definitions of what it reads, and the two would
 * drift — a source could pass the screen an administrator looks at and be refused by the trigger,
 * or, far worse, pass the trigger while the screen still showed a blocker. The brief's rule is
 * blunt about it: one readiness implementation. This is what makes that possible for two runtimes.
 *
 * <h2>It reads registry tables only</h2>
 *
 * <p>Exactly as before. No connector, no credential, no source system contacted — which is why it
 * can be constructed safely in either runtime, and why the verdict it feeds still answers "is this
 * configuration coherent" rather than "does this source work" (ADR-001).
 */
@RequiredArgsConstructor
public class OnboardingConfigurationReader {

    private final FinSourceSystemRepository sourceSystems;
    private final FinTempleCapabilityRepository capabilities;
    private final FinSourceOfTruthDeclRepository declarations;
    private final FinMappingRuleRepository rules;
    private final FinRevenueCategoryRepository categories;

    /**
     * Everything the validator may look at, for one source system.
     *
     * <p>Capabilities are read by source system rather than by temple. The repository's finders
     * are all temple-scoped, so this filters — which is the honest thing to do while D9 is open:
     * the rows carry {@code source_system_id} even though the unique key ignores it, and reading
     * another source's declarations into this source's verdict would be exactly the confusion
     * that decision is about.
     *
     * @param templeExists resolved by the caller, because the two runtimes reach the temple table
     *                     through different read paths and neither needs the temple itself here
     */
    public OnboardingConfiguration read(FinSourceSystem source, boolean templeExists) {
        List<OnboardingConfiguration.CapabilityDeclaration> declaredCapabilities =
                capabilities.findByTempleIdAndDeletedFalse(source.getTempleId()).stream()
                        .filter(row -> Objects.equals(row.getSourceSystemId(), source.getId()))
                        .map(OnboardingConfigurationReader::toDeclaration)
                        .toList();

        Set<String> metricsInForce = new LinkedHashSet<>();
        for (RevenueField field : RevenueField.values()) {
            declarations
                    .findFirstBySourceSystemIdAndMetricAndEffectiveToIsNullAndDeletedFalseOrderByVersionDesc(
                            source.getId(), field.metric())
                    .map(FinSourceOfTruthDecl::getMetric)
                    .ifPresent(metricsInForce::add);
        }

        List<OnboardingConfiguration.MappingRuleView> ruleViews =
                rules.findBySourceSystemIdAndDeletedFalse(source.getId()).stream()
                        .map(OnboardingConfigurationReader::toRuleView)
                        .toList();

        Set<String> canonical = categories.findByActiveTrueAndDeletedFalse().stream()
                .map(FinRevenueCategory::getCategoryCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new OnboardingConfiguration(
                source.getId(),
                source.getSystemCode(),
                templeExists,
                source.isSyncEnabled(),
                source.getCredentialRef() != null,
                sourceSystems.findByTempleIdAndDeletedFalse(source.getTempleId()).size(),
                declaredCapabilities,
                metricsInForce,
                ruleViews,
                canonical);
    }

    private static OnboardingConfiguration.CapabilityDeclaration toDeclaration(FinTempleCapability row) {
        return new OnboardingConfiguration.CapabilityDeclaration(
                row.getCapability(), row.getAvailability(), row.getAvailabilityReason(),
                row.getCoverageFrom(), row.getCoverageTo());
    }

    private static OnboardingConfiguration.MappingRuleView toRuleView(FinMappingRule rule) {
        return new OnboardingConfiguration.MappingRuleView(
                rule.getId(), rule.getMappingType(), rule.getSourceValue(),
                rule.getCanonicalValue(), rule.getPriority(), rule.isActive());
    }
}

package com.templeregistry.service.finance.onboarding;

import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The readiness rules, tested without a database (FIN-140 slice 140-A).
 *
 * <p>Every rule here could silently mislead an administrator about whether a temple's integration
 * is safe to switch on, which is why the validator is a pure function over a value record: these
 * run in milliseconds and none of them needs Docker.
 */
class OnboardingReadinessValidatorTest {

    private static final Set<String> CANONICAL =
            new LinkedHashSet<>(List.of("SEVA", "DONATION", "HUNDI_DONATION", "UNMAPPED"));

    // ------------------------------------------------------------------ happy

    @Test
    @DisplayName("A completely configured source produces no finding at all")
    void should_reportReady_when_configurationIsComplete() {
        List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete().build());

        assertThat(findings).isEmpty();
        assertThat(OnboardingReadinessValidator.statusOf(findings)).isEqualTo(ReadinessStatus.READY);
    }

    @Test
    @DisplayName("Warnings alone leave the source activatable")
    void should_reportWarning_when_onlyNonBlockingFindingsExist() {
        List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(
                complete().credentialRefSet(false).build());

        assertThat(OnboardingReadinessValidator.statusOf(findings))
                .isEqualTo(ReadinessStatus.WARNING);
        assertThat(findings).extracting(ReadinessFinding::code).contains("CREDENTIAL_REF_MISSING");
        assertThat(findings).noneMatch(f -> f.severity() == ReadinessStatus.BLOCKED);
    }

    // ------------------------------------------------------------ temple / D9

    @Nested
    @DisplayName("Temple association")
    class TempleAssociation {

        @Test
        @DisplayName("A source whose temple has gone is blocked, not quietly reported ready")
        void should_block_when_templeIsMissing() {
            assertThat(codesOf(complete().templeExists(false).build()))
                    .contains("TEMPLE_MISSING");
        }

        @Test
        @DisplayName("A temple with two source systems is blocked while D9 is open")
        void should_block_when_templeHasMoreThanOneSourceSystem() {
            List<ReadinessFinding> findings =
                    OnboardingReadinessValidator.validate(complete().sourceSystemsForTemple(2).build());

            assertThat(codesOf(findings)).contains("MULTIPLE_SOURCE_SYSTEMS_FOR_TEMPLE");
            assertThat(messageFor(findings, "MULTIPLE_SOURCE_SYSTEMS_FOR_TEMPLE"))
                    .as("the message must name the decision, so a reader can find out why")
                    .contains("D9");
        }
    }

    // ------------------------------------------------------------ capabilities

    @Nested
    @DisplayName("Capabilities")
    class Capabilities {

        @Test
        @DisplayName("No capability declared blocks: every metric would read NOT_AVAILABLE with no reason")
        void should_block_when_noCapabilityDeclared() {
            assertThat(codesOf(complete().capabilities(List.of()).build()))
                    .contains("NO_CAPABILITY_DECLARED");
        }

        @Test
        @DisplayName("An unavailable capability with no reason blocks — the reason is shown verbatim to a reader")
        void should_block_when_unavailableCapabilityHasNoReason() {
            assertThat(codesOf(complete()
                    .capabilities(List.of(
                            capability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE,
                                    "Recorded in full.", LocalDate.of(2019, 4, 1), null),
                            capability(FinanceCapability.EXPENSE, DataAvailability.NOT_AVAILABLE,
                                    "   ", null, null)))
                    .build()))
                    .contains("CAPABILITY_REASON_MISSING");
        }

        @Test
        @DisplayName("An available capability needs no reason")
        void should_notBlock_when_availableCapabilityHasNoReason() {
            assertThat(codesOf(complete()
                    .capabilities(List.of(capability(FinanceCapability.REVENUE,
                            DataAvailability.AVAILABLE, null, LocalDate.of(2019, 4, 1), null)))
                    .build()))
                    .doesNotContain("CAPABILITY_REASON_MISSING");
        }

        @Test
        @DisplayName("PARTIALLY_AVAILABLE is treated as reportable, so it needs a reason and a coverage start")
        void should_treatPartiallyAvailableAsReportable() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .capabilities(List.of(capability(FinanceCapability.REVENUE,
                            DataAvailability.PARTIALLY_AVAILABLE, "Gaps in FY2021-22.", null, null)))
                    .build());

            assertThat(codesOf(findings))
                    .contains("COVERAGE_START_MISSING")
                    .doesNotContain("CAPABILITY_REASON_MISSING");
        }

        @Test
        @DisplayName("Capabilities nobody has declared are reported, because silence is not a statement")
        void should_warn_when_capabilitiesAreUndeclared() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .capabilities(List.of(capability(FinanceCapability.REVENUE,
                            DataAvailability.AVAILABLE, null, LocalDate.of(2019, 4, 1), null)))
                    .build());

            assertThat(severityOf(findings, "CAPABILITY_NOT_DECLARED"))
                    .isEqualTo(ReadinessStatus.WARNING);
            assertThat(messageFor(findings, "CAPABILITY_NOT_DECLARED"))
                    .as("the message must name what is missing, or it is not actionable")
                    .contains("EXPENSE")
                    .contains("NOT_APPLICABLE");
        }

        @Test
        @DisplayName("One finding lists every undeclared capability rather than one finding each")
        void should_reportUndeclaredCapabilitiesAsOneFinding() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .capabilities(List.of(capability(FinanceCapability.REVENUE,
                            DataAvailability.AVAILABLE, null, LocalDate.of(2019, 4, 1), null)))
                    .build());

            assertThat(findings.stream().filter(f -> f.code().equals("CAPABILITY_NOT_DECLARED")))
                    .as("eighteen separate warnings would bury the blocking findings underneath them")
                    .hasSize(1);
        }

        @Test
        @DisplayName("A fully declared matrix raises no undeclared-capability warning")
        void should_notWarn_when_everyCapabilityIsDeclared() {
            assertThat(codesOf(complete().build())).doesNotContain("CAPABILITY_NOT_DECLARED");
        }

        @Test
        @DisplayName("NOT_APPLICABLE counts as declared — it is a statement, not a gap")
        void should_treatNotApplicableAsDeclared() {
            List<OnboardingConfiguration.CapabilityDeclaration> all = new ArrayList<>();
            for (FinanceCapability capability : FinanceCapability.values()) {
                all.add(capability(capability, DataAvailability.NOT_APPLICABLE,
                        "Does not arise here.", null, null));
            }

            assertThat(codesOf(complete().capabilities(all).build()))
                    .doesNotContain("CAPABILITY_NOT_DECLARED");
        }

        @Test
        @DisplayName("A coverage window that ends before it begins blocks")
        void should_block_when_coverageWindowIsInverted() {
            assertThat(codesOf(complete()
                    .capabilities(List.of(capability(FinanceCapability.REVENUE,
                            DataAvailability.AVAILABLE, null,
                            LocalDate.of(2024, 4, 1), LocalDate.of(2019, 3, 31))))
                    .build()))
                    .contains("COVERAGE_WINDOW_INVERTED");
        }

        @Test
        @DisplayName("Coverage declared on a capability the source does not record is a warning")
        void should_warn_when_unavailableCapabilityDeclaresCoverage() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .capabilities(List.of(
                            capability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE, null,
                                    LocalDate.of(2019, 4, 1), null),
                            capability(FinanceCapability.EXPENSE, DataAvailability.NOT_AVAILABLE,
                                    "No accounts-payable module.", LocalDate.of(2020, 1, 1), null)))
                    .build());

            assertThat(severityOf(findings, "COVERAGE_DECLARED_WHEN_UNAVAILABLE"))
                    .isEqualTo(ReadinessStatus.WARNING);
        }
    }

    // --------------------------------------------------------- source of truth

    @Nested
    @DisplayName("Source-of-truth declarations")
    class SourceOfTruth {

        @Test
        @DisplayName("Revenue reportable with no amount declaration blocks — normalization would reject every row")
        void should_block_when_requiredDeclarationIsMissing() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(
                    complete().declaredMetrics(Set.of("REVENUE_TRANSACTION_DATE")).build());

            assertThat(codesOf(findings)).contains("SOURCE_OF_TRUTH_MISSING");
            assertThat(findings).anyMatch(f -> "REVENUE_AMOUNT".equals(f.subject()));
        }

        @Test
        @DisplayName("A source that does not report revenue needs no revenue declarations at all")
        void should_notBlock_when_revenueIsNotReportable() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .capabilities(List.of(
                            capability(FinanceCapability.REVENUE, DataAvailability.NOT_AVAILABLE,
                                    "This source records no receipts.", null, null),
                            capability(FinanceCapability.EXPENSE, DataAvailability.AVAILABLE, null,
                                    LocalDate.of(2022, 4, 1), null)))
                    .declaredMetrics(Set.of())
                    .mappingRules(List.of())
                    .build());

            assertThat(codesOf(findings))
                    .as("demanding revenue configuration from an expenses-only source would make "
                            + "such a source impossible to onboard")
                    .doesNotContain("SOURCE_OF_TRUTH_MISSING", "NO_ACTIVE_MAPPING_RULE");
        }

        @Test
        @DisplayName("Only the fields the pipeline calls required are demanded")
        void should_notDemandOptionalDeclarations() {
            assertThat(codesOf(complete()
                    .declaredMetrics(Set.of("REVENUE_AMOUNT", "REVENUE_TRANSACTION_DATE"))
                    .build()))
                    .as("cancellation and counter declarations are optional; absent means the "
                            + "source does not record them, which is a fact and not a fault")
                    .doesNotContain("SOURCE_OF_TRUTH_MISSING");
        }
    }

    // ----------------------------------------------------------- mapping rules

    @Nested
    @DisplayName("Mapping rules")
    class MappingRules {

        @Test
        @DisplayName("Revenue reportable with no active rule blocks — all income would land in UNMAPPED")
        void should_block_when_noActiveRuleExists() {
            assertThat(codesOf(complete().mappingRules(List.of()).build()))
                    .contains("NO_ACTIVE_MAPPING_RULE");
        }

        @Test
        @DisplayName("Inactive rules do not satisfy the requirement")
        void should_block_when_onlyInactiveRulesExist() {
            assertThat(codesOf(complete()
                    .mappingRules(List.of(rule(1L, "SANNIDHI:DS", "SEVA", 100, false)))
                    .build()))
                    .contains("NO_ACTIVE_MAPPING_RULE");
        }

        @Test
        @DisplayName("A rule naming a category that does not exist blocks, instead of failing per row at run time")
        void should_block_when_canonicalValueIsUnknown() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .mappingRules(List.of(rule(7L, "SANNIDHI:DS", "NOT_A_CATEGORY", 100, true)))
                    .build());

            assertThat(codesOf(findings)).contains("MAPPING_CANONICAL_UNKNOWN");
            assertThat(messageFor(findings, "MAPPING_CANONICAL_UNKNOWN"))
                    .as("the message must list what is valid, or the reader has to go looking")
                    .contains("SEVA");
        }

        @Test
        @DisplayName("A rule that names no staged field blocks — it can never match anything")
        void should_block_when_storedValueIsMalformed() {
            assertThat(codesOf(complete()
                    .mappingRules(List.of(rule(8L, "430", "SEVA", 100, true)))
                    .build()))
                    .contains("MAPPING_RULE_MALFORMED");
        }

        @Test
        @DisplayName("Two namespaces at one priority is a warning, never a block")
        void should_warnNotBlock_when_namespacesShareAPriority() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .mappingRules(List.of(
                            rule(1L, "SANNIDHI:DS", "SEVA", 100, true),
                            rule(2L, "STREAM:SAREE_DONATION", "DONATION", 100, true)))
                    .build());

            assertThat(severityOf(findings, "MAPPING_RULE_POSSIBLY_AMBIGUOUS"))
                    .as("the first onboarded source ships exactly this shape on purpose — its "
                            + "SANNIDHI and STREAM rules sit at one priority because they cannot "
                            + "co-occur. Blocking here would refuse a reasoned configuration")
                    .isEqualTo(ReadinessStatus.WARNING);
        }

        @Test
        @DisplayName("A more specific rule raised above the others raises no ambiguity warning")
        void should_notWarn_when_prioritiesSeparateNamespaces() {
            assertThat(codesOf(complete()
                    .mappingRules(List.of(
                            rule(1L, "SANNIDHI:KN", "DONATION", 100, true),
                            rule(2L, "SEVA_CODE:430", "HUNDI_DONATION", 200, true)))
                    .build()))
                    .doesNotContain("MAPPING_RULE_POSSIBLY_AMBIGUOUS");
        }

        @Test
        @DisplayName("Rules of a type nothing reads are reported as inert rather than hidden")
        void should_warn_when_inertRuleTypesAreActive() {
            List<OnboardingConfiguration.MappingRuleView> withMetal =
                    new ArrayList<>(complete().build().mappingRules());
            withMetal.add(new OnboardingConfiguration.MappingRuleView(
                    99L, MappingType.METAL_TYPE, "2", "GOLD", 100, true));

            List<ReadinessFinding> findings =
                    OnboardingReadinessValidator.validate(complete().mappingRules(withMetal).build());

            assertThat(severityOf(findings, "MAPPING_RULE_INERT_TYPE"))
                    .isEqualTo(ReadinessStatus.WARNING);
        }

        @Test
        @DisplayName("An inert rule is not judged by the revenue rules' standards")
        void should_notBlock_when_inertRuleIsMalformedOrUnknown() {
            List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                    .mappingRules(List.of(
                            rule(1L, "SANNIDHI:DS", "SEVA", 100, true),
                            new OnboardingConfiguration.MappingRuleView(
                                    99L, MappingType.METAL_TYPE, "2", "GOLD", 100, true)))
                    .build());

            assertThat(codesOf(findings))
                    .as("the two seeded METAL_TYPE rules are un-namespaced and name no revenue "
                            + "category; that is a known defect of an inert type, not a reason to "
                            + "refuse a source whose revenue mapping is sound")
                    .doesNotContain("MAPPING_RULE_MALFORMED", "MAPPING_CANONICAL_UNKNOWN");
        }
    }

    // ---------------------------------------------------------------- ordering

    @Test
    @DisplayName("Blocking findings are listed before warnings, deterministically")
    void should_orderBlockersFirst() {
        List<ReadinessFinding> findings = OnboardingReadinessValidator.validate(complete()
                .credentialRefSet(false)
                .mappingRules(List.of())
                .build());

        assertThat(findings).isNotEmpty();
        assertThat(findings.get(0).severity()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(findings.get(findings.size() - 1).severity()).isEqualTo(ReadinessStatus.WARNING);
    }

    @Test
    @DisplayName("statusOf reports the worst finding present")
    void should_foldToWorstStatus() {
        assertThat(OnboardingReadinessValidator.statusOf(List.of())).isEqualTo(ReadinessStatus.READY);
        assertThat(OnboardingReadinessValidator.statusOf(
                List.of(ReadinessFinding.warning("W", null, "w"))))
                .isEqualTo(ReadinessStatus.WARNING);
        assertThat(OnboardingReadinessValidator.statusOf(List.of(
                ReadinessFinding.warning("W", null, "w"),
                ReadinessFinding.blocking("B", null, "b"))))
                .isEqualTo(ReadinessStatus.BLOCKED);
    }

    // ----------------------------------------------------------------- builder

    /** A source that passes everything, which each test then breaks in exactly one way. */
    private static Builder complete() {
        return new Builder();
    }

    private static final class Builder {
        private boolean templeExists = true;
        private boolean credentialRefSet = true;
        private int sourceSystemsForTemple = 1;
        private List<OnboardingConfiguration.CapabilityDeclaration> capabilities = everyCapability();
        private Set<String> declaredMetrics =
                Set.of("REVENUE_AMOUNT", "REVENUE_TRANSACTION_DATE");
        private List<OnboardingConfiguration.MappingRuleView> mappingRules =
                List.of(rule(1L, "SANNIDHI:DS", "SEVA", 100, true));

        Builder templeExists(boolean value) { this.templeExists = value; return this; }
        Builder credentialRefSet(boolean value) { this.credentialRefSet = value; return this; }
        Builder sourceSystemsForTemple(int value) { this.sourceSystemsForTemple = value; return this; }
        Builder capabilities(List<OnboardingConfiguration.CapabilityDeclaration> value) {
            this.capabilities = value; return this;
        }
        Builder declaredMetrics(Set<String> value) { this.declaredMetrics = value; return this; }
        Builder mappingRules(List<OnboardingConfiguration.MappingRuleView> value) {
            this.mappingRules = value; return this;
        }

        OnboardingConfiguration build() {
            return new OnboardingConfiguration(1L, "TESTSRC", templeExists, false,
                    credentialRefSet, sourceSystemsForTemple, capabilities, declaredMetrics,
                    mappingRules, CANONICAL);
        }
    }

    /**
     * A fully declared matrix: revenue available, every other capability explicitly not applicable.
     *
     * <p>All nineteen, because since FIN-140-B an undeclared capability is itself a finding — an
     * undeclared one and a {@code NOT_AVAILABLE} one look identical to a reader but are different
     * statements. A "complete" fixture that declared one capability would be testing against a
     * standard the first onboarded source's own configuration does not follow.
     */
    private static List<OnboardingConfiguration.CapabilityDeclaration> everyCapability() {
        List<OnboardingConfiguration.CapabilityDeclaration> all = new ArrayList<>();
        all.add(capability(FinanceCapability.REVENUE, DataAvailability.AVAILABLE,
                "Receipts are recorded in full.", LocalDate.of(2019, 4, 1),
                LocalDate.of(2026, 7, 26)));
        for (FinanceCapability capability : FinanceCapability.values()) {
            if (capability != FinanceCapability.REVENUE) {
                all.add(capability(capability, DataAvailability.NOT_APPLICABLE,
                        "This question does not arise for this temple.", null, null));
            }
        }
        return List.copyOf(all);
    }

    private static OnboardingConfiguration.CapabilityDeclaration capability(
            FinanceCapability capability, DataAvailability availability, String reason,
            LocalDate from, LocalDate to) {
        return new OnboardingConfiguration.CapabilityDeclaration(
                capability, availability, reason, from, to);
    }

    private static OnboardingConfiguration.MappingRuleView rule(
            Long id, String storedValue, String canonicalValue, int priority, boolean active) {
        return new OnboardingConfiguration.MappingRuleView(
                id, MappingType.REVENUE_CATEGORY, storedValue, canonicalValue, priority, active);
    }

    private static List<String> codesOf(OnboardingConfiguration config) {
        return codesOf(OnboardingReadinessValidator.validate(config));
    }

    private static List<String> codesOf(List<ReadinessFinding> findings) {
        return findings.stream().map(ReadinessFinding::code).toList();
    }

    private static ReadinessStatus severityOf(List<ReadinessFinding> findings, String code) {
        return findings.stream()
                .filter(f -> f.code().equals(code))
                .map(ReadinessFinding::severity)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No finding with code " + code
                        + "; present: " + codesOf(findings)));
    }

    private static String messageFor(List<ReadinessFinding> findings, String code) {
        return findings.stream()
                .filter(f -> f.code().equals(code))
                .map(ReadinessFinding::message)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No finding with code " + code));
    }
}

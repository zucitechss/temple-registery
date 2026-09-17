package com.templeregistry.service.finance.pipeline;

import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-054. What the resolver decides, and — more to the point — what it refuses to decide.
 *
 * <p>No database and no Spring: resolution is a pure function of the rules and one record, and
 * the properties worth proving here are about determinism rather than persistence. The
 * database-level guarantees are in {@link RevenueMappingStageTest}.
 */
class MappingRuleResolverTest {

    private static final Set<String> CATEGORIES =
            Set.of("SEVA", "SPECIAL_SEVA", "DONATION", "HUNDI_DONATION", "PRASADAM_SALE", "UNMAPPED");

    // ---------------------------------------------------------------- the ordinary case

    @Test
    @DisplayName("One matching rule produces one canonical value")
    void should_map_when_exactlyOneRuleMatches() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVA", 100));

        MappingRuleResolver.Decision decision = resolver.resolve(Map.of("BUCKET", "DS"));

        assertThat(decision.outcome()).isEqualTo(MappingOutcome.MAPPED);
        assertThat(decision.canonicalValue()).isEqualTo("SEVA");
        assertThat(decision.ruleId()).isEqualTo(1L);
        assertThat(decision.sourceField()).isEqualTo("BUCKET");
        assertThat(decision.sourceValue()).isEqualTo("DS");
        assertThat(decision.reason()).as("a decision that worked needs no explanation").isNull();
    }

    /**
     * The fields consulted come from the rules, never from this class. That is the whole
     * mechanism by which a shared stage reads a temple's payload without knowing its schema.
     */
    @Test
    @DisplayName("The fields read are derived from the rules themselves")
    void should_deriveReadableFields_when_rulesAreLoaded() {
        MappingRuleResolver resolver = resolver(
                rule(1L, "BUCKET:DS", "SEVA", 100),
                rule(2L, "SERVICE_CODE:430", "HUNDI_DONATION", 200),
                rule(3L, "STREAM:X", "DONATION", 100));

        assertThat(resolver.readableFields())
                .containsExactlyInAnyOrder("BUCKET", "SERVICE_CODE", "STREAM");
    }

    // ---------------------------------------------------------------- precedence

    /**
     * The decision FIN-D-015 describes and the schema could not previously express: a rule on a
     * specific service code must beat the coarse bucket that code sits inside. Without it, the
     * first temple's donation-box collections — 13 records averaging over a crore — are reported
     * as ordinary donations.
     */
    @Test
    @DisplayName("The higher priority wins when a specific rule overlaps a general one")
    void should_preferHigherPriority_when_twoRulesMatch() {
        MappingRuleResolver resolver = resolver(
                rule(1L, "BUCKET:KN", "DONATION", 100),
                rule(2L, "SERVICE_CODE:430", "HUNDI_DONATION", 200));

        MappingRuleResolver.Decision decision =
                resolver.resolve(Map.of("BUCKET", "KN", "SERVICE_CODE", "430"));

        assertThat(decision.outcome()).isEqualTo(MappingOutcome.MAPPED);
        assertThat(decision.canonicalValue()).isEqualTo("HUNDI_DONATION");
        assertThat(decision.ruleId()).isEqualTo(2L);
        assertThat(decision.priority()).isEqualTo(200);
    }

    @Test
    @DisplayName("Precedence does not depend on the order rules arrive in")
    void should_beDeterministic_when_ruleOrderVaries() {
        FinMappingRule general = rule(1L, "BUCKET:KN", "DONATION", 100);
        FinMappingRule specific = rule(2L, "SERVICE_CODE:430", "HUNDI_DONATION", 200);
        Map<String, String> record = Map.of("BUCKET", "KN", "SERVICE_CODE", "430");

        assertThat(resolver(general, specific).resolve(record).canonicalValue())
                .isEqualTo(resolver(specific, general).resolve(record).canonicalValue())
                .isEqualTo("HUNDI_DONATION");
    }

    /**
     * Two rules of equal standing is a contradiction in configuration. Resolving it by taking
     * whichever the database returned first would make a temple's published revenue depend on a
     * query plan, so nothing is decided and somebody is told which rules disagree.
     */
    @Test
    @DisplayName("Equal priority with two matches is ambiguous, never an arbitrary pick")
    void should_reportAmbiguous_when_twoRulesMatchAtTheSamePriority() {
        MappingRuleResolver resolver = resolver(
                rule(1L, "BUCKET:KN", "DONATION", 100),
                rule(2L, "SERVICE_CODE:430", "HUNDI_DONATION", 100));

        MappingRuleResolver.Decision decision =
                resolver.resolve(Map.of("BUCKET", "KN", "SERVICE_CODE", "430"));

        assertThat(decision.outcome()).isEqualTo(MappingOutcome.AMBIGUOUS);
        assertThat(decision.canonicalValue())
                .as("an undecided record must not carry a canonical value")
                .isNull();
        assertThat(decision.ruleId()).isNull();
        assertThat(decision.reason())
                .contains("DONATION", "HUNDI_DONATION", "priority 100")
                .contains("Give the more specific rule a higher priority");
    }

    @Test
    @DisplayName("The ambiguity message reads the same however the rules are ordered")
    void should_describeAmbiguityStably_when_ruleOrderVaries() {
        FinMappingRule a = rule(1L, "BUCKET:KN", "DONATION", 100);
        FinMappingRule b = rule(2L, "SERVICE_CODE:430", "HUNDI_DONATION", 100);
        Map<String, String> record = Map.of("BUCKET", "KN", "SERVICE_CODE", "430");

        assertThat(resolver(a, b).resolve(record).reason())
                .isEqualTo(resolver(b, a).resolve(record).reason());
    }

    // ---------------------------------------------------------------- nothing decided

    @Test
    @DisplayName("A value no rule covers is unmapped, and routed to the UNMAPPED category")
    void should_reportUnmapped_when_noRuleMatchesTheValue() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVA", 100));

        MappingRuleResolver.Decision decision = resolver.resolve(Map.of("BUCKET", "ZZ"));

        assertThat(decision.outcome()).isEqualTo(MappingOutcome.UNMAPPED);
        assertThat(decision.canonicalValue())
                .as("unmapped revenue stays visible in its own category, never OTHER_INCOME")
                .isEqualTo("UNMAPPED");
        assertThat(decision.sourceValue()).isEqualTo("ZZ");
        assertThat(decision.reason()).contains("BUCKET:ZZ", "add a mapping rule");
    }

    /**
     * "We do not recognise this value" and "the source sent us nothing" are different questions with
     * different owners: the first needs a mapping rule, the second means extraction stopped
     * supplying a field. Collapsing them hides a broken connector behind a configuration gap.
     */
    @Test
    @DisplayName("A record carrying none of the rule fields is not applicable, not unmapped")
    void should_reportNotApplicable_when_noRuleFieldIsPresent() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVA", 100));

        MappingRuleResolver.Decision decision = resolver.resolve(Map.of("amount", "100.00"));

        assertThat(decision.outcome()).isEqualTo(MappingOutcome.NOT_APPLICABLE);
        assertThat(decision.canonicalValue()).isNull();
        assertThat(decision.sourceValue()).isNull();
        assertThat(decision.reason()).contains("carries none of", "BUCKET");
    }

    @Test
    @DisplayName("A field present but empty is nothing to map, and is not guessed at")
    void should_reportNotApplicable_when_fieldIsBlank() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVA", 100));

        for (String empty : new String[]{"", "   "}) {
            MappingRuleResolver.Decision decision = resolver.resolve(Map.of("BUCKET", empty));

            assertThat(decision.outcome()).isEqualTo(MappingOutcome.NOT_APPLICABLE);
            assertThat(decision.canonicalValue()).isNull();
            assertThat(decision.reason()).contains("no value");
        }
    }

    @Test
    @DisplayName("A field present but null is nothing to map either")
    void should_reportNotApplicable_when_fieldIsNull() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVA", 100));

        Map<String, String> record = new HashMap<>();
        record.put("BUCKET", null);

        MappingRuleResolver.Decision decision = resolver.resolve(record);

        assertThat(decision.outcome()).isEqualTo(MappingOutcome.NOT_APPLICABLE);
        assertThat(decision.sourceValue()).isNull();
    }

    @Test
    @DisplayName("An empty record decides nothing rather than defaulting")
    void should_reportNotApplicable_when_recordIsEmptyOrNull() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVA", 100));

        assertThat(resolver.resolve(Map.of()).outcome()).isEqualTo(MappingOutcome.NOT_APPLICABLE);
        assertThat(resolver.resolve(null).outcome()).isEqualTo(MappingOutcome.NOT_APPLICABLE);
    }

    // ---------------------------------------------------------------- bad configuration

    /**
     * Caught here rather than at load, where a typo would surface as a constraint failure on a
     * batch of tens of thousands of rows with nothing saying which rule caused it.
     */
    @Test
    @DisplayName("A rule naming a category that does not exist is a configuration error")
    void should_reportInvalidConfiguration_when_canonicalValueIsUnknown() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVAA", 100));

        MappingRuleResolver.Decision decision = resolver.resolve(Map.of("BUCKET", "DS"));

        assertThat(decision.outcome()).isEqualTo(MappingOutcome.INVALID_CONFIGURATION);
        assertThat(decision.canonicalValue())
                .as("a category that does not exist must not be written as though it did")
                .isNull();
        assertThat(decision.ruleId()).isEqualTo(1L);
        assertThat(decision.reason()).contains("SEVAA", "not a canonical");
    }

    @Test
    @DisplayName("A rule with no field namespace can never fire, and is reported")
    void should_reportProblem_when_ruleHasNoNamespace() {
        MappingRuleResolver resolver = resolver(
                rule(1L, "DS", "SEVA", 100),
                rule(2L, "BUCKET:SS", "SPECIAL_SEVA", 100));

        assertThat(resolver.configurationProblems())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("rule 1", "names no field");
        assertThat(resolver.readableFields()).containsExactly("BUCKET");
        assertThat(resolver.isUsable()).isTrue();
    }

    @Test
    @DisplayName("A rule set where nothing can fire is not usable")
    void should_beUnusable_when_noRuleCanEverMatch() {
        assertThat(resolver().isUsable()).isFalse();
        assertThat(resolver(rule(1L, "DS", "SEVA", 100)).isUsable()).isFalse();
    }

    // ---------------------------------------------------------------- no helpful guessing

    /**
     * A source that pads or capitalises a value differently is telling us something real. The
     * helpful thing — trimming it into a match — is exactly the silent normalization this
     * pipeline exists to avoid, so the difference surfaces as unmapped instead.
     */
    @Test
    @DisplayName("Values are matched exactly: nothing is trimmed or case-folded into a match")
    void should_notNormalise_when_valueDiffersOnlyBySpacingOrCase() {
        MappingRuleResolver resolver = resolver(rule(1L, "BUCKET:DS", "SEVA", 100));

        for (String near : new String[]{" DS", "DS ", "ds", "Ds"}) {
            assertThat(resolver.resolve(Map.of("BUCKET", near)).outcome())
                    .as("[%s] is not [DS], and pretending otherwise would be a guess", near)
                    .isEqualTo(MappingOutcome.UNMAPPED);
        }
    }

    // ---------------------------------------------------------------- purity

    @Test
    @DisplayName("The resolver names no source system, transport or credential")
    void should_stayGeneric_when_sourceScanned() throws IOException {
        Path source = Path.of("src", "main", "java", "com", "templeregistry", "service",
                "finance", "pipeline", "MappingRuleResolver.java");
        String code = Files.readString(source, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ")
                .toLowerCase(Locale.ROOT);

        for (String token : List.of("kollur", "kolsoham", "mookambika", "dailyseva", "hkanike",
                "sevacode", "sannidhi", "billcancled", "deleteflag", "templecode", "300001",
                "java.sql", "datasource", "jdbc", "credential", "password")) {
            assertThat(code)
                    .as("[%s] belongs to a connector or a configuration row, not to a stage that "
                            + "runs for every temple", token)
                    .doesNotContain(token);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static MappingRuleResolver resolver(FinMappingRule... rules) {
        return new MappingRuleResolver(MappingType.REVENUE_CATEGORY, List.of(rules), CATEGORIES);
    }

    private static FinMappingRule rule(Long id, String sourceValue, String canonical, int priority) {
        FinMappingRule rule = FinMappingRule.builder()
                .sourceSystemId(1L)
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue(sourceValue)
                .canonicalValue(canonical)
                .priority(priority)
                .build();
        rule.setId(id);
        return rule;
    }
}

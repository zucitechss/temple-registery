package com.templeregistry.service.finance.onboarding;

import com.templeregistry.dto.request.finance.RegisterSourceSystemRequest;
import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinSourceOfTruthDecl;
import com.templeregistry.entity.finance.FinTempleCapability;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinTempleCapabilityRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.ScopeHelper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Seeding shared by the FIN-140 onboarding tests.
 *
 * <p>Two temples in two districts, because a single-temple fixture passes every isolation
 * assertion by having nothing to leak.
 *
 * <p>Codes are UUID-suffixed. {@code uk_fss_temple_system} is per temple and
 * {@code fin_revenue_category.uk_frc_code} is platform-wide, and every method in a class shares
 * one container with no rollback between them — a literal code collides on the second test, as it
 * did once already in {@code FinanceReportServiceImplTest}.
 */
final class OnboardingTestFixture {

    static final long DISTRICT_A = 9_400_001L;
    static final long DISTRICT_B = 9_400_002L;

    private final TempleRepository temples;
    private final FinTempleCapabilityRepository capabilities;
    private final FinSourceOfTruthDeclRepository declarations;
    private final FinMappingRuleRepository rules;

    OnboardingTestFixture(TempleRepository temples,
                          FinTempleCapabilityRepository capabilities,
                          FinSourceOfTruthDeclRepository declarations,
                          FinMappingRuleRepository rules) {
        this.temples = temples;
        this.capabilities = capabilities;
        this.declarations = declarations;
        this.rules = rules;
    }

    Temple temple(String name, long districtId) {
        return temples.saveAndFlush(Temple.builder()
                .registrationNumber("FIN140-" + UUID.randomUUID())
                .name(name)
                .primaryDeity("Test")
                .districtId(districtId)
                .build());
    }

    /** A well-formed registration request. Tests break exactly one field of it at a time. */
    static RegisterSourceSystemRequest registration(Long templeId) {
        RegisterSourceSystemRequest request = new RegisterSourceSystemRequest();
        request.setTempleId(templeId);
        request.setSystemCode(uniqueCode("SRC"));
        request.setSystemName("Operational system");
        request.setSourceTechnology(SourceTechnology.SQL_SERVER);
        request.setConnectorType(ConnectorType.PULL_JDBC);
        request.setConnectorBean("someFinanceConnector");
        request.setCredentialRef("some-readonly-alias");
        return request;
    }

    /**
     * Temple B from the onboarding runbook: PostgreSQL, push agent, has expenses, no Nirantara,
     * no precious metals, coverage from FY2022-23. Deliberately dissimilar to the first onboarded
     * source in every dimension the architecture claims to abstract over.
     */
    static RegisterSourceSystemRequest templeBRegistration(Long templeId) {
        RegisterSourceSystemRequest request = new RegisterSourceSystemRequest();
        request.setTempleId(templeId);
        request.setSystemCode(uniqueCode("SRT_MANDYA"));
        request.setSystemName("Sri Ranganatha accounts");
        request.setSourceTechnology(SourceTechnology.POSTGRESQL);
        request.setConnectorType(ConnectorType.PUSH_AGENT);
        request.setConnectorBean("templeBFinanceConnector");
        request.setSourceDatabaseName("srt_accounts");
        request.setCredentialRef("templeb-agent-token");
        request.setSourceTimezone("Asia/Kolkata");
        return request;
    }

    static String uniqueCode(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                .toUpperCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------ declarations

    FinTempleCapability capability(Long templeId, Long sourceSystemId, FinanceCapability capability,
                                   DataAvailability availability, String reason, LocalDate from) {
        return capabilities.saveAndFlush(FinTempleCapability.builder()
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .capability(capability)
                .availability(availability)
                .availabilityReason(reason)
                .coverageFrom(from)
                .build());
    }

    FinSourceOfTruthDecl declaration(Long sourceSystemId, String metric) {
        return declarations.saveAndFlush(FinSourceOfTruthDecl.builder()
                .sourceSystemId(sourceSystemId)
                .metric(metric)
                .version(1)
                .sourceObject("SomeTable")
                .sourceField("SomeField")
                .build());
    }

    FinMappingRule rule(Long sourceSystemId, String storedValue, String canonicalValue, int priority) {
        return rules.saveAndFlush(FinMappingRule.builder()
                .sourceSystemId(sourceSystemId)
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue(storedValue)
                .canonicalValue(canonicalValue)
                .priority(priority)
                .active(true)
                .build());
    }

    /** Everything a source needs to reach READY: revenue capability, both required metrics, one rule. */
    void makeFullyConfigured(Long templeId, Long sourceSystemId) {
        capability(templeId, sourceSystemId, FinanceCapability.REVENUE, DataAvailability.AVAILABLE,
                "Receipts are recorded in full.", LocalDate.of(2019, 4, 1));
        declaration(sourceSystemId, "REVENUE_AMOUNT");
        declaration(sourceSystemId, "REVENUE_TRANSACTION_DATE");
        rule(sourceSystemId, "SANNIDHI:DS", "SEVA", 100);
        declareRemainingAsNotApplicable(templeId, sourceSystemId);
    }

    /**
     * Declares every capability nobody has declared yet as {@code NOT_APPLICABLE}, with a reason.
     *
     * <p>Needed for a source to reach {@code READY} since FIN-140-B: an undeclared capability and
     * one declared {@code NOT_AVAILABLE} look identical to a reader but are different statements,
     * so readiness warns about the gap. The first onboarded source declares all nineteen for
     * exactly that reason, and a fixture that declared one would be testing against a standard the
     * real configuration does not follow.
     */
    void declareRemainingAsNotApplicable(Long templeId, Long sourceSystemId) {
        Set<FinanceCapability> declared = capabilities.findByTempleIdAndDeletedFalse(templeId).stream()
                .map(FinTempleCapability::getCapability)
                .collect(Collectors.toSet());

        for (FinanceCapability capability : FinanceCapability.values()) {
            if (!declared.contains(capability)) {
                capability(templeId, sourceSystemId, capability, DataAvailability.NOT_APPLICABLE,
                        "This question does not arise for this temple.", null);
            }
        }
    }

    // ----------------------------------------------------------------- security

    static void authenticateAs(String role, long userId, Long districtId, Long templeId) {
        ScopeHelper.Claims claims =
                new ScopeHelper.Claims(userId, role, districtId, templeId,
                        role.toLowerCase(java.util.Locale.ROOT), "EDIT");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}

package com.templeregistry.service.finance.mapping;

import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinStgRevenueMapping;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.entity.temple.Temple;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.temple.TempleRepository;
import com.templeregistry.security.ScopeHelper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Seeding shared by the FIN-054A service tests.
 *
 * <p>Two temples in two districts, each with its own source system, is the minimum shape that can
 * show isolation failing: a single-temple fixture passes every cross-temple assertion by accident.
 */
final class MappingAdminTestFixture {

    static final long DISTRICT_A = 9_300_001L;
    static final long DISTRICT_B = 9_300_002L;

    private final TempleRepository temples;
    private final FinSourceSystemRepository sourceSystems;
    private final FinMappingRuleRepository rules;
    private final FinStgRevenueRepository staging;
    private final FinStgRevenueMappingRepository decisions;
    private final FinSyncBatchRepository batches;

    MappingAdminTestFixture(TempleRepository temples,
                            FinSourceSystemRepository sourceSystems,
                            FinMappingRuleRepository rules,
                            FinStgRevenueRepository staging,
                            FinStgRevenueMappingRepository decisions,
                            FinSyncBatchRepository batches) {
        this.temples = temples;
        this.sourceSystems = sourceSystems;
        this.rules = rules;
        this.staging = staging;
        this.decisions = decisions;
        this.batches = batches;
    }

    Temple temple(String name, long districtId) {
        return temples.saveAndFlush(Temple.builder()
                .registrationNumber("FIN054A-" + UUID.randomUUID())
                .name(name)
                .primaryDeity("Test")
                .districtId(districtId)
                .build());
    }

    FinSourceSystem sourceSystem(Temple temple, String code) {
        return sourceSystems.saveAndFlush(FinSourceSystem.builder()
                .templeId(temple.getId())
                .systemCode(code)
                .systemName(code + " operational system")
                .sourceTechnology(SourceTechnology.SQL_SERVER)
                .connectorType(ConnectorType.PULL_JDBC)
                .connectorBean("syntheticFinanceConnector")
                .build());
    }

    FinMappingRule rule(long sourceSystemId, String storedValue, String canonicalValue, int priority) {
        return rules.saveAndFlush(FinMappingRule.builder()
                .sourceSystemId(sourceSystemId)
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceValue(storedValue)
                .canonicalValue(canonicalValue)
                .priority(priority)
                .active(true)
                .build());
    }

    FinSyncBatch batch(Temple temple, FinSourceSystem source) {
        return batches.saveAndFlush(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(temple.getId())
                .sourceSystemId(source.getId())
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(SyncStatus.SUCCESS)
                .startedAt(LocalDateTime.now())
                .build());
    }

    /** One staged record plus the decision the mapping stage would have recorded about it. */
    void stagedRecord(FinSyncBatch batch, String recordRef, String rawJson,
                      String sourceField, String sourceValue, MappingOutcome outcome) {
        FinStgRevenue staged = staging.saveAndFlush(FinStgRevenue.builder()
                .templeId(batch.getTempleId())
                .sourceSystemId(batch.getSourceSystemId())
                .syncBatchId(batch.getId())
                .sourceRecordRef(recordRef)
                .rawJson(rawJson)
                .validationStatus(StagingStatus.VALID)
                .extractedAt(LocalDateTime.now())
                .build());
        decisions.saveAndFlush(FinStgRevenueMapping.builder()
                .stgRevenueId(staged.getId())
                .templeId(batch.getTempleId())
                .sourceSystemId(batch.getSourceSystemId())
                .syncBatchId(batch.getId())
                .sourceRecordRef(recordRef)
                .mappingType(MappingType.REVENUE_CATEGORY)
                .sourceField(sourceField)
                .sourceValue(sourceValue)
                .outcome(outcome)
                .canonicalValue(outcome == MappingOutcome.UNMAPPED ? FinMappingRule.UNMAPPED : null)
                .mappedAt(LocalDateTime.now())
                .build());
    }

    static void authenticateAs(String role, long userId, Long districtId, Long templeId) {
        ScopeHelper.Claims claims =
                new ScopeHelper.Claims(userId, role, districtId, templeId, role.toLowerCase(), "EDIT");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}

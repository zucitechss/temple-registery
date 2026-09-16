package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.enums.MappingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Source-value to canonical-value translations, loaded once per sync batch. */
@Repository
public interface FinMappingRuleRepository extends JpaRepository<FinMappingRule, Long> {

    /** All active rules of one type. Loaded into a map at the start of a batch. */
    List<FinMappingRule> findBySourceSystemIdAndMappingTypeAndActiveTrueAndDeletedFalse(
            Long sourceSystemId, MappingType mappingType);

    Optional<FinMappingRule> findBySourceSystemIdAndMappingTypeAndSourceValueAndDeletedFalse(
            Long sourceSystemId, MappingType mappingType, String sourceValue);

    List<FinMappingRule> findBySourceSystemIdAndDeletedFalse(Long sourceSystemId);
}

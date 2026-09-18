package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinMappingRule;
import com.templeregistry.entity.finance.enums.MappingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * One rule, by id, for the administrative API (FIN-054A).
     *
     * <p>Filtered on {@code deleted} so a soft-deleted rule is absent rather than editable.
     */
    Optional<FinMappingRule> findByIdAndDeletedFalse(Long id);

    /**
     * The administrative rule list: one source system, paged, with every filter optional.
     *
     * <p>Scoped to a single {@code sourceSystemId} and not optional about it. A rule only means
     * anything against the source whose vocabulary it translates, and a list spanning sources
     * would be a list the caller's temple scope had not been checked against.
     *
     * <p>Inactive and malformed rules are included deliberately. A rule that cannot fire is
     * precisely what an administrator opened this screen to find, so filtering it out of the
     * default view would hide the defect the screen exists to surface.
     *
     * <p>{@code search} matches the stored namespaced value, the source label and the canonical
     * value. It is a {@code LIKE} over three columns rather than anything cleverer because the
     * rule set for one source system is tens of rows, not thousands.
     */
    @Query("""
        SELECT r
          FROM FinMappingRule r
         WHERE r.sourceSystemId = :sourceSystemId
           AND r.deleted = false
           AND (:mappingType IS NULL OR r.mappingType = :mappingType)
           AND (:active IS NULL OR r.active = :active)
           AND (:canonicalValue IS NULL OR r.canonicalValue = :canonicalValue)
           AND (:search IS NULL
                OR LOWER(r.sourceValue)     LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(r.sourceLabel)     LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(r.canonicalValue)  LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<FinMappingRule> search(@Param("sourceSystemId") Long sourceSystemId,
                                @Param("mappingType") MappingType mappingType,
                                @Param("active") Boolean active,
                                @Param("canonicalValue") String canonicalValue,
                                @Param("search") String search,
                                Pageable pageable);

    /**
     * Whether another rule already claims this key.
     *
     * <p>{@code uk_fmr_source_type_value} is the real guarantee; this exists so the API can refuse
     * with a message naming the existing rule instead of surfacing a constraint violation. The
     * {@code id} exclusion is what lets an edit leave its own key unchanged.
     */
    @Query("""
        SELECT r
          FROM FinMappingRule r
         WHERE r.sourceSystemId = :sourceSystemId
           AND r.mappingType    = :mappingType
           AND r.sourceValue    = :sourceValue
           AND r.deleted        = false
           AND (:excludingId IS NULL OR r.id <> :excludingId)
        """)
    Optional<FinMappingRule> findConflicting(@Param("sourceSystemId") Long sourceSystemId,
                                             @Param("mappingType") MappingType mappingType,
                                             @Param("sourceValue") String sourceValue,
                                             @Param("excludingId") Long excludingId);
}

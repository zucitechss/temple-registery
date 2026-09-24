package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinTempleCapability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Declared data availability per temple.
 *
 * <p>Report services consult this before rendering any metric, so that an absent
 * capability is reported as NOT_AVAILABLE with a reason rather than as zero.
 */
@Repository
public interface FinTempleCapabilityRepository extends JpaRepository<FinTempleCapability, Long> {

    List<FinTempleCapability> findByTempleIdAndDeletedFalse(Long templeId);

    Optional<FinTempleCapability> findByTempleIdAndCapabilityAndDeletedFalse(
            Long templeId, FinanceCapability capability);

    List<FinTempleCapability> findByTempleIdInAndDeletedFalse(List<Long> templeIds);

    /**
     * One source system's declarations (FIN-140-B).
     *
     * <p>Every other finder here is temple-scoped, which is the single-source assumption open
     * decision D9 records. Onboarding administers one <em>source system</em>, so it reads by source:
     * the column has always been present and populated even though {@code uk_ftc_temple_capability}
     * ignores it, and attributing another source's declarations to this one is exactly the
     * confusion D9 is about.
     */
    List<FinTempleCapability> findBySourceSystemIdAndDeletedFalse(Long sourceSystemId);

    /**
     * Any declaration of this capability for this temple, <b>including a retired one</b> and
     * <b>whichever source system owns it</b> (FIN-140-B).
     *
     * <p>{@code uk_ftc_temple_capability} is {@code (temple_id, capability)}: it does not include
     * {@code is_deleted}, and it does not include {@code source_system_id}. A duplicate check that
     * filtered either out would pass and then hit the constraint, turning a refusal a caller could
     * act on into an opaque server error — the defect found in slice 140-A on the equivalent key.
     */
    Optional<FinTempleCapability> findByTempleIdAndCapability(Long templeId, FinanceCapability capability);
}

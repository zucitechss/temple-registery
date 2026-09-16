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
}

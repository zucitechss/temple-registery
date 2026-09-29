package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinDataAlert;
import com.templeregistry.entity.finance.enums.AlertStatus;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Standing missed-entry conditions (V129, FR17, FR18, FR20).
 *
 * <p>At most one OPEN row per temple and capability. MySQL and TiDB have no
 * partial unique index, so that invariant is enforced by the alert service and
 * {@code idx_fda_open} is what makes its check cheap.
 */
@Repository
public interface FinDataAlertRepository extends JpaRepository<FinDataAlert, Long> {

    Optional<FinDataAlert> findByTempleIdAndCapabilityAndStatus(
            Long templeId, FinanceCapability capability, AlertStatus status);

    /** The DC dashboard query, narrowed to the temples in the caller scope. */
    List<FinDataAlert> findByStatusAndTempleIdIn(AlertStatus status, List<Long> templeIds);

    List<FinDataAlert> findByTempleIdOrderByOpenedAtDesc(Long templeId);
}

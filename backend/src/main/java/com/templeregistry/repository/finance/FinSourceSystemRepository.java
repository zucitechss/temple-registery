package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinSourceSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Registry of external systems that supply financial data. */
@Repository
public interface FinSourceSystemRepository extends JpaRepository<FinSourceSystem, Long> {

    List<FinSourceSystem> findByTempleIdAndDeletedFalse(Long templeId);

    /**
     * Every registered source system (FIN-054A).
     *
     * <p>For the administrative selector, which is filtered down to the caller's jurisdiction in
     * the service. Unfiltered here because there is no join from a source system to a district:
     * the path runs through the temple, and the traversal that scopes it already lives in
     * {@code JurisdictionGuard}.
     */
    List<FinSourceSystem> findByDeletedFalse();

    Optional<FinSourceSystem> findByTempleIdAndSystemCodeAndDeletedFalse(Long templeId, String systemCode);

    /** Sources the scheduler is permitted to contact. Used only by the sync worker. */
    List<FinSourceSystem> findBySyncEnabledTrueAndDeletedFalse();
}

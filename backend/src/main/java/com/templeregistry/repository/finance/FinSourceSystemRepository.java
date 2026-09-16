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

    Optional<FinSourceSystem> findByTempleIdAndSystemCodeAndDeletedFalse(Long templeId, String systemCode);

    /** Sources the scheduler is permitted to contact. Used only by the sync worker. */
    List<FinSourceSystem> findBySyncEnabledTrueAndDeletedFalse();
}

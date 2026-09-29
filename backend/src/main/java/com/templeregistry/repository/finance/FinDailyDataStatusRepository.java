package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinDailyDataStatus;
import com.templeregistry.entity.finance.enums.DataSubmissionStatus;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * One row per temple, capability and business date (V137, FR17, FR19, FR20).
 *
 * <p>{@link #findGap} is what both the alert severity and the FR20 clearing
 * decision are computed from: the still-missing days, in order. Severity is
 * derived from that list on every evaluation rather than incremented, which is
 * what lets a partial backfill de-escalate an alert instead of leaving it
 * stuck.
 */
@Repository
public interface FinDailyDataStatusRepository extends JpaRepository<FinDailyDataStatus, Long> {

    Optional<FinDailyDataStatus> findByTempleIdAndCapabilityAndBusinessDate(
            Long templeId, FinanceCapability capability, LocalDate businessDate);

    List<FinDailyDataStatus> findByTempleIdAndCapabilityAndBusinessDateBetweenOrderByBusinessDateAsc(
            Long templeId, FinanceCapability capability, LocalDate from, LocalDate to);

    /** The outstanding days for one obligation, oldest first. */
    default List<FinDailyDataStatus> findGap(Long templeId, FinanceCapability capability) {
        return findByTempleIdAndCapabilityAndStatusOrderByBusinessDateAsc(
                templeId, capability, DataSubmissionStatus.MISSED);
    }

    List<FinDailyDataStatus> findByTempleIdAndCapabilityAndStatusOrderByBusinessDateAsc(
            Long templeId, FinanceCapability capability, DataSubmissionStatus status);

    List<FinDailyDataStatus> findByFulfilledByBatchId(Long syncBatchId);
}

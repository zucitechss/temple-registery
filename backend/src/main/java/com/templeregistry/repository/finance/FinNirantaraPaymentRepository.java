package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinNirantaraPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Money received against perpetual seva bookings (V135, FR11).
 *
 * <p>These amounts may also appear in {@code fin_revenue_fact}. No report sums
 * the two, and none should: FR11 reports per-booking income from here, and the
 * revenue reports read the revenue facts.
 */
@Repository
public interface FinNirantaraPaymentRepository extends JpaRepository<FinNirantaraPayment, Long> {

    Optional<FinNirantaraPayment> findBySourceSystemIdAndSourceRecordRef(Long sourceSystemId,
                                                                          String sourceRecordRef);

    List<FinNirantaraPayment> findBySubscriptionIdOrderByPaidOnAsc(Long subscriptionId);

    List<FinNirantaraPayment> findByTempleIdAndFinancialYear(Long templeId, String financialYear);
}

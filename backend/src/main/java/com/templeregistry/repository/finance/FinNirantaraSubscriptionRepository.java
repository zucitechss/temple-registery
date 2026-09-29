package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinNirantaraSubscription;
import com.templeregistry.entity.finance.enums.NirantaraStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Perpetual seva bookings (V135, FR11). */
@Repository
public interface FinNirantaraSubscriptionRepository
        extends JpaRepository<FinNirantaraSubscription, Long> {

    Optional<FinNirantaraSubscription> findBySourceSystemIdAndSourceRecordRef(Long sourceSystemId,
                                                                               String sourceRecordRef);

    List<FinNirantaraSubscription> findByTempleId(Long templeId);

    /** FR11 chart: bookings per seva type. */
    List<FinNirantaraSubscription> findByTempleIdAndStatus(Long templeId, NirantaraStatus status);
}

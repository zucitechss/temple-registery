package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinServiceDim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The per-temple service catalogue (V120, V131).
 *
 * <p><b>This interface did not exist until Phase 0, and its absence was the
 * problem.</b> {@code fin_service_dim} has been a table since V120 and nothing
 * else: no repository, no seed, no writer. {@code RevenueNormalizer} passes a
 * literal null for {@code service_id}, so the column is not merely nullable but
 * always null — which makes FR8 and FR10 unimplementable and left
 * {@code fin_agg_revenue_service} a table that cannot receive a row
 * (FIN-D-069).
 *
 * <p>Creating the repository does not fix that. Service resolution — seeding
 * the dimension, adding a {@code SERVICE} mapping target and a lookup in the
 * normalizer — is the first task of the reporting stream, and this is the
 * interface it needs to exist first so that nobody else invents a second one.
 */
@Repository
public interface FinServiceDimRepository extends JpaRepository<FinServiceDim, Long> {

    Optional<FinServiceDim> findByTempleIdAndServiceCodeAndDeletedFalse(Long templeId,
                                                                         String serviceCode);

    List<FinServiceDim> findByTempleIdAndActiveTrueAndDeletedFalse(Long templeId);

    /** FR8: the sevas this temple has marked special. Configuration, not a revenue classification. */
    List<FinServiceDim> findByTempleIdAndSpecialTrueAndActiveTrueAndDeletedFalse(Long templeId);
}

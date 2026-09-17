package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinRevenueCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The platform-wide revenue taxonomy.
 *
 * <p>Deliberately not temple-scoped: a taxonomy each temple invents for itself cannot produce a
 * district total, which is the whole reason this table has no {@code temple_id}.
 */
@Repository
public interface FinRevenueCategoryRepository extends JpaRepository<FinRevenueCategory, Long> {

    Optional<FinRevenueCategory> findByCategoryCodeAndDeletedFalse(String categoryCode);

    List<FinRevenueCategory> findByActiveTrueAndDeletedFalse();
}

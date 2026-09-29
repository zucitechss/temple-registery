package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** The canonical expenditure taxonomy (V132). Read by mapping, validation and reporting. */
@Repository
public interface FinExpenseCategoryRepository extends JpaRepository<FinExpenseCategory, Long> {

    Optional<FinExpenseCategory> findByCategoryCodeAndDeletedFalse(String categoryCode);

    List<FinExpenseCategory> findByActiveTrueAndDeletedFalseOrderByDisplayOrderAsc();
}

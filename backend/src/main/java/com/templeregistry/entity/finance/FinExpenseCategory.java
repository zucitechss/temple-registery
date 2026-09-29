package com.templeregistry.entity.finance;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Canonical expenditure taxonomy (V124, FR4).
 *
 * <p>Deliberately separate from {@link FinRevenueCategory}. Sharing one table
 * would let a query group by category across both facts and produce a number
 * that is neither income nor spend.
 *
 * <p>Codes are platform-wide and source-agnostic; a source spelling reaches one
 * of them through {@code fin_mapping_rule} with
 * {@code mapping_type = EXPENSE_CATEGORY}. The {@code UNMAPPED} row is a
 * destination for values no rule covers, and is deliberately unattractive to
 * report.
 */
@Entity
@Table(
    name = "fin_expense_category",
    uniqueConstraints = @UniqueConstraint(name = "uk_fec_code", columnNames = "category_code")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonical code, e.g. SALARIES. Never a source value. */
    @Column(name = "category_code", nullable = false, length = 50)
    private String categoryCode;

    @Column(name = "category_name", nullable = false, length = 150)
    private String categoryName;

    /** What belongs in this category, and what does not. User-facing. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 100;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private java.time.LocalDateTime updatedAt;

    @Builder.Default
    @Column(name = "created_by", nullable = false)
    private Long createdBy = 0L;

    @Builder.Default
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy = 0L;
}

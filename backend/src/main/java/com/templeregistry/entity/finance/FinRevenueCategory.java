package com.templeregistry.entity.finance;

import com.templeregistry.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * The canonical income taxonomy -- platform-wide, identical for every temple.
 *
 * <p>This is what keeps two temples comparable. A source system's own category
 * vocabulary never reaches this table: it is translated by
 * {@code fin_mapping_rule} into one of these codes first, so onboarding a temple
 * adds mapping rows, never taxonomy rows.
 *
 * <p>The distinctions it preserves are the ones a report loses silently. A
 * prasadam sale is retail, not a ritual; a donation-box collection is a counting
 * event, not a service anybody bought. Ranking "top sevas" across a taxonomy that
 * has collapsed those differences produces a confident, wrong answer.
 *
 * <p>Extends {@code BaseEntity} because this is reviewed configuration with real
 * human ownership, matching the other {@code fin_} configuration entities.
 */
@Entity
@Table(
    name = "fin_revenue_category",
    uniqueConstraints = @UniqueConstraint(name = "uk_frc_code", columnNames = "category_code")
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FinRevenueCategory extends BaseEntity {

    /** Canonical code, e.g. {@code SEVA}, {@code HUNDI_DONATION}. Never a source value. */
    @Column(name = "category_code", nullable = false, length = 50)
    private String categoryCode;

    @Column(name = "category_name", nullable = false, length = 150)
    private String categoryName;

    /** What belongs in this category and what does not. User-facing. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 100;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}

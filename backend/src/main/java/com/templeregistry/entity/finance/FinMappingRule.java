package com.templeregistry.entity.finance;

import com.templeregistry.entity.base.BaseEntity;
import com.templeregistry.entity.finance.enums.MappingType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One source-value to canonical-value translation (ADR-004).
 *
 * <p>Connectors are code because source schemas differ structurally; value
 * mapping is configuration because it changes far more often and is the part a
 * non-developer can review. A temple adding a new seva should be a data change,
 * not a release.
 *
 * <p>An unmapped source value is <b>not</b> silently defaulted. It routes to the
 * canonical value {@link #UNMAPPED}, is counted, and is surfaced as a warning --
 * so a newly added seva code appears as an operational signal instead of quietly
 * disappearing from a revenue total.
 */
@Entity
@Table(
    name = "fin_mapping_rule",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fmr_source_type_value",
        columnNames = {"source_system_id", "mapping_type", "source_value"}),
    indexes = @Index(name = "idx_fmr_lookup", columnList = "source_system_id, mapping_type, is_active")
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FinMappingRule extends BaseEntity {

    /** Canonical value assigned when no rule matches a source value. */
    public static final String UNMAPPED = "UNMAPPED";

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_type", nullable = false, length = 40)
    private MappingType mappingType;

    /** Raw value exactly as it appears in the source, e.g. {@code 430}, {@code KN}. */
    @Column(name = "source_value", nullable = false, length = 200)
    private String sourceValue;

    /** Human label from the source. May be in a non-Latin script. */
    @Column(name = "source_label", length = 400)
    private String sourceLabel;

    /** e.g. {@code HUNDI_DONATION}, {@code DONATION}, {@code GOLD}. */
    @Column(name = "canonical_value", nullable = false, length = 100)
    private String canonicalValue;

    /**
     * Precedence where several rules match one record. Higher wins.
     *
     * <p>FIN-D-015 decided that the more specific rule wins — a rule keyed on a single service
     * code beats one keyed on the coarse income bucket that code sits inside — but left the
     * decision in prose, where an engine reading these rows could not act on it. Storing it
     * makes precedence configuration rather than something each connector has to remember.
     *
     * <p>Equal priority with more than one match is not resolved by picking one: it is an
     * {@code AMBIGUOUS} outcome, because published revenue must not depend on the order a
     * database happens to return rows in.
     */
    @Builder.Default
    @Column(name = "priority", nullable = false)
    private int priority = 100;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Optimistic lock (FIN-054A).
     *
     * <p>A mapping rule decides which revenue category a temple's income lands in, so two
     * administrators editing one rule from a list they each loaded a minute ago must not
     * silently resolve to whichever saved second. Without this, the loser of that race gets a
     * success response for a change that no longer exists, and the classification that reaches
     * a published figure is decided by request ordering.
     *
     * <p>Declared here rather than on {@code BaseEntity}, because putting it there would add a
     * lock to every audited entity in the application. The entities that need one declare it
     * individually, as {@code Temple}, {@code Trust} and {@code AssetDeclaration} already do.
     */
    @Version
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 0;
}

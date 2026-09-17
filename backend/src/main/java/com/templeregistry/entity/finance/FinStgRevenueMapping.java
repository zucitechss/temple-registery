package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One recorded decision about what a staged source value means canonically (FIN-054).
 *
 * <p>Standalone rather than extending {@code BaseEntity}, following the operational-log
 * precedent the other pipeline tables use: this is an append-and-correct record of what the
 * pipeline decided, not reviewed configuration with human ownership, and soft-deleting a
 * mapping decision would mean nothing.
 *
 * <p><b>Why this is not columns on {@code fin_stg_revenue}.</b> Staging holds what the source
 * actually sent. Mapping is an interpretation of it, and interpretations change when somebody
 * adds a rule. Evidence that gets rewritten every time the interpretation changes is no longer
 * evidence, so the interpretation lives beside the record rather than inside it.
 *
 * <p>Provenance is denormalised from the staged row on purpose. A decision that cannot say
 * which temple and which run it belongs to cannot be audited once staging has been purged, and
 * staging retention is still unresolved (Q7).
 */
@Entity
@Table(
    name = "fin_stg_revenue_mapping",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fsrm_row_type",
        columnNames = {"stg_revenue_id", "mapping_type"}),
    indexes = {
        @Index(name = "idx_fsrm_batch_type",  columnList = "sync_batch_id, mapping_type, outcome"),
        @Index(name = "idx_fsrm_unmapped",    columnList = "source_system_id, mapping_type, outcome, source_value"),
        @Index(name = "idx_fsrm_temple_type", columnList = "temple_id, mapping_type, outcome")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinStgRevenueMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The staged record this decision is about. */
    @Column(name = "stg_revenue_id", nullable = false)
    private Long stgRevenueId;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** Copied from the staged row so the trail survives a staging purge. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_type", nullable = false, length = 40)
    private MappingType mappingType;

    /**
     * The staged field consulted — the mapping rule's namespace, which is a logical attribute
     * name, never a source table or column name. NULL where no rule namespace was present.
     */
    @Column(name = "source_field", length = 200)
    private String sourceField;

    /** The value found there, exactly as staged. NULL means nothing was found to map. */
    @Column(name = "source_value", length = 200)
    private String sourceValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private MappingOutcome outcome;

    /**
     * Set only where a decision was reached: the rule's canonical value for {@code MAPPED}, and
     * the literal {@code UNMAPPED} category for {@code UNMAPPED}. NULL for every outcome that
     * did not decide, so an undecided row can never be read as having landed somewhere.
     */
    @Column(name = "canonical_value", length = 100)
    private String canonicalValue;

    /** The rule that produced {@link #canonicalValue}. NULL where no single rule won. */
    @Column(name = "mapping_rule_id")
    private Long mappingRuleId;

    /** Recorded so that a later change to precedence is visible against past decisions. */
    @Column(name = "rule_priority")
    private Integer rulePriority;

    /** Actionable in an operator's terms. Never a stack trace, never payload content. */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** When this decision was made. Not a business date. */
    @Column(name = "mapped_at", nullable = false)
    private LocalDateTime mappedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

package com.templeregistry.entity.finance;

import com.templeregistry.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The declared authoritative source field for one financial metric of one source
 * system -- versioned, signed off, and referenced by connector code (ADR-008).
 *
 * <p>Kollur is why this is data rather than a WHERE clause. Three columns there
 * plausibly represent revenue and they disagree materially; the <em>more granular</em>
 * detail-table column is 41% short of the header total, which means a later
 * engineer "improving" the query by moving to it would silently understate
 * revenue on a government oversight dashboard.
 *
 * <p>{@link #rejectedAlternativesJson} is what gives the declaration force: the
 * next person sees not only what was chosen, but that the tempting alternative
 * was measured and found wrong.
 *
 * <p>Changing a declaration creates a <b>new version</b> and a restatement. It
 * never edits history in place, so any previously published figure stays
 * explicable via {@code source_of_truth_version} stamped on the facts.
 *
 * <p><b>There is deliberately no {@code @Version} optimistic lock here</b>, unlike
 * {@code FinSourceSystem} and {@code FinTempleCapability} (FIN-D-083). Those are
 * edited in place, so a lock guards what actually happens to them. This table is
 * append-only: the only in-place write is closing {@code effective_to} on the row
 * being superseded, in the same transaction that inserts its replacement. The race
 * this table does have — two administrators each computing the next version number —
 * is caught by {@code uk_fsotd_source_metric_version}, which an optimistic lock would
 * not have caught, because neither writer overwrites the row they read.
 */
@Entity
@Table(
    name = "fin_source_of_truth_decl",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fsotd_source_metric_version",
        columnNames = {"source_system_id", "metric", "version"}),
    indexes = @Index(name = "idx_fsotd_current", columnList = "source_system_id, metric, effective_to")
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FinSourceOfTruthDecl extends BaseEntity {

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** e.g. {@code REVENUE_AMOUNT}, {@code PRECIOUS_METAL_WEIGHT}. */
    @Column(name = "metric", nullable = false, length = 50)
    private String metric;

    /** Incremented on change; an existing version is never overwritten. */
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /** Source object the value comes from, e.g. {@code DailySevaNew}. */
    @Column(name = "source_object", nullable = false, length = 200)
    private String sourceObject;

    /** Source field, e.g. {@code Amount}. */
    @Column(name = "source_field", nullable = false, length = 100)
    private String sourceField;

    /** Filter that defines which source rows count, e.g. exclusion of cancelled bills. */
    @Column(name = "filter_predicate", columnDefinition = "TEXT")
    private String filterPredicate;

    /** Candidates considered, their measured totals, and why each was rejected. */
    @Column(name = "rejected_alternatives_json", columnDefinition = "JSON")
    private String rejectedAlternativesJson;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** {@code null} means this version is currently in force. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;
}

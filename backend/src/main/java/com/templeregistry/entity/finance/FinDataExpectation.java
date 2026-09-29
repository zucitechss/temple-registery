package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.FinanceCapability;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * What a temple owes each day, and by when (V137, FR17).
 *
 * <p>Without this, missed is undefined: nothing says that one temple owes daily
 * expenditure and another owes nothing. {@link FinTempleCapability} says what a
 * source <em>can</em> supply; this says what a temple is <em>required</em> to
 * submit, and carries the deadline.
 *
 * <p>Expectations exist only for manual and file-upload channels. FR17 applies
 * to any temple without an automated connector, and
 * {@code ConnectorType.isAutomated()} is exactly that test — so a temple that
 * later gets a connector stops being alerted the moment its source is switched
 * over, with no list to keep in step.
 *
 * <p><b>{@link #cutoffLocalTime} is a wall clock, not an instant.</b> It is
 * resolved against {@code fin_source_system.source_timezone}. Every existing
 * scheduler in this codebase is pinned to UTC, and a 22:00 UTC cutoff is 03:30
 * the next morning in Asia/Kolkata: it would mark a day missed five and a half
 * hours after the agreed deadline, silently.
 *
 * <p>{@link #activeFrom} is set at onboarding so that a newly onboarded temple
 * does not immediately owe a year of history. Earlier periods are a one-off
 * bulk upload, not a wall of alerts.
 */
@Entity
@Table(
    name = "fin_data_expectation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fde_temple_source_capability",
        columnNames = {"temple_id", "source_system_id", "capability"}),
    indexes = @Index(name = "idx_fde_active", columnList = "is_deleted, active_from, active_to")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinDataExpectation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** The manual or file-upload channel this obligation belongs to. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    /** What is owed. Per capability, because a temple may owe expenditure and nothing else. */
    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 50)
    private FinanceCapability capability;

    /**
     * DAILY for now. The column exists because WEEKLY is the first thing anyone
     * will ask for, and adding it later would mean backfilling every row.
     */
    @Builder.Default
    @Column(name = "cadence", nullable = false, length = 20)
    private String cadence = "DAILY";

    /** FR17 end of day. LOCAL wall clock, resolved against the source timezone. Never UTC. */
    @Builder.Default
    @Column(name = "cutoff_local_time", nullable = false)
    private LocalTime cutoffLocalTime = LocalTime.of(22, 0);

    /** Expected days are generated from here. Set at onboarding. */
    @Column(name = "active_from", nullable = false)
    private LocalDate activeFrom;

    /** Null means still expected. */
    @Column(name = "active_to")
    private LocalDate activeTo;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder.Default
    @Column(name = "created_by", nullable = false)
    private Long createdBy = 0L;

    @Builder.Default
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy = 0L;
}

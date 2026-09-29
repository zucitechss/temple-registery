package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.AlertSeverity;
import com.templeregistry.entity.finance.enums.AlertStatus;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A standing missed-entry condition (V129, FR17, FR18, FR20).
 *
 * <p><b>Why this is not an {@code InAppNotification}.</b> The platform already
 * has notifications, rules, an outbox, delivery preferences and an SSE channel,
 * and this feature uses all of them to <em>deliver</em> transitions. But a
 * notification is a message that was sent: it cannot escalate, it cannot clear,
 * and it cannot answer which temples are in breach right now — which is what
 * both dashboards display. This entity holds that state; the notification stack
 * announces its changes.
 *
 * <p><b>{@link #consecutiveMissedDays} is recomputed, never incremented.</b>
 * That single decision is what makes the rest correct. A temple twelve days
 * behind is HIGH; it uploads days 3 to 12; two days remain; the next evaluation
 * recomputes 2 and the alert de-escalates to LOW rather than closing or staying
 * high. An incremented counter could not do that, and an hourly job that ran
 * twice would double it.
 *
 * <p>{@link #escalatedAt} is kept through a later de-escalation, because the
 * fact that this temple was once eight days behind is part of the record even
 * after the gap shrinks.
 *
 * <p>There is deliberately no acknowledged state. FR20 clears the alert on
 * submission; a dismissable alert could be cleared without the data ever
 * arriving.
 */
@Entity
@Table(
    name = "fin_data_alert",
    indexes = {
        @Index(name = "idx_fda_open",        columnList = "temple_id, capability, status"),
        @Index(name = "idx_fda_dashboard",   columnList = "status, severity, temple_id"),
        @Index(name = "idx_fda_expectation", columnList = "expectation_id, status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinDataAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_data_expectation.id}. */
    @Column(name = "expectation_id", nullable = false)
    private Long expectationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 50)
    private FinanceCapability capability;

    /** Earliest still-missing day in the current gap. Moves later when an early day is backfilled. */
    @Column(name = "first_missed_date", nullable = false)
    private LocalDate firstMissedDate;

    /** Most recent missing day, normally the last evaluated cutoff. */
    @Column(name = "last_missed_date", nullable = false)
    private LocalDate lastMissedDate;

    /** Derived from {@link FinDailyDataStatus} on every evaluation. Never incremented. */
    @Column(name = "consecutive_missed_days", nullable = false)
    private Integer consecutiveMissedDays;

    /** A function of {@link #consecutiveMissedDays}, so it can go down as well as up. */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private AlertSeverity severity;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AlertStatus status = AlertStatus.OPEN;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    /** When it first became HIGH. Kept through a later de-escalation. */
    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /**
     * When the job last recomputed this row. Proves the job is running, which a
     * quiet alert otherwise cannot: an alert that has not changed and an alert
     * nothing is evaluating look identical without it.
     */
    @Column(name = "last_evaluated_at", nullable = false)
    private LocalDateTime lastEvaluatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

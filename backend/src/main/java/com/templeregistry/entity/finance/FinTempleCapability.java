package com.templeregistry.entity.finance;

import com.templeregistry.entity.base.BaseEntity;
import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What one temple can actually answer for one financial capability, and -- when it
 * cannot -- why (ADR-007).
 *
 * <p>This is the mechanism that keeps missing data from being rendered as zero.
 * Reports consult the declaration instead of inferring availability from the
 * presence or absence of fact rows, because those two situations look identical
 * in a query and mean entirely different things.
 *
 * <p>{@link #availabilityReason} is <b>user-facing text</b>, written during
 * onboarding and reviewed -- not a developer note. The dashboard renders it
 * verbatim, so an empty panel explains itself rather than looking broken.
 */
@Entity
@Table(
    name = "fin_temple_capability",
    uniqueConstraints = @UniqueConstraint(name = "uk_ftc_temple_capability", columnNames = {"temple_id", "capability"}),
    indexes = {
        @Index(name = "idx_ftc_source",       columnList = "source_system_id"),
        @Index(name = "idx_ftc_availability", columnList = "availability")
    }
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FinTempleCapability extends BaseEntity {

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 50)
    private FinanceCapability capability;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability", nullable = false, length = 30)
    private DataAvailability availability;

    /** Shown to the user verbatim when data is absent or partial. */
    @Column(name = "availability_reason", columnDefinition = "TEXT")
    private String availabilityReason;

    /** Earliest date for which this capability has data. */
    @Column(name = "coverage_from")
    private LocalDate coverageFrom;

    /**
     * Latest date present <em>in the source</em>. This is not the last sync time:
     * Kollur data currently ends weeks before the present, and conflating the two
     * would present stale data as current.
     */
    @Column(name = "coverage_to")
    private LocalDate coverageTo;

    /** Documented holes inside the coverage window, as JSON. */
    @Column(name = "known_gaps_json", columnDefinition = "JSON")
    private String knownGapsJson;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;
}

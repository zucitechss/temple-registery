package com.templeregistry.dto.response.finance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One version of one source-of-truth declaration (FIN-140-C, ADR-008).
 *
 * <p>Superseded versions are returned alongside the one in force. They are the reason the table
 * is versioned: a fact loaded last year carries the version that produced it, and a reader asking
 * why a figure changed needs the row that used to be right, not only the row that is right now.
 * {@link #inForce()} is the only thing distinguishing them, computed from {@code effectiveTo}
 * rather than stored, so it cannot drift from the column the pipeline actually reads.
 *
 * <p>Carries no credential reference, no connector bean, no database name, no host and no port.
 * {@code sourceObject} and {@code sourceField} are here because they <em>are</em> the declaration
 * — ADR-008 exists to make exactly those two reviewable — and the endpoints returning them are
 * administrator-only for that reason.
 */
public record SourceOfTruthDeclarationResponse(
        Long id,
        Long sourceSystemId,
        String metric,
        Integer version,
        boolean inForce,
        String sourceObject,
        String sourceField,
        String filterPredicate,

        /**
         * The rejected candidates as stored, key by key.
         *
         * <p>Deliberately untyped. The declarations seeded for the first onboarded source carry
         * their own measurement keys — {@code measuredRows}, {@code measuredAmountFy2025_26} —
         * and a fixed record would silently drop the measured evidence that gives those rows
         * their force. A renderer showing whatever keys are present shows all of it.
         */
        List<Map<String, String>> rejectedAlternatives,

        String rationale,
        boolean approved,
        Long approvedBy,
        LocalDateTime approvedAt,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

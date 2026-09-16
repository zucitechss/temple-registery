package com.templeregistry.connector.finance;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A closed or open range of <b>business dates</b> -- the date a transaction belongs to, not
 * the date a record was last changed.
 *
 * <p>The distinction is load-bearing. Extraction advances along a change-timestamp axis
 * (what has been modified since the last successful sync); reconciliation and restatement
 * work along a business-date axis (what belongs to FY2025-26). Using one range type for
 * both would invite a connector to filter reconciliation by modification time, which would
 * silently exclude older records that were never touched and make a total look correct
 * because it compared two subsets of the same shape.
 *
 * <p>Both bounds are optional so that a full-history comparison needs no sentinel dates.
 * Bounds are inclusive.
 */
public record DateRange(Optional<LocalDate> from, Optional<LocalDate> to) {

    public DateRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("DateRange bounds must be Optional, not null.");
        }
        if (from.isPresent() && to.isPresent() && from.get().isAfter(to.get())) {
            throw new IllegalArgumentException(
                    "DateRange start " + from.get() + " is after end " + to.get() + ".");
        }
    }

    public static DateRange of(LocalDate from, LocalDate to) {
        return new DateRange(Optional.of(from), Optional.of(to));
    }

    /** Everything the source holds, with no date bound in either direction. */
    public static DateRange unbounded() {
        return new DateRange(Optional.empty(), Optional.empty());
    }

    public static DateRange upTo(LocalDate to) {
        return new DateRange(Optional.empty(), Optional.of(to));
    }

    public boolean isUnbounded() {
        return from.isEmpty() && to.isEmpty();
    }
}

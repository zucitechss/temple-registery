package com.templeregistry.dto.response.finance;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * When the platform last talked to a source, and separately, how current that source's own data
 * is (FIN-080, API_CONTRACT §3).
 *
 * <p>The two dates are deliberately never merged. {@code lastSyncedAt} answers "is the pipeline
 * working"; {@code sourceDataThrough} answers "how current is what it found" — a source can be
 * synced successfully every night and still have no record newer than several weeks ago, and
 * reporting only the sync time would present that gap as current data.
 */
public record DataFreshnessBlock(
        LocalDateTime lastSyncedAt,
        LocalDate sourceDataThrough,
        Status status,
        String stalenessReason) {

    public enum Status {
        FRESH,
        /** Covers both "past the staleness threshold" and "never synced at all". */
        STALE
    }
}

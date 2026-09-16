package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.SyncType;

import java.time.Instant;
import java.util.Optional;

/**
 * Everything a connector needs to know about one extraction request.
 *
 * <p>Two independent axes are carried, and conflating them is a data-loss bug:
 *
 * <ul>
 *   <li>{@link #changedSince()} / {@link #changedUpTo()} -- the <b>change axis</b>. Which
 *       records the source has created or modified in this interval.</li>
 *   <li>{@link #businessDateRange()} -- the <b>business-date axis</b>. Which transaction
 *       dates are in scope, used for a historical load and for the bounded restatement
 *       window that catches back-dated entries and late cancellations.</li>
 * </ul>
 *
 * <p><b>{@link #changedSince()} is the watermark of the last SUCCESSFUL batch</b>, never
 * the last attempted one (FIN-D-005). The distinction is the difference between re-reading
 * a window that failed and skipping past it forever, and the field is named to make the
 * wrong reading hard.
 *
 * <p><b>A connector never returns a watermark.</b> The framework chooses
 * {@link #changedUpTo()} in advance and, only if the batch succeeds, records it as the new
 * watermark. A connector that proposed its own high-water mark would make it possible for a
 * failed batch to advance one, which is precisely what ADR-006 forbids.
 */
public record SyncContext(
        SourceSystemDescriptor source,
        String batchRef,
        SyncType syncType,
        Optional<Instant> changedSince,
        Instant changedUpTo,
        DateRange businessDateRange) {

    public SyncContext {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null.");
        }
        if (batchRef == null || batchRef.isBlank()) {
            throw new IllegalArgumentException("batchRef must not be blank: it is the audit key for this run.");
        }
        if (syncType == null) {
            throw new IllegalArgumentException("syncType must not be null.");
        }
        if (changedSince == null) {
            throw new IllegalArgumentException("changedSince must be Optional, not null.");
        }
        if (changedUpTo == null) {
            throw new IllegalArgumentException("changedUpTo must not be null.");
        }
        if (businessDateRange == null) {
            throw new IllegalArgumentException("businessDateRange must not be null.");
        }
        if (changedSince.isPresent() && changedSince.get().isAfter(changedUpTo)) {
            throw new IllegalArgumentException(
                    "changedSince " + changedSince.get() + " is after changedUpTo " + changedUpTo + ".");
        }
        if (syncType == SyncType.HISTORICAL && changedSince.isPresent()) {
            throw new IllegalArgumentException(
                    "A HISTORICAL load must not carry a watermark: it reads everything in the business-date "
                            + "range, and a watermark would silently exclude records never modified since.");
        }
    }

    /** True when there is no prior successful sync to resume from. */
    public boolean isFirstRun() {
        return changedSince.isEmpty();
    }
}

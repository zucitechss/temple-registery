package com.templeregistry.connector.finance;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Totals computed <b>by the source system itself</b>, for reconciliation.
 *
 * <p>The value of these numbers depends entirely on them being independent. A total
 * produced by re-summing rows the platform already extracted would prove only that the
 * platform can add up its own copy; it would agree with canonical data even when the
 * extraction query was wrong, which is the failure reconciliation exists to catch.
 *
 * <p><b>An absent metric means "not available", never zero.</b> {@link #total(ReconMetric)}
 * returns an empty {@link Optional} rather than {@code BigDecimal.ZERO} so that a source
 * which cannot compute a figure can never be recorded as a source which reported nothing
 * (ADR-007). The reconciliation layer maps absence to {@code NOT_AVAILABLE}, which is a
 * different outcome from a pass.
 */
public record SourceTotals(Map<ReconMetric, BigDecimal> values) {

    public SourceTotals {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null; use SourceTotals.notAvailable().");
        }
        Map<ReconMetric, BigDecimal> copy = new EnumMap<>(ReconMetric.class);
        values.forEach((metric, amount) -> {
            if (amount == null) {
                throw new IllegalArgumentException(
                        "Metric " + metric + " was given a null total. Omit the metric entirely to report "
                                + "NOT_AVAILABLE; a null would be indistinguishable from zero downstream.");
            }
            copy.put(metric, amount);
        });
        values = Collections.unmodifiableMap(copy);
    }

    /** The source could compute nothing for this capability and period. */
    public static SourceTotals notAvailable() {
        return new SourceTotals(Map.of());
    }

    public static SourceTotals of(ReconMetric metric, BigDecimal total) {
        return new SourceTotals(Map.of(metric, total));
    }

    /** Empty when the source did not report this metric -- not available, not zero. */
    public Optional<BigDecimal> total(ReconMetric metric) {
        return Optional.ofNullable(values.get(metric));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}

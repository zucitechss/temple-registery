package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One reported number, wrapped so absence and presence share one shape (FIN-080, API_CONTRACT §2).
 *
 * <p>{@code value} is {@code null} exactly when {@code availability} is not {@code AVAILABLE} or
 * {@code PARTIALLY_AVAILABLE}. A consumer never branches on "is this field present" — it always
 * reads {@code availability} and renders {@code reason} when the value is absent. This is the one
 * shape every numeric metric in the finance API uses (ADR-007): converting "we do not know" into a
 * zero is exactly what this envelope exists to prevent.
 *
 * <p>{@code availability} and {@code reconciliation} are the platform's own enums
 * ({@link DataAvailability}, {@link ReconciliationStatus}), not a parallel DTO-level vocabulary —
 * {@code ReconciliationStatus}'s own javadoc anticipates this exact use.
 */
public record MetricEnvelope(
        BigDecimal value,
        String unit,
        DataAvailability availability,
        String reason,
        LocalDate asOfDate,
        ReconciliationStatus reconciliation) {

    public static MetricEnvelope available(BigDecimal value, String unit, LocalDate asOfDate,
                                           ReconciliationStatus reconciliation) {
        return new MetricEnvelope(value, unit, DataAvailability.AVAILABLE, null, asOfDate, reconciliation);
    }

    public static MetricEnvelope partiallyAvailable(BigDecimal value, String unit, LocalDate asOfDate,
                                                     ReconciliationStatus reconciliation, String reason) {
        return new MetricEnvelope(
                value, unit, DataAvailability.PARTIALLY_AVAILABLE, reason, asOfDate, reconciliation);
    }

    public static MetricEnvelope notAvailable(String reason) {
        return new MetricEnvelope(null, null, DataAvailability.NOT_AVAILABLE, reason, null,
                ReconciliationStatus.NOT_AVAILABLE);
    }
}

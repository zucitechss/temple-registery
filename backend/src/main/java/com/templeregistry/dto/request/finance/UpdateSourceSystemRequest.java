package com.templeregistry.dto.request.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Change a registered source system's metadata (FIN-140 slice 140-A).
 *
 * <p>{@code templeId} and {@code systemCode} are absent on purpose. Both are identity: the pair is
 * the unique key, capability rows and facts already point at the temple, and moving a source
 * system to a different temple would silently reattribute every figure it has loaded. Retire the
 * row and register a new one instead.
 *
 * <p>{@code syncEnabled} is absent for the reason given on {@code RegisterSourceSystemRequest}:
 * activation is a separate gated act, not a metadata edit.
 *
 * <p>{@code credentialRef} is tri-state. Omitted leaves the stored alias untouched; a value
 * replaces it; an empty string clears it. It has to work that way because the current value is
 * never returned, so a client cannot echo it back and "absent" cannot mean "clear".
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateSourceSystemRequest {

    /**
     * The version the caller loaded. Checked explicitly.
     *
     * <p>The race this has is not two simultaneous commits but two administrators who opened the
     * same source system minutes apart — Hibernate's own check passes for both, and the earlier
     * reader's change vanishes with nobody told.
     */
    @NotNull(message = "version is required — it is how a stale edit is detected.")
    private Integer version;

    @NotBlank(message = "systemName is required.")
    @Size(max = 200, message = "systemName must be at most 200 characters.")
    private String systemName;

    @NotNull(message = "sourceTechnology is required.")
    private SourceTechnology sourceTechnology;

    @NotNull(message = "connectorType is required.")
    private ConnectorType connectorType;

    @NotBlank(message = "connectorBean is required — it names the connector the worker resolves.")
    @Size(max = 150, message = "connectorBean must be at most 150 characters.")
    private String connectorBean;

    @Size(max = 50, message = "sourceTempleCode must be at most 50 characters.")
    private String sourceTempleCode;

    @Size(max = 100, message = "sourceDatabaseName must be at most 100 characters.")
    private String sourceDatabaseName;

    /** Omit to keep the stored alias, send a value to replace it, send "" to clear it. */
    @Size(max = 200, message = "credentialRef must be at most 200 characters.")
    private String credentialRef;

    @Size(max = 50, message = "syncScheduleCron must be at most 50 characters.")
    private String syncScheduleCron;

    @Min(value = 1, message = "stalenessThresholdHours must be between 1 and 8760.")
    @Max(value = 8760, message = "stalenessThresholdHours must be between 1 and 8760.")
    private Integer stalenessThresholdHours;

    @Size(max = 50, message = "sourceTimezone must be at most 50 characters.")
    private String sourceTimezone;

    @Size(max = 4000, message = "notes must be at most 4000 characters.")
    private String notes;
}

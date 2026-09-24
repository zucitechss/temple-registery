package com.templeregistry.dto.request.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Register a temple's finance source system (FIN-140 slice 140-A).
 *
 * <p>Registering a source never starts traffic to a temple. {@code syncEnabled} is not a field
 * here and cannot be set through this request: the schema defaults it off, and switching it on is
 * a separate deliberate act gated on readiness, which slice 140-A does not implement.
 *
 * <p>{@code credentialRef} is an <b>alias</b> resolved by the sync worker from its own
 * environment. It is accepted here and never returned by any endpoint. No field on this request
 * accepts a password, a host, a port, a URL or a connection string, and none exists on the row it
 * writes.
 */
@Getter
@Setter
@NoArgsConstructor
public class RegisterSourceSystemRequest {

    @NotNull(message = "templeId is required.")
    private Long templeId;

    /**
     * Stable short code, e.g. {@code KOLSOHAM}. Unique per temple.
     *
     * <p>Constrained to upper-case letters, digits and underscore because it is quoted in support
     * conversations, log lines and audit detail, where a code differing from another only by case
     * or a stray space is a code nobody can search for reliably.
     */
    @NotBlank(message = "systemCode is required.")
    @Size(max = 50, message = "systemCode must be at most 50 characters.")
    @Pattern(regexp = "[A-Z0-9_]+",
             message = "systemCode may contain only upper-case letters, digits and underscore.")
    private String systemCode;

    @NotBlank(message = "systemName is required.")
    @Size(max = 200, message = "systemName must be at most 200 characters.")
    private String systemName;

    @NotNull(message = "sourceTechnology is required.")
    private SourceTechnology sourceTechnology;

    @NotNull(message = "connectorType is required.")
    private ConnectorType connectorType;

    /**
     * Spring bean name of the connector implementation.
     *
     * <p>Required because the column is, and because a source with no named connector can never
     * run. Naming one does not create one: the bean is resolved only inside the sync worker, and
     * only if a release has deployed a class registered under that name.
     */
    @NotBlank(message = "connectorBean is required — it names the connector the worker resolves.")
    @Size(max = 150, message = "connectorBean must be at most 150 characters.")
    private String connectorBean;

    /** Temple identifier as used inside the source system, e.g. {@code 43}. */
    @Size(max = 50, message = "sourceTempleCode must be at most 50 characters.")
    private String sourceTempleCode;

    /** Documentation only. Never used to build a connection. */
    @Size(max = 100, message = "sourceDatabaseName must be at most 100 characters.")
    private String sourceDatabaseName;

    /** Secret alias only. Never a credential value, and never returned. */
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

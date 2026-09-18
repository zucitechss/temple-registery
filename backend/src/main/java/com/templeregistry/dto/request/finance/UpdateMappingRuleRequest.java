package com.templeregistry.dto.request.finance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An edit to an existing translation (FIN-054A).
 *
 * <p>{@code sourceSystemId} and {@code mappingType} are absent on purpose. Moving a rule to
 * another source system is not an edit to that rule — it is a rule about a different temple's
 * vocabulary, and allowing it here would let one request change which temple's scope the rule had
 * been authorized against.
 *
 * <p>{@code version} is required. An edit that does not say which version it was written against
 * cannot be checked for a concurrent change, and the whole point of holding a version on this
 * entity is that a mapping rule is a financial control rather than a preference.
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateMappingRuleRequest {

    @NotNull(message = "version is required so a concurrent edit can be detected.")
    private Integer version;

    @NotBlank(message = "namespace is required — it names the staged field this rule reads.")
    @Size(max = 100, message = "namespace must be at most 100 characters.")
    private String namespace;

    @NotBlank(message = "sourceValue is required.")
    @Size(max = 99, message = "sourceValue must be at most 99 characters.")
    private String sourceValue;

    @Size(max = 400, message = "sourceLabel must be at most 400 characters.")
    private String sourceLabel;

    @NotBlank(message = "canonicalValue is required.")
    @Size(max = 100, message = "canonicalValue must be at most 100 characters.")
    private String canonicalValue;

    @Min(value = 0, message = "priority must be between 0 and 1000.")
    @Max(value = 1000, message = "priority must be between 0 and 1000.")
    private Integer priority;

    private Boolean active;

    @Size(max = 2000, message = "notes must be at most 2000 characters.")
    private String notes;
}

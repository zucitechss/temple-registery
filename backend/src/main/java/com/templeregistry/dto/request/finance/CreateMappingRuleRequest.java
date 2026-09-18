package com.templeregistry.dto.request.finance;

import com.templeregistry.entity.finance.enums.MappingType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A new source-value translation (FIN-054A).
 *
 * <p>The namespace and the value are separate fields here and a single column in the database.
 * That is deliberate: the stored form {@code SEVA_CODE:430} is one string whose first colon is
 * structural, and a form that asked for it as one string would let a user write {@code 430} and be
 * told nothing until a run a day later reported the value unmapped again. Splitting it makes the
 * field name a thing the server can check and the client can offer from a list.
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateMappingRuleRequest {

    @NotNull(message = "sourceSystemId is required.")
    private Long sourceSystemId;

    @NotNull(message = "mappingType is required.")
    private MappingType mappingType;

    /** The staged field this rule reads, e.g. {@code SEVA_CODE}. Becomes the stored namespace. */
    @NotBlank(message = "namespace is required — it names the staged field this rule reads.")
    @Size(max = 100, message = "namespace must be at most 100 characters.")
    private String namespace;

    /** The raw value as the source writes it, e.g. {@code 430}. Matched exactly. */
    @NotBlank(message = "sourceValue is required.")
    @Size(max = 99, message = "sourceValue must be at most 99 characters.")
    private String sourceValue;

    @Size(max = 400, message = "sourceLabel must be at most 400 characters.")
    private String sourceLabel;

    @NotBlank(message = "canonicalValue is required.")
    @Size(max = 100, message = "canonicalValue must be at most 100 characters.")
    private String canonicalValue;

    /** Higher wins where several rules match one record. Defaults to the schema default. */
    @Min(value = 0, message = "priority must be between 0 and 1000.")
    @Max(value = 1000, message = "priority must be between 0 and 1000.")
    private Integer priority;

    private Boolean active;

    @Size(max = 2000, message = "notes must be at most 2000 characters.")
    private String notes;
}

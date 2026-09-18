package com.templeregistry.dto.request.finance;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Retire or reinstate a rule without restating the rest of it (FIN-054A). */
@Getter
@Setter
@NoArgsConstructor
public class MappingRuleStatusRequest {

    @NotNull(message = "active is required.")
    private Boolean active;

    @NotNull(message = "version is required so a concurrent edit can be detected.")
    private Integer version;
}

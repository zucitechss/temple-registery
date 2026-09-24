package com.templeregistry.dto.request.finance;

import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * Declare what a source system can answer for one financial question (FIN-140-B).
 *
 * <p>No {@code templeId} field. The temple is resolved from the source system on the server, for
 * the reason the Source Mapper gives: a request that carries its own answer to "whose data is this"
 * is not a request that has been authorized. It also makes "a declaration cannot be created for an
 * unrelated temple" true by construction rather than by a check that could be forgotten.
 *
 * <p>{@code availabilityReason} is user-facing copy. Where a figure would otherwise appear, a
 * reader is shown this sentence verbatim — so it is required whenever the capability is anything
 * other than {@code AVAILABLE}, and an empty one would render an empty explanation.
 */
@Getter
@Setter
@NoArgsConstructor
public class DeclareCapabilityRequest {

    @NotNull(message = "capability is required.")
    private FinanceCapability capability;

    @NotNull(message = "availability is required.")
    private DataAvailability availability;

    /** Required unless availability is AVAILABLE. Shown to a reader verbatim. */
    @Size(max = 4000, message = "availabilityReason must be at most 4000 characters.")
    private String availabilityReason;

    /** Earliest date this capability has data for. */
    private LocalDate coverageFrom;

    /** Latest date present in the source — never the last sync time. */
    private LocalDate coverageTo;

    /** Documented holes inside the coverage window. Each entry is shown to a reader. */
    private List<@Size(max = 500, message = "each known gap must be at most 500 characters.") String> knownGaps;
}

package com.templeregistry.dto.request.finance;

import com.templeregistry.entity.finance.enums.DataAvailability;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * Revise an existing capability declaration (FIN-140-B).
 *
 * <p>{@code capability} is absent: it is identity. {@code uk_ftc_temple_capability} is
 * {@code (temple_id, capability)}, so changing it would not edit this declaration but silently
 * become a different one — and collide with whatever already occupies that slot. Withdraw the
 * declaration by setting it {@code NOT_APPLICABLE} and declare the other capability separately.
 *
 * <p>{@code sourceSystemId} and {@code templeId} are absent for the same reason, and because a
 * declaration that could be moved between source systems could reattribute what a temple is said
 * to be capable of.
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateCapabilityDeclarationRequest {

    /**
     * The version the caller loaded. Checked explicitly.
     *
     * <p>The race here is not two simultaneous commits but two administrators who opened the same
     * declaration minutes apart — Hibernate's own check passes for both, and the earlier reader's
     * change vanishes with nobody told.
     */
    @NotNull(message = "version is required — it is how a stale edit is detected.")
    private Integer version;

    @NotNull(message = "availability is required.")
    private DataAvailability availability;

    @Size(max = 4000, message = "availabilityReason must be at most 4000 characters.")
    private String availabilityReason;

    private LocalDate coverageFrom;

    private LocalDate coverageTo;

    private List<@Size(max = 500, message = "each known gap must be at most 500 characters.") String> knownGaps;
}

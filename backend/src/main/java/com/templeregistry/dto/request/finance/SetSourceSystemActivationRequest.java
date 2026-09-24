package com.templeregistry.dto.request.finance;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Enable or disable a source system for future synchronisation (FIN-140-D).
 *
 * <p>One request for both directions, carrying the <b>desired end state</b> rather than an
 * instruction to toggle. That is what makes the operation idempotent: two administrators who both
 * want it enabled get one enabled source and one audit line, and a repeated call is a no-op rather
 * than an error.
 *
 * <p>It is also why there is <b>no {@code version} field</b>, unlike the source system update
 * request. An optimistic lock there protects a dozen fields where a lost update silently discards
 * somebody's edit. Here the caller states the outcome they want, so a concurrent write either
 * wanted the same outcome or is a later decision that should win — and requiring a version would
 * turn a harmless repeat into a conflict.
 */
@Getter
@Setter
@NoArgsConstructor
public class SetSourceSystemActivationRequest {

    /**
     * {@code true} to enable this source for future synchronisation, {@code false} to disable it.
     *
     * <p>Enabling requires readiness to carry no blocking finding. Disabling never does: a switch
     * that can only be turned on is not a switch, and the moment an administrator most needs to
     * turn a source off is the moment its configuration has gone wrong.
     */
    @NotNull(message = "enabled is required — say which state you want rather than toggling.")
    private Boolean enabled;

    /**
     * Why. Required when disabling, optional when enabling.
     *
     * <p>Disabling stops a temple's financial data flowing once the worker path exists, and the
     * audit trail is read by people who were not in the room. "Somebody switched this off" is not
     * something anyone can act on; "switched off because the source is being migrated" is.
     */
    @Size(max = 1000, message = "reason must be at most 1000 characters.")
    private String reason;
}

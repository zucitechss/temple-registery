package com.templeregistry.connector.finance;

import java.time.Instant;

/**
 * The outcome of asking a connector whether its source is currently usable.
 *
 * <p>Named for the question rather than the mechanism. For a pull connector this is a
 * connection attempt; for a push connector it is whether an agent has registered and
 * delivered recently; for a file drop it is whether an expected export is present. The
 * framework asks the same question of all four and does not need to know which was done.
 *
 * <p>{@link #detail()} is shown to an operator during onboarding, so it should say what was
 * wrong in terms they can act on -- and must never contain a credential, an endpoint or any
 * other value that would be unsafe to display or log.
 */
public record SourceProbeResult(boolean usable, String detail, Instant checkedAt) {

    public SourceProbeResult {
        if (checkedAt == null) {
            throw new IllegalArgumentException("checkedAt must not be null.");
        }
    }

    public static SourceProbeResult usable(String detail) {
        return new SourceProbeResult(true, detail, Instant.now());
    }

    public static SourceProbeResult unusable(String detail) {
        return new SourceProbeResult(false, detail, Instant.now());
    }
}

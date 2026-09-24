package com.templeregistry.dto.response.finance;

import com.templeregistry.service.finance.onboarding.ReadinessFinding;
import com.templeregistry.service.finance.onboarding.ReadinessStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether a source system's configuration is coherent enough to switch on (FIN-140, FIN-032
 * Class A).
 *
 * <h2>{@code connectivityVerified} is always false, and says so</h2>
 *
 * <p>It is a constant, not a bug and not a placeholder. The registry runtime holds no connector
 * and no credential and a test asserts it never will (ADR-001), so nothing reachable from an HTTP
 * request can establish that a temple's system answers. A field that merely stayed absent would
 * let a reader infer that {@code READY} meant connected. Stating the boundary in the payload is
 * the same discipline as the availability model's reason text: the platform says what it does not
 * know rather than leaving a confident-looking gap.
 *
 * <p>It becomes meaningful when a worker-side probe exists (FIN-032 Class B), which is deferred
 * until a connector exists to probe with.
 *
 * @param status             the worst finding present, or READY when there are none
 * @param activationAllowed  false when any finding is blocking. Reported, not acted on: slice
 *                           140-A has no activation endpoint
 * @param syncEnabled        the current kill-switch state, for context
 * @param evaluatedAt        when this was computed. Never stored — a persisted verdict goes stale
 *                           the moment a mapping rule changes, and a validator that tells an
 *                           administrator a configuration is clean because it was clean an hour
 *                           ago is worse than none
 */
public record SourceSystemReadinessResponse(Long sourceSystemId,
                                            Long templeId,
                                            String templeName,
                                            String systemCode,
                                            ReadinessStatus status,
                                            boolean activationAllowed,
                                            boolean syncEnabled,
                                            boolean connectivityVerified,
                                            String connectivityNote,
                                            int blockingCount,
                                            int warningCount,
                                            List<ReadinessFinding> findings,
                                            LocalDateTime evaluatedAt) {
}

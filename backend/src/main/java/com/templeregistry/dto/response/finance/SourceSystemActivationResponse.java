package com.templeregistry.dto.response.finance;

import com.templeregistry.service.finance.onboarding.ReadinessStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What enabling a source system did, and — more importantly — what it did not (FIN-140-D).
 *
 * <h2>Why this is not {@code {"active": true}}</h2>
 *
 * <p>A bare boolean called "active" would be read as "synchronising", and today that would be
 * false in three separate ways at once: no connector implementation exists, nothing consumes
 * {@code sync_enabled}, and no source database has ever been contacted. The flag this endpoint
 * sets is a <b>permission</b> — the plan's own state model calls it "may the platform contact the
 * source?" — and a permission granted to machinery that does not yet exist is worth saying out
 * loud rather than dressing up.
 *
 * <p>{@link #syncInfrastructureAvailable()} and {@link #connectorDeploymentVerified()} are
 * therefore constants, in exactly the discipline {@code SourceSystemReadinessResponse} already
 * uses for {@code connectivityVerified}: the platform states what it does not know instead of
 * leaving a confident-looking gap. The second is a constant for a structural reason, not a
 * temporary one — the connector registry lives in the sync-worker runtime, and this runtime holds
 * no part of it by test-enforced design (ADR-001), so this process genuinely cannot tell whether
 * the named connector has been deployed.
 *
 * <p>Carries no credential reference, connector bean, database name, host or port.
 *
 * @param enabledForSync              the state after this call — a permission, never an activity
 * @param changed                     false when the source was already in the requested state.
 *                                    Makes idempotency visible rather than leaving a caller to
 *                                    infer it from an unchanged value
 * @param readinessStatus             the verdict this call was gated on, recomputed here
 * @param syncInfrastructureAvailable always false: enabling a source starts nothing. FIN-058 built
 *                                    a trigger, and it is manual, worker-side and has no caller
 *                                    reachable from here, so from this screen nothing will run
 * @param connectorDeploymentVerified always false: the connector registry is a sync-worker bean
 *                                    and this runtime cannot see it
 * @param warnings                    non-blocking things worth knowing — unsigned source-of-truth
 *                                    declarations, readiness warnings. Never a reason the call
 *                                    failed; a failed call does not return this type
 * @param evaluatedAt                 when readiness was computed. Never stored: a source enabled
 *                                    today can be misconfigured tomorrow, and readiness is
 *                                    recomputed on every read for that reason
 */
public record SourceSystemActivationResponse(Long sourceSystemId,
                                             Long templeId,
                                             String templeName,
                                             String systemCode,
                                             boolean enabledForSync,
                                             boolean changed,
                                             ReadinessStatus readinessStatus,
                                             int blockingCount,
                                             int warningCount,
                                             boolean syncInfrastructureAvailable,
                                             boolean connectorDeploymentVerified,
                                             String activationNote,
                                             List<String> warnings,
                                             LocalDateTime evaluatedAt) {
}

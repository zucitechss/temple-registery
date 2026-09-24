package com.templeregistry.service.finance.sync;

/**
 * A sync run was refused before any batch existed and before any source was contacted (FIN-058).
 *
 * <h2>Refusal is not failure</h2>
 *
 * <p>A refused request leaves no {@code fin_sync_batch} row, because no run was attempted. That is
 * deliberate and it is the distinction this exception carries: {@code SyncStatus.FAILED} means the
 * platform tried to read a temple's system and could not, which is an operational event somebody
 * should look at. A source that is switched off, or whose configuration has an unresolved blocking
 * finding, produced no such event — recording one would put rows in the audit spine describing
 * attempts that never happened, and would make "how often does this source fail?" unanswerable.
 *
 * <p>{@link Reason} exists so that a caller and a log line can distinguish the cases without
 * parsing a sentence. Every one of them is a state an administrator can resolve; none of them is a
 * defect in the platform.
 */
public class SyncRefusedException extends RuntimeException {

    /** Why the run was refused. Logged as the failure category, and safe to branch on. */
    public enum Reason {

        /** {@code trm.finance.sync.enabled} is false: this worker process may generate no traffic. */
        WORKER_DISABLED,

        /** No such source system, or it has been soft-deleted. */
        NO_SUCH_SOURCE,

        /** {@code fin_source_system.sync_enabled} is false — nobody has authorised synchronisation. */
        NOT_ENABLED_FOR_SYNC,

        /** Readiness, recomputed now, has at least one blocking finding. */
        READINESS_BLOCKED,

        /** A batch for this source and capability is already PENDING or RUNNING. */
        ALREADY_IN_PROGRESS
    }

    private final transient Reason reason;

    public SyncRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

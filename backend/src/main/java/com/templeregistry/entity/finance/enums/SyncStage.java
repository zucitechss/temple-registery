package com.templeregistry.entity.finance.enums;

/** Pipeline stage at which a source row was rejected. Recorded on {@code fin_sync_error}. */
public enum SyncStage {
    EXTRACT,
    VALIDATE,
    MAP,
    NORMALIZE,
    LOAD,
    RECONCILE,

    /** Rebuilding period aggregates from facts (FIN-072). Never re-extracts, never rewrites a fact. */
    AGGREGATE
}

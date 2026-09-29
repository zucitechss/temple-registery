package com.templeregistry.entity.finance.enums;

/**
 * What initiated a sync batch.
 *
 * <p>{@link #TEMPLE_INPUT} and {@link #EXCEL_UPLOAD} are the two that always
 * have a person behind them, and they are the reason {@code
 * fin_sync_batch.actor_user_id} exists (V122). For every other value the actor
 * is null, which is the honest reading: nobody entered a scheduled run.
 *
 * <p>{@link #MANUAL} is not the same thing as {@link #TEMPLE_INPUT}. It means an
 * operator re-ran an extraction by hand; the data still came from a source
 * system. {@code TEMPLE_INPUT} means the data is the submission.
 */
public enum SyncTrigger {
    /** The nightly schedule. No actor. */
    SCHEDULER,
    /** An operator re-ran an extraction by hand. The data still came from a source. */
    MANUAL,
    /** The first load when a source system is activated. */
    ONBOARDING,
    /** A temple staff member submitted figures through the input API. */
    TEMPLE_INPUT,
    /** A temple staff member committed an uploaded workbook. */
    EXCEL_UPLOAD
}

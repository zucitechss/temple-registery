package com.templeregistry.entity.finance.enums;

/** Why a sync batch ran, which determines its extraction window. */
public enum SyncType {
    /** Full historical load performed once at onboarding. */
    HISTORICAL,
    /** Routine watermark-driven load of new and changed rows. */
    INCREMENTAL,
    /** Deliberate re-extraction of a past window to correct known bad data. */
    BACKFILL,
    /** Re-processing of retained staging rows without contacting the source. */
    REPLAY,
    /** Extract and validate only; nothing is loaded into canonical tables. */
    DRY_RUN
}

package com.templeregistry.entity.finance.enums;

/**
 * What a reconciliation row compared, and therefore how much it is worth (FIN-060).
 *
 * <p>The distinction is not cosmetic. Two of these compare numbers this platform computed from
 * its own data; two compare against a figure the source system produced. Only the second kind can
 * show that an extraction was wrong, and only the first kind can be relied on today — because no
 * production connector implements {@code sourceTotals()} yet.
 *
 * <p>Recording which is which is what stops a run of green internal checks from being read as
 * "the figures agree with the temple". They do not; nobody has asked the temple.
 */
public enum ReconciliationCheckType {

    /**
     * Every row a batch staged reached a terminal state, and the counts add up.
     *
     * <p><b>Authoritative.</b> Entirely local: staging statuses against the batch counters. It
     * cannot tell whether the right rows were extracted, only that none was lost afterwards.
     */
    STAGE_COMPLETENESS,

    /**
     * Every rejected row has an error explaining it.
     *
     * <p><b>Authoritative.</b> Catches a rejection that happened silently — a row removed from
     * the figures with no recorded reason, which is indistinguishable from data loss when
     * somebody asks six months later why a day is short.
     */
    REJECTION_ACCOUNTING,

    /**
     * A total the source system computed against the same total computed from canonical facts.
     *
     * <p><b>Advisory until a connector implements it.</b> This is the only check that can catch a
     * wrong extraction query, because it is the only one whose two sides are independent.
     * Unavailable means {@code NOT_AVAILABLE}, never a pass (ADR-007).
     */
    SOURCE_VS_CENTRAL,

    /**
     * The source now reports fewer records for a closed period than this platform holds.
     *
     * <p><b>Advisory, and never destructive.</b> It is evidence, not proof: a partial source
     * response, a network failure, a filter error and a genuine deletion all look identical from
     * here (FIN-D-044). Nothing is deleted, nothing is overwritten, and the prior canonical
     * record stands until a human decides otherwise.
     */
    SUSPECTED_SOURCE_DELETION
}

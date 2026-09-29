package com.templeregistry.entity.finance.enums;

/**
 * Which of the two fund tables a staged row becomes.
 *
 * <p>One staging table serves both because a fund and its drawdowns arrive
 * together, on one form and in one sheet, and splitting them at staging would
 * make a single submission span two batches.
 */
public enum FundStagingRecordKind {
    /** Becomes a {@code fin_dc_fund} row. */
    FUND,
    /** Becomes a {@code fin_fund_utilisation} row. */
    UTILISATION
}

package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.ReconciliationStatus;

import java.time.LocalDateTime;

/**
 * How verified the figures in a {@link ReportTableResponse} are (Phase 0 contract).
 *
 * <p>Deliberately smaller than {@link ReconciliationResponse}, which lists every
 * individual check for the reconciliation screen. This is the one-line verdict a
 * report carries alongside its rows, so that a reader never sees a number
 * without seeing how much it has been checked.
 *
 * @param status    the verdict the figures were published under. {@code FAILED} never appears
 *                  here: a failed period keeps its previous published figures and the response
 *                  describes those
 * @param inherited true when the verdict belongs to a wider scope than the period shown.
 *                  Monthly figures inherit their financial year verdict, because reconciliation
 *                  records no month-scoped result. Never claim a month was verified when it
 *                  was not
 * @param scope     what was actually verified, e.g. {@code FINANCIAL_YEAR 2025-26}
 * @param checkedAt when that verdict was reached. Null when status is NOT_AVAILABLE
 * @param note      why verification is unavailable, where it is. For a manual or Excel source
 *                  this is permanent and says so: a self-reported figure has no independent
 *                  source to compare against
 */
public record ReconciliationSummary(
        ReconciliationStatus status,
        boolean inherited,
        String scope,
        LocalDateTime checkedAt,
        String note) {

    /** The verdict for a self-reported figure. Permanent, and not a failure. */
    public static ReconciliationSummary selfReported(String scope) {
        return new ReconciliationSummary(
                ReconciliationStatus.NOT_AVAILABLE, false, scope, null,
                "Entered by temple management. There is no independent source to verify it against.");
    }
}

package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.DataAvailability;

import java.util.List;

/**
 * The one response shape every finance report returns (Phase 0 contract).
 *
 * <p>Chart, grid, PDF and spreadsheet all render from a single call. That is
 * the point: a figure cannot differ between the chart and the table, and an
 * export cannot omit a caveat the screen displayed, because there is only ever
 * one answer to render.
 *
 * <h2>Absence travels with the data</h2>
 *
 * <p>{@link #availability} and {@link #unavailableReason} carry ADR-007 at the
 * level of a whole report, as {@link MetricEnvelope} does for a single figure.
 * A report a temple source cannot answer returns {@code NOT_AVAILABLE} with the
 * reason declared in {@code fin_temple_capability}, and <b>no rows</b> — never
 * an empty list, which a client would draw as a chart of zeroes.
 *
 * <p>So {@code rows.isEmpty()} means only one thing: this temple and period
 * genuinely had nothing. Check {@link #availability} first.
 *
 * <h2>Verification travels with it too</h2>
 *
 * <p>{@link #reconciliation} is the verdict the figures were published under.
 * For a manual or Excel source it is permanently {@code NOT_AVAILABLE}: a
 * self-reported figure has no independent source to compare against. The client
 * must render that distinction rather than badging a self-reported figure like
 * a source-verified one.
 *
 * <h2>Why rows are maps and not a typed list</h2>
 *
 * <p>Eleven reports have eleven row shapes, and a typed DTO per report would
 * mean a parallel grid component per report. {@link #columns} is the schema
 * instead, so one grid, one PDF renderer and one spreadsheet writer serve all
 * of them. The trade is deliberate and is the reason {@code ReportColumn}
 * carries type and unit: without them the client would be guessing.
 *
 * @param rows            one map per row, keyed by {@link ReportColumn#key}. Empty when the
 *                        period genuinely had nothing; see the note above
 * @param columns         the schema, in display order
 * @param totalRows       total matching rows, which may exceed {@code rows.size()} when paged
 * @param page            zero-based page index, or 0 when the report is not paged
 * @param pageSize        rows per page, or {@code totalRows} when the report is not paged
 * @param availability    whether this report can be answered at all for this temple and period
 * @param unavailableReason user-facing, taken verbatim from the capability declaration. Non-null
 *                        whenever availability is not AVAILABLE
 * @param freshness       how current the underlying data is, and why it might not be
 * @param reconciliation  how verified the figures are, and at what scope
 * @param servedFrom      which store answered this: the published aggregate, or the facts. A range
 *                        that does not align to whole months falls through to facts, and saying so
 *                        makes a slow report diagnosable instead of mysterious
 */
public record ReportTableResponse(
        List<java.util.Map<String, Object>> rows,
        List<ReportColumn> columns,
        long totalRows,
        int page,
        int pageSize,
        DataAvailability availability,
        String unavailableReason,
        DataFreshnessBlock freshness,
        ReconciliationSummary reconciliation,
        ServedFrom servedFrom) {

    /** Which store answered the request. See {@code servedFrom}. */
    public enum ServedFrom {
        /** A published, gate-approved aggregate row. The normal path. */
        AGGREGATE,
        /** Canonical facts, because the requested range did not align to a stored period. */
        FACTS
    }

    /**
     * A report that cannot be answered. Carries no rows, so a client cannot
     * mistake absence for zero.
     */
    public static ReportTableResponse notAvailable(List<ReportColumn> columns,
                                                   String reason,
                                                   DataFreshnessBlock freshness) {
        return new ReportTableResponse(
                List.of(), columns, 0, 0, 0,
                DataAvailability.NOT_AVAILABLE, reason, freshness, null, null);
    }
}

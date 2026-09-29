package com.templeregistry.dto.response.finance;

/**
 * One column of a tabular report (FR13), described by the API rather than by
 * the frontend.
 *
 * <p>The grid, the PDF and the spreadsheet all render from this list, which is
 * what stops the three disagreeing about what a column is called, what unit it
 * is in, or whether it can be sorted. A column added to a report appears in all
 * three without a frontend change.
 *
 * @param key        field name in each row map; stable, and what {@code sort} refers to
 * @param label      user-facing heading. Already localised by the server
 * @param type       how to render and align: TEXT, NUMBER, MONEY, DATE, WEIGHT, PERCENT, STATUS
 * @param unit       e.g. {@code INR} or {@code g}. Null where the type implies it or none applies
 * @param sortable   whether {@code sort=key} is accepted by the endpoint. Server-side sorting
 *                   only: a grid that sorts one loaded page while claiming to sort the report
 *                   is a lie about the data the reader is looking at
 * @param nullMeans  what an absent value means in THIS column, shown in place of a blank cell
 *                   (ADR-007). For example "not recorded by this source" against a value column
 *                   whose purity was never entered. Null where the column cannot be absent
 */
public record ReportColumn(
        String key,
        String label,
        Type type,
        String unit,
        boolean sortable,
        String nullMeans) {

    public enum Type {
        TEXT,
        NUMBER,
        MONEY,
        DATE,
        WEIGHT,
        PERCENT,
        /** A coded state the client renders as a chip rather than as text. */
        STATUS
    }

    public static ReportColumn text(String key, String label) {
        return new ReportColumn(key, label, Type.TEXT, null, true, null);
    }

    public static ReportColumn money(String key, String label, String nullMeans) {
        return new ReportColumn(key, label, Type.MONEY, "INR", true, nullMeans);
    }

    public static ReportColumn date(String key, String label) {
        return new ReportColumn(key, label, Type.DATE, null, true, null);
    }
}

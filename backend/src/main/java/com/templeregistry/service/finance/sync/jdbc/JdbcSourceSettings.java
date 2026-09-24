package com.templeregistry.service.finance.sync.jdbc;

import java.util.List;
import java.util.Optional;

/**
 * Where a JDBC source lives and which table the generic connector reads (FIN-040).
 *
 * <h2>Why this is worker-side and not a registry table</h2>
 *
 * <p>It carries a JDBC URL. {@code fin_source_system} deliberately holds no host, port, URL,
 * database name, username or password (FIN-D-002), and the registry runtime must have no path to
 * a temple's connection details at all. Putting this in the registry database would give an HTTP
 * request a way to read where a temple's system lives, which is the thing ADR-001 exists to
 * prevent. It therefore lives in worker-process configuration, beside the credentials it is
 * useless without.
 *
 * <h2>What it does not contain</h2>
 *
 * <p><b>No username, no password.</b> Those come from {@code SourceCredentialProvider}, resolved
 * from the {@code credential_ref} alias at extraction time and held for the duration of a read.
 *
 * <p><b>No SQL.</b> There is no query, no WHERE clause and no predicate here. The connector
 * composes its own statement from validated identifiers and bound parameters; a configured SQL
 * fragment would be a string from configuration executed against a temple's database, which is
 * the injection route this whole design avoids. That is also why
 * {@code fin_source_of_truth_decl.filter_predicate} is not executed by this connector — see
 * {@link JdbcTableConnector}.
 *
 * <h2>Two axes, two optional columns</h2>
 *
 * <p>{@code changedAtColumn} carries the change axis and {@code businessDateColumn} the
 * business-date axis, matching {@code SyncContext}'s two ranges. Both are optional because a
 * source may not have them, and the connector must narrow only on what the source actually
 * records: filtering on a column that does not mean what the name suggests is how history gets
 * silently excluded.
 *
 * @param jdbcUrl             connection URL. Vendor-specific connection tuning, including any
 *                            connect timeout, belongs in this URL — JDBC has no portable API for
 *                            it, and {@code DriverManager.setLoginTimeout} is process-global
 * @param table               the table or view to read
 * @param columns             the columns to read, in order. Each becomes a {@code RawRow} key
 *                            under its own name, because normalization looks up the staged
 *                            payload by the {@code source_field} a source-of-truth declaration
 *                            names (V115)
 * @param recordRefColumn     the column identifying a record in the source, used as
 *                            {@code RawRow.sourceRecordRef} so a rejected row can be found again
 * @param changedAtColumn     optional; the column extraction narrows on for an incremental run
 * @param businessDateColumn  optional; the column reconciliation and historical loads narrow on
 * @param amountColumn        optional; enables independent source totals. Absent means
 *                            {@code SourceTotals.notAvailable()}, which is a different outcome
 *                            from zero and is handled as such downstream
 * @param fetchSize           JDBC fetch size hint, to keep a large read off the heap
 * @param queryTimeoutSeconds per-statement timeout; 0 disables, which this class refuses
 */
public record JdbcSourceSettings(String jdbcUrl,
                                 SqlIdentifier table,
                                 List<SqlIdentifier> columns,
                                 SqlIdentifier recordRefColumn,
                                 Optional<SqlIdentifier> changedAtColumn,
                                 Optional<SqlIdentifier> businessDateColumn,
                                 Optional<SqlIdentifier> amountColumn,
                                 int fetchSize,
                                 int queryTimeoutSeconds) {

    public JdbcSourceSettings {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank.");
        }
        if (table == null) {
            throw new IllegalArgumentException("table must not be null.");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one column must be configured. A connector reading no columns would "
                            + "stage empty rows, which is indistinguishable downstream from a source "
                            + "that holds nothing.");
        }
        if (recordRefColumn == null) {
            throw new IllegalArgumentException(
                    "recordRefColumn must not be null: a staged row that cannot be traced back to "
                            + "the source record is not diagnosable.");
        }
        if (changedAtColumn == null || businessDateColumn == null || amountColumn == null) {
            throw new IllegalArgumentException("Optional columns must be Optional, not null.");
        }
        if (fetchSize <= 0) {
            throw new IllegalArgumentException(
                    "fetchSize must be positive: a first historical load can run to millions of "
                            + "rows and nothing in the pipeline may require them all in memory.");
        }
        if (queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "queryTimeoutSeconds must be positive. A read with no timeout can hold a "
                            + "worker thread against an unresponsive temple system indefinitely.");
        }
        columns = List.copyOf(columns);
    }

    /**
     * Every column the connector will select: the configured ones plus the record reference.
     *
     * <p>The reference is included whether or not it was listed, because the row cannot be staged
     * without it, and duplicated selection of one column is harmless.
     */
    public List<SqlIdentifier> selectedColumns() {
        if (columns.stream().anyMatch(c -> c.value().equalsIgnoreCase(recordRefColumn.value()))) {
            return columns;
        }
        return java.util.stream.Stream.concat(columns.stream(), java.util.stream.Stream.of(recordRefColumn))
                .toList();
    }

    /**
     * The URL with any embedded user information removed, safe to log.
     *
     * <p>A URL is the most common way a password reaches a log file, because some drivers accept
     * {@code ?user=&password=} in the query string and the whole string then looks like harmless
     * configuration.
     */
    public String describeTargetSafely() {
        int query = jdbcUrl.indexOf('?');
        String withoutQuery = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        int credentials = withoutQuery.indexOf('@');
        return credentials < 0 ? withoutQuery : "jdbc:<redacted>@" + withoutQuery.substring(credentials + 1);
    }
}

package com.templeregistry.service.finance.sync.jdbc;

import com.templeregistry.connector.finance.ConnectorMetadata;
import com.templeregistry.connector.finance.DateRange;
import com.templeregistry.connector.finance.RawRow;
import com.templeregistry.connector.finance.ReconMetric;
import com.templeregistry.connector.finance.SchemaFingerprint;
import com.templeregistry.connector.finance.SourceProbeResult;
import com.templeregistry.connector.finance.SourceSystemDescriptor;
import com.templeregistry.connector.finance.SourceTotals;
import com.templeregistry.connector.finance.SyncContext;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.service.finance.sync.SourceCredentialProvider;
import com.templeregistry.service.finance.sync.SourceCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.StringJoiner;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The generic, configuration-driven JDBC connector promised by ADR-004 (FIN-040).
 *
 * <h2>One class, every simple source</h2>
 *
 * <p>ADR-004 settled that extraction is code and value mapping is configuration, and carved out
 * one exception: <i>"A generic {@code JdbcTableConnector} still covers simple sources with no new
 * code, so 'a connector per temple' is the exception rather than the rule."</i> This is that
 * class. Two temples with entirely different schemas are two sets of worker properties and this
 * same bean; a new Java class is needed only when a source is too irregular to read as a table —
 * as the first onboarded source is, with seven physical tables to union and an archive that
 * duplicates six of them.
 *
 * <p>There is therefore <b>no temple name, no source table name and no source column name
 * anywhere in this file</b>. Everything specific arrives as {@link JdbcSourceSettings}.
 *
 * <h2>It reads; it does not interpret</h2>
 *
 * <p>Every column is emitted into {@link RawRow} under its own name, as a string, exactly as the
 * driver rendered it. Nothing here parses a date, converts a currency or decides what a value
 * means. That boundary is not stylistic: normalization looks up the staged payload by the
 * {@code source_field} a source-of-truth declaration names (V115), so a connector that renamed or
 * cleaned a field would break the declaration that points at it and would hide the defects
 * staging exists to record.
 *
 * <h2>Read-only by construction, not by filter</h2>
 *
 * <p>This class exposes no method that accepts SQL. Statements are composed here from validated
 * identifiers and bound parameters, so {@code INSERT}, {@code UPDATE}, {@code DELETE}, DDL and
 * stored-procedure calls are not operations it can be asked to perform — there is nowhere to put
 * them. {@code setReadOnly(true)} is set as well, but it is a driver hint and is the weaker of
 * the two defences, not the primary one.
 *
 * <p>{@code fin_source_of_truth_decl.filter_predicate} is deliberately <b>not</b> executed. It is
 * free text written by an administrator, and running it would reintroduce exactly the arbitrary
 * SQL the rest of this design excludes. A source needing predicate filtering needs either a
 * database view or its own connector; the limitation is recorded rather than worked around.
 */
@RequiredArgsConstructor
@Slf4j
public class JdbcTableConnector implements TempleFinanceConnector {

    /**
     * The bean name, and the value {@code fin_source_system.connector_bean} must hold.
     *
     * <p>{@code ConnectorRegistry} refuses a connector whose declared id differs from the name it
     * is registered under, so this constant and the {@code @Bean} method name in
     * {@code SyncWorkerConfig} must agree.
     */
    public static final String CONNECTOR_ID = "jdbcTableConnector";

    /**
     * The only capability a table read can honestly claim today.
     *
     * <p>{@code REVENUE} is the one capability the pipeline extracts, and declaring more would be
     * a commitment this connector cannot keep: returning empty results for a capability the source
     * does not record is indistinguishable from a real zero, which is what the availability model
     * exists to prevent (ADR-007).
     */
    private static final FinanceCapability SUPPORTED = FinanceCapability.REVENUE;

    private final JdbcSourceSettingsProvider settingsProvider;
    private final SourceCredentialProvider credentialProvider;
    private final JdbcConnectionFactory connectionFactory;

    @Override
    public ConnectorMetadata metadata() {
        return new ConnectorMetadata(CONNECTOR_ID, ConnectorType.PULL_JDBC,
                "Generic configuration-driven JDBC table reader (ADR-004). Reads one configured "
                        + "table per source system; contains no source-specific SQL.");
    }

    /**
     * {@code REVENUE} when this source has usable JDBC settings, nothing otherwise.
     *
     * <p>Returning nothing for an unconfigured source is the honest answer and the one the
     * framework needs: it lets the platform report NOT_AVAILABLE with a reason instead of
     * extracting zero rows and presenting that as a temple with no income.
     */
    @Override
    public Set<FinanceCapability> describeCapabilities(SourceSystemDescriptor source) {
        return settings(source).isPresent() ? Set.of(SUPPORTED) : Set.of();
    }

    /**
     * Opens a connection, validates it, and closes it again.
     *
     * <p>Never throws. An unreachable temple system is an expected operational state — a nightly
     * window, a firewall change, a server being patched — and the contract requires it to be
     * reported rather than raised.
     */
    @Override
    public SourceProbeResult probe(SourceSystemDescriptor source) {
        Optional<JdbcSourceSettings> configured = settings(source);
        if (configured.isEmpty()) {
            return SourceProbeResult.unusable(
                    "No JDBC read settings are configured for this source system in the worker.");
        }
        JdbcSourceSettings settings = configured.get();

        long startedAt = System.nanoTime();
        try (Connection connection = connect(source, settings)) {
            boolean valid = connection.isValid(settings.queryTimeoutSeconds());
            log.info("[FinanceSync] Probe of source [{}] via {} completed in {} ms: {}",
                    source.systemCode(), CONNECTOR_ID, elapsedMs(startedAt),
                    valid ? "usable" : "not usable");
            return valid
                    ? SourceProbeResult.usable("Connected and the session is valid. No data was read.")
                    : SourceProbeResult.unusable("Connected, but the driver reports the session is not valid.");
        } catch (SQLException | RuntimeException failure) {
            // The detail is shown to an operator during onboarding, so it names the failure class
            // and not the URL, the principal or anything the driver may have embedded in either.
            log.warn("[FinanceSync] Probe of source [{}] failed after {} ms: {}",
                    source.systemCode(), elapsedMs(startedAt), failure.getClass().getSimpleName());
            return SourceProbeResult.unusable(
                    "Could not establish a usable session: " + failure.getClass().getSimpleName()
                            + ". Check the worker's connection settings and credential for this source.");
        }
    }

    /**
     * Hashes the configured table's column names and types, from JDBC metadata.
     *
     * <p>Only the columns this connector actually reads are hashed. Fingerprinting the whole
     * schema would make an unrelated table's change look like drift in this extraction, and an
     * alert that fires for changes that cannot affect the figures is an alert that gets muted.
     */
    @Override
    public Optional<SchemaFingerprint> fingerprintSchema(SourceSystemDescriptor source) {
        Optional<JdbcSourceSettings> configured = settings(source);
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        JdbcSourceSettings settings = configured.get();

        try (Connection connection = connect(source, settings)) {
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, String> types = new LinkedHashMap<>();
            try (ResultSet columns = metaData.getColumns(null, null, settings.table().value(), null)) {
                while (columns.next()) {
                    // Size and scale are part of the fingerprint, not only the type name. A
                    // DECIMAL narrowed from (18,2) to (10,0) keeps its type name and silently
                    // truncates every amount read through it, which is exactly the drift this
                    // exists to catch.
                    String shape = columns.getString("TYPE_NAME")
                            + "(" + columns.getInt("COLUMN_SIZE")
                            + "," + columns.getInt("DECIMAL_DIGITS")
                            + "," + columns.getInt("NULLABLE") + ")";
                    types.put(columns.getString("COLUMN_NAME").toUpperCase(java.util.Locale.ROOT), shape);
                }
            }
            if (types.isEmpty()) {
                // "Cannot be checked" and "changed" are different answers, and the framework
                // treats them differently. An absent table is reported by the read, not here.
                return Optional.empty();
            }

            StringJoiner material = new StringJoiner(",");
            for (SqlIdentifier column : settings.selectedColumns()) {
                String key = column.value().toUpperCase(java.util.Locale.ROOT);
                material.add(key + ":" + types.getOrDefault(key, "<absent>"));
            }
            return Optional.of(new SchemaFingerprint(sha256(material.toString())));
        } catch (SQLException | RuntimeException failure) {
            log.warn("[FinanceSync] Could not fingerprint source [{}]: {}",
                    source.systemCode(), failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Streams the configured table, narrowed by whichever axes the source actually records.
     *
     * <p>The stream holds an open connection, statement and result set, and closing it closes all
     * three. The contract requires the caller to close it, and {@code RevenueExtractionStage} does
     * so with try-with-resources.
     *
     * <p>Narrowing is by bound parameter on a configured column, never by configured SQL. A source
     * that declares no change column is read in full on every run: correct, and honest about
     * costing more, where filtering on a column that does not mean what its name suggests would
     * silently drop history.
     */
    @Override
    public Stream<RawRow> extract(FinanceCapability capability, SyncContext context) {
        SourceSystemDescriptor source = context.source();
        requireCapability(capability, source);
        JdbcSourceSettings settings = settings(source).orElseThrow(() -> new SourceReadException(
                source.systemCode(), "No JDBC read settings are configured for this source.", null));

        List<Object> parameters = new ArrayList<>();
        String sql;
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        long startedAt = System.nanoTime();

        try {
            connection = connect(source, settings);
            sql = buildSelect(connection.getMetaData(), settings, context, parameters);

            statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(settings.fetchSize());
            statement.setQueryTimeout(settings.queryTimeoutSeconds());
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }

            resultSet = statement.executeQuery();
            log.info("[FinanceSync] Extract opened for source [{}] batch [{}] via {}: table {}, "
                            + "{} columns, {} bound predicate(s), target {}",
                    source.systemCode(), context.batchRef(), CONNECTOR_ID, settings.table(),
                    settings.selectedColumns().size(), parameters.size(),
                    settings.describeTargetSafely());

            return rowStream(source, settings, connection, statement, resultSet, startedAt);
        } catch (SQLException | RuntimeException failure) {
            // Nothing was handed to the caller, so nothing else will close these.
            closeQuietly(resultSet, statement, connection);
            throw readFailure(source, "read table " + settings.table(), failure);
        }
    }

    /**
     * Totals computed by the source engine, over business dates.
     *
     * <p>A separate statement with a separate {@code WHERE} clause on a separate axis from
     * {@link #extract}, which is what the contract requires: a total derived from the extraction
     * query would agree with the extraction even when the extraction was wrong.
     *
     * <p>Returns {@link SourceTotals#notAvailable()} when no amount column is configured, or when
     * the source cannot be asked. Never zero — "we could not ask" and "the answer is nothing" lead
     * to opposite conclusions on a finance dashboard.
     */
    @Override
    public SourceTotals sourceTotals(FinanceCapability capability,
                                     SourceSystemDescriptor source,
                                     DateRange period) {
        requireCapability(capability, source);
        JdbcSourceSettings settings = settings(source).orElse(null);
        if (settings == null || settings.amountColumn().isEmpty()) {
            return SourceTotals.notAvailable();
        }

        List<Object> parameters = new ArrayList<>();
        try (Connection connection = connect(source, settings)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String where = businessDatePredicate(metaData, settings, period, parameters);
            String sql = "SELECT COUNT(*), SUM(" + settings.amountColumn().get().quoted(metaData) + ") FROM "
                    + settings.table().quoted(metaData) + where;

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(settings.queryTimeoutSeconds());
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return SourceTotals.notAvailable();
                    }
                    Map<ReconMetric, BigDecimal> totals = new EnumMap<>(ReconMetric.class);
                    totals.put(ReconMetric.RECORD_COUNT, BigDecimal.valueOf(rs.getLong(1)));
                    BigDecimal gross = rs.getBigDecimal(2);
                    // SUM over no rows is SQL NULL. Reporting it as zero would assert the source
                    // holds nothing for the period, which is a different claim from "no rows
                    // matched" only when the count is also zero — and the count says that already.
                    if (gross != null) {
                        totals.put(ReconMetric.GROSS_AMOUNT, gross);
                    }
                    return new SourceTotals(totals);
                }
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("[FinanceSync] Source totals unavailable for [{}]: {}",
                    source.systemCode(), failure.getClass().getSimpleName());
            return SourceTotals.notAvailable();
        }
    }

    // ----------------------------------------------------------------- query

    /**
     * Composes the read statement.
     *
     * <p>Every identifier is validated on the way into {@link JdbcSourceSettings} and quoted here
     * with the character the driver reports. Every value is a bound parameter. There is no path
     * by which configuration contributes a fragment of SQL.
     */
    private String buildSelect(DatabaseMetaData metaData, JdbcSourceSettings settings,
                               SyncContext context, List<Object> parameters) throws SQLException {
        StringJoiner columns = new StringJoiner(", ");
        for (SqlIdentifier column : settings.selectedColumns()) {
            columns.add(column.quoted(metaData));
        }

        List<String> predicates = new ArrayList<>();

        // Change axis: only for an incremental run, and only when the source records it.
        if (settings.changedAtColumn().isPresent()) {
            SqlIdentifier changedAt = settings.changedAtColumn().get();
            if (context.changedSince().isPresent()) {
                predicates.add(changedAt.quoted(metaData) + " > ?");
                parameters.add(Timestamp.from(context.changedSince().get()));
            }
            predicates.add(changedAt.quoted(metaData) + " <= ?");
            parameters.add(Timestamp.from(context.changedUpTo()));
        }

        // Business-date axis: independent of the change axis, and never a substitute for it.
        String businessDate = businessDatePredicate(metaData, settings,
                context.businessDateRange(), parameters);
        if (!businessDate.isEmpty()) {
            predicates.add(businessDate.substring(" WHERE ".length()));
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(columns)
                .append(" FROM ").append(settings.table().quoted(metaData));
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        // Deterministic order, so a chunked read and a re-read of the same window agree.
        sql.append(" ORDER BY ").append(settings.recordRefColumn().quoted(metaData));
        return sql.toString();
    }

    private String businessDatePredicate(DatabaseMetaData metaData, JdbcSourceSettings settings,
                                         DateRange period, List<Object> parameters)
            throws SQLException {
        if (settings.businessDateColumn().isEmpty() || period == null || period.isUnbounded()) {
            return "";
        }
        SqlIdentifier column = settings.businessDateColumn().get();
        List<String> bounds = new ArrayList<>();
        Optional<LocalDate> from = period.from();
        if (from.isPresent()) {
            bounds.add(column.quoted(metaData) + " >= ?");
            parameters.add(java.sql.Date.valueOf(from.get()));
        }
        Optional<LocalDate> to = period.to();
        if (to.isPresent()) {
            bounds.add(column.quoted(metaData) + " <= ?");
            parameters.add(java.sql.Date.valueOf(to.get()));
        }
        return bounds.isEmpty() ? "" : " WHERE " + String.join(" AND ", bounds);
    }

    // ---------------------------------------------------------------- stream

    /**
     * Wraps the cursor as a lazily-consumed stream whose close releases all three resources.
     *
     * <p>A list would be simpler and would not survive a first historical load; the contract
     * returns a stream precisely so that tens of millions of rows never have to be in memory at
     * once.
     */
    private Stream<RawRow> rowStream(SourceSystemDescriptor source, JdbcSourceSettings settings,
                                     Connection connection, PreparedStatement statement,
                                     ResultSet resultSet, long startedAt) {
        long[] counted = {0};

        Spliterator<RawRow> spliterator = new Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super RawRow> action) {
                try {
                    if (!resultSet.next()) {
                        return false;
                    }
                    action.accept(toRawRow(settings, resultSet));
                    counted[0]++;
                    return true;
                } catch (SQLException failure) {
                    throw readFailure(source, "read a row from " + settings.table(), failure);
                }
            }
        };

        return StreamSupport.stream(spliterator, false).onClose(() -> {
            closeQuietly(resultSet, statement, connection);
            // The count is the result cardinality, never the data. Logging rows would put a
            // temple's financial records into the worker's log file.
            log.info("[FinanceSync] Extract closed for source [{}]: {} row(s) in {} ms",
                    source.systemCode(), counted[0], elapsedMs(startedAt));
        });
    }

    /**
     * One source record, every value as the driver rendered it.
     *
     * <p>Strings throughout, matching {@link RawRow}: a typed extraction contract turns an
     * impossible date or text in a numeric column into a crash that loses the whole batch, where
     * staging is built to land the row and reject it with a reason. A decimal rendered as text and
     * parsed back is exact, so money is unaffected.
     */
    private RawRow toRawRow(JdbcSourceSettings settings, ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            // The label as the source names it. Normalization looks the staged payload up by the
            // source_field a declaration names, so renaming here would break that lookup.
            String label = metaData.getColumnLabel(i);
            Object value = resultSet.getObject(i);
            values.put(label, value == null ? null : String.valueOf(value));
        }

        String reference = resultSet.getString(settings.recordRefColumn().value());
        if (reference == null || reference.isBlank()) {
            throw new SourceReadException(null,
                    "A row in " + settings.table() + " has no value in the configured record "
                            + "reference column. A staged row that cannot be traced back to the "
                            + "source record is not diagnosable, and a blank reference would also "
                            + "collide with every other blank one in the batch.", null);
        }
        return new RawRow(reference, values);
    }

    // ----------------------------------------------------------------- plumbing

    private Optional<JdbcSourceSettings> settings(SourceSystemDescriptor source) {
        if (source == null || source.connectorType() != ConnectorType.PULL_JDBC) {
            return Optional.empty();
        }
        return settingsProvider.settingsFor(source.systemCode());
    }

    /**
     * Resolves the credential and opens the connection.
     *
     * <p>This is the only place a secret exists, it exists for the duration of one call, and it is
     * never returned, logged or stored. Resolution happens in the worker runtime because
     * {@code SourceCredentialProvider} has no bean in the registry runtime (FIN-D-002).
     */
    private Connection connect(SourceSystemDescriptor source, JdbcSourceSettings settings)
            throws SQLException {
        SourceCredentials credentials = credentialProvider.resolve(source.credentialRef());
        Connection connection = connectionFactory.open(settings, credentials);
        if (!connection.isReadOnly()) {
            // Some drivers silently ignore setReadOnly. Saying so is worth more than assuming it
            // worked, though it is not what prevents a write — see the class comment.
            log.warn("[FinanceSync] Driver for source [{}] did not honour read-only mode. "
                            + "The connector issues no write statements regardless.",
                    source.systemCode());
        }
        return connection;
    }

    private SourceReadException readFailure(SourceSystemDescriptor source, String attempted,
                                            Throwable cause) {
        if (cause instanceof SourceReadException alreadyTranslated) {
            return alreadyTranslated;
        }
        return new SourceReadException(source.systemCode(),
                "Could not " + attempted + " for source system [" + source.systemCode() + "]: "
                        + cause.getClass().getSimpleName()
                        + ". The connection target and credential are not repeated here; check the "
                        + "worker configuration for this source.", cause);
    }

    private void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception ignored) {
                // A close failure must not mask the failure being handled, and must not stop the
                // remaining resources being released.
                log.debug("[FinanceSync] Ignoring failure closing {}",
                        resource.getClass().getSimpleName());
            }
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            // Truncated to the storage width of fin_source_system.schema_fingerprint.
            return HexFormat.of().formatHex(hash).substring(0, SchemaFingerprint.MAX_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM.", impossible);
        }
    }
}

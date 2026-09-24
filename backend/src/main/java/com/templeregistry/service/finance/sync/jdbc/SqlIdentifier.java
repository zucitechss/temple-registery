package com.templeregistry.service.finance.sync.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A table or column name that is safe to place into generated SQL (FIN-040).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Every value the generic connector reads from a source is bound as a JDBC parameter. Two
 * things cannot be: a table name and a column name. JDBC has no placeholder for an identifier, so
 * they are the one part of a generated statement that is concatenated — and therefore the one
 * place a configuration row could carry SQL into the source database.
 *
 * <p>The answer here is an allow-list rather than an escape routine. Escaping asks "can I make
 * this string safe?", which is a question with an endless supply of counter-examples across
 * vendors, quoting modes and character sets. An allow-list asks "is this a plain identifier?",
 * which has one answer. Anything with a space, a quote, a semicolon, a comment marker or a
 * parenthesis is refused outright rather than transformed into something that looks harmless.
 *
 * <p>Validation and quoting are both applied, deliberately. Validation stops injection; quoting
 * stops a legitimate identifier that happens to be a reserved word from breaking the statement.
 * Neither substitutes for the other.
 */
public final class SqlIdentifier {

    /**
     * A plain unqualified identifier: a letter or underscore, then letters, digits or underscores.
     *
     * <p>Deliberately excludes the dot. A qualified name like {@code schema.table} is refused
     * because quoting it correctly means knowing where the boundary is, and a configuration value
     * containing a dot is more likely a mistake than a schema reference. A source needing one
     * should say so through the connection URL's default schema.
     */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /**
     * Rejected even when they match the pattern.
     *
     * <p>These are not injection vectors; they are names that suggest the configuration is
     * pointing somewhere it should not. Failing loudly beats reading a system catalogue.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "information_schema", "mysql", "performance_schema", "sys", "pg_catalog");

    private final String value;

    private SqlIdentifier(String value) {
        this.value = value;
    }

    /**
     * Validates an identifier, or refuses it.
     *
     * @param role  what the identifier is for, so the failure names the configuration key
     * @throws IllegalArgumentException if the value is not a plain identifier
     */
    public static SqlIdentifier of(String role, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(role + " must not be blank.");
        }
        String trimmed = value.trim();
        if (!PLAIN.matcher(trimmed).matches()) {
            // The rejected value is included because an operator has to find it in configuration,
            // and it is configuration they wrote rather than data from the source.
            throw new IllegalArgumentException(
                    role + " [" + trimmed + "] is not a plain SQL identifier. Only letters, digits "
                            + "and underscores are accepted, starting with a letter or underscore, "
                            + "because an identifier cannot be bound as a JDBC parameter and is the "
                            + "one part of a generated statement that is concatenated.");
        }
        if (FORBIDDEN.contains(trimmed.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    role + " [" + trimmed + "] names a database catalogue rather than a temple's "
                            + "own data. The generic connector reads configured business tables only.");
        }
        return new SqlIdentifier(trimmed);
    }

    /** The validated identifier, unquoted. */
    public String value() {
        return value;
    }

    /**
     * The identifier quoted using the quote character the driver itself reports.
     *
     * <p>Asking the driver rather than hardcoding backticks or double quotes is what keeps this
     * class vendor-neutral: the same code quotes correctly for every JDBC driver that answers
     * {@link DatabaseMetaData#getIdentifierQuoteString()} honestly. A driver reporting a single
     * space means "quoting unsupported", and the identifier is already proven safe, so it is used
     * bare.
     */
    public String quoted(DatabaseMetaData metaData) throws SQLException {
        String quote = metaData.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return value;
        }
        return quote + value + quote;
    }

    @Override
    public String toString() {
        return value;
    }
}

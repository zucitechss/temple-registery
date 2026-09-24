package com.templeregistry.service.finance.sync.jdbc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads JDBC source settings from the sync-worker process environment (FIN-040).
 *
 * <p>Property convention, for {@code system_code = SRT_MANDYA}:
 *
 * <pre>
 *   trm.finance.jdbc.srt_mandya.url                  = jdbc:mysql://host:3306/accounts
 *   trm.finance.jdbc.srt_mandya.table                = SOURCE_RECEIPTS
 *   trm.finance.jdbc.srt_mandya.columns              = id,transaction_date,amount,payment_mode
 *   trm.finance.jdbc.srt_mandya.record-ref-column    = id
 *   trm.finance.jdbc.srt_mandya.changed-at-column    = updated_at      (optional)
 *   trm.finance.jdbc.srt_mandya.business-date-column = transaction_date (optional)
 *   trm.finance.jdbc.srt_mandya.amount-column        = amount           (optional)
 *   trm.finance.jdbc.srt_mandya.fetch-size           = 1000             (optional)
 *   trm.finance.jdbc.srt_mandya.query-timeout-seconds = 120             (optional)
 * </pre>
 *
 * <p>Mirroring {@code EnvironmentSourceCredentialProvider} exactly, including the reason: these
 * arrive as environment variables injected into the worker process and are deliberately
 * <b>not</b> declared in committed YAML. A property with a placeholder default in
 * {@code application.yml} is how credentials end up in version control.
 *
 * <p><b>This is where a new temple is onboarded without new Java.</b> Two temples with different
 * schemas are two sets of these properties and the same connector class, which is what ADR-004
 * promises for a structurally simple source. It is also the honest limitation of this slice:
 * onboarding still touches worker configuration, not only the registry screen. Promoting these
 * settings to registry configuration would need somewhere to put a JDBC URL that the registry
 * runtime must not be able to read, which is an architectural decision rather than a refactor.
 */
@RequiredArgsConstructor
@Slf4j
public class PropertiesJdbcSourceSettingsProvider implements JdbcSourceSettingsProvider {

    static final String PREFIX = "trm.finance.jdbc.";
    private static final int DEFAULT_FETCH_SIZE = 1000;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 120;

    private final Environment environment;

    @Override
    public Optional<JdbcSourceSettings> settingsFor(String systemCode) {
        String key = normalise(systemCode);
        if (key == null) {
            return Optional.empty();
        }

        String url = property(key, "url");
        String table = property(key, "table");
        String columns = property(key, "columns");
        String recordRef = property(key, "record-ref-column");

        if (url == null || table == null || columns == null || recordRef == null) {
            // Not an error. Most source systems are not JDBC, and a partially configured one is
            // reported by the connector as "no capability" rather than as a failure at startup.
            return Optional.empty();
        }

        List<SqlIdentifier> columnIdentifiers = Arrays.stream(columns.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> SqlIdentifier.of("column", value))
                .toList();

        JdbcSourceSettings settings = new JdbcSourceSettings(
                url.trim(),
                SqlIdentifier.of("table", table),
                columnIdentifiers,
                SqlIdentifier.of("record-ref-column", recordRef),
                optionalIdentifier(key, "changed-at-column"),
                optionalIdentifier(key, "business-date-column"),
                optionalIdentifier(key, "amount-column"),
                positiveInt(key, "fetch-size", DEFAULT_FETCH_SIZE),
                positiveInt(key, "query-timeout-seconds", DEFAULT_QUERY_TIMEOUT_SECONDS));

        // The target is logged without its query string, which is where a driver may accept a
        // password. The system code is not sensitive.
        log.info("[FinanceSync] JDBC settings loaded for source [{}]: table {}, {} columns, target {}",
                systemCode, settings.table(), settings.selectedColumns().size(),
                settings.describeTargetSafely());
        return Optional.of(settings);
    }

    private Optional<SqlIdentifier> optionalIdentifier(String key, String suffix) {
        String value = property(key, suffix);
        return value == null ? Optional.empty() : Optional.of(SqlIdentifier.of(suffix, value));
    }

    private int positiveInt(String key, String suffix, int fallback) {
        String value = property(key, suffix);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    PREFIX + key + "." + suffix + " is not a number: [" + value + "].", malformed);
        }
    }

    private String property(String key, String suffix) {
        String value = environment.getProperty(PREFIX + key + "." + suffix);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Lower-cases the system code and refuses one that could escape the property namespace.
     *
     * <p>The same guard {@code EnvironmentSourceCredentialProvider} applies to a credential
     * alias, for the same reason: {@code system_code} comes from a database row, and a value such
     * as {@code ..spring.datasource} would otherwise let a configuration row read an unrelated
     * property — including the registry database password.
     */
    private String normalise(String systemCode) {
        if (systemCode == null || systemCode.isBlank()) {
            return null;
        }
        String key = systemCode.trim().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_-]{1,100}")) {
            log.warn("[FinanceSync] Source system code [{}] cannot key a property lookup; "
                    + "no JDBC settings will be resolved for it.", systemCode);
            return null;
        }
        return key;
    }
}

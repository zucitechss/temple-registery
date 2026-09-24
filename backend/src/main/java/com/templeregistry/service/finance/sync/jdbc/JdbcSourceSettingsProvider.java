package com.templeregistry.service.finance.sync.jdbc;

import java.util.Optional;

/**
 * Supplies the JDBC read settings for a source system (FIN-040).
 *
 * <p>The same seam as {@code SourceCredentialProvider}, for the same reason: the connector must
 * not care where its target came from, so that moving these settings from worker properties to
 * somewhere better is a single bean replacement rather than a change to extraction code.
 *
 * <p><b>Implementations exist only in the sync-worker runtime.</b> No bean of this type is
 * registered in the registry runtime, so no HTTP request can discover where a temple's database
 * lives.
 *
 * <p>Returns {@link Optional#empty()} rather than throwing when a source is not configured for
 * JDBC reading. Absence is an ordinary state — most registered sources will never be JDBC — and
 * the connector reports it as "no capability" rather than as a failure.
 */
public interface JdbcSourceSettingsProvider {

    /**
     * @param systemCode {@code fin_source_system.system_code}, the stable non-secret key a source
     *                   is configured under
     */
    Optional<JdbcSourceSettings> settingsFor(String systemCode);
}

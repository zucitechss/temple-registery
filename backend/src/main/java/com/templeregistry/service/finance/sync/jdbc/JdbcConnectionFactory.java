package com.templeregistry.service.finance.sync.jdbc;

import com.templeregistry.service.finance.sync.SourceCredentials;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a connection to a temple source database (FIN-040).
 *
 * <p>A seam rather than a direct {@code DriverManager} call, so that a test can exercise the
 * connector's full read path against a synthetic in-memory schema without a temple database
 * existing anywhere — which is the only way this slice could be tested at all while Q4 is
 * unresolved.
 *
 * <p>Implementations must set the connection read-only before returning it. The connector asserts
 * that as well; both are cheap and the failure they prevent is a write to a government temple's
 * live finance database.
 */
public interface JdbcConnectionFactory {

    /**
     * @param settings    where to connect, never carrying a credential
     * @param credentials resolved inside the worker, never logged and never persisted
     */
    Connection open(JdbcSourceSettings settings, SourceCredentials credentials) throws SQLException;
}

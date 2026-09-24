package com.templeregistry.service.finance.sync.jdbc;

import com.templeregistry.service.finance.sync.SourceCredentials;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens a source connection with {@link DriverManager} (FIN-040).
 *
 * <h2>No connection pool, deliberately</h2>
 *
 * <p>A pool holds open connections to a temple's production database between batches. For a
 * scheduled read that runs occasionally against a government system on someone else's network,
 * that is a standing liability rather than a performance win: idle connections consume a quota the
 * temple sized for its own application, and a pooled connection outlives the credential resolution
 * that authorised it. One connection is opened per read and closed when the read ends.
 *
 * <p>Whether that trade holds is measurable rather than permanent — if extraction ever runs often
 * enough for connection setup to matter, this is the one class that changes.
 *
 * <h2>Read-only from the first instruction</h2>
 *
 * <p>{@link Connection#setReadOnly(boolean)} is set before the connection is handed back. It is a
 * hint to some drivers rather than a guarantee, which is why it is not the only defence: the
 * connector composes every statement itself and has no API that accepts SQL, so a write is not
 * something it can be asked to perform.
 */
public class DriverManagerJdbcConnectionFactory implements JdbcConnectionFactory {

    @Override
    public Connection open(JdbcSourceSettings settings, SourceCredentials credentials)
            throws SQLException {
        Properties properties = new Properties();
        if (credentials.hasPrincipal()) {
            properties.setProperty("user", credentials.principal());
        }
        properties.setProperty("password", credentials.secret());

        Connection connection = DriverManager.getConnection(settings.jdbcUrl(), properties);
        try {
            connection.setReadOnly(true);
            connection.setAutoCommit(true);
            return connection;
        } catch (SQLException | RuntimeException failed) {
            // A connection that could not be put into the intended mode must not escape half
            // configured; leaking it would also leak the socket.
            try {
                connection.close();
            } catch (SQLException ignored) {
                failed.addSuppressed(ignored);
            }
            throw failed;
        }
    }
}

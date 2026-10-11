package com.lifeos.db.libsql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for libSQL / Turso over Hrana HTTP with real transactions (see {@link LibSqlConnection}).
 *
 * Replaces the DBeaver libSQL driver, which only speaks the stateless legacy HTTP API and therefore
 * cannot hold a transaction (its commit/rollback were turned into no-ops, so @Transactional was not
 * atomic). URL forms: see {@link LibSqlUrl}. Auth token: the {@code password} property
 * (spring.datasource.password = TURSO_AUTH_TOKEN); the user name is ignored.
 */
public final class LibSqlHttpDriver implements Driver {

    static {
        try {
            DriverManager.registerDriver(new LibSqlHttpDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        LibSqlUrl u;
        try {
            u = LibSqlUrl.parse(url);
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), "08001", e);
        }
        String token = info == null ? null : info.getProperty("password");
        if (token == null || token.isBlank()) token = info == null ? null : info.getProperty("authToken");
        if (token == null || token.isBlank()) token = u.authTokenFromUrl();
        if (u.requiresAuthToken() && (token == null || token.isBlank())) {
            throw new SQLException(missingTokenMessage(u), "28000");
        }
        return new LibSqlConnection(new HranaClient(u.httpBase(), token), url).proxy();
    }

    public static String missingTokenMessage(LibSqlUrl u) {
        return "No auth token for remote libSQL database " + u.display()
                + ". Set TURSO_AUTH_TOKEN (e.g. `turso db tokens create <db>`), "
                + "or run with SPRING_PROFILES_ACTIVE=local to use a local SQLite file.";
    }

    @Override
    public boolean acceptsURL(String url) {
        return LibSqlUrl.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}

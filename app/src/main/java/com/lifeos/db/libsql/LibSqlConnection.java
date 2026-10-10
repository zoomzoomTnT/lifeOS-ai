package com.lifeos.db.libsql;

import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDBC connection to a libSQL server (Turso, sqld) over Hrana HTTP, with REAL transactions.
 *
 * <ul>
 *   <li>Auto-commit mode: every statement is one HTTP request on a fresh stream
 *       ({@code PRAGMA foreign_keys=ON; <stmt>; close}).</li>
 *   <li>{@code setAutoCommit(false)}: the first statement opens a stream and sends
 *       {@code PRAGMA foreign_keys=ON; BEGIN IMMEDIATE} (or {@code BEGIN DEFERRED} when read-only)
 *       in the same request; later statements continue that stream via its baton, and
 *       {@code commit()/rollback()} send {@code COMMIT/ROLLBACK} and close it. So a Spring
 *       {@code @Transactional} method is atomic on Turso exactly as on a local SQLite file.</li>
 * </ul>
 *
 * {@code PRAGMA foreign_keys} is per server-side connection and Turso defaults it to OFF, so it is
 * sent at the start of every stream (it is a no-op inside a transaction, hence before BEGIN).
 *
 * If the server rolls the transaction back on its own (e.g. RAISE(ROLLBACK), stream expired) or the
 * HTTP request fails mid-transaction, the transaction is marked aborted: further statements and
 * {@code commit()} throw, and {@code rollback()} clears the state. Nothing is ever half-committed.
 */
final class LibSqlConnection {

    private static final Logger LOG = Logger.getLogger(LibSqlConnection.class.getName());
    static final String FK_PRAGMA = "PRAGMA foreign_keys=ON";

    private final HranaClient client;
    private final String url;
    private Connection self;

    private boolean autoCommit = true;
    private boolean readOnly = false;
    private boolean closed = false;
    private int holdability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
    private int networkTimeoutMs = 0;

    // open transaction state
    private boolean txOpen = false;
    private String baton;
    private String streamBaseUrl;
    private String txAborted; // non-null = reason the open transaction can no longer commit

    LibSqlConnection(HranaClient client, String url) {
        this.client = client;
        this.url = url;
    }

    Connection proxy() {
        if (self == null) self = JdbcProxy.create(Connection.class, this);
        return self;
    }

    String url() {
        return url;
    }

    @Override
    public String toString() {
        return client.baseUrl();
    }

    // ---------------------------------------------------------------- execution core

    synchronized StmtResult execute(String sql, List<Object> args) throws SQLException {
        checkOpen();
        ObjectNode exec = HranaClient.execute(sql, args);
        if (autoCommit) {
            // the statement only runs if the PRAGMA succeeded (never without FK enforcement)
            ObjectNode batch = HranaClient.batch(List.of(
                    HranaClient.step(HranaClient.execute(FK_PRAGMA), null),
                    HranaClient.step(exec, 0)));
            HranaClient.Pipeline p = client.pipeline(null, null, List.of(batch, HranaClient.close()));
            if (!p.ok(0)) throw HranaClient.Pipeline.error(p.result(0), sql);
            tools.jackson.databind.JsonNode res = p.result(0).path("response").path("result");
            if (res.path("step_errors").path(0).isObject()) {
                throw HranaClient.Pipeline.errorOf(res.path("step_errors").path(0), FK_PRAGMA);
            }
            if (res.path("step_errors").path(1).isObject()) {
                throw HranaClient.Pipeline.errorOf(res.path("step_errors").path(1), sql);
            }
            return StmtResult.from(res.path("step_results").path(1));
        }
        if (txAborted != null) {
            throw new SQLException("Transaction was aborted (" + txAborted + "); roll back before running: "
                    + HranaClient.abbreviate(sql), "25000");
        }
        if (!txOpen) {
            return beginWith(sql, exec);
        }
        HranaClient.Pipeline p;
        try {
            p = client.pipeline(baton, streamBaseUrl, List.of(exec, HranaClient.getAutocommit()));
        } catch (SQLException e) {
            abortTx((e instanceof HranaClient.StreamGoneException ? "stream expired: " : "HTTP request failed mid-transaction: ")
                    + e.getMessage());
            throw e;
        }
        trackStream(p, p.ok(1) && p.result(1).path("response").path("is_autocommit").asBoolean(false));
        return StmtResult.from(p.stmtResult(0, sql));
    }

    /**
     * First statement of a transaction: ONE request with a Hrana batch
     * {@code [PRAGMA foreign_keys=ON, BEGIN ... if PRAGMA ok, <stmt> if BEGIN ok]}. The statement only runs when
     * BEGIN succeeded, so a failed BEGIN can never let the statement auto-commit on its own.
     */
    private StmtResult beginWith(String sql, ObjectNode exec) throws SQLException {
        String beginSql = readOnly ? "BEGIN DEFERRED" : "BEGIN IMMEDIATE";
        ObjectNode batch = HranaClient.batch(List.of(
                HranaClient.step(HranaClient.execute(FK_PRAGMA), null),
                HranaClient.step(HranaClient.execute(beginSql), 0),
                HranaClient.step(exec, 1)));
        HranaClient.Pipeline p = client.pipeline(null, null, List.of(batch, HranaClient.getAutocommit()));
        if (!p.ok(0)) {
            closeStreamQuietly(p.baton(), p.baseUrl());
            throw HranaClient.Pipeline.error(p.result(0), beginSql);
        }
        tools.jackson.databind.JsonNode res = p.result(0).path("response").path("result");
        tools.jackson.databind.JsonNode pragmaErr = res.path("step_errors").path(0);
        if (pragmaErr.isObject()) { // BEGIN and the statement were skipped: report the real cause
            closeStreamQuietly(p.baton(), p.baseUrl());
            throw HranaClient.Pipeline.errorOf(pragmaErr, FK_PRAGMA);
        }
        tools.jackson.databind.JsonNode beginErr = res.path("step_errors").path(1);
        if (beginErr.isObject()) {
            closeStreamQuietly(p.baton(), p.baseUrl());
            throw HranaClient.Pipeline.errorOf(beginErr, beginSql);
        }
        txOpen = true;
        trackStream(p, p.ok(1) && p.result(1).path("response").path("is_autocommit").asBoolean(false));
        tools.jackson.databind.JsonNode stmtErr = res.path("step_errors").path(2);
        if (stmtErr.isObject()) {
            throw HranaClient.Pipeline.errorOf(stmtErr, sql);
        }
        tools.jackson.databind.JsonNode stmtRes = res.path("step_results").path(2);
        if (!stmtRes.isObject()) {
            throw new SQLException("libSQL batch did not run the statement: " + HranaClient.abbreviate(sql));
        }
        return StmtResult.from(stmtRes);
    }

    private void trackStream(HranaClient.Pipeline p, boolean serverInAutocommit) {
        baton = p.baton();
        if (p.baseUrl() != null) streamBaseUrl = p.baseUrl();
        if (baton == null) {
            abortTx("server closed the stream");
        } else if (serverInAutocommit) {
            abortTx("server rolled the transaction back");
        }
    }

    private void abortTx(String why) {
        if (txAborted == null) txAborted = why;
    }

    private void resetTx() {
        txOpen = false;
        baton = null;
        streamBaseUrl = null;
        txAborted = null;
    }

    private void closeStreamQuietly(String b, String base) {
        if (b == null) return;
        try {
            client.pipeline(b, base, List.of(HranaClient.close()));
        } catch (SQLException e) {
            LOG.log(Level.FINE, "closing libSQL stream failed (server will expire it)", e);
        }
    }

    boolean inTransaction() {
        return txOpen;
    }

    // ---------------------------------------------------------------- transactions

    public synchronized void setAutoCommit(boolean value) throws SQLException {
        checkOpen();
        if (value == autoCommit) return;
        if (value && txOpen) commit(); // JDBC: switching auto-commit on commits the open transaction
        autoCommit = value;
    }

    public synchronized boolean getAutoCommit() throws SQLException {
        checkOpen();
        return autoCommit;
    }

    public synchronized void commit() throws SQLException {
        checkOpen();
        if (!txOpen) {
            txAborted = null;
            return; // nothing was executed in this transaction (or auto-commit mode)
        }
        if (txAborted != null) {
            String why = txAborted;
            rollback();
            throw new SQLException("Cannot commit: transaction was aborted (" + why + "); nothing was committed",
                    "40000");
        }
        String b = baton;
        String base = streamBaseUrl;
        resetTx();
        HranaClient.Pipeline p;
        try {
            p = client.pipeline(b, base, List.of(HranaClient.execute("COMMIT"), HranaClient.close()));
        } catch (HranaClient.StreamGoneException e) {
            // The server no longer has the stream (expired / invalid baton): COMMIT never ran and the
            // server discarded the open transaction. Definitely not committed.
            throw new SQLException("COMMIT failed, transaction rolled back (stream expired on the server): "
                    + e.getMessage(), "40000", e);
        } catch (SQLException e) {
            throw new SQLException("COMMIT outcome unknown (HTTP failure): " + e.getMessage(), "08007", e);
        }
        if (!p.ok(0)) {
            // closing the stream rolls back whatever is still open
            throw new SQLException("COMMIT failed, transaction rolled back: "
                    + HranaClient.Pipeline.error(p.result(0), null).getMessage(), "40000");
        }
    }

    public synchronized void rollback() throws SQLException {
        checkOpen();
        if (!txOpen) {
            txAborted = null;
            return;
        }
        String b = baton;
        String base = streamBaseUrl;
        boolean serverAlreadyRolledBack = txAborted != null;
        resetTx();
        if (b == null) return;
        try {
            HranaClient.Pipeline p = client.pipeline(b, base,
                    List.of(HranaClient.execute("ROLLBACK"), HranaClient.close()));
            if (!p.ok(0) && !serverAlreadyRolledBack) {
                LOG.warning("libSQL ROLLBACK returned an error (stream closed; server discards the transaction): "
                        + p.result(0).path("error").path("message").asString(""));
            }
        } catch (SQLException e) {
            // The stream is abandoned; the server rolls it back when the stream expires.
            LOG.warning("libSQL ROLLBACK request failed; the open transaction is discarded by the server "
                    + "when its stream expires: " + e.getMessage());
        }
    }

    public java.sql.Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException("savepoints are not supported by the libSQL HTTP driver");
    }

    public java.sql.Savepoint setSavepoint(String name) throws SQLException {
        return setSavepoint();
    }

    public void rollback(java.sql.Savepoint sp) throws SQLException {
        setSavepoint();
    }

    public void releaseSavepoint(java.sql.Savepoint sp) throws SQLException {
        setSavepoint();
    }

    public int getTransactionIsolation() {
        return Connection.TRANSACTION_SERIALIZABLE;
    }

    public void setTransactionIsolation(int level) throws SQLException {
        // SQLite transactions are always SERIALIZABLE; weaker requested levels are satisfied by it.
        if (level == Connection.TRANSACTION_NONE) {
            throw new SQLFeatureNotSupportedException("TRANSACTION_NONE is not supported");
        }
    }

    public synchronized void setReadOnly(boolean ro) throws SQLException {
        checkOpen();
        readOnly = ro;
    }

    public synchronized boolean isReadOnly() {
        return readOnly;
    }

    // ---------------------------------------------------------------- statements

    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    public Statement createStatement(int type, int concurrency) throws SQLException {
        return createStatement(type, concurrency, holdability);
    }

    public Statement createStatement(int type, int concurrency, int hold) throws SQLException {
        checkOpen();
        checkResultSetOptions(type, concurrency);
        return new LibSqlStatement(this, null, type, Statement.NO_GENERATED_KEYS, null).statementProxy();
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkOpen();
        if (autoGeneratedKeys != Statement.RETURN_GENERATED_KEYS && autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw new SQLException("invalid autoGeneratedKeys: " + autoGeneratedKeys);
        }
        return new LibSqlStatement(this, sql, ResultSet.TYPE_FORWARD_ONLY, autoGeneratedKeys, null).preparedProxy();
    }

    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();
        LibSqlStatement.checkKeyColumns(columnIndexes == null ? 0 : columnIndexes.length);
        return new LibSqlStatement(this, sql, ResultSet.TYPE_FORWARD_ONLY, Statement.RETURN_GENERATED_KEYS, null)
                .preparedProxy();
    }

    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkOpen();
        LibSqlStatement.checkKeyColumns(columnNames == null ? 0 : columnNames.length);
        String keyName = columnNames == null || columnNames.length == 0 ? null : columnNames[0];
        return new LibSqlStatement(this, sql, ResultSet.TYPE_FORWARD_ONLY, Statement.RETURN_GENERATED_KEYS, keyName)
                .preparedProxy();
    }

    public PreparedStatement prepareStatement(String sql, int type, int concurrency) throws SQLException {
        return prepareStatement(sql, type, concurrency, holdability);
    }

    public PreparedStatement prepareStatement(String sql, int type, int concurrency, int hold) throws SQLException {
        checkOpen();
        checkResultSetOptions(type, concurrency);
        return new LibSqlStatement(this, sql, type, Statement.NO_GENERATED_KEYS, null).preparedProxy();
    }

    /** Results are fully buffered: forward-only and scroll-insensitive read-only are supported. */
    static void checkResultSetOptions(int type, int concurrency) throws SQLException {
        if (type != ResultSet.TYPE_FORWARD_ONLY && type != ResultSet.TYPE_SCROLL_INSENSITIVE) {
            throw new SQLFeatureNotSupportedException("result set type " + type + " is not supported "
                    + "(only TYPE_FORWARD_ONLY / TYPE_SCROLL_INSENSITIVE)");
        }
        if (concurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException("updatable result sets are not supported");
        }
    }

    public String nativeSQL(String sql) {
        return sql;
    }

    // ---------------------------------------------------------------- lifecycle / misc

    public synchronized void close() {
        if (closed) return;
        try {
            if (txOpen) rollback();
        } catch (SQLException e) {
            LOG.log(Level.FINE, "rollback on close failed", e);
        }
        closed = true;
    }

    public void abort(Executor executor) {
        close();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public boolean isValid(int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 0) throw new SQLException("negative timeout");
        if (isClosed()) return false;
        try {
            execute("SELECT 1", List.of());
            return true;
        } catch (SQLException e) {
            LOG.log(Level.FINE, "isValid failed", e);
            return false;
        }
    }

    void checkOpen() throws SQLException {
        if (closed) throw new SQLException("connection is closed", "08003");
    }

    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return LibSqlMetaData.create(this);
    }

    public SQLWarning getWarnings() {
        return null;
    }

    public void clearWarnings() {
    }

    public String getCatalog() {
        return null;
    }

    public void setCatalog(String catalog) {
    }

    public String getSchema() {
        return null;
    }

    public void setSchema(String schema) {
    }

    public int getHoldability() {
        return holdability;
    }

    public void setHoldability(int h) {
        holdability = h;
    }

    public int getNetworkTimeout() {
        return networkTimeoutMs;
    }

    public void setNetworkTimeout(Executor executor, int ms) {
        // 0 = no explicit timeout: back to the driver default. (Hikari sets its validation timeout
        // around isValid() and then restores the original value, 0; ignoring 0 used to leave every
        // later statement with Hikari's 5 s validation timeout.)
        networkTimeoutMs = Math.max(0, ms);
        client.setRequestTimeout(ms > 0 ? Duration.ofMillis(ms) : null);
    }

    public Map<String, Class<?>> getTypeMap() {
        return Map.of();
    }

    public void setTypeMap(Map<String, Class<?>> map) {
    }

    public void setClientInfo(String name, String value) throws SQLClientInfoException {
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException {
    }

    public String getClientInfo(String name) {
        return null;
    }

    public Properties getClientInfo() {
        return new Properties();
    }
}

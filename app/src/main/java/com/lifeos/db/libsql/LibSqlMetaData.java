package com.lifeos.db.libsql;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

/**
 * Small DatabaseMetaData: product/driver identity and capability flags that Hikari, Hibernate and
 * Spring Boot ask for. Catalog queries (getTables, getColumns, …) return empty result sets.
 */
final class LibSqlMetaData {
    private LibSqlMetaData() {}

    static final String DRIVER_NAME = "lifeOS libSQL HTTP (Hrana v3)";
    static final String DRIVER_VERSION = "1.0";

    static DatabaseMetaData create(LibSqlConnection conn) {
        return (DatabaseMetaData) Proxy.newProxyInstance(LibSqlMetaData.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, m, args) -> {
                    String n = m.getName();
                    Class<?> rt = m.getReturnType();
                    switch (n) {
                        case "getDatabaseProductName": return "SQLite";
                        case "getDatabaseProductVersion": return "3.45 (libSQL)";
                        case "getDatabaseMajorVersion": return 3;
                        case "getDatabaseMinorVersion": return 45;
                        case "getDriverName": return DRIVER_NAME;
                        case "getDriverVersion": return DRIVER_VERSION;
                        case "getDriverMajorVersion": return 1;
                        case "getDriverMinorVersion": return 0;
                        case "getJDBCMajorVersion": return 4;
                        case "getJDBCMinorVersion": return 3;
                        case "getURL": return conn.url();
                        case "getUserName": return "";
                        case "getConnection": return conn.proxy();
                        case "isReadOnly": return conn.isReadOnly();
                        case "getIdentifierQuoteString": return "\"";
                        case "getSearchStringEscape": return "\\";
                        case "getCatalogSeparator": return ".";
                        case "getCatalogTerm": return "catalog";
                        case "getSchemaTerm": return "schema";
                        case "getProcedureTerm": return "procedure";
                        case "getSQLStateType": return DatabaseMetaData.sqlStateSQL;
                        case "getDefaultTransactionIsolation": return Connection.TRANSACTION_SERIALIZABLE;
                        case "getResultSetHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                        case "getRowIdLifetime": return RowIdLifetime.ROWID_UNSUPPORTED;
                        case "supportsTransactions", "supportsGetGeneratedKeys", "supportsBatchUpdates",
                             "supportsMixedCaseIdentifiers", "storesMixedCaseIdentifiers",
                             "supportsMixedCaseQuotedIdentifiers", "storesMixedCaseQuotedIdentifiers",
                             "supportsDataManipulationTransactionsOnly", "nullsAreSortedLow",
                             "supportsAlterTableWithAddColumn", "supportsColumnAliasing",
                             "supportsUnion", "supportsUnionAll", "supportsOuterJoins",
                             "supportsLikeEscapeClause", "supportsSubqueriesInExists",
                             "supportsSubqueriesInIns", "supportsCorrelatedSubqueries":
                            return true;
                        case "supportsTransactionIsolationLevel":
                            return (Integer) args[0] == Connection.TRANSACTION_SERIALIZABLE;
                        case "supportsResultSetType":
                            return (Integer) args[0] == ResultSet.TYPE_FORWARD_ONLY
                                    || (Integer) args[0] == ResultSet.TYPE_SCROLL_INSENSITIVE;
                        case "supportsResultSetConcurrency":
                            return (Integer) args[1] == ResultSet.CONCUR_READ_ONLY
                                    && ((Integer) args[0] == ResultSet.TYPE_FORWARD_ONLY
                                    || (Integer) args[0] == ResultSet.TYPE_SCROLL_INSENSITIVE);
                        case "supportsResultSetHoldability":
                            return (Integer) args[0] == ResultSet.CLOSE_CURSORS_AT_COMMIT;
                        case "unwrap":
                            if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                            throw new SQLException("not a wrapper for " + args[0]);
                        case "isWrapperFor":
                            return ((Class<?>) args[0]).isInstance(proxy);
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "DatabaseMetaData@libsql[" + conn + "]";
                        default: break;
                    }
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == String.class) return "";
                    if (rt == ResultSet.class) {
                        return new LibSqlResultSet(null, List.of(), List.of(), List.of(),
                                ResultSet.TYPE_FORWARD_ONLY).proxy();
                    }
                    throw new SQLFeatureNotSupportedException("DatabaseMetaData." + n);
                });
    }
}

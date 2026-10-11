package com.lifeos.db.libsql;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Fully buffered, read-only result set (exposed through {@link JdbcProxy}). */
final class LibSqlResultSet implements JdbcProxy.Labeled {

    private final Statement statement;
    private final List<String> columns;
    private final List<String> declTypes;
    private final List<Object[]> rows;
    private final int type;
    private int cursor = -1; // -1 = before first, rows.size() = after last
    private boolean wasNull;
    private boolean closed;
    private int fetchSize;
    private ResultSet self;

    LibSqlResultSet(Statement statement, List<String> columns, List<String> declTypes, List<Object[]> rows, int type) {
        this.statement = statement;
        this.columns = columns;
        this.declTypes = declTypes;
        this.rows = rows;
        this.type = type;
    }

    ResultSet proxy() {
        if (self == null) self = JdbcProxy.create(ResultSet.class, this);
        return self;
    }

    // ---------------------------------------------------------------- navigation

    public boolean next() throws SQLException {
        checkOpen();
        if (cursor < rows.size()) cursor++;
        return cursor < rows.size();
    }

    private void scrollable() throws SQLException {
        if (type == ResultSet.TYPE_FORWARD_ONLY) throw new SQLException("result set is TYPE_FORWARD_ONLY");
    }

    public boolean previous() throws SQLException { scrollable(); if (cursor >= 0) cursor--; return cursor >= 0; }
    public void beforeFirst() throws SQLException { scrollable(); cursor = -1; }
    public void afterLast() throws SQLException { scrollable(); cursor = rows.size(); }
    public boolean first() throws SQLException { scrollable(); cursor = 0; return !rows.isEmpty(); }
    public boolean last() throws SQLException { scrollable(); cursor = rows.size() - 1; return !rows.isEmpty(); }
    public boolean absolute(int row) throws SQLException {
        scrollable();
        int target = row > 0 ? row - 1 : rows.size() + row;
        cursor = Math.max(-1, Math.min(rows.size(), target));
        return cursor >= 0 && cursor < rows.size();
    }
    public boolean relative(int n) throws SQLException { return absolute(cursor + 1 + n); }
    public int getRow() { return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0; }
    public boolean isBeforeFirst() { return cursor == -1 && !rows.isEmpty(); }
    public boolean isAfterLast() { return cursor >= rows.size() && !rows.isEmpty(); }
    public boolean isFirst() { return cursor == 0 && !rows.isEmpty(); }
    public boolean isLast() { return !rows.isEmpty() && cursor == rows.size() - 1; }

    // ---------------------------------------------------------------- values

    private Object raw(int col) throws SQLException {
        checkOpen();
        if (cursor < 0 || cursor >= rows.size()) throw new SQLException("no current row");
        if (col < 1 || col > columns.size()) throw new SQLException("column index out of range: " + col);
        Object v = rows.get(cursor)[col - 1];
        wasNull = v == null;
        return v;
    }

    public boolean wasNull() {
        return wasNull;
    }

    @Override
    public int findColumn(String label) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(label)) return i + 1;
        }
        // "t.col" style labels
        for (int i = 0; i < columns.size(); i++) {
            String c = columns.get(i);
            int dot = c.lastIndexOf('.');
            if (dot >= 0 && c.substring(dot + 1).equalsIgnoreCase(label)) return i + 1;
        }
        throw new SQLException("no such column: " + label + " (have " + columns + ")");
    }

    public Object getObject(int col) throws SQLException {
        return raw(col);
    }

    public String getString(int col) throws SQLException {
        Object v = raw(col);
        if (v == null) return null;
        if (v instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return v.toString();
    }

    public String getNString(int col) throws SQLException {
        return getString(col);
    }

    public long getLong(int col) throws SQLException {
        Object v = raw(col);
        if (v == null) return 0L;
        Number n = toNumber(v);
        if (n instanceof Double d && (d >= 0x1p63 || d < -0x1p63 || d.isNaN())) {
            throw new SQLException("value " + d + " in column " + col + " is out of range for long", "22003");
        }
        return n.longValue();
    }

    public int getInt(int col) throws SQLException { return (int) ranged(col, Integer.MIN_VALUE, Integer.MAX_VALUE, "int"); }
    public short getShort(int col) throws SQLException { return (short) ranged(col, Short.MIN_VALUE, Short.MAX_VALUE, "short"); }
    public byte getByte(int col) throws SQLException { return (byte) ranged(col, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte"); }

    /** Narrowing never wraps silently: out-of-range values throw (SQLSTATE 22003). */
    private long ranged(int col, long min, long max, String type) throws SQLException {
        long v = getLong(col);
        if (v < min || v > max) {
            throw new SQLException("value " + v + " in column " + col + " is out of range for " + type, "22003");
        }
        return v;
    }

    public double getDouble(int col) throws SQLException {
        Object v = raw(col);
        return v == null ? 0d : toNumber(v).doubleValue();
    }

    public float getFloat(int col) throws SQLException { return (float) getDouble(col); }

    public boolean getBoolean(int col) throws SQLException {
        Object v = raw(col);
        if (v == null) return false;
        if (v instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(s);
        }
        return toNumber(v).doubleValue() != 0;
    }

    public BigDecimal getBigDecimal(int col) throws SQLException {
        Object v = raw(col);
        if (v == null) return null;
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        try {
            return new BigDecimal(v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString().strip());
        } catch (NumberFormatException e) {
            throw new SQLException("not a number: " + v);
        }
    }

    public BigDecimal getBigDecimal(int col, int scale) throws SQLException {
        BigDecimal d = getBigDecimal(col);
        return d == null ? null : d.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    public byte[] getBytes(int col) throws SQLException {
        Object v = raw(col);
        if (v == null) return null;
        if (v instanceof byte[] b) return b;
        return v.toString().getBytes(StandardCharsets.UTF_8);
    }

    public InputStream getBinaryStream(int col) throws SQLException {
        byte[] b = getBytes(col);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    public Reader getCharacterStream(int col) throws SQLException {
        String s = getString(col);
        return s == null ? null : new StringReader(s);
    }

    // Text without an offset is read as UTC for timestamps (SQLite's own datetime() convention),
    // or in the Calendar's zone when one is passed; text with Z / ±hh:mm is an exact instant.
    public Timestamp getTimestamp(int col) throws SQLException { return getTimestamp(col, null); }

    public Timestamp getTimestamp(int col, Calendar cal) throws SQLException {
        Object v = raw(col);
        if (v == null) return null;
        if (v instanceof Long l) return new Timestamp(l);
        java.time.ZoneId zone = cal == null ? java.time.ZoneOffset.UTC : cal.getTimeZone().toZoneId();
        return Timestamp.from(toInstant(v.toString(), zone));
    }

    public Date getDate(int col) throws SQLException { return getDate(col, null); }

    public Date getDate(int col, Calendar cal) throws SQLException {
        Object v = raw(col);
        if (v == null) return null;
        if (v instanceof Long l) return new Date(l);
        String s = v.toString().strip();
        LocalDate d;
        try {
            d = LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            throw new SQLException("not a date: " + s);
        }
        if (cal == null) return Date.valueOf(d); // midnight in the JVM zone, as JDBC specifies
        return new Date(d.atStartOfDay(cal.getTimeZone().toZoneId()).toInstant().toEpochMilli());
    }

    public Time getTime(int col) throws SQLException { return getTime(col, null); }

    public Time getTime(int col, Calendar cal) throws SQLException {
        Object v = raw(col);
        if (v == null) return null;
        if (v instanceof Long l) return new Time(l);
        String s = v.toString().strip();
        if (s.length() > 10 && (s.charAt(10) == 'T' || s.charAt(10) == ' ')) s = s.substring(11);
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1);
        LocalTime t;
        try {
            t = LocalTime.parse(s);
        } catch (RuntimeException e) {
            throw new SQLException("not a time: " + v);
        }
        if (cal == null) return Time.valueOf(t);
        return new Time(t.atDate(LocalDate.EPOCH).atZone(cal.getTimeZone().toZoneId()).toInstant().toEpochMilli());
    }

    @SuppressWarnings("unchecked")
    public <T> T getObject(int col, Class<T> cls) throws SQLException {
        Object v = raw(col);
        if (v == null) return null;
        if (cls.isInstance(v)) return (T) v;
        Object out;
        if (cls == Long.class) out = getLong(col);
        else if (cls == Integer.class) out = getInt(col);
        else if (cls == Short.class) out = getShort(col);
        else if (cls == Byte.class) out = getByte(col);
        else if (cls == Double.class) out = getDouble(col);
        else if (cls == Float.class) out = getFloat(col);
        else if (cls == Boolean.class) out = getBoolean(col);
        else if (cls == String.class) out = getString(col);
        else if (cls == BigDecimal.class) out = getBigDecimal(col);
        else if (cls == java.math.BigInteger.class) out = getBigDecimal(col).toBigInteger();
        else if (cls == byte[].class) out = getBytes(col);
        else if (cls == Timestamp.class) out = getTimestamp(col);
        else if (cls == Date.class) out = getDate(col);
        else if (cls == Time.class) out = getTime(col);
        else if (cls == LocalDateTime.class) out = localDateTime(v.toString());
        else if (cls == LocalDate.class) out = getDate(col).toLocalDate();
        else if (cls == LocalTime.class) out = getTime(col).toLocalTime();
        else if (cls == Instant.class) out = v instanceof Long l ? Instant.ofEpochMilli(l) : toInstant(v.toString(), java.time.ZoneOffset.UTC);
        else if (cls == OffsetDateTime.class) out = (v instanceof Long l ? Instant.ofEpochMilli(l) : toInstant(v.toString(), java.time.ZoneOffset.UTC)).atOffset(java.time.ZoneOffset.UTC);
        else if (cls == java.util.UUID.class) out = java.util.UUID.fromString(v.toString());
        else throw new SQLException("cannot convert " + v.getClass().getSimpleName() + " to " + cls.getName());
        return (T) out;
    }

    private static Number toNumber(Object v) throws SQLException {
        if (v instanceof Number n) return n;
        String s = v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString();
        s = s.strip();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e2) {
                throw new SQLException("not a number: '" + HranaClient.abbreviate(s) + "'");
            }
        }
    }

    private static boolean hasOffset(String t) {
        return t.endsWith("Z") || t.endsWith("z") || t.matches(".*T.*[+-]\\d\\d:?\\d\\d$");
    }

    /** ISO text → instant; text without an offset is wall time in {@code zone}. */
    private static Instant toInstant(String s, java.time.ZoneId zone) throws SQLException {
        String t = s.strip().replace(' ', 'T');
        try {
            if (hasOffset(t)) return OffsetDateTime.parse(t.replace('z', 'Z')).toInstant();
            if (t.length() == 10) return LocalDate.parse(t).atStartOfDay(zone).toInstant();
            return LocalDateTime.parse(t).atZone(zone).toInstant();
        } catch (RuntimeException e) {
            throw new SQLException("not a timestamp: " + s);
        }
    }

    /** Wall time as written; text with an offset is converted to UTC wall time. */
    private static LocalDateTime localDateTime(String s) throws SQLException {
        String t = s.strip().replace(' ', 'T');
        try {
            if (hasOffset(t)) return LocalDateTime.ofInstant(OffsetDateTime.parse(t.replace('z', 'Z')).toInstant(), java.time.ZoneOffset.UTC);
            if (t.length() == 10) return LocalDate.parse(t).atStartOfDay();
            return LocalDateTime.parse(t);
        } catch (RuntimeException e) {
            throw new SQLException("not a timestamp: " + s);
        }
    }

    // ---------------------------------------------------------------- metadata / misc

    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return JdbcProxy.create(ResultSetMetaData.class, new Meta());
    }

    /** Column metadata; SQL type comes from the declared type, else from the first non-null value. */
    final class Meta {
        private void check(int c) throws SQLException {
            if (c < 1 || c > columns.size()) throw new SQLException("column index out of range: " + c);
        }

        public int getColumnCount() { return columns.size(); }
        public String getColumnLabel(int c) throws SQLException { check(c); return columns.get(c - 1); }
        public String getColumnName(int c) throws SQLException { return getColumnLabel(c); }

        public int getColumnType(int c) throws SQLException {
            check(c);
            String d = declTypes.size() >= c ? declTypes.get(c - 1) : null;
            if (d != null) {
                String u = d.toUpperCase(Locale.ROOT);
                if (u.contains("INT")) return Types.BIGINT;
                if (u.contains("CHAR") || u.contains("CLOB") || u.contains("TEXT")) return Types.VARCHAR;
                if (u.contains("BLOB")) return Types.BLOB;
                if (u.contains("REAL") || u.contains("FLOA") || u.contains("DOUB")) return Types.DOUBLE;
                return Types.NUMERIC;
            }
            for (Object[] r : rows) {
                Object v = r[c - 1];
                if (v instanceof Long) return Types.BIGINT;
                if (v instanceof Double) return Types.DOUBLE;
                if (v instanceof String) return Types.VARCHAR;
                if (v instanceof byte[]) return Types.BLOB;
            }
            return Types.NULL;
        }

        public String getColumnTypeName(int c) throws SQLException {
            String d = declTypes.size() >= c ? declTypes.get(c - 1) : null;
            if (d != null) return d;
            return switch (getColumnType(c)) {
                case Types.BIGINT -> "INTEGER";
                case Types.DOUBLE -> "REAL";
                case Types.VARCHAR -> "TEXT";
                case Types.BLOB -> "BLOB";
                default -> "NULL";
            };
        }

        public String getColumnClassName(int c) throws SQLException {
            return switch (getColumnType(c)) {
                case Types.BIGINT -> Long.class.getName();
                case Types.DOUBLE -> Double.class.getName();
                case Types.BLOB -> byte[].class.getName();
                case Types.VARCHAR -> String.class.getName();
                default -> Object.class.getName();
            };
        }

        public int isNullable(int c) { return ResultSetMetaData.columnNullableUnknown; }
        public boolean isAutoIncrement(int c) { return false; }
        public boolean isCaseSensitive(int c) { return true; }
        public boolean isSearchable(int c) { return true; }
        public boolean isCurrency(int c) { return false; }
        public boolean isSigned(int c) { return true; }
        public int getColumnDisplaySize(int c) { return Integer.MAX_VALUE; }
        public int getPrecision(int c) { return 0; }
        public int getScale(int c) { return 0; }
        public String getSchemaName(int c) { return ""; }
        public String getTableName(int c) { return ""; }
        public String getCatalogName(int c) { return ""; }
        public boolean isReadOnly(int c) { return true; }
        public boolean isWritable(int c) { return false; }
        public boolean isDefinitelyWritable(int c) { return false; }
    }

    public Statement getStatement() { return statement; }
    public int getType() { return type; }
    public int getConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    public int getHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
    public void setFetchDirection(int d) { }
    public int getFetchSize() { return fetchSize; }
    public void setFetchSize(int n) { fetchSize = n; }
    public SQLWarning getWarnings() { return null; }
    public void clearWarnings() { }
    public String getCursorName() throws SQLException { throw new java.sql.SQLFeatureNotSupportedException("cursor names"); }
    public void close() { closed = true; }
    public boolean isClosed() { return closed; }

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("result set is closed");
    }
}

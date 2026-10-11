package com.lifeos.repo.jdbc;

final class SqliteIds {
    private SqliteIds() {}

    static Long longOrNull(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

}

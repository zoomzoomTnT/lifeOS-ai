package com.lifeos.repo.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

final class SqliteIds {
    private SqliteIds() {}

    static long lastInsertId(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        if (id == null) throw new IllegalStateException("last_insert_rowid was null");
        return id;
    }

    static Long longOrNull(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

}

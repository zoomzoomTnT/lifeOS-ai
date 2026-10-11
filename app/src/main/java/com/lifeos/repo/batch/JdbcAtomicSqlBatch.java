package com.lifeos.repo.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local SQLite: plain JDBC statements inside a Spring transaction (joins the caller's, if any). */
public class JdbcAtomicSqlBatch implements AtomicSqlBatch {

    private final JdbcTemplate jdbc;

    public JdbcAtomicSqlBatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<List<Map<String, Object>>> execute(List<SqlStep> steps) {
        List<List<Map<String, Object>>> out = new ArrayList<>(steps.size());
        for (SqlStep step : steps) {
            Object[] args = step.args().toArray();
            if (returnsRows(step.sql())) {
                out.add(jdbc.queryForList(step.sql(), args));
            } else {
                jdbc.update(step.sql(), args);
                out.add(List.of());
            }
        }
        return out;
    }

    static boolean returnsRows(String sql) {
        String s = sql.strip().toUpperCase(Locale.ROOT);
        return s.startsWith("SELECT") || s.contains(" RETURNING ") || s.contains("\nRETURNING ");
    }
}

package com.lifeos.repo.jdbc;

import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoKind;
import com.lifeos.domain.MemoStatus;
import com.lifeos.repo.MemoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcMemoRepository implements MemoRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Memo> ROW = (rs, n) -> new Memo(
            rs.getLong("id"),
            rs.getLong("owner_id"),
            rs.getString("title"),
            rs.getString("body"),
            MemoKind.from(rs.getString("kind")),
            MemoStatus.from(rs.getString("status")),
            rs.getInt("priority"),
            rs.getString("due_at"),
            rs.getString("timezone"),
            rs.getString("cron_expr"),
            rs.getString("cron_tz"),
            rs.getString("source_domain"),
            rs.getString("source_table"),
            SqliteIds.longOrNull(rs.getObject("source_id")),
            rs.getString("payload_json")
    );

    @Override
    public long insert(Memo memo) {
        jdbc.update("""
                        INSERT INTO memos (owner_id, title, body, kind, status, priority,
                            due_at, timezone, cron_expr, cron_tz,
                            source_domain, source_table, source_id, payload_json)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                memo.ownerId(), memo.title(), memo.body(),
                memo.kind().db(), memo.status().db(), memo.priority(),
                memo.dueAt(), memo.timezone(), memo.cronExpr(), memo.cronTz(),
                memo.sourceDomain(), memo.sourceTable(), memo.sourceId(), memo.payloadJson()
        );
        return SqliteIds.lastInsertId(jdbc);
    }

    @Override
    public List<Memo> due(long ownerId, int withinHours) {
        return jdbc.query("""
                SELECT * FROM memos
                WHERE owner_id = ?
                  AND status IN ('open','snoozed')
                  AND due_at IS NOT NULL
                  AND due_at <= strftime('%Y-%m-%dT%H:%M:%SZ', 'now', ? || ' hours')
                ORDER BY priority ASC, due_at ASC
                LIMIT 20
                """, ROW, ownerId, withinHours);
    }

    @Override
    public List<Memo> dueForWake(long ownerId, int leadMinutes, boolean nightPriorityOnly) {
        return jdbc.query("""
                SELECT * FROM memos
                WHERE owner_id = ?
                  AND status IN ('open','snoozed')
                  AND due_at IS NOT NULL
                  AND due_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now', '+' || ? || ' minutes')
                  AND (last_fired_at IS NULL
                       OR last_fired_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now','-6 hours'))
                  AND (? = 0 OR priority = 1)
                ORDER BY priority ASC, due_at ASC
                LIMIT 10
                """, ROW, ownerId, leadMinutes, nightPriorityOnly ? 1 : 0);
    }

    @Override
    public void updateStatus(long id, MemoStatus status) {
        jdbc.update("UPDATE memos SET status=?, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?",
                status.db(), id);
    }

    @Override
    public void updateDueAt(long id, String dueAt) {
        jdbc.update("UPDATE memos SET due_at=?, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?",
                dueAt, id);
    }

    @Override
    public void updateAutomationId(long id, String automationId) {
        jdbc.update("UPDATE memos SET automation_id=? WHERE id=?", automationId, id);
    }

    @Override
    public void markFired(long id) {
        jdbc.update("UPDATE memos SET last_fired_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?", id);
    }
}

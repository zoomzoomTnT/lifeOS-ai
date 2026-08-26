package com.lifeos.repo.jdbc;

import com.lifeos.domain.PersonRole;
import com.lifeos.repo.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcPersonRepository implements PersonRepository {

    private final JdbcTemplate jdbc;

    @Override
    public Optional<Long> findIdByHandle(String handle) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM people WHERE handle = ?", handle);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(((Number) rows.get(0).get("id")).longValue());
    }

    @Override
    public Optional<String> findOwnerHandle() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT handle FROM people WHERE role = ? LIMIT 1", PersonRole.OWNER.db());
        if (rows.isEmpty()) return Optional.empty();
        return Optional.ofNullable((String) rows.get(0).get("handle"));
    }

    @Override
    public long insertMember(String handle, String displayName) {
        jdbc.update("INSERT INTO people (handle, display_name, role) VALUES (?,?,?)",
                handle, displayName, PersonRole.MEMBER.db());
        return SqliteIds.lastInsertId(jdbc);
    }
}

package com.lifeos.repo.jdbc;

import com.lifeos.repo.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcMerchantRepository implements MerchantRepository {

    private final JdbcTemplate jdbc;

    @Override
    public Optional<Long> findIdByNameNorm(String nameNorm) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM merchants WHERE name_norm = ?", nameNorm);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(((Number) rows.get(0).get("id")).longValue());
    }

    @Override
    public long insert(String name, String nameNorm) {
        jdbc.update("INSERT INTO merchants (name, name_norm) VALUES (?,?)", name, nameNorm);
        return SqliteIds.lastInsertId(jdbc);
    }
}

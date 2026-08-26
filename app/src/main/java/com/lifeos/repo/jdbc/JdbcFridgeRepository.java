package com.lifeos.repo.jdbc;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.repo.FridgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcFridgeRepository implements FridgeRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<FridgeItem> ROW = (rs, n) -> new FridgeItem(
            rs.getLong("id"),
            rs.getLong("owner_id"),
            rs.getLong("added_by_id"),
            rs.getString("name"),
            rs.getString("name_norm"),
            FoodCategory.from(rs.getString("category")),
            FridgeLocation.from(rs.getString("location")),
            FridgeStatus.from(rs.getString("status")),
            rs.getDouble("qty"),
            rs.getString("expires_at"),
            SqliteIds.longOrNull(rs.getObject("source_receipt_id")),
            SqliteIds.longOrNull(rs.getObject("source_receipt_item_id"))
    );

    @Override
    public long insertInStock(FridgeItem item, Integer expiresInDays) {
        jdbc.update("""
                        INSERT INTO fridge_items (owner_id, added_by_id, name, name_norm, category, location, status, qty,
                            purchased_at, expires_at, source_receipt_id, source_receipt_item_id)
                        VALUES (?,?,?,?,?,?,?,?, strftime('%Y-%m-%dT%H:%M:%SZ','now'),
                            CASE WHEN ? IS NOT NULL THEN strftime('%Y-%m-%dT%H:%M:%SZ','now', ? || ' days') ELSE NULL END,
                            ?, ?)
                        """,
                item.ownerId(), item.addedById(), item.name(), item.nameNorm(),
                item.category() == null ? null : item.category().db(),
                item.location().db(),
                FridgeStatus.IN_STOCK.db(),
                item.qty(),
                expiresInDays, expiresInDays,
                item.sourceReceiptId(), item.sourceReceiptItemId()
        );
        return SqliteIds.lastInsertId(jdbc);
    }

    @Override
    public List<FridgeItem> list(long ownerId, FridgeStatus status, Integer expiringWithinHours) {
        if (expiringWithinHours != null) {
            return jdbc.query("""
                    SELECT * FROM fridge_items
                    WHERE owner_id = ? AND status = ?
                      AND expires_at IS NOT NULL
                      AND expires_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now', ? || ' hours')
                    ORDER BY expires_at ASC
                    """, ROW, ownerId, FridgeStatus.IN_STOCK.db(), expiringWithinHours);
        }
        if (status != null) {
            return jdbc.query("SELECT * FROM fridge_items WHERE owner_id = ? AND status = ? ORDER BY id DESC",
                    ROW, ownerId, status.db());
        }
        return jdbc.query("SELECT * FROM fridge_items WHERE owner_id = ? ORDER BY id DESC LIMIT 50", ROW, ownerId);
    }

    @Override
    public void updateStatus(long id, FridgeStatus status) {
        jdbc.update("UPDATE fridge_items SET status=?, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?",
                status.db(), id);
    }

    @Override
    public void bumpExpiryOneDay(long id) {
        jdbc.update("""
                UPDATE fridge_items SET expires_at = strftime('%Y-%m-%dT%H:%M:%SZ', expires_at, '+1 day'),
                updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?
                """, id);
    }
}

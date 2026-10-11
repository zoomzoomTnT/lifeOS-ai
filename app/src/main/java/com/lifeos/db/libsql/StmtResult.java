package com.lifeos.db.libsql;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** One statement's result: columns, buffered rows, affected row count, last insert rowid. */
record StmtResult(List<String> columns, List<String> declTypes, List<Object[]> rows,
                  long affectedRows, Long lastInsertRowid) {

    static StmtResult from(JsonNode r) {
        List<String> cols = new ArrayList<>();
        List<String> decl = new ArrayList<>();
        for (JsonNode c : r.path("cols")) {
            cols.add(c.path("name").asString(""));
            JsonNode d = c.path("decltype");
            decl.add(d.isString() ? d.asString() : null);
        }
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode row : r.path("rows")) {
            Object[] vals = new Object[cols.size()];
            for (int i = 0; i < vals.length; i++) vals[i] = HranaClient.fromValue(row.path(i));
            rows.add(vals);
        }
        JsonNode rowid = r.path("last_insert_rowid");
        Long last = rowid.isString() ? Long.valueOf(rowid.asString())
                : rowid.isNumber() ? rowid.asLong() : null;
        return new StmtResult(cols, decl, rows, r.path("affected_row_count").asLong(0), last);
    }

    boolean hasColumns() {
        return !columns.isEmpty();
    }
}

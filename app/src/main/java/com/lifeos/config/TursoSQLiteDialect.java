package com.lifeos.config;

import org.hibernate.community.dialect.SQLiteDialect;

/**
 * SQLiteDialect that gets IDENTITY ids with {@code INSERT ... RETURNING id} (SQLite 3.35+, libSQL).
 *
 * The stock dialect runs the INSERT and then a separate {@code select last_insert_rowid()}. Over
 * Turso's HTTP API an auto-commit statement runs on its own server-side stream, so that second
 * statement could see a different connection and return the wrong id; the id has to come back from
 * the INSERT itself. It also saves a round trip. Works the same on a local SQLite file.
 */
public class TursoSQLiteDialect extends SQLiteDialect {

    @Override
    public boolean supportsInsertReturning() {
        return true;
    }

    @Override
    public boolean supportsInsertReturningGeneratedKeys() {
        return false;
    }

    @Override
    public boolean getDefaultUseGetGeneratedKeys() {
        return false;
    }
}

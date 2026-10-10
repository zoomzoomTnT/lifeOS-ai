package com.lifeos.config;

import org.hibernate.community.dialect.SQLiteDialect;

/**
 * SQLiteDialect that gets IDENTITY ids with {@code INSERT ... RETURNING id} (SQLite 3.35+, libSQL).
 *
 * The stock dialect runs the INSERT and then a separate {@code select last_insert_rowid()}. Over
 * Turso's stateless HTTP API that second statement runs on a different server-side connection and
 * returns the wrong id, so the id has to come back from the INSERT itself. Works the same on a
 * local SQLite file.
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

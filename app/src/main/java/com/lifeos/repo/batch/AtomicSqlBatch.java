package com.lifeos.repo.batch;

import java.util.List;
import java.util.Map;

/**
 * Runs a list of SQL statements all-or-nothing: either every statement commits, or none does.
 *
 * <ul>
 *   <li>Local SQLite: a normal JDBC transaction ({@link JdbcAtomicSqlBatch}).</li>
 *   <li>Turso: one Hrana-over-HTTP pipeline request with BEGIN … COMMIT in a conditional batch
 *       ({@link TursoAtomicSqlBatch}), because the JDBC driver cannot hold a transaction.</li>
 * </ul>
 *
 * Later statements can refer to rows written by earlier ones (sub-selects on unique keys,
 * {@code last_insert_rowid()}); everything runs on one connection.
 */
public interface AtomicSqlBatch {

    /**
     * @return for each step, the rows it produced (e.g. from {@code RETURNING}); empty list if none.
     * @throws AtomicBatchException if any step failed; nothing was committed in that case.
     */
    List<List<Map<String, Object>>> execute(List<SqlStep> steps);
}

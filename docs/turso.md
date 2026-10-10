# Database: Turso (libSQL)

Production runs on Turso (hosted libSQL) over HTTPS. Local development can still use a SQLite
file (`local` profile). Both use the same schema and the same SQL.

## Configuration

| Variable | Required | Notes |
|---|---|---|
| `TURSO_DATABASE_URL` | yes (default profile) | `jdbc:libsql:https://<db>-<org>.turso.io`, or `libsql://<db>-<org>.turso.io` as printed by `turso db show <db> --url`. The old `jdbc:dbeaver:libsql:` form still works. **No default**: if unset, startup fails with `No database configured: TURSO_DATABASE_URL is not set`. |
| `TURSO_AUTH_TOKEN` | yes for `https`/`libsql://` | `turso db tokens create <db>`. If missing, startup fails with a message naming `TURSO_AUTH_TOKEN` (not Hikari's `JWT error`). Plain `http://` (local / CI sqld) needs no token. |

Local SQLite instead: `SPRING_PROFILES_ACTIVE=local` (`mvn spring-boot:run -Dspring-boot.run.profiles=local`,
or `docker compose -f docker-compose.yml -f docker-compose.local.yml up`).

CD passes `TURSO_DATABASE_URL` from the repo **variable** `TURSO_DATABASE_URL` (or a secret of the
same name) and `TURSO_AUTH_TOKEN` from the secret; empty values fall back to `~/lifeos/.env`. The
deploy script stops **before** touching the host if neither provides a URL (and a token for https).

## Driver and transactions

The app uses its own small JDBC driver, `com.lifeos.db.libsql.LibSqlHttpDriver`, speaking Turso's
Hrana-over-HTTP protocol (`POST /v3/pipeline`). It replaced the DBeaver libSQL driver, which only
supports the stateless legacy API: it could not hold a transaction, so commit/rollback had been
turned into no-ops and `@Transactional` methods were not atomic on Turso.

- Auto-commit statement: one HTTP request on a fresh server stream.
- Transaction (`@Transactional`, `setAutoCommit(false)`): the first statement opens a stream with
  `BEGIN IMMEDIATE` (`BEGIN DEFERRED` for read-only transactions); later statements continue the
  same stream via its baton; `commit()` / `rollback()` send `COMMIT` / `ROLLBACK`. A failure
  half-way leaves nothing behind, exactly like a local SQLite transaction.
- If the server rolls a transaction back on its own (stream expired, lock timeout,
  `RAISE(ROLLBACK)`) or an HTTP request fails mid-transaction, the transaction is marked aborted:
  further statements and `commit()` throw. Nothing is ever half-committed.
- Keep transactions short. sqld/Turso time out a write transaction that holds the lock while
  another writer waits (about 5 s); the loser gets an error, never partial data.
- Generated keys: `RETURN_GENERATED_KEYS` / key-column overloads return `last_insert_rowid`.
  Entities get ids from `INSERT ... RETURNING id` (`TursoSQLiteDialect`). Unsupported JDBC
  features (savepoints, updatable or scroll-sensitive result sets, callable statements) throw
  `SQLFeatureNotSupportedException` instead of silently doing nothing.

### Foreign keys

`PRAGMA foreign_keys` is per server-side connection. Turso documents it as OFF by default (a local
sqld 0.24 happens to start with it ON). A pooled JDBC connection is not one server connection
(each transaction / auto-commit statement is its own stream), so the old Hikari
`connection-init-sql` did not reliably apply. The driver now sends `PRAGMA foreign_keys=ON` at the
start of **every** stream (before `BEGIN`, where SQLite honours it). Verified by
`LibSqlConnectionTest` (fake server with FKs off by default) and `LibSqlRealServerTest` (sqld).

## Migrating the old SQLite file (`data/life.db`)

Manual, one-off, reusable for any target (nothing hard-coded). Runs from the app image:

```bash
cd ~/lifeos
docker compose -f compose.yaml stop api           # nothing writes to either database meanwhile
export TURSO_DATABASE_URL='jdbc:libsql:https://<db>-<org>.turso.io'
export TURSO_AUTH_TOKEN='...'
./migrate-sqlite-to-turso.sh data/life.db --dry-run    # copy + verify, then roll back
./migrate-sqlite-to-turso.sh data/life.db              # for real
```

Add `--wipe-target` if the target already has rows (for example the seed rows the app writes when it
first starts against an empty database); without it the tool refuses a non-empty target.

What it does, in **one** target transaction: create missing tables from the source DDL, copy all
rows with their ids (multi-row INSERTs), carry `sqlite_sequence`, create missing indexes / views /
triggers, check row counts and `PRAGMA foreign_key_check`, then commit (or roll back on any problem
or `--dry-run`). Without Docker: `java -cp app/target/life-os-app.jar
-Dloader.main=com.lifeos.tools.SqliteToLibsql org.springframework.boot.loader.launch.PropertiesLauncher
<file> [options]`.

CD no longer makes rotating copies of `data/life.db` on every deploy (the app does not write it
any more). It keeps the file in place and takes one frozen snapshot,
`data/backups/life.db.pre-turso`, the first time it runs. Live data is in Turso (point-in-time
restore is Turso's).

## Tests

- `LibSqlConnectionTest`, `ReceiptAtomicTursoTest`, `TursoTransactionalAtomicityTest`,
  `JpaIdentityReturningTursoTest`, `OpsServiceRecordAiTest`, `SqliteToLibsqlTest`: in-process
  Hrana server (`FakeTursoServer`, SQLite file behind it).
- `LibSqlRealServerTest`, `RealSqldAppTest`: a real sqld, enabled by `LIBSQL_TEST_URL`. CI runs one
  as a service container; locally:
  `sqld --http-listen-addr 127.0.0.1:8080 & LIBSQL_TEST_URL=jdbc:libsql:http://127.0.0.1:8080 mvn test`.
- CI's Docker job runs the compose smoke test twice: local SQLite overlay, and the production
  compose file against a sqld container.

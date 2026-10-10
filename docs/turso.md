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
- Each HTTP request has a 30 s timeout by default; `Connection.setNetworkTimeout(ms)` overrides
  it and `0` restores the default (Hikari sets its 5 s validation timeout only around `isValid`).
- A `COMMIT` that hits an expired / unknown stream (HTTP 400 `STREAM_EXPIRED`) is reported as
  rolled back (SQLSTATE 40000): the server already discarded the transaction. Only a COMMIT whose
  HTTP request itself failed is reported as outcome unknown (08007).
- The first statement of a transaction travels in one Hrana batch with `BEGIN`; it only runs if
  `BEGIN` succeeded, so a failed `BEGIN` cannot let it auto-commit.
- Dates/times are ISO-8601 text with seconds: `Timestamp`/`Instant` as UTC (`...Z`),
  `OffsetDateTime` with its offset, `LocalDateTime` as written. Text without an offset is read as
  UTC by `getTimestamp` (SQLite's convention) unless a `Calendar` is passed.
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

Manual, one-off, reusable for any target (nothing hard-coded). **Migrate before cutover**, into the
new, empty database, so nothing the new app writes can be lost:

1. Stop the old API so `data/life.db` is final: `cd ~/lifeos && docker compose -f compose.yaml stop api`.
2. Migrate (the script is in the repo, `docker/migrate-sqlite-to-turso.sh`; CD also copies it to
   `~/lifeos/` on every deploy). Use a **pinned** image that contains the tool, e.g. the
   `sha-<commit>` tag CI/CD pushed for the release commit (`:latest` is refused):

   ```bash
   export LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:sha-<commit>
   export TURSO_DATABASE_URL='jdbc:libsql:https://<new-db>-<org>.turso.io'
   export TURSO_AUTH_TOKEN='...'
   ./migrate-sqlite-to-turso.sh data/life.db --dry-run    # copy + verify, then roll back
   ./migrate-sqlite-to-turso.sh data/life.db              # for real
   ```
3. Deploy the Turso release (CD) with `TURSO_DATABASE_URL` pointing at that database.

What it does, in **one** target transaction: create the app schema (`schema.sql`) objects the target
lacks — so an old file without e.g. `merchants` still ends up complete — and any source-only
tables; copy all rows with their ids (multi-row INSERTs; app seed rows of copied tables are
replaced by the source's); carry `sqlite_sequence`; create missing source indexes / views /
triggers; check row counts and `PRAGMA foreign_key_check`; then commit (or roll back on any
problem or `--dry-run`). The source is read in one read transaction (consistent snapshot) and never
modified.

Non-empty target: refused. `--wipe-target` replaces existing rows, and on its own is only allowed
when the target holds nothing but the app's seed rows (owner, settings, model prices — what the app
writes when it first starts on an empty database). If it holds more (e.g. rows written after a
deploy), the tool prints the exact counts and requires `--confirm-target-counts t1=N,...` matching
them; those rows are deleted. Counts are re-checked under the write lock.

Without Docker: `java -cp app/target/life-os-app.jar -Dloader.main=com.lifeos.tools.SqliteToLibsql
org.springframework.boot.loader.launch.PropertiesLauncher <file> [options]`.

CD no longer makes rotating copies of `data/life.db` on every deploy (the app does not write it
any more). It keeps the file in place and takes one frozen snapshot,
`data/backups/life.db.pre-turso`, the first time it runs: with `sqlite3 .backup` when the host has
sqlite3, otherwise after stopping the API (no torn copy of a live database + WAL). Live data is in
Turso (point-in-time restore is Turso's).

## Tests

- `LibSqlConnectionTest`, `ReceiptAtomicTursoTest`, `TursoTransactionalAtomicityTest`,
  `JpaIdentityReturningTursoTest`, `OpsServiceRecordAiTest`, `SqliteToLibsqlTest`: in-process
  Hrana server (`FakeTursoServer`, SQLite file behind it).
- `LibSqlRealServerTest`, `RealSqldAppTest`: a real sqld, enabled by `LIBSQL_TEST_URL`;
  `SqliteToLibsqlRealServerTest`: migration into a second, empty sqld (`LIBSQL_MIGRATION_TEST_URL`).
  CI runs both as service containers; locally:
  `sqld --http-listen-addr 127.0.0.1:8080 & LIBSQL_TEST_URL=jdbc:libsql:http://127.0.0.1:8080 mvn test`.
- CI's Docker job runs the compose smoke test twice: local SQLite overlay, and the production
  compose file against a sqld container.

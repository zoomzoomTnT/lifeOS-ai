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

## Cutover to this driver (PR #27)

**No data migration is planned for this cutover.** Production has run on Turso since release
`bf5ce57` (deployed ~21:37 JST, 2026-10-10, DBeaver driver), and `vars.TURSO_DATABASE_URL` is that
**live** `lifeos-ai` database. Deploying this driver only changes how the app talks to the same
database. The legacy `data/life.db` is not needed (zoomzoom, 2026-10-11) and is not imported; CD
just keeps one snapshot of it (below).

### Risk: every statement is gated on `PRAGMA foreign_keys=ON`

The driver sends `PRAGMA foreign_keys=ON` in the same Hrana `/v3/pipeline` batch as each
statement / `BEGIN`, and the statement only runs if the PRAGMA succeeded. The old DBeaver driver
used Turso's *legacy* HTTP endpoint, which rejected PRAGMA writes (PR #26); the Hrana endpoint is
expected to accept them, and local sqld does, but **this is not yet verified on hosted Turso**. If
hosted Turso refuses the PRAGMA (or the conditional batch), **no statement can run and prod will not
start** (health check fails, CD prints the api logs). The driver then logs once, at SEVERE/ERROR:
`libSQL: the libSQL server refused 'PRAGMA foreign_keys=ON' (or the Hrana batch carrying it)...`;
errors read `PRAGMA foreign_keys=ON refused by the server...` or
`libSQL server rejected the whole batch [...]`. Then roll back (below).

### Before deploying

1. **Smoke-test on a throwaway hosted Turso database** (same region/plan as prod):

   ```bash
   turso db create lifeos-smoke
   export LIBSQL_TEST_URL="jdbc:libsql:$(turso db show lifeos-smoke --url | sed 's#^libsql://#https://#')"
   export LIBSQL_TEST_TOKEN="$(turso db tokens create lifeos-smoke)"
   cd app
   mvn -B test -Dtest='LibSqlRealServerTest,RealSqldAppTest'   # driver + whole app (tx, FKs, schema init)
   # optional: the real jar
   TURSO_DATABASE_URL="$LIBSQL_TEST_URL" TURSO_AUTH_TOKEN="$LIBSQL_TEST_TOKEN" OPENCLAW_ENABLED=false \
     LIFE_API_PORT=18787 java -jar target/life-os-app.jar &
   curl -fsS localhost:18787/actuator/health    # "UP"; log shows "Turso: foreign_keys=1"
   kill %1
   turso db destroy lifeos-smoke --yes
   ```
   Both test classes must pass (0 skipped). Do not point these tests at the live database: they
   create and drop tables.

2. **Read-only foreign-key check on the live database.** Writes since `bf5ce57` relied only on
   Turso's default `foreign_keys=1` and were never checked by us; the new driver enforces FKs on
   every statement, so existing orphans would make related writes fail later:

   ```bash
   turso db shell lifeos-ai "PRAGMA foreign_key_check"          # no output = OK
   # or without the CLI (read-only, needs the prod token):
   curl -sS https://lifeos-ai-zoomzoomtnt.aws-ap-northeast-1.turso.io/v2/pipeline \
     -H "Authorization: Bearer $TURSO_AUTH_TOKEN" -H 'Content-Type: application/json' \
     -d '{"requests":[{"type":"execute","stmt":{"sql":"PRAGMA foreign_key_check"}},{"type":"close"}]}'
   # OK when results[0].response.result.rows is []
   ```
   Any rows (table, rowid, parent, fk index) must be fixed or explained before deploying.

### Deploy

Merge into `release/0.1.0` → CD builds `sha-<7-char commit>` and deploys it with
`vars.TURSO_DATABASE_URL`. Watch the first start: `Turso: foreign_keys=1 (enforced by the server)`
and `/actuator/health` UP.

### Rollback

Redeploy the previous image without rebuilding: Actions → **CD** → *Run workflow* on
`release/0.1.0` with `skip_build` = true and `image_tag` = `sha-bf5ce57` (CD tags images
`sha-<first 7 chars of the commit>`; check the tag exists in GHCR / the bf5ce57 CD run).
Or: `gh workflow run cd.yml --ref release/0.1.0 -f skip_build=true -f image_tag=sha-bf5ce57`.

**Caveat:** the old image's DBeaver driver only accepts `jdbc:dbeaver:libsql:...` or `libsql://...`
URLs, not the `jdbc:libsql:https://...` form now in `vars.TURSO_DATABASE_URL`. Before a rollback,
set the repo variable to `jdbc:dbeaver:libsql:https://lifeos-ai-zoomzoomtnt.aws-ap-northeast-1.turso.io`
(this driver accepts that form too, so it can also stay that way permanently). No data step is
needed: both versions use the same database and schema.

### Legacy `data/life.db`

CD no longer makes rotating copies of `data/life.db` on every deploy (the app does not write it
any more). It keeps the file in place and takes one frozen snapshot,
`data/backups/life.db.pre-turso`, the first time it runs: SQLite's online backup API
(`com.lifeos.tools.SqliteBackup`), run inside the new image as root (the files are root-owned from
the container and reading WAL needs write access to `-shm`), then `integrity_check`. If that fails,
it stops the API (a failed stop aborts the deploy before anything changed) and copies the files
cold; if that fails too, the deploy stops with an error and `data/life.db` stays untouched. Live
data is in Turso (point-in-time restore is Turso's).

## Optional tool: copying a SQLite file into a NEW, EMPTY database

Not part of any planned cutover. `SqliteToLibsql` (wrapper `docker/migrate-sqlite-to-turso.sh`,
copied to `~/lifeos/` by CD) copies a Life OS SQLite file into an **empty** libSQL/Turso database,
e.g. a future new database. **Never run it against the live database, and never use
`--confirm-target-counts` there**: that flag deletes the rows it confirms.

```bash
export LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:sha-<commit>     # pinned; :latest is refused
export TURSO_DATABASE_URL='jdbc:libsql:https://<new-empty-db>-<org>.turso.io'
export TURSO_AUTH_TOKEN='...'
./migrate-sqlite-to-turso.sh data/backups/life.db.pre-turso --dry-run   # copy + verify, roll back
./migrate-sqlite-to-turso.sh data/backups/life.db.pre-turso             # for real
```

What it does, in **one** target transaction: create the app schema (`schema.sql`) objects the target
lacks — so an old file without e.g. `merchants` still ends up complete — and any source-only
tables; copy all rows with their ids (multi-row INSERTs; app seed rows of copied tables are
replaced by the source's); carry `sqlite_sequence`; create missing source indexes / views /
triggers; check row counts and `PRAGMA foreign_key_check`; then commit (or roll back on any
problem or `--dry-run`). The source is read in one read transaction (consistent snapshot) and never
modified.

Non-empty target: refused. `--wipe-target` replaces existing rows, and on its own is only allowed
when the target holds nothing but the app's **unchanged** seed rows (owner, settings, model prices,
schema_migrations — what the app writes when it first starts on an empty database), compared by
content (time-default columns such as `created_at` ignored). Request-log rows (`http_requests`, written
on every request incl. health checks) don't block this and are replaced by the source's. Anything
else (new or edited rows) makes the tool print the exact counts and require
`--confirm-target-counts t1=N,...` matching them; those rows are deleted. The check is repeated
under the write lock. The script copies the source file to a temp dir (the original is never
mounted) and refuses to run while `lifeos-api` is up.

Without Docker: `java -cp app/target/life-os-app.jar -Dloader.main=com.lifeos.tools.SqliteToLibsql
org.springframework.boot.loader.launch.PropertiesLauncher <file> [options]`.

Without Docker: `java -cp app/target/life-os-app.jar -Dloader.main=com.lifeos.tools.SqliteToLibsql
org.springframework.boot.loader.launch.PropertiesLauncher <file> [options]`.

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

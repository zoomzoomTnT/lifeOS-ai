# Database: Turso (libSQL)

Production runs on Turso (hosted libSQL) over HTTPS. Local development can use a SQLite file
(`local` profile); same schema and SQL.

## Why a custom driver

`com.lifeos.db.libsql.LibSqlHttpDriver` speaks Turso's Hrana-over-HTTP protocol
(`POST /v3/pipeline`). It replaces the DBeaver libSQL driver, which uses the stateless legacy API
and cannot hold a transaction: commit/rollback were no-ops, so `@Transactional` methods were not
atomic on Turso.

- Auto-commit statement: one request on a fresh stream. Transaction: the first statement opens a
  stream with `BEGIN IMMEDIATE` (`DEFERRED` if read-only), later ones reuse its baton, then
  `COMMIT`/`ROLLBACK`. A server-side rollback (expired stream, lock timeout) or a failed request
  mid-transaction marks it aborted. Later statements and `commit()` throw, and nothing is
  half-committed. `COMMIT` on an expired stream is reported as rolled back (SQLSTATE 40000).
- Every stream starts with `PRAGMA foreign_keys=ON` (per server connection, OFF by default on
  Turso) in one conditional Hrana batch: `BEGIN` / the statement only run if the step before
  succeeded.
- 30 s per-request timeout; `setNetworkTimeout(0)` restores it. Keep transactions short: sqld/Turso
  abort a lock holder after ~5 s if another writer waits.
- Dates as ISO-8601 text with seconds (`Timestamp`/`Instant` in UTC). Generated keys come from
  `last_insert_rowid` on the same stream, and entities use `INSERT ... RETURNING id`. Unsupported
  JDBC features throw instead of silently doing nothing.
- On libSQL, `SchemaInitializer` does not send schema.sql's PRAGMAs (the driver handles
  `foreign_keys`, and `journal_mode` belongs to the server).

## Configuration

| Variable | Notes |
|---|---|
| `TURSO_DATABASE_URL` | Required, **no default**. `jdbc:dbeaver:libsql:https://<db>-<org>.turso.io` (prod's form, also understood by the old driver), `jdbc:libsql:https://...` or `libsql://...`. If unset, startup fails with `No database configured: TURSO_DATABASE_URL is not set`. |
| `TURSO_AUTH_TOKEN` | Required for https / `libsql://` (`turso db tokens create <db>`). If missing, startup fails naming it. Not needed for plain `http://` (local sqld). |

CD takes the URL from repo variable `TURSO_DATABASE_URL` (or a secret), else `~/lifeos/.env`, and
fails before touching the host if neither has one. Local SQLite: `SPRING_PROFILES_ACTIVE=local`,
or `docker compose -f docker-compose.yml -f docker-compose.local.yml up`.

## Hosted Turso: verify before deploying

Every statement depends on hosted Turso accepting `PRAGMA foreign_keys=ON` over `/v3/pipeline`.
Local sqld does, but hosted Turso is not verified yet. If it refuses, nothing can run: the app
doesn't start, and the driver logs one ERROR saying `the libSQL server refused 'PRAGMA foreign_keys=ON'`.
So first smoke-test on a throwaway database:

```bash
turso db create lifeos-smoke
export LIBSQL_TEST_URL="jdbc:libsql:$(turso db show lifeos-smoke --url | sed 's#^libsql://#https://#')"
export LIBSQL_TEST_TOKEN="$(turso db tokens create lifeos-smoke)"
(cd app && mvn -B test -Dtest='LibSqlRealServerTest,RealSqldAppTest')   # must pass, 0 skipped
turso db destroy lifeos-smoke --yes
```

Never point these tests at the live DB: they create and drop tables.

## Rollback

Actions → CD → Run workflow on `release/0.1.0` with `skip_build=true` and
`image_tag=sha-e2e4938` (the last image before API auth; see docs/auth.md#rollback for what CD does
with a pre-auth image), or
`gh workflow run cd.yml --ref release/0.1.0 -f skip_build=true -f image_tag=sha-e2e4938`.
Same database and schema, so no data step is needed, and the `jdbc:dbeaver:libsql:https://...` URL
works for both drivers.

## Tests

`LibSqlConnectionTest` and the Spring/JPA tests use an in-process Hrana server
(`FakeTursoServer`). `LibSqlRealServerTest` and `RealSqldAppTest` need a real sqld via
`LIBSQL_TEST_URL` (CI runs one as a service container). Locally:
`sqld --http-listen-addr 127.0.0.1:8080 & LIBSQL_TEST_URL=jdbc:libsql:http://127.0.0.1:8080 mvn verify`.

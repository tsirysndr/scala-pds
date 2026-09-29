# Storage backends

One schema serves both backends. Setting any `PDS_DATABASE_*` variable selects
PostgreSQL; with none set, everything lives in a single SQLite file.

## SQLite, the zero-configuration default

```sh
mise exec -- sbt run     # data/scala-pds.sqlite3, data/master.key
```

SQLite is chosen when no `PDS_DATABASE_*` variable is present. The file path is
`PDS_SQLITE_PATH`, default `data/scala-pds.sqlite3`; its parent directory is
created at startup. Connections enable foreign keys, WAL journalling, `NORMAL`
synchronous mode and a five-second busy timeout, and the pool holds **one**
connection, because SQLite serializes writers anyway.

It suits a single host and a handful of accounts. It is not suitable when more
than one instance must serve the same data, because the advisory locking that
guards migrations, and the row locking that serializes account mutations, exist
only on PostgreSQL.

## PostgreSQL

```sh
export PDS_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/pds
export PDS_DATABASE_USER=pds
export PDS_DATABASE_PASSWORD="…"
export PDS_MASTER_KEY="…"
```

All three of URL, user and password are required together: setting one alone is a
startup error rather than a silent fallback to SQLite. `PDS_DATABASE_POOL_SIZE`
(default 10, range 1–100) bounds the HikariCP pool. A master key must be set
explicitly — PostgreSQL deployments do not get the generated file.

PostgreSQL 17 or newer is recommended; nothing newer than PostgreSQL 12 syntax is
used.

## What the dialect layer does

Three placeholders and one clause differ:

| Placeholder  | PostgreSQL              | SQLite                              |
| ------------ | ----------------------- | ----------------------------------- |
| `{{BLOB}}`   | `bytea`                 | `blob`                              |
| `{{JSON}}`   | `jsonb`                 | `text`                              |
| `{{ID_PK}}`  | `bigserial PRIMARY KEY` | `integer PRIMARY KEY AUTOINCREMENT` |
| `FOR UPDATE` | emitted                 | omitted                             |

Everything else is deliberately identical. Timestamps are `bigint` epoch
milliseconds and UUIDs are `text` everywhere, which removes every timezone and
type-mapping difference between the two backends and makes ordering trivial.

## Migrations

The schema is a numbered sequence under
[`src/main/resources/migrations`](../src/main/resources/migrations) — the
baseline, blob backends, passkeys and account recovery. Startup:

1. takes a PostgreSQL advisory lock, so concurrent instances cannot race;
2. creates `schema_migrations` if needed;
3. compares each migration's SHA-256 against the recorded checksum;
4. applies anything unapplied, inside the same transaction;
5. records version, checksum and timestamp.

An applied migration whose contents changed aborts startup with “Migration …
changed after it was applied”. Add a new file rather than editing an old one.

## Tables

| Group          | Tables                                                                                                                                                                         |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Accounts       | `accounts`, `account_keys`, `handle_reservations`, `account_imports`, `account_recoveries`                                                                                     |
| Authentication | `sessions`, `app_passwords`, `account_tokens`, `browser_sessions`, `account_totp`, `account_recovery_codes`, `account_webauthn_users`, `account_passkeys`, `webauthn_requests` |
| Invites        | `invite_codes`, `invite_uses`                                                                                                                                                  |
| Repositories   | `repo_roots`, `repo_blocks`, `records`, `record_blobs`, `blobs`, `blob_deletions`                                                                                              |
| Federation     | `repo_events`                                                                                                                                                                  |
| OAuth          | `oauth_requests`, `oauth_interactions`, `oauth_codes`, `oauth_tokens`, `oauth_replay`                                                                                          |
| Services       | `account_preferences`, `identity_cache`, `email_outbox`, `plc_operations`, `service_token_replay`                                                                              |

Constraints do real work: account status is a `CHECK`ed enumeration, email token
purposes are constrained, blob and block ownership cascades from the account, and
a browser session must have a security epoch exactly when it has a DID. The test
suite asserts that a foreign key violation and an invalid status are both
rejected by the database, not only by the application.

## Where blobs live

By default blob bytes are rows in the database, which keeps a small deployment
to a single file to back up. Setting `PDS_S3_BUCKET` moves them to an
S3-compatible bucket instead, and the backend is recorded per blob — so a server
that switches later keeps serving what it already stored. See
[blobs](/blobs/).

That choice changes what a backup covers: with a bucket, the database no longer
contains the bytes, so the bucket needs backing up too. See [backup and
restore](/backup/).

## Growth and housekeeping

Repository blocks accumulate as records are rewritten, and firehose events
accumulate as they are emitted. Both are bounded:

- events older than `PDS_FIREHOSE_RETENTION_HOURS` are dropped;
- blocks that are neither reachable from the current commit nor needed by a
  retained event are reclaimed.

See [repositories](/repositories/) for the collection rules and
[firehose](/firehose/) for the window.

A background sweep runs every thirty seconds. It delivers queued email, drains
queued blob deletions, expires events, collects blocks for a bounded number of
accounts, and deletes expired browser sessions, email tokens, OAuth requests,
interactions, codes and replay records, stale identity cache entries, expired
sessions, and accounts past their `delete_after`.

# Backup and restore

Three things have to survive a loss, and they are deliberately separate:

1. the **database** — accounts, repositories, blobs, sessions;
2. the **master key** — without it every sealed secret is unreadable;
3. the **configuration** — so a restore reproduces the same public URL and
   handle domain.

A backup that carries both the database and the key that opens it protects
nothing against whoever takes the backup. Store them apart.

## SQLite

SQLite can copy itself consistently while the server is running, so a backup
needs no downtime:

```sh
java -jar scala-pds.jar backup /srv/backups/pds-$(date +%F).sqlite3
```

```
scala-pds: wrote /srv/backups/pds-2026-09-29.sqlite3 (356352 bytes)
scala-pds: sha256 81c959e3227c0cb226c962437c0251dddaf4942f0d5d5b46ef23d62025032e9d
scala-pds: 1 account(s), 0 record(s), 0 blob(s)
scala-pds: back up the master key separately; it is not in this file
```

It writes three files: the copy, a `.sha256` sidecar in `sha256sum` format, and
a `.manifest.json` recording the size, checksum, schema version and row counts.
The copy is written to a `.partial` file and moved into place only once it has
been read back, so an interrupted run never leaves a half-file where a backup
should be.

Do not copy a live SQLite file with `cp`: the write-ahead log lives beside it
and a plain copy can catch a torn state.

## Verifying

A backup nobody has read is a guess. Check one:

```sh
java -jar scala-pds.jar verify-backup /srv/backups/pds-2026-09-29.sqlite3
```

```
scala-pds: /srv/backups/pds-2026-09-29.sqlite3 is readable, 4 migration(s) applied
scala-pds: sha256 81c959e3227c0cb226c962437c0251dddaf4942f0d5d5b46ef23d62025032e9d
  account_keys 1
  accounts 1
  records 0
  repo_blocks 2
  repo_events 3
```

It recomputes the checksum and compares it with the sidecar, opens the file
**read-only** so inspection cannot change the bytes it just checksummed, and
reports the schema version and row counts. A corrupted file, a file that is not
a scala-pds database, and a file that does not match its recorded checksum are
all refused.

## PostgreSQL

Use `pg_dump`; it snapshots consistently and restores with `pg_restore`. The
`backup` command says so rather than producing something weaker.

```sh
pg_dump --format=custom --file=pds-$(date +%F).dump "$PDS_DATABASE_URL"
pg_restore --clean --if-exists --dbname="$PDS_DATABASE_URL" pds-2026-09-29.dump
```

## Blobs

With `PDS_S3_BUCKET` configured, blob bytes are in the bucket rather than the
database, so the database backup does **not** contain them. Back the bucket up
too, or enable its own versioning and replication. Without a bucket, blobs are
in the database and a database backup is complete. See [blobs](/blobs/).

## The recovery drill

Restoring is only proven by doing it:

1. Copy the backup and the master key to a fresh host.
2. Start the server with the same `PDS_PUBLIC_URL` and `PDS_USER_DOMAIN`, and
   `PDS_MASTER_KEY` set to the backed-up key.
3. Confirm the sealed secrets open:
   ```sh
   java -jar scala-pds.jar verify-master-key
   ```
4. Confirm a repository still verifies, which proves the signing keys survived:
   ```sh
   curl -sS "http://127.0.0.1:3000/xrpc/com.atproto.sync.getRepo?did=$DID" -o repo.car
   ```
5. Sign in as a test account.

A restore with the wrong master key fails at step 3, with the account rows
intact but their signing keys unreadable — which is exactly why the key is
backed up separately and tested.

## What a restore does not bring back

- **Sessions and OAuth tokens** survive in the database, but tokens are derived
  from the master key, so a restore with a *different* key invalidates them all.
- **Firehose consumers** past the [retention
  window](/firehose/) resynchronise with `getRepo` rather than by cursor.
- **The `data/master.key` file** is not in the backup. If you relied on the
  generated key rather than `PDS_MASTER_KEY`, copy that file separately.

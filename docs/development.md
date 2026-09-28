# Development

## The test suite

```sh
mise exec -- sbt test
```

The suite is hermetic: every test that needs storage creates a temporary SQLite
file, and the HTTP client is a scripted in-process stub, so nothing reaches the
network. It covers:

- **Protocol conformance** against the vendored
  [atproto-interop-tests](https://github.com/bluesky-social/atproto-interop-tests)
  fixtures: identifier syntax, the data model, DAG-CBOR bytes and CIDs, MST key
  heights and prefixes, and the W3C `did:key` and signature vectors.
- **Non-canonical input rejection** — indefinite lengths, overlong integers,
  floats, unsorted map keys, high-S signatures, tampered CAR blocks.
- **Merkle search tree behaviour** — order independence of the root CID,
  interleaved writes and deletes against a reference map, reload from blocks.
- **End-to-end HTTP flows** — registration, sessions, refresh rotation and
  replay, app password privileges, takedowns, records, compare-and-swap,
  `applyWrites` atomicity, blobs, CAR export verification, and the whole OAuth
  authorization code flow including DPoP binding and revocation.

## The account interface

```sh
cd frontend
npm install
npm test          # vitest + Testing Library
npm run check     # tsc --noEmit
npm run build     # writes src/main/resources/ui/{app.js,style.css}
```

`npm run dev` serves the interface on Vite's port and proxies `/account`,
`/oauth` and `/xrpc` to a PDS on `http://127.0.0.1:3000`, rewriting `Origin` so
the backend's same-origin checks still pass.

The build output is committed so the Scala build and the Docker image need no
Node toolchain. Rebuild and commit it whenever the interface changes.

## PostgreSQL

The default suite runs on SQLite. To exercise the PostgreSQL dialect, start a
server and point the variables at it:

```sh
docker compose up -d postgres
export PDS_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/pds
export PDS_DATABASE_USER=pds
export PDS_DATABASE_PASSWORD="$PDS_DATABASE_PASSWORD"
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
mise exec -- sbt run
```

Both backends share one schema. The dialect layer substitutes three
placeholders — `{{BLOB}}`, `{{JSON}}` and `{{ID_PK}}` — and decides whether
`SELECT … FOR UPDATE` is emitted; everything else, including
epoch-millisecond timestamps and text UUIDs, is identical.

## Layout

| Path | Contents |
| --- | --- |
| `src/main/scala/pds/crypto` | encodings, hashing, scrypt, EC keys, JWT, AES-GCM sealing |
| `src/main/scala/pds/protocol` | syntax, TID, DAG-CBOR, CID, MST, CAR, commits |
| `src/main/scala/pds/storage` | configuration, dialect, pool, SQL helpers, migrations |
| `src/main/scala/pds/identity` | DID documents, `did:plc`, resolution, outbound HTTP |
| `src/main/scala/pds/accounts` | registration, sessions, tokens, app passwords, invites, email |
| `src/main/scala/pds/repo` | repository persistence and commit application |
| `src/main/scala/pds/firehose` | the event sequence and `subscribeRepos` |
| `src/main/scala/pds/oauth` | scopes, DPoP, clients, PAR, interactions, tokens |
| `src/main/scala/pds/security` | browser sessions, TOTP, the account interface routes |
| `src/main/scala/pds/api` | XRPC endpoints, the proxy, rate limiting |
| `frontend` | the React account and consent interface |
| `docs` | these pages, which are also the published site |

## This documentation

```sh
cd docs
deno task serve    # preview on http://localhost:3000
deno task build    # writes _site/
deno task deploy   # publishes to Deno Deploy
```

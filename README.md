# scala-pds

An AT Protocol Personal Data Server in Scala 3, with PostgreSQL persistence and
a zero-configuration SQLite fallback.
**In development: not yet a fully federating PDS.**

Implemented: hosted `did:plc`/`did:web` accounts, sessions and app passwords,
email security flows, TOTP and WebAuthn passkeys, OAuth with DPoP and scoped
consent, signed repositories with Lexicon-validated records and verified CAR
import and export, content-addressed blobs in the database or an S3-compatible
bucket, a WebSocket firehose with bounded retention and block collection, an
authenticated service proxy, account migration end to end, and
administrative/moderation APIs — 71 XRPC methods, plus a React account and
consent interface, Prometheus metrics and host commands for backups, key
rotation, account recovery, migration and identity reconciliation. See the [compatibility
matrix](docs/COMPATIBILITY.md) for exact coverage and the
[roadmap](docs/ROADMAP.md) for what remains.

## Quick start

```sh
docker run --rm -p 3000:3000 ghcr.io/tsirysndr/scala-pds:latest
```

Or from source:

```sh
mise trust && mise install

# Zero configuration: stores everything in data/scala-pds.sqlite3 and
# generates data/master.key on first start.
mise exec -- sbt run

curl http://127.0.0.1:3000/xrpc/_health
curl http://127.0.0.1:3000/xrpc/com.atproto.server.describeServer
```

Open <http://127.0.0.1:3000/account> for the account interface.

For PostgreSQL (recommended beyond a single small host), set `PDS_MASTER_KEY`
and the `PDS_DATABASE_*` variables; any of them switches the backend.
`docker compose up --build` runs both together.

Run the tests with `mise exec -- sbt test`; see
[development](docs/development.md) for the frontend suite, the PostgreSQL matrix
and the project layout.

## Documentation

Browse these pages as a website at <https://scala-pds.tsirysndr.deno.net/>
(`docs/` doubles as a Lume static site; see [docs/README.md](docs/README.md)).

### Setup and operations

- [Get started](docs/get-started.md) — install, run, create an account, watch the firehose
- [Installation](docs/installation.md) — source, Docker image, Nix flake
- [Configuration](docs/configuration.md) — every environment variable
- [Deployment](docs/DEPLOYMENT.md) — public hostname, TLS, systemd, backups
- [Storage backends](docs/STORAGE.md) — SQLite, PostgreSQL, the dialect layer, migrations
- [Master key](docs/MASTER-KEY.md) — what it seals and how to handle it
- [Backup and restore](docs/BACKUP.md) — checksummed snapshots and the recovery drill
- [Admin and invites](docs/ADMIN.md) — administrative APIs and invite codes
- [Moderation](docs/MODERATION.md) — account, record and blob takedowns
- [Email delivery](docs/EMAIL.md) — the outbox contract
- [Development](docs/development.md) — test suites, frontend, layout

### Identity and accounts

- [Hosted identities](docs/IDENTITY.md) — DID methods, resolution, handle changes
- [Sessions and lifecycle](docs/ACCOUNTS.md) — registration, tokens, app passwords, email flows
- [Account security](docs/ACCOUNT-SECURITY.md) — browser sessions, TOTP, CSRF
- [OAuth](docs/OAUTH.md) — authorization server, DPoP, scoped consent
- [Account interface](docs/ACCOUNT-UI.md) — the React application and its contract

### Data and federation

- [Repositories](docs/REPOSITORIES.md) — the Merkle search tree, commits, CAR
- [Blobs](docs/BLOBS.md) — upload, reference checks, download
- [Firehose](docs/FIREHOSE.md) — the event sequence and `subscribeRepos`
- [Account migration](docs/MIGRATION.md) — importing a repository and switching identity
- [Service proxy](docs/PROXY.md) — authenticated proxying to AppViews and labelers
- [Preferences](docs/PREFERENCES.md) — private Bluesky preferences

### Status

- [Compatibility matrix](docs/COMPATIBILITY.md) — implemented routes and verification evidence
- [Interoperability](docs/INTEROP.md) — what the reference implementation verifies
- [Current limits](docs/LIMITS.md) — validation, repository, token and rate-limit bounds
- [Roadmap](docs/ROADMAP.md) — what is done and what is next

## Protocol references

- [AT Protocol specifications](https://atproto.com/specs/atp)
- [XRPC HTTP API](https://atproto.com/specs/xrpc)
- [Official lexicons and reference implementation](https://github.com/bluesky-social/atproto)

The specifications and official lexicons define compatibility; the TypeScript
implementation is a reference, not a runtime dependency.

## License

[MIT](LICENSE). Vendored conformance fixtures retain their upstream CC0 license.

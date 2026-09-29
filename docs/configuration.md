# Configuration

Every setting comes from the environment and is validated before the listener
binds, so a typo fails at startup rather than at the first request. Nothing has
to be set for a development server.

## Server

| Variable                       | Default        | Meaning                                                                                          |
| ------------------------------ | -------------- | ------------------------------------------------------------------------------------------------ |
| `PDS_HOST`                     | `127.0.0.1`    | bind address                                                                                     |
| `PDS_PORT`                     | `3000`         | HTTP port, 1–65535                                                                               |
| `PDS_HOSTNAME`                 | `localhost`    | public DNS hostname, without scheme, port or path                                                |
| `PDS_PUBLIC_URL`               | derived        | canonical origin clients see; `http://<host>:<port>` on loopback, `https://<hostname>` otherwise |
| `PDS_USER_DOMAIN`              | `PDS_HOSTNAME` | the suffix account handles must end with                                                         |
| `PDS_RATE_LIMIT_PER_MINUTE`    | `300`          | requests per client address per minute                                                           |
| `PDS_FIREHOSE_RETENTION_HOURS` | `72`           | how far back `subscribeRepos` can rewind; `0` keeps everything and disables block collection     |
| `PDS_ACCESS_LOG`               | `true`         | one structured log line per request                                                              |

`PDS_PUBLIC_URL` must be a canonical origin: a lowercase host, no path, and no
default port. `https://pds.example.com` and `https://pds.example.com:8443` are
accepted; `https://PDS.example.com`, `https://pds.example.com/` and
`https://pds.example.com:443` are refused. Plain HTTP is allowed only for
`localhost` and `127.0.0.1`.

The service DID is derived as `did:web:<PDS_HOSTNAME>` and its document is
served at `/.well-known/did.json`.

## Identity

| Variable            | Default                     | Meaning                                      |
| ------------------- | --------------------------- | -------------------------------------------- |
| `PDS_DID_METHOD`    | `plc`, or `web` on loopback | the DID method new accounts get              |
| `PDS_PLC_DIRECTORY` | `https://plc.directory`     | directory that receives `did:plc` operations |

A loopback server defaults to `did:web` because it cannot publish to a public
directory. See [hosted identities](/identity/).

## Signup policy

| Variable              | Default | Meaning                                      |
| --------------------- | ------- | -------------------------------------------- |
| `PDS_SIGNUP_ENABLED`  | `true`  | whether `createAccount` is accepted at all   |
| `PDS_INVITE_REQUIRED` | `false` | whether registration consumes an invite code |

## Secrets

| Variable             | Default                 | Meaning                                                    |
| -------------------- | ----------------------- | ---------------------------------------------------------- |
| `PDS_MASTER_KEY`     | generated beside SQLite | 32 bytes of unpadded base64url sealing every stored secret |
| `PDS_ADMIN_PASSWORD` | unset                   | enables the administrative APIs; at least 12 characters    |

Generate a master key once and keep it:

```sh
openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
```

Without `PDS_MASTER_KEY`, a SQLite server writes one to `master.key` beside the
database and reuses it across restarts. PostgreSQL deployments must set it
explicitly. See [the master key](/master-key/).

## Storage

| Variable                 | Default                  | Meaning                                                 |
| ------------------------ | ------------------------ | ------------------------------------------------------- |
| `PDS_SQLITE_PATH`        | `data/scala-pds.sqlite3` | single-file database when no PostgreSQL variable is set |
| `PDS_DATABASE_URL`       | unset                    | `jdbc:postgresql://host:port/database`                  |
| `PDS_DATABASE_USER`      | unset                    | PostgreSQL role                                         |
| `PDS_DATABASE_PASSWORD`  | unset                    | PostgreSQL password                                     |
| `PDS_DATABASE_POOL_SIZE` | `10`                     | pooled connections, 1–100                               |

Setting *any* `PDS_DATABASE_*` variable selects PostgreSQL and then requires the
URL, user and password together. See [storage backends](/storage/).

## Shared counters

| Variable              | Default | Meaning                                                                              |
| --------------------- | ------- | ------------------------------------------------------------------------------------ |
| `PDS_REDIS_URL`       | unset   | `redis://` or `rediss://` URL; setting it shares rate-limit windows across instances |
| `PDS_REDIS_POOL_SIZE` | `8`     | pooled connections, 1–100                                                            |

Without it, each instance counts on its own. See [deployment](/deployment/).

## Services

| Variable               | Default   | Meaning                                                                                           |
| ---------------------- | --------- | ------------------------------------------------------------------------------------------------- |
| `PDS_APPVIEW_URL`      | unset     | AppView origin for proxied `app.bsky.*` queries                                                   |
| `PDS_APPVIEW_DID`      | unset     | AppView DID, the audience of proxy service tokens                                                 |
| `PDS_MOD_SERVICE_DID`  | unset     | moderation service DID: the audience of reports, and allowed to act on the administrative methods |
| `PDS_MOD_SERVICE_URL`  | unset     | moderation service origin, where reports are sent                                                 |
| `PDS_HANDLE_AUTHORITY` | unset     | server that owns this handle domain, asked before allocating a name                               |
| `PDS_RESERVED_HANDLES` | unset     | extra handle labels nobody may register, added to the built-in set                                |
| `PDS_RELAY_URLS`       | unset     | comma-separated relays asked to crawl this host at startup                                        |
| `PDS_BLOB_MAX_SIZE`    | `5242880` | largest accepted blob in bytes, 1 KiB–100 MiB                                                     |

## Blob storage

| Variable                   | Default                             | Meaning                                             |
| -------------------------- | ----------------------------------- | --------------------------------------------------- |
| `PDS_S3_BUCKET`            | unset                               | bucket for blobs; setting it selects the S3 backend |
| `PDS_S3_REGION`            | unset                               | signing region, required with a bucket              |
| `PDS_S3_ENDPOINT`          | `https://s3.<region>.amazonaws.com` | endpoint URL                                        |
| `PDS_S3_ACCESS_KEY_ID`     | unset                               | access key, required with a bucket                  |
| `PDS_S3_SECRET_ACCESS_KEY` | unset                               | secret key, required with a bucket                  |
| `PDS_S3_PATH_STYLE`        | `true`                              | `false` selects virtual-host addressing             |
| `PDS_S3_PREFIX`            | `blobs/`                            | key space this server owns in the bucket            |

With no bucket configured, blob bytes stay in the database. See [blobs](/blobs/).

## Email

| Variable             | Default              | Meaning                                    |
| -------------------- | -------------------- | ------------------------------------------ |
| `PDS_EMAIL_ENDPOINT` | unset                | HTTPS endpoint that delivers outbound mail |
| `PDS_EMAIL_TOKEN`    | unset                | bearer token sent to that endpoint         |
| `PDS_EMAIL_FROM`     | `noreply@<hostname>` | sender address passed to the endpoint      |

With no endpoint configured the flows that need mail refuse with
`EmailUnavailable` rather than silently succeeding. See [email
delivery](/email/).

## Server description

| Variable                   | Default | Meaning                       |
| -------------------------- | ------- | ----------------------------- |
| `PDS_CONTACT_EMAIL`        | unset   | published in `describeServer` |
| `PDS_PRIVACY_POLICY_URL`   | unset   | published in `describeServer` |
| `PDS_TERMS_OF_SERVICE_URL` | unset   | published in `describeServer` |

## Development escape hatch

| Variable                    | Default          | Meaning                                          |
| --------------------------- | ---------------- | ------------------------------------------------ |
| `PDS_ALLOW_PRIVATE_NETWORK` | `false` on HTTPS | allows outbound fetches to private address space |

Outbound requests for handle documents, DID documents and OAuth client metadata
resolve their host first and refuse loopback, link-local and private ranges.
Loopback servers allow them so a local AppView or client can be used; a public
server must opt in deliberately.

## Migrations

The schema is a numbered, checksummed sequence under
[`src/main/resources/migrations`](../src/main/resources/migrations). Startup
applies anything unapplied inside one transaction, under a PostgreSQL advisory
lock, before binding the port. A migration whose contents changed after it was
applied aborts startup instead of diverging between hosts.

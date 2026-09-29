# Deployment

A public server needs four things the development default does not: a real
hostname behind TLS, a durable master key, PostgreSQL, and an administrator
password.

## Checklist

```sh
# Generate once. Losing this key makes every stored signing key unreadable.
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
export PDS_ADMIN_PASSWORD="$(openssl rand -hex 24)"

export PDS_HOSTNAME=pds.example.com
export PDS_PUBLIC_URL=https://pds.example.com
export PDS_HOST=0.0.0.0

export PDS_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/pds
export PDS_DATABASE_USER=pds
export PDS_DATABASE_PASSWORD="…"

export PDS_APPVIEW_URL=https://api.bsky.app
export PDS_APPVIEW_DID=did:web:api.bsky.app
export PDS_RELAY_URLS=https://bsky.network
```

`PDS_PUBLIC_URL` is the OAuth issuer and the DPoP audience, so it must be the
exact origin clients reach: lowercase host, no path, no default port. Getting it
wrong does not fail quietly — the server refuses to start.

## TLS and the reverse proxy

Terminate TLS in front of the server and forward to `PDS_PORT`. The proxy must:

- preserve the `Host` header, so the public URL and the request agree;
- set `X-Forwarded-For`, which is what rate limiting keys on;
- support WebSocket upgrades on `/xrpc/com.atproto.sync.subscribeRepos`;
- not buffer that endpoint, or the firehose will stall.

A minimal Caddy configuration:

```
pds.example.com {
	reverse_proxy 127.0.0.1:3000
}
```

Wildcard TLS for `*.pds.example.com` is needed only if account handles are served
as subdomains of the PDS itself.

## Docker Compose

[`compose.yaml`](../compose.yaml) runs the server and PostgreSQL together:

```sh
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
export PDS_DATABASE_PASSWORD="$(openssl rand -hex 16)"
export PDS_HOSTNAME=pds.example.com
export PDS_PUBLIC_URL=https://pds.example.com
docker compose up -d --build
```

Both variables are required — the file refuses to start without them rather than
defaulting to something guessable.

## systemd

```ini
[Unit]
Description=scala-pds
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
User=pds
WorkingDirectory=/var/lib/pds
EnvironmentFile=/etc/pds/pds.env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -jar /opt/pds/scala-pds.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/pds

[Install]
WantedBy=multi-user.target
```

Keep `/etc/pds/pds.env` at mode 600: it holds the master key.

## Startup order

Migrations run inside one transaction, under a PostgreSQL advisory lock, *before*
the port is bound. Several instances can start at once; one applies the schema
and the others wait. A migration whose contents changed after it was applied
aborts startup rather than diverging between hosts, so a rolling deploy either
has the schema it expects or does not serve.

## Health and monitoring

| Path | Meaning |
| --- | --- |
| `/xrpc/_health` | `{"version":"scala-pds 0.1.0-SNAPSHOT"}`, after a successful database round trip |
| `/_health` | the same version, without touching the database |

`/xrpc/_health` returns 500 when the database is unreachable, which is the right
signal for a load balancer. The container image health-checks it every thirty
seconds.

## Rate limiting

`PDS_RATE_LIMIT_PER_MINUTE` (300 by default) is a per-address fixed window, keyed
on `X-Forwarded-For` when present. Exceeding it returns `RateLimitExceeded` with
`Retry-After: 60`. Counters are in-process: several instances each enforce their
own share, so divide the intended total by the instance count.

## Backups

Back up three things:

1. the **master key** — without it, stored signing keys and TOTP secrets are
   unreadable and accounts cannot be recovered;
2. the **database** — `pg_dump` for PostgreSQL, or the SQLite file plus its `-wal`
   sidecar taken with `sqlite3 … ".backup"` rather than a plain copy;
3. the **configuration**, so a restore reproduces the same public URL and handle
   domain.

Test a restore: bring a copy up with the same master key and public URL, then
confirm `com.atproto.sync.getRepo` still verifies for an account.

## Resource notes

Blobs and repository blocks both live in the database, and blocks are
append-only, so storage grows with history. Budget accordingly and watch the
`repo_blocks` and `blobs` tables.

# Get started

The fastest path to a running server needs no configuration at all: the PDS
creates a SQLite database and a master key beside it, applies its migrations
and starts listening on loopback.

## Prerequisites

[mise](https://mise.jdx.dev) pins the exact Temurin JDK 21 build in
`mise.toml`, and `sbt` drives the build.

```sh
mise trust && mise install
```

## Run

```sh
mise exec -- sbt run
```

The first start prints where it put the master key and the database:

```
scala-pds: using the master key at data/master.key; back it up and set PDS_MASTER_KEY in production
scala-pds: Sqlite storage, 1 migration(s) applied
scala-pds: listening on 127.0.0.1:3000 as http://localhost:3000
```

Confirm it answers:

```sh
curl http://127.0.0.1:3000/xrpc/_health
curl http://127.0.0.1:3000/xrpc/com.atproto.server.describeServer
```

## Create an account and write a record

A loopback server defaults to `did:web` identities, so nothing is published to
a public directory while you are developing.

```sh
ACCOUNT=$(curl -sS -X POST http://127.0.0.1:3000/xrpc/com.atproto.server.createAccount \
  -H 'content-type: application/json' \
  -d '{"handle":"alice.localhost","email":"alice@example.com","password":"correct horse battery"}')

TOKEN=$(printf '%s' "$ACCOUNT" | jq -r .accessJwt)
DID=$(printf '%s' "$ACCOUNT" | jq -r .did)

curl -sS -X POST http://127.0.0.1:3000/xrpc/com.atproto.repo.createRecord \
  -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d "{\"repo\":\"$DID\",\"collection\":\"app.bsky.feed.post\",
       \"record\":{\"\$type\":\"app.bsky.feed.post\",\"text\":\"hello\",
       \"createdAt\":\"2026-01-01T00:00:00.000Z\"}}"

curl -sS "http://127.0.0.1:3000/xrpc/com.atproto.repo.listRecords?repo=$DID&collection=app.bsky.feed.post"
```

The repository is a real signed repository from the first commit: export it as
a CAR archive and the commit verifies against the account's signing key.

```sh
curl -sS "http://127.0.0.1:3000/xrpc/com.atproto.sync.getRepo?did=$DID" -o repo.car
```

## Watch the firehose

```sh
curl --include --no-buffer \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Sec-WebSocket-Version: 13' \
  "http://127.0.0.1:3000/xrpc/com.atproto.sync.subscribeRepos?cursor=0"
```

Frames are DAG-CBOR: a header envelope naming the message type, followed by the
body. See [the firehose](/firehose/).

## The account interface

Open <http://127.0.0.1:3000/account> to sign in, manage app passwords, enrol an
authenticator and review which applications have access. The same interface
renders the OAuth consent screen. See [the account
interface](/account-ui/).

## Next

- [Installation](/installation/) — the Docker image, the Nix flake, building
  from source.
- [Configuration](/configuration/) — every environment variable.
- [Deployment](/deployment/) — running a public server with a real hostname,
  PostgreSQL and a durable master key.

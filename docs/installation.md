# Installation

## From source

```sh
mise trust && mise install
mise exec -- sbt test        # the full suite, SQLite only, no network
mise exec -- sbt run
```

`mise.toml` pins the exact Temurin JDK 21 build. `sbt assembly` produces a
single runnable jar at `target/scala-3.3.7/scala-pds.jar`:

```sh
mise exec -- sbt assembly
java -jar target/scala-3.3.7/scala-pds.jar
```

## Docker

The image builds the assembly in one stage and runs it from a JRE in the next.
The account interface is committed to the repository, so no Node toolchain is
needed to build the image.

```sh
docker build -t scala-pds .

docker run --rm -p 3000:3000 \
  -v scala-pds-data:/data \
  -e PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')" \
  scala-pds
```

The container stores its SQLite database under `/data`, runs as an unprivileged
user, and has a health check on `/xrpc/_health`. Point `PDS_DATABASE_*` at
PostgreSQL to switch backends; [compose.yaml](../compose.yaml) wires both
together.

```sh
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
export PDS_DATABASE_PASSWORD="$(openssl rand -hex 16)"
docker compose up --build
```

## Nix flake

```sh
nix run github:tsirysndr/scala-pds        # runs the server
nix develop github:tsirysndr/scala-pds    # JDK, sbt, Node, PostgreSQL, Deno
```

The package runs the PDS from the store source. Maven dependencies are fetched
on first start into `$XDG_CACHE_HOME/scala-pds` — that one start needs network
access — and are not vendored into the store. Because the source is read-only,
the zero-configuration database and master key are written to the directory you
invoke it from, under `data/`.

## Toolchain summary

| Tool | Version | Used for |
| --- | --- | --- |
| Temurin JDK | 21 (pinned in `mise.toml`) | running the server and tests |
| sbt | 1.10.7 | build, tests, assembly |
| Node | 24 | rebuilding the account interface |
| Deno | 2 | building and deploying this documentation site |
| PostgreSQL | 17 or newer | the durable backend beyond a single host |

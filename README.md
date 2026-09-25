# scala-pds

An AT Protocol Personal Data Server, implemented incrementally in Scala 3.
This is an early implementation, not yet a functioning federated PDS.

See [the implementation roadmap](docs/roadmap.md) for scope and sequencing.
Each commit represents one reviewable step, with tests alongside behavior.

## Development

The exact Temurin JDK 21 version is pinned in `mise.toml`. With mise and sbt installed:

```sh
mise install
mise exec -- sbt test
```

Configuration is read from the environment and validated at startup:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PDS_HOST` | `127.0.0.1` | HTTP bind address |
| `PDS_PORT` | `3000` | HTTP port, 1–65535 |
| `PDS_HOSTNAME` | `localhost` | Public DNS hostname, without scheme, port or path |

The service DID is derived as `did:web:<PDS_HOSTNAME>`. The localhost default
is for development only; public DID document hosting arrives with identity support.

## Protocol references

- [AT Protocol specifications](https://atproto.com/specs/atp)
- [XRPC HTTP API](https://atproto.com/specs/xrpc)
- [Official lexicons and reference implementation](https://github.com/bluesky-social/atproto)

The specifications and official lexicons define compatibility; the TypeScript
implementation is a reference, not a runtime dependency.

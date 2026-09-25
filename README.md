# scala-pds

An AT Protocol Personal Data Server, implemented incrementally in Scala 3.
This is an early implementation, not yet a functioning federated PDS.

See [the implementation roadmap](docs/roadmap.md) for scope and sequencing.
Each commit represents one reviewable step, with tests alongside behavior.

## Development

Requires JDK 21+ and sbt. Run `sbt test` to verify the project.

## Protocol references

- [AT Protocol specifications](https://atproto.com/specs/atp)
- [XRPC HTTP API](https://atproto.com/specs/xrpc)
- [Official lexicons and reference implementation](https://github.com/bluesky-social/atproto)

The specifications and official lexicons define compatibility; the TypeScript
implementation is a reference, not a runtime dependency.

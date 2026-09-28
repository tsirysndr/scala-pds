# Roadmap

## Approach

Build vertical slices with explicit wire contracts, failure cases and tests.
Keep every commit buildable. Commit tests with the behaviour they verify. Do not
claim interoperability until it is checked against independent AT Protocol tools.

Scala 3, Cats Effect for resource lifecycles, http4s for HTTP, Circe for JSON.
One sbt module, with protocol, HTTP and persistence concerns in separate
packages. PostgreSQL for durable storage, with a zero-configuration SQLite
fallback, a pooled connection lifecycle and checksummed migrations.

## Done

1. **Server foundation** — reproducible build, validated configuration, health
   endpoints, `describeServer`, and the XRPC error envelope.
2. **Protocol primitives** — identifier syntax, TIDs, canonical DAG-CBOR, CIDv1,
   CAR archives, the Merkle search tree, and ECDSA over secp256k1 and P-256, all
   checked against the upstream interoperability fixtures.
3. **Durable storage** — one schema for two backends, checksummed migrations
   under an advisory lock, transactions, block and blob storage, constraint
   coverage.
4. **Identity and accounts** — `did:plc` genesis and updates, `did:web`
   documents, DNS and HTTPS handle resolution with a bounded cache, account
   provisioning and lifecycle.
5. **Authentication** — scrypt passwords, session tokens with epoch-based
   revocation, refresh rotation, app passwords, service authentication, TOTP, and
   OAuth with DPoP, PAR and PKCE.
6. **Repositories** — signed commits, atomic writes, compare-and-swap, batched
   `applyWrites`, listing, CAR export and verified import.
7. **Blobs** — content-addressed upload and download, reference checks, takedowns.
8. **Federation** — a durable event sequence, `subscribeRepos` over WebSocket,
   CAR export, relay crawl requests.
9. **Service integration** — an authenticated streaming proxy to AppViews and
   labelers, private preferences, administrative and moderation APIs.
10. **Operations** — the account interface, invite codes, email flows, rate
    limits, SSRF defences, the container image and the Nix flake.

## Next

### Interoperability

- Run an independent client (a Bluesky app build, `goat`, `atcute`) against a
  deployed server and record the result.
- Have a real relay consume the firehose and confirm it accepts the commits.
- Add a conformance run against the upstream Lexicon schemas rather than only
  shape checks.

### Records and Lexicons

- Dynamic Lexicon resolution, so records are validated against their published
  schema rather than only against the data model.
- `com.atproto.label.*` emission, and an Ozone integration for moderation
  decisions taken elsewhere.

### Storage and scale

- An S3-compatible blob backend, so blobs stop living in the database.
- Shared rate-limit counters (Redis), so several instances enforce one budget.
- Block garbage collection, with a retention window that keeps firehose backfill
  intact.
- Streaming CAR export and import, rather than buffering the archive.

### Key custody

- Master-key rotation: an offline pass that re-encrypts every sealed value.
- Managed signing and rotation key rotation, published through the directory.
- Externally signed PLC recovery forks, and reconciliation when a directory
  changes outside this server.

### Account security

- WebAuthn passkeys, as a second factor and as a primary credential.
- Authenticator recovery that an operator can perform without database access.

### Operations

- Checksummed backup and restore tooling, and a documented recovery drill.
- Metrics and structured logs.
- A migration driver that runs the whole [account migration](/migration/)
  sequence rather than leaving it to the client.

## Completion criteria

An independent client can create an account, authenticate, publish, read, update
and delete records and blobs; a relay can synchronise signed repositories;
accounts can migrate without data loss. OAuth, lifecycle and administrative APIs,
abuse controls, durability and operational documentation must also be implemented
and tested.

The first four are demonstrated by the test suite. The relay and independent
client checks are the honest gap: until they are run against real software, this
is a PDS that passes its own tests.

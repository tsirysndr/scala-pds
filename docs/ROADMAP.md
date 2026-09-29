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
    limits, SSRF defences, master-key rotation, the container image and the Nix
    flake.
11. **Record validation** — a trusted, checksummed Lexicon catalog, plus
    authenticated resolution and admission of Lexicons published outside it.
12. **Blob backends** — blobs in the database or in an S3-compatible bucket,
    with a durable deletion queue.
13. **Retention** — a bounded firehose window and block garbage collection that
    never touches a reachable block.
14. **Passkeys** — WebAuthn registration and sign-in, verified end to end
    against a virtual authenticator.
15. **Shared counters** — optional Redis-backed rate-limit windows.
16. **Observability** — Prometheus metrics behind administrator credentials and
    a structured access log.
17. **Operator recovery** — an audited host command that clears an account's
    second factors without touching its password.
18. **Backups** — checksummed SQLite snapshots taken while running, verified by
    re-reading them.
19. **Managed key rotation** — new signing and rotation keys published through
    the directory, with the head re-signed so exports keep verifying.
20. **Reference verification** — the atproto TypeScript packages check the
    server's repositories, proofs, records and firehose frames on every CI run,
    the official client library drives it end to end, and the reference OAuth
    client completes the authorization flow against it.
21. **Account migration end to end** — inbound service authentication, DID
    adoption vouched for by the old host, and a driver that runs the whole
    sequence between two servers.
22. **Directory reconciliation** — managed identities compared against the
    directory's log head, so an operation signed outside this server is found
    and either adopted or reported.

## Next

### Interoperability

- Point the Bluesky application itself at a deployed server and record the
  result; the official client *library* already drives it in CI.
- Have a real relay consume the firehose and confirm it accepts the commits.

### Records and Lexicons

- `com.atproto.label.*` emission, and an Ozone integration for moderation
  decisions taken elsewhere.

## Completion criteria

An independent client can create an account, authenticate, publish, read, update
and delete records and blobs; a relay can synchronise signed repositories;
accounts can migrate without data loss. OAuth, lifecycle and administrative APIs,
abuse controls, durability and operational documentation must also be implemented
and tested.

The first four are demonstrated by the test suite, and the repository, proof,
record and firehose formats are additionally verified by the reference
TypeScript packages while the official client library and the reference OAuth
client drive the server end to end — see [interoperability](/interop/). A real relay and the Bluesky
application itself remain the honest gap.

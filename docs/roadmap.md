# Implementation roadmap

## Approach

Build vertical slices with explicit wire contracts, failure cases, and tests.
Keep every commit buildable. Commit tests with the behavior they verify.
Do not claim interoperability until checked against independent AT Protocol tools.

Use Scala 3, Cats Effect for resource lifecycles, http4s for HTTP, and Circe for
JSON. Start as one sbt module, keeping protocol, HTTP, and persistence concerns
separate as they emerge. Use SQLite for initial durable account and repository
storage; introduce it when the first persistent feature lands.

## Milestones

1. **Server foundation (implemented)**: reproducible build, validated configuration, health,
   `com.atproto.server.describeServer`, and XRPC errors.
2. **Protocol primitives**: validated identifiers (DID, handle, NSID, AT URI,
   record key, TID), canonical DAG-CBOR, CID, CAR, and crypto with test vectors.
3. **Durable storage**: migrations, transactions, account metadata, block storage,
   blob storage, restart and recovery tests.
4. **Identity and accounts**: DID resolution, handle verification, signing keys,
   PLC operations, account provisioning and lifecycle.
5. **Authentication**: password hashing, sessions, token rotation/revocation,
   app passwords, service authentication, OAuth including DPoP and discovery.
6. **Repositories**: Merkle search tree, signed commits, atomic record writes,
   compare-and-swap, read/list/describe endpoints and repository validation.
7. **Blobs**: streaming upload/download, content addressing, limits, record
   references, garbage collection.
8. **Federation**: durable event sequencing, subscribeRepos, sync and CAR export,
   relay notification, backfill and reconnection.
9. **Service integration**: authenticated AppView proxying, preferences,
   moderation/reporting integration and endpoint coverage audit.
10. **Operations and portability**: account import/export/migration, invitations,
    admin APIs, email flows, rate limits, SSRF defenses, observability, backups,
    deployment packaging and end-to-end interoperability tests.

This is a work breakdown, not a claim that each milestone is a single commit.
Break milestones into small changes and update this document as scope is verified.

## Completion criteria

An independent client can create an account, authenticate, publish/read/update/
delete records and blobs; a relay can synchronize signed repositories; accounts
can migrate without data loss. OAuth, lifecycle/admin APIs, abuse controls,
durability and operational documentation must also be implemented and tested.

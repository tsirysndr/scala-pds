---
layout: layouts/home.vto
title: scala-pds
description: An AT Protocol Personal Data Server in Scala 3, with PostgreSQL persistence and a zero-configuration SQLite fallback.
---

scala-pds is an [AT Protocol](https://atproto.com) Personal Data Server built
on Scala 3, Cats Effect and http4s. It stores accounts and repositories in
PostgreSQL, or in a single SQLite file when nothing is configured, and serves
71 XRPC methods alongside an OAuth authorization server and a React account
interface.

**In development: not yet a fully federating PDS.** Signed repositories,
Lexicon-validated records, sessions, OAuth with DPoP, passkeys, blobs, the
firehose and administrative APIs are implemented and tested; see the
[compatibility matrix](/compatibility/) for exact coverage and the
[roadmap](/roadmap/) for what remains.

<div class="card-grid">

<div class="card">

### Start a server

One command, no configuration: accounts and repositories land in
`data/scala-pds.sqlite3`.

[Get started](/get-started/)

</div>

<div class="card">

### Host real accounts

Hosted `did:plc` and `did:web` identities, handle verification, sessions, app
passwords and email security flows.

[Hosted identities](/identity/)

</div>

<div class="card">

### Authorize applications

A full OAuth 2.1 authorization server: pushed authorization requests, PKCE,
DPoP-bound tokens and scoped consent.

[OAuth](/oauth/)

</div>

<div class="card">

### Federate

Signed Merkle search tree commits, verified CAR export and import, and a
WebSocket firehose other services can follow.

[Repositories](/repositories/)

</div>

</div>

## What is implemented

- **Protocol primitives** — identifier syntax, deterministic DAG-CBOR, CIDv1,
  CAR archives, the Merkle search tree and ECDSA over secp256k1 and P-256, all
  checked against the upstream interoperability fixtures.
- **Storage** — checksummed migrations over PostgreSQL or SQLite, one schema
  for both, epoch-millisecond timestamps and a pooled connection lifecycle.
- **Identity** — `did:plc` genesis and update operations, `did:web` documents,
  DNS and HTTPS handle resolution with a bounded cache, and reconciliation
  against the directory that publishes them.
- **Accounts** — registration, sessions, refresh rotation, app passwords,
  invite codes, email flows, TOTP, WebAuthn passkeys, deactivation, deletion and
  takedowns.
- **Records** — validated against a pinned Lexicon catalog, or against a
  Lexicon published and signed by its own namespace authority.
- **Repositories** — signed commits, compare-and-swap writes, batched
  `applyWrites`, record listing, CAR export and verified import.
- **Blobs** — content-addressed upload and download with reference checks, in
  the database or an S3-compatible bucket.
- **Federation** — a durable event sequence and `subscribeRepos` over
  WebSocket, plus relay crawl requests at startup.
- **Services** — an authenticated proxy to AppViews and labelers, private
  preferences, and the administrative and moderation APIs, which a configured
  moderation service can call with a service token of its own.
- **Migration** — inbound service authentication, DID adoption vouched for by
  the old host, and a driver that moves a whole account between two servers.
- **Operations** — Prometheus metrics, a structured access log, checksummed
  backups, master-key and account-key rotation, and audited account recovery.

## License

[MIT](https://github.com/tsirysndr/scala-pds/blob/main/LICENSE). Vendored
conformance fixtures retain their upstream CC0 license.

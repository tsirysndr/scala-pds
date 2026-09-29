# Firehose

`com.atproto.sync.subscribeRepos` is the WebSocket stream relays and AppViews
follow to learn what changed here.

## The event sequence

Events are rows in a durable, monotonic sequence, written **in the same
transaction as the change they describe**. A consumer therefore never sees a
commit the repository does not have, and never misses one the repository does.

| Type | Emitted when |
| --- | --- |
| `#commit` | records were created, updated or deleted |
| `#identity` | an account was created, or its handle changed |
| `#account` | an account was created, deactivated, taken down or deleted |
| `#sync` | a repository was created or imported, carrying its current head |

## Frames

Each message is two concatenated DAG-CBOR values: a header naming the type,
then the body.

```
header: { "op": 1, "t": "#commit" }
body:   { "seq": 42, "repo": "did:plc:…", "commit": <link>, "rev": "3mwm…", … }
```

A `#commit` body carries `repo`, `commit`, `rev`, `since` (the previous
revision), `blocks` — a CAR archive of exactly the blocks that commit introduced,
rooted at the new commit — `ops` with one `{action, path, cid}` per record
change, the blob CIDs the commit references, `prevData` (the previous tree root)
and `time`.

An error frame uses `op: -1` and **ends** the subscription:

```
header: { "op": -1 }
body:   { "error": "FutureCursor", "message": "Cursor is ahead of the sequence" }
```

An `#info` message is a notice that does not end anything:

```
header: { "op": 1, "t": "#info" }
body:   { "name": "OutdatedCursor", "message": "…some events were skipped" }
```

## Cursors

| `cursor` | Behaviour |
| --- | --- |
| omitted | stream events from now on |
| within the retained sequence | replay from that sequence number, then continue live |
| older than the retained sequence | an `OutdatedCursor` `#info` message, then replay from the oldest event still held |
| ahead of the sequence | a `FutureCursor` error frame, and the connection ends |

A consumer that asks for a cursor the retention window has passed is **told so**
before anything else arrives, because the alternative — quietly resuming from the
oldest retained event — would leave it believing it had an unbroken history. On
that notice it should read the whole repository with `com.atproto.sync.getRepo`
rather than trusting the gap.

Backfill is served in batches of up to 1000 events. A ping is sent every thirty
seconds so idle connections survive intermediaries.

## Following it

```sh
websocat "wss://pds.example.com/xrpc/com.atproto.sync.subscribeRepos?cursor=0"
```

A fresh server with one account and one post emits `#identity`, `#account`,
`#sync` and `#commit`, in that order.

## Retention

`PDS_FIREHOSE_RETENTION_HOURS` (72 by default, `0` to keep everything) bounds
how far back a consumer can rewind. A background pass drops events past the
window, and a second pass then reclaims the repository blocks those revisions
were holding open — see [repositories](/repositories/).

A consumer that falls further behind than the window is answered with an
`OutdatedCursor` notice; it reads the whole repository with
`com.atproto.sync.getRepo` and resumes live. That is why the retained window and
the block retention are the same window: an event that can still be replayed
always has its blocks.

## Announcing the server

`PDS_RELAY_URLS` is a comma-separated list of relays. At startup the server
posts `com.atproto.sync.requestCrawl` with its hostname to each one and logs the
outcome, so a new host is discovered without manual registration. Failures are
logged and do not stop the server.

## Verifying what you receive

Nothing in a frame has to be trusted. The `blocks` archive verifies every block
against its own CID, the commit verifies against the account's signing key from
its DID document, and `prevData` chains it to the tree root the previous event
announced. [`com.atproto.sync.getRepo`](/repositories/) provides the full
repository for a consumer that fell behind the retained sequence.

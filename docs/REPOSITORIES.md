# Repositories

Each account owns one signed repository: a Merkle search tree of records,
committed with the account's signing key, addressed by content.

## Structure

Records are DAG-CBOR objects keyed by `<collection>/<rkey>`, where the
collection is an NSID and the record key is 1–512 characters of
`[A-Za-z0-9.:_~-]`. Keys live in a Merkle search tree with fanout 4: a key's
layer is the number of leading zero *pairs* of bits in the SHA-256 of the key,
so the shape of the tree is a function of its contents and nothing else.

Nodes serialize with prefix compression — each entry stores how many bytes it
shares with the previous key and only the remaining suffix — and map keys are
ordered shortest-first then bytewise, which is what makes a node's CID a function
of its bytes alone.

Every block is CIDv1 with a SHA-256 multihash: `dag-cbor` for commits, tree nodes
and records, `raw` for blobs.

The root CID does not depend on insertion order. The test suite inserts the same
keys in several shuffled orders and asserts identical roots, and drives
interleaved writes and deletes against a reference map.

## Commits

```
{ did, version: 3, data: <tree root>, rev: <TID>, prev: <previous commit> | null, sig }
```

The signature covers the DAG-CBOR encoding of that object *without* `sig`, using
ECDSA over secp256k1 or P-256 with SHA-256, as 64 raw bytes normalized to low-S.
High-S signatures do not verify, so a signature cannot be reshaped into a second
valid form. Signing is RFC 6979 deterministic, so identical input yields
identical bytes.

Revisions are TIDs: 53 bits of microseconds and a 10-bit clock identifier,
base32-sortable. Generation never repeats or moves backwards within a process, so
revisions sort in commit order.

## Writing

| Method                          | Behaviour                                                       |
| ------------------------------- | --------------------------------------------------------------- |
| `com.atproto.repo.createRecord` | fails if the key exists; generates a TID key when none is given |
| `com.atproto.repo.putRecord`    | creates or replaces                                             |
| `com.atproto.repo.deleteRecord` | succeeds silently when the record is already absent             |
| `com.atproto.repo.applyWrites`  | 1–200 writes in a single commit                                 |

Each write is validated before it reaches the tree: the collection must be an
NSID, the key a valid record key, the record an object whose `$type` matches the
collection, and its encoding must stay within the data model — no floats, no
non-string map keys, no oversized records.

Writes are authenticated, and only for the caller's own repository; naming
another account's `repo` is refused with `Forbidden`.

### Compare-and-swap

`swapRecord` requires the record's current CID (or its absence) and `swapCommit`
requires the repository head. A mismatch is refused with `InvalidSwap` before
anything is written, so two clients cannot silently overwrite each other.

### Atomicity

A commit is one database transaction: the new blocks, the updated root, the
record index, the blob references and the firehose event are written together. A
batch containing one invalid write leaves the repository exactly as it was.

## Reading

`getRecord` and `listRecords` read the indexed records. Listing is keyed by
record key, forward or reverse, 1–100 at a time, returning the last key as the
cursor. `describeRepo` reports the handle, DID, DID document and the collections
in use.

Records under a takedown are omitted from listings and answered `RecordNotFound`.

## Export and import

`com.atproto.sync.getRepo` streams a CARv1 archive whose single root is the
current commit, followed by the commit block, every tree node and every record.
The response carries `Atproto-Repo-Rev`. Because the export walks the live tree,
blocks orphaned by past deletions are never served.

The archive is produced incrementally: the tree walk collects block
*identifiers*, then a held connection reads and writes one block at a time, so
the server's memory does not scale with repository size.

`since=<rev>` returns a diff instead: the same root and commit block, but only
the blocks a revision after `rev` introduced. Every block is stored with the
revision that created it, so the filter is exact — a consumer that already holds
everything up to `rev` gets what it is missing and nothing else.

`com.atproto.sync.getRecord` returns a proof: the commit, the tree nodes on the
path to the key, and the record itself when it exists — enough to verify
inclusion, or exclusion, against the signed root.

`com.atproto.repo.importRepo` accepts a CAR archive up to 256 MiB. The body is
staged to a temporary file rather than held in memory, then parsed a block at a
time. Every block is verified against its own CID as it is read, the root must
decode as a commit for the authenticated account, and the commit must verify
against the account's signing key and resolve its whole tree before anything is
stored. The record index and blob references are then rebuilt from the imported
tree, and the staged file is removed whether the import succeeded or not.

## Storage model

Blocks are keyed by `(did, cid)` and written append-only within a revision, so a
repeated write costs nothing new — identical content has an identical CID — and
historical revisions stay resolvable for firehose consumers. Nothing orphaned is
ever served: exports and reads walk from the current root.

### Garbage collection

Overwriting or deleting a record orphans the blocks that held the old version,
and the superseded commit and tree nodes with it. A background pass reclaims
them, under two rules:

- a block reachable from the **current commit** is never removed, whatever its
  revision, so the repository stays exportable and verifiable;
- a block whose revision is still needed by a retained firehose event is never
  removed, so backfill keeps working.

That second bound is the [firehose retention window](/firehose/). With
`PDS_FIREHOSE_RETENTION_HOURS=0`, events are kept forever and no block is ever
collected.

Collection is idempotent and runs per account, a bounded number of accounts per
pass, so it never holds a long transaction.

## Limits

| Limit                  | Value                |
| ---------------------- | -------------------- |
| Record size            | 64 KiB encoded       |
| Writes per commit      | 200                  |
| Records per listing    | 100                  |
| Blocks per `getBlocks` | 1000                 |
| CAR block size         | 4 MiB                |
| Import archive         | 256 MiB              |
| Nesting depth          | 32 (JSON), 64 (CBOR) |

# Blobs

Blobs are the binary payloads records point at: avatars, images, video. They are
content-addressed and stored per account.

## Upload

`com.atproto.repo.uploadBlob` takes the raw bytes with a `Content-Type`, hashes
them into a CIDv1 `raw` CID, and stores them against the account. It returns the
reference a record embeds:

```json
{
  "blob": {
    "$type": "blob",
    "ref": { "$link": "bafkrei…" },
    "mimeType": "image/png",
    "size": 12345
  }
}
```

Uploading the same bytes twice is idempotent — the CID is the same, so the second
upload keeps the existing row.

Empty uploads are refused. Anything larger than `PDS_BLOB_MAX_SIZE` (5 MiB by
default, 1 KiB–100 MiB) is refused with `PayloadTooLarge` without buffering the
whole body.

## References

A record may only reference a blob the same account has already uploaded, at the
size it declares. Both are checked inside the commit transaction, so a record can
never name a blob that is not there:

- an unknown CID fails with “Blob … has not been uploaded”;
- a mismatched `size` fails with “does not have the referenced size”;
- a `ref` that is not a `raw` CID is refused by the data model itself.

References are indexed per record, so rewriting or deleting a record updates
which blobs it claims.

`com.atproto.repo.listMissingBlobs` lists blobs that records reference but the
account has not uploaded — the check a migration runs before it activates.

## Download

`com.atproto.sync.getBlob` returns the bytes with their stored content type and
headers that stop a browser treating them as active content:

```
Content-Security-Policy: default-src 'none'; sandbox
X-Content-Type-Options: nosniff
```

`com.atproto.sync.listBlobs` lists an account's referenced blob CIDs, optionally
only those first referenced after a given revision, paginated by CID.

## Takedowns

`com.atproto.admin.updateSubjectStatus` with a `com.atproto.admin.defs#repoBlobRef`
subject marks a blob taken down. It then answers `BlobNotFound` while the record
referencing it stays intact, and the state is readable with
`com.atproto.admin.getSubjectStatus`. See [moderation](/moderation/).

## Storage

By default blobs live in the database — `bytea` on PostgreSQL, `blob` on
SQLite — keyed by `(did, cid)` with their size and content type.

Setting `PDS_S3_BUCKET` moves them to an S3-compatible bucket instead, which is
what a server of any size wants: the database stops carrying binary payloads,
and the bucket can be served through a CDN.

| Variable                   | Meaning                                                       |
| -------------------------- | ------------------------------------------------------------- |
| `PDS_S3_BUCKET`            | the bucket; setting it selects the S3 backend                 |
| `PDS_S3_REGION`            | signing region                                                |
| `PDS_S3_ENDPOINT`          | endpoint URL; defaults to `https://s3.<region>.amazonaws.com` |
| `PDS_S3_ACCESS_KEY_ID`     | access key                                                    |
| `PDS_S3_SECRET_ACCESS_KEY` | secret key                                                    |
| `PDS_S3_PATH_STYLE`        | `false` for virtual-host addressing; path style by default    |
| `PDS_S3_PREFIX`            | key space this server owns; `blobs/` by default               |

Naming a bucket requires the region and both credentials; a partial set is a
startup error rather than a silent fallback to the database. Requests are signed
with AWS Signature Version 4 over the payload, so any compatible
### Sharing a bucket

`PDS_S3_PREFIX` is the key space this server owns; every object it writes goes
under it, as `<prefix><did>/<cid>`. It exists so one bucket can hold more than
one service's blobs, which is worth doing deliberately rather than by accident:
a service that lists a prefix and assumes it understands every key it finds will
break on someone else's, and one that deletes what it does not recognise will
destroy them. Give each service a prefix of its own and neither can see the
other.

Leading and trailing slashes are normalised, so `scala-pds/blobs`,
`/scala-pds/blobs/` and `scala-pds/blobs/` are the same setting.

Changing the prefix does not move existing objects. Each blob row stores the key
it was written under, so old blobs keep resolving; only new writes use the new
prefix.

implementation — MinIO, R2, Backblaze B2 — works by pointing `PDS_S3_ENDPOINT`
at it.

Objects are keyed `blobs/<did>/<cid>`, with `:` replaced in the DID. The object
is written **before** the row that names it, so a failure leaves an
unreferenced object rather than a row pointing at nothing; because blobs are
content-addressed, a retry rewrites identical bytes. An upload the bucket
refuses is reported as `BlobStoreFailed` and creates no row.

The backend is recorded per blob, so a server that switches to a bucket keeps
serving everything it stored earlier from the database.

Deleting an account cannot wait on a remote call inside its transaction, so the
objects are queued in `blob_deletions` by the same transaction that removes the
rows. A background sweep drains the queue every thirty seconds, retrying
failures with a backoff. Nothing is forgotten if the process stops midway.

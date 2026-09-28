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

Blobs live in the database — `bytea` on PostgreSQL, `blob` on SQLite — keyed by
`(did, cid)` with their size and content type. Deleting an account removes its
blobs with it. An external object store is [on the
roadmap](/roadmap/).

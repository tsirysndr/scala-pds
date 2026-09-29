# Moderation

Takedowns are reversible state on a subject, not deletion. The content stays in
the repository — so the signed history remains verifiable — while the server
stops serving it.

## Subjects

| Subject type                         | Identified by     | Effect while applied                                                  |
| ------------------------------------ | ----------------- | --------------------------------------------------------------------- |
| `com.atproto.admin.defs#repoRef`     | `did`             | the account cannot authenticate; API access returns `AccountTakedown` |
| `com.atproto.repo.strongRef`         | `uri` (an AT URI) | the record is omitted from listings and answers `RecordNotFound`      |
| `com.atproto.admin.defs#repoBlobRef` | `did` and `cid`   | the blob answers `BlobNotFound`                                       |

## Applying and lifting

```sh
curl -sS -X POST https://pds.example.com/xrpc/com.atproto.admin.updateSubjectStatus \
  -u "admin:$PDS_ADMIN_PASSWORD" -H 'content-type: application/json' \
  -d '{"subject":{"$type":"com.atproto.admin.defs#repoRef","did":"did:plc:…"},
       "takedown":{"applied":true}}'
```

A reference can be supplied as `takedown.ref` to link the action to a moderation
case; when it is omitted the server generates one. Lifting it is the same call
with `"applied": false`.

```sh
curl -sS -u "admin:$PDS_ADMIN_PASSWORD" \
  "https://pds.example.com/xrpc/com.atproto.admin.getSubjectStatus?did=did:plc:…"
```

```json
{
  "subject": { "$type": "com.atproto.admin.defs#repoRef", "did": "did:plc:…" },
  "takedown": { "applied": true, "ref": "…" }
}
```

## Account takedowns and prior state

An account takedown records the status the account had before it — `active` or
`deactivated` — and lifting the takedown restores exactly that. A takedown
applied to an already-deactivated account therefore does not silently reactivate
it later.

While taken down:

- sign-in and every authenticated method return `AccountTakedown` with 403;
- `com.atproto.sync.getRepoStatus` reports `active: false` and `status: takendown`;
- an `#account` event is emitted on the firehose so downstream services learn of
  it;
- `activateAccount` refuses, so the owner cannot lift it themselves.

## Record and blob takedowns

A record takedown is a reference on the indexed row: `getRecord` answers
`RecordNotFound` and `listRecords` omits it. The record still exists in the tree,
so the signed commit history continues to verify and `getRepo` still produces a
consistent archive — a moderation decision here does not rewrite history.

A blob takedown behaves the same way: `getBlob` answers `BlobNotFound` while the
record referencing it stays intact.

## Deletion

Deletion is separate and irreversible. `com.atproto.server.deleteAccount` (the
owner, with their password and an emailed code) and
`com.atproto.admin.deleteAccount` (an operator) emit an `#account` event and then
remove the account row, its repository blocks and its blobs. Every dependent row
— sessions, app passwords, tokens, preferences, OAuth grants — cascades with it.

## Reports

`com.atproto.moderation.createReport` is proxied to `PDS_MOD_SERVICE_URL`, signed
as an inter-service token for the reporting account, so the moderation service
knows who reported what. It requires a session, and answers `NotImplemented` when
no moderation service is configured. See [the service proxy](/proxy/).

## A moderation service acting here

The administrator password is an operator credential and should not be handed to
another service. Set `PDS_MOD_SERVICE_DID` to a moderation service's DID — an
Ozone deployment, say — and it can call the administrative methods with its own
identity instead, the other direction from reports:

```
Authorization: Bearer <service token signed by the moderation service>
```

The token is checked like any other [inbound service
token](/migration/#service-authentication): the issuer must be exactly the
configured DID, `aud` must be this server's DID, `lxm` must name the method being
called, `exp` must be in the future, and the signature must verify against the
`#atproto` verification method in the moderation service's own DID document. A
token that fails any of those is refused as unauthorized, the same as a wrong
password — so a token scoped to one method cannot be replayed against another.

## What is not here yet

Label emission (`com.atproto.label.*`) is [on the roadmap](/roadmap/). This
server implements the takedown side of moderation: the mechanism a moderation
service acts through, not the service itself.

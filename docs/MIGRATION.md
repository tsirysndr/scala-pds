# Account migration

Moving an account here means preparing a destination account, importing the
repository, transferring blobs and finally pointing the DID at this server. The
primitives are implemented; the orchestration is the client's.

## Preparing the destination

`com.atproto.server.createAccount` accepts a `did` for an account that already
exists elsewhere. The supplied DID must resolve, and its document must already
name this server's `PDS_PUBLIC_URL` as its `atproto_pds` service — the server
refuses to adopt a DID that does not point at it. The account is created with a
fresh signing key on this host.

`com.atproto.server.reserveSigningKey` generates and seals a signing key for a
DID before the account exists, returning its `did:key`, so the operator can
publish the key in the DID document ahead of the move.

## Importing the repository

Export from the old host and import here:

```sh
curl -sS "https://old.example.com/xrpc/com.atproto.sync.getRepo?did=$DID" -o repo.car

curl -sS -X POST https://pds.example.com/xrpc/com.atproto.repo.importRepo \
  -H "authorization: Bearer $TOKEN" \
  -H 'content-type: application/vnd.ipld.car' \
  --data-binary @repo.car
```

Import verifies every block against its own CID, requires the root to decode as a
commit for the authenticated account, and requires that commit to verify against
the account's signing key and resolve its whole tree before anything is stored.
The record index and blob references are then rebuilt from the imported tree, and
a `#sync` event announces the new head. Archives up to 256 MiB are accepted.

## Transferring blobs

The imported repository references blobs that are still on the old host.

```sh
curl -sS "https://pds.example.com/xrpc/com.atproto.repo.listMissingBlobs" \
  -H "authorization: Bearer $TOKEN"
```

For each CID, fetch it from the old host with `com.atproto.sync.getBlob` and post
it to `com.atproto.repo.uploadBlob` here. Because blobs are content-addressed, the
CID matches automatically when the bytes do.

## Preferences

`app.bsky.actor.getPreferences` on the old host and `putPreferences` here carries
private preferences across. See [preferences](/preferences/).

## Confirming completeness

```sh
curl -sS https://pds.example.com/xrpc/com.atproto.server.checkAccountStatus \
  -H "authorization: Bearer $TOKEN"
```

```json
{
  "activated": true,
  "validDid": true,
  "repoCommit": "bafyrei…",
  "repoRev": "3mwm…",
  "repoBlocks": 214,
  "indexedRecords": 132,
  "privateStateValues": 3,
  "expectedBlobs": 12,
  "importedBlobs": 12
}
```

`expectedBlobs` equal to `importedBlobs`, and `indexedRecords` matching the
source, is the signal to switch the identity over.

## Switching the identity

For a `did:plc` account the owner signs an operation that changes the
`atproto_pds` service endpoint and the `atproto` verification method to this
server's values:

1. `com.atproto.identity.getRecommendedDidCredentials` here returns the values to
   publish.
2. `com.atproto.identity.requestPlcOperationSignature` emails a confirmation code.
3. `com.atproto.identity.signPlcOperation` merges those values onto the
   directory's current head and signs with the stored rotation key.
4. `com.atproto.identity.submitPlcOperation` submits it and marks the keys
   confirmed.

Steps 3 and 4 can also be performed by whoever holds a rotation key, entirely
outside this server.

For a `did:web` account the DID document is served by whoever controls that
domain; publish the new key and endpoint there.

## Finishing

`com.atproto.server.activateAccount` here and
`com.atproto.server.deactivateAccount` on the old host complete the move. The
old host keeps serving `getRepo` while deactivated, so a consumer that has not
caught up can still read the history.

## Service authentication

Inter-service calls during a migration are authenticated with
`com.atproto.server.getServiceAuth`: a token signed by the account's repository
key for a named audience and method, valid for at most ten minutes.

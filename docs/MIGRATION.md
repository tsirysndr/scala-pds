# Account migration

Moving an account here means preparing a destination account, importing the
repository, transferring blobs and finally pointing the DID at this server.
Every step is an ordinary XRPC call, and the built-in driver runs the whole
sequence.

## The driver

```sh
scala-pds migrate \
  --from https://old.example.com \
  --identifier alice.old.example.com --password "$OLD_PASSWORD" \
  --handle alice.pds.example.com --email alice@example.com \
  --new-password "$NEW_PASSWORD"
```

`--to` defaults to this server's `PDS_PUBLIC_URL`, and `--new-password` to
`--password`. The driver signs in to the source, has it vouch for the move,
creates the destination account, imports the repository, transfers every missing
blob, copies preferences, checks the counts agree, and then switches the
identity. For a `did:plc` account the last step needs the confirmation code the
source emails; the first run requests it and stops, and re-running with
`--plc-token <code>` publishes the operation, activates here and deactivates
there. A `did:web` account's document belongs to its own domain, so the driver
stops after the data is in place and says what is left to publish.

It exits `0` once the account is activated here, `3` when the move is complete
but the identity switch is still outstanding, and `1` on failure.

The sections below describe what the driver does at each step, which is also
what a client would do by hand.

## Preparing the destination

`com.atproto.server.createAccount` accepts a `did` for an account that already
exists elsewhere. The supplied DID must resolve, and then one of two things must
hold: either its document already names this server's `PDS_PUBLIC_URL` as its
`atproto_pds` service, or the request carries a service token from the account
itself, scoped to `com.atproto.server.createAccount` and addressed to this
server's DID. A migrating account still points at its old host — that is the
last step of a move, not the first — so the old host vouching for it is what
authorizes the adoption. The account is created with a fresh signing key here.

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
commit for the authenticated account, and requires that commit to verify — either
against the signing key reserved here or against the one the account's document
still publishes — and to resolve its whole tree before anything is stored.
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
source, is the signal to switch the identity over. `validDid` is resolved rather
than assumed: it is true only once the account's document names this server's
endpoint and the signing key this server holds.

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
`com.atproto.server.deactivateAccount` on the old host complete the move.
Activation is the point at which this server becomes the account's home, so an
imported head that was signed elsewhere is re-signed with the key published here
and announced on the firehose. The old host keeps serving `getRepo` while
deactivated, so a consumer that has not caught up can still read the history.

## Service authentication

Inter-service calls during a migration are authenticated with
`com.atproto.server.getServiceAuth`: a token signed by the account's repository
key for a named audience and method, valid for at most ten minutes.

Inbound tokens are checked the same way. The issuer must be a DID that resolves,
`aud` must be this server's DID, `lxm` must name the method being called, `exp`
must be in the future, and the signature must verify against the `#atproto`
verification method in the issuer's document — so a token is only as good as the
identity behind it.

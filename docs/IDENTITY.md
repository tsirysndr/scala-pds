# Hosted identities

Every account has a DID that resolves to a document naming its repository
signing key and this server as its PDS. scala-pds can host two DID methods.

## did:plc

`PDS_DID_METHOD=plc` — the default for a public server. Registration builds a
genesis operation, signs it with a freshly generated rotation key, derives the
DID from the signed bytes, and submits it to `PDS_PLC_DIRECTORY` *before* any
account row exists. A directory failure therefore leaves no half-created
account behind.

The DID is the first 24 characters of lowercase base32 over the SHA-256 of the
signed genesis operation, so it is bound to the keys and services it was created
with:

```
did:plc:<base32(sha256(dag-cbor(signed genesis)))[0..24]>
```

The genesis operation carries the account's rotation keys, its `atproto`
verification method, its handle as an `at://` alias, and this server as its
`atproto_pds` service. Both the signing key and the rotation key are generated
on this host, sealed with [the master key](/master-key/) and stored; only the
public halves leave the server.

A `recoveryKey` supplied to `createAccount` is prepended to the rotation key
list, so the account owner keeps a key that outranks the server's.

## did:web

`PDS_DID_METHOD=web`, and the default when the hostname is `localhost` or
`127.0.0.1`, because a loopback server cannot publish to a public directory.
Account DIDs take the standard path form and are served from this host:

```
did:web:pds.example.com:u:alice  →  https://pds.example.com/u/alice/did.json
```

The document is rendered from live account state, so a handle change is visible
immediately with no directory round trip.

## The service document

The server's own DID is `did:web:<PDS_HOSTNAME>`, served at
`/.well-known/did.json`. It names only the PDS service endpoint; it holds no
verification method, because the server signs inter-service tokens with each
account's repository key rather than a service key.

## Handle resolution

`com.atproto.identity.resolveHandle` answers locally hosted handles from the
database. For anything else it tries, in order:

1. a DNS `TXT` record at `_atproto.<handle>` whose value is `did=<did>`, and
2. `https://<handle>/.well-known/atproto-did`, whose body is the bare DID.

Exactly one usable DNS answer is accepted; several conflicting records are
treated as no answer. `com.atproto.identity.resolveIdentity` goes further and
only returns a document whose `alsoKnownAs` claims the handle back, so a domain
cannot assert an identity that the DID does not confirm.

Resolved documents are cached in the database for ten minutes.
`com.atproto.identity.refreshIdentity` drops the entry and emits an `#identity`
event for hosted accounts.

## Changing a handle

`com.atproto.identity.updateHandle` accepts a handle under `PDS_USER_DOMAIN`
without further proof. An external domain must already resolve to the account's
DID by DNS or the well-known document; the server verifies that before it
records the change, so a handle is never claimed on the strength of the request
alone.

For `did:plc` accounts the server then signs an update operation with the stored
rotation key, submits it to the directory, and invalidates the cache. For
`did:web` accounts the served document simply reflects the new handle.

## Owner-signed PLC operations

An owner can change their own directory state through the server without holding
the rotation key themselves:

- `com.atproto.identity.getRecommendedDidCredentials` returns the rotation key,
  aliases, verification methods and services this server would publish.
- `com.atproto.identity.requestPlcOperationSignature` emails a confirmation
  code, when email is configured.
- `com.atproto.identity.signPlcOperation` merges the requested changes onto the
  directory's current head and signs the result. When email is configured the
  emailed code is required.
- `com.atproto.identity.submitPlcOperation` submits an already-signed operation
  and marks the account's keys as confirmed.

## Outbound safety

Every outbound fetch — handle documents, DID documents, OAuth client metadata,
relay requests — resolves its hostname first and refuses loopback, link-local,
private and carrier-grade NAT addresses, and refuses plain HTTP. A loopback
development server relaxes this so a local AppView can be used;
`PDS_ALLOW_PRIVATE_NETWORK=true` opts a public server in deliberately.

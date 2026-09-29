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
AT Protocol allows only the hostname form of `did:web` — no port and no path —
so an account's DID is its own handle's domain, and its document is served at
that domain:

```
did:web:alice.pds.example.com  →  https://alice.pds.example.com/.well-known/did.json
```

The document is rendered from live account state, so a handle change is visible
immediately with no directory round trip.

## The service document

The server's own DID is `did:web:<PDS_HOSTNAME>`. Because every `did:web`
document lives at `/.well-known/did.json`, one route answers for all of them and
picks the document by the request's `Host` header: a host that names a hosted
account gets that account's document, anything else gets the service document.
The service document names only the PDS service endpoint; it holds no
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

The same route is **served** for handles hosted here, so another server can
resolve them without a DNS record: `GET /.well-known/atproto-did` answers with
the bare DID of the account whose handle is the request's `Host`, as
`text/plain` and `no-store`, and `404` for a domain no handle here claims.

## Sharing a handle domain with another server

One handle domain can be served by several PDS instances — `*.bsky.social` works
that way — because handle resolution belongs to whichever server owns the
wildcard, not to whichever server stores the repository. That server answers
`/.well-known/atproto-did` for the whole namespace, and the DID documents point
at wherever the repositories actually live.

To be one of several servers in such a domain, two things have to hold.

The owner of the wildcard has to resolve this server's handles. That is its
configuration, not this server's: it needs to answer `/.well-known/atproto-did`
for them, and, if it issues certificates on demand, to allow those names as well
— otherwise the TLS handshake fails before resolution is ever attempted.

`PDS_HANDLE_AUTHORITY` is that server's origin. Before allocating a handle in its
own user domain — at registration and at `updateHandle` — this server asks the
authority `com.atproto.identity.resolveHandle` and refuses a name the authority
already resolves to a different account. Only an answered claim counts as taken:
an authority that cannot be reached leaves the name unproven rather than
blocking registration, and a name that resolves back to the account asking for it
is not a collision with itself.

Without `PDS_HANDLE_AUTHORITY` the server assumes it alone allocates its user
domain, which is the right assumption when it owns that domain outright.

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
- `com.atproto.identity.submitPlcOperation` submits an already-signed operation.
  The keys this server holds are marked confirmed only when the operation
  actually names them, because an operation signed elsewhere can hand the
  identity to a different key.

## Rotating managed keys

An operator can replace the keys this server holds for an account:

```sh
java -jar scala-pds.jar rotate-account-keys alice.example.com both
```

```
scala-pds: rotated keys for alice.example.com (did:plc:…)
scala-pds: signing key is now did:key:zQ3sh…
scala-pds: head re-signed at revision 3mwm…
scala-pds: rotation key is now did:key:zQ3sh…
```

`signing`, `rotation` or `both` selects what changes.

The directory is updated **first**, signed with the current rotation key: a
rejected operation must not leave the server holding a key the published
document does not name. Only then are the new sealed keys stored.

Rotating the signing key leaves the head commit signed by a key the document no
longer names, so the head is **re-signed at a new revision** in the same
transaction, and a `#commit` event announces it. A consumer that verifies the
current commit against the current document therefore never sees a gap, and the
records themselves are untouched — the tree root does not change.

Rotating the rotation key replaces only the key this server holds. A
`recoveryKey` the account supplied at registration outranks it and is left in
place, so the owner keeps independent control.

For `did:web` accounts there is no directory to update: the served document
reflects the new key immediately.

This is different from [master-key rotation](/master-key/), which re-encrypts
the same keys under a new sealing key without changing any published identity.

## Recovery forks and reconciliation

Anyone holding a rotation key can change a `did:plc` document, and the
`recoveryKey` given at registration outranks this server's own. Such an
operation is signed entirely outside this server — it can be submitted through
`submitPlcOperation` or straight to the directory — and it can replace the
signing key, the rotation keys, the handle or the PDS endpoint. The local view is
therefore never assumed to be current; the difference is looked for:

```sh
java -jar scala-pds.jar reconcile           # report
java -jar scala-pds.jar reconcile --repair  # report and act
```

```
scala-pds: checked 128 managed identity/identities
scala-pds: did:plc:… (alice.example.com) handle: the document names alice2.example.com — repaired
scala-pds: did:plc:… (bob.example.com) signing-key: the document names did:key:zQ3sh…, this server holds did:key:zDna…
```

`--identifier <handle|did|email>` narrows it to one account. Each managed
identity is read from the directory's log head, the cached document is dropped,
and the drift is classified:

| Kind           | Meaning                                                    | `--repair`                                      |
| -------------- | ---------------------------------------------------------- | ----------------------------------------------- |
| `handle`       | the document names another handle                          | adopt it locally, if free, and emit `#identity` |
| `endpoint`     | the document names another PDS                             | deactivate the account here                     |
| `absent`       | the directory holds no operation for the DID               | deactivate the account here                     |
| `signing-key`  | the document names a signing key this server does not hold | reported only                                   |
| `rotation-key` | this server's rotation key is no longer in the log         | reported only                                   |

The last two cannot be repaired from here: whoever signed that change holds a key
this server does not, which is exactly what a recovery key is for. The command
exits `0` when nothing is outstanding and `3` when something is.

## Outbound safety

Every outbound fetch — handle documents, DID documents, OAuth client metadata,
relay requests — resolves its hostname first and refuses loopback, link-local,
private and carrier-grade NAT addresses, and refuses plain HTTP. A loopback
development server relaxes this so a local AppView can be used;
`PDS_ALLOW_PRIVATE_NETWORK=true` opts a public server in deliberately.

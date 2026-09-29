# Interoperability

The Scala test suite proves the server agrees with itself. That is not the same
as interoperating. This check proves the **reference TypeScript packages** — the
ones a relay, AppView or client actually runs — accept what this server
produces.

```sh
bash scripts/interop.sh
```

It builds the assembly, starts a throwaway server on a temporary database,
creates an account, writes records, and then hands the server to
`@atproto/api`, `@atproto/repo`, `@atproto/crypto`, `@atproto/lexicon` and
`@atproto/oauth-client-node`. Nothing in the check is written here: every
verdict comes from the reference implementation.

```
repository
  ok   the DID document's verification method parses as a did:key — ES256K
  ok   @atproto/repo verifies the exported repository — 4 records, 1405 bytes
  ok   every written record is present with the CID the server reported — 4 matched
  ok   a repository signed by another key is rejected — rejected
  ok   a tampered archive is rejected — rejected

proofs
  ok   @atproto/repo verifies the inclusion proof — 1 claim
  ok   the proof carries the record itself — hello from scala-pds
  ok   an exclusion proof verifies for an absent record — absence proven

lexicons
  ok   the reference DAG-CBOR decoder reads every record the server wrote — 4 records
  ok   @atproto/lexicon accepts every record the server stored — 4 records against 108 schemas
  ok   the server refuses what @atproto/lexicon refuses — both rejected

official client
  ok   @atproto/api signs in with a password — interop.localhost, active=true
  ok   @atproto/api reads the session back — interop.localhost
  ok   @atproto/api writes and reads a record — at://did:web:…/app.bsky.feed.post/3mwn…
  ok   @atproto/api lists records and describes the repository — 3 posts, 3 collections
  ok   @atproto/api rotates the session with a refresh token — rotated
  ok   @atproto/api deletes the record it wrote — deleted
  ok   @atproto/api reads the server description — did:web:localhost

firehose
  ok   @atproto/repo verifies the blocks in a #commit frame — 6 frames, 3 blocks
  ok   the frame sequence starts with identity, account and sync
  ok   a cursor beyond the sequence is refused with an error frame — FutureCursor

cross-origin
  ok   a browser preflight is answered for the headers a client sends — max-age 600
  ok   the identity documents are readable from another origin — did.json 200, atproto-did 404

23/23 checks passed
```

The second half drives the authorization server with the reference OAuth
client, which performs discovery, the pushed authorization request, PKCE, DPoP
and the token exchange as a real application would. Only consent is driven by
the script, because that is this server's own browser surface rather than part
of the OAuth contract.

```
authorization
  ok   the client completes discovery and a pushed authorization request — /oauth/authorize
  ok   the owner signs in and approves the request — permissions: 2

tokens
  ok   the client exchanges the code for a DPoP-bound session — did:web:oauth….localhost
  ok   the issuer the client received matches the one it discovered — atproto transition:generic

resource access
  ok   the DPoP-bound token calls an XRPC method — oauth….localhost
  ok   the client writes a record over OAuth — 3mwn…
  ok   the session refreshes its access token — refreshed
  ok   a revoked session stops working — revoked

8/8 checks passed
```

## What each check establishes

| Check                     | Reference function                               | What it proves                                                                                                                            |
| ------------------------- | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| DID key                   | `parseDidKey`                                    | the published `publicKeyMultibase` is a well-formed `did:key` the reference can use                                                       |
| Repository                | `verifyRepoCar`                                  | the CAR parses, the commit signature verifies, and the Merkle search tree rebuilds and yields exactly the records written                 |
| Wrong key, tampered bytes | `verifyRepoCar`                                  | the archive is genuinely bound to the signing key and its own bytes                                                                       |
| Inclusion proof           | `verifyProofs`                                   | `com.atproto.sync.getRecord` carries a path that proves the record's CID against the signed root                                          |
| Record in the proof       | `verifyRecords`                                  | that proof also carries the record itself, and it decodes to what was written                                                             |
| Exclusion proof           | `verifyProofs`                                   | absence is provable, not merely asserted                                                                                                  |
| Record decoding           | `cborToLexRecord`                                | the DAG-CBOR the server wrote is read by the reference decoder                                                                            |
| Lexicon validation        | `Lexicons.validate`                              | every stored record satisfies its published schema                                                                                        |
| Mutual rejection          | `Lexicons.validate` + the server                 | the server refuses a record the reference also refuses                                                                                    |
| Client session            | `AtpAgent.login`, `getSession`, `refreshSession` | the official client library authenticates, reads its session and rotates it                                                               |
| Client writes             | `AtpAgent.com.atproto.repo.*`                    | it creates, reads, lists and deletes records, and describes the repository                                                                |
| Firehose blocks           | `readCarWithRoot`, `verifyCommitSig`             | a `#commit` frame's CAR is rooted at the commit it announces, and that commit verifies                                                    |
| Frame order               | —                                                | a fresh account emits `#identity`, `#account`, `#sync`, then `#commit`                                                                    |
| Cursor bounds             | —                                                | a cursor past the end is answered with an `op: -1` `FutureCursor` frame and nothing more                                                  |
| Cross-origin access       | —                                                | a preflight allows the headers a client actually sends, exposes `DPoP-Nonce`, and the identity documents are readable from another origin |
| Discovery and PAR         | `NodeOAuthClient.authorize`                      | the metadata documents are conformant and the pushed request is accepted with PKCE and a DPoP key                                         |
| Token exchange            | `NodeOAuthClient.callback`                       | the code redeems for a DPoP-bound token, and the client resolves the account's identity back to this server                               |
| Resource access           | `Agent` over `OAuthSession`                      | the DPoP-bound token is accepted by XRPC, including a write, with the client handling the nonce handshake                                 |
| Refresh and revocation    | `OAuthSession.getTokenInfo`, `signOut`           | the refresh token rotates, and revocation actually stops the session                                                                      |

`@atproto/api` builds its requests and **validates every response against the
published lexicons**, so a wire-format difference fails the check rather than
passing silently.

## What it does not cover yet

- **A real relay.** The frames verify, but no relay has consumed this server.
- **A Bluesky app build.** The official client *library* drives the server, but
  the application itself has not been pointed at it.
- **Account migration** between two independent servers.

Those remain the honest gap, and the [roadmap](/roadmap/) keeps them listed
until they are actually run.

## Running it against a server you already have

```sh
PDS_URL=https://pds.example.com node interop/verify.mjs
PDS_URL=https://pds.example.com node interop/oauth.mjs
```

It creates an account, so point it at a development server rather than a
production one.

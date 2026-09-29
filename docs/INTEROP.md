# Interoperability

The Scala test suite proves the server agrees with itself. That is not the same
as interoperating. This check proves the **reference TypeScript packages** — the
ones a relay, AppView or client actually runs — accept what this server
produces.

```sh
bash scripts/interop.sh
```

It builds the assembly, starts a throwaway server on a temporary database,
creates an account, writes records, and then hands the output to
`@atproto/repo`, `@atproto/crypto` and `@atproto/lexicon`. Nothing in the check
is written here: every verdict comes from the reference implementation.

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

firehose
  ok   @atproto/repo verifies the blocks in a #commit frame — 6 frames, 3 blocks
  ok   the frame sequence starts with identity, account and sync

13/13 checks passed
```

## What each check establishes

| Check | Reference function | What it proves |
| --- | --- | --- |
| DID key | `parseDidKey` | the published `publicKeyMultibase` is a well-formed `did:key` the reference can use |
| Repository | `verifyRepoCar` | the CAR parses, the commit signature verifies, and the Merkle search tree rebuilds and yields exactly the records written |
| Wrong key, tampered bytes | `verifyRepoCar` | the archive is genuinely bound to the signing key and its own bytes |
| Inclusion proof | `verifyProofs` | `com.atproto.sync.getRecord` carries a path that proves the record's CID against the signed root |
| Record in the proof | `verifyRecords` | that proof also carries the record itself, and it decodes to what was written |
| Exclusion proof | `verifyProofs` | absence is provable, not merely asserted |
| Record decoding | `cborToLexRecord` | the DAG-CBOR the server wrote is read by the reference decoder |
| Lexicon validation | `Lexicons.validate` | every stored record satisfies its published schema |
| Mutual rejection | `Lexicons.validate` + the server | the server refuses a record the reference also refuses |
| Firehose blocks | `readCarWithRoot`, `verifyCommitSig` | a `#commit` frame's CAR is rooted at the commit it announces, and that commit verifies |
| Frame order | — | a fresh account emits `#identity`, `#account`, `#sync`, then `#commit` |

## What it does not cover yet

- **A real relay.** The frames verify, but no relay has consumed this server.
- **A real client.** The repository verifies, but no Bluesky app build has signed
  in against it.
- **OAuth.** The authorization server is checked by this repository's own
  end-to-end suite, not yet by a reference client implementation.
- **Account migration** between two independent servers.

Those remain the honest gap, and the [roadmap](/roadmap/) keeps them listed
until they are actually run.

## Running it against a server you already have

```sh
PDS_URL=https://pds.example.com node interop/verify.mjs
```

It creates an account, so point it at a development server rather than a
production one.

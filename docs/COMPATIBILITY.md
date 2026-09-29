# Compatibility matrix

71 XRPC methods are implemented, plus the OAuth, discovery and account-interface
routes. "Auth" is what the method requires: *none*, *session* (an access token,
including a scoped OAuth token), *privileged* (a session that is not a plain app
password), *refresh* (a refresh token), or *admin* (`PDS_ADMIN_PASSWORD` over
HTTP Basic).

## com.atproto.server

| Method | Verb | Auth | Notes |
| --- | --- | --- | --- |
| `describeServer` | GET | none | handle domains, invite policy, contact and policy links |
| `createAccount` | POST | none | validates handle, email, password, invite; provisions identity and genesis commit |
| `createSession` | POST | none | account password or app password |
| `refreshSession` | POST | refresh | rotates the pair; replay fails |
| `deleteSession` | POST | refresh | |
| `getSession` | GET | session | |
| `createAppPassword` | POST | privileged | secret returned once |
| `listAppPasswords` | GET | privileged | |
| `revokeAppPassword` | POST | privileged | also revokes its sessions |
| `createInviteCode` | POST | admin | |
| `createInviteCodes` | POST | admin | |
| `getAccountInviteCodes` | GET | privileged | |
| `requestEmailConfirmation` | POST | privileged | needs email configured |
| `confirmEmail` | POST | privileged | |
| `requestEmailUpdate` | POST | privileged | reports whether a token is required |
| `updateEmail` | POST | privileged | clears confirmation and the email factor |
| `requestPasswordReset` | POST | none | does not enumerate accounts |
| `resetPassword` | POST | none | raises the security epoch |
| `requestAccountDelete` | POST | privileged | |
| `deleteAccount` | POST | none | requires the password and the emailed token |
| `deactivateAccount` | POST | privileged | optional `deleteAfter` |
| `activateAccount` | POST | privileged | refuses under a takedown |
| `checkAccountStatus` | GET | privileged | migration counters |
| `getServiceAuth` | GET | privileged | ES256K, ten-minute ceiling |
| `reserveSigningKey` | POST | none | seals a key for a migrating DID |
| `com.atproto.temp.checkSignupQueue` | GET | none | always activated |

## com.atproto.identity

| Method | Verb | Auth | Notes |
| --- | --- | --- | --- |
| `resolveHandle` | GET | none | local, then DNS `_atproto`, then well-known |
| `resolveDid` | GET | none | local accounts answered from live state |
| `resolveIdentity` | GET | none | requires the document to claim the handle back |
| `refreshIdentity` | POST | none | drops the cache, emits `#identity` |
| `updateHandle` | POST | privileged | external handles must already resolve to the DID |
| `getRecommendedDidCredentials` | GET | privileged | |
| `requestPlcOperationSignature` | POST | privileged | needs email configured |
| `signPlcOperation` | POST | privileged | signs with the stored rotation key |
| `submitPlcOperation` | POST | privileged | |

## com.atproto.repo

| Method | Verb | Auth | Notes |
| --- | --- | --- | --- |
| `createRecord` | POST | session | `swapCommit`; generates a TID key |
| `putRecord` | POST | session | `swapRecord`, `swapCommit` |
| `deleteRecord` | POST | session | `swapRecord`, `swapCommit` |
| `applyWrites` | POST | session | 1–200 writes, atomic |
| `getRecord` | GET | none | omits taken-down records |
| `listRecords` | GET | none | 1–100, forward or reverse, cursor by key |
| `describeRepo` | GET | none | handle, DID document, collections |
| `uploadBlob` | POST | session | bounded by `PDS_BLOB_MAX_SIZE` |
| `listMissingBlobs` | GET | session | referenced but not uploaded |
| `importRepo` | POST | privileged | verifies every block and the commit signature |

## com.atproto.sync

| Method | Verb | Auth | Notes |
| --- | --- | --- | --- |
| `getRepo` | GET | none | CARv1, `Atproto-Repo-Rev` |
| `getRepoStatus` | GET | none | active flag, status, revision |
| `getLatestCommit` | GET | none | |
| `getRecord` | GET | none | inclusion or exclusion proof as CAR |
| `getBlocks` | GET | none | 1–1000 CIDs |
| `getBlob` | GET | none | sandboxed content headers |
| `listBlobs` | GET | none | optional `since` revision |
| `listRepos` | GET | none | |
| `listReposByCollection` | GET | none | |
| `subscribeRepos` | WS | none | `#commit`, `#identity`, `#account`, `#sync` |

## com.atproto.admin

| Method | Verb | Auth | Notes |
| --- | --- | --- | --- |
| `getAccountInfo` | GET | admin | |
| `getAccountInfos` | GET | admin | up to 100 DIDs |
| `searchAccounts` | GET | admin | by email, or paginated |
| `updateAccountEmail` | POST | admin | |
| `updateAccountHandle` | POST | admin | |
| `updateAccountPassword` | POST | admin | raises the security epoch |
| `deleteAccount` | POST | admin | |
| `getInviteCodes` | GET | admin | |
| `disableInviteCodes` | POST | admin | codes or whole accounts |
| `disableAccountInvites` | POST | admin | |
| `enableAccountInvites` | POST | admin | |
| `getSubjectStatus` | GET | admin | account, record or blob |
| `updateSubjectStatus` | POST | admin | applies or lifts a takedown |
| `sendEmail` | POST | admin | needs email configured |

## app.bsky

| Method | Verb | Auth | Notes |
| --- | --- | --- | --- |
| `app.bsky.actor.getPreferences` | GET | privileged | never proxied |
| `app.bsky.actor.putPreferences` | POST | privileged | replaces the whole set |

Other `app.bsky.*`, `chat.bsky.*` and `tools.ozone.*` methods are proxied to the
configured AppView or to the service named by `atproto-proxy`. See [the service
proxy](/proxy/).

## Non-XRPC routes

| Path | Purpose |
| --- | --- |
| `GET /` | the ASCII banner |
| `GET /xrpc/_health`, `GET /_health` | liveness, with and without a database round trip |
| `GET /metrics` | Prometheus metrics, administrator credentials required |
| `GET /.well-known/did.json` | the service DID document |
| `GET /.well-known/oauth-authorization-server` | OAuth server metadata |
| `GET /.well-known/oauth-protected-resource` | OAuth resource metadata |
| `GET /u/<name>/did.json` | hosted `did:web` account documents |
| `POST /oauth/par` | pushed authorization requests |
| `GET /oauth/authorize` | starts the browser flow |
| `GET /oauth/flow/<id>`, `/state`; `POST /attach`, `/decide` | the consent flow |
| `POST /oauth/token`, `POST /oauth/revoke` | tokens |
| `GET /account`, `/account/app.js`, `/account/style.css` | the account interface |
| `GET /account/session`, `POST /account/action/*` | its backing API |

## Verification evidence

| Area | Checked against |
| --- | --- |
| Lexicon validation | upstream record-data fixtures, plus every catalog record root |
| Lexicon resolution | admission of a published schema graph, and a signed record proof from this server |
| WebAuthn | a virtual authenticator: real attestation and assertion round trips |
| Master-key rotation | every sealed value re-encrypted, with an aborting failure case |
| Managed key rotation | the head re-signed, exports verifying against the new document |
| Block collection | reachability preserved, idempotence, retention bound |
| Backups | checksum match, corruption, non-database files |
| Identifier syntax | upstream `handle`, `did`, `nsid`, `recordkey`, `tid`, `at-uri`, `datetime` fixtures |
| Data model and DAG-CBOR | upstream `data-model` fixtures: exact bytes and CIDs |
| Non-canonical CBOR | indefinite lengths, overlong integers, floats, unsorted and duplicate keys, unknown tags, trailing bytes |
| Merkle search tree | upstream `key_heights` and `common_prefix` fixtures; order-independent roots; interleaved writes against a reference map |
| Cryptography | upstream signature fixtures including high-S and DER rejection; W3C `did:key` vectors for both curves |
| Empty MST root | `bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm` |
| CAR archives | round trip, tampered block rejection, truncation, version rejection |
| HTTP flows | end-to-end suites for accounts, repositories, the account interface and OAuth |

One upstream "valid" NSID fixture exceeds the 253-character domain authority the
current [NSID specification](https://atproto.com/specs/nsid) sets; the test
preserves the fixture and asserts rejection.

## Not implemented

Externally signed PLC recovery forks and directory reconciliation, and an
end-to-end migration driver. Label *emission* belongs to a labeler rather than a
PDS; self-labels inside records are validated like any other field. See [the
roadmap](/roadmap/).

Nothing here has yet been run against an independent client or a real relay,
which is the honest gap between "passes its own tests" and "interoperates".

# Sessions and lifecycle

## Registration

`com.atproto.server.createAccount` validates before it writes anything:

- the handle must be syntactically valid, end with `PDS_USER_DOMAIN`, contain no
  extra label, be at least three characters, and not be a reserved name such as
  `admin`, `oauth`, `xrpc` or `www`;
- the password must be 8–1024 characters;
- an email address is required when email delivery is configured, and must be
  unique;
- an invite code is required and consumed when `PDS_INVITE_REQUIRED=true`.

It then generates a signing key and a rotation key, provisions the
[identity](/identity/), inserts the account, creates the genesis commit, and
emits `#identity`, `#account` and `#sync` events — all in one transaction. The
response carries a session pair and the account's DID document.

Handle and email uniqueness are re-checked inside that transaction, so two
simultaneous registrations cannot both win.

## Sessions

`com.atproto.server.createSession` accepts a handle, DID or email address with
either the account password or an app password. Unknown accounts cost the same
as wrong passwords: verification always runs scrypt, against a throwaway hash
when no account matched.

Tokens are HS256 JWTs keyed from the master key:

| Token   | Lifetime | Claims                                                               |
| ------- | -------- | -------------------------------------------------------------------- |
| access  | 2 hours  | `scope`, `sub`, `aud`, `epoch`, `iat`, `exp`, `jti`                  |
| refresh | 90 days  | `scope`, `sub`, `aud`, `epoch`, `jti` (the session id), `iat`, `exp` |

`aud` is the service DID, so a token issued by another PDS is refused. `epoch`
is the account's security epoch: raising it — on a password change, a password
reset or an administrative reset — invalidates every issued token at once,
because each request compares the claim against live account state.

`com.atproto.server.refreshSession` rotates the pair: the presented refresh
token's session row is deleted and a new one inserted. Presenting the old token
again fails, so a stolen refresh token is usable at most once and its use is
visible as a broken session for the legitimate client.

`com.atproto.server.deleteSession` removes the session. `getSession` reports the
handle, DID, email, confirmation state and active status.

## App passwords

`com.atproto.server.createAppPassword` returns a generated
`xxxx-xxxx-xxxx-xxxx` secret once and stores only its scrypt hash. Sessions
created with an app password carry a reduced scope and cannot create or list app
passwords, change email, deactivate the account, or read invite codes —
`requirePrivileged` refuses those with `InvalidToken`. A privileged app password
(`privileged: true`) keeps that access.

Revoking an app password also revokes the sessions it created.

## Email flows

Each flow issues a single-use code, stored only as a digest, that expires in
fifteen minutes; issuing a new code for the same purpose invalidates the
previous one.

| Purpose                 | Request                        | Confirm            |
| ----------------------- | ------------------------------ | ------------------ |
| Confirm an address      | `requestEmailConfirmation`     | `confirmEmail`     |
| Change an address       | `requestEmailUpdate`           | `updateEmail`      |
| Reset a password        | `requestPasswordReset`         | `resetPassword`    |
| Delete the account      | `requestAccountDelete`         | `deleteAccount`    |
| Sign in (second factor) | issued automatically           | `login/factor`     |
| Sign a PLC operation    | `requestPlcOperationSignature` | `signPlcOperation` |

`requestPasswordReset` answers the same way whether or not the address exists,
so it does not enumerate accounts. `resetPassword` raises the security epoch,
ending every existing session. `updateEmail` clears the confirmed flag and turns
off the email second factor, so a new address must be proven before it can guard
sign-in.

## Deactivation, deletion and takedown

| State         | Set by                                                       | Effect                                                                                                    |
| ------------- | ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------- |
| `active`      | default                                                      | everything works                                                                                          |
| `deactivated` | `deactivateAccount`, owner                                   | API access refused with `AccountDeactivated`; repository still exportable; `#account` event emitted       |
| `taken_down`  | `com.atproto.admin.updateSubjectStatus`                      | access refused with `AccountTakedown`; the prior status is remembered so lifting the takedown restores it |
| deleted       | `deleteAccount` (password and emailed code) or the admin API | blocks, blobs and the account row removed; `#account` event emitted                                       |

`activateAccount` reverses a deactivation but refuses while a takedown is in
force. `deactivateAccount` accepts an optional `deleteAfter` timestamp; the
background sweep removes accounts past it.

`com.atproto.server.checkAccountStatus` reports the current commit, revision,
block count, indexed records, stored preferences and expected versus imported
blobs — the counters a migration needs to confirm it is complete.

## Service tokens

`com.atproto.server.getServiceAuth` signs a short-lived ES256K token with the
account's repository key, for an audience DID and optionally a single method
(`lxm`). Lifetimes beyond ten minutes are refused with `BadExpiration`. The
[service proxy](/proxy/) mints these automatically.

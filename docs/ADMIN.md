# Admin and invites

The administrative API is enabled by setting `PDS_ADMIN_PASSWORD` (at least 12
characters). Requests authenticate with HTTP Basic as user `admin`, compared in
constant time. Without the variable set, every administrative method answers
`AuthenticationRequired`.

```sh
curl -sS -u "admin:$PDS_ADMIN_PASSWORD" \
  "https://pds.example.com/xrpc/com.atproto.admin.getAccountInfo?did=did:plc:…"
```

## Accounts

| Method | Purpose |
| --- | --- |
| `getAccountInfo` | one account: handle, email, confirmation, invites, deactivation |
| `getAccountInfos` | up to 100 accounts by DID |
| `searchAccounts` | by email address, or paginated by DID |
| `updateAccountEmail` | set an address and clear its confirmed flag |
| `updateAccountHandle` | set a handle, reserving it and emitting `#identity` |
| `updateAccountPassword` | set a password and raise the security epoch |
| `deleteAccount` | emit `#account` and remove the account, blocks and blobs |

`updateAccountPassword` is the operator's lockout recovery path: it ends every
session and OAuth token for the account.

## Invite codes

| Method | Purpose |
| --- | --- |
| `com.atproto.server.createInviteCode` | one code with a use count of 1–1000 |
| `com.atproto.server.createInviteCodes` | up to 100 codes each, for named accounts |
| `com.atproto.admin.getInviteCodes` | paginated codes with their uses |
| `com.atproto.admin.disableInviteCodes` | disable codes, or every code of an account |
| `com.atproto.admin.disableAccountInvites` | stop an account's codes from being usable |
| `com.atproto.admin.enableAccountInvites` | allow them again |
| `com.atproto.server.getAccountInviteCodes` | an account reading its own codes |

Codes look like `pds.example.com-<base32>`. Consumption is checked inside the
registration transaction against three conditions — not disabled, uses remaining,
and the owning account's invites not disabled — so a code cannot be
over-redeemed by concurrent signups. Each account may consume at most one code,
enforced by a unique constraint on the use.

With `PDS_INVITE_REQUIRED=true`, registration without a usable code fails with
`InvalidInviteCode`.

## Email

`com.atproto.admin.sendEmail` queues a message to an account's address through
the configured [email endpoint](/email/). It fails with `EmailUnavailable` when
none is configured, rather than reporting a delivery that will not happen.

## Moderation

Takedowns of accounts, records and blobs use
`com.atproto.admin.getSubjectStatus` and `updateSubjectStatus`. See
[moderation](/moderation/).

## Host commands

Some acts are deliberately not reachable over HTTP, because they need the
database rather than an API credential. They run on the host from the same jar:

| Command | Purpose |
| --- | --- |
| `serve` | run the server; the default |
| `rotate-master-key` | re-encrypt every sealed secret under `PDS_NEW_MASTER_KEY` |
| `verify-master-key` | check every sealed value opens, without writing |
| `recover-account <identifier> <reference>` | clear an account's second factors |
| `rotate-account-keys <identifier> [signing\|rotation\|both]` | replace an account's managed keys |
| `backup <path>` | checksummed SQLite snapshot, safe while running |
| `verify-backup <path>` | re-read a backup and check its checksum |

See [the master key](/master-key/) and [account
security](/account-security/).

## Operational notes

- Administrative requests are rate limited like everything else.
- Changing the administrator password takes effect on restart; it is not stored
  in the database.
- Nothing in the administrative API can read a sealed secret. Signing keys, TOTP
  secrets and rotation keys stay sealed under [the master
  key](/master-key/).

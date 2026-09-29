# Master key

One 32-byte key seals every secret the database holds. Losing it is not
recoverable: the accounts whose signing keys it protects can no longer sign
commits or publish identity operations.

## What it protects

| Secret | Sealed with purpose |
| --- | --- |
| Repository signing keys | `pds/signing-key/<curve>` |
| `did:plc` rotation keys | `pds/signing-key/<curve>` |
| TOTP secrets | `pds/totp` |

It also derives, without storing anything:

- the HS256 key for session access and refresh tokens;
- browser and OAuth CSRF tokens, bound to a per-session nonce;
- DPoP nonces, from a three-minute time window.

Sealing is AES-256-GCM with a fresh 12-byte nonce prepended to the ciphertext,
and the purpose string is authenticated as associated data — so a sealed TOTP
secret cannot be replayed as a signing key, and a value from another deployment
will not open.

## Generating one

```sh
openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
```

32 bytes, unpadded base64url — 43 characters. Anything else is refused at
startup.

## How it is provided

| Backend | Behaviour |
| --- | --- |
| PostgreSQL | `PDS_MASTER_KEY` is **required**; startup fails without it |
| SQLite | `PDS_MASTER_KEY` if set; otherwise generated once into `master.key` beside the database and reused |

The generated file is created with owner-only permissions, and the path is
printed at startup:

```
scala-pds: using the master key at data/master.key; back it up and set PDS_MASTER_KEY in production
```

That default exists so a development server works with no setup. Production
should pass the key through the environment from a secret manager, so it is not
sitting next to the data it protects.

## Handling

- Back it up **separately from the database**. A backup containing both gives an
  attacker everything; a backup of neither is useless.
- Keep the environment file at mode 600.
- Reuse the same key across every instance of one deployment: sessions, CSRF
  tokens and DPoP nonces are all derived from it, so instances with different
  keys will reject each other's tokens.
- Never reuse a key across deployments. The purpose binding limits the damage
  within a deployment; it does not isolate two servers sharing a key.

## Rotation

Rotation is an offline pass that re-encrypts every sealed value under a new key,
in one transaction: either all of them are readable under the current key and
rewritten, or nothing changes.

```sh
# Stop the server first: this rewrites the rows it reads at startup.
export PDS_MASTER_KEY="<the current key>"
export PDS_NEW_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"

java -jar scala-pds.jar rotate-master-key
```

```
scala-pds: re-encrypted 128 signing key(s), 128 rotation key(s) and 12 authenticator secret(s)
scala-pds: set PDS_MASTER_KEY to the new key before restarting; every session and OAuth token is now invalid
```

Then set `PDS_MASTER_KEY` to the new value and start the server again.

What rotation moves: repository signing keys, `did:plc` rotation keys and TOTP
secrets. What it does not need to move: session tokens, CSRF tokens and DPoP
nonces are *derived* from the key rather than stored, so changing it simply
invalidates them. Every account signs in again; repository signing keys are
unchanged, so no identity operation is needed and no commit has to be re-signed.

If any row fails to open with the current key — a value sealed by a different
deployment, or a partially restored backup — the pass aborts and nothing is
written. Check first, without writing anything:

```sh
java -jar scala-pds.jar verify-master-key
```

```
scala-pds: 268 sealed value(s) open with this master key
```

Keep the old key until the new one is confirmed working and backed up.

## Recovering from a lost key

There is no way to read a sealed value without the key. What survives:

- account rows, handles, email addresses and password hashes;
- repository blocks, records and blobs;
- the public halves of every key.

What does not: the ability to sign new commits or identity operations for those
accounts. Recovery means provisioning new signing keys and publishing them in
each DID document — which needs a rotation key, and those were sealed too.
An account that supplied its own `recoveryKey` at registration holds a rotation
key that outranks the server's and can recover independently.

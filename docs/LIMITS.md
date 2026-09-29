# Current limits

Every bound below is enforced, not aspirational.

## Records and repositories

| Limit | Value |
| --- | --- |
| Record size | 64 KiB encoded |
| Record key | 1–512 characters of `[A-Za-z0-9.:_~-]`, never `.` or `..` |
| Collection | a valid NSID: 3+ segments, ≤317 characters, ≤253-character authority |
| Writes per `applyWrites` | 1–200 |
| Records per `listRecords` | 1–100, default 50 |
| Blocks per `getBlocks` | 1–1000 |
| JSON nesting | 32 levels |
| CBOR nesting | 64 levels |
| CAR block | 4 MiB |
| CAR blocks per archive | 200 000 |
| `importRepo` archive | 256 MiB |

Integers must fit a signed 64-bit value, and floats are rejected outright —
except a float that is exactly integral, which the data model accepts as an
integer.

## Blobs

| Limit | Value |
| --- | --- |
| Maximum size | `PDS_BLOB_MAX_SIZE`, default 5 MiB, range 1 KiB–100 MiB |
| Minimum size | one byte; empty uploads are refused |
| `listBlobs`, `listMissingBlobs` | 1–1000, default 500 |

## Accounts

| Limit | Value |
| --- | --- |
| Handle | valid domain, ends with `PDS_USER_DOMAIN`, one extra label, ≥3 characters, not reserved |
| Password | 8–1024 characters |
| Email address | ≤320 characters |
| Identifier at sign-in | 1–2048 characters |
| App passwords per account | 100 |
| App password name | 1–64 characters, unique per account |
| Invite code uses | 1–1000 per code |
| Invite codes per batch | 100 |
| Invite codes consumed per account | 1 |
| Preferences | 1000 entries |

## Tokens and sessions

| Credential | Lifetime |
| --- | --- |
| Session access token | 2 hours |
| Session refresh token | 90 days, rotated on use |
| OAuth access token | 1 hour |
| OAuth refresh token | 90 days, rotated on use |
| Service auth token | ≤10 minutes |
| Email token | 15 minutes, single use |
| Browser session | 5 minutes absolute |
| OAuth pushed request | 5 minutes, single use |
| OAuth interaction | 10 minutes |
| OAuth authorization code | 60 seconds, single use |
| DPoP proof `iat` window | ±30 seconds |
| DPoP nonce window | 3 minutes, current and previous accepted |
| Identity cache entry | 10 minutes |

## Second factors

| Limit | Value |
| --- | --- |
| TOTP | SHA-1, 6 digits, 30-second step, ±1 step accepted |
| TOTP enrollment window | 10 minutes |
| TOTP failures | 5 within 5 minutes, then `RateLimitExceeded` |
| Recovery codes | 8, single use, 26 characters |

A TOTP step at or below the last accepted one is refused, so an observed code
cannot be replayed inside its window.

## HTTP

| Limit | Value |
| --- | --- |
| Rate limit | `PDS_RATE_LIMIT_PER_MINUTE`, default 300 per address per minute |
| JSON request body | 512 KiB |
| Form body (OAuth) | 64 KiB |
| Proxied response | 20 MiB |
| Outbound document fetch | 128 KiB |
| Outbound request timeout | 15 seconds |
| Idle connection timeout | 75 seconds |

Rate-limit counters are in-process, so each instance enforces its own share.

## Firehose

| Limit | Value |
| --- | --- |
| Backfill batch | 1000 events |
| Retention window | `PDS_FIREHOSE_RETENTION_HOURS`, default 72 hours |
| Poll interval | 500 ms |
| Keep-alive ping | 30 seconds |

## Crypto

| Choice | Value |
| --- | --- |
| Repository signing curves | secp256k1 (`ES256K`) and P-256 (`ES256`) |
| Signature encoding | 64 raw bytes, low-S enforced, RFC 6979 deterministic |
| DPoP and client assertions | `ES256` only |
| Session tokens | HS256, keyed from the master key |
| Password hashing | scrypt, N=16384, r=8, p=1, 16-byte salt |
| Secret sealing | AES-256-GCM with an authenticated purpose string |
| CID | CIDv1, SHA-256, `dag-cbor` or `raw` only |

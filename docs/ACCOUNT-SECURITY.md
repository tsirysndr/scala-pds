# Account security

Everything on this page happens in the browser interface, never over the XRPC
API, because it is guarded by an owner session that a client application cannot
obtain.

## Browser sessions

`GET /account/session` opens or resumes a short-lived browser session and
returns the current view. The session token lives only in a cookie —
`__Host-pds-security` over HTTPS, `pds-security` on loopback — marked
`HttpOnly`, `SameSite=Lax`, `Secure` when the public URL is HTTPS, with a
**five-minute absolute lifetime**. A settings page therefore cannot outlive the
sign-in that opened it.

Only a digest of the token is stored. The session progresses through three
stages:

| Stage | Meaning |
| --- | --- |
| `login` | anonymous; the identifier and password have not been accepted |
| `factor` | the password was accepted and a second factor is outstanding |
| `authenticated` | every factor passed; management actions are permitted |

The token is rotated on every stage change and whenever a mutation raises the
account's security epoch, so a captured cookie stops working as soon as the real
owner continues.

## CSRF and origin binding

The view carries a `csrf` value derived from the session's stored nonce and the
token itself, keyed by the master key. Every `POST /account/action/*` must
present it in `X-CSRF-Token`; it is compared in constant time.

Requests must also carry `Origin: <PDS_PUBLIC_URL>`, a `Sec-Fetch-Site` of
`same-origin` when the browser sends one, and a JSON content type. A
cross-origin post is refused with `InvalidOrigin` before any state is read.

## Response headers

The account interface is served under a policy that allows no third-party
resources at all:

```
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self';
  connect-src 'self'; img-src 'self' data:; form-action 'self';
  frame-ancestors 'none'; base-uri 'none'
Cache-Control: no-store
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
```

## Authenticator app (TOTP)

RFC 6238, SHA-1, six digits, a thirty-second step, accepting the neighbouring
steps for clock drift.

1. `totp/begin` seals a fresh 160-bit secret, stores it unconfirmed with a
   ten-minute enrollment window, and returns the base32 secret and an
   `otpauth://` URI.
2. `totp/confirm` verifies a code from that secret, marks the factor confirmed,
   and returns **eight single-use recovery codes**, stored only as digests.
3. `totp/disable` requires a current code or a recovery code.

Verification refuses a step at or below the last accepted one, so an observed
code cannot be replayed within its window. Five consecutive failures inside a
five-minute window return `RateLimitExceeded`.

Once confirmed, password sign-in stops at the `factor` stage until a code — or a
recovery code, which is consumed — is presented.

## Email second factor

When email delivery is configured and the address is confirmed, `email/enable`
turns on a one-time code at sign-in. The code is issued during the password step
and consumed at the `factor` step. `email/disable` turns it off, clears any
outstanding codes and revokes existing API sessions. Changing the email address
turns the factor off, because the new address is unproven.

## Password changes

`password/change` requires the current password, applies the new one, and raises
the security epoch — ending every API session and OAuth token. The browser
session that performed the change is rotated forward rather than invalidated, so
the owner is not signed out of the page they are working in.

## Reviewing access

The authenticated view lists:

- app passwords, with their names, privilege and creation dates;
- connected OAuth applications with their granted permissions, each revocable
  individually;
- how many TOTP recovery codes remain.

## Lockout recovery

Losing every factor is recovered by an operator with `PDS_ADMIN_PASSWORD`:

```sh
curl -sS -X POST https://pds.example.com/xrpc/com.atproto.admin.updateAccountPassword \
  -u "admin:$PDS_ADMIN_PASSWORD" -H 'content-type: application/json' \
  -d '{"did":"did:plc:…","password":"a new strong password"}'
```

That raises the security epoch and ends every session. It does not clear a TOTP
enrollment; removing one requires database access, which is deliberate.

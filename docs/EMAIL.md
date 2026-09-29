# Email delivery

scala-pds holds no SMTP credentials. It writes messages to a durable outbox and
POSTs them as JSON to an endpoint you control — a Cloudflare Worker, a small
Lambda, anything that can talk to your mail provider.

## Configuration

| Variable             | Meaning                                                                  |
| -------------------- | ------------------------------------------------------------------------ |
| `PDS_EMAIL_ENDPOINT` | HTTPS endpoint that accepts the JSON message                             |
| `PDS_EMAIL_TOKEN`    | sent as `Authorization: Bearer <token>`                                  |
| `PDS_EMAIL_FROM`     | sender address, when the endpoint does not own sender identity itself    |

With no endpoint configured, every flow that needs mail refuses with
`EmailUnavailable` instead of reporting a delivery that will not happen. That
includes email confirmation, password reset, account deletion, the email second
factor, PLC operation signatures and `com.atproto.admin.sendEmail`.

## The contract

A message on the wire is exactly three fields, with the row's identifier as an
idempotency key:

```
POST $PDS_EMAIL_ENDPOINT
Authorization: Bearer $PDS_EMAIL_TOKEN
Idempotency-Key: 6f1c7f94-2e7a-4a1a-9c0e-3f1d2b7a5c88

{
  "to": "alice@example.com",
  "subject": "Reset your pds.example.com password",
  "text": "Use this code to set a new password:\n\n    ABCDEF-GHIJK\n\n…"
}
```

The endpoint therefore owns sender identity and provider credentials, and nothing
else. The subject and body are rendered here, per purpose:

| `purpose`        | Sent when                                                                      |
| ---------------- | ------------------------------------------------------------------------------ |
| `confirm-email`  | the owner asked to confirm their address                                       |
| `update-email`   | the owner asked to change their address                                        |
| `reset-password` | a password reset was requested                                                 |
| `delete-account` | account deletion was requested                                                 |
| `sign-in`        | a second-factor code is needed to finish sign-in                               |
| `plc-operation`  | an identity operation needs confirmation                                       |
| `admin-notice`   | an operator sent a message; carries `subject` and `content` instead of `token` |

Any 2xx response marks the message sent; anything else is a failure. Retries
repeat the same `Idempotency-Key`, so an endpoint that honours it will not deliver
the same message twice after an ambiguous failure. A message that cannot be
rendered — an unknown purpose, or a notice with no body — is failed outright
rather than sent half-formed.

This is the shape a Cloudflare Worker mail relay expects, so the same worker can
serve several servers.

## The outbox

Messages are rows, written in the same transaction as the action that caused them
— so a token is never emailed for a state change that was rolled back. A
background worker drains up to twenty pending messages every thirty seconds.

Failures are retried with exponential backoff (30s, 60s, 120s, 240s, capped at an
hour) and give up after five attempts, recording the error. Nothing is retried
forever and nothing is silently dropped.

## Tokens

Codes are six base32 characters, a hyphen, and five more —
`ABCDEF-GHIJK`. Only a digest is stored, they expire after fifteen minutes, they
are single use, and issuing a new code for the same purpose invalidates the
previous one. Verification checks the purpose, the account and the address the
token was issued for, so a code for one flow cannot be replayed into another.

## Not enumerating accounts

`com.atproto.server.requestPasswordReset` answers identically whether or not the
address belongs to an account, and queues a message only when it does.

## Running without email

Everything except the email-dependent flows works with no endpoint configured:
registration (when no address is required), sessions, app passwords,
repositories, blobs, OAuth and the firehose. The gaps are address confirmation,
self-service password reset, self-service deletion and the email second factor.
The authenticator factor needs no mail at all, and an operator can always reset a
password through [the admin API](/admin/).

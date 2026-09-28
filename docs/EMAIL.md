# Email delivery

scala-pds holds no SMTP credentials. It writes messages to a durable outbox and
POSTs them as JSON to an endpoint you control — a Cloudflare Worker, a small
Lambda, anything that can talk to your mail provider.

## Configuration

| Variable | Meaning |
| --- | --- |
| `PDS_EMAIL_ENDPOINT` | HTTPS endpoint that accepts the JSON message |
| `PDS_EMAIL_TOKEN` | sent as `Authorization: Bearer <token>` |
| `PDS_EMAIL_FROM` | sender address included in the payload; defaults to `noreply@<hostname>` |

With no endpoint configured, every flow that needs mail refuses with
`EmailUnavailable` instead of reporting a delivery that will not happen. That
includes email confirmation, password reset, account deletion, the email second
factor, PLC operation signatures and `com.atproto.admin.sendEmail`.

## The contract

```json
{
  "to": "alice@example.com",
  "from": "noreply@pds.example.com",
  "purpose": "reset-password",
  "token": "ABCDEF-GHIJKL"
}
```

| `purpose` | Sent when |
| --- | --- |
| `confirm-email` | the owner asked to confirm their address |
| `update-email` | the owner asked to change their address |
| `reset-password` | a password reset was requested |
| `delete-account` | account deletion was requested |
| `sign-in` | a second-factor code is needed to finish sign-in |
| `plc-operation` | an identity operation needs confirmation |
| `admin-notice` | an operator sent a message; carries `subject` and `content` instead of `token` |

Your endpoint owns the wording. Any 2xx response marks the message sent;
anything else is a failure. It should be idempotent on `purpose` and `to`,
because a retry can deliver the same token twice.

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

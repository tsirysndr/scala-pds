# Service proxy

A PDS stores data; feeds, notifications and search live in an AppView. Rather
than requiring clients to authenticate separately, scala-pds proxies those
queries and signs them on the account's behalf.

## Choosing the upstream

| Request                               | Target                                           |
| ------------------------------------- | ------------------------------------------------ |
| `atproto-proxy: <did>#<service id>`   | that service, resolved from the DID document     |
| no header, `com.atproto.moderation.*` | `PDS_MOD_SERVICE_URL` with `PDS_MOD_SERVICE_DID` |
| no header, anything else              | `PDS_APPVIEW_URL` with `PDS_APPVIEW_DID`         |

The header must be a DID and a non-empty fragment. The DID is resolved and the
matching `service` entry supplies the endpoint, so the client names *who* it
wants, not *where* they are. With no header and no AppView configured, the
request is answered `NotImplemented`.

## What gets proxied

Any `app.bsky.*`, `chat.bsky.*`, `tools.ozone.*` or `com.atproto.moderation.*`
method this server does not implement itself. Methods it does implement —
including `app.bsky.actor.getPreferences` and `putPreferences`, which are private
to the PDS — are always answered locally.

`com.atproto.moderation.createReport` goes to the moderation service rather than
the AppView, and requires a session: a report names the account making it, so it
cannot be sent anonymously. Without a moderation service configured the answer is
`NotImplemented` rather than a silently dropped report.

## Authentication

When the request carries a session, the proxy mints a fresh inter-service token
signed with the account's repository key:

```json
{ "iss": "<account did>", "aud": "<service did>", "lxm": "<method>",
  "iat": …, "exp": …, "jti": "…" }
```

It is valid for sixty seconds and names the single method being called, so a token
captured upstream cannot be reused for anything else. Anonymous requests are
forwarded without one.

## Header handling

The client's `Authorization`, `DPoP` and `Cookie` headers are **never** forwarded:
they authenticate the client to this server, not to the AppView, and the
inter-service token replaces them. Hop-by-hop headers are stripped in both
directions. Everything else — `atproto-accept-labelers`, `accept-language` — is
passed through, and the upstream status, body and remaining headers are returned
unchanged.

Bodies are bounded in both directions: 512 KiB out, 20 MiB back.

## Failure

The proxy does not invent success. A DID that will not resolve, or a service
fragment that is not in the document, is an `InvalidRequest`; an unreachable
upstream surfaces as an error rather than an empty result.

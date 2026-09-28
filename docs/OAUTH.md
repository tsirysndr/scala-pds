# OAuth

scala-pds is a complete AT Protocol OAuth authorization server: pushed
authorization requests, PKCE, DPoP-bound tokens, client metadata documents and a
consent screen that the account owner drives.

## Discovery

| Path | Document |
| --- | --- |
| `/.well-known/oauth-authorization-server` | issuer, endpoints, supported scopes and algorithms |
| `/.well-known/oauth-protected-resource` | the resource identifier and its authorization server |

Both advertise `require_pushed_authorization_requests`, `S256` as the only code
challenge method, `ES256` for DPoP, and `client_id_metadata_document_supported`.
The issuer is exactly `PDS_PUBLIC_URL`, which is why that value must be a
canonical origin.

## Clients

A `client_id` is an HTTPS URL that serves the client's own metadata document.
The document must declare its own `client_id` identically, list its
`redirect_uris`, support the `authorization_code` grant, use only the `code`
response type, and set `dpop_bound_access_tokens: true`. Redirect targets must
be HTTPS, a loopback address, or a custom scheme.

Nothing in the document is treated as trusted branding: it constrains redirect
targets, scopes and the authentication method, and the consent screen shows the
`client_id` itself rather than a name the client chose for itself.

`token_endpoint_auth_method` may be `none` or `private_key_jwt`. For
`private_key_jwt`, assertions are verified against the document's `jwks` or
`jwks_uri`, must name the client as both issuer and subject, must name this
server as audience, must expire within ten minutes, and each `jti` is accepted
once.

Development clients may use the loopback form `http://localhost?redirect_uri=…`,
whose redirect must itself be `http://127.0.0.1…` or `http://[::1]…`.

## The flow

```
POST /oauth/par          → request_uri            (DPoP proof with a nonce)
GET  /oauth/authorize    → 303 /oauth/flow/<id>   (sets the browser cookie)
GET  /oauth/flow/<id>         the consent page
GET  /oauth/flow/<id>/state   the flow view as JSON
POST /oauth/flow/<id>/attach  binds the signed-in owner
POST /oauth/flow/<id>/decide  → the redirect, with a code or access_denied
POST /oauth/token        → access and refresh tokens
POST /oauth/revoke       → 200, idempotent
```

### Pushed authorization requests

Every authorization starts at `/oauth/par`, so the browser never carries request
parameters a client could tamper with. The endpoint requires `response_type=code`,
a registered `redirect_uri`, `code_challenge` with `code_challenge_method=S256`,
a `state` of 8–512 characters, and a scope containing `atproto`. It returns a
`urn:ietf:params:oauth:request_uri:…` reference valid for five minutes and usable
once. A DPoP proof is required and must carry a server nonce; the first attempt
without one is answered `use_dpop_nonce` with a fresh `DPoP-Nonce` header. When
the proof is present, its key thumbprint is remembered and the authorization code
is bound to it.

### The browser stage

`/oauth/authorize` accepts exactly `client_id` and `request_uri`, consumes the
pushed request, and redirects to `/oauth/flow/<id>` while setting an
`HttpOnly`, `SameSite=Lax` cookie. The URL holds only a random, **non-secret**
interaction identifier; the secret is in the cookie, so a leaked or logged URL
cannot be replayed from another browser.

`attach` binds an authenticated [browser owner](/account-security/) to the flow.
It requires both the flow CSRF token and the account session's own CSRF token, so
consent cannot be forged from an existing sign-in. When the client sent a
`login_hint`, the signed-in account must match it, otherwise the flow fails with
`access_denied`.

`decide` records the decision once. Approval stores a single-use authorization
code — digest only, sixty-second lifetime, bound to the DID, client, scope,
redirect URI, PKCE challenge, DPoP thumbprint and the account's security epoch —
and returns the redirect. Denial returns a redirect carrying
`error=access_denied`. A second decision on the same flow is refused.

### Tokens

`/oauth/token` requires a DPoP proof with a nonce. For `authorization_code` it
checks that the code exists, is unconsumed and unexpired, was issued to this
client and redirect URI, that `S256(code_verifier)` equals the stored challenge,
that the proof key matches the one bound at PAR time, and that the account's
security epoch has not moved. Replaying a consumed code revokes every token that
client holds for the account — a stolen code cannot be used quietly.

Access and refresh tokens are opaque 256-bit secrets stored only as digests:

| Token | Lifetime | Notes |
| --- | --- | --- |
| access | 1 hour | `token_type: DPoP`, bound to the proof key |
| refresh | 90 days | rotated on every use; the old row is deleted |

`/oauth/revoke` accepts either token of a pair and is idempotent.

## DPoP

Proofs are `dpop+jwt` JWTs signed with `ES256` over an embedded public JWK
(RFC 9449). Each is checked for:

- the declared algorithm matching the key, and a valid signature;
- `htm` equal to the request method;
- `htu` equal to `PDS_PUBLIC_URL` plus the request path, with no query;
- `iat` within thirty seconds of server time;
- `ath` equal to the base64url SHA-256 of the presented access token, when one is
  presented;
- a server `nonce` — required at the protocol endpoints, optional at resource
  endpoints;
- a `jti` not seen before, recorded per key for twice the accepted window.

Nonces are derived from the master key and a three-minute window; the current and
previous windows are both accepted. `DPoP-Nonce` is returned on OAuth responses
*and on rejections*, so a client always learns the value it needs.

## Using an access token

```
Authorization: DPoP <access token>
DPoP: <proof with ath for that token>
```

A missing proof is refused. The token's digest is looked up, its expiry,
revocation and the account's security epoch are checked, and the stored key
thumbprint is compared in constant time against the proof. The resulting session
carries its granted scope, and a scoped session may only call the methods its
scope permits.

An unauthorized XRPC response carries the discovery challenge:

```
WWW-Authenticate: DPoP resource_metadata="https://pds.example.com/.well-known/oauth-protected-resource"
```

## Scopes

`atproto` is mandatory and identifies the account. Beyond it:

| Scope | Grants |
| --- | --- |
| `transition:generic` | read and write the repository and account data |
| `transition:chat.bsky` | the `chat.bsky.*` methods |
| `transition:email` | the account's email address |

Granular `repo:`, `rpc:`, `blob:`, `account:`, `identity:` and `include:`
permissions are accepted and rendered on the consent screen. Method access is
checked per request: `transition:generic` allows everything, `rpc:<nsid>` (or
`rpc:*`) allows that method, and `transition:chat.bsky` allows the chat
namespace.

## Revoking access

An owner can revoke any connected application from `/account`, which marks its
tokens revoked. Any change that raises the account's security epoch — a password
change or reset — revokes every OAuth token at the same time.

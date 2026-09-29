# Account interface

`/account` serves a React single-page application that handles sign-in, account
settings and the OAuth consent screen. The server exposes exactly three asset
paths, so the strict content security policy never needs to widen:

| Path                 | Contents                |
| -------------------- | ----------------------- |
| `/account`           | the HTML shell          |
| `/account/app.js`    | the bundled application |
| `/account/style.css` | the bundled stylesheet  |

## Stack

| Concern         | Library                                   |
| --------------- | ----------------------------------------- |
| Rendering       | React 19                                  |
| Styling         | Tailwind CSS 4 with a HeroUI theme plugin |
| Components      | HeroUI                                    |
| Local state     | Jotai                                     |
| Server state    | TanStack React Query                      |
| Forms           | React Hook Form                           |
| Validation      | Zod, through `@hookform/resolvers`        |
| Icons           | Tabler Icons                              |
| Build and tests | Vite, Vitest, Testing Library             |

The bundle loads no third-party resources at runtime: the theme stays on locally
installed monospace fonts, because the account policy grants no `font-src`.

## Screens

| Screen                      | When it shows                                                                          |
| --------------------------- | -------------------------------------------------------------------------------------- |
| Sign in                     | the session stage is `login`                                                           |
| Create account              | the visitor chose signup, or a client asked for `prompt=create`, and signup is enabled |
| Confirm it's you            | the stage is `factor`; an authenticator or emailed code is outstanding                 |
| Authorize access            | an OAuth flow is attached to an authenticated owner                                    |
| Continue to the application | an OAuth flow exists and the owner is already signed in                                |
| Your account                | an authenticated owner with no OAuth flow in progress                                  |
| Message                     | a flow expired, or the session could not load                                          |

The account settings screen manages app passwords, authenticator enrollment with
its recovery codes, the email second factor, the account password, and
revocation of connected applications.

## Contract

React Query owns the two server resources. `GET /account/session` returns the
whole view — stage, CSRF token, handle, outstanding factor, app passwords,
connected applications — merged with the server's policy (`signup-enabled`,
`invite-required`, `user-domain`, `origin`). Every `POST /account/action/<name>`
responds with the *fresh* view plus a `result` object, so one round trip both
performs the action and refreshes the page; the mutation writes that straight
into the query cache.

```
GET  /account/session
POST /account/action/login/password       { identifier, password }
POST /account/action/login/factor         { code }
POST /account/action/signup               { handle, email, password, inviteCode? }
POST /account/action/logout
POST /account/action/app-passwords/create { name, privileged }
POST /account/action/app-passwords/revoke { name }
POST /account/action/oauth/revoke         { id }
POST /account/action/passkeys/begin        { name }
POST /account/action/passkeys/finish       { id, response }
POST /account/action/passkeys/remove       { id }
POST /account/action/login/passkey/begin   { identifier }
POST /account/action/login/passkey/finish  { id, response }
POST /account/action/totp/begin
POST /account/action/totp/confirm         { code }
POST /account/action/totp/disable         { code }
POST /account/action/email/enable
POST /account/action/email/disable
POST /account/action/password/change      { currentPassword, newPassword }
```

The OAuth flow adds `GET /oauth/flow/<id>/state`, `POST /oauth/flow/<id>/attach`
and `POST /oauth/flow/<id>/decide`. See [OAuth](/oauth/).

Every action carries `X-CSRF-Token` from the current view and is rejected
cross-origin. See [account security](/account-security/).

## Validation

Zod schemas validate in the browser before anything is posted: identifier and
password presence, username shape and length, email format, an eight-character
password minimum, a matching password confirmation, and the six-digit or
26-character recovery code shape. The server validates the same rules again —
the browser checks are for fast feedback, never for enforcement. Server error
codes are mapped to plain sentences rather than shown raw.

## Working on it

```sh
cd frontend
npm install
npm run dev     # Vite, proxying /account, /oauth and /xrpc to a local PDS
npm test        # Vitest and Testing Library
npm run build   # writes ../src/main/resources/ui/{app.js,style.css}
```

The build output is committed, so the Scala build and the container image need no
Node toolchain; rebuild and commit it whenever the interface changes.

The tests drive real user interaction — typing, clicking, waiting — against a
scripted `fetch`, and assert both what the user sees and exactly what was posted.

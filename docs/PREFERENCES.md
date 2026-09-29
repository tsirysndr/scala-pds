# Preferences

Bluesky client preferences — content filters, saved feeds, labeler choices — are
private account state. They are stored on the PDS and never proxied to an
AppView.

## Endpoints

| Method                          | Behaviour                          |
| ------------------------------- | ---------------------------------- |
| `app.bsky.actor.getPreferences` | returns this account's preferences |
| `app.bsky.actor.putPreferences` | replaces the whole set             |

Both require an authenticated session with full privileges: an app password
session is refused, because preferences can reveal moderation and content
choices.

## Storage

Each preference is a JSON object identified by its Lexicon `$type`, stored under
the `app.bsky` scope keyed by that type. `putPreferences` replaces the set
atomically; it does not merge, so a client must send the complete list it wants
to keep.

Validation happens before anything is written:

- every entry needs a `$type`;
- the `$type` must be a Lexicon reference, so its namespace is a valid NSID;
- types must be distinct within one request;
- at most 1000 preferences are accepted.

```json
{
  "preferences": [
    { "$type": "app.bsky.actor.defs#adultContentPref", "enabled": false },
    { "$type": "app.bsky.actor.defs#savedFeedsPrefV2", "items": [] }
  ]
}
```

## Isolation

Preferences are keyed by DID and readable only by that account's own sessions.
The test suite asserts that a second account reading its preferences sees an
empty list rather than another account's, and that an unauthenticated read is
refused.

They are counted as `privateStateValues` by
`com.atproto.server.checkAccountStatus`, which is how a
[migration](/migration/) confirms they were carried across, and they are deleted
with the account.

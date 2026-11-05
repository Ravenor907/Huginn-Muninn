# Security

## Reporting a problem

Please report security problems privately, not in a public issue:

1. Open https://github.com/Ravenor907/Huginn-Muninn/security/advisories/new
   (or the repository's **Security** tab, then **Report a vulnerability**).
2. Say what you found, which app and version, and how to make it happen.

Only you and the maintainer can see the report. There is no email address for this; GitHub's private
reporting is the one way in.

## What's in scope

- Huginn (Windows and Linux) and Muninn (Android and Google TV), the latest release.
- Their encryption and the wire protocol ([code/shared/docs/protocol.md](../code/shared/docs/protocol.md)): reading, changing
  or faking messages, calls, files or contacts you shouldn't be able to.
- Anything that leaks data the [Privacy](README.md#privacy-at-a-glance) section says stays private.
- The updaters: getting either app to install something that isn't a signed release
  ([code/shared/docs/releases.md](../code/shared/docs/releases.md)).
- The relay Worker in `code/shared/relay-worker/` and the add page in `code/shared/site/add/`.

Out of scope: problems in Nostr relays, Cloudflare, GitHub, Android or Windows themselves; someone who
already controls your unlocked device; and denial of service by flooding.

## What to expect

This is a small project run by one person, so there is no fixed timetable. You should hear back within a
week or two. A real problem gets fixed in the next release, and you'll be asked how you'd like to be
credited (or not) in the release notes. Please give a reasonable time for the fix to go out before talking
about it publicly.

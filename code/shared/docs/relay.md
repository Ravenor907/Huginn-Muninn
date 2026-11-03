# Relay credentials (app side)

Over the internet, calls go through Cloudflare TURN relays (code/shared/docs/protocol.md 5.1 and 5.6). The one
exception is two people who trust each other (5.8.1, the Family folder or the "Direct calls" switch): their
apps also try a direct path, which costs the relay nothing (5.8.2). The apps never hold the Cloudflare TURN
token. They ask the relay Worker (`code/shared/relay-worker/worker.js`) for a short-lived TURN login instead, and get
TURN URLs from it, never STUN URLs.

## STUN (trusted contacts only)

For the direct path each app asks `stun.cloudflare.com:3478` (Cloudflare's free STUN service, no login,
nothing to do with the Worker) what its call socket's public address is: one Binding request from the call
socket, only in a call with someone it trusts, never otherwise. The answer goes only to that person, in the
offer or answer (`srflx`). The STUN server sees your public address, as the TURN server does. Nothing here
changes the Worker or its daily caps.

## Data the relay carries

- Sound: about 30 to 40 kbps each way (24 kbps Opus plus headers between two `c2` apps, 16 kbps when
  either app lacks `c2`). Nothing while a free path (Wi-Fi or direct) carries the call: the relay only gets a ping a
  second then (5.8.3).
- Video: 480p, at most 700 kbps, about 0.4 GB an hour with the sound (5.8.6). On Wi-Fi or a direct path it
  may go up to 720p, without the relay.

## Constants

The Worker URL and `APP_SECRET` live in one constants file per app, and nowhere else:

- Huginn: `huginn/src/call/relayconfig.h` (`RelayConfig::kWorkerUrl`, `RelayConfig::kAppSecret`)
- Muninn: `muninn/app/src/main/java/io/github/ravenor907/muninn/net/call/RelayConfig.kt`
  (`RelayConfig.WORKER_URL`, `RelayConfig.APP_SECRET`)

```
WORKER_URL = "https://huginn-relay.ravenor907.workers.dev"
APP_SECRET = "<64 hex chars, same value as the Worker secret>"
```

`APP_SECRET` is not a real secret (anyone can pull it out of an app). It only stops casual scripts. The real
protection is the Worker's budgets ("Limits" below), and since 1.4 the install tokens below: whoever has `APP_SECRET`
can use up only the small pool of logins without a token, never the logins of installs that have one.

## Request

`POST {WORKER_URL}/turn`, `Content-Type: application/json`, over HTTPS.

```json
{"id": "<hex>", "t": 1800000000, "sig": "<hex>", "tok": "<token>", "rn": 1}
```

`tok` (1.36) is the install token (below), left out when the install has none; older apps never send it.
`rn` (round 17) says the app renews its relay before the login ends ("Renewal" below); it is the JSON number `1` (or `true`)
when sent, and any other value is a 400. Only `/turn` carries it, never `/register`.

- `id`: lowercase hex SHA-256 (64 chars) of a random 32-byte relay id that each install makes once and keeps in
  its vault (`relayId` in `secrets.json`).
  Huginn `RelayCredentials::body`, Muninn `TurnCreds.body`; both self-tests check the same example.
- `t`: current Unix time in whole seconds (a JSON integer, not a string).
- `sig`: lowercase hex HMAC-SHA256 with key `APP_SECRET` (the UTF-8 bytes of the string) over the UTF-8
  string `id + "." + t`, for example `aaaa...aaaa.1800000000` (the 64-char id, a dot, then the digits of t).

The Worker rejects `t` more than 300 s away from its clock. If the device clock is badly off, the request fails
with 401 `stale`, and (1.36) its body says the Worker's time: `{"error":"stale","now":1800000000}`. The app then
tries once more with `t` corrected by that offset (and keeps the offset for its next requests); the signature still
needs `APP_SECRET`, so nothing new is exposed.

## Install tokens (1.36)

The per-install cap used to be keyed by an `id` the app makes up, so a script with `APP_SECRET` could make a new `id`
for every request and, from enough addresses, use up the day's logins for everyone. Now an install registers its `id`
once, with a proof of work, and gets a short-lived token the Worker signs:

`POST {WORKER_URL}/register`, the same body as `/turn` plus `pow`:

```json
{"id": "<hex>", "t": 1800000000, "sig": "<hex>", "pow": 465984}
```

- `pow`: a JSON integer >= 0 such that SHA-256 of the UTF-8 string `"huginn-relay pow v1:" + id + ":" + t + ":" + pow`
  (pow in decimal) starts with **18 zero bits**. About 260,000 tries on average: well under a second on a phone,
  on a thread of its own. It is bound to `t`, so it can't be worked out ahead of time beyond the 300 s skew. Vector:
  `id` = 64 `a`, `t` = 1800000000: the smallest `pow` is 465984 (the Worker's test, Muninn `TurnTest`, Huginn
  `--call-selftest`).
- 200: `{"tok": "<exp>.<since>.<32 hex>", "exp": <unix seconds>}`, good for 7 days. `since` is when the install
  registered (Unix seconds); every token a login renews keeps it. The token is HMAC-SHA256 over
  `"tok2:" + id + ":" + exp + ":" + since`, keyed with the Worker secret `TOKEN_KEY` when set, else with a key made from
  `TURN_KEY_API_TOKEN`, cut to 16 bytes. Changing either makes every install register again, nothing worse. Tokens in the
  1.36 form `<exp>.<32 hex>` (HMAC over `"tok:" + id + ":" + exp`) are still taken, as registered `exp` less 7 days,
  and come back in the new form. The apps treat the token as opaque text either way.
- 401 `bad_pow` (the work doesn't check out), 401 `stale` and `bad_sig` as for `/turn`, 429 `ip_reg_limit` (6
  registrations a day per address group: IPv4 /24, IPv6 /48) or `reg_limit` (2000 a day for everyone). A full day
  refuses new registrations only, never logins.

The app keeps the token outside the vault, with the `id` it is for (Muninn: `relayToken` in its settings, Huginn:
`relay/token`), as `id|tok|exp`: it only spends this install's own logins. It sends it on every `/turn`. Every 200
from `/turn` with a token carries a new `tok` and `exp` (7 days from then), which replace the kept one, so an install
that calls now and then never registers again. With less than a day left, or none, the app registers before asking.
A 401 `bad_token` or `token_expired` drops the token, and the app registers once more and asks again. When
registering fails (an older Worker answers 404, a 429, no answer) the login goes without a token, as before, and no
registration is tried again for an hour (after a 429: until the next UTC day).

## Response

200:

```json
{
  "urls": [
    "turn:turn.cloudflare.com:3478?transport=udp",
    "turn:turn.cloudflare.com:3478?transport=tcp",
    "turns:turn.cloudflare.com:5349?transport=tcp",
    "turns:turn.cloudflare.com:443?transport=tcp"
  ],
  "username": "...",
  "credential": "...",
  "ttl": 600,
  "tok": "...",
  "exp": 1800604800
}
```

`tok` and `exp` only when the request had a good token (above).

- `urls`: only `turn:` and `turns:` URLs, UDP and TCP, as Cloudflare returns them (ports 3478, 443, 80, 5349,
  maybe 53). Use them in the order given; skip any the app can't use.
- `username` and `credential`: the TURN long-term credential (RFC 8489 style) for every URL in the list.
- `ttl`: seconds the login is valid, counted from when the response arrived: 600 when the request had `rn`, else 1800
  (apps that don't send it can't renew, so they keep the length they were built for).

Errors are JSON `{"error": "<code>"}`:

| Status | `error` | App behaviour |
| --- | --- | --- |
| 400 | `bad_request` | Bug in the app. Don't retry. |
| 413 | `too_large` | Body over 1 KB (refused before it is read). Bug in the app. Don't retry. |
| 401 | `stale`, `bad_sig` | Clock wrong or wrong `APP_SECRET`. After `stale`, one more try with `t` corrected by `now`; else don't retry this call. |
| 401 | `bad_token`, `token_expired` | Drop the token, register again (once), ask again. |
| 429 | `id_limit`, `ip_limit`, `global_limit`, `busy`, `slow_down` | A budget is used up ("Limits" below). The body says how long until it may work: `{"error":"busy","retry":1800}` (seconds). Apps that send `rn` ask again no sooner than that (at least 60 s, at most until the next UTC day); older apps, and any 429 without `retry`, wait until the next UTC day. |
| 502 | `upstream` | Cloudflare TURN API failed. Retry later. |
| 503 | `limits_unavailable`, `not_configured` | Worker not set up, or its limits storage is missing or down. Retry later. |

On any error, the call goes on without a relay: the Wi-Fi, Bluetooth and direct (5.8.2) paths can still work, and an
internet call with none of them fails with "Can't connect". During a call, a renewal that fails leaves the allocation in
use until its login ends; then the free paths carry the call, and if none is live the usual restart (5.6) asks again.

## Renewal (round 17)

A login with `rn` lasts 10 minutes. Two minutes before the login an allocation uses ends (`RENEW_BEFORE_MS`, 120 s), a
call in progress fetches a fresh login (the cached one has less than 5 minutes left by then, so it isn't reused) and
makes a second allocation with it. That allocation lets the peer's relayed address in at once, and its address goes to
the peer in `cands`, as after a network change (5.6), so a peer of any version follows it. Media still goes out through
the old allocation for 45 s more (`HANDOVER_MS`), while the peer learns the new address; what arrives on either counts.
Then media moves to the new allocation and the old one is released, 75 s before its login ends. A renewal that fails
is tried once more 20 s later. A restart (every path dead for 4 s) during a renewal replaces both allocations.
Huginn: `CallSession::renewRelay`; Muninn: `CallSession.renewRelay` and `CallMedia.renewRelay` / `finishRenew`.
A two-hour call costs 12 logins (120 minutes of budget).

## Limits (Worker side)

Every budget is counted in minutes of TURN login handed out (10 for a login with `rn`, 30 without), not in logins: what
one login can cost the TURN account grows with how long it lasts. The Worker can't see the bytes relayed; these
minutes are the most it can bound. Each UTC day:

| Budget | With a token | Without a token |
| --- | --- | --- |
| Per install (`id`) | 480 minutes (48 renewing logins, or 16 old ones) | 60 (2 logins) |
| Per address group (IPv4 /24, IPv6 /48) | 1440 minutes, all logins from the group | 120 (4 logins), and they count in the 1440 too |
| Pool | the day's budget below | 900 minutes (30 logins) of their own; using it up never touches logins with a token |

For everyone:

- **The day**: `DAILY_MINUTES` (a Worker variable), default 24,000 minutes (400 hours of login). Used up: `global_limit`
  until the next UTC day.
- **The hour**: no hour takes more than a sixth of the day's budget (4000 minutes by default), so a burst can't use
  up the day in minutes. Used up: `global_limit` until the next UTC hour.
- **Busy**: past 60% of the day's or the hour's budget, logins without a token, and installs that registered less
  than 2 days ago (`since` in the token), get `busy` until the day or hour turns; installs registered earlier go on
  until the budget is used up. A flood of fresh installs can then never take the relay from the households already
  using it.

When the relay says no, calls go on over the free paths (Wi-Fi, Bluetooth, direct between people who trust each other);
signaling never needs the relay Worker. Registrations: 6 a day per address group, 2000 a day for everyone.

The Worker counts in one SQLite-backed Durable Object (`RelayLimits`, binding `LIMITS`). One request is checked and
counted in a single step inside it, so requests at the same moment can't pass a budget. Rows (`counts`, keyed by the
day): `d` the day, `h<HH>` the hour, `u:` the install, `i:` the address group, and for logins without a token also `g`
their pool and `l:` their address group; registrations `rg` and `r:`. A login with a token writes 4 rows, one without
6, a registration 2. Rows from earlier days are deleted when the first request of a new day arrives. If the binding is
missing or the object fails, the Worker answers 503 `limits_unavailable` and hands out nothing.

The address key is HMAC-SHA256 (key: the Worker secret `TURN_KEY_API_TOKEN`) of `"ip:" + day + ":" + group` (for
example `203.0.113.0/24` or `2001:db8:1::/48`), cut to 16 bytes. Addresses themselves are never stored or logged. A /24
can be a carrier's shared addresses (CGNAT) or a campus; 1440 minutes a day is still a day of calling for such a group,
and installs never share a per-install budget.

## Junk requests

Every request that reaches the Worker counts against the free plan's 100,000 a day, whatever the Worker answers; once
they are used up Cloudflare refuses requests until the next UTC day. Inside the Worker, each check is cheaper than the
next, and nothing past a failed check runs:

1. Path and method: anything but `POST /turn`, `POST /register` and `GET /health` is a 404 or 405 at once, its body never
   read.
2. Configuration, then `Content-Length` over 1 KB: 503 or 413, body never read.
3. Per Worker instance, in memory only: at most 20 POSTs a minute from one address group (429 `slow_down`, `retry` 60).
   Each Cloudflare location runs its own instances, so this is a free speed bump, not a cap.
4. The Workers rate limiting binding `RATE`, if the owner bound one in `wrangler.toml` (per location, keyed by address
   group, same answer). The Worker works the same without it.
5. The body (JSON object, field types, `rn`), the time, the `APP_SECRET` signature, then the token (and, for
   `/register`, the proof of work). The HMAC keys are imported once per instance.
6. Refusals already known: a budget the Durable Object refused is kept in the instance's memory until it ends, so the
   same install, address group, pool or busy relay is answered without asking again.
7. Only then the Durable Object, and only after it says yes the TURN API.

So junk costs one Worker request and nothing else: no Durable Object request, no row, no TURN login. What no code inside
the Worker can do is keep junk from counting as a request. Only something in front of the Worker can: a route on a domain
on the same Cloudflare account, `workers_dev = false`, and the free plan's rate limiting rule (for example 20 requests
per 10 s per address on `/turn` and `/register`, and a rule blocking every other path). That needs a domain and the apps'
`WORKER_URL` changed in the same release, so it is the owner's choice (`wrangler.toml` says how). Until then, if a flood
does use up the day's requests, the apps lose only the relay, and only until the next UTC day: apps that send `rn` back off as
`retry` says, older ones until the next day, and calls go on over the free paths.

Why these Cloudflare parts (all within the free plan, no other service):

- Durable Object with SQLite storage for the counts: free with 100,000 requests and 100,000 rows written a day, and
  one object gives exact counts. KV would be eventually consistent (two places could both pass a cap) and allows only
  1000 writes a day on the free plan.
- With every budget used up: about 2400 logins with `rn` x 4 rows, 30 without x 6, 2000 registrations x 2 = about 13,800
  rows written, and as many deleted the next day; at most one Durable Object request per Worker request that passes
  steps 1 to 6.
- The Cache API does nothing on `workers.dev`, and a cached answer would still count as a Worker request, so the Worker
  caches in instance memory instead (steps 3, 5 and 6). `/health` and 404 answers say they may be cached by anything in
  between.

After each release, the relay-deploy job deploys the Worker with `wrangler.toml` (`code/shared/relay-worker/README.md`);
test builds and betas don't.

## Caching

- Store `{urls, username, credential, expires}` with `expires = time the response arrived + ttl`.
- Reuse the cached login until 5 minutes before `expires` (`now < expires - 300`). After that, fetch a new one. A call
  in progress renews its own allocation ("Renewal" above).
- Fetch when a call is near, not at app start: when a conversation with a contact that can take calls
  (`c1`) is opened (at most one try a minute), when a call starts, and when an offer arrives. The login is
  then usually cached by the time Call is pressed, so starting a call doesn't wait for the Worker.
  (Huginn `Calls.prepare`, Muninn `CallManager.prepare`.)
- Keep the cache in memory (or app private storage). Never log the credential.
- After a 429, don't ask again for `retry` seconds (at least 60, at most until the next UTC day), or until the next UTC
  day when there is no `retry`. After a 5xx, wait at least 60 s before asking again.

## Reaching the TURN server

The TURN URLs carry a host name (`turn.cloudflare.com`). The apps look it up with the system resolver,
a few tries a second apart. If it can't be looked up, the call has no relay and says "Can't reach the call
relay right now". The audio goes from the device to the TURN server's address directly (UDP, or TLS on 443).

## Health

`GET {WORKER_URL}/health` returns `{"ok":true}`. The apps don't need it; it is for checking that the Worker is up.

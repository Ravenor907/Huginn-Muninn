# Huginn and Muninn protocol

This is the wire protocol both apps speak: group chats, 1:1 calls, voice rooms, the kids' TV and the rest below.
It builds on the envelope and message ratchet of section 0 without changing them. Everything deterministic
(canonical JSON, signatures, group and TV acceptance rules, call keys, media packets, replay window) is
implemented in `code/shared/tools/proto06_ref.py`, which writes `code/shared/vectors/proto06-vectors.json`. When this text and the
reference disagree, the reference wins.

Words: MUST and MUST NOT are hard rules for interoperating. SHOULD is the expected behaviour; an app that
does otherwise still interoperates.

## 0. The base layer

- Identity: each device has one P-256 key pair, the "chat key". Its public half travels as standard base64
  of the SubjectPublicKeyInfo DER (`fromKey`, contact codes). The same key also signs (ECDSA, see 2.2).
- Envelope, on every route:
  `{room, fromKey, fromName, fromId, mailbox, id, cap:"r1", caps, [invite], (nonce, data) | (rh, data)}`
  (`caps` is 1.1). Unknown fields are ignored.
- Routes: Bluetooth LE (BleFrames), local Wi-Fi `POST /api/huginn/v1/message` (to the `port` the device announced,
  21.7), internet as a Nostr kind 1059
  event whose content is the envelope sealed to the recipient's chat key with a one-off key (to someone whose caps
  include `e1`, also to one of their day keys, 23.1).
- Pairwise sealing: the message ratchet per contact (`code/shared/tools/ratchet_ref.py`), or v1 (static shared key)
  where the ratchet isn't used.
- Every message goes out on every route at once; the receiver keeps the first copy (dedupe by id) and
  answers with a sealed receipt; the sender retries the same sealed bytes (5 s, 10 s, 20 s, 60 s, then every
  5 min) for a day until "delivered".

All payloads in this document are UTF-8 JSON plaintext inside `data`, sealed exactly like a private
message. "Send to X" always means: build an envelope for X, seal it pairwise for X, send it on all routes.

## 1. Capabilities

### 1.1 The `caps` field

`cap` MUST stay exactly `"r1"`. An app without `caps` tests `cap == "r1"` (Kotlin `ChatWire.capable`, Huginn
`ChatWire::saysCapable`), so a value like `"r1 g1 c1"` would make such a peer stop switching new
contacts to the ratchet.

Every envelope also carries a second field:

```
"caps": "r1 g1 c1 c2 k1 k2 k3 k4 t1 v1 m1"
```

Space-separated tokens, in any order. Unknown tokens are ignored. An app without `caps` ignores the field.

| token | meaning |
|---|---|
| `r1` | message ratchet (same as `cap:"r1"`) |
| `g1` | group chats (section 3) |
| `c1` | 1:1 calls (section 5) |
| `c2` | call extras (5.8): the direct path between trusted contacts, receiver reports and the higher Opus bitrate, video calls and screen share. Implies `c1`. |
| `v1` | voice rooms and group calls (section 6). Implies `c1`. |
| `v2` | room heartbeats (6.8): `vjoin` every 10 s while in a room, and presence that ends 60 s after the last one. Muninn and Huginn. |
| `k1` | kids' TV management (section 7). A parent app and a TV both send it. |
| `k2` | kids' TV config fields `calls` and `mic`, the `update` message and `app` in `applied` (7.5). Muninn only. |
| `k3` | kids' TV `status`, `usage` and `clear` (7.7): the level set on the TV, its storage, Clear history. Muninn only. |
| `k4` | kids' TV `apps` (7.9): a parent sends an app to the TV. Muninn only. |
| `k5` | kids' TV config field `nr`, the TV's noise removal (7.10). Muninn only. |
| `t1` | takes a kids' TV card (7.6): someone invited to talk with the kids. Muninn and Huginn. |
| `m1` | replies, reactions, edits, typing, active status, and being put straight in a group by a contact (section 11). Muninn and Huginn. |
| `m2` | photo albums, video messages, polls and link previews (section 12). Implies `m1`. Muninn and Huginn. |
| `i1` | room `intro`: introductions and group members asking to be contacts (section 13). Muninn and Huginn. |
| `f1` | files over the internet: room `file` and photo pieces with `kind:"file"` (section 14). Muninn and Huginn. |
| `d1` | message limits (section 16): group `kind` and `days` in the state, and `days` in every envelope. Muninn and Huginn. |
| `k6` | kids' TV reconnect (7.11): `rejoin`, `ask`, `here`, `approve`, `swap`, `orphan`. Muninn only. |
| `k7` | kids' TV config field `upd`, automatic update checks (7.12). Muninn only. |
| `k8` | kids' TV config field `keep`, the `allow` message and `off` in `status` (7.13). Muninn only. |
| `k9` | parent-approved installs on a kids' TV (7.15): `appask`, `appok`, `appno`, `appres`. Muninn only. |
| `k10` | App storage on a kids' TV from a parent's phone (7.16): `files`, `filelist`, `filedel`, `filemove`. Muninn only. |
| `j1` | group invite links and "Members can invite" (section 17): `mi` in the state, control messages `link`, `linkoff`, `links`. Muninn and Huginn. |
| `x1` | moving to another device (section 18). Muninn and Huginn. |
| `s1` | proven routes and bound envelopes (section 21): route proofs, the hidden hint, clear fields sealed in, the group state limits. Muninn and Huginn. |
| `p1` | profile pictures and status (section 22): room `profile`, and `st` and `cs` on `active`. Muninn and Huginn. |
| `b1` | the ring key (section 23.2): `rk` and `rp` in receipts, ring envelopes for 1:1 offers. Muninn and Huginn. |
| `e1` | the relay wrapping sealed to a day key too, and room `file` through the ratchet (section 23.1): `wk` and `wt` in receipts. Muninn and Huginn. |
| `a1` | animated GIFs: photo pieces with `kind:"gif"` (section 25). Muninn and Huginn. |
| `l1` | the whole account on a linked companion (section 15.1). Not in envelope `caps`: it goes in room `link`, in the companion's `sync`. Muninn as the phone, Huginn as the companion. |
| `g2` | group timeouts and bans (section 26): signed records in room `group` (`t:"mod"`), and posts from someone timed out or banned dropped. Muninn and Huginn. |
| `f2` | file sharing (section 27): the `lan` candidate, group files (`g` on an offer), the group's file rules (`fs` and `fm` in the state), `why` on a decline, and the Hold packet. Muninn and Huginn. |

A receiver records the token set per contact from the last **authentic** envelope (after it opened), like
`authentic()` does for the ratchet. Tokens are never removed by an envelope that lacks them unless
the contact's key changed (a changed key resets the set to empty, as it resets the ratchet).

### 1.2 Caps probe

A contact's caps are unknown until an authentic envelope with `caps` comes from them. To find out, send a receipt with no ids:

```
room "receipt", plaintext {"ids": [], "s": "delivered", "q": "caps"}
```

An app without `caps` accepts it and does nothing. An app with `caps` answers with `{"ids": [], "s": "delivered"}` (no `q`),
whose envelope carries its `caps`. At most one probe per contact per hour. Apps SHOULD probe when the user
opens a contact's chat and the caps are unknown.

Caps are also learned these ways:
- **Hello after adding.** Right after anyone becomes a contact (a code, an introduction, a Family offer, Keep in
  touch, an invite link, an accepted request, a key change), the app sends this probe once, outside the hourly
  limit. Its envelope carries the sender's caps and the answer carries the other side's, so both learn them at once.
- **Caps come from more places.** Besides 1:1 envelopes: authentic group envelopes (they open like any other),
  envelopes from someone met close by who isn't a contact yet (kept with them and carried over when they become
  a contact), and group states: a contact listed as a member of an accepted state has at least `r1 g1 c1` (3.7).
- **Unknown caps count as able in the screens.** Call, Video and video messages show for someone whose caps
  aren't known yet (a probe goes out). The wire rules of 1.3 still hold for anything whose caps are known; an app
  without `c1` that is sent a call simply doesn't answer.

### 1.3 Fallback for peers without a token

- Never send rooms `group`, `gmsg`, `gphoto`, `greceipt`, `call`, `tvconfig` to a peer without the matching
  token (`g1` for the group rooms, `c1` for `call`, `v1` for room messages in `call`, `k1` for `tvconfig`; `t1` for a `card`, `k2` for `update`
  and for a config with `calls` or `mic`; `k3` for `status`, `usage` and `clear`; `k4` for `apps`; `k5` for a config with `nr`; `k7` for a config with `upd`; `k8` for a config with `keep` and for `allow`; `m1` for rooms `rich`, `mx` and
  `live` and for `group` messages `react` and `edit`). A peer without the token answers 400 and drops it.
- UI: the call button on a contact without `c1` says "Needs an update"; inviting a contact without
  `g1` to a group says the same and sends nothing.
- 1:1 text, photos, voice messages, receipts, unsend and keep work with everyone, whatever their caps.
- A GIF goes animated (`kind:"gif"`, section 25) only to someone whose caps are known to include `a1`; anyone else,
  and anyone whose caps aren't known yet, gets its first frame as an ordinary photo.

## 2. Shared rules

### 2.1 Canonical JSON

Anything that is signed or hashed is turned into bytes with this canonical form, never with a library's
`toString()` (org.json writes `\/`, Qt reorders nothing but escapes differently):

- Object: `{`, members sorted by key in byte order, `"key":value` joined with `,`, `}`. Keys are ASCII.
- Array: `[`, values in their given order joined with `,`, `]`.
- String: `"`, the text as raw UTF-8 with only `\` written as `\\` and `"` written as `\"`, `"`.
  Text fields MUST NOT contain U+0000..U+001F or U+007F; an object containing one is malformed and refused.
  No Unicode normalization.
- Integer: plain decimal, 0 <= n < 2^53. No negative numbers, no fractions, no exponent.
- `true`, `false`. No `null`.
- No whitespace anywhere.

The reference function is `canon()`; vectors are under `canonical`.

### 2.2 Signed objects

```
signed bytes = UTF-8( domain + "\n" + canon(object without its "sig" member) )
sig          = base64( DER( ECDSA-P256-SHA256(chat private key, signed bytes) ) )
hash(object) = lowercase hex SHA-256( UTF-8( canon(object with "sig") ) )
```

Domains:

| domain | used for |
|---|---|
| `huginn-muninn group v1` | group state (3.3) |
| `huginn-muninn call v1` | call offer and answer (5.2) |
| `huginn-muninn tvconfig v1` | TV config (7.2) |
| `huginn-muninn tvdisconnect v1` | TV disconnect (7.3) |
| `huginn-muninn vroom v1` | voice room and group call messages (6.2) |

Signatures may use random k (Java `SHA256withECDSA`, OpenSSL `EVP_DigestSign`); receivers only verify.
The vectors use RFC 6979 so the file is reproducible.

Using the one P-256 key for both ECDH and ECDSA is a deliberate trade: no new key to back up, pair or
verify. The two uses never sign or agree on attacker-chosen raw values, and the domains above keep
signatures apart from each other.

### 2.3 Ids

- Group id, call id: 16 random bytes, base64url without padding (22 chars). Channel id and quick message id:
  8 random bytes, base64url (11 chars). The default channel's id is the literal `main`.
- Logical ids of group messages and group control messages (`id` in the plaintext, also called `gid`):
  16 random bytes as 32 lowercase hex chars. Hex, not base64url, because envelope ids end up in file names:
  Huginn keeps Outbox entries only for ids made of letters, digits and `-` (`fileSafe` in `huginn/src/chat.cpp`).
- Group fan-out: one logical message becomes one envelope per member. The envelope `id` for member K is

  ```
  id + "-" + first 8 hex chars of SHA-256(UTF-8(K's chat key text))
  ```

  so the Outbox and Seen (both keyed by envelope id) never collide across members, and retries rebuild the
  same id. Vectors: `group.envelopeIds`.

### 2.4 Ratchet and rate limits

- `group`, `gmsg`, `gphoto`, `greceipt`, `call`, `tvconfig` are in `RATCHET_ROOMS` (Kotlin) and
  `useRatchet` (Huginn). Receivers accept v1 as well.
- The limit of 5 envelopes per sender per 10 s (receipts exempt) applies to `gmsg`; `gphoto` pieces
  count like `photo`. `greceipt` is exempt like `receipt`. `group` and `tvconfig`: 20 per sender per 10 s.
  `call`: 30 per sender per 10 s (a voice room join answers many offers at once).

## 3. Group chats

### 3.1 Model

- No group key. Every group message is sealed separately to each member over that member's pairwise
  session (ratchet). A group of n people costs n-1 envelopes per message; in exchange there is no group key
  to rotate, leak, or resend when someone leaves, and every route and retry rule of section 0 applies unchanged.
- Max 50 members, 20 channels, 8 people per voice room or group call.
- Group state is a signed snapshot. Every change is a whole new state with a higher version, signed by an
  admin. Members keep the current state and the last 32 accepted states (for `sync`, 3.6).

### 3.2 Group state

```json
{
  "g": "<group id>",
  "v": 3,
  "owner": "<chat key>",
  "by": "<chat key of the admin who signed this version>",
  "name": "Family",
  "admins": ["<chat key>", ...],
  "members": [{"k": "<chat key>", "n": "<name>", "p": "<mailbox hex or empty>"}, ...],
  "channels": [{"id": "main", "n": "general", "t": "text"}, {"id": "<8-byte id>", "n": "Hangout", "t": "voice"}],
  "ts": 1761500120000,
  "sig": "<base64 DER>"
}
```

A state is well-formed only if all of these hold (reference `check_state`):

- exactly these members, no others (plus `kind` and `days` for a Family or Work group, cap `d1`, section 16.2); `v` >= 1; `ts` is the signer's clock in ms (shown, never trusted).
- `name` 1 to 40 characters (code points), not only spaces.
- `members` sorted by `k` (byte order of the base64 text), unique, 1 to 50 entries. `n` at most 40
  characters. `p` is empty or 64 lowercase hex (Nostr mailbox).
- `admins` sorted, unique, every admin is a member, `owner` is an admin, `by` is an admin.
- `channels`: 1 to 20, unique ids, the first is `{"id":"main","t":"text"}` (its name may change and it can't
  be removed), every other id is 8 bytes; `t` is `text` or `voice`; `n` 1 to 32 characters. Array order is
  display order.
- canonical size at most 24 KB (50 members with long names is about 15 KB).

### 3.3 Accepting a state

Given the current state `cur` and an incoming state `new` (reference `accept_state`):

1. `new` is well-formed and its `sig` verifies with `new.by`.
2. First sight (`cur` is none: you are joining): accept if 1 holds, you are in `new.members`, and `new.by` is
   the admin you accepted the invite from (3.5). Otherwise go on:
3. `new.g == cur.g`.
4. `new.by` is in `cur.admins`. (An admin removed in `cur` can't sign anything you will take.)
5. `new.owner == cur.owner`, unless `new.by == cur.owner` (only the owner hands ownership over).
6. Conflict rule: accept if `new.v > cur.v`, or `new.v == cur.v` and `hash(new) < hash(cur)` (string compare of
   the lowercase hex). Otherwise refuse. Also (section 21.5): refuse `new.v > cur.v + 1000`, and at the same
   version a state signed by its owner (`by == owner`) wins over one that isn't, before the hash decides.

Versions may jump (v3 to v7) as long as rule 4 holds against `cur`. If rule 4 fails only because you are
behind (the signer became an admin in a version you missed), send `sync` (3.6) to the envelope sender and
apply what comes back in version order, each checked against the one before.

Two admins changing v2 at the same moment both produce a v3. Every member ends up with the one with the
lower hash. The admin whose v3 lost sees the winner arrive, and its app SHOULD re-apply its change on top
as v4 (a rename or a channel add replays cleanly; a remove is re-done only if the person is still there).
Vectors: `group.accept`, `group.conflict`.

After accepting, diff old and new members: create a group peer (3.7) for each new key, stop sending to
removed keys, and show "You were removed from Family" if you were removed (the group goes read-only).

### 3.4 Room `group`: control messages

Plaintext is `{"t": <type>, "g": <group id>, "id": <logical id, 2.3>, ...}`. `id` is the logical id (envelope ids
come from 2.3). Admins sign every state; everything else rides on the pairwise envelope's authenticity.

| t | sent by, to | fields | effect |
|---|---|---|---|
| `state` | the admin who signed it, to every member of old and new state; anyone answering `sync` | `op`, `state` | apply 3.3. `op` is a hint for the UI: `create`, `add`, `join`, `leave`, `remove`, `rename`, `addch`, `rmch`, `admin`, `unadmin`, `owner`, `sync` |
| `invite` | an admin, to one contact | `state` (current), `inv` (16-byte token, base64url), `x` (expiry, s) | show "Raven invited you to Family". Nothing changes until they accept |
| `join` | the invitee, to the inviting admin | `inv`, `n` (their name), `p` (their mailbox) | admin checks the token (unexpired, made by this admin, for this group) and sends `state` with `op:"join"` |
| `decline` | the invitee, to the inviting admin | `inv` | admin forgets the token |
| `leave` | a member, to every member | | any admin who gets it signs a state without them (and out of `admins`). The owner can't leave until ownership is handed over (UI enforces) |
| `sync` | a member who is behind, to anyone in the group | `have` (their version) | answer with `states` |
| `states` | answer to `sync` | `list` (accepted states with v > have, oldest first, at most 16) | apply each in order with 3.3 |
| `unsend` | a message's author, to every member who got it | `msg` (the gmsg id) | recipients check the original's author is `fromKey`, blank it like a 1:1 unsend, answer `greceipt` `s:"unsent"` |

Creating a group: the creator makes v1 with themself as owner, the only admin and the only member, the
`main` channel, and sends nothing. Adding people is then `invite` per contact (or a group code, 3.5).

Renaming, channels, admins: the admin builds the next state from its current one, `v + 1`, signs, sends
`state` to all members.

Removing someone: the admin signs a state without them and sends `state` to everyone in the old state,
the removed person included, so their app knows.

Every `group` message is retried like a private message until a `greceipt` `delivered` for its `id` comes
back from that member, or a day passes, or the member has left the state.

### 3.5 Invites and group codes (reaching non-contacts)

Two ways in:

1. **A contact**: the admin sends `invite`. The invitee's app trusts it because it came from a contact.
2. **Anyone with the group code**: an admin shows a QR or text code

   ```
   MUNINNG1:<base64url(JSON {"g", "gn": group name, "a": admin chat key, "an": admin name,
                              "p": admin mailbox, "i": token, "x": expiry seconds})>
   ```

   valid for 1 hour, usable by several people until it expires (the admin can revoke it). The joiner sends
   `join` with `inv = i` to the admin. The admin's app accepts `group` envelopes from a non-contact only
   when they are a `join` carrying a live token it made; it then adds them without asking (whoever holds
   the code was invited) and they appear in the member list for any admin to remove.

Either way the joiner accepts its first state only from the admin it joined through (3.3 step 2).

### 3.6 Staying in step

Every `gmsg` carries `sv`, the sender's current state version. A receiver whose version is lower sends
`sync` to that sender; one whose version is higher sends its current state back (`state`, `op:"sync"`).
So a member who was offline catches up with the first message they see.

### 3.7 Group peers

Members need to message each other even if they aren't contacts. A member listed in an accepted state who
isn't a contact becomes a **group peer** on this device: stored with the contacts (chat key, name and
mailbox from the state, local flag `groupOnly`), shown in member lists but not in the contact list, not
verified (their code can be checked like any contact's), and treated as `r1 g1 c1` capable (only apps with `g1`
can join). Their mailbox follows the `mailbox` field of their authentic envelopes, like a contact's.

From a group peer, an app accepts only `group`, `gmsg`, `gphoto`, `greceipt` and `call` with a `g` of a group
they share, plus `receipt` for those. A `private` message from a group peer is handled like one from a
stranger far away: it needs a contact invite or a "Keep in touch". Adding a group peer as a
contact just clears `groupOnly`.

A member whose key changes is a new person: their old key stays in the state until an admin removes it, and
the new key has to be invited again.

### 3.8 Room `gmsg`: messages

```json
{"g": "<group id>", "ch": "<channel id>", "id": "<gid>", "sv": 3, "text": "...", "ts": 1761500130000}
```

- `text` at most 2000 characters, like private messages. `ch` must be a text channel.
- Dedupe by `(g, id)` against the stored chat and the deleted-ids list, before and after opening.
- From a key that isn't in the receiver's current state: if `sv` is higher than the receiver's version,
  hold it (at most 50 held per group), send `sync`, and show it once the sender turns out to be a member;
  otherwise drop it.
- Unknown `ch` while `sv` is higher: hold and sync, as above. A channel that was removed: show it in `main`.
- The receiver answers `greceipt` `delivered` to the sender only.

### 3.9 Room `gphoto`: photos and voice messages

Exactly the 1:1 `photo` pieces (16 KB, `i`, `n`, `kind`, `ms` in the clear), plus clear fields `g`, `ch`, `gid`.
The envelope `id` follows 2.3 from `gid`. "Delivered" once all pieces are in, as for `photo`.

### 3.10 Room `greceipt`: receipts

```json
{"g": "<group id>", "ids": ["<logical id>", ...], "s": "delivered" | "read" | "unsent"}
```

- Sent only to the author of those messages, never to the whole group. At most 40 ids per receipt.
- `read` only if the reader has read receipts on (the app's setting). Huginn never sends group `read`.
- Optional fields, both ignored by apps that don't know them:
  - `at` (integer, ms) on `read`: when the reader read them. The author keeps it between the message's own
    time and the moment the receipt came in; without `at`, the moment it came in.
  - `nr` (integer 1) on `delivered`: this app doesn't send `read` (read receipts off, or Huginn). A `delivered`
    without it clears that for the message; a later `read` clears it too.
- The author keeps per message a set of member keys that delivered, a set that read with when, and a set
  that sent `nr`. Muninn shows "Delivered to 4 of 6", "Read by 2", and in the message's "Read by" page each
  member under Read (with the time), Delivered to, Not yet or Not shown (`nr`). Nobody else learns who read what.

## 4. Contact folders

Folders are local only: a name and a list of chat keys stored on the device (and in the encrypted backup).
Nothing about folders ever goes on the wire, and the two apps don't need to agree on anything.

## 5. Calls (1:1)

### 5.1 Overview

- Signaling travels in room `call`, sealed pairwise like any message, on all routes.
- Each call has a fresh P-256 ephemeral key on each side, signed with the chat key. ECDH of the two gives
  the call root; HKDF gives per-direction AES-256-GCM keys that change every 16384 packets.
- Audio is Opus, 20 ms frames. Every packet goes out on every live path at once: UDP on the shared Wi-Fi,
  UDP through the TURN relay over the internet, and Bluetooth LE when connected. The receiver keeps the
  first copy of each sequence number.
- **Relay only over the internet**, unless both people trust each other (5.8.1). Nothing is ever sent
  straight to the internet address of someone who isn't trusted: no STUN, no hole punching, no `host` or
  `srflx` candidates. Each side allocates its own address on the TURN relay
  (Cloudflare, logins from the relay Worker, see `code/shared/docs/relay.md`) and sends to the other side's relayed
  address through it. So the person you call never learns your IP address, and you never learn theirs.
- If no path works the call fails with "Can't connect".

Calls are offered only to contacts with `c1`, and to group peers inside a voice room or group call (section 6, `v1`).

### 5.2 Signaling messages (room `call`)

Plaintext `{"t": <type>, "call": <call id>, ...}`.

| t | from | fields | meaning |
|---|---|---|---|
| `offer` | caller | `eph`, `sig`, `ts`, `cands`, [`lp`], `ble`, `codec`, [`g`, `ch`] | start a call. `g`/`ch` only inside a voice room |
| `ring` | callee | | the offer arrived and is ringing (caller shows "Ringing") |
| `answer` | callee | `eph`, `sig`, `cands`, [`lp`], `ble` | accepted |
| `decline` | callee | | said no |
| `busy` | callee | | already in a call |
| `cands` | either | `cands`, [`lp`] | candidates that weren't ready before (the relay came up, Wi-Fi changed), at most one per 2 s |
| `mute` | either | `on` (bool) | muted or unmuted |
| `hangup` | either | `why`: `hangup`, `noanswer`, `nopath`, `dropped`, `error` | the call is over |

- `eph`: the ephemeral public key, base64 SPKI DER, new for every call, private half never stored.
- `sig`: signature (2.2, domain `huginn-muninn call v1`) over exactly `{"t", "call", "eph"}`. Verify it
  with the envelope's `fromKey`; refuse the offer or answer if it fails. It binds the ephemeral key to the
  identity even if a v1-sealed envelope was used. Vectors: `call.offer`, `call.answer`.
- `ts`: caller's clock in ms, a JSON integer. The callee ignores an offer whose `ts` is more than 60 s old or 120 s ahead
  by its own clock, and shows it as a missed call instead (an offer can sit on a relay for hours).
- `codec`: `"opus16"` (5.5). An answer to an unknown codec is `decline`.
- `ble`: true if this device can carry media to the other over Bluetooth LE right now (5.6).
- `cands` and `lp`: below. `cands` is always there in `offer`, `answer` and `cands` (an empty array when
  nothing is ready yet).
- Dedupe by call id: a second `offer` with the same id is a retry; answer it again with `ring` (or the
  same `answer` bytes).

Timing:

- Caller resends the same sealed `offer` every 3 s until `ring`, `answer`, `decline` or `busy`, and gives
  up after 45 s with `hangup` `why:"noanswer"`. The callee rings for up to 45 s from the first offer.
- Callee resends the same `answer` every 2 s until the first authentic media packet, for up to 15 s.
- `hangup`, `decline`, `busy`: sent 3 times over 2 s, no further retries. Every `call` message expires: it
  is never kept in the day-long Outbox.
- A device in a call answers any other `offer` with `busy` (offers for the room it is in excepted, 6.3).

Candidates (the same in both apps):

```json
"cands": [{"t": "relay", "a": "104.30.1.2", "p": 61000}, {"t": "lan", "p": 40312}],
"lp": 40312
```

- `relay`: this side's relayed transport address on the TURN server (`a` an IPv4 or IPv6 literal, `p` the
  port). It is the relay's address, never the device's. Sent once the allocation is up.
- `lan`: the port of this side's call socket, with **no address**: the other side already knows this
  device's Wi-Fi address from Chat's discovery (the LocalSend announcement it came from), so no IP of the
  person is ever put in a message. Sent only while Chat reaches the peer over the shared Wi-Fi.
- `lp` (top level): the same port as the `lan` candidate. Both apps send the port both ways and accept
  either; when both are there
  and differ, `lp` wins.
- At most 8 entries; a message with more is malformed and refused. Ports are integers 1 to 65535.
- Receivers take the first `relay` whose `a` is an IP literal (a peer's candidate never makes an app look a
  name up, so a name is skipped) and ignore every other type. With `c2`, `srflx` and `host` are used too, only
  between trusted contacts that both speak `c2` (5.8.2); an app without `c2` ignores them.
- Send the offer with what is ready (an app may wait up to 400 ms for its relay and its STUN answer; an app
  without `c2` up to 1.5 s), and anything later in `cands`. Resent offers and answers carry the newest candidates,
  and the caller takes the candidates of a resent answer too (same `eph` as the first), not only of
  `cands`. A `cands` that would come sooner than 1 s (without `c2`: 2 s) after the last one waits, it is not dropped. The callee sends no `cands` before it answers: the
  answer carries the relay when it is ready.

### 5.3 Key derivation

```
ikm     = ECDH(my eph private, their eph public)          32-byte x coordinate
salt    = SHA-256(callId raw 16 bytes || offer eph DER || answer eph DER)
root    = HKDF-SHA256(salt, ikm, info "huginn-muninn call v1", 32)
chain_0 = root
chain_e = HMAC-SHA256(chain_{e-1}, 0x02)
km_e    = HKDF-SHA256(salt = 32 zero bytes, ikm = chain_e, info "huginn-muninn call keys", 72)
          o2a key = km_e[0:32], o2a salt = km_e[32:36], a2o key = km_e[36:68], a2o salt = km_e[68:72]
epoch(seq) = seq >> 14
tag     = SHA-256("huginn-muninn call tag" || callId raw 16 bytes)[0:2]
```

`o2a` is offerer to answerer, `a2o` the other way. Separate keys per direction are required: both sides
count `seq` from 0, so one shared key would reuse GCM nonces.

Rekeying is automatic: every 16384 packets (about 5.5 minutes at 50 packets a second, longer with silence)
a direction moves to the next epoch. Once a receiver's window (5.4) has moved fully past an epoch in both
directions, it deletes that epoch's keys and every chain value before the oldest epoch still needed, so a
device captured mid-call can't decrypt earlier minutes of it. Vectors: `call.root`, `call.epochs`.

### 5.4 Media packets

```
offset size
0      1    version, 0x81
1      1    type: 0 audio, 1 ping, 2 pong
2      2    tag (5.3)
4      4    seq, uint32 big-endian, per direction, from 0, +1 for every packet of any type
8      4    ts, uint32 big-endian, ms since this side's first packet
12     n+16 AES-256-GCM(key, nonce, plaintext, aad = bytes 0..11), 16-byte tag at the end
```

- `nonce = salt (4) || 00 00 00 00 || seq (4, big-endian)`, with key and salt of `epoch(seq)` for the
  sender's direction.
- Plaintext: audio is one Opus packet; ping is 8 random bytes; pong is the 8 bytes of the ping it answers.
- Size 28 to 1200 bytes. The first byte 0x81 tells media apart from STUN and TURN messages (first byte
  0x00 or 0x01) and ChannelData (0x40 to 0x7F) on a UDP socket, and from a JSON envelope (`{`, 0x7B) on
  Bluetooth.
- A sender never reuses a seq. A call ends before seq would wrap (2^32 packets is over two years).

Receiving (reference `Receiver.open`), in this order:

1. Size, version, type known, tag matches a live call (and the source fits, 5.6).
2. Replay window, 1024 packets: let H be the highest seq accepted so far. Drop if `seq + 1024 <= H`
   (too old) or if seq is within the window and already marked (duplicate).
3. Drop if `epoch(seq) > epoch(H) + 2` (too far ahead; stops a flood from making the receiver derive
   thousands of keys).
4. Decrypt. Drop if it fails.
5. Only now mark seq in the window (and move H if seq > H).

The same check is the dedupe across paths: a copy of a packet that already arrived on another path is a
duplicate. Vectors: `call.frames`, `call.receive` (order matters; one receiver per side for the whole list).

### 5.5 Audio

Opus, application VOIP, mono, **16 kHz (wideband), 16 kbps VBR, 20 ms frames**, in-band FEC on (expected
loss 10%), DTX on, complexity 5. Decoders may run at 16 or 48 kHz.

Why 16 kHz and 16 kbps over 48 kHz and 24 kbps: each packet is sent on up to 3 paths, and to up to 7 peers
in a voice room. At 16 kbps a packet is about 68 bytes (12 header, ~40 Opus, 16 tag), so one Bluetooth LE
write per 20 ms carries it, and a full voice room (8 people) is about 210 kbps up per UDP path instead of about 330.
Wideband is what phone calls with "HD voice" use; speech gains little above 8 kHz of audio bandwidth.

Jitter buffer: play in seq order, target delay adaptive between 40 and 200 ms (start at 60 ms). A missing
frame is rebuilt from the next packet's FEC when it is there, otherwise Opus packet loss concealment.
Both apps use the same buffer (Huginn `JitterBuffer`, Muninn `Jitter`, one algorithm, the same
simulated-network tests): the delay is the 95th percentile of how much arrival times wander over the last
3 s plus one frame; it grows at once (and by one frame for every packet that came too late to play, at most
4), and shrinks by one frame every 2 s at most. Playout follows the sender's clock: too far behind drops a
frame, too far ahead plays one concealed frame, at most once every 100 ms. Muninn sends a DTX silence
packet every 400 ms too, so the other side's decoder makes comfort noise.
With DTX, silence sends a packet about every 400 ms; `ts` shows the gap.

Speaking indicator: from the decoded audio, never sent. Speaking while the 20 ms level is above -45 dBFS
for 2 frames in a row, held for 300 ms after it drops.

Muted: the muted side sends no audio packets (pings go on) and sends `mute` `on:true`.

### 5.6 Paths

One UDP socket for the Wi-Fi path per call, bound to a random port on all interfaces, open only while a
call is active. Incoming UDP: first byte 0x81 is media; anything else is dropped. The relay has its own
socket or TLS connection (below).

**Relay (internet).** On starting a call, and on an offer arriving (the callee allocates while it rings,
so answering doesn't wait for the relay), get a TURN
login from the relay Worker over HTTPS (`code/shared/docs/relay.md`) and allocate on the TURN server (RFC 8656:
Allocate with the long-term credential, Refresh before it lapses, a permission or ChannelBind for the
peer's relayed address, refreshed every 4 minutes). Try UDP first, then TURN over TLS on port 443, then
any other `turns:` URL. The TURN server's host name is looked up with the system resolver,
retried a few times; if it can't be looked up, there is no relay for this call ("Can't reach the call relay
right now"). Media to the peer goes to the peer's `relay`
candidate through this side's own allocation. Media from it arrives as relayed data; the relay only
lets in addresses this side gave a permission, and every packet must pass the checks of 5.4 (tag, replay
window, AEAD) before it counts, so the relayed source address is **not** required to match the peer's
`relay` candidate. A relay with several sites may translate addresses between them (Cloudflare anchors
each allocation at the nearest data center, so a phone on a carrier and a PC on home Wi-Fi can land on
different ones): when an authentic packet arrives relayed from an address other than the peer's `relay`
candidate, that address becomes a second target (permission and ChannelBind for it too) and media goes
to both from then on; the receiver drops the copies (5.4). Both sides need an allocation for the relay
path to work. The allocation is released when the call ends. A TURN error answer (for example 438, stale
nonce) is used even without MESSAGE-INTEGRITY (many servers send it without); only success answers must
carry a valid one. Cloudflare hands out IPv4 relayed addresses only (it ignores REQUESTED-ADDRESS-FAMILY),
so both sides always get the same family, whatever network the device is on.

**Wi-Fi.** When Chat reaches the peer on the shared Wi-Fi, send to the peer's Wi-Fi address (from Chat's
discovery) at the port of its `lan` candidate or `lp`. Packets on this path are accepted only from that
address.

**Live paths.** A path becomes live when an authentic packet arrives on it. Every ping is answered with a
pong on the path it came on. A path is dead after 4 s with no authentic packet on it. Pings: every 50 ms
for 2 s, then every 250 ms, until the path is live or 15 s pass; once live every 1 s (which also keeps the
relay's channel and the Wi-Fi mapping fresh); dead paths every 2 s for revival.

**Bluetooth LE.** When both sent `ble:true` and the two are connected over Bluetooth LE, media packets go
over a characteristic `MEDIA` = `7b1e0005-6d75-6e69-6e6e-48756767696e` on Muninn's service:
Huginn (central) writes without response, Muninn (peripheral) notifies. One packet per write, no BleFrames
header, so the negotiated MTU must be at least 3 + packet size (185 or more; with a smaller MTU, Bluetooth
is not a media path). Media never waits in the message queue: a packet that can't be written within 40 ms
is dropped. Two Muninns close by may also use their Nearby Connections link (BYTES payloads) the same way.
These paths are live by the same rule (authentic packet within 4 s). Optional: an app without it sends
`ble:false` and uses the relay and Wi-Fi only.

**Sending.** Every audio packet goes out on every live path (relay, Wi-Fi, Bluetooth), except that the
relay rests while a free path carries the call (5.8.3). That is the sender's choice alone, so it
works with peers without `c2` too.

**No path.** If no path is live 15 s after the answer was sent or received, both sides show "Can't connect"
and send `hangup` `why:"nopath"`. During a call, if every path is dead, show "Reconnecting"; after 20 s,
end with `hangup` `why:"dropped"` and show "Call dropped".

### 5.7 IP addresses (shown to the user before the first internet call)

- The person you call never learns your IP address, and you never learn theirs: offers and answers carry
  only the relay's address and, on the same Wi-Fi, a port. The one exception is someone you both trust
  (5.8.1): then each of you sends your internet address so the call can skip the relay. Neither app ever
  shows an IP address on screen.
- The TURN relay (Cloudflare) sees your public address, because the audio goes from your device to it
  directly. It can't hear the call: media is encrypted end to end (5.3, 5.4).
- The relay Worker sees your public address (an HTTPS request), a hash of a random relay id and the time.
  Your DNS server sees the TURN server's name being looked up.
- Nostr relays see nothing new: signaling is sealed like any message.
- Calls over Bluetooth or the same Wi-Fi use local addresses that Chat's discovery already shares there.
  A setting "Internet calls" (default on) can be turned off: then no relay login is fetched, no `relay`
  candidate is sent, and calls work only over Wi-Fi and Bluetooth. An offer from someone not reachable on the
  same Wi-Fi or over Bluetooth is then answered with `decline` at once, never rung, and the chat gets a missed-call
  line saying "Internet calls are off".

### 5.8 Call extras (cap `c2`)

Everything here is used in a call only when **both** the offer and the answer carry `"c2": true`. An app
without `c2` never sends it and never gets anything below: its calls work exactly as in 5.1 to 5.7.

Fields in `offer` and `answer` (all optional; the signature still covers only `t`, `call`, `eph`):

| field | meaning |
|---|---|
| `c2` | `true`: this side speaks this section |
| `vc` | `"vp8"`: this side can send and show video (5.8.5). Missing: sound only |
| `vid` | `true`: its camera is on from the start (a video call; the callee's screen says "Video call") |
| `p2p` | `true`: this side trusts the other and offers the direct path (5.8.1); its `cands` carry `srflx`/`host` |

#### 5.8.1 Trusted contacts

Each app keeps, per contact and only on that device, whether it trusts them for direct calls:

- Default: trusted when the contact is in a folder named "Family" (section 4, any case).
- A per-contact switch "Direct calls" overrides it, on or off (Huginn `calltrust.json`, Muninn
  `calltrust.json`, both in the Vault).
- The caller puts `p2p` and its direct candidates in the offer only when it trusts the callee. The callee
  puts `p2p` and its own in the answer only when the offer had `p2p` **and** it trusts the caller.
- The direct path is tried only when both did (`p2p` both ways). Switching "Direct calls" off during a
  call stops it at once on that side: its later `cands` carry no `srflx` or `host`, it sends nothing more
  to the peer's address and takes nothing from it, and the relay carries the call. Anyone else is relay only: they never get
  your address, and you never send anything to theirs. A caller who trusts the callee has shared its own
  address with them by its own choice, even if the callee doesn't trust back.

#### 5.8.2 Direct path

Candidates, only in messages to someone trusted (and never more than 8 in all, 5.2):

```json
{"t": "srflx", "a": "203.0.113.7", "p": 51820}
{"t": "host", "a": "2001:db8::17", "p": 40312}
```

- `srflx`: the call socket's address as a STUN server saw it: one Binding request (RFC 8489, no
  authentication) from the call socket (the Wi-Fi one of 5.6) to `stun.cloudflare.com:3478`, looked up with
  the system resolver, resent after 300, 900 and 1800 ms, given up after 3 s. Only the server's answer
  to that transaction id counts; the XOR-MAPPED-ADDRESS is the candidate. The callee asks while it rings.
- `host`: up to 2 global unicast IPv6 addresses (2000::/3) of this device, with the call socket's port. No
  IPv4 host candidates: a private address is the Wi-Fi path, a public one is the `srflx`.
- Receivers take up to 3, IP literals only, and never a local address (private, shared, loopback or
  link-local IPv4, `::1`, `fe80::/10`, `fc00::/7`, multicast): a peer can't make an app send to its LAN.
- Connectivity checks are the pings of 5.6, from the call socket to every direct candidate of the peer,
  on the same schedule (50 ms for 2 s, then 250 ms to 15 s, then 2 s). Both sides ping at once, which
  opens both NATs (hole punching). Once an authentic packet arrives on the call socket from an address
  that isn't the peer's Wi-Fi address, and the packet opened as new (a duplicate never teaches an address,
  so a copy replayed from somewhere else can't redirect the call), that address is the Direct path and replies go there (the peer's
  NAT may have picked another port than its `srflx`). Only between two trusted sides; otherwise such
  packets are dropped as in 5.6.
- Live by the same rule as every path (an authentic packet within 4 s). A symmetric NAT on both sides
  means no direct path: the relay carries the call, nothing else changes.
- During "Reconnecting" (every path dead for 1.5 s), after 4 s an app makes a new relay allocation and asks
  the STUN server again (the network may have changed, Wi-Fi to mobile data), at most 3 times a call,
  6 s apart, and sends the new candidates in `cands`. Peers without `c2` take a new `relay` candidate from
  `cands` too. A relay allocation that fails is tried once more after 2 s.

#### 5.8.3 Free paths first

Sound, video and the packets below go out on every live path, except:

- the relay is left out while Wi-Fi or Direct answered one of this side's pings (a pong on that path) within
  the last 750 ms. A pong proves the path works both ways; packets that only come in (a firewall on the
  other side that lets nothing in) never rest the relay;
- video (and its resends) never goes over Bluetooth.

So the relay is a standby: it keeps its 1 s pings (it stays live) and takes over within a second when the
free path stops. A live Wi-Fi or Direct path is pinged every 250 ms (not 1 s) so that its silence is noticed
quickly. Pings, pongs and resends follow the same rules as the media they belong to.

#### 5.8.4 Receiver reports and Opus

More media packet types (5.4; a receiver without `c2` refuses them as "type", so nobody sends them to one):

| type | name | plaintext |
|---|---|---|
| 3 | video | a video fragment (5.8.5) |
| 4 | report | 8 bytes, below |
| 5 | keyframe request | 1 byte: the stream (0 camera, 1 screen) |
| 6 | resend request (NACK) | stream (1), frame (2, big-endian), n (1), then n fragment indexes (1 each); n = 0 asks for the whole frame |

Each side sends a **report** every second, from the first authentic packet on:

```
offset size
0      1    loss: percent of the packets due since the last report that didn't arrive (0 to 100)
1      1    flags: bit 0 my camera is on (sending video), bit 1 I'm sharing my screen; others 0
2      2    jitter: my jitter buffer's delay in ms
4      2    kbps of media I received since the last report (all paths, copies not counted)
6      2    my round trip in ms, 0 when unknown
```

Loss counts audio by its `ts`: a gap of 2 to 10 frames between two packets is that many lost, a longer one
is a DTX pause (one packet due), a late packet takes one back. Video counts fragments, and the fragments
asked for again the first time as lost. Reports are how each side knows whether the other's camera or
screen share is on, so the picture appears and goes away even if a signaling message is slow.

Opus in a 1:1 call between two `c2` apps: still 16 kHz wideband, 20 ms, VBR, DTX, but **32 kbps** while a
free path carries the call and **24 kbps** over the relay (16 kbps stays the rule in voice rooms, where
every peer gets its own copy, and with a peer without `c2`). The expected loss given to the encoder (and so how much in-band
FEC it adds) is `clamp(loss * 1.5 + 3, 5, 30)` percent from the other side's latest report (10 % before the
first). The decoder of an app without `c2` plays these packets as they are.

#### 5.8.5 Video

VP8 (RFC 6386 bitstream), both ways, when both sides said `"vc": "vp8"`. Encoded at the profile of 5.8.6,
realtime, error resilient, a keyframe at the start, on request and at least every 10 s. Each encoded frame
is cut into at most 255 even fragments of at most 1166 bytes, each the plaintext of one type 3 packet:

```
offset size
0      1    stream: 0 camera, 1 screen
1      1    flags: bit 0 keyframe; bits 1-2 rotation, the quarter turns clockwise that show it upright
            (0, 90, 180, 270 degrees); others 0
2      2    frame number, uint16 big-endian, per stream, +1 for every encoded frame (wraps)
4      1    fragment index, from 0
5      1    fragment count, 1 to 255
6      n    VP8 data
```

A sealed video packet is at most 12 + 6 + 1166 + 16 = 1200 bytes. Video uses the call's per-direction keys
like sound (5.3, 5.4): it is end-to-end encrypted, and its seqs share the one replay window. A frame that
would need more than 255 fragments isn't sent.

The receiver (reference: Huginn `VideoWire::Assembler`, Muninn `VideoWire.Assembler`; their self-tests
match) puts each stream's fragments back together and hands whole frames to the decoder in order:

- A frame with a fragment missing, once a later frame has started (or 30 ms after its last fragment), is
  asked for again with a NACK for just the missing indexes (the whole frame when nothing of it came), at
  most 3 times, `max(40 ms, 1.2 x round trip)` apart. The sender keeps the last 64 frames' fragments and
  resends them (as new packets, new seqs).
- A frame that still isn't whole `max(250 ms, 2 x round trip + 120 ms)` after it was missed is given up:
  the receiver asks for a keyframe (type 5), at most every `max(300 ms, 2 x round trip)`, and shows
  nothing new until a whole keyframe arrives. A whole keyframe ahead of a hole is taken at once.
- A new receiver (or a stream that was off) asks for a keyframe first.
- Keyframe requests go at most once per `max(300 ms, 2 x round trip)` per stream, whatever asks (the
  reassembly, a decoder that lost its place, a picture that came back on screen). A sender ignores a request
  that comes within 300 ms of a keyframe it sent (they cross on the way).
- A gap of 120 frames or more between the next frame due and the newest one isn't asked for again: the
  receiver waits for a keyframe instead.

Camera on or off: the sender's report flags say it (and the sender sends a report at once when it
changes); the receiver shows the picture while the flag is on and the person's initial otherwise.
Switching between the front and the back camera is the sender's business (a keyframe follows).

#### 5.8.6 Size and bitrate

| | over the relay | on a free path (Wi-Fi or Direct answered a ping within 3 s) |
|---|---|---|
| camera | 640 x 480, 24 fps, at most 700 kbps | 1280 x 720, 30 fps, at most 2000 kbps |
| screen | 1280 x 720, 8 fps, at most 800 kbps | 1920 x 1080, 10 fps, at most 2500 kbps |

480p over the relay is about 0.4 GB an hour with the sound, to save data. Sizes are the largest; a camera
that can't do them sends what it can. The bitrate starts at 60 % of the cap and follows the other side's
reports: 10 % loss or more takes a quarter off, 4 % or more takes 8 % off, under 2 % for 3 s adds 8 % +
10 kbps a second, never below 120 kbps or above the cap. When a free path comes or goes the encoder starts
again at the other size (with a keyframe).

#### 5.8.7 Screen share in voice rooms

In a voice room or group call (section 6) someone can share their screen (Huginn on Windows); everyone
else in the room sees it (Muninn and Huginn). It is the video of 5.8.5 with stream 1, sent separately to
every peer whose pair call speaks `c2` with `vc`, sealed with that pair's keys like the room's sound:
encrypted per recipient, nothing to rekey when someone leaves. The sharer encodes once and sends the same
fragments to everyone; a keyframe request from anyone makes the next frame a keyframe for all. The size and
bitrate are the lowest any peer allows (the relay profile as soon as one peer is on the relay). A peer
without `c2` keeps hearing the room and simply doesn't see the screen.

What is shared is chosen first, on the sharer's device only (nothing changes on the wire): **an app window** (a list of
open windows with live pictures; only that window is captured, even when another window is in front) or **a full
screen** (which monitor, with a live picture). On Wayland and in the Flatpak the desktop's own ScreenCast picker asks
instead. While sharing, a bar says "You're sharing: <window title or Screen N>" with **Stop**.

#### 5.8.8 Timing

- Offer: sent once the relay and the STUN answer are in, or after 400 ms with what is there.
- `cands`: at most one a second (peers without `c2` accept any rate up to the room limit of 2.4).
- States the apps show: "Calling" (offer out), "Ringing" (they rang), "Connecting" (answered, no path
  yet), "Connected" with the time (a path is live), "Reconnecting", then the end text.

## 6. Voice rooms and group calls (cap `v1`)

### 6.1 Model

- A **room** is named by `(g, ch)`: `g` is a group, `ch` is either the id of a `voice` channel in that group's
  current state (a **voice room**, always there, Discord style) or 16 random bytes in base64url (22 chars) made
  up by whoever starts a **group call** (a temporary room that exists while someone is in it). The two kinds
  can't be mixed up: channel ids are 11 characters, group call ids 22.
- **At most 8 people** in a room, the one who started it included. The apps say so where you join or call.
- **Full mesh of 1:1 calls.** Every pair in a room runs its own call from section 5: its own call id, its own
  signed ephemeral keys, its own per-direction AES-256-GCM keys, epochs and replay window, its own paths. Each
  device encodes its microphone once and seals and sends that Opus stream separately to every other person.
  So media is encrypted **per recipient**: there is no room key, and nothing to rotate when someone joins or
  leaves. Someone who leaves holds no key for anything said after, and someone who joins holds none for
  anything said before (each pair's keys are fresh when the pair's call starts).
- **Relay only over the internet**, exactly as 5.6: each pair's call has its own TURN allocation (from the same
  cached relay login), so a device has one relayed address per peer, the peers see only relay addresses, and
  nobody in the room learns anyone's IP address. On the same Wi-Fi a pair may also go direct, as in 5.6.
- **Mixing on receive**: one jitter buffer (5.5) and one Opus decoder per peer; each 20 ms the decoded frames are
  summed and clipped into the speaker. The speaking indicator is per peer, from that peer's decoded audio, and
  for yourself from your microphone (same rule, 5.5).
- Cost: about 68 bytes per 20 ms packet plus UDP and TURN headers, so about 30 kbps per peer each way; a full
  room of 8 is about 210 kbps up and 210 kbps down per path. Fine on Wi-Fi and LTE; the reason for the cap.

### 6.2 Room messages (room `call`)

```json
{"t": "vjoin", "call": "-", "g": "<group id>", "ch": "<room id>", "ts": 1761500400000, "sig": "<base64 DER>"}
```

| t | sent by, to | meaning |
|---|---|---|
| `vjoin` | someone joining (and every 10 minutes while in, every 10 s to members with `v2`, 6.8), to every member | I'm in this room |
| `vleave` | someone leaving, to every member | I left |
| `vring` | whoever starts a group call, to each person it rings | join my group call |
| `vdecline` | someone who was rung, to the ringer | not now |
| `full` | someone in the room, to a joiner | the room already has 8 people |

- Exactly the fields `t`, `call` (the literal `-`), `g`, `ch`, `ts` (sender's clock in ms) and `sig`.
- `sig`: signature (2.2) with domain `huginn-muninn vroom v1` over the whole object without `sig`, by the
  sender's chat key, checked against the envelope's `fromKey`. Join, leave and ring are thereby signed
  statements, not just authentic envelopes; the media keys are signed per pair by the 5.2 offer and answer.
- 1:1 calls go on using real call ids; `call:"-"` marks a room message and never names a call.

Accepting one (reference `accept_room`), with the receiver's current state of group `g`:

1. Well-formed: the fields above and no others, `t` one of the five, `call` is `-`, `g` is 16 bytes and `ch`
   8 or 16 bytes of canonical base64url (`main` is never a room).
2. The signature verifies with the sender's chat key.
3. The receiver has group `g`, the sender and the receiver are both members of its current state, and the
   sender isn't the receiver.
4. An 11-character `ch` is a `voice` channel of that state.
5. Fresh: `ts` at most 60 s old and at most 120 s ahead by the receiver's clock gives **ok**. Otherwise
   **stale**: a stale `vjoin` or `vleave` still counts for presence (6.4), a stale `vring` is shown as a missed
   group call, a stale `full` or `vdecline` does nothing.

Anything else is refused and dropped. Vectors: `room.messages`, `room.accept`.

### 6.3 Joining, the mesh, leaving

1. Joining `(g, ch)`: send `vjoin` to every other member of `g` whose caps include `v1`, or aren't known yet
   (a group peer that hasn't sent anything since). Members known to lack `v1` get nothing (6.7).
2. A member M who is in `(g, ch)` and gets a fresh `vjoin` from J, and has no call with J for this room: if M
   already has 7 peers there it answers `full`; otherwise M calls J (5.2) with an `offer` that also carries
   `"g"` and `"ch"`. M stays in the room whatever J does.
3. J, while in `(g, ch)`, answers offers carrying that `g` and `ch` from members of `g` at once: no ringing and
   never `busy`. An offer naming a room J isn't in is answered `decline`.
4. Two offers for the same room crossing (both joined at the same moment): the offer from the side whose chat
   key text is lower in byte order wins (reference `room_offer_wins`). The lower side ignores the other's
   offer; the higher side drops its own call without a word and answers. Vectors: `room.glare`.
5. A `vjoin` that got lost: J resends `vjoin` 4 s and 12 s after joining to the members it has no call with
   yet, and everyone in a room re-sends `vjoin` to every member every 10 minutes, which a member in the room
   that has no call with them answers as in step 2. So a lost offer or a pair that never connected heals.
6. A joiner that gets `full` leaves at once and shows "This room is full (8 people)".
7. A pair's call that ends with `nopath` or `dropped` while both stay in the room: the side with the lower
   key calls again after 5 s, at most 3 times.
8. Leaving: `vleave` to every member who got `vjoin`, then `hangup` to every peer (5.2). A `vleave` from a peer
   ends that pair's call at once.

Mute: `mute` (5.2) on each pair's call; a muted device sends no audio to anyone (pings go on).

### 6.4 Presence: who is in a room

Every member keeps, per `(g, ch, sender)`, the last `vjoin` or `vleave` it accepted (ok or stale): in or out,
the sender's `ts`, and when it was seen by its own clock (reference `presence_apply`, `presence_in`):

- A message replaces the entry only if its `ts` is higher than the entry's (a late, older copy changes
  nothing; comparing one sender's clock with itself needs no clock agreement).
- Someone counts as in while the entry says in and it was seen within the last 25 minutes (two and a half
  times the 10-minute re-send; a phone that died never sends `vleave`).
- While you are in a room, everyone you have a live call with there counts as in as well.

Shown to everyone in the group, in or out of the room: "Raven, Moose" under the voice room with **Join**,
and a group call as "Group call" with **Join** while anyone is in it. Vectors: `room.presence`. A sender whose caps
include `v2` goes by 6.8 instead of the 25 minutes.

### 6.5 Group calls (ringing people into a room)

1. The caller picks a group and who to ring: everyone (the default) or some of its members, which is how a
   small ad hoc set is called (people who share no group have no pairwise sessions with each other, so they
   are put in a group first). The caller makes a new 22-character room id, joins it (6.3 step 1: everyone in
   the group with `v1` sees the call and can join) and sends `vring` to the people it rings, again 15 s later
   to those who haven't joined or declined.
2. A fresh `vring` rings for up to 45 s: "Raven is calling Family" with **Join** and **Decline**. Join joins
   that room (6.3). Decline sends `vdecline` to the ringer. Ringing stops on join or decline, after 45 s, or
   once presence shows nobody in that room (the caller gave up).
3. A `vring` that arrives while already in a call or a room is shown as a missed group call, never `busy`.
4. After 45 s with nobody else in the room the caller leaves it and shows "No one joined".
5. A group call has no end message: it is over when the last person leaves.
6. More people than fit can be rung: the first 7 to join get in, later ones get `full`.

### 6.6 One call at a time

A device is in at most one 1:1 call or one room. In a room, a 1:1 `offer` is answered `busy` (5.2). In a 1:1
call, joining a room says "Hang up first", and a `vring` is a missed group call.

### 6.7 Peers without `v1`

Apps without `v1` (caps `r1 g1 c1`) drop every room "call" message that has `call:"-"` or a `g` without answering,
so nothing breaks; they show voice channels as "coming soon" and can't join. Apps with `v1` send them
nothing for rooms, and show "Needs an update to join calls" next to members known to lack `v1`. Probing caps
(1.2) when a group opens finds out for members whose caps aren't known.

### 6.8 Heartbeats (cap `v2`)

A phone that loses its connection or dies never sends `vleave`, so without more it would stay "in" for 25 minutes.

- While in a room, an app with `v2` sends `vjoin` (the same message, 6.2, a new `ts` each time) every **10 s** to every
  member whose caps include `v2`. Members without it still get the 10-minute re-send of 6.3 step 5, so nothing new
  reaches an older app.
- The receiver keeps presence (6.4) as before and notes that the sender has `v2`. For such a sender: in while the last
  `vjoin` was seen at most **20 s** ago; **Reconnecting** from 20 s to **60 s**; out after 60 s (reference
  `heard(quietMs, heartbeats)`: 1 in, 2 reconnecting, 0 gone). A stale (6.2 step 5) `vjoin` from a `v2` sender counts as
  seen 60 s ago, so a late copy never brings someone back. Senders without `v2` keep the 25 minutes.
- In the room: someone whose pair call dropped but whose heartbeats still come shows as Reconnecting; someone with `v2`
  not heard from for 60 s whose pair call isn't up is taken out of the room (their call is dropped without a word).
  A live pair call always counts as in.
- A `vjoin` from someone in the room with no call here is answered with an offer (6.3 step 2) at most every 30 s per
  sender, so a pair that can't connect doesn't retry every 10 s; a `vleave` clears that.
- The same rule is the group's "in a voice room" presence for members outside the room (member list, the rows under a
  voice room, group calls).

Cost: one small envelope per `v2` member every 10 s while in a room. Huginn `RoomWire::heard` and `Presence::state`,
Muninn `RoomLogic.heard` and `Presence.state`; checked in Huginn's `--call-selftest` and Muninn's `RoomTest`.

## 7. Kids' TV

The TV is a Muninn install set to Kids' TV mode on first start (for example on an Android TV box). It has a
chat key like any device and the same caps as any Muninn (`k1` and `k2` matter here).

### 7.1 Pairing

1. The TV shows a QR contact code: the `MUNINN1:` contact code with an invite token, plus `"tv":1`.
2. The parent scans it. The parent's app adds the TV as a contact with the local flag `managed` and sends
   the first config (7.2, `v:1`, `parents:[parent]`) in room `tvconfig`.
3. While the TV has no parents it accepts a config only from an envelope carrying a live invite token it
   showed, with `by == fromKey` and `by` in `parents`. That config's `parents` become the TV's parent keys
   and pairing mode ends.
4. From then on the TV accepts configs only from its parent keys (7.2), and takes no new contacts any other
   way.

A second parent is added by a config from an existing parent that lists both.

### 7.2 Room `tvconfig`: config

Plaintext `{"t":"config","cfg":{...}}`, where:

```json
{
  "tv": "<TV chat key>",
  "v": 2,
  "by": "<parent chat key that signed>",
  "parents": ["<chat key>", ...],
  "quick": [{"id": "<8-byte id>", "text": "I'm hungry"}, ...],
  "contacts": [{"k": "<chat key>", "n": "Grandma", "p": "<mailbox hex or empty>"}, ...],
  "dictation": false,
  "ts": 1761500100000,
  "sig": "<base64 DER>"
}
```

Well-formed (reference `check_tvconfig`): exactly these fields, plus optionally the k2 fields `calls` and `mic`, the k5 field `nr`, the k7 field `upd` and the k8 field `keep`
(7.5, 7.10, 7.12, 7.13); `parents` sorted, unique, not empty, `by` in
it; `quick` at most 24, unique ids, each text 1 to 80 characters, array order is the order on screen;
`contacts` sorted by `k`, unique, at most 50, names at most 40; canonical size at most 16 KB.

The TV accepts `new` if: well-formed, `new.tv` is its own key, `new.by` is one of its current parent keys,
the signature (domain `huginn-muninn tvconfig v1`) verifies, and either it has no config yet, `new.v > cur.v`,
or `new.v == cur.v` with `hash(new) < hash(cur)` (two parents at once). Vectors: `tv.accept`.

Applying it: parent keys become `parents`; the TV's contacts become exactly `contacts` (people removed are
dropped with their chats); the screen shows the quick messages in order; the dictate button shows only when
`dictation` is true (speech to text on the TV, sent as a normal private message). The TV answers every parent
with `{"t":"applied","v":<v>}` so their apps can show "TV is up to date".

On the TV: private messages are accepted only from keys in `contacts` (and parents), and sent only to them.
No Nearby room, no Keep in touch, no groups. Calls (room `call`, section 5) only with those same keys, and
only while `calls` is not false (7.5). A listed person still has to have the TV as a contact on their side:
the parent's app sends them a card (7.6), or shares the TV's code.

### 7.3 Disconnect

Plaintext `{"t":"disconnect","d":{"tv":"<TV key>","v":<TV's current config version>,"by":"<parent key>","ts":...,"sig":...}}`,
signed with domain `huginn-muninn tvdisconnect v1`. The TV accepts it from any current parent whose
signature verifies and whose `v` equals its current config version (an old disconnect can't be replayed
after a new config). It then erases its chats, contacts, config and parent keys and goes back to the pairing
screen. To drop just one of two parents, the other sends a config without them.

### 7.4 No lock screen

The TV has no app lock, panic lock or hidden erase: anyone in the room can read what's on it. Parents'
apps say so when pairing. Nothing secret is in the config, and messages on the TV follow the "Delete
messages after" setting a parent sets from their phone (`keep`, 7.13).

### 7.5 Calls, microphone level and updates (`k2`)

A TV whose app says `k2` takes two more config fields. A parent's app puts them in only for such a TV (a
`k1`-only TV would refuse the config as malformed); until the TV's caps are known it leaves them out, and
sends a config with them once the TV's `applied` shows `k2`.

| field | type | absent | meaning |
|---|---|---|---|
| `calls` | true or false | true | the kids can call people in `contacts` (and parents), and those people can call the TV |
| `mic` | integer 0 to 100 | 50 | the TV's microphone level: a software gain of `0.3 * mic` dB with a soft limiter (tanh above 0.6 of full scale), applied to voice messages, speech to text and calls. A TV that runs its own voice processing (echo canceller, noise suppression and automatic gain) applies `0.3 * (mic - 50)` dB after it instead, so 50 is the processed level |

Anything else that's extra is still malformed. Vectors: `tv.configs.cfg4` (calls off, mic 80), and step 7 of
`tv.accept`.

The TV adds its Muninn version to every answer: `{"t":"applied","v":<v>,"app":"0.61"}` (text, at most 16
characters; apps without `k2` ignore it). A parent may send `{"t":"update"}`: the TV, if the sender is one of
its parents, looks for a new Muninn release at once, installs it, and answers with `applied` for its current
version. The TV also looks every day by itself.

### 7.6 Inviting someone to talk with the kids (`t1`)

A parent invites a contact (say the other parent, or a grandparent) from their phone, without touching the TV:

1. The parent's app adds the person to `contacts` and sends the TV a new config (7.2). The TV now has their
   chat key and mailbox and accepts them.
2. The parent's app sends the person, in room `tvconfig`, a card:
   `{"t":"card","code":"MUNINN1:..."}`, the TV's contact code (name, chat key, mailbox; no invite token and no
   `"tv":1`, so it never offers to set the TV up).
3. The person's app (Muninn or Huginn with `t1`) takes a card only from someone who is already a contact,
   only if the TV isn't a contact yet and the code isn't a key change of another contact (same mailbox,
   other key: ignored). It adds the TV as a contact and says who added them.

Now each has the other's key: messages and calls work both ways. A person whose app hasn't said `t1` (caps
still unknown a minute after a probe, 1.2) gets the TV's link as a private message instead. A card is sent
once on every route (the relays hold it for an offline phone); the parent can always share the TV's code too.
To put the person in a family group, the parent's app uses the normal group invite (3.5).

### 7.7 Microphone level on the TV, storage and Clear history (`k3`)

A TV may keep a microphone level of its own, set on the TV (the Muninn kids' TV sets none: its level comes from the
parents' phones). The TV uses that
level until a parent picks another one: it applies a new config's `mic` only when it differs from the
`mic` of the config it had (a parent changed it; the last change wins), and keeps its own otherwise.

The TV tells its parents (those whose app says `k3`) in room `tvconfig`:

```
{"t":"status","mic":<level in use, 0..100>,"used":<bytes Muninn takes on the TV>,"ts":<ms>[,"own":true]}
```

`ts` is when the level last changed: the TV's clock when it was set on the TV, else the config's `ts`.
All three are integers `0 <= n < 2^53`; anything else is malformed. `own` is present (and `true`) only
while the level in use was set on the TV itself; when present it MUST be a boolean, else the status is
malformed. A status without it means `false`. The TV sends it a second after its slider stops
moving, after every config it applies, after Clear history, and in answer to `usage`.

A parent's app that gets a `status` whose `mic` differs from its own takes the TV's level and sends a new
config with it (7.2), so the other parent and later configs carry it too, when:

- `own` is `true` and the TV has already said `applied` for this phone's latest config (a change made on
  the phone that is still on its way wins). The phone records the change at its own clock, the moment the
  status arrived. The TV's `ts` is not compared with the phone's clock, since the two clocks can differ.
- `own` is absent and `ts` is newer than the phone's own last change.

It shows `used` as the TV's storage.

A parent (only a current parent; anyone else gets 403) may send:

| plaintext | the TV |
|---|---|
| `{"t":"usage"}` | answers with `status` |
| `{"t":"clear"}` | erases every message, photo and voice message and its caches (pairing, parent keys, config and contacts stay), then sends `status` |

Neither is retried; the parent's app asks again when its Kids' TV screen opens. On its own, the TV also
keeps its storage small (recent messages only, smaller photos, voice messages for two weeks, caches
emptied); that is local and not on the wire.

### 7.8 Several parents and keeping them in step

All three are optional fields that an app which doesn't know them ignores.

- **`applied` carries the config.** The TV adds its current signed config to every `applied`:
  `{"t":"applied","v":<v>,"app":"0.61","cfg":{...}}`. A parent's app takes `cfg` only if it passes 7.2's
  checks for that TV, lists the app's own key in `parents`, and is signed by its `by`. If that config is
  newer than the last one the app sent, the app adopts it: the TV runs another parent's change, or the app
  was restored from an older backup. If the app had a change of its own waiting, it tells the parent that
  the change didn't go through instead of reporting the TV as up to date. An app that isn't managing the TV
  yet but finds itself in `parents` starts managing it.
- **Adding a parent.** A parent's app puts the new parent's key in `parents` and sends them a card with the
  config: `{"t":"card","code":"MUNINN1:...","cfg":{...}}`. Their app adds the TV, checks `cfg` the same way
  (and that `by` is the sender), and manages the TV from then on. Only apps that manage TVs (`k1`) can be
  parents.
- **A TV that loses its config** (a damaged file, or stricter checks after an update) stays a Kids' TV,
  shows "Getting this TV ready", and sends its parents `{"t":"applied","v":0}`. Their apps send the whole
  config again under a new version.
- **Leaving kids' mode.** Once a TV has been paired, its pairing screen doesn't offer "Use Muninn
  normally". A parent's disconnect plaintext may carry `"free":true` (`{"t":"disconnect","d":{...},
  "free":true}`). The TV then offers it again. The flag rides in the parent's sealed envelope, which the TV
  only takes from a parent.

### 7.9 Apps a parent sends to the TV (`k4`)

A Kids' TV takes no files: it shows no "wants to send" question and answers every LocalSend
`prepare-upload` with 403, except for an app a parent sends. The parent's app first names the files in
room `tvconfig` (only to a TV whose app says `k4`):

```
{"t":"apps","name":"<the app's name>","files":[{"h":"<SHA-256, lowercase hex>","s":<bytes>}, ...]}
```

`name` is text of 1 to 100 characters; `files` holds 1 to 64 entries; `h` is exactly 64 lowercase hex
digits; `s` is an integer `0 < s < 2^53`. Anything else is malformed and ignored. The TV takes it only
from a current parent while it has a config, and keeps it for 30 minutes (its own clock). A disconnect
forgets every one.

The parent's app then sends the files with LocalSend over the Wi-Fi, each with its `sha256` in
`prepare-upload`. The TV accepts the transfer only when every file is an `.apk` with a `sha256`, and the
set of (`sha256`, size) pairs is exactly the set one `apps` named. That `apps` is then used up. The
`prepare-upload` may arrive before the `apps` does, so the TV waits up to 10 seconds for it before
answering 403. The upload itself still checks each file's SHA-256 (as any LocalSend transfer that gives one).

An app on the phone goes out as `<name>.apk`, plus `<name>.split.<part>.apk` for each part Android keeps it
in. The TV saves them in its Downloads, like any received file, and shows a notice; nothing it receives is
installed or opened by itself. A kids' TV then asks its parents to install them (7.15). A TV used normally (not in kids' mode) does the same with any file the person
accepts. Muninn's own updates are separate (7.5).

### 7.10 Noise removal on the TV (`k5`)

A TV whose app says `k5` takes one more optional config field. A parent's app puts it in only for such a TV.

| field | value | absent means | what the TV does |
|---|---|---|---|
| `nr` | integer 0 to 3 | 3 | the noise removal on its microphone, for calls, voice messages and speech to text: 0 none, 1 Standard (WebRTC's noise suppressor), 2 Strong (RNNoise), 3 both, Standard first |

Anything else (a boolean, text, out of range) makes the config malformed. Anyone at the TV can also pick it in
the TV's "For a parent" screen; like the microphone level (7.7), the TV keeps its own choice until a config
arrives whose `nr` differs from the one before (a parent changed it), and the last change wins. A TV that can't
run its voice library uses Android's own noise suppressor whenever `nr` is not 0. The echo canceller and
automatic gain are not affected. Vectors: `tv.configs.cfg5` (`nr` 1) and step 8 of `tv.accept`.

### 7.11 A parent reconnects after a wipe (`k6`)

A parent whose phone was wiped or reinstalled has a new chat key, so the TV doesn't know them. All messages are in
room `tvconfig`, and the TV and the parents send them on every route at once, the internet included, so nobody nearby
can stand in the way. Reference `code/shared/tools/p95_ref.py` (`tv`), vectors `code/shared/vectors/p95-vectors.json`.

1. On the TV, holding OK on the remote for 5 seconds on "Connect a parent" shows a code: the TV's contact code (no
   invite) with `"tv": 2` and `"r"`, a token of its own (16 random bytes as hex, an hour, once). A child can't open it by
   pressing around.
2. The parent's phone adds the TV from it and sends `{"t":"rejoin","n":<name>,"r":<token>}`. The TV takes it only with
   that token, in time, once, from someone who isn't a parent, and keeps them as the **joiner** (key, name, mailbox of the
   envelope, when). One joiner at a time.
3. The TV warns every parent: `{"t":"ask","k":<joiner key>,"n":<name>}`, again every 6 hours. While a joiner waits the
   TV shows a banner that says so. A parent's phone asks its person: **Approve** sends `{"t":"approve","k"}`, **Block**
   sends `{"t":"block","k"}`.
4. `approve` from a parent: the TV answers that parent `{"t":"swap","k","n","p"}` and the parent's phone signs the next
   config with the joiner added as a parent. Nobody is taken off.
5. `block` from any parent ends it: the TV forgets the joiner and tells them `{"t":"blocked"}`.
6. If no parent blocks within 24 hours of `rejoin` (vectors: `due`), the TV sends the joiner `{"t":"orphan","cfg":<its
   signed config>}` (again every 6 hours until it answers). The joiner's phone takes it only for a TV it asked, checks it
   (7.2, signed by its `by`, for this TV) and signs the next version with itself as the only parent and the same people,
   messages and settings. The TV takes that one config from the joiner with the joiner as the only parent (as in
   pairing, 7.1 step 3). This takeover is the only path that removes parents.
7. A config that lists the joiner ends it.

Well-formed (reference `tv_ok`): exactly the fields above, keys are base64 text, names 1 to 40 characters, `r` 16 to 64
characters, `p` empty or 64 hex, `cfg` an object.

### 7.12 Automatic update checks on the TV (`k7`)

A TV whose app says `k7` takes one more optional config field. A parent's app puts it in only for such a TV (and only
next to the k2 fields), set to that phone's own "Check for updates automatically".

| field | value | absent means | what the TV does |
|---|---|---|---|
| `upd` | true or false | true | true: it looks for a new release on its own (at start and once a day). false: it never asks GitHub by itself; a parent's `update` (7.5) still makes it check |

Anything else (a number, text) makes the config malformed. When the parent changes the switch, the parent's app sends
a new config to every TV that said `k7`. Vectors: `tv.configs.cfg6` (`upd` false) and step 9 of `tv.accept`.

### 7.13 Parent settings from the phone (`k8`)

The Muninn kids' TV has no parent screens: every setting comes from a parent's phone. A TV whose app says `k8`
takes one more optional config field. A parent's app puts it in only for such a TV (and only next to the k2 fields).

| field | value | absent means | what the TV does |
|---|---|---|---|
| `keep` | integer 1 to 7 | the TV keeps its own setting | messages on the TV are deleted after that many days (7: each chat's own limit) |

Anything else (0, 8, text, true) makes the config malformed.

A parent (only a current parent; anyone else gets 403) may send `{"t":"allow","p":<perm>}`, exactly these two
fields, `perm` one of `mic`, `notify`, `overlay`, `install`. The TV then shows Android's own prompt for that
permission, or opens its Android settings page, with one short line. It is not retried.

The TV's `status` (7.7) carries `"off":[<perm>, ...]`, the permissions from that list still off on the TV (empty
when all are on). When present it MUST be an array of strings, else the status is malformed; unknown names are
ignored. A status without it means not known. The TV also sends `status` when one of them changes. Apps that don't
know `off` ignore it.

The TV's `status` may also carry `"update":<state>`, the result of its last update since Muninn started: `needs` (a new
Muninn is downloaded and waits for the Install updates permission, `install` above), `ready` (it waits for Install on
the TV), `installing`, `done` (the TV runs the newest) or `failed` (it didn't go in; the TV tries again the next day).
Absent: nothing to report. A state an app doesn't know is ignored. The TV sends `status` when it changes, and a
parent's phone shows it with the TV's apps and updates.

### 7.14 A parent taken off the TV

A parent's app takes another parent off by signing a config without them (7.2). Their phone is told with
`{"t":"removed","cfg":{...}}` in room `tvconfig`, `cfg` being a signed config for that TV that no longer lists them:

- the parent's phone that made the change sends it to each parent it took off, once the TV has applied that config;
- the TV sends it too, to every parent dropped by a config it applies, and to anyone on its list who isn't a parent
  and asks for a parent's thing.

The removed phone takes it only for a TV it looks after, from the TV itself or from one of the TV's parents it knows,
only if `cfg` passes 7.2's checks for that TV, doesn't list this phone in `parents`, is signed by its `by` (a parent this
phone knew, unless the TV itself sent it) and isn't older than the last config this phone saw applied. It then forgets
the TV (its pending configs and cards too) and says "You're no longer a parent of [TV]". The removed person stays among
the people the TV can reach.

### 7.15 Installing an app a parent sent (`k9`)

An app that comes to a kids' TV (7.9) is saved in its Downloads and installs only when a parent says so. All
messages are in room `tvconfig`, sealed pairwise like the rest of section 7, so each one is from the key it says.

| t | from | fields |
|---|---|---|
| `appask` | the TV, to each parent whose app says `k9` | `h` (SHA-256 of the app's main file, lowercase hex), `name` (1 to 200 characters, no `/`, `\` or control characters), `size` (all its files, 1 to 2^53 - 1), optional `pkg` (its package name) and `ver` (version name, up to 40 characters) |
| `appok` | a parent | `h`: install exactly that app |
| `appno` | a parent | `h`: not now (the TV forgets it; the file stays in Downloads) |
| `appres` | the TV, to each parent with `k9` | `h`, `r`: `installed`, `perm` (Install updates is off on the TV), `confirm` (Android asks on the TV), `failed`; with `failed` optionally `why` (up to 120 characters) |

- The TV keeps each app it asked about (its parts' places in Downloads and their SHA-256) for 7 days, at most 20.
- It takes `appok` and `appno` only from a current parent, and only for an `h` it keeps. Without `appok` nothing is
  installed. Install puts every part of that app in one PackageInstaller session, checking each part's SHA-256 again as
  it is read; a part that changed or went missing is `failed`.
- When Android asks to confirm on the TV, the TV shows its box with "Ask a parent" and sends `confirm`; the final
  result follows. A parent's phone shows the ask in the TV's menu and as a notification (no name without previews on),
  with Install and Not now, and then the TV's result: "Installed", "Needs Install updates permission", "Failed: …".
- Malformed messages are ignored; `pkg` or `ver` that don't fit are dropped, not the message.

### 7.16 App storage on the TV (`k10`)

Labs mode's App storage lists files an app keeps in its own private folders that can go: received files left there
from before they went to Downloads, copies made for sending, update downloads and caches. A kids' TV has no Settings,
so a parent in Labs mode sees the TV's list under Kids' TV, Storage. All messages are in room `tvconfig`, sealed
pairwise like the rest of section 7.

| t | from | fields |
|---|---|---|
| `files` | a parent | none: send your list |
| `filelist` | the TV, to that parent | `files`: at most 40, newest first, each `id`, `n` (name, 1 to 80 characters), `s` (bytes), `at` (ms), `k` (`received`, `sent`, `update`, `cache`), `w` (a plain folder name, 1 to 40 characters); `count` (all of them), `total` (their bytes), `mv` (true: the TV can move received files to its Downloads) |
| `filedel` | a parent | `ids` (1 to 40) or `all`: `received` or `cache` |
| `filemove` | a parent | `ids` (1 to 40), received files to the TV's Downloads |

- An `id` is a root letter (`c` the app's cache folder, `f` its files folder, `e` its folder on shared storage), `/`, and
  the file's path there. Only a fixed allowlist is ever listed or touched: `c/apps/...`, `e/Download/...`,
  `f/Download/...` (received), `c/outgoing/...`, `c/app/...` (sent copies), `c/updates/...` (updates), `c/files/...`,
  `c/camera/...` and `c/app-info-*` (caches). `.`, `..`, empty parts, `\`, `:` and control characters are refused, and
  so is a file reached through a link. The vault, keys, chats, settings and everything else are never named.
- `k` must be the allowlist's kind for the `id`. Files changed in the last minute are still being written and are left out.
- The TV takes `files`, `filedel` and `filemove` only from a current parent and answers with a fresh `filelist`; after
  `filedel` or `filemove` its `status` (7.7) follows. Malformed messages are ignored.

## 8. Message types

| room | t | plaintext | ratchet | retried | dedupe | needs |
|---|---|---|---|---|---|---|
| `private` | | text | yes | a day, until delivered | (from, id) vs chat | |
| `receipt` | | `{ids, s, [q]}` (1.2) | yes | no | Seen | |
| `unsend`, `keep`, `photo` | | base layer | yes | base layer | base layer | |
| `nearby` | | text | never | no | id | |
| `group` | `state` `invite` `join` `decline` `leave` `sync` `states` `unsend` | 3.4 | yes | a day, until greceipt | Seen (from, envelope id) | g1 |
| `gmsg` | | 3.8 | yes | a day, until greceipt | (g, id) vs chat | g1 |
| `gphoto` | | 3.9 pieces | yes | as photo | (from, gid, i) | g1 |
| `greceipt` | | 3.10 | yes | no | Seen | g1 |
| `call` | `offer` `ring` `answer` `decline` `busy` `cands` `mute` `hangup` | 5.2 | yes | short, 5.2 | call id + t; Seen | c1 |
| `call` | `vjoin` `vleave` `vring` `vdecline` `full` | 6.2 | yes | short, 6.3 and 6.5 | Seen; then 6.4 | v1 |
| `tvconfig` | `config` `applied` `disconnect` | 7 | yes | config and disconnect: a day, until `applied` | Seen; then version rule | k1 |
| `tvconfig` | `update` | 7.5 | yes | no | Seen | k2 |
| `tvconfig` | `card` | 7.6 | yes | no | already a contact | t1 |
| `tvconfig` | `status` `usage` `clear` | 7.7 | yes | no | Seen | k3 |
| `tvconfig` | `appask` `appok` `appno` `appres` | 7.15 | yes | no | Seen | k9 |
| `tvconfig` | `files` `filelist` `filedel` `filemove` | 7.16 | yes | no | Seen | k10 |
| `rich` | | 11.2 | yes | as `private` | as `private` | m1 |
| `mx` | `react` `edit` | 11.3 | yes | a day, until a receipt names its envelope id | Seen | m1 |
| `live` | `typing` `active` | 11.4 | never (v1) | no | Seen | m1 |
| `group` | `react` `edit` | 11.5 | yes | a day, until greceipt | Seen | m1 |
| `mx` | `vote` | 12.3 | yes | as `react` | Seen | m2 |
| `group` | `vote` | 12.3 | yes | as `react` | Seen | m2 |
| `photo`, `gphoto` | | pieces with `kind:"video"` (12.2) | yes | as photo | as photo | m2 |
| `photo`, `gphoto` | | pieces with `kind:"gif"` (25) | yes | as photo | as photo | a1 |
| `intro` | `intro` `yes` `add` | 13 | v1 (it may go to a non-contact) | no (the relays keep it) | Seen | i1 |
| `file` | `offer` `accept` `decline` | 14.1 | to `e1`; v1 otherwise | no | Seen | f1 |
| `receipt` | | `rk` `rp` `wk` `wt` (23) | yes | no | Seen | b1, e1 |

## 9. Size limits

| what | limit |
|---|---|
| envelope on any route | 64 KB (the Bluetooth limit; stays under Nostr relays' usual 64 to 128 KB after wrapping) |
| text in `private` / `gmsg` | 2000 characters |
| photo piece | 16 KB |
| animated GIF (25) | 8 MB (512 pieces, on every route), 400 frames, 2048 px a side, 160 M pixels of frames in all |
| group members | 50 |
| channels per group | 20 (including `main`) |
| people per voice room or group call | 8 |
| group name, member name, contact name | 40 characters |
| channel name | 32 characters |
| group state, canonical | 24 KB |
| held messages from unknown senders | 50 per group |
| state history kept | 32 states; `states` answer at most 16 |
| ids per receipt | 40 |
| candidates | 8 |
| media packet | 28 to 1200 bytes (about 68 typical) |
| quick messages | 24, each 80 characters |
| TV contacts | 50 |
| TV config, canonical | 16 KB |
| TV microphone level (`mic`) | 0 to 100 |
| group code / invite token | 1 hour |
| a reaction | 1 to 16 characters (or empty, to take it back) |
| a 1:1 message id in `re`, `msg` | 1 to 64 of `A-Z a-z 0-9 - _` |
| typing and active status clock difference | 60 s |

Characters means Unicode code points. Anything over a limit is malformed and refused, not cut.

## 10. Test vectors

`python3 code/shared/tools/proto06_ref.py` writes `code/shared/vectors/proto06-vectors.json` and checks it twice (while building it,
then again from the file). Keys A and B are those of `code/shared/vectors/chat-vectors.json`; C is the P-256 key with
private value `0x33` repeated 32 times, the TV `0x44` repeated 32 times; call ephemeral keys are `0x3001`
(offerer, A) and `0x4001` (answerer, B).

Each app's unit tests should:

- `canonical.good`: `canon(value)` equals `canonical`; `canonical.refuse`: parsing then canonicalizing fails.
- `group.states`: `signed` equals the bytes it builds, `hash` matches, every signature verifies.
- `group.accept` and `tv.accept`: same accept or refuse, in order.
- `group.envelopeIds`: same ids.
- `call`: both signatures verify; ECDH, root, tag and every epoch's chain, keys and salts match; sealing each
  `frames` entry gives `packet` exactly; running `receive` in order through one receiver per side gives each
  `result`.
- `tv.disconnect`: accepted at version 3, refused at 4.
- `room`: every `messages` entry's `signed` matches and its signature verifies with its sender; `accept` gives
  each `result` (with the named state, receiver and clock); running `presence` in order through one table
  gives each `in` list (and `changed`); `glare` gives each winner.

`python3 code/shared/tools/i1f1_ref.py` writes `code/shared/vectors/i1f1-vectors.json` (sections 13 to 15): the 4 emoji and `add` ids, which
`intro` and `file` messages are well-formed, the file key from both one-off keys, sealed packets byte for byte, acks,
and what a `MUNINNL1:` link code carries. Muninn checks it in `I1F1VectorsTest`, Huginn in `--chat-selftest` (which
also sends a file between two copies of FileLink on 127.0.0.1, losing some pieces on the way).

`python3 code/shared/tools/p95_ref.py` writes `code/shared/vectors/p95-vectors.json` (sections 7.11, 17 and 18): the move key from the two one-off
keys and the code's secret on both sides, the 4 emoji, the hello, ready and status checks, a sealed part and the sealed
link code, the payload layout and part counts; link ids and when a link lets someone in; `mi` in a group state; which
Kids' TV reconnect messages are well-formed. Muninn checks it in `P95VectorsTest`, Huginn in `--chat-selftest` (the file
next to `chat-vectors.json`).

`python3 code/shared/tools/s1_ref.py` writes `code/shared/vectors/s1-vectors.json` (section 21): route proofs that check out and ones that don't,
hints for three days and which match, bound fields for good and malformed envelopes, a bound v1 envelope (byte for
byte from its nonce) with the changed copies that must not open, a bound ratchet envelope Bob opens on a new session,
and the group state cases. Muninn checks it in `S1Test`, Huginn in `--chat-selftest`.

`python3 code/shared/tools/e1b1_ref.py` writes `code/shared/vectors/e1b1-vectors.json` (section 23): the day key wrapping (both
ECDH keys, the key, the id, a wrapping that opens and ones that don't), the ring envelope, which ring plaintexts are
well-formed and fresh, which receipt fields are taken, and the day key rules. Muninn checks it in `E1B1Test`, Huginn in
`--chat-selftest` (the file next to `chat-vectors.json`).

`python3 code/shared/tools/m1_ref.py` writes `code/shared/vectors/m1-vectors.json` (section 11): every `good` payload is well-formed for its
room and every `refuse` one is refused; running each `reactions` case in order gives `result`; each `edits` case
gives the text, edited flag and edit time; `fresh` gives each `ok`. Muninn checks it in `RichTest`, Huginn in
`--chat-selftest` (the file next to `chat-vectors.json`).

## 11. Messenger features (cap `m1`)

Replies, reactions, edits, typing and active status, in 1:1 chats and groups. Nothing here is new cryptography:
every payload is UTF-8 JSON sealed exactly like a private message, so the reference (`code/shared/tools/m1_ref.py`) only
pins down which payloads are well-formed and how reactions and edits that arrive in any order settle.
Everything in this section goes only to someone whose caps include `m1`; to anyone else the app sends what
an app without `m1` understands (a reply as plain text) or nothing (a reaction, an edit, typing) and says so where it matters
("Raven needs a newer Muninn or Huginn to see edits").

### 11.1 Shared rules

- A 1:1 message id (`re`, `msg` in 11.2 and 11.3) is 1 to 64 characters of `A-Z a-z 0-9 - _` (the apps use UUIDs).
  A group message id is a logical id (2.3).
- `ts` is the sender's clock in ms, an integer 0 <= ts < 2^53.
- `text` is 1 to 2000 characters (code points), not only spaces, like a private message.
- `e` (a reaction) is `""` (take mine back) or 1 to 16 characters, not only spaces, without U+0000..U+001F or U+007F.
  Any emoji will do; the apps offer heart, laugh, thumbs up, wow and sad first (`❤️ 😂 👍 😮 😢`).
- Fields not listed are ignored. An unknown `t` is refused (400).

### 11.2 Room `rich`: a reply

```json
{"text": "Count me in", "re": "<the id of the message it answers>"}
```

Exactly a `private` message in every other way: the envelope `id` is the message id, it is deduped against the
chat, answered with `receipt` `delivered` (and `read`), retried for a day, unsent with `unsend`. `re` may be left
out. The receiver looks the quoted message up in that chat; one that isn't there (deleted, or never arrived)
shows as "Not here any more". A reply to a contact without `m1` goes as `private` with just the text.

### 11.3 Room `mx`: reactions and edits of 1:1 messages

```json
{"t": "react", "msg": "<id>", "e": "❤️", "ts": 1761500130000}
{"t": "edit",  "msg": "<id>", "text": "Fixed the typo", "ts": 1761500130000}
```

- Sent to the other person only, sealed like a private message. The receiver answers `receipt`
  `{"ids": ["<envelope id>"], "s": "delivered"}`; the sender keeps the sealed envelope and sends the same bytes
  again (5 s, 10 s, 20 s, 60 s, then every 5 min) until a receipt names its envelope id, or a day passes.
- `react`: on a message of either person in that chat. Each person has one reaction per message; the receiver
  keeps, per message and person, the reaction with the highest `ts` (an `e` of `""` stays as a marker, so an
  older copy can't bring the reaction back). Equal or lower `ts` changes nothing. Vectors: `reactions`.
- `edit`: only on a message the sender wrote (theirs, from the receiver's side), not unsent, not a photo or voice
  message. The highest `ts` wins; the message shows "(edited)". Vectors: `edits`.
- A `react` or `edit` for a message that isn't there is confirmed and dropped.
- Limit: 20 per sender per 10 s.

### 11.4 Room `live`: typing and active status

```json
{"t": "typing", "on": true, "ts": 1761500130000}
{"t": "active", "on": true, "ts": 1761500130000}
```

- Always sealed with v1 (the static pair key), never through the ratchet, so these never move it. Sent once on
  every route, never retried, never answered. Limit: 30 per sender per 10 s.
- Taken only if `ts` is within 60 s of the receiver's clock (vectors: `fresh`); a late copy from the relays does
  nothing.
- `typing`: `on` while the box has text, at most every 3 s; `off` when it's cleared (a sent message also ends it
  at the receiver). The receiver shows "typing" for 6 s after the last `on`.
- `active`: `on` when the app comes to the front and every 2 minutes while it stays there, `off` when it goes to the
  back, to the 20 contacts with `m1` chatted with most lately. The receiver shows a green dot for 5 minutes after
  the last `on`. Active status is a setting (on by default); off sends `off` once, then nothing, and shows nobody's.

### 11.5 Groups

- `gmsg` (3.8) may carry `"re": "<logical id>"`, the message it answers (in the same group). A `gmsg` whose `re`
  isn't a logical id is refused. Apps without `m1` ignore the field and show the text.
- Room `group` (3.4) gets two more `t`, sent to every member whose caps include `m1`, retried like every group
  control message until a `greceipt` `delivered` for its `id`:

  | t | fields | effect |
  |---|---|---|
  | `react` | `msg` (a gmsg or gphoto id), `e`, `ts` | as 11.3, per member: everyone in the group sees reactions |
  | `edit` | `msg` (the sender's gmsg), `text`, `ts` | as 11.3, only from the message's author |

  Taken only from a member of the receiver's current state.
- Read receipts are the 3.10 ones: Muninn shows the author a check mark and, in a circle, how many members have
  read the message, and who in its "Read by" page. Huginn shows no read marks on group messages. Nobody else learns who read what.
- `@name` in a message's text (any case, not inside a longer word) mentions that member: the app lights the
  message up and says "Raven mentioned you in Family". Nothing extra goes on the wire.

### 11.6 Put straight in a group by a contact

Messenger style: a contact can make a group with you in it without an invite to accept. The admin signs a state
that adds you and sends it (`state`, `op:"add"`) as in 3.4. An app with `m1` that has no state of that group and
isn't joining it accepts it at first sight (3.3 step 2) when all of these hold: the envelope is from a real
contact (not a group peer), `new.by` is that contact, you are in `new.members`, and `new` is well-formed and its
signature verifies. It says "Raven added you to Family". Anything else first-sight still needs the invite or
group code of 3.5. An admin only does this for people whose caps include `m1`; everyone else gets the 3.4 invite.
(`op:"add"` is also sent when someone is let in with a code; that person is joining, so 3.3 step 2 applies to them as
usual.)

### 11.7 Only on the device

Pinned messages, the "New messages" line, the emoji picker, search inside a chat and a chat's photos and voice
page are local: nothing about them goes on the wire, and the two apps don't need to agree on them.

## 12. Messenger additions (cap `m2`)

Photo albums, video messages, polls and link previews. As in section 11 nothing here is new cryptography; the reference
`code/shared/tools/m2_ref.py` writes `code/shared/vectors/m2-vectors.json` (every `good` payload is well-formed for its room and every `refuse` one is
refused; running each `votes` case in order gives `result`; `fallback` gives the text; `video` gives `ok`). Muninn checks it in
`MoreTest`, Huginn in its chat self-test (`MoreWire::selfTest`). Room `piece` in the vectors means the clear fields of a `photo` or `gphoto` piece. Text rules (code points, not
only spaces) are those of 11.1; the m2 fields may not contain control characters other than a newline, and one-line fields
(a question, an option, a title) none at all.

### 12.1 Albums

Photos sent together carry three more clear fields in every piece: `al` (an album id, like a 1:1 message id), `ai` (this
photo's place, 0 to 9) and `an` (how many, 1 to 10, `ai < an`). Each photo is still its own message with its own id,
receipts and retries. An app with `m2` shows the photos of one album from one sender side by side, in `ai` order, as they
arrive; an app without it ignores the fields and shows them one by one. So albums may go to anyone.

### 12.2 Video messages

A piece with `"kind": "video"` and `ms` (1 to 60000) is part of a video message: the joined bytes are an MP4 whose first box
is `ftyp`, at most 2 MB over the internet (as photos, `INTERNET_PIECES`) and 3.2 MB close by. A video is never in an album.
Only to someone whose caps include `m2`: an app without it would try to read it as a photo and refuse it. The receiver keeps it
encrypted like a photo and decrypts it only to play it.

### 12.3 Polls

A poll is a `rich` (11.2) or `gmsg` (3.8) message with one more field:

```json
{"text": "Poll: Pizza or tacos?\n1. Pizza\n2. Tacos", "poll": {"q": "Pizza or tacos?", "a": ["Pizza", "Tacos"], "m": false}}
```

- `q`: 1 to 200 characters, one line. `a`: 2 to 10 answers, each 1 to 100 characters, one line, no two the same (ignoring
  case and spaces at the ends). `m`: several options may be chosen (default false).
- `text` is what an app without `m2` shows: `"Poll: " + q`, then one line per option, `"1. Pizza"` (vectors: `fallback`).
- A vote: room `mx` `{"t": "vote", "msg": "<poll id>", "v": [0], "ts": ...}` in a 1:1 chat, room `group`
  `{"t": "vote", "g", "id", "msg", "v", "ts"}` in a group. `v` is the options chosen, sorted, no repeats, `[]` takes the vote
  back; a single choice poll uses at most one. Each person has one vote per poll: the highest `ts` wins, as reactions do
  (vectors: `votes`). A vote naming an option the poll doesn't have is ignored. Retried and confirmed like a reaction.
- Everyone in the chat or group sees the counts and who voted for what. Only to `m2`.

### 12.4 Link previews

A `rich` or `gmsg` message whose text has a web link may carry what it links to:

```json
{"text": "Look https://example.com", "lp": {"u": "https://example.com", "t": "Example Domain", "d": "...", "i": "<base64 JPEG>"}}
```

- `u`: the `http://` or `https://` link, at most 2000 characters, no spaces. `t`: the page's title, 1 to 200 characters, one
  line. `d`: its description, at most 300. `i`: a small JPEG, at most 24000 base64 characters. `d` and `i` may be left out.
- The sender's phone fetches the page (only when its own Settings › Privacy › Link previews is on) and shrinks the picture.
  The receiver never fetches anything: no website learns who got the link or when they read it. Tapping the preview asks
  "Open example.com?" before the browser does.
- Apps without `m2` ignore `lp` and show the text, so a preview may go to anyone with `m1`.

## 13. Getting in touch through someone you trust (cap `i1`)

Room `intro`, sealed like a private message with v1 (it often goes to someone who isn't a contact yet), sent once on the
first route that takes it (the relays keep it for the receiver). Limit: 20 per sender per 10 s. Only to `i1`.

| t | sent by, to | fields | taken when |
|---|---|---|---|
| `intro` | a contact (the introducer), to each of the two people | `id` (a logical id, 2.3), `k` (the other person's chat key), `n` (their name, 1 to 40 characters), `p` (their mailbox or empty) | it comes from a real contact; nothing happens until the person says yes |
| `yes` | an introduced person, to the other one | `id` | the receiver holds an introduction with that `id` whose `k` is the sender |
| `add` | someone in a group, to another member | `g` (the group) | both are members of the receiver's current state of `g` |

- Both said yes (`yes` both ways, or `add` both ways): each phone adds the other as a contact, not verified. An `add`
  pair is named on both phones by `id = first 32 hex of SHA-256("add|" + g + "|" + the two chat keys sorted, joined by "|")`.
- Both apps offer `add` for the members of a group called Family (or one in the person's own Family folder) who aren't
  contacts yet, once their caps include `i1`.
- Saying no sends nothing.

### 13.5 Checking a contact by pictures (local)

Besides the 6-digit code, both apps show 4 emoji for a pair: byte `i` (0 to 3) of
`SHA-256(UTF-8("emoji code") || shared key)` picks emoji `byte mod 64` from the list `ChatCrypto.EMOJI` (its order is
fixed). The same on both phones means nobody is in between. Nothing goes on the wire.

### 13.6 One-time invite links (local)

A contact code may carry `"1": true`: its invite token works once. The first stranger whose message carries it is added
as a contact at once (no request to accept), on any route (checked before "met close by"), and the token stops adding
anyone at once; it stays a valid invite until `x`, so someone else with the same code gets an ordinary request instead of
being dropped. The app that adds such a code says hi straight away, which is what makes it a contact on the other side.
Every code Huginn and Muninn show (My code, the add-me QR) carries `"1"`, as do one-time links, so adding someone's code
makes both sides contacts with no request in between.

## 14. Files over the internet (cap `f1`)

### 14.1 Straight between phones

Never through the call relay. Only between two people who trust each other with their addresses (5.8.1).

Room `file`, sealed like a call signal and sent once on every route (5.1); deduped with Seen. Limit: 30 per sender per 10 s.
To someone whose caps include `e1` (and who has the ratchet) it goes through the ratchet like `call`; to anyone else it
is v1. Receivers take both (23.1).

| t | fields | |
|---|---|---|
| `offer` | `id` (logical id), `name` (1 to 200 characters, no `/`, `\` or control characters), `size` (1 byte to 4 GB), `sha` (SHA-256, lowercase hex), `e` (a one-off P-256 public key, base64 SPKI), `c` (candidates, 5.8.2) | the sender's phone, after asking a STUN server from a new UDP socket |
| `accept` | `id`, `e`, `c` | the receiver's phone, from its own new socket; with Data saver on mobile data only once the person taps Download |
| `decline` | `id` | the receiver doesn't trust the sender (addresses aren't swapped), said no, or stopped it while it came |

- Key: `SHA-256(UTF-8("muninn file v1") || ECDH-derived key of the two one-off keys (as ChatCrypto.sharedKey) || UTF-8(id))`.
- UDP packets: `type (1 byte) || seq (8 bytes, big endian) || AES-256-GCM(payload)`, nonce `dir (4 bytes) || seq`, AAD
  `type || UTF-8(id)`. `dir` is 0 from the sender, 1 from the receiver. Types: 1 probe, 2 data (`index (4 bytes) || piece`),
  3 ack (`below (4 bytes) || bits (8 bytes)`: every piece below `below` is in, and bit `b` says piece `below + b` is),
  4 done.
- A file of 50 MB or more from someone whose caps include `e1` asks Accept or Decline first (Accept sends `accept`,
  Decline sends `decline`); so does any file while the receiver's Data saver says wait. From a sender without `e1` (it
  gives up after 120 s) a big file is taken as before, without asking.
- The sender waits 30 s for an answer to a file of 2 MB or less, then sends it as in 14.2 (it arrives when they're
  back); a bigger file waits 10 minutes. A `decline` to an offer, no candidates, or no path after probing does the same
  for a small file. A `decline` while the file is on its way means the receiver stopped it.
- Pieces are 1100 bytes (the last one shorter). Both sides probe every candidate of the other until an authentic probe
  comes back (20 s at most); the address it came from is used. The sender keeps at most 96 pieces unconfirmed and sends a
  piece again 500 ms after it last went. The receiver acks at least every 40 ms or 16 new pieces, checks the SHA-256 at
  the end, says done, and saves the file in Downloads. 30 s with nothing heard ends it.

### 14.2 Small files over the relays

A file of at most 2 MB that can't go straight (no trust, or no direct path) goes as `photo` pieces with
`"kind": "file"` and `"fn"` (its name, as above), never in an album. Only to `f1`. The receiver keeps it encrypted like
a photo and also saves it to Downloads as soon as it is whole (Save when that couldn't be done). A received file is
never opened or installed by itself.


## 15. Linked devices, companion style (room `link`)

A tablet or computer (the companion) shows a phone's 1:1 chats and sends through it, while the phone is on and
online. Contacts see nothing new: every message still comes from and goes to the phone. Muninn and Huginn, each as a
companion or as the phone (a Muninn phone usually; Huginn on Windows and Linux and Muninn on a tablet are usually the
companion). Envelopes in room `link` are sealed like private messages (v1) and go by the routing rules below; they are
exempt from the 5-per-10-seconds limit and from the 2000-character cut, because only a paired device gets past the check
below.

**Routes.** A linked pair is usually in one household, behind one internet address that relays rate-limit, so link
traffic MUST stay off the relays except for small live frames:
- History (`chat` pages), media (`get`, `part`) and a `me` that carries `pic` go **only** over the LAN route (the HTTPS
  message endpoint of a device on the same home Wi-Fi, never Direct). When the other device isn't on it, they wait: the
  phone remembers which companions it owes history or its picture and sends them when the companion is on the home Wi-Fi
  again (at least every 30 s it looks, or when the device shows up there). The companion says "History comes over on
  home Wi-Fi".
- Only small live frames may use the internet: `msg`, `send`, `hello`, `unlink`, `result`, `sync`, `setme`, and `me`
  without `pic`. They also go on Bluetooth and the LAN, but to **at most 2 relays** (open ones first), never all of them,
  and they wait for a relay in an outbox of their own (at most 40) that never pushes out a real message (real messages
  are sent first when a relay comes back, and keep their own cap of 200). A `me` sent away from the home Wi-Fi drops
  `pic` and says `"pk": true`: the companion keeps the picture it has until the full `me` comes over the LAN.
- History goes only on "They match" (both sides), on linking again, and on Refresh. Opening a chat or the mirrored list
  never asks for it. Refresh is a `sync` without `f`, which the phone takes at most once in 10 minutes per companion
  (the companion doesn't send more often either); the companion's `sync` right after "They match" carries `"f": true`
  and is always taken.

**Pairing.** The companion shows a link code as a QR code: `MUNINNL1:` then base64url (no padding) of
`{"k": chat key, "p": Nostr mailbox, "n": device name, "i": token}`. The token is 128 random bits, good for 10 minutes and
once. A companion showing its code makes a new one (a new token) a minute before the old one runs out, so the code on
screen always works; a code shown before stays valid until its own 10 minutes are up (a phone may have just scanned
it), and the first `hello` with any of them uses them all up. The code can also be copied as text, for a phone without a
camera. The phone takes the code (its QR
scanner, or the text pasted), keeps the companion, and sends `{"t": "hello", "i": token, "n": its name, "p": its
mailbox}`. The companion accepts a `hello` only with a token it showed, in time; from then on the phone is its phone. A
`hello` with a good token from the phone already linked (linking it again) uses the token up too and sets the pair
back to unchecked: the chats stay, and nothing shows until the emoji are checked again.

**Checking the pair.** After the `hello`, both screens show the 4 emoji of the two devices' pair key (13.5) and
the person confirms "They match" on each ("No" unlinks). Until then the phone sends nothing (`chat`, `msg`, `part`) and
takes nothing (`send`, `sync`, `get`) from that companion, and the companion shows nothing from the phone; on "They
match" the companion sends `{"t": "sync", "f": true}`. A pair linked without this check stays linked. While a phone's chats are mirrored
anywhere, it says "Mirrored to [name]".

**From the phone:**
- `{"t": "chat", "peer", "n", "msgs": [item, ...], "d", "pg": i, "pgs": n}`: one page of a chat's history, over the LAN
  route only. On "They match" and on every `sync` it takes, the phone sends every message it still keeps for each 1:1 chat with a contact (within the
  chat's limit, section 16), oldest first, in pages `pg` 0 to `pgs` - 1, each at most 100 items and at most 20 KB (20480 bytes) of UTF-8
  JSON in `msgs`, so a page fits an envelope on every route. The companion **merges** each page into the chat by message `id` (a
  later copy of an id replaces the earlier one): no page ever wipes what an earlier page or `msg` brought.
- `{"t": "msg", "peer", "n", "m": item, "d"}`: one message in or out, merged the same way.
- `{"t": "result", "id", "ok", "err", "mid"}`: the answer to every `send` (`id` is the `send`'s). `ok` true: sent (or sent
  already, for an `id` seen before), `mid` the new message's id on the phone when there is one. `ok` false with `err`:
  `"confirm"` (the phone hasn't checked the emoji for this companion yet; the companion shows "Confirm the emoji on your
  phone"), `"not a contact"` (shown as "Not a contact on your phone"), or anything else ("Didn't send").
- `{"t": "me", "n": name, "st": status, "cs": custom status (omitted when none), "pic": base64 JPEG (omitted when none)}`:
  the phone's own profile. The phone sends it on "They match", on every `sync`, and whenever its name, status, custom
  status or picture changes. `st` is `online`, `away`, `dnd` or `invisible` (anything else reads as `online`), `n` at most
  40 characters (a blank one is ignored), `cs` cleaned as in section 22, `pic` the phone's profile picture as sent in room
  `profile` (a 256x256 JPEG of at most 24 KB; any other is dropped). `"pk": true` (sent without `pic`, away from the
  home Wi-Fi): keep the picture already here. The companion keeps the last one and, while the
  phone is linked and checked, shows it everywhere as "you": its name, picture and status, never the companion's own.
  The companion's own name and picture stay with its own chats (put away below) and are shown again only when they're
  opened or the phone is unlinked.
- `{"t": "part", ...}`: a file the companion asked for (below).
- `{"t": "unlink"}`.

An item is `{"id", "x", "me", "t"}` and, for a photo, voice message, video message or file, `"f": {"h": SHA-256 of the
file (lowercase hex), "s": bytes, "k": "photo" | "voice" | "video" | "file", "nm": file name}`. `x` is at most 2000
characters; for media it stays the word ("Photo", "Voice message", "Video message", "File: name"). An `f` that isn't
well-formed (or `s` over 100 MB) is ignored and the item shows as words. `d` is the chat's message limit in days
(section 16): the companion deletes older messages, and their files, too.

**From the companion:** `{"t": "send", "peer", "text", "id"}` (the phone sends `text` to its contact `peer` as its own
message, each `id` once, and always answers with `result`), `{"t": "sync", "f"}` (all chats again, see Routes),
`{"t": "get", "h"}`
(below), `{"t": "setme", "n", "st", "cs"}` (the person changed their name, status or custom status on the companion: the
phone is the account, so it takes them as its own, read as in `me`, and sends `me` to every checked companion; a blank
`n` or `st` leaves that one as it is; a picture is changed only on the phone), `{"t": "unlink"}`.

**Sending through the phone.** The companion shows a `send` at once in the chat as "Sending". The phone's `result` (or
its `msg` copy of the message) ends that; `ok` false shows why. With no answer within 60 s it shows "Didn't send · Retry",
and Retry sends the same `id` again (so the phone never sends it twice).

**Media, on the home Wi-Fi only.** The companion asks for a file it doesn't have with `{"t": "get", "h"}`, one file at a
time, and the phone answers with `{"t": "part", "h", "i", "n", "b": base64}`: the file in parts of 192 KB (196608 bytes,
the last one shorter), `i` from 0 to `n` - 1, `n` = ceil(`s` / 196608), each part sent once the one before was taken
(HTTP 200). `get` and `part` go **only** over the LAN route (the HTTPS message endpoint of a device on the same home
Wi-Fi), never over Nostr, a relay, Bluetooth or Direct (a hotspot one of the devices made); either side drops a `get` or
`part` that came any other way, and a device on its own Direct connect hotspot neither asks nor answers. The LAN
message endpoint takes bodies up to 512 KB, but only for room `link`: any other room over 96 KB is answered 413. The phone sends only files of its contacts' 1:1 chats, at most 100 MB. The
companion takes parts only for the file it asked for and only of the expected sizes, joins them, and keeps the file only
if its size and SHA-256 match `s` and `h`; it keeps it in its own encrypted store and shows it in the chat. Until then the
item shows as its word with "On home Wi-Fi". A file that stops coming for a minute is asked for again later.

**This device's own chats.** A companion that had its own account and chats when it links keeps them: nothing is
erased, and nothing of them is hidden. The phone's chats show in their own section ("From your phone") at the top of the
chat list; this device's own chats, folders, groups and contacts stay on show and in use beside them. Unlinking takes
the phone's chats (and their files) off the companion.

Anything in room `link` from a device that isn't paired (and isn't a `hello` answering a live token) is answered 403.

### 15.1 The whole account on the companion (cap `l1`)

With `l1` the companion is the same account as the phone: its name, picture and status, every contact (names,
pictures, verified marks, blocks), every group (the signed state, so its members, and the person's role) and every
folder. Changes made on either device go to the other. Everything still goes in room `link`, sealed between the two
devices only with the keys the pairing set up; nothing new reaches contacts or relays beyond what section 15 allows.
Messages stay as in section 15 (`chat`, `msg`, `send`), and calls stay on the phone (the companion's call button says
"Calls ring on your phone").

**The gate.** The companion puts `"caps": "l1"` in every `sync` it sends (on "They match", on Refresh, and once a run to
a phone linked before it had `l1`). A phone with `l1` marks that companion and answers with `acct` frames; a phone
without it ignores the field, and the pair stays as in section 15. The companion treats the phone as `l1` from the
first `acct` that comes from it, and only then sends its own changes. Huginn takes the companion side and Muninn the
phone side; Huginn as a phone and Muninn as a companion leave `l1` out and link the old way.

**Items.** The account is a set of items, `{"k": kind, "id", "ts": ms, "v": {...}}`, or once deleted a tombstone
`{"k", "id", "ts", "del": true}`. `id` is 1 to 200 characters; `ts` > 0. Kinds:

| `k` | `id` | `v` |
|---|---|---|
| `me` | `"me"` | `{n, st, cs}` as in `me` (section 15) |
| `pic` | `"me"` or a contact's chat key | `{"pic": base64 JPEG}`, a picture as section 22 takes it (anything else shows none) |
| `contact` | the chat key | `{n: their name, nn: my nickname for them (omitted when none), p: mailbox, vf: verified, bl: blocked}` |
| `group` | the group id | `{n: name, role: "owner" \| "admin" \| "member", gone: "" \| "left" \| "removed" (omitted when ""), st: the signed group state (3.2)}` |
| `folder` | the folder id | `{n: name (at most 24 characters), k: [chat keys and "g:" + group id]}` (section 4) |

Unknown kinds and items without a good `id`, `ts` or `v` are dropped.

**Merging: last writer wins, per item.** Each device keeps the last item it knows for every (`k`, `id`). An item that
comes in replaces it when its `ts` is later; at the same `ts` the phone's copy wins (the companion takes the phone's,
the phone keeps its own); the very same item changes nothing. A delete is a tombstone with its own `ts`, so an older
copy coming in late never brings it back, and a later one (added again) does. Tombstones are kept 30 days, then
dropped. The phone gives an item a new `ts` (now, and always later than the one before) whenever its value changes,
and a tombstone when it's gone from the account. A companion that changes an item gives it `ts` = now (later than the
one it had).

**Who changes what.** The companion may change a contact's `nn` and `bl`, remove a contact (a tombstone; the phone
removes the contact, the chat stays), add, rename, delete and fill folders, and change `me` (`setme` as in section 15
also still works). `group` and `pic` items change only on the phone, which drops them from a companion. A change the
phone can't take (a verified mark, say) comes back from it as its own later item, so both end the same.

**Frames.** `{"t": "acct", "items": [item, ...]}` carries changes, either way, as they happen (the phone waits about
1.5 s so many changes go together). `{"t": "acct", "full": true, "items", "pg": i, "pgs": n}` is the phone's whole
account, in pages of at most 200 KB of UTF-8 JSON, on every `sync` it takes from an `l1` companion and when one first
says `l1`. Routes as in section 15: a full set, and any frame with a `pic` item, only over the home Wi-Fi (the phone
owes it until the companion is there again); a change of at most 16 KB with no picture may use the internet, to at
most 2 relays.

**Linking replaces the companion's account.** After a `hello` (a new phone, or the same one linked again), the
companion's next full set replaces everything it kept from the phone instead of merging, and the phone's name,
status and picture become the companion's own (with no picture when the phone has none, on the last page). Nothing
of the companion's old name or picture is left in use. A plain merge would keep a name written later on the companion,
which is why the first full set is taken whole.

**Groups.** To an `l1` companion the phone also sends its group chats as chats with `peer` = `"g:"` + group id (the
last 500 messages each, in `chat` pages, and new ones as `msg`), each item with `"fn"`: the name of who wrote it. A
`send` with `peer` `"g:"` + id from an `l1` companion is posted in that group's main channel as the phone's own
message (the same member), each `id` once, answered with `result` (`err` `"not in group"` when it can't be posted).

**Unlinking.** The companion asks: keep a copy or remove it. Keeping sends `unlink` and leaves the chats, contacts,
groups and folders on the companion, read only (nothing is sent, fetched or taken from that phone until it's linked
again, which then replaces the copy). Removing takes everything that came from the phone off the companion. A phone
that unlinks the companion removes it all, as in section 15.

## 16. Message limits (cap `d1`)

Messages don't stay forever. Every chat, 1:1 and group, has a **limit in days**: messages older than that are deleted on
every device, with everything that belongs to them (photos, voice clips, videos, files, album photos, poll votes, link
previews, cached copies), and an encrypted backup never holds anything past it. The limit is **7 days** unless a Family
group, or two people who keep each other in their Family or Work folder, choose longer, **at most 30**.
`code/shared/tools/d1_ref.py` is the reference and writes `code/shared/vectors/d1-vectors.json`; Muninn checks it in `LimitsTest`, Huginn in
`--chat-selftest` (the file next to `chat-vectors.json`).

### 16.1 What counts

- A message's age is its time on the device (when it was sent or arrived there). One exactly at the limit stays
  (vectors: `sweep`). Apps delete at least every few minutes while running, when a chat opens, and when they start.
- Deleting is local: nothing goes on the wire. Ids of deleted messages are kept as for a delete (two days), so a late
  copy isn't shown again. Receipts, retries and the ratchet are untouched: a sealed copy still waiting for "delivered"
  is dropped with its message (it is long past the Outbox's one day anyway).
- Each app's own "delete sooner" setting (Muninn: Day) may only make it shorter. Kids' TV keeps 7 days and its storage
  trimming (7.7).

### 16.2 Groups: the type and limit in the signed state

A group has a **type**: **Folk** or **Family**. The state (3.2) carries it in two optional fields:

```json
{"g": "...", "v": 5, ..., "kind": "family", "days": 30}
```

- `kind`: `"family"` for a Family group. Left out: a Folk group.
- `days`: 7 to 30, only next to `kind`. Left out: 7.
- A Folk group's state has neither field, so it is exactly the state an app without `d1` takes.
- `"work"` stays valid on the wire (groups made before Folk and Family): such a group is shown and treated as **Folk**
  and keeps its `days`. Apps no longer make one; making it Folk changes nothing, making it Family replaces the kind.
- The limit (vectors: `states`, `days`) is the same for everyone in the group. Only an admin changes it, by signing the
  next state as for any change (3.4, `op` hint `"limit"`); a group can be made with a type from the start.

What each type means (the same in both apps; Huginn `Limits::typeRules`, Muninn `Limits.typeRules`):

| | Folk | Family |
|---|---|---|
| One line | Friends and acquaintances. Easy to join, light to keep. | Your closest, most trusted people. Invite only, checked in person, kept longer. |
| Who adds people | Anyone in the group, by a link or a code: made with `"mi": true` (17.1) | Only admins, face to face (the code's QR) or by a code they hand over; no links (17.1) |
| Checking | | Each member's safety code (13.5) is matched once; the member list marks them Checked and the group asks to check the rest |
| Messages | 7 days | Up to 30 days (an admin picks 7 to 30) |
| Files from members | Small ones arrive by themselves, big ones ask first (14.1) | All arrive by themselves (14.1, Data saver still asks) |
| Contacts | Members aren't added to your contacts (3.7 group peers) | Members who are contacts go in your Family folder, once each (moved out by hand, they stay out) |

Changing the type is an admin's signed `"limit"` state: to Family, `kind` and `days` go in and `mi` comes out; to Folk,
`kind` and `days` come out and `mi` goes in when every other member's caps include `j1`. Huginn `Limits::withType`,
Muninn `Limits.withType`; checked in `--chat-selftest` and `LimitsTest`, `GroupsTest`.

An app without `d1` refuses a state with `kind` (3.2 "exactly these fields"). So an admin's app makes a group Family
only when the caps of every other member include `d1`, invites or adds to a Family group only people whose caps include
`d1` (others: "Needs an update"), and moving back to Folk works with everyone. In a Folk group, members without `d1` keep
their messages; the group's info says so.

### 16.3 1:1 chats: `days` in the envelope

- Each person picks a limit per contact in their **Family or Work folder**: 7 to 30 days. For everyone else it is 7
  (vectors: `mine`).
- Every envelope to a contact whose caps include `d1` carries a clear field `"days": <my limit for them>`, next to
  `caps`. Only to `d1`.
- The receiver takes `days` from **authentic** envelopes (after they open, like `caps`) whose caps include `d1`, when it
  is an integer 7 to 30; anything else is ignored and the value kept before stays (vectors: `envelope`). Until a
  value comes, it counts as 7.
- The chat's limit is the **shorter** of the two (vectors: `pair`). After changing it, an app sends that contact an
  empty receipt (`{"ids": [], "s": "delivered"}`, room `receipt`), so the new `days` reaches them at once.
- With a contact without `d1`, this side deletes after its own limit anyway, and the chat's info says
  "Their app keeps messages until it updates".

### 16.4 First run with limits

On an app's first run with `d1`, every chat it already holds gets its limit at once: anything older is deleted, and the
chat list says so once.

## 17. Group invite links (cap `j1`)

Discord-style links to join a group, for people who aren't contacts yet. They sit beside the group codes of 3.5 and use the
same join path, so an app without `j1` can join by a link too.

### 17.1 Who may make one: "Members can invite"

Admins always may. A group state may carry `"mi": true` ("Members can invite"): then members may make links too. It is
only ever `true`; off is left out, so a group that never turned it on keeps exactly the state an app without `j1` takes
(vectors: `mi`). An app without `j1` refuses a state with `mi`, so an admin's app turns it on only when every other member's caps
include `j1`, and while it is on only people whose caps include `j1` are added (others: "Needs an update").
The change is an admin's signed state like any other (3.4, `op` hint `invites`). A Folk group is made with `mi` on
(16.2).

**Family groups have no links.** In a group whose state has `"kind": "family"`:

- nobody makes a link (the apps hide it and refuse), `mi` is never set (turning it on is refused), and a `link` control
  message is taken but never kept;
- the counting admin lets nobody in by a link (`link_ok` is false whatever the link says), so every link stops at once
  when a group becomes Family, and the counting admin sends its `links` list again (empty) so the screens drop them;
- group codes (3.5) still work: an admin shows one (its QR face to face, or hands the code over).

### 17.2 The link and who counts it

A link is the add page with a group code (3.5) after `#`, which browsers never send to the server:

```
https://ravenor907.github.io/Huginn-Muninn/add/#MUNINNG1:<base64url {"g","gn","a","an","p","i","x"}>
```

`a` is the **counting admin**: the only device that holds the token `i`, counts its uses and lets people in by it. An
admin's own link is counted by that admin; a member's by the group's owner. `x` is when it stops (seconds); a link that
never expires says 100 years from now, so an app without `j1` joining by it never calls it expired. The add page opens the app
(`muninn://add/MUNINNG1:...` on Android); Huginn takes the pasted link.

Expiry: 30 minutes, 1, 6 or 12 hours, 1 day, 7 days (the default) or never. Uses: 1, 5, 10, 25, 50, 100 or no limit.

Control messages in room `group` (3.4), only to members whose caps include `j1`:

| t | sent by, to | fields | effect |
|---|---|---|---|
| `link` | a member, to the counting admin | `inv` (the token), `lx` (expiry ms, 0 never), `max` (0 to 100, 0 no limit) | the admin keeps it if the sender may invite (an admin, or `mi`) |
| `linkoff` | the link's maker or an admin, to the counting admin | `lid` | revoked |
| `links` | the counting admin, to every admin and to each link's maker (their own only) | `list`: `[{"lid","by","x","max","uses"}]` | what the group's screens show |

`lid` is the first 16 hex of SHA-256(UTF-8(`"link|"` + token)) (vectors: `ids`): lists and revokes name a link without
its token.

### 17.3 Joining by a link

The joiner sends `join` with `inv` = the token to `a` (3.4), exactly as for a group code. The counting admin takes it
only while the link is live (reference `link_ok`, vectors: `ok`): not revoked, not past its expiry, fewer uses than its
maximum, and its maker still in the group and still allowed to invite. It then adds the person as for a code and counts
one use, only when they really were added (someone already in gets the state again, uncounted).

Expiry, uses and revoking are decided only on the counting admin's device, never trusted from the joiner. **One admin
counts each link**, so two admins can never let in more people than its maximum at once: no other admin has the token.
If the counting admin stops being an admin or leaves, its links stop working.

## 18. Moving to another device (cap `x1`)

"Move here": everything moves to a new phone, tablet or computer (any of Muninn and Huginn), straight over the home
Wi-Fi, never the internet. Contacts don't re-add or re-verify: the identity, its mailbox and the ratchets move. The old
device is then locked out with its keys erased. Reference `code/shared/tools/p95_ref.py` (`move`), vectors `code/shared/vectors/p95-vectors.json`.

### 18.1 The code and the key

The new device shows `MUNINNM1:` + base64url of `{"v":1, "a":["ip:port", ...], "e": a one-off P-256 public key (base64
SPKI), "s": 16 random bytes (base64url), "n": its name, "app": "muninn" or "huginn"}`, also as the add page with it after
`#` (so a camera opens Muninn). The old device takes it and makes its own one-off key. Both get

```
K = SHA-256("huginn-muninn move v1" || ECDH x of the two one-off keys || s || SHA-256(new key) || SHA-256(old key))
```

and both show the 4 emoji of K (13.5). Nothing is moved until the person taps "They match" on both.

**Only on the home network.** `a` may only hold IPv4 literals in 10/8, 172.16/12, 192.168/16 or link-local 169.254/16
with a port (vectors: `lan`); anything else (a name, a public address) is dropped when the code is read, and a code with
none left isn't a move code. The old device also only uses addresses on its own network (its own address and prefix,
vectors: `sameNetwork`). A move code is only taken when it is scanned or pasted inside the Move screen, never from a link
or the add page, and that screen says "Only continue if the other device is yours and in front of you." The hello's `n`
is the device's or person's chat name, never a computer's host name.

### 18.2 Messages

JSON `POST /api/huginn/v1/move` to `a`, on the port Wi-Fi messages use (HTTPS with any certificate, or HTTP: everything
that matters is sealed with K or checked with it). `mac(t)` is base64 HMAC-SHA256(K, UTF-8(t)); sealed is base64 of a
12-byte nonce, then AES-256-GCM(K) ciphertext and tag, with the named additional data.

| t | from | fields | answer |
|---|---|---|---|
| `hello` | old | `e` (its one-off key), `n` (name), `app`, `m` = mac("hello\|" + e) | `{"m": mac("ready\|" + e)}` |
| `part` | old | `i`, `n` (parts), `h` (SHA-256 hex of the payload), `d` sealed with additional data `"move\|i\|n\|h"` | `{"have": parts here}` |
| `status` | old | | `{"state", "missing": [up to 64 part numbers], "m": mac("status\|" + state + "\|" + h)}` |
| `link` | old, after the move | `d`: a linked-device code (15) sealed with additional data `"move\|link"` | 200 when linked |

`state` is `need` (parts missing; `h` is empty before the first part arrived), `confirm` (all here, its person hasn't
tapped "They match"), `taking`, or `done`. Parts are 512 KB of the payload; the last is shorter. The old device only
believes a status whose `m` checks out, so nobody else on the Wi-Fi can tell it to erase itself.

### 18.3 The steps

1. The old device's person taps "They match": from now on it is **locked**. It takes nothing in, seals nothing and sends
   nothing as you (the ratchets go with the move). It packs the payload, keeps it sealed with K until the move is done,
   and asks `status`, sends the missing parts, and asks again, until `done`.
2. The new device keeps the parts as they came. With all of them and its person's "They match", it checks `h`, takes
   everything in, marks itself `done` and starts again as you.
3. On `done` the old device erases everything (keys, contacts, chats) and shows "Moved to [name]", with **Use as a
   linked device** (it starts fresh and sends `link` with its new link code, 15) or **Start fresh**.
4. Interrupted (Wi-Fi gone, an app closed): both keep their side and go on where they stopped; parts already there aren't
   sent again. K, the one-off keys and the payload are kept in each app's vault (the parts on the new device stay sealed
   with K until they're taken in); only what is shown before the vault opens is kept outside it. The old device stays
   locked until `done`, unless its person stops it with their own password or pattern, or an hour passes with no
   checked status from the new device: then it is theirs again, as before. Moving back is the same thing the other way.
5. `link` is taken only within 10 minutes of `done`, and only after the new device's person says yes to "Link [name]?";
   the new device keeps its `done` state (with when) in the vault it just took in. The old device keeps K only for those
   10 minutes (Start fresh, the time running out or Erase all drop it).

### 18.4 The payload

Entries one after another: `[uint32 BE name length][name, UTF-8][uint32 BE data length][data]` (vectors: `payload`).
Between two of the same app it is the whole backup (each app's backup files, with its settings entry). Between Muninn
and Huginn it is one entry, `@move.json`:

```
{"v":1, "me":{"name","pub","priv","nostr"}, "contacts":[{k,n,p,v,i,r,nick,kc,go,cp,[xc,xf,xa]}], "ratchets":{key: ratchet},
 "chats":{key:[{id,peer,name,text,mine,time,failed,via,state,photo:"",unsent,voice:0}]},
 "groups":[{"s": state, "h":[older states]}], "gchats":{g:[{id,ch,f,n,x,m,t,st}]}, "folders":{"folders":[{id,n,k,c}],"top":[]},
 "profile":{v,col,pic,st,cs}}
```

`priv` is the chat key (PKCS#8, base64), `nostr` the inbox secret (hex). A ratchet is written as Muninn keeps it
(`dhsPriv`, `dhsPub`, `null` where Huginn writes ""). Photos, voice, video and files stay behind: their messages
become words ("Photo"). `xc`, `xf`, `xa` are the local choices no calls, no files and active status hidden. `profile`
is the person's own profile picture, colour and status (22.3), left out when they never set one.

## 19. Per person, on the device only

Set on the press-and-hold sheet, kept in the encrypted contacts, never on the wire: calls and video allowed (off: a
1:1 `offer` or a group `vring` from them is dropped), files allowed (off: their room `file` and `kind:"file"` pieces are
dropped), and whether they see my active status (off: no `active` goes to them). Pin, archive, mute (15 minutes, 1
hour, 8 hours or until turned back on) and mark unread are kept per chat beside them, and so are: how a chat notifies
(every message; one until it's opened, which Settings › Notifications › Reduce notifications makes the default; or,
for a group, only @mentions and replies to me; calls are not messages and ring as before), Hide (someone nearby who
isn't a contact leaves the Nearby list for good, with no notifications) and Delete chat (the conversation and its
messages go from this device, and its row leaves the list until a message comes or goes; a contact stays one, and a
Kids' TV's settings on the parent's phone are untouched). Nothing of this goes on the wire.

A new key that carries an existing contact's mailbox (from nearby, Keep in touch, an invite link, an introduction or a
group) is never a quiet key change: it becomes a request that says "says it replaces [name]", and only the person's
accept (or a code they scanned or pasted themselves) replaces the key. The local ids of group members' `add` requests
(13) are kept apart from introducers' ids, and a match needs the group and the person too.

Keep in touch: a `keep` yes from someone who is already a contact is answered with this device's own yes, once,
so the side still waiting becomes a contact too. Two people who both said yes are contacts at once, not verified; the 4
emoji check is offered. Someone met close by can be called while on the same Wi-Fi or close by.

## 20. Relay envelopes: padding, sending sockets, expiry

How a sealed envelope travels over the Nostr relays, so a relay learns less about who talks to whom. None of it
changes what a receiver must understand, so there is **no cap token**: every app reads these events. `code/shared/tools/pad_ref.py` is the reference and writes `code/shared/vectors/pad-vectors.json`; Muninn checks it in
`SecDataTest`, Huginn in `--nostr-selftest` (the file next to `nostr-vectors.json`).

### 20.1 Padding

The event's `content` is base64 of the outer wrapping `{"e": .., "n": .., "d": ..}` (the throwaway key, the nonce and the
sealed envelope; with `e1` also `"w"`, 23.1; a ring envelope is the same shape, 23.2). The wrapping gets one more field, `"z"`, a string of `0` characters, so that the base64 is
exactly one of these lengths:

- 1024, 4096, 16384 or 32768 characters, the smallest that fits;
- above that, the next multiple of 16384.

The steps stay at 16 KB above 32 KB because many relays refuse events over 64 KB: a photo piece (about 42 KB) becomes
48 KB, never 64 KB.

How: write the wrapping as JSON text `w` (any field order). `base = bytes(w) + 7` (the 7 characters of `,"z":""`).
`target = bucket(4 * ceil(base / 3)) / 4 * 3`. The new text is `w` without its last `}`, then `,"z":"`, then
`target - base` zeros, then `"}`. Its base64 is then exactly `bucket` characters. The padding sits outside the seal and
means nothing; receivers ignore `z` (as they ignore any unknown field).

### 20.2 Sending sockets

Events are published on short-lived sockets that only send: they never send `REQ` and never answer `AUTH`, so a relay
can't tie a published event to the mailbox the same app subscribes to (and may sign in as, NIP-42) on its listening
socket. Every relay gets every event (except a linked device's small frames, which go to at most 2, section 15), each on
a sending socket of its own, which closes about 20 s after the last event given to it. Events still waiting when a relay's listening socket comes back are sent on a new sending socket. Retries
go to every relay, again at 1 h and 6 h while not delivered. The relay still sees the internet
address on both kinds of socket; only a VPN or Tor hides that.

### 20.3 Expiry (NIP-40)

Each event's `created_at` is the time of sending less a random 0 to 599 seconds. Each event carries
`["expiration", "<unix seconds>"]`:

```
expiration = ceil_day(created_at + 600 + 30 * 86400)      ceil_day(t) = the smallest multiple of 86400 >= t
```

30 is the longest message limit (section 16), so relays that follow NIP-40 never drop an event before any chat's limit
has passed, and drop it once it is gone from the devices anyway. It is taken from the blurred `created_at` and is a
whole day (UTC) the same for every chat, so it reveals neither the exact sending time nor the chat's limit. Receivers
ignore the tag. Vectors: `expiry` in `pad-vectors.json` (with `blur` and `longestDays`).

## 21. Proven routes and bound envelopes (cap `s1`)

Everything here is in `code/shared/tools/s1_ref.py`, which writes `code/shared/vectors/s1-vectors.json`.

### 21.1 Every route, always

Every message, receipt and control message goes on every route at once and **always to the internet too** (the
contact's mailbox, when there is one), as section 0 says. A close-by or Wi-Fi route that answers is never a reason
to skip the internet. A route counts as having taken a message ("sent") only when it is **proven** (21.2); an
unproven one is only ever an extra copy. Among several devices that claim the same chat key, a proven one is used
first, then the one seen most recently. Never pick one by its name or by list order.

### 21.2 Route proofs

A proof is a signed object (2.2) by the chat key, without the object itself on the wire: only its `sig` travels.

- **Wi-Fi.** The LocalSend info (announce, `register` and its answer, `prepare-upload`) carries
  `"chatProof": sig` over domain `"huginn-muninn route v1"` and `{"fp": <the device's certificate fingerprint,
  64 uppercase hex>, "k": <chat key>}`. A device is learned only from a certificate it really showed over TLS (the
  answer to my `register`, or the client certificate on a request to me). A multicast announce or a request without
  a certificate is never believed: it is answered with a `register` to that address (at most one every 10 s per
  address). `chatKey` is taken as that device's only if `chatProof` verifies with it against the certificate it
  showed. A wrong proof means no chat key at all; no proof at all is an app without `s1` (unproven).
- **Bluetooth LE and Nearby.** Each side puts a fresh one-off number in its hello, `"n": <16 random bytes as 32
  lowercase hex>`, one per connection. The other side signs it over domain `"huginn-muninn close v1"` and
  `{"k": <chat key>, "n": <that number>}`. Nearby: each phone answers the other's hello with
  `{"t":"proof","sig":...}`. Bluetooth LE: Muninn's HELLO characteristic carries `n`; Huginn's hello (its first
  frame) carries its own `n` and `"proof"` over Muninn's; Muninn answers with a `{"t":"proof","sig":...}` frame.
  Only a number that is exactly 32 lowercase hex characters is ever signed, so a Wi-Fi proof and a close-by proof can
  never stand in for each other.
- Someone whose caps include `s1` always proves: for them an unproven route is never used at all.

### 21.3 Nearby off (hidden from people nearby)

Settings › Privacy › Nearby is one switch (Muninn: Settings › Lock and privacy). Off, a device sends nothing about
itself close by on its own, looks for nobody and lists nobody nearby:

- **Wi-Fi.** No multicast announcement, no answer on multicast, and no scan of the home network ("Look again" and
  Refresh only ask contacts, below). `info` gets 404, and `register` gets 404 unless it comes from a contact (below).
  Only contacts are listed; everyone else already listed is dropped when it is turned off.
- **Bluetooth LE.** Silent: Muninn doesn't advertise, serves nothing on HELLO and lets go of any connection; Huginn
  doesn't scan (an active scan names its adapter), doesn't connect and sends no hello.
- **The chat list.** No Nearby section (no Nearby room, nobody who isn't a contact), and nobody new can message over
  Bluetooth or the Wi-Fi.
- Huginn's HTTPS server shows a throwaway certificate made for that time while off, never its lasting one, which
  anyone close by could follow. Its `register`s show that same certificate, and its info's `fingerprint` and
  `chatProof` are about it, so contacts can still prove the route (21.2).

Contacts keep the private home Wi-Fi route, with nothing announced to strangers:

- Each device remembers where each contact was last reached over a **proven** route (address, port, protocol; at
  most 64, kept in the vault, never shown). Off, every minute on the Wi-Fi (and when it comes back), each contact
  not heard from in the last 2 minutes is sent a `register` straight to that address (at most one every 10 s per
  address). Its answer is only taken if it proves a contact (21.2); anyone else is ignored.
- A contact's own multicast announcement (its `chatKey`, or a hint that matches a contact) is answered the same way,
  straight to it.
- The info such a device sends has no name (`alias` is "Huginn" or "Muninn") and no `chatKey` or `mailbox`: only the
  `hint` below, its `fingerprint` and `chatProof`. It goes only to contacts (or to whoever claims a contact's key,
  who learns only the hint and the proof, which nobody without the chat key can match).
- A `register` to a device with Nearby off is answered (with that info) only when its body's key (`chatKey`, or a
  hint matching a contact) is a contact, its `fingerprint` is the client certificate it showed, and `chatProof`
  verifies against that certificate. The device is then listed like any proven route.

A Kids' TV keeps its own behaviour whatever the switch says (Muninn on a TV in kids' mode ignores it).

A hello or announce may still carry
`"hint": hex(HMAC-SHA256(key = UTF-8 of the chat key, "huginn-muninn hint v1|" + day))[:32]` in place of the name,
`chatKey` and `mailbox`, where day is the number of whole days since 1970 (UTC); Muninn's `prepare-upload` carries
one when a hidden phone sends a file over the Wi-Fi. Both apps match one against each contact's key for yesterday,
today and tomorrow and, on a match, treat the device as that contact (its proof must still check out).

### 21.4 Bound envelopes

To someone whose caps include `s1`, the envelope also carries `"aad": "s1"` and the clear fields are sealed in:

- `bound` = canonical JSON (2.1) of `{"toKey": <the recipient's chat key>}` plus each of `room`, `id`, `fromKey`,
  `mailbox`, `caps`, `invite` (strings) and `days` (an integer) that the envelope has. A field of the wrong type
  makes the envelope malformed.
- v1: AES-GCM associated data `"huginn-muninn s1\n" + bound` instead of `"huginn-muninn"`.
- Ratchet: the header's associated data, then `"\n"`, then the same as v1.
- `"aad"` with any other value is refused. An envelope without it opens with the plain associated data.
- A day after a sender's envelopes first came bound (time for anything they sealed unbound to arrive), only bound
  envelopes are taken from them, and v1 in a room their app always ratchets (2.4) is refused. A sender seals again
  any kept copy (an Outbox copy) that isn't bound when the other side says `s1`.

So nobody can change `days`, `room`, `mailbox` or `caps`, send a copy again under a new `id`, or send someone's
envelope back to them or on to someone else.

Also, with no token of its own: envelope ids already handled (receipts, unsends, keeps and the rest) are kept on disk for
two days (by a hash of sender and id), so a restart doesn't let a copy in again; a linked companion's `send`
(section 15) carries an `id` and the phone sends each id once; and after 20 ratchet envelopes from one key that
don't open within a minute, more from that key wait a while before any ratchet work is done.

### 21.5 Group states

Accepting a state (3.3) refuses `new.v > cur.v + 1000`, and at the same version a state signed by its owner wins
over one that isn't (then the hash decides, 3.3). So no admin can push `v` out of the owner's reach. Also:
an `unsend` for a group message that hasn't arrived yet only stops later copies **from that member**; `sync` (3.6)
is answered only for members of the current state.

### 21.6 Limits on the local routes (no wire change)

- Wi-Fi: only from addresses on this very home network (a private address inside a subnet of one of the device's
  own private addresses); at most 32 connections at once; a chat envelope body at most 96 KB, any other request
  2 MB, read as it arrives; an upload never more than the size accepted; after 3 "wants to send" prompts from one
  address are turned down or left unanswered in 10 minutes, that address waits (429).
- Nearby files: only as many as the accepted offer named, from that phone, none bigger than its biggest; anything
  else is stopped, not written.
- The Nearby room: at most 10 messages in 10 seconds from one address or connection, whatever keys it uses.
- A stranger's private message close by shows as `"Name" · Not a contact · nearby`, in lists and notifications.

### 21.7 Ports: sharing 53317 with the LocalSend app

LocalSend's own app needs port 53317, so Huginn and Muninn leave it free and can run beside it on one device:

- **Discovery** stays on UDP 224.0.0.167:53317, shared: the socket sets `SO_REUSEADDR` (Huginn: `ShareAddress` and
  `ReuseAddressHint`) before it binds, then joins the group on each home-network interface. Every app on the device
  hears the multicast.
- **The server** (HTTPS, or HTTP without a certificate) takes the first free TCP port from **53318** (up to 53337),
  the same on every home-network address. It is announced as `port` in the announcement, the `register` body and
  answer, `info` and `prepare-upload`'s `info`, as LocalSend v2 defines, so LocalSend, Huginn and Muninn find and
  send to each other as before. Every request goes to the `port` a device announced (53317 when it says none).
- **A scan** of the home network (Nearby on) asks each address on 53317 (HTTPS, then HTTP: LocalSend apps and older
  Huginn and Muninn) and on 53318 (HTTPS: Huginn and Muninn).
- A move code (18.1) carries `address:port` with the server's real port.

## 22. Profile pictures and status (cap `p1`)

Everything here is in `code/shared/tools/p1_ref.py`, which writes `code/shared/vectors/p1-vectors.json`. A person has a profile:
a picture, a colour for the disc with their initial that shows when there is no picture, a status (online, away,
do not disturb, invisible) and a custom status text. The picture and colour go to the people they are in touch with;
the status rides on the `active` message that already says their app is open. Nothing of it goes to anyone else.

### 22.1 Status: `st` and `cs` on `active`

Room `live` `active` (11.4) carries two more fields:

```json
{"t": "active", "on": true, "ts": 1761500130000, "st": "away", "cs": "Back at 3"}
```

- `st` is `online`, `away` or `dnd`. `cs` is the custom status: 0 to 80 characters (code points), without U+0000..U+001F
  or U+007F; left out when empty. Both may be left out (an app without `p1`). An app with `p1` refuses (400) an
  `active` or `typing` whose `st` is anything else or whose `cs` is not such text; an app without `p1` ignores the fields.
- **Invisible** is never on the wire: the sender sends `{"t":"active","on":false,"ts":..}`, exactly what an app without
  `p1` sends when it goes to the back, and sends nothing more while invisible (no `on` every 2 minutes). Going back to
  another status sends `on` at once with the new `st`. Changing the status or the custom text sends `active` at once.
- It goes to exactly the people `active` goes to (11.4, 19): the 20 contacts with `m1` chatted with most lately, never
  someone for whom "they see when I'm active" is off, and nothing at all while the Active status setting is off.
- A receiver keeps, in memory only, the last `st` and `cs` per key while that person's `active` holds (5 minutes after
  the last `on`); `off` clears both. What it shows (vectors: `shown`): their `st` while active (`online` when the sender
  gave none); `offline` once it runs out, for someone it ever heard `active` from or whose caps say `m1`; nothing for
  anyone else. With its own Active status off it shows nobody's, as before.

### 22.2 Room `profile`: the picture and colour

```json
{"t": "profile", "v": 3, "pic": "<base64 JPEG>", "col": "#3a7bd5"}
```

- `v` is an integer, 1 <= v < 2^53, higher with every change of the picture or colour. `col` is `#rrggbb` (either case).
  `pic` is standard base64 of a JPEG, exactly 256 x 256, at most 24 KB (24576 bytes) decoded; `""` means no picture
  (clears one). Fields not listed are ignored. Vectors: `profile`, `pictures`.
- A sender makes the picture from the centre square of what was chosen, scaled to 256 x 256, encoded as JPEG at
  quality 80, then 70, 60, 50, 40, 30, 20, the first that is **12 KB (12288 bytes) or less** (vectors: `limits`), and
  with every metadata block removed (as photos are, Photos.scrub). 12 KB keeps one relay event under 64 KB (20.1): a
  24 KB picture would not fit one. The receiver's cap stays 24 KB. A picture that fits none of the qualities is refused
  on the sender's own screen.
- Sealed through the ratchet (it is in the ratchet rooms, like `rich` and `mx`), envelope `id` a fresh UUID, deduped by
  sender and envelope id like `mx` (a copy from another route is dropped before it is opened). Sent once on every route
  (the relays hold it); never answered, never retried on its own: the sender keeps, per person, the version last taken
  by a route, and sends again only when the version changed or no route had taken it yet (every minute, and whenever
  contacts or groups change).
- **Who gets it**: every contact, and every member of every group the sender is in (through that member's own
  session, like every group envelope; a member who is not a contact is a group peer, 3.7), but only those whose caps
  say `p1` (an app without it answers 400 and could not show it), and never anyone blocked, in the trash, or no longer
  in a group together. It goes to a person once per version: when the picture or colour changes, and when a new
  contact or group member appears (or their app first says `p1`). Nobody else ever gets it.
- **Who is taken from**: a contact or a member of a group the receiver is in right now; anyone else is refused (403).
  At most one profile message per sender per 10 seconds (429). Refused (400): malformed, over 24 KB (checked on the
  base64 length before anything is decoded), not a JPEG, or not 256 x 256. A `v` equal to or lower than the one kept
  from that sender is taken (200) and changes nothing (vectors: `versions`).
- A receiver keeps one picture and colour per key, encrypted at rest with everything else (Muninn: files under
  `profile/` sealed with the vault key; Huginn: in the vault), and shows it everywhere the person is shown. The
  picture of someone removed for good goes with them; a group peer's goes when no group is shared any more. Erase
  everything wipes them all.
- The disc colour when no `col` came: one of 12 (vectors: `limits.palette`), picked by the first byte of SHA-256 of the
  key (vectors: `colours.default`), the same in both apps, so a person looks the same everywhere before their profile
  arrives.

### 22.3 Backup and moving

The sender's own profile (`v`, `col`, `pic`, `st`, `cs`) is kept with the identity: it is in the backup and goes with a
move (18). Between Muninn and Huginn `@move.json` (18.4) carries it as
`"profile": {"v", "col", "pic", "st", "cs"}`; the new device keeps `v`, so people who had version 3 take version 4
after the move. Received pictures travel too (they are vault files), with the per-person sent versions, so nothing is
sent twice.

## 23. The ring key and the sealed relay wrapping (caps `b1`, `e1`)

Everything here is in `code/shared/tools/e1b1_ref.py`, which writes `code/shared/vectors/e1b1-vectors.json`. `ECDH(a, B)` below is
`ChatCrypto.sharedKey` (section 0: HKDF over the P-256 secret, salted with both public keys).

### 23.1 Day keys and the relay wrapping (`e1`)

Without `e1`, the relay wrapping (20.1) is sealed to the recipient's chat key alone, so whoever later steals that key
can open every saved relay event and read who sent it, their name and the room. With `e1` it is also sealed to a
**day key** the recipient deletes after a while; once it is gone, the chat key alone opens nothing.

- Each device makes a new P-256 day key when its newest is a day old, keeps the private halves in the vault, and
  deletes each 40 days after it was made (7 days of use, 31 on a relay, a margin). A day key stamped more than a day
  ahead of the clock (made while the clock ran fast) is deleted too and never counts as the newest, so a clock put
  back makes one new day key, not one on every receipt.
  Its id is the first 16 lowercase hex characters of SHA-256 of its SubjectPublicKeyInfo.
- It announces the newest in every receipt (room `receipt`, also caps probes and their answers) to someone whose caps
  include `e1`: `"wk"` (the public key, base64 SPKI) and `"wt"` (when it was made, unix seconds, an integer). The
  first time a contact's caps gain `e1` (or `b1`), an empty receipt `{"ids":[],"s":"delivered"}` carrying them goes to
  that contact at once.
- A receiver takes `wk` and `wt` from an authentic receipt of a contact or group peer when `wk` is a P-256 SPKI of 91
  bytes (uncompressed point) and `wt` is an integer no more than 40 days old and no more than a day ahead; it keeps
  the newest per person (a lower `wt` changes nothing). Anything else in those fields is ignored; the receipt itself
  is still taken.
- A sender wraps with the day key it holds for someone whose caps include `e1`, only while that key is at most 7 days
  old (`wt`, by the sender's clock, and at most a day ahead). Otherwise (no day key, or an older one) it sends the
  wrapping of 20.1. The wrapping:

  ```
  k1  = ECDH(one-off key, recipient's chat key)
  k2  = ECDH(one-off key, day key)
  key = SHA-256(UTF-8("huginn-muninn wrap v2") || k1 || k2)
  {"e": one-off public key (base64 SPKI), "w": day key id, "n": nonce, "d": AES-256-GCM(key, envelope, AAD "huginn-muninn")}
  ```

  then padded (20.1). A receiver opens a wrapping with `"w"` only with its own chat key and the day key that id names
  while it still has it; without `"w"` as in 20.1.
- Room `file` (14.1) goes through the ratchet to someone whose caps include `e1`, so file offers and accepts (with
  their one-off keys and addresses) are forward secret like call signals.
- **After restoring a backup** the backup's day keys may be older than the ones contacts hold. The device makes a new
  day key (whatever the newest's age) and sends an empty receipt `{"ids":[],"s":"delivered"}` carrying `wk` and `wt`
  to every contact whose caps include `e1`, at once when the restored app first opens (Huginn: at the first unlock
  after the restore). For 7 days after the restore, a relay event whose wrapping names a day key this device doesn't
  have is not marked seen (it is tried again when a relay sends it again); after that it is dropped like any event
  that doesn't open. Either way it is never shown.
- Erase everything and Move (on the device moved from) delete the day keys with the vault.

### 23.2 The ring key (`b1`)

A device whose vault is locked can't open anything (every envelope is sealed with keys in the vault), so a call to it
can't ring. With `b1` it can:

- Each device makes a **ring key**: a P-256 key pair and a Nostr secret for its **ring mailbox**, kept in the app's own
  private storage **outside the vault** (Muninn: `ring-key.json` in its files; Huginn: `ring-key.json` in its data
  folder, beside the vault folder), readable by this user only, never in an OS key store. It is not the chat key and
  is not in backups. Erase everything and Move (on the device moved from) delete the file; a new ring key is made only
  once there is a vault again.
- Its public half goes in every receipt to a **contact** whose caps include `b1` (never to a group-only peer, never
  to someone blocked): `"rk"` (base64 SPKI) and `"rp"` (the ring mailbox, 64 lowercase hex). A receiver takes both
  from an authentic receipt of a contact or group peer when `rk` is a P-256 SPKI as in 23.1 and `rp` is 64 lowercase
  hex, and keeps the latest.
- **Blocking someone** makes a new ring key and ring mailbox, so whoever was blocked can't ring the device any more.
  An empty receipt `{"ids":[],"s":"delivered"}` with the new `rk` and `rp` goes at once to every other contact whose
  caps include `b1`. The old ring mailbox is still read (and signed in as) for 2 days, for rings from contacts who
  haven't heard of the new one yet, then dropped.
- A caller sending a 1:1 `offer` (5.2, not one inside a voice room) to someone whose caps include `b1` and whose ring
  key it holds also publishes, once per call, a **ring envelope** to their ring mailbox, padded and expiring like
  any other event (section 20):

  ```
  key = SHA-256(UTF-8("huginn-muninn ring v1") || ECDH(one-off key, ring key))
  {"e": one-off public key, "n": nonce, "d": AES-256-GCM(key, {"t":"ring","call":<call id>,"ts":<the offer's ts>}, AAD "huginn-muninn")}
  ```

  Nothing else is in it: no name, no key, no sender. A ring plaintext is well-formed when `t` is `ring`, `call` is a
  call id (22 base64url characters) and `ts` an integer 0 <= ts < 2^53; other fields are ignored.
- A device subscribes to its ring mailbox on an inbox request of its own (locked or not), and when a relay asks for a
  sign-in (NIP-42) signs in as the ring mailbox too, so a ring envelope arrives without anything from the vault.
  An event for the ring mailbox is only ever tried with the ring key and then dropped: never kept or opened otherwise.
- **Locked**, a well-formed ring that opens and is fresh like an offer (no more than 60 s old, 120 s ahead) rings
  "Incoming call" with no name, as long as any call rings (45 s). Answer is kept (Muninn opens its unlock screen); once
  unlocked, the offer (kept sealed with everything else that came while locked, or fetched from the relays again) is
  answered at once, even if it is up to 105 s old.
  Decline: once unlocked, the offer is answered with `decline`. **Unlocked**, a ring envelope is ignored: the offer
  itself rings, with the name.
- Someone holding a locked device learns only that calls came and when; the relays learn that the ring mailbox got an
  event, as they learn that the mailbox did.

An app without `b1` or `e1` ignores the receipt fields and gets neither ring envelopes nor day key wrappings; with
mixed versions everything falls back to sections 14 and 20.

## 24. Labs bug reports (field `lb`)

Labs mode (tap the raven in Settings 10 times, 10 more to turn it off) lets a tester send a bug report to the
project. A report is an ordinary private message from the tester to the **Labs contact**, built into both apps:

- name `Huginn Muninn Labs`
- chat key (SPKI, base64)
  `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5ihedM8ypmMLI817zKk9CbxL0Tl3Q67Orwj8/UUpgOH9PlIIlw7FQDuIfKBa6BuK2fjw6Iu2RKxNvLdEFfVK+w==`
- mailbox `5ee40952b01671ffec761fc6ff65529fac098b03aed4b7a0b5d69d86e2e93529`

The Labs contact is never shown in the tester's contact list and never gets a chat there.

**Sending.** Over the internet only, to that mailbox, wrapped for that key like any envelope (section 20 and 23),
sealed like a first message to a new contact: always v1 (never the ratchet) and never bound (no `aad`), whatever the
tester's app knows about that key. The report is one envelope in room `private`:

```
{ room: "private", fromKey, mailbox, id, nonce, data,
  lb: { app: "muninn" | "huginn", v: "<version label>", os: "<system>", model: "<device model>", s: "<screenshot id>" } }
```

- No `fromId` and no `caps`. A `fromName` or `cap` is optional and never required by the owner's app.
- `data` opens to the text: UTF-8, trimmed, 1 to 2000 characters (code points).
- `v` is the installed version as the app shows it (`0.98`, `0.99 beta 12`), `os` the system (`Android 15`,
  `Windows 11`, `Linux`), `model` the device model. Each at most 80 characters. Nothing else about the device:
  no logs, no addresses, no other data.
- `s` is there only when a screenshot goes with it: the screenshot's id (1 to 64 characters, letters, digits, `-`).

The screenshot (optional, one JPEG, every metadata block removed as for any photo) goes as photo pieces exactly as
a photo to a contact (room `photo`, `i`, `n`, the same 16 KB pieces), with `id` = the screenshot's id and the same `lb`
(without `s`) in each piece's envelope. At most 130 pieces (the relay limit for a photo). The tester's app waits for the relays to take every
envelope and says whether the report went; it is not retried later.

**Receiving.** Only the app whose own chat key is the Labs key takes envelopes with `lb`. Every other app drops them
quietly (answers 200 and keeps nothing). The Labs contact's app:

- takes them from anyone, with no contact code or invite, but opens each only as v1 with the sender's chat key, and
  refuses a malformed `lb` or text (400);
- takes at most 10 reports a day from one sender (429 after that, pieces too);
- keeps them, with their screenshots, for 30 days in a "Labs reports" list (from the chat list's More row) showing
  the sender's name, the label, system and model, the text and the screenshot;
- matches a screenshot to its report by `s`, whichever arrives first; a report without `s` takes a screenshot from
  the same sender that came within 15 minutes of it;
- sends no receipts for them.

**Replying.** Reply in the Labs reports list makes the sender a contact on the Labs contact's app and opens their
chat. Its messages go as to any new contact (no invite). The tester's app, once it has sent a report, takes a
private message from the Labs key with no invite as a chat request (Requests), like a code shared some other way.

## 25. Animated GIFs (cap `a1`)

A GIF goes as `photo` (or `gphoto`, 3.9) pieces exactly like a photo (16 KB, `i`, `n`, sealed one by one, padded like
any relay envelope, 20.1), with one more clear field in every piece:

```json
{"room": "photo", "id": "..", "i": 0, "n": 37, "kind": "gif"}
```

`code/shared/tools/gif_ref.py` is the reference and writes `code/shared/vectors/gif-vectors.json` (every `good` input
cleans to exactly its `out` with its `frames`; every `refuse` input is refused for its `why`). Muninn checks it in
`GifsTest`, Huginn in its chat self-test (next to `m2-vectors.json`).

### 25.1 Who gets it

- Only to someone whose caps include `a1` (1.3). An app without `a1` refuses an unknown `kind` (400), so everyone else,
  and anyone whose caps aren't known yet, gets the GIF's first frame as an ordinary JPEG photo (no `kind`), made the
  way any photo is. In a group the pieces are sealed per member (3.9), so each member gets what their app shows.
- A linked device (section 15) gets the first frame as a photo (`"k": "photo"`).
- Never in an album: a piece with `kind:"gif"` and `al` is malformed.

### 25.2 Cleaning

The sender cleans the GIF before it goes, and the receiver cleans the joined bytes again before keeping them, so only
the picture survives whatever the other side did. Cleaning keeps:

- the header, always written `GIF89a`;
- the logical screen descriptor and its global color table, as they came;
- every image: descriptor, local color table and image data, with the graphic control extension just before it (the
  last well-formed one, exactly 4 bytes in one sub-block; a broken one is dropped);
- the loop count, from the first `NETSCAPE2.0` or `ANIMEXTS1.0` block whose second sub-block is `01 lo hi`, written
  once, as `21 FF 0B "NETSCAPE2.0" 03 01 lo hi 00`, right after the global color table;
- the trailer `3B` (added when the GIF ends between blocks without one).

Everything else goes: comments, plain text blocks (with the control block before them), every other application
block (XMP, ICC profiles, maker data) and anything after the trailer.

### 25.3 Limits

A GIF is refused (`why` in the vectors) when:

- `gif`: it doesn't start `GIF87a` or `GIF89a`, a block is cut off or unknown, the screen is 0 wide or high, a frame
  is 0 wide or high or reaches past the screen, an LZW code size isn't 2 to 8, or it has no frames;
- `big`: it is over 8 MB, the screen is over 2048 px a side, or its frames add up to more than 160 M pixels;
- `frames`: it has more than 400 frames.

A receiver answers 400 to a GIF that doesn't clean. A piece with `kind:"gif"` may have `n` up to 512 (8 MB); a
receiver keeps at most 3 such GIFs coming in at once (429 to a fourth). Over the internet a GIF may use all 512
pieces. A sender whose GIF is over the limits offers to send it as a still (its first frame, a photo) or as a file
(section 14).

### 25.4 Showing it

A received GIF is decoded only after it cleaned, off the main thread, at no more than the size it's shown at, and a
decoder failure leaves the first frame or the word "GIF". It moves in the chat, in the viewer and on the Kids' TV.
With Less motion (or the system's animations off) it holds its first frame with a play button. It stops when it's
off screen or the app is in the background. The chat list and notifications say "GIF".

## 26. Mute for me, volume, timeouts and bans (cap `g2`)

`code/shared/tools/g2_ref.py` is the reference and writes `code/shared/vectors/g2-vectors.json`; Huginn checks it in
`--chat-selftest` (all of it) and `--call-selftest` (the volume), Muninn in `ModTest`. Huginn `ModWire` and
`Moderation`, Muninn `ModLogic` and `PeopleSound` (net/Mod.kt) with `Groups`.

### 26.1 Mute for me and volume (on the device only)

Set from a person's menu in a voice room, a group's member list or one of their group messages (right click on
Huginn, press and hold on Muninn). Kept per person in the encrypted store, never on the wire, and the same in every
call, room and group:

- **Mute for me**: their voice is silent here (gain 0). With **Fold their messages** (on by default) their group
  messages show as "Muted, tap to show". Unmute is in the same menu.
- **Volume**: 0 to 200 % (whole numbers; anything else is 100, vectors `volume`). Every 20 ms frame of their decoded
  sound is multiplied by it before it is mixed or played: `sample * pct / 100`, truncated toward zero and clipped to
  16 bits (vectors `gain`). Their speaking ring follows what they said, not the volume. It is read again for every
  frame, so it holds across reconnects and new calls.

A 1:1 call or a group call into a chat that is **muted** (its own Mute, section 19) doesn't ring:
it is kept as a missed call in that chat, without a sound or a notification ("Always right away" still rings).

### 26.2 Records

An admin's timeout or ban is a signed object (2.2) with the domain `huginn-muninn gmod v1`:

```json
{"a": "timeout", "g": "<group id>", "k": "<chat key of who>", "by": "<chat key of the admin>",
 "ts": 1762000000000, "until": 1762000600000, "sig": "<base64 DER>"}
```

- `a`: `ban`, `unban`, `timeout` or `untimeout`. Exactly the fields `a`, `g`, `k`, `by`, `ts`, `sig`, plus `until`
  only for `timeout`.
- `g` a group id, `k` and `by` 1 to 200 characters and different, `ts` an integer above 0 (the admin's clock, ms).
- `until` (ms): `ts < until <= ts + 7 days + 1 minute`. The apps offer 10 minutes, 1 hour, 1 day and 1 week.

Taking one (reference `mod_accept`, vectors `records`, `refuse`, `admins`), against the receiver's current state of `g`:

1. Well-formed, as above.
2. `sig` verifies with `by`.
3. The receiver has group `g`, and `by` is in its `admins`.
4. `k` isn't the owner; if `k` is an admin, `by` is the owner (as for removing an admin, 3.4).

Each `(g, k)` has two slots, `ban` (ban, unban) and `timeout` (timeout, untimeout), each keeping the record with the
highest `ts` it took; the same or a lower `ts` changes nothing (vectors `order`). Someone is **banned** while their
`ban` slot holds `ban`, and **timed out** while their `timeout` slot holds `timeout` and the receiver's clock is below
`until` (vectors `active`). An admin's app signs a new record with `ts` above the slot's current one, even if its
clock is behind.

### 26.3 Room `group`: `t:"mod"`

| t | sent by, to | fields | effect |
|---|---|---|---|
| `mod` | an admin, to every member of the current state whose caps include `g2`, and to the person it names | `list`: 1 to 50 records | each record is taken as in 26.2; a list that is empty or longer is malformed (400) |

It is sent again, with every record still in force, to a `g2` member who sends `sync` (3.6, after the `states`
answer) and to someone an admin just added (3.4 `join`). Only to members whose caps include `g2`: an app without it
answers 400 to an unknown `t`.

What every app with `g2` does while someone is timed out or banned in a group:

- drops their `gmsg`, `gphoto` (photos, voice messages, files) and the `react`, `edit` and `vote` control messages,
  and answers `greceipt` `delivered` so their app stops sending again; nothing is stored or shown;
- refuses their room messages for that group (6.2: `vjoin`, `vring`, `vdecline`, `full`; a `vleave` still counts)
  and declines their room offers, and takes them out of a room it is in with them;
- an admin's app refuses their `join` by a code, a link or an invite (3.5, 17.3), and won't invite them, until they
  are unbanned.

**Ban** is a `ban` record, then the admin signs a state without them (3.4 `op:"remove"`) as for Remove. **Unban** is an
`unban` record; they can then join again by a code, a link or an invite. **Cancel** a timeout is an `untimeout`.

The person's own app shows "You're timed out until 3:40 PM" (posting, files and joining voice rooms are refused with
those words) or "You were banned from Family". The group's settings list who is banned (with Unban) and who is timed
out (with the time left and Cancel), for admins.

### 26.4 Apps without `g2`

Nothing changes for them: the state is untouched, so they take it as before, and they get no `mod` messages. Their
posts during a timeout or a ban are still dropped by every app with `g2`. An admin whose app lacks `g2` can add a
banned person back; the apps with `g2` go on dropping that person's posts until an admin with `g2` unbans them.


## 27. File sharing (cap `f2`)

Builds on section 14. One way to send (the clip in every chat and group, drag and drop on Huginn, the share sheet into
a chat on Muninn); the app picks the route; every transfer shows in Transfers with its route, and says why when it
doesn't work. Huginn `Sharing` (sharing.cpp) and `FileLink`, Muninn `Sharing.kt` and `FileLink.kt`; checked in
`--chat-selftest` and `SharingTest`.

### 27.1 The route

In this order (`Sharing::route`, `Sharing.route`):

1. **Same Wi-Fi** ("Direct"): the other side's app says `f2` and they are on this home Wi-Fi as a device whose chat key
   proved its certificate (21.2). The offer and the accept carry a candidate `{"t": "lan", "p": port}`: the port of the
   transfer's own UDP socket and **no address**. The receiver pairs it with the address it already reached that person
   at on this Wi-Fi; nothing new about either side is learned and no address is ever shown. Between two people who
   don't trust each other with their addresses (14.1), the `lan` candidate is the only one, no STUN server is asked,
   and the receiver answers with only its own `lan` candidate.
2. **Straight over the internet** ("Over the internet"): with trust, as 14.1 (the `lan` candidate comes first when
   they are also on the same Wi-Fi; whichever answers first is used, and the row says which).
3. **The relays** ("Over the internet"): a file of 2 MB or less, as 14.2.

Otherwise it can't go, and the row says so. An app without `f2` ignores a `lan` candidate (5.8.2 takes only `srflx`
and `host`).

### 27.2 Group files

A file sent in a group goes once to each other member, by the best route to that member. Its `offer` carries
`"g"`: the group's id (2.3). Room `file` is taken from a member who isn't a contact when the offer names a group both
are in, or when it answers a transfer of mine to them. The receiver saves it under the group's name. A group's file
goes over the relays (14.2) only to a contact, into the chat with them; to anyone else it needs one of the first two
routes, and the row says so. Sent only to members whose caps include `f2` (others: "needs the latest Muninn or
Huginn").

### 27.3 The group's file rules

Two optional fields in the signed state (3.2), changed only by an admin's next state (`op` hint `"files"`):

- `fs`: `"admins"` (only admins can send files) or `"nobody"`. Left out: everyone can.
- `fm`: a whole number of MB, 1 to 4096: files bigger are refused. Left out: no limit.

Every member's app checks an incoming group file against them (and against membership) before anything moves, and
answers `decline` with `"why": "rules"`; it shows the dropped file and the reason ("Refused by the group's file rules:
only admins can send files."). The sender's app checks its own rules first. An app without `f2` refuses a state with
`fs` or `fm` ("exactly these fields"), so an admin's app sets rules only when every other member's caps include `f2`,
and adds someone to a group with rules only when theirs do.

### 27.4 Why it was declined

`decline` may carry `"why"`: `"rules"` (the group's file rules), `"space"` (not enough room on the receiver: it checks
before accepting, with 64 MB to spare) or `"no"` (the person said no). A decline with `why` ends the transfer and the
sender's row says it in words ("Refused by the group's file rules.", "Not enough space on their device.", "They
declined."); it is never sent another way. A decline without `why` is as in 14.1.

### 27.5 Pause and Hold

Either side can pause a transfer that is moving. UDP packet type 5, **hold**: payload 1 byte, `1` paused or `0` going
on, sealed like the others. The side that paused sends hold every second instead of data (the sender) or acks (the
receiver), and hold `0` three times when it goes on. A sender that hears hold `1` stops sending data and sends a probe
every second; a receiver that hears it acks once a second. Any authentic packet counts against the 30 s silence limit,
so an app without `f2` keeps the line open too.

### 27.6 Asking, and where files go

- From a contact: a file under 50 MB arrives by itself, a bigger one asks (14.1). "Always from this person" (on the
  device only) lets every file from them arrive. In a group, the group's type decides (16.2: Folk small ones, Family
  all). Data saver still asks.
- The ask shows who sent it, the name, size and kind, and where it goes, with Accept, Decline and Always from this
  person.
- Files are saved in `Downloads/Huginn/<chat name>` (Huginn) and `Download/Muninn/<chat name>` through MediaStore
  (Muninn), never a hidden app folder. The chat name is made safe: no folders, `..`, control characters, characters
  Windows refuses, trailing dots or spaces, or reserved names (`CON`, `NUL`, `COM1`...). A received file is never
  opened by itself; on a Kids' TV an app installs only after a parent approves it (7.15).
- Each chat's Files lists what was sent and received there (on the device only, kept as long as the longest a chat
  keeps messages), with Open, Show in folder and Delete.

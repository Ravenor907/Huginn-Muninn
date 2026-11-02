#!/usr/bin/env python3
"""Reference for the deterministic parts of the Huginn/Muninn protocol (code/shared/docs/protocol.md),
and the test vectors both apps are checked against (code/shared/vectors/proto06-vectors.json). Run it to
regenerate the vectors; it checks every round trip and every expected rejection as it goes.

Covers:
  - canonical JSON (what gets signed and hashed)
  - signed objects: ECDSA P-256 / SHA-256 with the chat (identity) key, DER, standard base64
  - group state: validation, acceptance against the current state, conflict rule
  - per-recipient envelope ids for group fan-out
  - call: offer/answer signatures, key derivation, epoch keys, media packet seal/open, replay window
  - kids' TV: config and disconnect, signature and acceptance

Same conventions as code/shared/tools/ratchet_ref.py: P-256, public keys as base64 SubjectPublicKeyInfo DER,
HKDF-SHA256, AES-256-GCM. Signatures in the vectors use RFC 6979 (deterministic) so this script
always writes the same file; the apps may sign with random k and only have to verify.
"""
import base64, hashlib, hmac, json, os, re, struct, sys
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidSignature, InvalidTag

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ratchet_ref import b64, pub_der, load_pub, priv_from_int, hkdf, shared_key  # noqa: E402

# ------------------------------------------------------------------ limits (code/shared/docs/protocol.md, "Size limits")

MAX_MEMBERS = 50
MAX_CHANNELS = 20
MAX_ROOM_PEOPLE = 8          # people in one voice room or group call, the caller included
ROOM_TYPES = ("vjoin", "vleave", "vring", "vdecline", "full")
ROOM_PAST_MS, ROOM_AHEAD_MS = 60_000, 120_000   # a room message is fresh if at most 60 s old, 120 s ahead
PRESENCE_TTL_MS = 25 * 60_000                    # a join not heard again for this long counts as gone
ROOM_ANNOUNCE_MS = 10 * 60_000                   # those in a room re-send vjoin this often
B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
MAX_NAME = 40
MAX_CHANNEL_NAME = 32
MAX_QUICK = 24
MAX_QUICK_TEXT = 80
MAX_TV_CONTACTS = 50
MAX_STATE_BYTES = 24 * 1024
MAX_ABOUT = 500             # cap g3: a group's about text, in characters (code points)
MAX_TVCONFIG_BYTES = 16 * 1024
MAX_AHEAD = 1000             # a group state at most this many versions ahead of the current one (cap s1)

EPOCH_BITS = 14              # a new media key every 16384 packets (about 5.5 minutes of 20 ms audio)
REPLAY_WINDOW = 1024
MAX_EPOCH_AHEAD = 2
MEDIA_VERSION = 0x81           # top bits 10: never a STUN message (00) or a JSON envelope (0x7B)
HEADER_LEN = 12
TAG_LEN = 16
MAX_PACKET = 1200
T_AUDIO, T_PING, T_PONG = 0, 1, 2
# c2 only (5.8): video fragments, receiver reports, keyframe requests, resend requests.
T_VIDEO, T_REPORT, T_KEYREQ, T_NACK = 3, 4, 5, 6

D_GROUP = "huginn-muninn group v1"
D_CALL = "huginn-muninn call v1"
D_TVCONFIG = "huginn-muninn tvconfig v1"
D_TVDISCONNECT = "huginn-muninn tvdisconnect v1"
D_ROOM = "huginn-muninn vroom v1"


def b64url(b): return base64.urlsafe_b64encode(b).decode().rstrip("=")
def b64url_d(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

# ------------------------------------------------------------------ canonical JSON

def check_text(s, limit=None):
    """Text fields: no control characters (U+0000..U+001F, U+007F) and at most [limit] characters (code points)."""
    if not isinstance(s, str): raise ValueError("not text")
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in s): raise ValueError("control character")
    if limit is not None and len(s) > limit: raise ValueError("too long")
    return s

def canon(v):
    """Objects: keys (ASCII) in byte order, no spaces. Arrays keep their order. Strings: raw UTF-8,
    only '"' and '\\' escaped (control characters are not allowed at all). Integers: plain decimal,
    0 <= n < 2^53. true/false. No null, no floats."""
    if isinstance(v, bool): return "true" if v else "false"
    if isinstance(v, int):
        if v < 0 or v >= 2 ** 53: raise ValueError("integer out of range")
        return str(v)
    if isinstance(v, str):
        check_text(v)
        return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if isinstance(v, list): return "[" + ",".join(canon(x) for x in v) + "]"
    if isinstance(v, dict):
        for k in v:
            if not k.isascii(): raise ValueError("non-ASCII key")
        return "{" + ",".join(canon(k) + ":" + canon(v[k]) for k in sorted(v)) + "}"
    raise ValueError("type not allowed: %r" % type(v))

def unsigned(obj): return {k: x for k, x in obj.items() if k != "sig"}
def signed_bytes(domain, obj): return (domain + "\n" + canon(unsigned(obj))).encode()
def obj_hash(obj): return hashlib.sha256(canon(obj).encode()).hexdigest()   # over the signed object, "sig" included

def sign(priv, domain, obj):
    sig = priv.sign(signed_bytes(domain, obj), ec.ECDSA(hashes.SHA256(), deterministic_signing=True))
    out = dict(obj); out["sig"] = b64(sig)
    return out

def verify(pub_b64, domain, obj):
    try:
        load_pub(pub_b64).verify(base64.b64decode(obj["sig"]), signed_bytes(domain, obj), ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, KeyError, ValueError):
        return False

# ------------------------------------------------------------------ group state

def check_state(s):
    """Shape and limits; raises ValueError. Does not check the signature."""
    need = {"g", "v", "owner", "by", "name", "admins", "members", "channels", "ts", "sig"}
    # Cap d1 (section 16): "kind" and "days" may be there too, only for a Family or Work group.
    # Cap j1 (section 17.1): "mi" (members can invite) may be there too, only ever true.
    # Cap f2 (section 27.3): the group's file rules, "fs" and "fm", each left out when not set.
    # Cap g3 (section 28): the group's about text, "about", left out when there is none.
    if not need <= set(s) <= need | {"kind", "days", "mi", "fs", "fm", "about"}: raise ValueError("fields")
    if "mi" in s and s["mi"] is not True: raise ValueError("mi")
    if "fs" in s and s["fs"] not in ("admins", "nobody"): raise ValueError("fs")
    if "fm" in s and (type(s["fm"]) is not int or not 1 <= s["fm"] <= 4096): raise ValueError("fm")
    if "about" in s:
        t = s["about"]
        if not isinstance(t, str) or not t.strip() or len(t) > MAX_ABOUT or any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in t):
            raise ValueError("about")
    if "kind" in s and s["kind"] not in ("family", "work"): raise ValueError("kind")
    if "days" in s:
        d = s["days"]
        if "kind" not in s: raise ValueError("days without kind")
        if not isinstance(d, int) or isinstance(d, bool) or not 7 <= d <= 30: raise ValueError("days")
    if len(b64url_d(s["g"])) != 16 or len(s["g"]) != 22: raise ValueError("group id")
    if not isinstance(s["v"], int) or isinstance(s["v"], bool) or s["v"] < 1: raise ValueError("version")
    check_text(s["name"], MAX_NAME)
    if not s["name"].strip(): raise ValueError("empty name")
    keys = [m["k"] for m in s["members"]]
    if keys != sorted(keys) or len(set(keys)) != len(keys): raise ValueError("members not sorted/unique")
    if not 1 <= len(keys) <= MAX_MEMBERS: raise ValueError("member count")
    for m in s["members"]:
        if set(m) != {"k", "n", "p"}: raise ValueError("member fields")
        check_text(m["n"], MAX_NAME)
        if m["p"] and (len(m["p"]) != 64 or any(c not in "0123456789abcdef" for c in m["p"])): raise ValueError("mailbox")
    if s["admins"] != sorted(set(s["admins"])) or not set(s["admins"]) <= set(keys): raise ValueError("admins")
    if s["owner"] not in s["admins"]: raise ValueError("owner not admin")
    if s["by"] not in s["admins"]: raise ValueError("signer not admin in the new state")
    ids = [c["id"] for c in s["channels"]]
    if len(set(ids)) != len(ids) or not 1 <= len(ids) <= MAX_CHANNELS: raise ValueError("channels")
    if s["channels"][0] != {"id": "main", "n": s["channels"][0]["n"], "t": "text"}: raise ValueError("main channel first")
    for c in s["channels"]:
        # Cap g3 (section 28.2): "ao" (only admins post) on a text channel, only ever true.
        if set(c) - {"ao"} != {"id", "n", "t"} or c["t"] not in ("text", "voice"): raise ValueError("channel fields")
        if "ao" in c and (c["ao"] is not True or c["t"] != "text"): raise ValueError("channel fields")
        if c["id"] != "main" and len(b64url_d(c["id"])) != 8: raise ValueError("channel id")
        check_text(c["n"], MAX_CHANNEL_NAME)
    if len(canon(s).encode()) > MAX_STATE_BYTES: raise ValueError("too big")

def accept_state(cur, new):
    """(True, why) if [new] replaces [cur]. cur None means first sight (from an invite): self-consistent only."""
    try: check_state(new)
    except ValueError as e: return False, "malformed: %s" % e
    if not verify(new["by"], D_GROUP, new): return False, "bad signature"
    if cur is None: return True, "first state, signed by one of its own admins"
    if new["g"] != cur["g"]: return False, "other group"
    if new["by"] not in cur["admins"]: return False, "signer is not an admin in the current state"
    if new["owner"] != cur["owner"] and new["by"] != cur["owner"]: return False, "only the owner can hand over ownership"
    # Cap s1 (section 21): never more than 1000 versions ahead, so no admin can push v out of reach of everyone else.
    if new["v"] > cur["v"] + MAX_AHEAD: return False, "too far ahead"
    if new["v"] > cur["v"]: return True, "higher version"
    if new["v"] == cur["v"]:
        # At the same version a state its owner signed wins, so the owner can always settle things.
        if by_owner(new) != by_owner(cur): return (True, "same version, signed by the owner") if by_owner(new) else (False, "the owner's stands")
        if obj_hash(new) < obj_hash(cur): return True, "same version, lower hash"
    return False, "not newer"

def by_owner(s): return s["by"] == s["owner"]

def winner(a, b):
    """Conflict rule on its own: higher v, then the one its owner signed, then lower SHA-256 of the canonical signed state."""
    if a["v"] != b["v"]: return a if a["v"] > b["v"] else b
    if by_owner(a) != by_owner(b): return a if by_owner(a) else b
    return a if obj_hash(a) <= obj_hash(b) else b

def envelope_id(gid, recipient_key):
    """Each member's copy of one group message has its own envelope id (Outbox and receipts are per envelope)."""
    return gid + "-" + hashlib.sha256(recipient_key.encode()).hexdigest()[:8]

# ------------------------------------------------------------------ calls

def call_tag(call_id_b64url): return hashlib.sha256(b"huginn-muninn call tag" + b64url_d(call_id_b64url)).digest()[:2]

def call_root(my_eph, their_eph_b64, call_id_b64url, offer_eph_b64, answer_eph_b64):
    ikm = my_eph.exchange(ec.ECDH(), load_pub(their_eph_b64))
    salt = hashlib.sha256(b64url_d(call_id_b64url) + base64.b64decode(offer_eph_b64) + base64.b64decode(answer_eph_b64)).digest()
    return hkdf(salt, ikm, D_CALL, 32)

def chain(root, e):
    c = root
    for _ in range(e): c = hmac.new(c, b"\x02", hashlib.sha256).digest()
    return c

def epoch_keys(root, e):
    """{"o2a": (key, salt), "a2o": (key, salt)}: o2a is offerer to answerer."""
    km = hkdf(b"\x00" * 32, chain(root, e), "huginn-muninn call keys", 72)
    return {"o2a": (km[0:32], km[32:36]), "a2o": (km[36:68], km[68:72])}

def nonce(salt, seq): return salt + b"\x00\x00\x00\x00" + struct.pack(">I", seq)

def header(ptype, tag, seq, ts): return struct.pack(">BB2sII", MEDIA_VERSION, ptype, tag, seq, ts)

def seal_packet(root, direction, tag, ptype, seq, ts, payload):
    key, salt = epoch_keys(root, seq >> EPOCH_BITS)[direction]
    h = header(ptype, tag, seq, ts)
    pkt = h + AESGCM(key).encrypt(nonce(salt, seq), payload, h)
    if len(pkt) > MAX_PACKET: raise ValueError("packet too big")
    return pkt

# ---- call extras (cap c2, code/shared/docs/protocol.md 5.8)
VIDEO_HEADER = 6
VIDEO_MAX_FRAGMENT = MAX_PACKET - HEADER_LEN - 16 - VIDEO_HEADER   # 1166

def video_fragment(stream, key, rotation, frame, index, count, data):
    assert stream in (0, 1) and rotation in (0, 90, 180, 270) and 0 <= index < count <= 255 and 0 < len(data) <= VIDEO_MAX_FRAGMENT
    return struct.pack(">BBHBB", stream, (1 if key else 0) | ((rotation // 90) << 1), frame & 0xFFFF, index, count) + data

def video_split(stream, frame, key, rotation, vp8):
    n = (len(vp8) + VIDEO_MAX_FRAGMENT - 1) // VIDEO_MAX_FRAGMENT
    if not vp8 or n > 255: return []
    each = (len(vp8) + n - 1) // n
    return [video_fragment(stream, key, rotation, frame, i, n, vp8[i * each:(i + 1) * each]) for i in range(n)]

def video_report(loss, camera, screen, jitter_ms, kbps, rtt_ms):
    return struct.pack(">BBHHH", loss, (1 if camera else 0) | (2 if screen else 0), jitter_ms, kbps, rtt_ms)

def video_nack(stream, frame, indexes):
    return struct.pack(">BHB", stream, frame & 0xFFFF, len(indexes)) + bytes(indexes)

class Receiver:
    """One direction of one call. open() returns (ptype, seq, ts, payload) or raises ValueError(reason)."""
    def __init__(self, root, direction, tag):
        self.root, self.dir, self.tag = root, direction, tag
        self.high = -1; self.bits = 0   # bit i set = (high - i) was accepted

    def open(self, pkt):
        if len(pkt) < HEADER_LEN + TAG_LEN or len(pkt) > MAX_PACKET: raise ValueError("size")
        ver, ptype, tag, seq, ts = struct.unpack(">BB2sII", pkt[:HEADER_LEN])
        if ver != MEDIA_VERSION: raise ValueError("version")
        if ptype not in (T_AUDIO, T_PING, T_PONG, T_VIDEO, T_REPORT, T_KEYREQ, T_NACK): raise ValueError("type")
        if tag != self.tag: raise ValueError("other call")
        if self.high >= 0 and seq + REPLAY_WINDOW <= self.high: raise ValueError("too old")
        if 0 <= seq <= self.high and (self.bits >> (self.high - seq)) & 1: raise ValueError("duplicate")
        cur_epoch = max(self.high, 0) >> EPOCH_BITS
        if (seq >> EPOCH_BITS) > cur_epoch + MAX_EPOCH_AHEAD: raise ValueError("too far ahead")
        key, salt = epoch_keys(self.root, seq >> EPOCH_BITS)[self.dir]
        try: payload = AESGCM(key).decrypt(nonce(salt, seq), pkt[HEADER_LEN:], pkt[:HEADER_LEN])
        except InvalidTag: raise ValueError("not authentic")
        # Only an authentic packet moves the window.
        if seq > self.high:
            shift = seq - self.high if self.high >= 0 else REPLAY_WINDOW
            self.bits = ((self.bits << shift) | 1) & ((1 << REPLAY_WINDOW) - 1)
            self.high = seq
        else:
            self.bits |= 1 << (self.high - seq)
        return ptype, seq, ts, payload

# ------------------------------------------------------------------ voice rooms and group calls (section 6)

def is_id(s, n):
    """Exactly n random bytes as canonical base64url without padding (22 chars for 16, 11 for 8)."""
    if not isinstance(s, str) or len(s) != (4 * n + 2) // 3 or any(c not in B64URL for c in s): return False
    try: raw = b64url_d(s)
    except ValueError: return False
    return len(raw) == n and b64url(raw) == s

def check_room(m):
    """Shape of a room message (6.2); raises ValueError. Does not check the signature."""
    if not isinstance(m, dict) or set(m) != {"t", "call", "g", "ch", "ts", "sig"}: raise ValueError("fields")
    if m["t"] not in ROOM_TYPES: raise ValueError("type")
    if m["call"] != "-": raise ValueError("call")
    if not is_id(m["g"], 16): raise ValueError("group id")
    if not (is_id(m["ch"], 8) or is_id(m["ch"], 16)): raise ValueError("room id")
    ts = m["ts"]
    if not isinstance(ts, int) or isinstance(ts, bool) or not 0 <= ts < 2 ** 53: raise ValueError("ts")

def accept_room(state, me, from_key, m, now):
    """6.2: "ok" (act on it), "stale" (presence only; a stale vring is a missed group call) or why it's refused.
    [state]: the receiver's current state of m's group (None if it has none), [me]: the receiver's chat key."""
    try: check_room(m)
    except ValueError as e: return "malformed: %s" % e
    if not verify(from_key, D_ROOM, m): return "bad signature"
    if state is None or state["g"] != m["g"]: return "unknown group"
    keys = {x["k"] for x in state["members"]}
    if from_key == me: return "from myself"
    if from_key not in keys: return "sender not a member"
    if me not in keys: return "not a member"
    if len(m["ch"]) == 11 and not any(c["id"] == m["ch"] and c["t"] == "voice" for c in state["channels"]):
        return "not a voice room"
    if not now - ROOM_PAST_MS <= m["ts"] <= now + ROOM_AHEAD_MS: return "stale"
    return "ok"

def presence_apply(table, from_key, m, now):
    """6.4. [table]: {(g, ch, key): (in, ts, seenAt)}. [m]: an accepted ("ok" or "stale") vjoin or vleave.
    Newer ts (the sender's clock) wins; [now] (this device's clock) is when it was seen. True if it changed."""
    k = (m["g"], m["ch"], from_key)
    if k in table and m["ts"] <= table[k][1]: return False
    table[k] = (m["t"] == "vjoin", m["ts"], now)
    return True

def presence_in(table, g, ch, now):
    """Who is in room (g, ch): joined, and seen within the last 25 minutes by this device's clock. Sorted keys."""
    return sorted(key for (gg, cc, key), (here, _, seen) in table.items()
                  if gg == g and cc == ch and here and now - seen <= PRESENCE_TTL_MS)

def room_offer_wins(a, b):
    """6.3 glare: when two members offer each other for the same room, the offer from the lower chat key text wins."""
    return a if a < b else b

def room_full(peers):
    """6.3: someone with this many peers in the room answers a new joiner with "full"."""
    return peers >= MAX_ROOM_PEOPLE - 1

# ------------------------------------------------------------------ kids' TV

MAX_MIC = 100

NR_NONE, NR_STANDARD, NR_STRONG, NR_BOTH = 0, 1, 2, 3   # k5 (7.10): the TV's noise removal

def is_hex(v, n):
    return isinstance(v, str) and len(v) == n and all(ch in "0123456789abcdef" for ch in v)

def check_quiet(q):
    """k11 (7.18): at most two quiet times, "school" and "bed", each once; days 1..127; minutes 0..1439, start != end."""
    def small(v, lo, hi): return isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi
    if not isinstance(q, list) or len(q) > 2: raise ValueError("quiet")
    names = []
    for w in q:
        if not isinstance(w, dict) or set(w) != {"n", "d", "s", "e"} or w["n"] not in ("school", "bed"): raise ValueError("quiet")
        if not small(w["d"], 1, 127) or not small(w["s"], 0, 1439) or not small(w["e"], 0, 1439) or w["s"] == w["e"]: raise ValueError("quiet")
        names.append(w["n"])
    if len(set(names)) != len(names): raise ValueError("quiet")

def check_tvconfig(c):
    need = {"tv", "v", "by", "parents", "quick", "contacts", "dictation", "ts", "sig"}
    optional = {"calls", "mic", "nr", "upd", "keep", "chat", "quiet", "pin", "hav", "havw", "havh", "tz", "look", "season", "accent", "lookpick", "kname", "tsz", "dsz", "hc", "cv", "still", "child", "gok", "wait"}   # k2 (7.5) calls and mic; k5 (7.10) nr; k7 (7.12) upd; k8 (7.13) keep; k11 (7.18) chat, quiet, pin, tz; k12 (7.18) hav, havw, havh; k13 (7.18) look, season, accent, lookpick, kname; k14 (7.18) tsz, dsz, hc, cv, still, child, gok, wait: only sent to a device whose app said so
    if not need <= set(c) or not set(c) - need <= optional: raise ValueError("fields")
    if "calls" in c and not isinstance(c["calls"], bool): raise ValueError("calls")
    if "mic" in c and (not isinstance(c["mic"], int) or isinstance(c["mic"], bool) or not 0 <= c["mic"] <= MAX_MIC): raise ValueError("mic")
    if "nr" in c and (not isinstance(c["nr"], int) or isinstance(c["nr"], bool) or not 0 <= c["nr"] <= NR_BOTH): raise ValueError("nr")
    if "upd" in c and not isinstance(c["upd"], bool): raise ValueError("upd")
    if "keep" in c and (not isinstance(c["keep"], int) or isinstance(c["keep"], bool) or not 1 <= c["keep"] <= 7): raise ValueError("keep")
    if "chat" in c and (not isinstance(c["chat"], int) or isinstance(c["chat"], bool) or c["chat"] not in (1, 2)): raise ValueError("chat")
    if "quiet" in c: check_quiet(c["quiet"])
    if "pin" in c:
        p = c["pin"]
        if not isinstance(p, dict) or set(p) not in ({"salt", "h"}, {"salt", "h", "r"}) or not is_hex(p["salt"], 32) or not is_hex(p["h"], 64): raise ValueError("pin")
        # "r": the PBKDF2 rounds, 100000 to 1000000 (absent: 20000, a PIN set before "r").
        if "r" in p and (not isinstance(p["r"], int) or isinstance(p["r"], bool) or not 100000 <= p["r"] <= 1000000): raise ValueError("pin")
    if "hav" in c and not isinstance(c["hav"], bool): raise ValueError("hav")
    if "havw" in c and c["havw"] not in ("b", "y"): raise ValueError("havw")
    if "havh" in c and (not is_hex(c["havh"], 64) or c.get("havw") != "y"): raise ValueError("havh")
    # k11: the IANA time zone quiet times follow.
    if "tz" in c and (not isinstance(c["tz"], str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_+\-/]{0,63}", c["tz"]) or "//" in c["tz"] or c["tz"].endswith("/")): raise ValueError("tz")
    # k13: the colors (a look or season the device doesn't know shows as Dark or Seasonal off), Let the kid choose, and the kid's name.
    for f in ("look", "season"):
        if f in c and (not isinstance(c[f], str) or not re.fullmatch(r"[a-z]{1,16}", c[f])): raise ValueError(f)
    if "accent" in c and (not isinstance(c["accent"], str) or not (c["accent"] in ("gold", "iron", "copper", "jade", "fjord", "heather", "ember") or re.fullmatch(r"#[0-9A-F]{6}", c["accent"]))): raise ValueError("accent")
    if "lookpick" in c and not isinstance(c["lookpick"], bool): raise ValueError("lookpick")
    if "kname" in c and (not isinstance(c["kname"], str) or not c["kname"].strip() or len(c["kname"]) > 40 or any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in c["kname"])): raise ValueError("kname")
    # k14: accessibility, the child's name, group members a parent approved, other families' Kids' Devices still waiting.
    def key_ok(k): return isinstance(k, str) and 0 < len(k) <= 200 and all(ord(ch) > 0x20 and ord(ch) != 0x7F for ch in k)
    def int_in(v, allowed): return isinstance(v, int) and not isinstance(v, bool) and v in allowed
    if "tsz" in c and not int_in(c["tsz"], (100, 115, 130, 150)): raise ValueError("tsz")
    if "dsz" in c and not int_in(c["dsz"], (85, 100, 115, 130)): raise ValueError("dsz")
    for f in ("hc", "still"):
        if f in c and not isinstance(c[f], bool): raise ValueError(f)
    if "cv" in c and c["cv"] not in ("typical", "protan", "deutan", "tritan", "mono"): raise ValueError("cv")
    if "child" in c and (not isinstance(c["child"], str) or not c["child"].strip() or len(c["child"]) > 40 or any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in c["child"])): raise ValueError("child")
    if "gok" in c:
        g = c["gok"]
        if not isinstance(g, list) or len(g) > 200: raise ValueError("gok")
        pairs = []
        for o in g:
            if not isinstance(o, dict) or set(o) != {"g", "k"} or not isinstance(o["g"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", o["g"]) or not key_ok(o["k"]): raise ValueError("gok")
            pairs.append((o["g"], o["k"]))
        if pairs != sorted(set(pairs)): raise ValueError("gok")
    if "wait" in c:
        w = c["wait"]
        if not isinstance(w, list) or len(w) > MAX_TV_CONTACTS or not all(key_ok(k) for k in w) or w != sorted(set(w)): raise ValueError("wait")
    if not isinstance(c["v"], int) or c["v"] < 1: raise ValueError("version")
    if c["parents"] != sorted(set(c["parents"])) or not c["parents"] or c["by"] not in c["parents"]: raise ValueError("parents")
    if not isinstance(c["dictation"], bool): raise ValueError("dictation")
    if len(c["quick"]) > MAX_QUICK: raise ValueError("too many quick messages")
    qids = [q["id"] for q in c["quick"]]
    if len(set(qids)) != len(qids): raise ValueError("quick ids")
    for q in c["quick"]:
        # k14: "e", an emoji a parent picked (1 to 8 code points, no letters, digits, spaces or control characters).
        e_ok = "e" not in q or (isinstance(q["e"], str) and 0 < len(q["e"]) <= 8 and not any(ch.isalnum() or ch.isspace() or ord(ch) < 0x20 or ord(ch) == 0x7F for ch in q["e"]))
        if set(q) - {"e"} != {"id", "text"} or not e_ok or len(b64url_d(q["id"])) != 8: raise ValueError("quick fields")
        check_text(q["text"], MAX_QUICK_TEXT)
        if not q["text"].strip(): raise ValueError("empty quick message")
    ks = [x["k"] for x in c["contacts"]]
    if ks != sorted(set(ks)) or len(ks) > MAX_TV_CONTACTS: raise ValueError("contacts")
    for x in c["contacts"]:
        if set(x) != {"k", "n", "p"}: raise ValueError("contact fields")
        check_text(x["n"], MAX_NAME)
    if len(canon(c).encode()) > MAX_TVCONFIG_BYTES: raise ValueError("too big")

def accept_tvconfig(tv_key, parents, cur, new):
    """[parents]: the TV's current parent keys (set at pairing, then from accepted configs)."""
    try: check_tvconfig(new)
    except ValueError as e: return False, "malformed: %s" % e
    if new["tv"] != tv_key: return False, "for another TV"
    if new["by"] not in parents: return False, "not from a parent"
    if not verify(new["by"], D_TVCONFIG, new): return False, "bad signature"
    if cur is None or new["v"] > cur["v"]: return True, "newer"
    if new["v"] == cur["v"] and obj_hash(new) < obj_hash(cur): return True, "same version, lower hash"
    return False, "not newer"

def accept_disconnect(tv_key, parents, cur_v, d):
    if set(d) != {"tv", "v", "by", "ts", "sig"}: return False, "fields"
    if d["tv"] != tv_key: return False, "for another TV"
    if d["by"] not in parents: return False, "not from a parent"
    if d["v"] != cur_v: return False, "not for the current config"
    if not verify(d["by"], D_TVDISCONNECT, d): return False, "bad signature"
    return True, "ok"

# ------------------------------------------------------------------ vectors

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    v = json.load(open(os.path.join(here, "..", "vectors", "chat-vectors.json")))
    A = serialization.load_der_private_key(base64.b64decode(v["privA"]), None)
    B = serialization.load_der_private_key(base64.b64decode(v["privB"]), None)
    C = priv_from_int(int.from_bytes(b"\x33" * 32, "big"))
    TV = priv_from_int(int.from_bytes(b"\x44" * 32, "big"))
    kA, kB, kC, kTV = v["pubA"], v["pubB"], b64(pub_der(C)), b64(pub_der(TV))
    assert kA == b64(pub_der(A)) and kB == b64(pub_der(B))
    mbA, mbB, mbC = "a1" * 32, "b2" * 32, "c3" * 32
    ok = 0

    def expect(cond, what):
        nonlocal ok
        assert cond, what
        ok += 1

    # ---- canonical JSON
    canon_cases = [
        {"b": 1, "a": [True, False, "x"], "c": {"z": "", "y": 0}},
        {"name": 'Quote " and back\\slash', "rune": "ᚺᚢᚷᛁᚾᚾ", "slash": "a/b", "big": 9007199254740991},
    ]
    canon_vec = [{"value": c, "canonical": canon(c)} for c in canon_cases]
    expect(canon_vec[0]["canonical"] == '{"a":[true,false,"x"],"b":1,"c":{"y":0,"z":""}}', "canon order")
    for bad in [{"t": "line\nbreak"}, {"n": -1}, {"f": 1.5}, {"x": None}, {"n": 2 ** 53}]:
        try: canon(bad); expect(False, "canon should refuse %r" % bad)
        except ValueError: ok += 1
    canon_bad = ['{"t":"line\\nbreak"}', '{"n":-1}', '{"f":1.5}', '{"x":null}', '{"n":9007199254740992}']

    # ---- group states
    g = b64url(bytes(range(16)))
    ch_voice = b64url(bytes([0xA0 + i for i in range(8)]))
    ch_news = b64url(bytes([0xB0 + i for i in range(8)]))
    member = lambda k, n, p: {"k": k, "n": n, "p": p}
    mem_ab = sorted([member(kA, "Raven", mbA), member(kB, "Moose", mbB)], key=lambda m: m["k"])
    mem_abc = sorted(mem_ab + [member(kC, "Bear", mbC)], key=lambda m: m["k"])
    base = {"g": g, "owner": kA, "channels": [{"id": "main", "n": "general", "t": "text"}], "ts": 1761500000000}
    s1 = sign(A, D_GROUP, dict(base, v=1, by=kA, name="Family", admins=[kA], members=mem_ab))
    s2 = sign(A, D_GROUP, dict(base, v=2, by=kA, name="Family", admins=sorted([kA, kB]), members=mem_abc, ts=1761500060000,
                              channels=base["channels"] + [{"id": ch_voice, "n": "Hangout", "t": "voice"}]))
    # Two admins change v2 at the same time: both make a v3.
    s3a = sign(A, D_GROUP, dict(s2, v=3, by=kA, name="Family Hall", ts=1761500120000))
    s3b = sign(B, D_GROUP, dict(unsigned(s2), v=3, by=kB, ts=1761500121000,
                                channels=s2["channels"] + [{"id": ch_news, "n": "news", "t": "text"}]))
    win3 = winner(s3a, s3b)
    lose3 = s3b if win3 is s3a else s3a
    # C, a plain member, tries to rename: refused.
    s4c = sign(C, D_GROUP, dict(unsigned(win3), v=4, by=kC, admins=sorted([kA, kB, kC]), name="Mine now", ts=1761500180000))
    # B removes C (B is an admin).
    s4b = sign(B, D_GROUP, dict(unsigned(win3), v=4, by=kB, members=mem_ab, ts=1761500240000))
    # B tries to take ownership: refused (only the owner hands it over).
    s5b = sign(B, D_GROUP, dict(unsigned(s4b), v=5, by=kB, owner=kB, ts=1761500300000))
    # A hands ownership to B: fine.
    s5a = sign(A, D_GROUP, dict(unsigned(s4b), v=5, by=kA, owner=kB, ts=1761500300000))
    tampered = dict(s2, name="Family!")
    for s in (s1, s2, s3a, s3b, s4b, s5a): check_state(s)
    states = {"s1": s1, "s2": s2, "s3a": s3a, "s3b": s3b, "s4c": s4c, "s4b": s4b, "s5b": s5b, "s5a": s5a, "tampered_s2": tampered}
    accept_cases = [
        (None, "s1", True), ("s1", "s2", True), ("s2", "s1", False), ("s1", "tampered_s2", False),
        ("s2", "s3a", True), ("s2", "s3b", True),
        ("s3a", "s3b", win3 is s3b), ("s3b", "s3a", win3 is s3a), ("s3a", "s3a", False),
        (win3_label := ("s3a" if win3 is s3a else "s3b"), "s4c", False),
        (win3_label, "s4b", True), ("s4b", "s5b", False), ("s4b", "s5a", True), ("s1", "s4b", False),
    ]
    acc_vec = []
    for cur, new, want in accept_cases:
        got, why = accept_state(states[cur] if cur else None, states[new])
        expect(got == want, "accept %s -> %s: %s" % (cur, new, why))
        acc_vec.append({"current": cur, "new": new, "accept": got, "why": why})
    # s1 -> s4b refused: B was no admin in s1. A member that far behind asks for the states in between ("sync").
    group_vec = {
        "g": g,
        "states": {k: {"state": s, "signed": signed_bytes(D_GROUP, s).decode(), "hash": obj_hash(s)} for k, s in states.items()},
        "accept": acc_vec,
        "conflict": {"a": "s3a", "b": "s3b", "winner": win3_label, "loser": "s3b" if win3 is s3a else "s3a",
                     "rule": "same version 3: the lower hash wins; the admin whose change lost re-applies it as v4"},
        "envelopeIds": [{"gid": "000102030405060708090a0b0c0d0e0f", "to": k, "id": envelope_id("000102030405060708090a0b0c0d0e0f", k)} for k in (kA, kB, kC)],
    }
    del lose3

    # ---- call
    call_id = b64url(bytes([0xC0 + i for i in range(16)]))
    ephA, ephB = priv_from_int(0x3001), priv_from_int(0x4001)
    eA, eB = b64(pub_der(ephA)), b64(pub_der(ephB))
    offer = sign(A, D_CALL, {"t": "offer", "call": call_id, "eph": eA})
    answer = sign(B, D_CALL, {"t": "answer", "call": call_id, "eph": eB})
    expect(verify(kA, D_CALL, offer) and verify(kB, D_CALL, answer), "offer/answer verify")
    expect(not verify(kB, D_CALL, offer), "offer is not B's")
    expect(not verify(kA, D_CALL, dict(offer, eph=eB)), "swapped eph fails")
    rootA = call_root(ephA, eB, call_id, eA, eB)
    rootB = call_root(ephB, eA, call_id, eA, eB)
    expect(rootA == rootB, "both ends get the same call root")
    tag = call_tag(call_id)
    epochs = []
    for e in (0, 1, 2):
        k = epoch_keys(rootA, e)
        epochs.append({"epoch": e, "chain": chain(rootA, e).hex(),
                       "o2aKey": k["o2a"][0].hex(), "o2aSalt": k["o2a"][1].hex(), "a2oKey": k["a2o"][0].hex(), "a2oSalt": k["a2o"][1].hex()})
    opus = lambda i: bytes([0xF8]) + bytes((i * 7 + j) & 0xFF for j in range(39))   # 40 bytes, like one 20 ms 16 kbps frame
    sends = [
        ("o2a", T_AUDIO, 0, 0, opus(0)), ("o2a", T_AUDIO, 1, 20, opus(1)), ("o2a", T_AUDIO, 2, 40, opus(2)),
        ("o2a", T_PING, 3, 50, bytes(range(8))), ("a2o", T_PONG, 0, 51, bytes(range(8))),
        ("a2o", T_AUDIO, 1, 60, opus(9)),
        ("o2a", T_AUDIO, 16384, 327700, opus(3)),      # first packet of epoch 1
        ("o2a", T_AUDIO, 16383, 327680, opus(4)),      # late packet from epoch 0, still in the window
        ("o2a", T_AUDIO, 70000, 1400000, opus(5)),     # epoch 4: too far ahead of epoch 1
        ("o2a", T_AUDIO, 32768, 655360, opus(6)),      # epoch 2: two ahead is allowed
        ("o2a", T_AUDIO, 100, 2000, opus(7)),          # far behind the window now
    ]
    frames = []
    for d, t, seq, ts, p in sends:
        pkt = seal_packet(rootA if d == "o2a" else rootB, d, tag, t, seq, ts, p)
        frames.append({"dir": d, "type": t, "seq": seq, "ts": ts, "payload": p.hex(), "packet": pkt.hex()})
    # Receive script: B gets o2a, A gets a2o. Index into frames, or a hand-made packet.
    rxB, rxA = Receiver(rootB, "o2a", tag), Receiver(rootA, "a2o", tag)
    flip = bytearray(bytes.fromhex(frames[2]["packet"])); flip[-1] ^= 1
    hdr_edit = bytearray(bytes.fromhex(frames[2]["packet"])); hdr_edit[11] ^= 1   # timestamp changed: header is AAD
    other = bytearray(bytes.fromhex(frames[0]["packet"])); other[2] ^= 0xFF
    script = [
        ("B", 1, "ok"), ("B", 0, "ok"), ("B", 1, "duplicate"), ("B", "flip", "not authentic"), ("B", "hdr", "not authentic"),
        ("B", 2, "ok"), ("B", 3, "ok"), ("A", 4, "ok"), ("A", 5, "ok"), ("A", 5, "duplicate"),
        ("B", "other", "other call"), ("B", 6, "ok"), ("B", 7, "ok"), ("B", 8, "too far ahead"), ("B", 9, "ok"),
        ("B", 10, "too old"), ("B", 6, "too old"), ("B", 9, "duplicate"),
    ]
    extra = {"flip": bytes(flip), "hdr": bytes(hdr_edit), "other": bytes(other)}
    rx_vec = []
    for who, idx, want in script:
        pkt = extra[idx] if isinstance(idx, str) else bytes.fromhex(frames[idx]["packet"])
        r = rxB if who == "B" else rxA
        try:
            t, seq, ts, payload = r.open(pkt)
            got = "ok"
            f = frames[idx]
            expect(payload.hex() == f["payload"] and seq == f["seq"] and ts == f["ts"] and t == f["type"], "round trip %s" % idx)
        except ValueError as e:
            got = str(e)
        expect(got == want, "receive %s %s: wanted %s, got %s" % (who, idx, want, got))
        rx_vec.append({"receiver": who, "frame": idx if not isinstance(idx, str) else None,
                       "packet": pkt.hex() if isinstance(idx, str) else None, "result": got})

    # ---- call extras (c2): video fragments, a report, a NACK, sealed with this call's keys
    vp8 = bytes((i * 31 + 7) & 0xFF for i in range(2500))
    frags = video_split(0, 65535, True, 90, vp8)
    expect(len(frags) == 3 and all(len(f) <= VIDEO_HEADER + VIDEO_MAX_FRAGMENT for f in frags), "2500 bytes of video in 3 fragments")
    c2_sends = [("o2a", T_VIDEO, 40, 1000, frags[0]), ("o2a", T_VIDEO, 41, 1000, frags[1]), ("o2a", T_VIDEO, 42, 1000, frags[2]),
                ("a2o", T_REPORT, 50, 2000, video_report(7, False, True, 80, 812, 45)),
                ("a2o", T_NACK, 51, 2010, video_nack(0, 65535, [1])), ("a2o", T_KEYREQ, 52, 2020, bytes([1]))]
    c2_packets = []
    for d, t, seq, ts, p in c2_sends:
        pkt = seal_packet(rootA if d == "o2a" else rootB, d, tag, t, seq, ts, p)
        c2_packets.append({"dir": d, "type": t, "seq": seq, "ts": ts, "payload": p.hex(), "packet": pkt.hex()})
    c2_vec = {
        "vp8": vp8.hex(), "stream": 0, "frame": 65535, "key": True, "rotation": 90, "fragments": [f.hex() for f in frags],
        "report": {"loss": 7, "camera": False, "screen": True, "jitter": 80, "kbps": 812, "rtt": 45, "bytes": video_report(7, False, True, 80, 812, 45).hex()},
        "nack": {"stream": 0, "frame": 65535, "indexes": [1], "bytes": video_nack(0, 65535, [1]).hex()},
        "packets": c2_packets,
    }
    call_vec = {
        "callId": call_id, "tag": tag.hex(), "offerEphPriv": "0x3001", "answerEphPriv": "0x4001",
        "offer": offer, "offerSigned": signed_bytes(D_CALL, offer).decode(),
        "answer": answer, "answerSigned": signed_bytes(D_CALL, answer).decode(),
        "ecdh": ephA.exchange(ec.ECDH(), load_pub(eB)).hex(), "root": rootA.hex(), "epochs": epochs,
        "frames": frames, "receive": rx_vec, "c2": c2_vec,
    }

    # ---- voice rooms and group calls
    T = 1761500400000
    temp = b64url(bytes([0xE0 + i for i in range(16)]))          # a group call's room
    no_ch = b64url(bytes([0x90 + i for i in range(8)]))          # an 8-byte id that isn't a voice channel of s2
    rm = lambda who, t, ch, ts: sign(who, D_ROOM, {"t": t, "call": "-", "g": g, "ch": ch, "ts": ts})
    room_msgs = {
        "joinB": rm(B, "vjoin", ch_voice, T), "joinC": rm(C, "vjoin", ch_voice, T + 1000),
        "leaveB": rm(B, "vleave", ch_voice, T + 5000), "joinB2": rm(B, "vjoin", ch_voice, T + 6000),
        "ringA": rm(A, "vring", temp, T), "joinA": rm(A, "vjoin", temp, T), "declineB": rm(B, "vdecline", temp, T + 3000),
        "fullC": rm(C, "full", ch_voice, T + 2000), "joinBnoRoom": rm(B, "vjoin", no_ch, T),
        "joinBmain": rm(B, "vjoin", "main", T), "joinBold": rm(B, "vjoin", ch_voice, T - 10 * 60_000),
        "joinBextra": sign(B, D_ROOM, {"t": "vjoin", "call": "-", "g": g, "ch": ch_voice, "ts": T, "n": "x"}),
        "joinBcall": sign(B, D_ROOM, {"t": "vjoin", "call": call_id, "g": g, "ch": ch_voice, "ts": T}),
        "waveB": sign(B, D_ROOM, {"t": "wave", "call": "-", "g": g, "ch": ch_voice, "ts": T}),
        "joinAasB": rm(A, "vjoin", ch_voice, T),
    }
    st_names = {"s2": s2, "s4b": s4b}
    key_of = {"A": kA, "B": kB, "C": kC}
    room_cases = [
        ("s2", "A", "B", "joinB", T + 500, "ok"), ("s2", "A", "C", "joinC", T + 1500, "ok"), ("s2", "A", "B", "leaveB", T + 5000, "ok"),
        ("s2", "B", "A", "ringA", T + 100, "ok"), ("s2", "C", "A", "ringA", T + 61000, "stale"), ("s2", "A", "B", "declineB", T + 3000, "ok"),
        ("s2", "B", "C", "fullC", T + 2000, "ok"), ("s2", "A", "B", "joinB", T - 121000, "stale"),
        ("s2", "A", "B", "joinBold", T, "stale"), ("s2", "A", "B", "joinBnoRoom", T, "not a voice room"),
        ("s2", "A", "B", "joinBmain", T, "malformed: room id"), ("s2", "A", "B", "joinBextra", T, "malformed: fields"),
        ("s2", "A", "B", "joinBcall", T, "malformed: call"), ("s2", "A", "B", "waveB", T, "malformed: type"),
        ("s2", "C", "B", "joinAasB", T, "bad signature"), ("s2", "A", "A", "joinA", T, "from myself"),
        ("s4b", "A", "C", "joinC", T + 1000, "sender not a member"), ("s4b", "C", "B", "joinB", T, "not a member"),
        (None, "A", "B", "joinB", T, "unknown group"),
    ]
    room_acc = []
    for st, me, frm, msg, now, want in room_cases:
        got = accept_room(st_names[st] if st else None, key_of[me], key_of[frm], room_msgs[msg], now)
        expect(got == want, "room %s from %s at %s: wanted %s, got %s" % (msg, frm, me, want, got))
        room_acc.append({"state": st, "me": me, "from": frm, "msg": msg, "now": now, "result": got})
    # What A's app shows for the Hangout room (and the group call) as messages arrive; "now" is A's clock.
    table = {}
    pres_steps = [
        ("B", "joinB", T + 400), ("C", "joinC", T + 1200), ("B", "leaveB", T + 5100), ("B", "joinBold", T + 5200),
        ("B", "joinB", T + 5300), ("B", "joinB2", T + 6100), (None, None, T + 1200 + PRESENCE_TTL_MS - 1),
        (None, None, T + 1200 + PRESENCE_TTL_MS + 1), (None, None, T + 6100 + PRESENCE_TTL_MS + 1),
    ]
    pres_vec = []
    for frm, msg, now in pres_steps:
        changed = False
        if msg:
            assert accept_room(s2, kA, key_of[frm], room_msgs[msg], now) in ("ok", "stale")
            changed = presence_apply(table, key_of[frm], room_msgs[msg], now)
        inside = ["ABC"[[kA, kB, kC].index(k)] for k in presence_in(table, g, ch_voice, now)]
        pres_vec.append({"from": frm, "msg": msg, "now": now, "changed": changed, "in": inside})
    expect([p["in"] for p in pres_vec] == [["B"], ["B", "C"], ["C"], ["C"], ["C"], ["B", "C"], ["B", "C"], ["B"], []], "presence")
    expect(room_offer_wins(kA, kB) == min(kA, kB) and room_offer_wins(kB, kA) == min(kA, kB), "glare")
    expect(not room_full(6) and room_full(7), "room size")
    room_vec = {
        "g": g, "voiceRoom": ch_voice, "groupCall": temp, "maxPeople": MAX_ROOM_PEOPLE, "freshPastMs": ROOM_PAST_MS,
        "freshAheadMs": ROOM_AHEAD_MS, "presenceTtlMs": PRESENCE_TTL_MS,
        "messages": {k: {"msg": m, "signed": signed_bytes(D_ROOM, m).decode()} for k, m in room_msgs.items()},
        "accept": room_acc, "presence": pres_vec,
        "glare": [{"a": "A", "b": "B", "wins": "A" if room_offer_wins(kA, kB) == kA else "B"},
                  {"a": "B", "b": "C", "wins": "B" if room_offer_wins(kB, kC) == kB else "C"},
                  {"a": "C", "b": "A", "wins": "C" if room_offer_wins(kC, kA) == kC else "A"}],
    }

    # ---- kids' TV
    q = lambda i, text: {"id": b64url(bytes([0xD0 + i] * 8)), "text": text}
    cfg1 = sign(A, D_TVCONFIG, {"tv": kTV, "v": 1, "by": kA, "parents": [kA], "dictation": False, "ts": 1761500000000,
                                "quick": [q(0, "I'm hungry"), q(1, "Can you come here?"), q(2, "I love you"), q(3, "Goodnight ᚺ")],
                                "contacts": sorted([member(kA, "Dad", mbA), member(kB, "Grandma", mbB)], key=lambda m: m["k"])})
    # v2: B becomes a second parent, the list is reordered, dictation on.
    cfg2 = sign(A, D_TVCONFIG, dict(unsigned(cfg1), v=2, parents=sorted([kA, kB]), dictation=True, ts=1761500100000,
                                    quick=[cfg1["quick"][2], cfg1["quick"][0], cfg1["quick"][1]]))
    cfg3c = sign(C, D_TVCONFIG, dict(unsigned(cfg2), v=3, by=kC, parents=sorted([kA, kB, kC]), ts=1761500200000))
    cfg3b = sign(B, D_TVCONFIG, dict(unsigned(cfg2), v=3, by=kB, dictation=False, ts=1761500200000))
    # v4 (k2): calls off, microphone level 80.
    cfg4 = sign(B, D_TVCONFIG, dict(unsigned(cfg3b), v=4, calls=False, mic=80, ts=1761500250000))
    for c in (cfg1, cfg2, cfg3b, cfg4): check_tvconfig(c)
    for bad in (dict(cfg4, mic=101), dict(cfg4, mic=True), dict(cfg4, calls=1), dict(cfg4, x=1)):
        try: check_tvconfig(bad); expect(False, "malformed k2 config refused")
        except ValueError: expect(True, "malformed k2 config refused")
    # v5 (k5): noise removal Standard (WebRTC's) only.
    cfg5 = sign(B, D_TVCONFIG, dict(unsigned(cfg4), v=5, nr=NR_STANDARD, ts=1761500260000))
    check_tvconfig(cfg5)
    for bad in (dict(cfg5, nr=4), dict(cfg5, nr=True), dict(cfg5, nr="1")):
        try: check_tvconfig(bad); expect(False, "malformed k5 config refused")
        except ValueError: expect(True, "malformed k5 config refused")
    # v6 (k7): the parent turned automatic update checks off.
    cfg6 = sign(B, D_TVCONFIG, dict(unsigned(cfg5), v=6, upd=False, ts=1761500270000))
    check_tvconfig(cfg6)
    for bad in (dict(cfg6, upd=0), dict(cfg6, upd="off")):
        try: check_tvconfig(bad); expect(False, "malformed k7 config refused")
        except ValueError: expect(True, "malformed k7 config refused")
    tv_cases = [([kA], None, cfg1, True), ([kA], cfg1, cfg2, True), ([kA, kB], cfg2, cfg1, False),
                ([kA, kB], cfg2, cfg3c, False), ([kA, kB], cfg2, cfg3b, True), ([kA], cfg1, dict(cfg2, dictation=False), False),
                ([kA, kB], cfg3b, cfg4, True), ([kA, kB], cfg4, cfg5, True), ([kA, kB], cfg5, cfg6, True)]
    tv_acc = []
    names = {id(cfg1): "cfg1", id(cfg2): "cfg2", id(cfg3c): "cfg3c", id(cfg3b): "cfg3b", id(cfg4): "cfg4", id(cfg5): "cfg5", id(cfg6): "cfg6"}
    for parents, cur, new, want in tv_cases:
        got, why = accept_tvconfig(kTV, parents, cur, new)
        expect(got == want, "tv accept: " + why)
        tv_acc.append({"parents": ["A" if k == kA else "B" for k in parents], "current": names.get(id(cur)) if cur else None,
                       "new": names.get(id(new), "cfg2 with dictation changed after signing"), "accept": got, "why": why})
    disc = sign(B, D_TVDISCONNECT, {"tv": kTV, "v": 3, "by": kB, "ts": 1761500300000})
    expect(accept_disconnect(kTV, [kA, kB], 3, disc)[0], "disconnect ok")
    expect(not accept_disconnect(kTV, [kA, kB], 4, disc)[0], "stale disconnect refused")
    expect(not accept_disconnect(kTV, [kA], 3, disc)[0], "disconnect from a non-parent refused")
    tv_vec = {
        "configs": {k: {"config": c, "signed": signed_bytes(D_TVCONFIG, c).decode(), "hash": obj_hash(c)}
                    for k, c in (("cfg1", cfg1), ("cfg2", cfg2), ("cfg3c", cfg3c), ("cfg3b", cfg3b), ("cfg4", cfg4), ("cfg5", cfg5), ("cfg6", cfg6))},
        "accept": tv_acc,
        "disconnect": {"msg": disc, "signed": signed_bytes(D_TVDISCONNECT, disc).decode(),
                       "acceptWhenCurrentV": 3, "refuseWhenCurrentV": 4},
    }

    vectors = {
        "about": "Huginn/Muninn test vectors (code/shared/docs/protocol.md), made by code/shared/tools/proto06_ref.py. Static keys A and B are "
                 "those of chat-vectors.json; C is the P-256 key with private value 0x33 repeated 32 times, TV 0x44 repeated "
                 "32 times. Call ephemeral keys: offerer (A) 0x3001, answerer (B) 0x4001. Signatures here are RFC 6979 "
                 "(deterministic) so the file never changes; apps may sign with random k and must only verify. 'signed' is "
                 "the exact UTF-8 text that is signed (domain line, newline, canonical JSON without sig). 'hash' is SHA-256 "
                 "of the canonical JSON with sig. Packets and keys are hex. Every 'accept' and 'receive' step must give the "
                 "same result, in the order listed (receive steps share one receiver per side).",
        "keys": {"A": kA, "B": kB, "C": kC, "TV": kTV, "mailboxA": mbA, "mailboxB": mbB, "mailboxC": mbC},
        "domains": {"group": D_GROUP, "call": D_CALL, "tvconfig": D_TVCONFIG, "tvdisconnect": D_TVDISCONNECT, "room": D_ROOM},
        "canonical": {"good": canon_vec, "refuse": canon_bad},
        "group": group_vec,
        "call": call_vec,
        "room": room_vec,
        "tv": tv_vec,
        "pairKeyAB": shared_key(A, base64.b64decode(kA), kB).hex(),
    }
    path = os.path.normpath(os.path.join(here, "..", "vectors", "proto06-vectors.json"))
    with open(path, "w") as f:
        json.dump(vectors, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("wrote", path)
    print("group: %d states, %d acceptance checks, conflict winner %s" % (len(states), len(acc_vec), win3_label))
    print("call: %d packets sealed, %d receive steps, epochs 0..2 derived, both ends agree on the root" % (len(frames), len(rx_vec)))
    print("room: %d messages, %d acceptance checks, %d presence steps" % (len(room_vec["messages"]), len(room_acc), len(pres_vec)))
    print("tv: %d configs, %d acceptance checks, disconnect checked" % (len(tv_vec["configs"]), len(tv_acc)))
    print("all %d checks passed" % ok)

    # Re-read the file and check it independently of the objects in memory.
    check_file(path)

def check_file(path):
    v = json.load(open(path))
    n = 0
    for c in v["canonical"]["good"]:
        assert canon(c["value"]) == c["canonical"]; n += 1
    st = {k: x["state"] for k, x in v["group"]["states"].items()}
    for k, x in v["group"]["states"].items():
        assert signed_bytes(D_GROUP, x["state"]).decode() == x["signed"] and obj_hash(x["state"]) == x["hash"]; n += 1
    for a in v["group"]["accept"]:
        assert accept_state(st[a["current"]] if a["current"] else None, st[a["new"]])[0] == a["accept"]; n += 1
    c = v["call"]
    tag = bytes.fromhex(c["tag"])
    rB = Receiver(bytes.fromhex(c["root"]), "o2a", tag); rA = Receiver(bytes.fromhex(c["root"]), "a2o", tag)
    for s in c["receive"]:
        pkt = bytes.fromhex(s["packet"] if s["packet"] else c["frames"][s["frame"]]["packet"])
        try: (rB if s["receiver"] == "B" else rA).open(pkt); got = "ok"
        except ValueError as e: got = str(e)
        assert got == s["result"], (s, got); n += 1
    x = c["c2"]
    assert [f.hex() for f in video_split(x["stream"], x["frame"], x["key"], x["rotation"], bytes.fromhex(x["vp8"]))] == x["fragments"]; n += 1
    for f in x["packets"]:
        assert seal_packet(bytes.fromhex(c["root"]), f["dir"], tag, f["type"], f["seq"], f["ts"], bytes.fromhex(f["payload"])).hex() == f["packet"]; n += 1
    for f in c["frames"]:
        root = bytes.fromhex(c["root"])
        assert seal_packet(root, f["dir"], tag, f["type"], f["seq"], f["ts"], bytes.fromhex(f["payload"])).hex() == f["packet"]; n += 1
    r = v["room"]
    keys = v["keys"]
    rst = {"s2": st["s2"], "s4b": st["s4b"]}
    for k, x in r["messages"].items():
        assert signed_bytes(D_ROOM, x["msg"]).decode() == x["signed"]; n += 1
    for a in r["accept"]:
        got = accept_room(rst[a["state"]] if a["state"] else None, keys[a["me"]], keys[a["from"]], r["messages"][a["msg"]]["msg"], a["now"])
        assert got == a["result"], (a, got); n += 1
    table = {}
    for p in r["presence"]:
        if p["msg"]:
            assert presence_apply(table, keys[p["from"]], r["messages"][p["msg"]]["msg"], p["now"]) == p["changed"]
        assert presence_in(table, r["g"], r["voiceRoom"], p["now"]) == sorted(keys[x] for x in p["in"]); n += 1
    for gl in r["glare"]:
        assert room_offer_wins(keys[gl["a"]], keys[gl["b"]]) == keys[gl["wins"]]; n += 1
    print("re-read %s: %d checks passed" % (os.path.basename(path), n))

if __name__ == "__main__":
    main()

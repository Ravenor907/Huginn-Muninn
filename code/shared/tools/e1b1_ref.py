#!/usr/bin/env python3
"""Reference for caps b1 and e1 (shared/docs/protocol-0.6.md, section 23), and the test vectors both apps check
(shared/vectors/e1b1-vectors.json). Run it to write the vectors; it checks every case twice (building, then from
the file).

 - e1: the relay wrapping sealed to the recipient's chat key and to one of its day keys ("wrap keys"), so a stolen
   chat key and saved relay traffic don't show who sent what once the day key is gone; which day keys a sender may
   use and how long the recipient keeps them; and room "file" goes through the ratchet.
 - b1: the ring key outside the vault, the ring envelope sealed to it (a call id and a time, nothing more), which ring
   plaintexts are well-formed and which are fresh enough to ring.
 - Both: the "rk", "rp", "wk" and "wt" fields a receipt carries, and which of them a receiver takes.
"""
import base64, hashlib, json, os, re

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

WRAP_DOMAIN = b"huginn-muninn wrap v2"
RING_DOMAIN = b"huginn-muninn ring v1"
AAD = b"huginn-muninn"
DAY = 86400
WRAP_USE_S = 7 * DAY        # a sender uses a day key at most this long after it was made
WRAP_KEEP_S = 40 * DAY      # the recipient keeps its private half this long (7 days of use, 31 on a relay, a margin)
WRAP_NEW_S = DAY            # a new day key once the newest is this old
WRAP_AHEAD_S = DAY          # a day key's time may be at most this far ahead of the receiver's clock
RING_OLD_MS = 60000         # as an offer (5.2): no older than this
RING_AHEAD_MS = 120000      # and no further ahead than this
P256_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")


def b64(b): return base64.b64encode(b).decode()


def key_pair(scalar):
    priv = ec.derive_private_key(scalar, ec.SECP256R1())
    pub = priv.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    der = priv.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return priv, der, pub


def shared_key(priv, my_pub, their_pub):
    """ChatCrypto.sharedKey: ECDH, HKDF-SHA256 with salt = SHA-256 of both public keys sorted as unsigned bytes."""
    secret = priv.exchange(ec.ECDH(), serialization.load_der_public_key(their_pub))
    a, b = sorted([my_pub, their_pub])
    return HKDF(hashes.SHA256(), 32, hashlib.sha256(a + b).digest(), b"huginn-muninn chat v1").derive(secret)


def key_ok(text):
    """A P-256 public key as standard base64 of its 91-byte SubjectPublicKeyInfo (uncompressed point)."""
    if not isinstance(text, str):
        return False
    try:
        der = base64.b64decode(text, validate=True)
    except Exception:
        return False
    if len(der) != 91 or not der.startswith(P256_PREFIX) or der[26] != 4:
        return False
    try:
        k = serialization.load_der_public_key(der)
    except Exception:
        return False
    return isinstance(k, ec.EllipticCurvePublicKey) and isinstance(k.curve, ec.SECP256R1)


def wrap_id(pub_der):
    """A day key's id on the wire: the first 16 hex characters of SHA-256 of its SubjectPublicKeyInfo."""
    return hashlib.sha256(pub_der).hexdigest()[:16]


# ------------------------------------------------------------------ e1: the wrapping

def wrap_key(k1, k2):
    return hashlib.sha256(WRAP_DOMAIN + k1 + k2).digest()


def seal_wrap(eph, eph_pub, static_pub, day_pub, body, nonce):
    k1 = shared_key(eph, eph_pub, static_pub)
    k2 = shared_key(eph, eph_pub, day_pub)
    key = wrap_key(k1, k2)
    data = AESGCM(key).encrypt(nonce, body, AAD)
    return k1, k2, key, {"e": b64(eph_pub), "w": wrap_id(day_pub), "n": b64(nonce), "d": b64(data)}


def open_wrap(o, static_priv, static_pub, days):
    """days: {id: (priv, pub)} still kept. None when it doesn't open."""
    if "w" not in o:
        return None
    day = days.get(o["w"])
    if day is None:
        return None
    eph_pub = base64.b64decode(o["e"])
    k1 = shared_key(static_priv, static_pub, eph_pub)
    k2 = shared_key(day[0], day[1], eph_pub)
    try:
        return AESGCM(wrap_key(k1, k2)).decrypt(base64.b64decode(o["n"]), base64.b64decode(o["d"]), AAD)
    except Exception:
        return None


def may_use(wt, now):
    return now - wt <= WRAP_USE_S and wt - now <= WRAP_AHEAD_S


def keep(t, now):
    # A key stamped more than a day ahead (made under a clock that was later set back) is dropped.
    return now - t < WRAP_KEEP_S and t - now <= WRAP_AHEAD_S


def rotate_due(newest, now):
    return newest is None or now - newest >= WRAP_NEW_S or newest - now > WRAP_AHEAD_S


# ------------------------------------------------------------------ b1: the ring envelope

def ring_key(k):
    return hashlib.sha256(RING_DOMAIN + k).digest()


def seal_ring(eph, eph_pub, ring_pub, plain, nonce):
    k = shared_key(eph, eph_pub, ring_pub)
    data = AESGCM(ring_key(k)).encrypt(nonce, plain, AAD)
    return k, {"e": b64(eph_pub), "n": b64(nonce), "d": b64(data)}


def open_ring(o, ring_priv, ring_pub):
    try:
        k = shared_key(ring_priv, ring_pub, base64.b64decode(o["e"]))
        return AESGCM(ring_key(k)).decrypt(base64.b64decode(o["n"]), base64.b64decode(o["d"]), AAD)
    except Exception:
        return None


CALL_ID = re.compile(r"^[A-Za-z0-9_-]{22}$")


def ring_ok(p):
    """{"t":"ring","call":<call id, 22 base64url characters>,"ts":<ms, integer>}; other fields are ignored."""
    if not isinstance(p, dict) or p.get("t") != "ring":
        return False
    c = p.get("call")
    ts = p.get("ts")
    return isinstance(c, str) and CALL_ID.match(c) is not None and isinstance(ts, int) and not isinstance(ts, bool) \
        and 0 <= ts < 2 ** 53


def ring_fresh(ts, now):
    return now - RING_OLD_MS <= ts <= now + RING_AHEAD_MS


# ------------------------------------------------------------------ both: the fields a receipt carries

MAILBOX = re.compile(r"^[0-9a-f]{64}$")


def announce(r, now):
    """What a receiver takes from a receipt's fields: (ring, wrap), each (key, mailbox or time) or None."""
    ring = wrap = None
    if key_ok(r.get("rk")) and isinstance(r.get("rp"), str) and MAILBOX.match(r["rp"]):
        ring = (r["rk"], r["rp"])
    wt = r.get("wt")
    if key_ok(r.get("wk")) and isinstance(wt, int) and not isinstance(wt, bool) and 0 <= wt < 2 ** 53 \
            and now - wt < WRAP_KEEP_S and wt - now <= WRAP_AHEAD_S:
        wrap = (r["wk"], wt)
    return ring, wrap


# ------------------------------------------------------------------ the vectors

def build():
    chat = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "vectors", "chat-vectors.json")))
    b_priv = serialization.load_der_private_key(base64.b64decode(chat["privB"]), None)
    b_pub = base64.b64decode(chat["pubB"])
    eph, eph_der, eph_pub = key_pair(0x5001)
    day, day_der, day_pub = key_pair(0x5002)
    old, old_der, old_pub = key_pair(0x5003)
    body = json.dumps({"room": "private", "fromKey": chat["pubA"], "fromName": "Huginn", "id": "e1-test", "cap": "r1",
                       "nonce": "AAECAwQFBgcICQoL", "data": "c2VhbGVk"}, separators=(",", ":")).encode()
    nonce = bytes(range(12))
    k1, k2, key, outer = seal_wrap(eph, eph_pub, b_pub, day_pub, body, nonce)
    wrap = {"recipientPriv": chat["privB"], "recipientPub": chat["pubB"],
            "ephPriv": b64(eph_der), "ephPub": b64(eph_pub), "dayPriv": b64(day_der), "dayPub": b64(day_pub),
            "dayId": wrap_id(day_pub), "otherDayPriv": b64(old_der), "otherDayPub": b64(old_pub), "otherDayId": wrap_id(old_pub),
            "k1": k1.hex(), "k2": k2.hex(), "key": key.hex(), "nonce": b64(nonce), "body": body.decode(), "outer": outer,
            "changed": dict(outer, d=outer["d"][:-4] + ("AAAA" if not outer["d"].endswith("AAAA") else "BBBB")),
            "otherId": dict(outer, w=wrap_id(old_pub))}

    ring, ring_der, ring_pub = key_pair(0x6001)
    reph, reph_der, reph_pub = key_pair(0x6002)
    plain = json.dumps({"t": "ring", "call": "AAECAwQFBgcICQoLDA0ODw", "ts": 1761500130000}, separators=(",", ":")).encode()
    rnonce = bytes(range(20, 32))
    rk, router = seal_ring(reph, reph_pub, ring_pub, plain, rnonce)
    ringv = {"ringPriv": b64(ring_der), "ringPub": b64(ring_pub), "ephPriv": b64(reph_der), "ephPub": b64(reph_pub),
             "shared": rk.hex(), "key": ring_key(rk).hex(), "nonce": b64(rnonce), "plain": plain.decode(), "outer": router,
             "wrongKeyPriv": chat["privB"], "wrongKeyPub": chat["pubB"]}

    good = [{"t": "ring", "call": "AAECAwQFBgcICQoLDA0ODw", "ts": 1761500130000},
            {"t": "ring", "call": "abcdefghijklmnopqrstu-", "ts": 0, "x": "ignored"}]
    refuse = [{"t": "offer", "call": "AAECAwQFBgcICQoLDA0ODw", "ts": 1},
              {"t": "ring", "call": "AAECAwQFBgcICQoLDA0OD", "ts": 1},
              {"t": "ring", "call": "AAECAwQFBgcICQoLDA0ODw=", "ts": 1},
              {"t": "ring", "call": "AAECAwQFBgcICQoLDA0O+w", "ts": 1},
              {"t": "ring", "call": "AAECAwQFBgcICQoLDA0ODw", "ts": "1"},
              {"t": "ring", "call": "AAECAwQFBgcICQoLDA0ODw", "ts": -1},
              {"t": "ring", "call": "AAECAwQFBgcICQoLDA0ODw", "ts": 1.5},
              {"t": "ring", "call": "AAECAwQFBgcICQoLDA0ODw"},
              {"call": "AAECAwQFBgcICQoLDA0ODw", "ts": 1}]
    now_ms = 1761500130000
    fresh = [{"ts": now_ms + d, "now": now_ms, "ring": ring_fresh(now_ms + d, now_ms)}
             for d in (0, -60000, -60001, 120000, 120001, -5000, 90000)]

    now = 1761500000
    p384 = ec.derive_private_key(7, ec.SECP384R1()).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    box = hashlib.sha256(b"ring mailbox").hexdigest()
    rpub = b64(ring_pub)
    dpub = b64(day_pub)
    cases = [
        ("both", {"rk": rpub, "rp": box, "wk": dpub, "wt": now - 100}),
        ("ring only", {"rk": rpub, "rp": box}),
        ("ring without its mailbox", {"rk": rpub}),
        ("mailbox in capitals", {"rk": rpub, "rp": box.upper()}),
        ("mailbox too short", {"rk": rpub, "rp": box[:62]}),
        ("a P-384 key", {"rk": b64(p384), "rp": box, "wk": b64(p384), "wt": now}),
        ("not base64", {"rk": "not a key", "rp": box, "wk": "%%%", "wt": now}),
        ("compressed point", {"rk": b64(bytes.fromhex("3039301306072a8648ce3d020106082a8648ce3d030107032200") +
                                         ec.derive_private_key(9, ec.SECP256R1()).public_key().public_bytes(
                                             serialization.Encoding.X962, serialization.PublicFormat.CompressedPoint)), "rp": box}),
        ("day key without time", {"wk": dpub}),
        ("day key time as text", {"wk": dpub, "wt": str(now)}),
        ("day key 39 days old", {"wk": dpub, "wt": now - 39 * DAY}),
        ("day key 40 days old", {"wk": dpub, "wt": now - 40 * DAY}),
        ("day key a day ahead", {"wk": dpub, "wt": now + DAY}),
        ("day key more than a day ahead", {"wk": dpub, "wt": now + DAY + 1}),
    ]
    ann = []
    for name, r in cases:
        ring_t, wrap_t = announce(r, now)
        ann.append({"name": name, "fields": r, "now": now, "ring": ring_t is not None, "wrap": wrap_t is not None})

    use = [{"wt": now - d, "now": now, "use": may_use(now - d, now)} for d in (0, DAY, 7 * DAY, 7 * DAY + 1, -DAY, -DAY - 1)]
    kept = [{"t": now - d, "now": now, "keep": keep(now - d, now)} for d in (0, 39 * DAY, 40 * DAY - 1, 40 * DAY)]
    rot = [{"newest": None if d is None else now - d, "now": now, "new": rotate_due(None if d is None else now - d, now)}
           for d in (None, 0, DAY - 1, DAY, -DAY - 1)]

    return {"note": "caps b1 and e1, shared/docs/protocol-0.6.md section 23; written by shared/tools/e1b1_ref.py",
            "constants": {"wrapUseS": WRAP_USE_S, "wrapKeepS": WRAP_KEEP_S, "wrapNewS": WRAP_NEW_S, "wrapAheadS": WRAP_AHEAD_S,
                          "ringOldMs": RING_OLD_MS, "ringAheadMs": RING_AHEAD_MS},
            "wrap": wrap, "ring": ringv, "ringPlain": {"good": good, "refuse": refuse}, "ringFresh": fresh,
            "announce": ann, "useWrap": use, "keepWrap": kept, "rotate": rot}


def check(v):
    w = v["wrap"]
    b_priv = serialization.load_der_private_key(base64.b64decode(w["recipientPriv"]), None)
    b_pub = base64.b64decode(w["recipientPub"])
    eph = serialization.load_der_private_key(base64.b64decode(w["ephPriv"]), None)
    eph_pub = base64.b64decode(w["ephPub"])
    day = serialization.load_der_private_key(base64.b64decode(w["dayPriv"]), None)
    day_pub = base64.b64decode(w["dayPub"])
    other = serialization.load_der_private_key(base64.b64decode(w["otherDayPriv"]), None)
    other_pub = base64.b64decode(w["otherDayPub"])
    k1, k2, key, outer = seal_wrap(eph, eph_pub, b_pub, day_pub, w["body"].encode(), base64.b64decode(w["nonce"]))
    assert (k1.hex(), k2.hex(), key.hex(), outer) == (w["k1"], w["k2"], w["key"], w["outer"]), "wrap"
    assert wrap_id(day_pub) == w["dayId"] and wrap_id(other_pub) == w["otherDayId"]
    days = {w["dayId"]: (day, day_pub), w["otherDayId"]: (other, other_pub)}
    assert open_wrap(w["outer"], b_priv, b_pub, days).decode() == w["body"]
    assert open_wrap(w["outer"], b_priv, b_pub, {w["otherDayId"]: (other, other_pub)}) is None, "a day key that's gone"
    assert open_wrap(w["changed"], b_priv, b_pub, days) is None
    assert open_wrap(w["otherId"], b_priv, b_pub, days) is None
    r = v["ring"]
    rp = serialization.load_der_private_key(base64.b64decode(r["ringPriv"]), None)
    rpub = base64.b64decode(r["ringPub"])
    reph = serialization.load_der_private_key(base64.b64decode(r["ephPriv"]), None)
    k, router = seal_ring(reph, base64.b64decode(r["ephPub"]), rpub, r["plain"].encode(), base64.b64decode(r["nonce"]))
    assert (k.hex(), ring_key(k).hex(), router) == (r["shared"], r["key"], r["outer"]), "ring"
    assert open_ring(r["outer"], rp, rpub).decode() == r["plain"]
    wrong = serialization.load_der_private_key(base64.b64decode(r["wrongKeyPriv"]), None)
    assert open_ring(r["outer"], wrong, base64.b64decode(r["wrongKeyPub"])) is None
    assert ring_ok(json.loads(r["plain"]))
    for p in v["ringPlain"]["good"]:
        assert ring_ok(p), p
    for p in v["ringPlain"]["refuse"]:
        assert not ring_ok(p), p
    for c in v["ringFresh"]:
        assert ring_fresh(c["ts"], c["now"]) == c["ring"], c
    for c in v["announce"]:
        ring_t, wrap_t = announce(c["fields"], c["now"])
        assert (ring_t is not None, wrap_t is not None) == (c["ring"], c["wrap"]), c["name"]
    for c in v["useWrap"]:
        assert may_use(c["wt"], c["now"]) == c["use"], c
    for c in v["keepWrap"]:
        assert keep(c["t"], c["now"]) == c["keep"], c
    for c in v["rotate"]:
        assert rotate_due(c["newest"], c["now"]) == c["new"], c
    # The cases that matter are really there.
    assert any(c["ring"] and c["wrap"] for c in v["announce"]) and any(not c["ring"] and not c["wrap"] for c in v["announce"])


def main():
    v = build()
    check(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "e1b1-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "e1b1-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            check(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

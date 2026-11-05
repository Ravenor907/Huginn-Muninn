#!/usr/bin/env python3
"""Reference for cap s1 (code/shared/docs/protocol.md, section 21): proven routes and sealed clear fields.

  - Route proofs: a device signs, with its chat key, the TLS certificate it answers Wi-Fi requests with (Wi-Fi), or a
    one-off number the other side picked (Bluetooth LE and Nearby). A route is only taken as a contact's when its proof
    checks out with that contact's key.
  - Hidden hint: hidden from people nearby, a device sends a hint that changes every day instead of its chat key and
    mailbox. Only someone who already has the key can match it.
  - Bound envelopes: room, id, fromKey, toKey, mailbox, caps, days and invite go into the AES-GCM associated data
    (v1 and the ratchet), so none of them can be changed or the envelope sent to someone else.
  - Group states: a new state may be at most 1000 versions ahead; at the same version a state signed by its owner wins.

Writes code/shared/vectors/s1-vectors.json and the Android test copy; every case is checked twice (building, then from the file).
"""
import base64, hashlib, hmac, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402
from cryptography.exceptions import InvalidTag  # noqa: E402
from ratchet_ref import b64, pub_der, priv_from_int, shared_key, Session, hkdf  # noqa: E402
import ratchet_ref  # noqa: E402
from proto06_ref import D_GROUP, accept_state, canon, sign, verify, signed_bytes, b64url, winner  # noqa: E402

CAP = "s1"
D_ROUTE = "huginn-muninn route v1"     # Wi-Fi: the chat key vouches for a TLS certificate
D_CLOSE = "huginn-muninn close v1"     # Bluetooth LE and Nearby: the chat key answers the other side's number
HINT = "huginn-muninn hint v1|"
BOUND = "huginn-muninn s1\n"
DAY = 86_400_000
BOUND_TEXT = ("room", "id", "fromKey", "mailbox", "caps", "invite")


def is_nonce(n):
    """A one-off number: 16 random bytes as 32 lowercase hex characters, nothing else is ever signed."""
    return isinstance(n, str) and len(n) == 32 and all(c in "0123456789abcdef" for c in n)


def is_fingerprint(fp):
    """A certificate fingerprint: SHA-256 as 64 uppercase hex characters (both apps spell it this way)."""
    return isinstance(fp, str) and len(fp) == 64 and all(c in "0123456789ABCDEF" for c in fp)


def route_proof(priv, key, fp):
    assert is_fingerprint(fp)
    return sign(priv, D_ROUTE, {"fp": fp, "k": key})["sig"]


def route_ok(key, fp, sig):
    return isinstance(sig, str) and is_fingerprint(fp.upper() if isinstance(fp, str) else fp) and \
        verify(key, D_ROUTE, {"fp": fp.upper(), "k": key, "sig": sig})


def close_proof(priv, key, n):
    assert is_nonce(n)
    return sign(priv, D_CLOSE, {"k": key, "n": n})["sig"]


def close_ok(key, n, sig):
    return isinstance(sig, str) and is_nonce(n) and verify(key, D_CLOSE, {"k": key, "n": n, "sig": sig})


def hint(key, day):
    """What a hidden device sends instead of its chat key on [day] (days since 1970, UTC)."""
    return hmac.new(key.encode(), (HINT + str(day)).encode(), hashlib.sha256).hexdigest()[:32]


def hint_matches(key, h, now_ms):
    """Yesterday, today and tomorrow all match, so clocks a little apart still find each other."""
    d = now_ms // DAY
    return any(hmac.compare_digest(hint(key, x), h) for x in (d - 1, d, d + 1))


def bound(o, to_key):
    """The clear fields an s1 envelope's seal covers, as canonical JSON; None when one has the wrong type."""
    b = {"toKey": to_key}
    for k in BOUND_TEXT:
        if k in o:
            if not isinstance(o[k], str): return None
            b[k] = o[k]
    if "days" in o:
        d = o["days"]
        if not isinstance(d, int) or isinstance(d, bool) or d < 0 or d >= 2 ** 53: return None
        b["days"] = d
    try: return canon(b)
    except ValueError: return None


def v1_aad(text): return (BOUND + text).encode()
def ratchet_aad(h, text): return ratchet_ref.aad(h) + b"\n" + v1_aad(text)


def seal_v1(sk, o, to_key, plain, nonce):
    text = bound(o, to_key)
    out = dict(o, aad=CAP, nonce=b64(nonce))
    out["data"] = b64(AESGCM(sk).encrypt(nonce, plain, v1_aad(text)))
    return out


def open_v1(sk, o, my_key):
    """Plaintext, or None. Marked "aad":"s1": the bound fields; otherwise the plain v1 constant (the caller decides
    whether an unbound envelope is still allowed from this sender)."""
    if o.get("aad", "") not in ("", CAP): return None
    if o.get("aad") == CAP:
        text = bound(o, my_key)
        if text is None: return None
        ad = v1_aad(text)
    else:
        ad = b"huginn-muninn"
    try: return AESGCM(sk).decrypt(base64.b64decode(o["nonce"]), base64.b64decode(o["data"]), ad)
    except (InvalidTag, KeyError, ValueError): return None


def ratchet_seal(mk, h, plain, text):
    k = hkdf(b"\x00" * 32, mk, "huginn-muninn message", 44)
    return AESGCM(k[:32]).encrypt(k[32:], plain, ratchet_aad(h, text))


def ratchet_open(mk, h, c, text):
    k = hkdf(b"\x00" * 32, mk, "huginn-muninn message", 44)
    try: return AESGCM(k[:32]).decrypt(k[32:], c, ratchet_aad(h, text))
    except InvalidTag: return None


def first_message_key(sk, their_pub, eph):
    """Alice's first message key on a new session (ratchet_ref.Session.alice, then one chain step)."""
    rk, cks = ratchet_ref.kdf_rk(sk, ratchet_ref.dh(eph, their_pub))
    mk, _ = ratchet_ref.kdf_ck(cks)
    return mk


def accept_s1(cur, new):
    """Group states: proto06_ref.accept_state, which has s1's two rules (at most 1000 ahead, the owner wins a tie)."""
    return accept_state(cur, new)


# ------------------------------------------------------------------ vectors

def build():
    here = os.path.dirname(os.path.abspath(__file__))
    v = json.load(open(os.path.join(here, "..", "vectors", "chat-vectors.json")))
    A = serialization.load_der_private_key(base64.b64decode(v["privA"]), None)
    B = serialization.load_der_private_key(base64.b64decode(v["privB"]), None)
    kA, kB = v["pubA"], v["pubB"]
    C = priv_from_int(int.from_bytes(b"\x33" * 32, "big"))
    kC = b64(pub_der(C))
    ok = 0

    def expect(cond, what):
        nonlocal ok
        assert cond, what
        ok += 1

    # ---- route proofs
    fpA = hashlib.sha256(b"certificate of A").hexdigest().upper()
    fpX = hashlib.sha256(b"someone else's certificate").hexdigest().upper()
    nA = hashlib.sha256(b"a one-off number").hexdigest()[:32]
    lan = route_proof(A, kA, fpA)
    near = close_proof(A, kA, nA)
    routes = [
        {"what": "wifi: A vouches for its certificate", "kind": "wifi", "key": kA, "n": fpA, "sig": lan, "ok": True},
        {"what": "wifi: lowercase fingerprint is the same certificate", "kind": "wifi", "key": kA, "n": fpA.lower(), "sig": lan, "ok": True},
        {"what": "wifi: another certificate", "kind": "wifi", "key": kA, "n": fpX, "sig": lan, "ok": False},
        {"what": "wifi: claimed by C", "kind": "wifi", "key": kC, "n": fpA, "sig": lan, "ok": False},
        {"what": "wifi: a close-by proof isn't a Wi-Fi one", "kind": "wifi", "key": kA, "n": nA, "sig": near, "ok": False},
        {"what": "close: A answers the number", "kind": "close", "key": kA, "n": nA, "sig": near, "ok": True},
        {"what": "close: another number", "kind": "close", "key": kA, "n": nA[:-1] + "0", "sig": near, "ok": False},
        {"what": "close: claimed by B", "kind": "close", "key": kB, "n": nA, "sig": near, "ok": False},
        {"what": "close: a Wi-Fi proof isn't a close-by one", "kind": "close", "key": kA, "n": fpA, "sig": lan, "ok": False},
        {"what": "close: uppercase number is never signed", "kind": "close", "key": kA, "n": nA.upper(), "sig": near, "ok": False},
        {"what": "close: empty signature", "kind": "close", "key": kA, "n": nA, "sig": "", "ok": False},
    ]
    for r in routes:
        got = route_ok(r["key"], r["n"], r["sig"]) if r["kind"] == "wifi" else close_ok(r["key"], r["n"], r["sig"])
        expect(got == r["ok"], r["what"])
    signed = {"wifi": signed_bytes(D_ROUTE, {"fp": fpA, "k": kA}).decode(), "close": signed_bytes(D_CLOSE, {"k": kA, "n": nA}).decode()}

    # ---- hidden hints
    now = 1762000000000
    day = now // DAY
    hints = [{"key": kA, "day": d, "hint": hint(kA, d)} for d in (day - 1, day, day + 1)]
    expect(hints[1]["hint"] != hints[0]["hint"], "a new hint every day")
    match = [{"key": k, "hint": h, "now": now, "ok": hint_matches(k, h, now)} for k, h in [
        (kA, hint(kA, day)), (kA, hint(kA, day - 1)), (kA, hint(kA, day + 1)), (kA, hint(kA, day - 2)), (kA, hint(kA, day + 2)),
        (kB, hint(kA, day)), (kA, "")]]
    expect([m["ok"] for m in match] == [True, True, True, False, False, False, False], "hint window")

    # ---- bound envelopes, v1
    sk = shared_key(A, base64.b64decode(kA), kB)
    expect(sk.hex() == v["key"], "pair key")
    env = {"room": "private", "fromKey": kA, "fromName": "Raven", "fromId": "x", "mailbox": "a1" * 32,
           "id": "4f1c2a7e-0d5b-4c8e-9a61-3b2f7d9e5c10", "cap": "r1", "caps": "r1 g1 m1 d1 s1", "days": 30}
    nonce = bytes(range(12))
    sealed = seal_v1(sk, env, kB, "hello".encode(), nonce)
    text = bound(sealed, kB)
    expect(open_v1(sk, sealed, kB) == b"hello", "v1 opens")
    tamper = [
        ("days rewritten", dict(sealed, days=7)),
        ("room rewritten", dict(sealed, room="live")),
        ("fresh id", dict(sealed, id="replayed")),
        ("mailbox rewritten", dict(sealed, mailbox="c3" * 32)),
        ("caps rewritten", dict(sealed, caps="r1")),
        ("invite added", dict(sealed, invite="tok")),
        ("marker dropped", {k: x for k, x in sealed.items() if k != "aad"}),
        ("days as text", dict(sealed, days="30")),
        ("unknown marker", dict(sealed, aad="s9")),
    ]
    v1_refuse = []
    for what, o in tamper:
        expect(open_v1(sk, o, kB) is None, what)
        v1_refuse.append({"what": what, "envelope": o})
    # Sent to B, opened by C as if for C (reflection to someone else): the toKey differs.
    expect(bound(sealed, kC) != text, "toKey is bound")
    # fromName and fromId are not bound (display only; the key behind fromKey is what counts).
    expect(open_v1(sk, dict(sealed, fromName="Mom"), kB) == b"hello", "name not bound")
    bound_cases = [{"envelope": o, "toKey": t, "bound": bound(o, t)} for o, t in [
        (env, kB), ({"room": "receipt", "id": "r", "fromKey": kA, "mailbox": "", "caps": ""}, kB),
        ({"room": "private", "id": "x", "fromKey": kA, "days": 7, "invite": "abc"}, kC),
        ({"room": "private", "id": 5, "fromKey": kA}, kB), ({"room": "private", "id": "x", "fromKey": kA, "days": True}, kB),
        ({"room": "private", "id": "x", "fromKey": kA, "days": 1.5}, kB), ({"room": "line\nbreak", "id": "x", "fromKey": kA}, kB)]]
    expect([c["bound"] is None for c in bound_cases] == [False, False, False, True, True, True, True], "bound shapes")

    # ---- bound envelopes, ratchet (A's first message on a new session, ratchet key 0x1001)
    eph = priv_from_int(0x1001)
    mk = first_message_key(sk, kB, eph)
    h = {"b": b64(pub_der(eph)), "d": b64(pub_der(eph)), "pn": 0, "n": 0}
    renv = dict(env, rh=h, aad=CAP)
    rtext = bound(renv, kB)
    c = ratchet_seal(mk, h, b"ratchet hello", rtext)
    renv["data"] = b64(c)
    # Bob's side, the real way: a new session from his static key.
    sk_b = shared_key(B, base64.b64decode(kB), kA)
    bob = Session.bob(sk_b, B, h["b"])
    saved = ratchet_ref.aad
    try:
        ratchet_ref.aad = lambda hh: saved(hh) + b"\n" + v1_aad(rtext)
        expect(bob.decrypt(h, c, lambda: priv_from_int(0x2001)) == b"ratchet hello", "ratchet opens with the bound fields")
    finally:
        ratchet_ref.aad = saved
    expect(ratchet_open(mk, h, c, bound(dict(renv, days=7), kB)) is None, "ratchet: days rewritten")
    expect(ratchet_open(mk, h, c, bound(renv, kC)) is None, "ratchet: other recipient")
    try:
        AESGCM(hkdf(b"\x00" * 32, mk, "huginn-muninn message", 44)[:32]).decrypt(
            hkdf(b"\x00" * 32, mk, "huginn-muninn message", 44)[32:], c, ratchet_ref.aad(h))
        expect(False, "ratchet: the plain header alone must not open it")
    except InvalidTag:
        ok += 1

    # ---- group states: at most 1000 ahead, the owner wins a tie
    g = b64url(bytes(range(16)))
    members = sorted([{"k": kA, "n": "Raven", "p": "a1" * 32}, {"k": kB, "n": "Moose", "p": "b2" * 32}], key=lambda m: m["k"])
    base = {"g": g, "owner": kA, "by": kA, "name": "Family", "admins": sorted([kA, kB]), "members": members,
            "channels": [{"id": "main", "n": "general", "t": "text"}], "ts": 1761500000000}
    states = {
        "v5": sign(A, D_GROUP, dict(base, v=5)),
        "v1005": sign(A, D_GROUP, dict(base, v=1005, ts=1761500060000)),
        "v1006": sign(A, D_GROUP, dict(base, v=1006, ts=1761500060000)),
        "max": sign(B, D_GROUP, dict(base, by=kB, v=2 ** 53 - 1, ts=1761500060000)),
        "v6admin": sign(B, D_GROUP, dict(base, by=kB, v=6, name="Admin's", ts=1761500120000)),
        "v6owner": sign(A, D_GROUP, dict(base, v=6, name="Owner's", ts=1761500120000)),
        "v6admin2": sign(B, D_GROUP, dict(base, by=kB, v=6, name="Admin's too", ts=1761500130000)),
    }
    group = []
    for cur, new in [("v5", "v1005"), ("v5", "v1006"), ("v5", "max"), ("v6admin", "v6owner"), ("v6owner", "v6admin"),
                     ("v6admin", "v6admin2"), ("v6admin2", "v6admin"), ("v5", "v6admin")]:
        a, why = accept_s1(states[cur], states[new])
        group.append({"current": cur, "new": new, "accept": a, "why": why})
    expect([x["accept"] for x in group][:5] == [True, False, False, True, False], "group rules")
    expect(sum(x["accept"] for x in group[5:7]) == 1 and group[7]["accept"], "admins still settle a tie by hash")
    wins = [{"a": a, "b": b, "wins": "a" if winner(states[a], states[b]) is states[a] else "b"}
            for a, b in [("v6admin", "v6owner"), ("v6owner", "v6admin"), ("v5", "v6admin")]]
    expect([w["wins"] for w in wins] == ["b", "a", "b"], "owner wins a tie")

    return {"note": "cap s1, code/shared/docs/protocol.md section 21; written by code/shared/tools/s1_ref.py",
            "keys": {"A": kA, "B": kB, "C": kC},
            "route": {"fingerprint": fpA, "nonce": nA, "signed": signed, "checks": routes},
            "hint": {"now": now, "days": hints, "match": match},
            "v1": {"pairKey": sk.hex(), "envelope": sealed, "toKey": kB, "bound": text, "aad": v1_aad(text).decode(),
                   "plain": "hello", "nonce": nonce.hex(), "refuse": v1_refuse},
            "bound": bound_cases,
            "ratchet": {"envelope": renv, "toKey": kB, "bound": rtext, "aad": ratchet_aad(h, rtext).decode(), "plain": "ratchet hello",
                        "bobNext": 0x2001},
            "group": {"states": states, "accept": group, "winner": wins}}, ok


def check(v):
    n = 0
    for r in v["route"]["checks"]:
        got = route_ok(r["key"], r["n"], r["sig"]) if r["kind"] == "wifi" else close_ok(r["key"], r["n"], r["sig"])
        assert got == r["ok"], r["what"]; n += 1
    for h in v["hint"]["days"]:
        assert hint(h["key"], h["day"]) == h["hint"]; n += 1
    for m in v["hint"]["match"]:
        assert hint_matches(m["key"], m["hint"], m["now"]) == m["ok"]; n += 1
    x = v["v1"]
    sk = bytes.fromhex(x["pairKey"])
    assert bound(x["envelope"], x["toKey"]) == x["bound"] and v1_aad(x["bound"]).decode() == x["aad"]; n += 1
    assert open_v1(sk, x["envelope"], x["toKey"]).decode() == x["plain"]; n += 1
    for r in x["refuse"]:
        assert open_v1(sk, r["envelope"], x["toKey"]) is None, r["what"]; n += 1
    for c in v["bound"]:
        assert bound(c["envelope"], c["toKey"]) == c["bound"]; n += 1
    r = v["ratchet"]
    assert bound(r["envelope"], r["toKey"]) == r["bound"] and ratchet_aad(r["envelope"]["rh"], r["bound"]).decode() == r["aad"]; n += 1
    st = v["group"]["states"]
    for a in v["group"]["accept"]:
        assert accept_s1(st[a["current"]], st[a["new"]])[0] == a["accept"], a; n += 1
    for w in v["group"]["winner"]:
        assert (winner(st[w["a"]], st[w["b"]]) is st[w["a"]]) == (w["wins"] == "a"); n += 1
    return n


def main():
    v, ok = build()
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "s1-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "s1-vectors.json")):
        with open(path, "w") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        print("wrote", os.path.normpath(path))
    print("all %d checks passed" % ok)
    n = check(json.load(open(os.path.join(here, "..", "vectors", "s1-vectors.json"))))
    print("re-read s1-vectors.json: %d checks passed" % n)


if __name__ == "__main__":
    main()

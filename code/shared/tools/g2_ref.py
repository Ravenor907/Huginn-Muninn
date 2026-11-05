#!/usr/bin/env python3
"""Reference for cap g2 (code/shared/docs/protocol.md, section 26): group timeouts, bans and removed messages, signed by
an admin, and the per-person volume both apps apply on their own device. Also cap g3 (section 28): the group's about
text and a channel's "only admins post" in its signed state (vectors "g3"). Writes code/shared/vectors/g2-vectors.json, which both apps
check (Huginn in --chat-selftest and --call-selftest, Muninn in ModTest); every case is checked twice (building, then from
the file).

A record is a signed object like a group state (section 2.2), with its own domain:

    {"a": "ban" | "unban" | "timeout" | "untimeout" | "remove", "g": <group id>, "k": <who>, "by": <admin>, "ts": ms,
     ["until": ms, only for "timeout"], ["m": <message id>, only for "remove"], "sig": <base64 DER>}
"""
import base64, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cryptography.hazmat.primitives import serialization  # noqa: E402
from proto06_ref import D_GROUP, b64url, canon, check_state, is_id, sign, signed_bytes, unsigned, verify  # noqa: E402

D_MOD = "huginn-muninn gmod v1"
ACTIONS = ("ban", "unban", "timeout", "untimeout", "remove")
MINUTE = 60_000
DAY = 86_400_000
TIMEOUTS = (10 * MINUTE, 60 * MINUTE, DAY, 7 * DAY)   # what the menus offer
LONGEST = 7 * DAY + MINUTE                              # the longest "until - ts" taken (a week, and a minute of slack)
MAX_LIST = 50                                           # records in one "mod" control message
VOLUME_MIN, VOLUME_MAX, VOLUME_DEFAULT = 0, 200, 100
ABOUT_MAX = 500                                         # cap g3: characters (code points) in a group's "about" (proto06_ref.MAX_ABOUT)


def is_int(x): return isinstance(x, int) and not isinstance(x, bool)


def is_logical_id(x): return isinstance(x, str) and len(x) == 32 and all(c in "0123456789abcdef" for c in x)


def check_mod(r):
    """Shape of a record; raises ValueError. Does not check the signature."""
    if not isinstance(r, dict): raise ValueError("not an object")
    need = {"a", "g", "k", "by", "ts", "sig"}
    want = need | {"until"} if r.get("a") == "timeout" else need | {"m"} if r.get("a") == "remove" else need
    if set(r) != want: raise ValueError("fields")
    if r["a"] not in ACTIONS: raise ValueError("action")
    if not is_id(r["g"], 16): raise ValueError("group id")
    for f in ("k", "by"):
        if not isinstance(r[f], str) or not 1 <= len(r[f]) <= 200: raise ValueError(f)
    if r["k"] == r["by"]: raise ValueError("self")
    if not is_int(r["ts"]) or not 0 < r["ts"] < 2 ** 53: raise ValueError("ts")
    if r["a"] == "timeout":
        u = r["until"]
        if not is_int(u) or not r["ts"] < u <= r["ts"] + LONGEST: raise ValueError("until")
    if r["a"] == "remove" and not is_logical_id(r["m"]): raise ValueError("m")


def mod_accept(state, r):
    """"ok" when a record is taken against the receiver's current state of its group, otherwise why not."""
    try: check_mod(r)
    except ValueError as e: return "malformed: %s" % e
    if not verify(r["by"], D_MOD, r): return "bad signature"
    if state is None or state["g"] != r["g"]: return "unknown group"
    if r["by"] not in state["admins"]: return "signer is not an admin"
    if r["k"] == state["owner"]: return "the owner"
    if r["k"] in state["admins"] and r["by"] != state["owner"]: return "an admin, and the signer isn't the owner"
    return "ok"


def slot(r):
    """Each person's two slots; a "remove" isn't kept in one (it blanks the message it names, 26.2)."""
    return "ban" if r["a"] in ("ban", "unban") else "timeout"


def state_verdict(s):
    """"" when a group state's shape is taken (proto06_ref.check_state), otherwise why not."""
    try: check_state(s)
    except ValueError as e: return str(e)
    return ""


def mod_newer(cur, new):
    """True when [new] replaces [cur] in its slot (per group, person and slot): a higher ts. The same ts keeps cur."""
    return cur is None or new["ts"] > cur["ts"]


def banned(rec): return rec is not None and rec["a"] == "ban"


def timed_out(rec, now): return rec is not None and rec["a"] == "timeout" and now < rec["until"]


def clamp_volume(p):
    """A saved or asked-for volume in percent: whole numbers 0 to 200; anything else is 100."""
    if not is_int(p): return VOLUME_DEFAULT
    return max(VOLUME_MIN, min(VOLUME_MAX, p))


def gain(sample, pct):
    """One 16-bit sample at pct percent (clamped first), truncated toward zero and clipped to 16 bits."""
    pct = clamp_volume(pct)
    v = sample * pct
    v = -((-v) // 100) if v < 0 else v // 100
    return max(-32768, min(32767, v))


# ------------------------------------------------------------------ vectors

def build():
    here = os.path.dirname(os.path.abspath(__file__))
    v = json.load(open(os.path.join(here, "..", "vectors", "chat-vectors.json")))
    A = serialization.load_der_private_key(base64.b64decode(v["privA"]), None)
    B = serialization.load_der_private_key(base64.b64decode(v["privB"]), None)
    kA, kB = v["pubA"], v["pubB"]
    g = b64url(bytes(range(16)))
    members = sorted([{"k": kA, "n": "Raven", "p": "a1" * 32}, {"k": kB, "n": "Moose", "p": "b2" * 32}], key=lambda m: m["k"])
    state = sign(A, D_GROUP, {"g": g, "v": 1, "owner": kA, "by": kA, "name": "Folk", "admins": [kA], "members": members,
                              "channels": [{"id": "main", "n": "general", "t": "text"}], "ts": 1761500000000})
    # The same group with Moose an admin too: the owner can't be timed out or banned, even by an admin.
    admins = sign(A, D_GROUP, dict(unsigned(state), v=2, admins=sorted([kA, kB]), ts=1761500060000))
    t0 = 1762000000000
    ban = sign(A, D_MOD, {"a": "ban", "g": g, "k": kB, "by": kA, "ts": t0})
    unban = sign(A, D_MOD, {"a": "unban", "g": g, "k": kB, "by": kA, "ts": t0 + 1000})
    timeout = sign(A, D_MOD, {"a": "timeout", "g": g, "k": kB, "by": kA, "ts": t0, "until": t0 + 10 * MINUTE})
    week = sign(A, D_MOD, {"a": "timeout", "g": g, "k": kB, "by": kA, "ts": t0, "until": t0 + 7 * DAY})
    untimeout = sign(A, D_MOD, {"a": "untimeout", "g": g, "k": kB, "by": kA, "ts": t0 + 2000})
    msg = "0123456789abcdef" * 2                                  # the message removed for everyone
    remove = sign(A, D_MOD, {"a": "remove", "g": g, "k": kB, "by": kA, "ts": t0 + 3000, "m": msg})
    good = {"ban": ban, "unban": unban, "timeout": timeout, "week": week, "untimeout": untimeout, "remove": remove}
    tampered = dict(ban, ts=t0 + 1)                               # changed after signing
    refuse = {
        "forged": sign(B, D_MOD, {"a": "ban", "g": g, "k": kA, "by": kB, "ts": t0}),     # a member who isn't an admin
        "forgedAsAdmin": sign(B, D_MOD, {"a": "ban", "g": g, "k": kB, "by": kA, "ts": t0 + 5}),   # says by A, signed by B
        "tampered": tampered,
        "wrongDomain": sign(A, D_GROUP, {"a": "ban", "g": g, "k": kB, "by": kA, "ts": t0}),
        "owner": sign(B, D_MOD, {"a": "timeout", "g": g, "k": kA, "by": kB, "ts": t0, "until": t0 + MINUTE}),
        "tooLong": sign(A, D_MOD, {"a": "timeout", "g": g, "k": kB, "by": kA, "ts": t0, "until": t0 + LONGEST + 1}),
        "untilPast": sign(A, D_MOD, {"a": "timeout", "g": g, "k": kB, "by": kA, "ts": t0, "until": t0}),
        "untilOnBan": sign(A, D_MOD, {"a": "ban", "g": g, "k": kB, "by": kA, "ts": t0, "until": t0 + MINUTE}),
        "noUntil": sign(A, D_MOD, {"a": "timeout", "g": g, "k": kB, "by": kA, "ts": t0}),
        "kick": sign(A, D_MOD, {"a": "kick", "g": g, "k": kB, "by": kA, "ts": t0}),
        "self": sign(A, D_MOD, {"a": "ban", "g": g, "k": kA, "by": kA, "ts": t0}),
        "otherGroup": sign(A, D_MOD, {"a": "ban", "g": b64url(bytes(range(1, 17))), "k": kB, "by": kA, "ts": t0}),
        "extra": sign(A, D_MOD, {"a": "ban", "g": g, "k": kB, "by": kA, "ts": t0, "why": "spam"}),
        "tsText": sign(A, D_MOD, {"a": "ban", "g": g, "k": kB, "by": kA, "ts": "1762000000000"}),
        "removeNoMsg": sign(A, D_MOD, {"a": "remove", "g": g, "k": kB, "by": kA, "ts": t0}),
        "removeBadMsg": sign(A, D_MOD, {"a": "remove", "g": g, "k": kB, "by": kA, "ts": t0, "m": msg.upper()}),
        "msgOnBan": sign(A, D_MOD, {"a": "ban", "g": g, "k": kB, "by": kA, "ts": t0, "m": msg}),
        "removeByMember": sign(B, D_MOD, {"a": "remove", "g": g, "k": kA, "by": kB, "ts": t0, "m": msg}),
        "removeTampered": dict(remove, m="f" * 32),
    }
    by_admin = {"ownerByAdmin": sign(B, D_MOD, {"a": "ban", "g": g, "k": kA, "by": kB, "ts": t0}),
                "byAdmin": sign(B, D_MOD, {"a": "untimeout", "g": g, "k": kA, "by": kB, "ts": t0}),
                "removeOwnersByAdmin": sign(B, D_MOD, {"a": "remove", "g": g, "k": kA, "by": kB, "ts": t0, "m": msg})}
    records = {k: {"record": r, "signed": signed_bytes(D_MOD, r).decode(), "verdict": mod_accept(state, r)} for k, r in good.items()}
    refused = {k: {"record": r, "verdict": mod_accept(state, r)} for k, r in refuse.items()}
    order = [{"cur": c, "new": n, "newer": mod_newer(good[c] if c else None, good[n])}
             for c, n in [(None, "ban"), ("ban", "unban"), ("unban", "ban"), ("timeout", "untimeout"), ("untimeout", "week"),
                          ("timeout", "week")]]
    active = [{"record": n, "now": now, "banned": banned(good[n]), "timedOut": timed_out(good[n], now)}
              for n, now in [("ban", t0), ("unban", t0), ("timeout", t0), ("timeout", t0 + 10 * MINUTE - 1),
                             ("timeout", t0 + 10 * MINUTE), ("week", t0 + 7 * DAY - 1), ("week", t0 + 7 * DAY), ("untimeout", t0),
                             ("remove", t0)]]
    volume = [{"in": p, "out": clamp_volume(p)} for p in [-5, 0, 1, 100, 150, 200, 201, 1000, None, "150", True, 99.5]]
    gains = [{"sample": s, "pct": p, "out": gain(s, p)} for s, p in [
        (1000, 100), (1000, 0), (1000, 50), (-1001, 50), (1001, 50), (30000, 200), (-30000, 200), (32767, 150),
        (-32768, 200), (333, 33), (-333, 33), (12345, 250), (12345, -10)]]
    # Cap g3 (section 28): the group's about text, "about" in the signed state, and a text channel's "ao" (only admins
    # post). "" when the state is taken.
    base = unsigned(state)
    about = {}
    news = b64url(bytes(range(40, 48)))
    for name, ch in [("adminsPost", {"id": news, "n": "rules", "t": "text", "ao": True}),
                     ("adminsPostOff", {"id": news, "n": "rules", "t": "text", "ao": False}),
                     ("adminsPostVoice", {"id": news, "n": "Voice", "t": "voice", "ao": True})]:
        st = sign(A, D_GROUP, dict(base, v=3, channels=base["channels"] + [ch], ts=1761500120000))
        about[name] = {"state": st, "verdict": state_verdict(st)}
    st = sign(A, D_GROUP, dict(base, v=3, channels=[dict(base["channels"][0], ao=True)], ts=1761500120000))
    about["adminsPostMain"] = {"state": st, "verdict": state_verdict(st)}
    # Paragraphs (28.1) are joined by U+2029 PARAGRAPH SEPARATOR, which isn't a control character, so it can be signed.
    for name, text in [("short", "No spam. Be kind."), ("longest", "\U0001F600" * ABOUT_MAX), ("tooLong", "a" * (ABOUT_MAX + 1)),
                       ("newline", "No spam.\nBe kind."), ("empty", ""), ("blank", "   "), ("number", 5),
                       ("paragraphs", "1. No NSFW\u20292. No slurs\u20293. Files under 25 MB")]:
        st = dict(base, v=3, about=text, ts=1761500120000)
        # A control character can't be signed (canonical text has none): that one carries the base state's signature.
        st = dict(st, sig=state["sig"]) if isinstance(text, str) and "\n" in text else sign(A, D_GROUP, st)
        about[name] = {"state": st, "verdict": state_verdict(st)}
    return {"note": "caps g2 and g3, code/shared/docs/protocol.md sections 26 and 28; written by code/shared/tools/g2_ref.py",
            "domain": D_MOD, "limits": {"timeouts": list(TIMEOUTS), "longest": LONGEST, "maxList": MAX_LIST,
                                        "volume": [VOLUME_MIN, VOLUME_MAX, VOLUME_DEFAULT]},
            "keys": {"A": kA, "B": kB}, "state": state, "records": records, "refuse": refused, "order": order,
            "admins": {"state": admins, "refuse": {k: {"record": r, "verdict": mod_accept(admins, r)} for k, r in by_admin.items()}},
            "active": active, "volume": volume, "gain": gains, "g3": about}


def verify_vectors(v):
    n = 0
    st = v["state"]
    for k, x in v["records"].items():
        r = x["record"]
        assert signed_bytes(D_MOD, r).decode() == x["signed"], k
        assert mod_accept(st, r) == x["verdict"] == "ok", k
        n += 1
    for k, x in v["refuse"].items():
        assert mod_accept(st, x["record"]) == x["verdict"] != "ok", (k, x["verdict"])
        n += 1
    for k, x in v["admins"]["refuse"].items():
        assert mod_accept(v["admins"]["state"], x["record"]) == x["verdict"] != "ok", k
        n += 1
    recs = {k: x["record"] for k, x in v["records"].items()}
    for c in v["order"]:
        assert mod_newer(recs[c["cur"]] if c["cur"] else None, recs[c["new"]]) == c["newer"], c; n += 1
    for c in v["active"]:
        r = recs[c["record"]]
        assert banned(r) == c["banned"] and timed_out(r, c["now"]) == c["timedOut"], c; n += 1
    for c in v["volume"]:
        assert clamp_volume(c["in"]) == c["out"], c; n += 1
    for c in v["gain"]:
        assert gain(c["sample"], c["pct"]) == c["out"], c; n += 1
    for k, c in v["g3"].items():
        assert state_verdict(c["state"]) == c["verdict"], k; n += 1
    assert v["g3"]["short"]["verdict"] == v["g3"]["longest"]["verdict"] == v["g3"]["adminsPost"]["verdict"] == v["g3"]["paragraphs"]["verdict"] == ""
    return n


def main():
    v = build()
    verify_vectors(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "g2-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "g2-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            n = verify_vectors(json.load(f))
        print("wrote %s: %d checks passed" % (os.path.normpath(path), n))


if __name__ == "__main__":
    main()

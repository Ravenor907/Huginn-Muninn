#!/usr/bin/env python3
"""Reference for cap d1 (code/shared/docs/protocol.md, section 16): message limits. Messages older than a chat's limit are
deleted on every device; the limit is 7 days unless a Family or Work group, or two people who keep each other in their
Family or Work folder, choose longer (up to 30). Writes code/shared/vectors/d1-vectors.json, which both apps check; every case is
checked twice (building, then from the file).

Nothing here is new cryptography. A group's type and limit ride in its signed state (two optional fields, checked by
code/shared/tools/proto06_ref.py check_state); each person's 1:1 limit rides in the clear part of their envelopes ("days").
"""
import base64, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cryptography.hazmat.primitives import serialization  # noqa: E402
from proto06_ref import D_GROUP, accept_state, check_state, obj_hash, sign, signed_bytes, b64url, unsigned  # noqa: E402

DAY = 86_400_000
DEFAULT = 7          # every chat, unless chosen otherwise
LONGEST = 30         # Family and Work groups, and Family and Work contacts
KINDS = ("family", "work")


def group_days(s):
    """A group's limit: its "days", 7 when it has none (a Standard group never has any)."""
    return s.get("days", DEFAULT) if "kind" in s else DEFAULT


def envelope_days(o):
    """The 1:1 limit an envelope says its sender keeps, or None when it says nothing usable (then nothing changes)."""
    d = o.get("days")
    if isinstance(d, int) and not isinstance(d, bool) and DEFAULT <= d <= LONGEST:
        return d
    return None


def my_days(chosen, close):
    """What I keep a chat for: my choice only for someone in my Family or Work folder, else 7."""
    if not close or not isinstance(chosen, int) or isinstance(chosen, bool):
        return DEFAULT
    return min(max(chosen, DEFAULT), LONGEST)


def pair_days(mine, has_d1, theirs):
    """A 1:1 chat's limit: the shorter of the two. Their side counts only when their app has d1; one that never said
    keeps 7. An app without d1 keeps everything: this side still deletes after its own limit."""
    if not has_d1:
        return mine
    return min(mine, DEFAULT if theirs is None else theirs)


def kept(times, days, now):
    """Which messages stay: those at most [days] old (a message exactly at the limit stays)."""
    cutoff = now - days * DAY
    return [t >= cutoff for t in times]


# ------------------------------------------------------------------ vectors

def build():
    here = os.path.dirname(os.path.abspath(__file__))
    v = json.load(open(os.path.join(here, "..", "vectors", "chat-vectors.json")))
    A = serialization.load_der_private_key(base64.b64decode(v["privA"]), None)
    kA, kB = v["pubA"], v["pubB"]
    g = b64url(bytes(range(16)))
    members = sorted([{"k": kA, "n": "Raven", "p": "a1" * 32}, {"k": kB, "n": "Moose", "p": "b2" * 32}], key=lambda m: m["k"])
    base = {"g": g, "owner": kA, "by": kA, "name": "Family", "admins": [kA], "members": members,
            "channels": [{"id": "main", "n": "general", "t": "text"}], "ts": 1761500000000}
    s1 = sign(A, D_GROUP, dict(base, v=1))
    s2 = sign(A, D_GROUP, dict(base, v=2, kind="family", days=30, ts=1761500060000))
    s3 = sign(A, D_GROUP, dict(base, v=3, kind="work", days=14, ts=1761500120000))
    s4 = sign(A, D_GROUP, dict(base, v=4, kind="family", ts=1761500180000))
    s5 = sign(A, D_GROUP, dict(base, v=5, ts=1761500240000))
    good = {"standard": s1, "family30": s2, "work14": s3, "family7": s4, "standardAgain": s5}
    bad = {
        "school": dict(unsigned(s2), kind="school"),
        "daysAlone": dict(unsigned(s1), days=14),
        "days6": dict(unsigned(s2), days=6),
        "days31": dict(unsigned(s2), days=31),
        "daysText": dict(unsigned(s2), days="14"),
        "daysTrue": dict(unsigned(s2), days=True),
        "kindEmpty": dict(unsigned(s2), kind=""),
        "other": dict(unsigned(s2), keep=7),
    }
    bad = {k: sign(A, D_GROUP, s) for k, s in bad.items()}
    states = {k: {"state": s, "signed": signed_bytes(D_GROUP, s).decode(), "hash": obj_hash(s), "days": group_days(s)}
              for k, s in good.items()}
    accept = [{"current": c, "new": n, "accept": accept_state(good[c] if c else None, good[n])[0]}
              for c, n in [(None, "family30"), ("standard", "family30"), ("family30", "work14"), ("work14", "family7"),
                           ("family7", "standardAgain"), ("work14", "family30")]]
    envelope = [{"o": o, "days": envelope_days(o)} for o in [
        {"days": 7}, {"days": 30}, {"days": 14}, {}, {"days": 6}, {"days": 31}, {"days": "14"}, {"days": True},
        {"days": 14.5}, {"days": None}, {"days": 0}]]
    mine = [{"chosen": c, "close": cl, "days": my_days(c, cl)} for c, cl in [
        (30, True), (14, True), (30, False), (3, True), (90, True), (None, True), (7, False)]]
    pair = [{"mine": m, "d1": d1, "theirs": t, "days": pair_days(m, d1, t)} for m, d1, t in [
        (7, True, 30), (30, True, 30), (30, True, 14), (14, True, 30), (30, True, None), (30, False, None), (30, False, 14),
        (7, False, None)]]
    now = 1762000000000
    times = [now, now - DAY, now - 7 * DAY, now - 7 * DAY - 1, now - 14 * DAY, now - 30 * DAY, now - 30 * DAY - 1]
    sweep = [{"now": now, "days": d, "times": times, "kept": kept(times, d, now)} for d in (7, 14, 30)]
    return {"note": "cap d1, code/shared/docs/protocol.md section 16; written by code/shared/tools/d1_ref.py",
            "limits": {"default": DEFAULT, "longest": LONGEST, "kinds": list(KINDS), "dayMs": DAY},
            "keys": {"A": kA, "B": kB}, "states": states, "refuse": {k: s for k, s in bad.items()}, "accept": accept,
            "envelope": envelope, "mine": mine, "pair": pair, "sweep": sweep}


def verify(v):
    n = 0
    for k, x in v["states"].items():
        s = x["state"]
        check_state(s)
        assert signed_bytes(D_GROUP, s).decode() == x["signed"] and obj_hash(s) == x["hash"], k
        assert group_days(s) == x["days"], k
        n += 1
    for k, s in v["refuse"].items():
        try:
            check_state(s)
        except ValueError:
            n += 1
            continue
        raise SystemExit("should refuse %s" % k)
    st = {k: x["state"] for k, x in v["states"].items()}
    for a in v["accept"]:
        assert accept_state(st[a["current"]] if a["current"] else None, st[a["new"]])[0] == a["accept"], a; n += 1
    for c in v["envelope"]:
        assert envelope_days(c["o"]) == c["days"], c; n += 1
    for c in v["mine"]:
        assert my_days(c["chosen"], c["close"]) == c["days"], c; n += 1
    for c in v["pair"]:
        assert pair_days(c["mine"], c["d1"], c["theirs"]) == c["days"], c; n += 1
    for c in v["sweep"]:
        assert kept(c["times"], c["days"], c["now"]) == c["kept"], c; n += 1
    return n


def main():
    v = build()
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "d1-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "d1-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            n = verify(json.load(f))
        print("wrote %s: %d checks passed" % (os.path.normpath(path), n))


if __name__ == "__main__":
    main()

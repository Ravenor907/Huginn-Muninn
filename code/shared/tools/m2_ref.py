#!/usr/bin/env python3
"""Reference for cap m2 (code/shared/docs/protocol.md, section 12): photo albums, video messages, polls and link
previews, and the test vectors both apps check (code/shared/vectors/m2-vectors.json). Run it to write the vectors; it checks
every case twice (building, then from the file).

Like m1 there is no new cryptography: polls and previews ride in the payloads of rooms "rich" and "gmsg",
votes in rooms "mx" and "group", and albums and videos are fields in the clear part of a photo piece (which is
itself sealed to the recipient on every route). What both apps must agree on is what is well-formed and how
votes that arrive in any order settle.
"""
import base64, json, os

TS_MAX = 2 ** 53
MAX_ID = 64
ID_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
HEX = set("0123456789abcdef")

ALBUM_MAX = 10           # photos in one album
VIDEO_MAX_MS = 60_000    # a video message is at most a minute
QUESTION_MAX = 200       # code points
OPTION_MAX = 100
OPTIONS_MIN, OPTIONS_MAX = 2, 10
URL_MAX = 2000
TITLE_MAX = 200
DESC_MAX = 300
THUMB_MAX = 24_000       # base64 characters of the preview picture (a small JPEG)


class Refused(ValueError):
    pass


def cp(s):
    return len(s)  # Python strings are code points


def ts_ok(x):
    return isinstance(x, int) and not isinstance(x, bool) and 0 <= x < TS_MAX


def int_ok(x, lo, hi):
    return isinstance(x, int) and not isinstance(x, bool) and lo <= x <= hi


def is_msg_id(s):
    return isinstance(s, str) and 1 <= len(s) <= MAX_ID and all(c in ID_CHARS for c in s)


def is_logical_id(s):
    return isinstance(s, str) and len(s) == 32 and all(c in HEX for c in s)


def clean(s, lo, hi):
    """Text of lo..hi code points, not only spaces, no control characters except a newline."""
    return (isinstance(s, str) and lo <= cp(s) <= hi and (lo == 0 or s.strip() != "")
            and not any((ord(c) < 0x20 and c != "\n") or ord(c) == 0x7F for c in s))


def need_poll(p):
    poll = p.get("poll")
    if not isinstance(poll, dict): raise Refused("poll")
    if not clean(poll.get("q"), 1, QUESTION_MAX) or "\n" in poll["q"]: raise Refused("poll.q")
    o = poll.get("a")
    if not isinstance(o, list) or not OPTIONS_MIN <= len(o) <= OPTIONS_MAX: raise Refused("poll.a")
    for x in o:
        if not clean(x, 1, OPTION_MAX) or "\n" in x: raise Refused("poll.a")
    if len(set(x.strip().lower() for x in o)) != len(o): raise Refused("poll.a same")
    if "m" in poll and not isinstance(poll["m"], bool): raise Refused("poll.m")


def need_preview(p):
    lp = p.get("lp")
    if not isinstance(lp, dict): raise Refused("lp")
    u = lp.get("u")
    if not isinstance(u, str) or not 8 <= len(u) <= URL_MAX or not (u.startswith("https://") or u.startswith("http://")) \
            or any(ord(c) <= 0x20 or ord(c) == 0x7F for c in u):
        raise Refused("lp.u")
    if not clean(lp.get("t"), 1, TITLE_MAX) or "\n" in lp["t"]: raise Refused("lp.t")
    if "d" in lp and (not clean(lp["d"], 0, DESC_MAX)): raise Refused("lp.d")
    if "i" in lp:
        i = lp["i"]
        if not isinstance(i, str) or not 4 <= len(i) <= THUMB_MAX: raise Refused("lp.i")
        try:
            raw = base64.b64decode(i, validate=True)
        except Exception:
            raise Refused("lp.i base64")
        if raw[:3] != b"\xff\xd8\xff": raise Refused("lp.i jpeg")


def need_votes(p):
    v = p.get("v")
    if not isinstance(v, list) or len(v) > OPTIONS_MAX: raise Refused("v")
    for x in v:
        if not int_ok(x, 0, OPTIONS_MAX - 1): raise Refused("v")
    if v != sorted(set(v)): raise Refused("v order")


def check(room, p):
    """Raises Refused unless [p] is well-formed under m2 for [room]:
    "rich" and "gmsg" (the m2 fields "poll" and "lp"), "mx" and "group" (t "vote"), "piece" (a photo piece's
    clear fields "al", "ai", "an", "kind", "ms")."""
    if not isinstance(p, dict): raise Refused("not an object")
    if room in ("rich", "gmsg"):
        # The text itself follows m1 (11.1); m2 adds only the fields below.
        t = p.get("text")
        if room == "rich" and (not isinstance(t, str) or len(t) > 2000 or not t.strip()): raise Refused("text")
        if "poll" in p: need_poll(p)
        if "lp" in p: need_preview(p)
        return
    if room == "mx":
        if p.get("t") != "vote": raise Refused("t")
        if not is_msg_id(p.get("msg")): raise Refused("msg")
        if not ts_ok(p.get("ts")): raise Refused("ts")
        need_votes(p)
        return
    if room == "group":
        if p.get("t") != "vote": raise Refused("t")
        g = p.get("g")
        if not isinstance(g, str) or not 1 <= len(g) <= 40: raise Refused("g")
        if not is_logical_id(p.get("id")): raise Refused("id")
        if not is_logical_id(p.get("msg")): raise Refused("msg")
        if not ts_ok(p.get("ts")): raise Refused("ts")
        need_votes(p)
        return
    if room == "piece":
        if any(k in p for k in ("al", "ai", "an")):
            if not is_msg_id(p.get("al")): raise Refused("al")
            if not int_ok(p.get("an"), 1, ALBUM_MAX): raise Refused("an")
            if not int_ok(p.get("ai"), 0, ALBUM_MAX - 1) or p["ai"] >= p["an"]: raise Refused("ai")
        kind = p.get("kind", "photo")
        if kind == "video":
            if not int_ok(p.get("ms"), 1, VIDEO_MAX_MS): raise Refused("ms")
            if "al" in p: raise Refused("video in an album")
        elif kind == "file":
            # Cap f1 (section 14): a small file over the relays, with its name.
            fn = p.get("fn")
            if not isinstance(fn, str) or not fn.strip() or len(fn) > 200 or any(ord(c) < 0x20 or c in "/\\" for c in fn): raise Refused("fn")
            if "al" in p: raise Refused("file in an album")
        elif kind == "gif":
            # Cap a1 (section 25): an animated GIF, never in an album.
            if "al" in p: raise Refused("GIF in an album")
        elif kind not in ("photo", "voice"):
            raise Refused("kind")
        return
    raise Refused("room")


def is_video(data):
    """A video message is an MP4: its first box is "ftyp"."""
    return len(data) >= 12 and data[4:8] == b"ftyp"


def apply_votes(n, events):
    """Votes on one poll with [n] options, in the order they arrive: [{from, v, ts}]. Each person has one vote
    (a list of options, [] takes it back); a later ts replaces it, an older or equal one changes nothing. A vote
    naming an option the poll doesn't have is ignored. Single choice polls take only the first option.
    Returns the count for each option."""
    best = {}
    for ev in events:
        if any(x >= n for x in ev["v"]): continue
        cur = best.get(ev["from"])
        if cur is None or ev["ts"] > cur[1]: best[ev["from"]] = (ev["v"], ev["ts"])
    counts = [0] * n
    for v, _ in best.values():
        for x in v: counts[x] += 1
    return counts


def fallback(q, options):
    """What an app without m2 shows for a poll: its text."""
    return "Poll: " + q + "\n" + "\n".join("%d. %s" % (i + 1, o) for i, o in enumerate(options))


# ------------------------------------------------------------------ vectors

ID = "6f1c2a9e-3b4d-4c55-9a1e-0d2c3b4a5f60"
GID = "0123456789abcdef0123456789abcdef"
G = "AAECAwQFBgcICQoLDA0ODw"
T = 1761500130000
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0" + b"\x00" * 12).decode()
POLL = {"q": "Pizza or tacos?", "a": ["Pizza", "Tacos"]}
LP = {"u": "https://example.com/a?b=c", "t": "Example Domain", "d": "This domain is for use in examples.", "i": JPEG}

GOOD = {
    "rich": [{"text": fallback(POLL["q"], POLL["a"]), "poll": POLL},
             {"text": "Where?", "poll": {"q": "Where?", "a": ["Home", "Park", "Beach"], "m": True}},
             {"text": "Look https://example.com/a?b=c", "lp": LP},
             {"text": "Look http://example.org", "lp": {"u": "http://example.org", "t": "Example"}},
             {"text": "plain, with a field from later", "future": 1}, {"text": "a\ttab is fine in text"}],
    "gmsg": [{"g": G, "ch": "main", "id": GID, "sv": 1, "text": fallback("Tonight?", ["Yes", "No"]), "ts": T, "poll": {"q": "Tonight?", "a": ["Yes", "No"]}},
             {"g": G, "ch": "main", "id": GID, "sv": 1, "text": "see https://example.com", "ts": T, "lp": {"u": "https://example.com", "t": "Example", "d": ""}}],
    "mx": [{"t": "vote", "msg": ID, "v": [0], "ts": T}, {"t": "vote", "msg": ID, "v": [], "ts": T}, {"t": "vote", "msg": ID, "v": [0, 2, 9], "ts": 0}],
    "group": [{"t": "vote", "g": G, "id": GID, "msg": GID, "v": [1], "ts": T}],
    "piece": [{}, {"kind": "voice", "ms": 1200}, {"al": ID, "ai": 0, "an": 3}, {"al": "a", "ai": 9, "an": 10},
              {"kind": "video", "ms": 60_000}, {"kind": "video", "ms": 1}, {"kind": "file", "fn": "Tax return 2025.pdf"},
              {"kind": "gif"}],
}
REFUSE = {
    "rich": [{"text": "x", "poll": {"q": "Q", "a": ["only one"]}},
             {"text": "x", "poll": {"q": "Q", "a": ["a"] * 0}},
             {"text": "x", "poll": {"q": "Q", "a": [str(i) for i in range(11)]}},
             {"text": "x", "poll": {"q": "", "a": ["a", "b"]}},
             {"text": "x", "poll": {"q": "Q\nQ", "a": ["a", "b"]}},
             {"text": "x", "poll": {"q": "Q", "a": ["a", "A "]}},
             {"text": "x", "poll": {"q": "Q", "a": ["a", ""]}},
             {"text": "x", "poll": {"q": "Q", "a": ["a", "x" * (OPTION_MAX + 1)]}},
             {"text": "x", "poll": {"q": "Q", "a": ["a", "b"], "m": 1}},
             {"text": "x", "poll": "Pizza?"},
             {"text": "x", "lp": {"u": "ftp://example.com", "t": "T"}},
             {"text": "x", "lp": {"u": "javascript:alert(1)", "t": "T"}},
             {"text": "x", "lp": {"u": "https://exa mple.com", "t": "T"}},
             {"text": "x", "lp": {"u": "https://example.com", "t": ""}},
             {"text": "x", "lp": {"u": "https://example.com", "t": "T", "d": "x" * (DESC_MAX + 1)}},
             {"text": "x", "lp": {"u": "https://example.com", "t": "T", "i": "not base64!"}},
             {"text": "x", "lp": {"u": "https://example.com", "t": "T", "i": base64.b64encode(b"\x89PNG\r\n\x1a\n").decode()}},
             {"text": "x", "lp": {"u": "https://example.com", "t": "T", "i": "A" * (THUMB_MAX + 4)}},
             {"text": "", "poll": POLL}],
    "gmsg": [{"g": G, "ch": "main", "id": GID, "sv": 1, "text": "x", "ts": T, "poll": {"q": "Q", "a": []}}],
    "mx": [{"t": "vote", "msg": ID, "v": [0, 0], "ts": T}, {"t": "vote", "msg": ID, "v": [2, 1], "ts": T},
           {"t": "vote", "msg": ID, "v": [10], "ts": T}, {"t": "vote", "msg": ID, "v": [-1], "ts": T},
           {"t": "vote", "msg": ID, "v": 1, "ts": T}, {"t": "vote", "msg": ID, "v": [0]},
           {"t": "vote", "msg": "", "v": [0], "ts": T}, {"t": "poll", "msg": ID, "v": [0], "ts": T}],
    "group": [{"t": "vote", "g": G, "id": GID, "msg": ID, "v": [0], "ts": T},
              {"t": "vote", "g": G, "id": "x", "msg": GID, "v": [0], "ts": T}],
    "piece": [{"al": ID}, {"al": ID, "ai": 3, "an": 3}, {"al": ID, "ai": 0, "an": 11}, {"al": "", "ai": 0, "an": 1},
              {"kind": "video"}, {"kind": "video", "ms": 60_001}, {"kind": "video", "ms": 5, "al": ID, "ai": 0, "an": 2},
              {"kind": "webp"}, {"kind": "gif", "al": ID, "ai": 0, "an": 2}, {"kind": "file"}, {"kind": "file", "fn": "../etc/passwd"}, {"kind": "file", "fn": " "}],
}

A, B, C = "A", "B", "C"
VOTES = [
    {"n": 2, "events": [{"from": A, "v": [0], "ts": 10}], "result": [1, 0]},
    {"n": 2, "events": [{"from": A, "v": [0], "ts": 10}, {"from": A, "v": [1], "ts": 20}], "result": [0, 1]},
    {"n": 2, "events": [{"from": A, "v": [1], "ts": 20}, {"from": A, "v": [0], "ts": 10}], "result": [0, 1]},
    {"n": 2, "events": [{"from": A, "v": [0], "ts": 10}, {"from": A, "v": [], "ts": 11}], "result": [0, 0]},
    {"n": 3, "events": [{"from": A, "v": [0, 2], "ts": 1}, {"from": B, "v": [2], "ts": 1}, {"from": C, "v": [1], "ts": 1}],
     "result": [1, 1, 2]},
    {"n": 2, "events": [{"from": A, "v": [5], "ts": 10}], "result": [0, 0]},
    {"n": 2, "events": [{"from": A, "v": [0], "ts": 10}, {"from": A, "v": [1], "ts": 10}], "result": [1, 0]},
]
FALLBACK = [{"q": "Pizza or tacos?", "a": ["Pizza", "Tacos"], "text": "Poll: Pizza or tacos?\n1. Pizza\n2. Tacos"}]
VIDEO = [{"hex": (b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 8).hex(), "ok": True},
         {"hex": b"\xff\xd8\xff\xe0JFIF\x00\x00\x00\x00".hex(), "ok": False},
         {"hex": b"\x00\x00\x00\x18fty".hex(), "ok": False}]


def verify(v):
    for room, cases in v["good"].items():
        for p in cases: check(room, p)
    for room, cases in v["refuse"].items():
        for p in cases:
            try:
                check(room, p)
            except Refused:
                continue
            raise SystemExit("should refuse %s %r" % (room, p))
    for c in v["votes"]:
        if apply_votes(c["n"], c["events"]) != c["result"]: raise SystemExit("votes %r" % c)
    for c in v["fallback"]:
        if fallback(c["q"], c["a"]) != c["text"]: raise SystemExit("fallback %r" % c)
    for c in v["video"]:
        if is_video(bytes.fromhex(c["hex"])) != c["ok"]: raise SystemExit("video %r" % c)


def main():
    v = {"note": "cap m2, code/shared/docs/protocol.md section 12; written by code/shared/tools/m2_ref.py",
         "limits": {"album": ALBUM_MAX, "videoMs": VIDEO_MAX_MS, "question": QUESTION_MAX, "option": OPTION_MAX,
                    "options": [OPTIONS_MIN, OPTIONS_MAX], "url": URL_MAX, "title": TITLE_MAX, "description": DESC_MAX,
                    "thumb": THUMB_MAX},
         "good": GOOD, "refuse": REFUSE, "votes": VOTES, "fallback": FALLBACK, "video": VIDEO}
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "m2-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "m2-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

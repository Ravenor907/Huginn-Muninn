#!/usr/bin/env python3
"""Reference for the messenger features of cap m1 (code/shared/docs/protocol.md, section 11): replies, reactions,
edits, typing and active status, in 1:1 chats and groups, and the test vectors both apps check
(code/shared/vectors/m1-vectors.json). Run it to write the vectors; it checks every case twice (building, then from the file).

Nothing here is new cryptography: every payload is UTF-8 JSON sealed exactly like a private message
(room "live" always with v1, the others through the ratchet when the contact has it). What both apps must
agree on is which payloads are well-formed, and how reactions and edits that arrive in any order settle.
"""
import json, os, sys

MAX_TEXT = 2000          # code points, like private and group messages
MAX_EMOJI = 16           # code points in one reaction (flags and family emoji take several)
MAX_ID = 64              # a 1:1 message id: letters, digits, "-" and "_" (Muninn and Huginn use UUIDs)
LIVE_WINDOW_MS = 60_000  # a typing or active signal counts if its clock is within a minute of ours
TS_MAX = 2 ** 53
ID_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
HEX = set("0123456789abcdef")


class Refused(ValueError):
    pass


def ts_ok(x):
    return isinstance(x, int) and not isinstance(x, bool) and 0 <= x < TS_MAX


def need_ts(p):
    if not ts_ok(p.get("ts")): raise Refused("ts")


def is_msg_id(s):
    return isinstance(s, str) and 1 <= len(s) <= MAX_ID and all(c in ID_CHARS for c in s)


def is_logical_id(s):
    return isinstance(s, str) and len(s) == 32 and all(c in HEX for c in s)


def need_text(p, k="text"):
    t = p.get(k)
    if not isinstance(t, str) or len(t) > MAX_TEXT or not t.strip(): raise Refused(k)


def need_emoji(p):
    e = p.get("e")
    if not isinstance(e, str): raise Refused("e")
    if e == "": return            # takes my reaction away
    if len(e) > MAX_EMOJI or not e.strip() or any(ord(c) < 0x20 or ord(c) == 0x7F for c in e): raise Refused("e")


def check(room, p):
    """Raises Refused unless [p] is a well-formed plaintext for [room] ("rich", "mx", "live", "gmsg" or "group")."""
    if not isinstance(p, dict): raise Refused("not an object")
    if room == "rich":
        need_text(p)
        if "re" in p and not is_msg_id(p["re"]): raise Refused("re")
        return
    if room == "mx":
        t = p.get("t")
        if not is_msg_id(p.get("msg")): raise Refused("msg")
        need_ts(p)
        if t == "react": need_emoji(p)
        elif t == "edit": need_text(p)
        else: raise Refused("t")
        return
    if room == "live":
        if p.get("t") not in ("typing", "active"): raise Refused("t")
        if not isinstance(p.get("on"), bool): raise Refused("on")
        need_ts(p)
        return
    if room == "gmsg":
        # Only the m1 part: "re", when there, names a message of the same group.
        if "re" in p and not is_logical_id(p["re"]): raise Refused("re")
        return
    if room == "group":
        t = p.get("t")
        if t not in ("react", "edit"): raise Refused("t")
        g = p.get("g")
        if not isinstance(g, str) or not 1 <= len(g) <= 40: raise Refused("g")
        if not is_logical_id(p.get("id")): raise Refused("id")
        if not is_logical_id(p.get("msg")): raise Refused("msg")
        need_ts(p)
        if t == "react": need_emoji(p)
        else: need_text(p)
        return
    raise Refused("room")


def fresh(ts, now):
    """A typing or active signal: only if the sender's clock is within a minute of the receiver's."""
    return now - LIVE_WINDOW_MS <= ts <= now + LIVE_WINDOW_MS


def apply_reactions(events):
    """Every reaction to one message, in the order they arrive: [{from, e, ts}]. Each person has at most one;
    a later ts replaces it ("" takes it away), an older or equal one changes nothing (late copies, crossed
    routes). Returns {from: emoji} of the reactions that stand."""
    best = {}
    for ev in events:
        cur = best.get(ev["from"])
        if cur is None or ev["ts"] > cur[1]: best[ev["from"]] = (ev["e"], ev["ts"])
    return {k: e for k, (e, _) in sorted(best.items()) if e != ""}


def apply_edits(author, text, events):
    """Edits of one message, in the order they arrive: [{from, text, ts}]. Only its author may edit; the
    highest ts wins. Returns (text, edited, ts of the edit shown)."""
    shown, edited, at = text, False, 0
    for ev in events:
        if ev["from"] != author or ev["ts"] <= at: continue
        shown, edited, at = ev["text"], True, ev["ts"]
    return {"text": shown, "edited": edited, "ts": at}


# ------------------------------------------------------------------ vectors

ID = "6f1c2a9e-3b4d-4c55-9a1e-0d2c3b4a5f60"
GID = "0123456789abcdef0123456789abcdef"
G = "AAECAwQFBgcICQoLDA0ODw"
T = 1761500130000

GOOD = {
    "rich": [{"text": "On my way"}, {"text": "Yes!", "re": ID}, {"text": "multi\nline", "re": "abc_DEF-123"},
             {"text": "x" * MAX_TEXT}, {"text": "🙂", "re": ID, "future": 1}],
    "mx": [{"t": "react", "msg": ID, "e": "❤️", "ts": T}, {"t": "react", "msg": ID, "e": "", "ts": T},
           {"t": "react", "msg": ID, "e": "👍🏽", "ts": 0}, {"t": "react", "msg": ID, "e": "👨‍👩‍👧‍👦", "ts": T},
           {"t": "edit", "msg": ID, "text": "Fixed the typo", "ts": T}],
    "live": [{"t": "typing", "on": True, "ts": T}, {"t": "typing", "on": False, "ts": T}, {"t": "active", "on": True, "ts": T}],
    "gmsg": [{"g": G, "ch": "main", "id": GID, "sv": 1, "text": "hi", "ts": T},
             {"g": G, "ch": "main", "id": GID, "sv": 1, "text": "hi", "ts": T, "re": GID}],
    "group": [{"t": "react", "g": G, "id": GID, "msg": GID, "e": "😂", "ts": T},
              {"t": "react", "g": G, "id": GID, "msg": GID, "e": "", "ts": T},
              {"t": "edit", "g": G, "id": GID, "msg": GID, "text": "edited", "ts": T}],
}
REFUSE = {
    "rich": [{}, {"text": ""}, {"text": "   "}, {"text": 5}, {"text": "x" * (MAX_TEXT + 1)}, {"text": "hi", "re": ""},
             {"text": "hi", "re": "has space"}, {"text": "hi", "re": "x" * (MAX_ID + 1)}, {"text": "hi", "re": 7}],
    "mx": [{"t": "react", "msg": ID, "e": "❤️"}, {"t": "react", "msg": ID, "e": "❤️", "ts": -1},
           {"t": "react", "msg": ID, "e": "❤️", "ts": 1.5}, {"t": "react", "msg": ID, "e": "❤️", "ts": "5"},
           {"t": "react", "msg": "", "e": "❤️", "ts": T}, {"t": "react", "msg": ID, "e": " ", "ts": T},
           {"t": "react", "msg": ID, "e": "a\u0007", "ts": T}, {"t": "react", "msg": ID, "e": "x" * (MAX_EMOJI + 1), "ts": T},
           {"t": "react", "msg": ID, "ts": T}, {"t": "edit", "msg": ID, "text": "", "ts": T},
           {"t": "edit", "msg": ID, "ts": T}, {"t": "pin", "msg": ID, "ts": T}, {"msg": ID, "e": "❤️", "ts": T}],
    "live": [{"t": "typing", "ts": T}, {"t": "typing", "on": 1, "ts": T}, {"t": "active", "on": True},
             {"t": "away", "on": True, "ts": T}],
    "gmsg": [{"g": G, "ch": "main", "id": GID, "sv": 1, "text": "hi", "ts": T, "re": ID},
             {"g": G, "ch": "main", "id": GID, "sv": 1, "text": "hi", "ts": T, "re": GID.upper()}],
    "group": [{"t": "react", "g": G, "id": GID, "msg": ID, "e": "😂", "ts": T},
              {"t": "react", "g": "", "id": GID, "msg": GID, "e": "😂", "ts": T},
              {"t": "react", "g": G, "id": "x", "msg": GID, "e": "😂", "ts": T},
              {"t": "edit", "g": G, "id": GID, "msg": GID, "text": " ", "ts": T},
              {"t": "edit", "g": G, "id": GID, "msg": GID, "text": "x", "ts": -5},
              {"t": "pin", "g": G, "id": GID, "msg": GID, "ts": T}],
}

A, B, C = "A", "B", "C"   # who reacted, as the apps key it (a chat key; short names keep the file readable)
REACTIONS = [
    {"events": [{"from": A, "e": "❤️", "ts": 10}], "result": {A: "❤️"}},
    {"events": [{"from": A, "e": "❤️", "ts": 10}, {"from": A, "e": "😂", "ts": 20}], "result": {A: "😂"}},
    {"events": [{"from": A, "e": "😂", "ts": 20}, {"from": A, "e": "❤️", "ts": 10}], "result": {A: "😂"}},
    {"events": [{"from": A, "e": "❤️", "ts": 10}, {"from": A, "e": "", "ts": 11}], "result": {}},
    {"events": [{"from": A, "e": "", "ts": 11}, {"from": A, "e": "❤️", "ts": 10}], "result": {}},
    {"events": [{"from": A, "e": "❤️", "ts": 10}, {"from": A, "e": "👍", "ts": 10}], "result": {A: "❤️"}},
    {"events": [{"from": A, "e": "❤️", "ts": 10}, {"from": B, "e": "❤️", "ts": 5}, {"from": C, "e": "😮", "ts": 7},
                {"from": B, "e": "", "ts": 9}], "result": {A: "❤️", C: "😮"}},
]
EDITS = [
    {"author": A, "text": "helo", "events": [{"from": A, "text": "hello", "ts": 10}], "result": {"text": "hello", "edited": True, "ts": 10}},
    {"author": A, "text": "helo", "events": [{"from": B, "text": "hacked", "ts": 10}], "result": {"text": "helo", "edited": False, "ts": 0}},
    {"author": A, "text": "v0", "events": [{"from": A, "text": "v2", "ts": 20}, {"from": A, "text": "v1", "ts": 10}],
     "result": {"text": "v2", "edited": True, "ts": 20}},
    {"author": A, "text": "v0", "events": [{"from": A, "text": "v1", "ts": 10}, {"from": A, "text": "v1b", "ts": 10}],
     "result": {"text": "v1", "edited": True, "ts": 10}},
]
NOW = 1761500200000
FRESH = [{"ts": NOW, "now": NOW, "ok": True}, {"ts": NOW - 60_000, "now": NOW, "ok": True}, {"ts": NOW - 60_001, "now": NOW, "ok": False},
         {"ts": NOW + 60_000, "now": NOW, "ok": True}, {"ts": NOW + 60_001, "now": NOW, "ok": False}]


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
    for c in v["reactions"]:
        if apply_reactions(c["events"]) != c["result"]: raise SystemExit("reactions %r" % c)
    for c in v["edits"]:
        if apply_edits(c["author"], c["text"], c["events"]) != c["result"]: raise SystemExit("edits %r" % c)
    for c in v["fresh"]:
        if fresh(c["ts"], c["now"]) != c["ok"]: raise SystemExit("fresh %r" % c)


def main():
    v = {"note": "cap m1, code/shared/docs/protocol.md section 11; written by code/shared/tools/m1_ref.py",
         "limits": {"text": MAX_TEXT, "emoji": MAX_EMOJI, "id": MAX_ID, "liveWindowMs": LIVE_WINDOW_MS},
         "good": GOOD, "refuse": REFUSE, "reactions": REACTIONS, "edits": EDITS, "fresh": FRESH}
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "vectors", "m1-vectors.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(v, f, indent=1, ensure_ascii=False)
        f.write("\n")
    with open(path, encoding="utf-8") as f:
        verify(json.load(f))
    print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

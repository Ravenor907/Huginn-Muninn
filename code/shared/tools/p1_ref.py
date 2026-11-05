#!/usr/bin/env python3
"""Reference for cap p1, profile pictures and status (code/shared/docs/protocol.md, section 22), and the test vectors
both apps check (code/shared/vectors/p1-vectors.json). Run it to write the vectors; it checks every case twice (building,
then from the file).

 - Room "profile": {"t":"profile","v":<int>,"pic":"<base64 JPEG>","col":"#rrggbb"}, through the ratchet, once per
   peer per version. The picture is 256x256, at most 24 KB on the receiving side; a sender keeps it at 12 KB or less so
   that one relay event carries it. "pic":"" clears it.
 - Room "live" "active" gets "st" (online, away or dnd) and "cs" (custom status, at most 80 characters). Invisible is
   sent as not active, without "st".
 - A person's fallback colour when none came: one of 12 from the SHA-256 of their chat key.
"""
import base64, hashlib, io, json, os, struct

from PIL import Image

MAX_BYTES = 24 * 1024
SEND_MAX_BYTES = 12 * 1024
SIDE = 256
QUALITIES = [80, 70, 60, 50, 40, 30, 20]
MAX_CUSTOM = 80
STATUSES = ("online", "away", "dnd")
RATE_PER_10S = 1
PALETTE = ["#d9853b", "#3fb27f", "#4f8fde", "#b86ad9", "#e0b44c", "#d9534f", "#2fa9a0", "#c96b8e",
           "#7a9b3c", "#e07b39", "#5c7cfa", "#8d6e63"]


def b64(b): return base64.b64encode(b).decode()
def b64d(s): return base64.b64decode(s, validate=True)


# ------------------------------------------------------------------ checks

def text_ok(s, limit):
    return isinstance(s, str) and len(s) <= limit and all(ord(c) >= 0x20 and ord(c) != 0x7F for c in s)


def colour_ok(c):
    return isinstance(c, str) and len(c) == 7 and c[0] == "#" and all(x in "0123456789abcdefABCDEF" for x in c[1:])


def jpeg_size(data):
    """(width, height) from the JPEG's frame header, or None if it isn't a JPEG. Reads only the markers before the scan."""
    if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8: return None
    at = 2
    while at + 4 <= len(data):
        if data[at] != 0xFF: return None
        m = data[at + 1]
        if m == 0xFF: at += 1; continue
        if m in (0xD8, 0x01) or 0xD0 <= m <= 0xD7: at += 2; continue
        if m == 0xD9 or m == 0xDA: return None
        (ln,) = struct.unpack(">H", data[at + 2:at + 4])
        if ln < 2 or at + 2 + ln > len(data): return None
        if m in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if ln < 7: return None
            h, w = struct.unpack(">HH", data[at + 5:at + 9])
            return (w, h)
        at += 2 + ln
    return None


def picture_ok(data):
    """The bytes of a received picture: at most MAX_BYTES, a JPEG, exactly SIDE x SIDE."""
    return len(data) <= MAX_BYTES and jpeg_size(data) == (SIDE, SIDE)


def profile_ok(o):
    """A room "profile" payload: t, v (an integer 1 <= v < 2^53), pic (base64, "" clears) and col. Other fields are ignored."""
    try:
        if o.get("t") != "profile": return False
        v = o.get("v")
        if not isinstance(v, int) or isinstance(v, bool) or v < 1 or v >= 2 ** 53: return False
        if not colour_ok(o.get("col")): return False
        pic = o.get("pic")
        if not isinstance(pic, str): return False
        if pic == "": return True
        return picture_ok(b64d(pic))
    except Exception:
        return False


def live_ok(o):
    """A room "live" payload with the p1 fields (an app without p1 ignores st and cs; one with it refuses bad ones)."""
    if o.get("t") not in ("typing", "active") or not isinstance(o.get("on"), bool): return False
    ts = o.get("ts")
    if not isinstance(ts, int) or isinstance(ts, bool) or ts < 0 or ts >= 2 ** 53: return False
    if "st" in o and o["st"] not in STATUSES: return False
    if "cs" in o and not text_ok(o["cs"], MAX_CUSTOM): return False
    return True


def active_msg(on, ts, status, custom):
    """What the sender puts in room "live" for its status: invisible is "not active" with no st."""
    if status == "invisible" or not on:
        return {"t": "active", "on": False, "ts": ts}
    o = {"t": "active", "on": True, "ts": ts, "st": status if status in STATUSES else "online"}
    if custom:
        o["cs"] = custom[:MAX_CUSTOM]
    return o


def status_of(active, st, heard):
    """What a receiver shows: active (a fresh "on" in the last 5 minutes) shows st (online when the sender has no p1),
    otherwise offline for someone it ever heard a live message from, and nothing for anyone else."""
    if active: return st if st in STATUSES else "online"
    return "offline" if heard else ""


def default_colour(key):
    return PALETTE[hashlib.sha256(key.encode()).digest()[0] % len(PALETTE)]


def profile_msg(v, pic, col):
    return {"t": "profile", "v": v, "pic": pic, "col": col}


def encode(img):
    """A sender's picture: the centre square scaled to SIDE, JPEG at the first quality of the ladder that fits SEND_MAX_BYTES."""
    w, h = img.size
    s = min(w, h)
    img = img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s)).resize((SIDE, SIDE)).convert("RGB")
    for q in QUALITIES:
        out = io.BytesIO()
        img.save(out, "JPEG", quality=q)
        if out.tell() <= SEND_MAX_BYTES:
            return out.getvalue(), q
    return None, 0


# ------------------------------------------------------------------ vectors

def jpeg(w, h, colour, progressive=False, quality=80):
    out = io.BytesIO()
    Image.new("RGB", (w, h), colour).save(out, "JPEG", quality=quality, progressive=progressive)
    return out.getvalue()


def build():
    ok = jpeg(SIDE, SIDE, (58, 123, 213))
    prog = jpeg(SIDE, SIDE, (217, 133, 59), progressive=True)
    small = jpeg(200, 200, (63, 178, 127))
    wide = jpeg(256, 200, (63, 178, 127))
    tall = jpeg(200, 256, (63, 178, 127))
    notjpeg = b"\x89PNG\r\n\x1a\n" + b"\0" * 100
    too_big = b"\0" * (MAX_BYTES + 1)
    at_cap_not_jpeg = b"\0" * MAX_BYTES
    long_text = "x" * MAX_CUSTOM
    for p in (ok, prog, small, wide, tall):
        assert jpeg_size(p) == Image.open(io.BytesIO(p)).size
    return {
        "about": "code/shared/docs/protocol.md section 22 (cap p1). Written by code/shared/tools/p1_ref.py.",
        "limits": {"maxBytes": MAX_BYTES, "sendMaxBytes": SEND_MAX_BYTES, "side": SIDE, "qualities": QUALITIES,
                   "maxCustom": MAX_CUSTOM, "statuses": list(STATUSES), "ratePer10s": RATE_PER_10S, "palette": PALETTE},
        "pictures": {
            # name: [base64, width, height, bytes, accepted]
            "ok": [b64(ok), SIDE, SIDE, len(ok), True],
            "progressive": [b64(prog), SIDE, SIDE, len(prog), True],
            "small": [b64(small), 200, 200, len(small), False],
            "wide": [b64(wide), 256, 200, len(wide), False],
            "tall": [b64(tall), 200, 256, len(tall), False],
            "notJpeg": [b64(notjpeg), 0, 0, len(notjpeg), False],
            "tooBig": [b64(too_big), 0, 0, len(too_big), False],
            "atCapNotJpeg": [b64(at_cap_not_jpeg), 0, 0, len(at_cap_not_jpeg), False],
        },
        "profile": {
            "good": [profile_msg(1, b64(ok), "#3a7bd5"), profile_msg(7, b64(prog), "#D9853B"), profile_msg(3, "", "#000000"),
                     dict(profile_msg(2, b64(ok), "#3a7bd5"), extra="ignored"), profile_msg(2 ** 53 - 1, "", "#ffffff")],
            "refuse": [{"t": "profile", "v": 1, "pic": "", "col": "3a7bd5"}, {"t": "profile", "v": 1, "pic": "", "col": "#3a7bd"},
                       {"t": "profile", "v": 1, "pic": "", "col": "#gg0000"}, {"t": "profile", "v": 1, "pic": ""},
                       {"t": "profile", "v": 0, "pic": "", "col": "#3a7bd5"}, {"t": "profile", "v": -1, "pic": "", "col": "#3a7bd5"},
                       {"t": "profile", "v": "1", "pic": "", "col": "#3a7bd5"}, {"t": "profile", "v": 2 ** 53, "pic": "", "col": "#3a7bd5"},
                       {"t": "profile", "v": 1, "col": "#3a7bd5"}, {"t": "profile", "v": 1, "pic": None, "col": "#3a7bd5"},
                       {"t": "profile", "v": 1, "pic": "not base64!", "col": "#3a7bd5"},
                       {"t": "profile", "v": 1, "pic": b64(small), "col": "#3a7bd5"}, {"t": "profile", "v": 1, "pic": b64(wide), "col": "#3a7bd5"},
                       {"t": "profile", "v": 1, "pic": b64(tall), "col": "#3a7bd5"}, {"t": "profile", "v": 1, "pic": b64(notjpeg), "col": "#3a7bd5"},
                       # (the pictures over the cap are in "pictures": tooBig and atCapNotJpeg)
                       {"t": "picture", "v": 1, "pic": "", "col": "#3a7bd5"}, {"v": 1, "pic": "", "col": "#3a7bd5"}],
            # Versions: a receiver keeps the highest v per sender; the same or a lower one changes nothing.
            "versions": [[0, 1, True], [1, 1, False], [5, 3, False], [5, 6, True]],
        },
        "live": {
            "good": [{"t": "active", "on": True, "ts": 1761500130000, "st": "online"}, {"t": "active", "on": True, "ts": 1761500130000, "st": "away", "cs": "Lunch"},
                     {"t": "active", "on": True, "ts": 1761500130000, "st": "dnd", "cs": long_text}, {"t": "active", "on": False, "ts": 1761500130000},
                     {"t": "active", "on": True, "ts": 1761500130000}, {"t": "typing", "on": True, "ts": 1761500130000},
                     {"t": "active", "on": True, "ts": 1761500130000, "st": "online", "cs": ""}, {"t": "active", "on": False, "ts": 0, "st": "away"}],
            "refuse": [{"t": "active", "on": True, "ts": 1761500130000, "st": "invisible"}, {"t": "active", "on": True, "ts": 1761500130000, "st": "offline"},
                       {"t": "active", "on": True, "ts": 1761500130000, "st": ""}, {"t": "active", "on": True, "ts": 1761500130000, "st": 1},
                       {"t": "active", "on": True, "ts": 1761500130000, "st": "online", "cs": long_text + "x"},
                       {"t": "active", "on": True, "ts": 1761500130000, "cs": "a\u0000b"}, {"t": "active", "on": True, "ts": 1761500130000, "cs": 5},
                       {"t": "active", "on": True, "ts": 1761500130000, "cs": None}],
            # [on, status, custom, what goes on the wire]
            "sent": [[True, "online", "", active_msg(True, 1, "online", "")], [True, "away", "Lunch", active_msg(True, 1, "away", "Lunch")],
                     [True, "dnd", "", active_msg(True, 1, "dnd", "")], [True, "invisible", "Lunch", active_msg(True, 1, "invisible", "Lunch")],
                     [False, "online", "Lunch", active_msg(False, 1, "online", "Lunch")], [True, "odd", "", active_msg(True, 1, "odd", "")],
                     [True, "online", "y" * 90, active_msg(True, 1, "online", "y" * 90)]],
            # [active, st, heard, shown]
            "shown": [[True, "online", True, "online"], [True, "away", True, "away"], [True, "dnd", True, "dnd"], [True, "", True, "online"],
                      [True, "", False, "online"], [False, "away", True, "offline"], [False, "", False, ""]],
        },
        "colours": {
            "good": ["#3a7bd5", "#3A7BD5", "#000000", "#ffffff"],
            "refuse": ["3a7bd5", "#3a7bd", "#3a7bd5a", "#gg0000", "", "#3a7bd5 ", "rgb(1,2,3)"],
            "default": [[k, default_colour(k)] for k in ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEb",
                                                           "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEc", "x", "")],
        },
    }


def verify(v):
    lim = v["limits"]
    assert lim["maxBytes"] == MAX_BYTES and lim["sendMaxBytes"] == SEND_MAX_BYTES and lim["side"] == SIDE and lim["palette"] == PALETTE
    for name, (pic, w, h, n, accepted) in v["pictures"].items():
        data = b64d(pic)
        assert len(data) == n, name
        size = jpeg_size(data)
        assert (size or (0, 0)) == (w, h), name
        assert picture_ok(data) == accepted, name
    for o in v["profile"]["good"]: assert profile_ok(o), o
    for o in v["profile"]["refuse"]: assert not profile_ok(o), o
    for have, new, taken in v["profile"]["versions"]: assert (new > have) == taken
    for o in v["live"]["good"]: assert live_ok(o), o
    for o in v["live"]["refuse"]: assert not live_ok(o), o
    for on, st, cs, sent in v["live"]["sent"]: assert active_msg(on, 1, st, cs) == sent, (on, st, cs)
    for active, st, heard, shown in v["live"]["shown"]: assert status_of(active, st, heard) == shown
    for c in v["colours"]["good"]: assert colour_ok(c), c
    for c in v["colours"]["refuse"]: assert not colour_ok(c), c
    for k, c in v["colours"]["default"]: assert default_colour(k) == c
    # The encoder: a big picture ends up SIDE x SIDE and within what one relay event carries.
    data, q = encode(Image.radial_gradient("L").resize((1024, 768)).convert("RGB"))
    assert data and jpeg_size(data) == (SIDE, SIDE) and len(data) <= SEND_MAX_BYTES and q in QUALITIES
    return True


def main():
    v = build()
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "p1-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "p1-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

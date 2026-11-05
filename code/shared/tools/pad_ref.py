#!/usr/bin/env python3
"""Reference for relay envelope padding (code/shared/docs/protocol.md section 20).

The outer wrapping {"e":..,"n":..,"d":..} gets a field "z" of zeros so that its base64 (the Nostr event's
content) is exactly one of a few sizes: 1024, 4096, 16384, 32768 characters, then steps of 16384.
The NIP-40 expiration tag (section 20.3) comes from the blurred created_at, plus the blur, plus the longest message
limit, rounded up to the next whole day, so it shows neither the sending time nor the chat's limit.
Writes code/shared/vectors/pad-vectors.json and the Android test copy.
"""
import base64
import json
import os

FIXED = (1024, 4096, 16384, 32768)
STEP = 16384
BLUR_S = 600          # created_at is up to this many seconds before the real time
LONGEST_DAYS = 30     # the longest message limit (section 16)
DAY = 86400


def expiration(created_at):
    at = created_at + BLUR_S + LONGEST_DAYS * DAY
    return (at + DAY - 1) // DAY * DAY


def bucket(n):
    for b in FIXED:
        if n <= b:
            return b
    return (n + STEP - 1) // STEP * STEP


def b64len(n):
    return (n + 2) // 3 * 4


def pad(js):
    body = js.rstrip()
    assert body.endswith("}")
    base = len(body.encode()) + 7          # ,"z":"" adds 7 characters
    target = bucket(b64len(base)) // 4 * 3
    return body[:-1] + ',"z":"' + "0" * (target - base) + '"}'


def verify(v):
    for c in v["expiry"]:
        e = expiration(c["created"])
        assert e == c["expiration"], c
        assert e % DAY == 0
        # Never before the real time (at most BLUR_S - 1 after created_at) plus the longest limit.
        assert e >= c["created"] + BLUR_S - 1 + LONGEST_DAYS * DAY
    for c in v["bucket"]:
        assert bucket(c["n"]) == c["bucket"], c
    for c in v["pad"]:
        js = wrapping(c["d"])
        out = pad(js)
        content = base64.b64encode(out.encode()).decode()
        assert len(content) == c["content"], c
        assert len(json.loads(out)["z"]) == c["zeros"], c
        assert len(content) == bucket(len(content))
        assert out.startswith(js[:-1]) and set(json.loads(out)) - {"z"} == set(json.loads(js)), "padding only adds z"


def wrapping(d_len):
    """The test wrapping: {"e":"AAA..(60)","n":"BBB..(16)","d":"CCC..(d_len)"}, compact."""
    return json.dumps({"e": "A" * 60, "n": "B" * 16, "d": "C" * d_len}, separators=(",", ":"))


def main():
    sizes = [0, 1, 100, 1017, 1018, 1024, 1025, 4089, 4090, 4096, 16384, 16385, 32768, 32769, 49152, 49153, 70000]
    v = {
        "fixed": list(FIXED),
        "step": STEP,
        "bucket": [{"n": n, "bucket": bucket(n)} for n in sizes],
        "pad": [],
        "blur": BLUR_S,
        "longestDays": LONGEST_DAYS,
        "expiry": [{"created": t, "expiration": expiration(t)} for t in
                   (0, 1, 1761500000, 1761523199 - 600, 1761523200 - 600, 1761523200 - 601, 1761523200, 1761609599)],
    }
    # The exact edge: a wrapping whose base64 with an empty "z" is exactly 1024 characters, and one more.
    edge = 768 - 7 - len(wrapping(0).encode())
    for d in (0, 10, 600, 700, edge, edge + 1, 2900, 3000, 12000, 24000, 31000, 36000, 50000):
        out = pad(wrapping(d))
        v["pad"].append({"d": d, "zeros": len(json.loads(out)["z"]), "content": len(base64.b64encode(out.encode()))})
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "pad-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "pad-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

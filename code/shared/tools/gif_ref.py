#!/usr/bin/env python3
"""Reference for animated GIFs (cap a1, code/shared/docs/protocol.md section 25) and the test vectors both apps
check (code/shared/vectors/gif-vectors.json). Run it to write the vectors; it checks every case twice (building,
then from the file).

A GIF is cleaned the same way on both sides, before sending and again on receiving: only the picture survives.
Kept: the header (always written "GIF89a"), the logical screen and its color table, every image (descriptor,
local color table, image data) with the graphic control block just before it, and the NETSCAPE2.0 loop count
(written once, in its usual form, right after the global color table). Dropped: comments, plain text blocks,
every other application block (XMP, ICC profiles, maker data) and anything after the trailer. Limits keep a
hostile GIF from freezing or crashing a decoder: at most 8 MB, 400 frames, 2048 px on a side, and 160 M pixels
of frames in all.

clean(b) gives (bytes, frames) or raises Refused("gif" | "big" | "frames").
"""
import base64, json, os, struct

MAX_BYTES = 8 * 1024 * 1024
MAX_FRAMES = 400
MAX_SIDE = 2048
MAX_AREA = 160 * 1024 * 1024
LOOP_APPS = (b"NETSCAPE2.0", b"ANIMEXTS1.0")


class Refused(ValueError):
    pass


def is_gif(b):
    return len(b) >= 6 and b[:6] in (b"GIF87a", b"GIF89a")


def clean(b):
    if len(b) > MAX_BYTES:
        raise Refused("big")
    if not is_gif(b) or len(b) < 13:
        raise Refused("gif")
    w, h = struct.unpack_from("<HH", b, 6)
    if w == 0 or h == 0:
        raise Refused("gif")
    if w > MAX_SIDE or h > MAX_SIDE:
        raise Refused("big")
    packed = b[10]
    i = 13
    if packed & 0x80:
        i += 3 << ((packed & 7) + 1)
        if i > len(b):
            raise Refused("gif")
    head = b"GIF89a" + b[6:i]
    body = bytearray()
    gce = None
    loop = None
    frames = 0
    area = 0

    def blocks(at):
        """The sub-blocks from at: their data, and where the block after them starts."""
        out = []
        while True:
            if at >= len(b):
                raise Refused("gif")
            n = b[at]
            at += 1
            if n == 0:
                return out, at
            if at + n > len(b):
                raise Refused("gif")
            out.append(b[at:at + n])
            at += n

    while True:
        if i >= len(b):
            break   # ends without a trailer, between blocks: the trailer is added
        t = b[i]
        if t == 0x3B:
            break
        if t == 0x2C:
            if i + 10 > len(b):
                raise Refused("gif")
            x, y, fw, fh = struct.unpack_from("<HHHH", b, i + 1)
            if fw == 0 or fh == 0 or x + fw > w or y + fh > h:
                raise Refused("gif")
            fp = b[i + 9]
            j = i + 10
            if fp & 0x80:
                j += 3 << ((fp & 7) + 1)
            if j >= len(b):
                raise Refused("gif")
            if not 2 <= b[j] <= 8:
                raise Refused("gif")
            _, end = blocks(j + 1)
            frames += 1
            if frames > MAX_FRAMES:
                raise Refused("frames")
            area += fw * fh
            if area > MAX_AREA:
                raise Refused("big")
            if gce is not None:
                body += gce
                gce = None
            body += b[i:end]
            i = end
            continue
        if t == 0x21:
            if i + 2 > len(b):
                raise Refused("gif")
            label = b[i + 1]
            subs, end = blocks(i + 2)
            if label == 0xF9:
                if len(subs) == 1 and len(subs[0]) == 4:
                    gce = b[i:end]
            elif label == 0xFF:
                if loop is None and len(subs) >= 2 and subs[0] in LOOP_APPS and len(subs[1]) == 3 and subs[1][0] == 1:
                    loop = subs[1][1] | (subs[1][2] << 8)
            elif label == 0x01:
                gce = None   # its graphic control block went with it
            i = end
            continue
        raise Refused("gif")
    if frames == 0:
        raise Refused("gif")
    out = bytearray(head)
    if loop is not None:
        out += b"\x21\xff\x0bNETSCAPE2.0\x03\x01" + struct.pack("<H", loop) + b"\x00"
    out += body
    out += b"\x3b"
    return bytes(out), frames


# ---------------------------------------------------------------- building test GIFs by hand

def image_data(pixels):
    """LZW data for the pixels (code size 2): a clear code before each pixel, so codes stay 3 bits."""
    codes = []
    for p in pixels:
        codes += [4, p]
    codes.append(5)
    bits = 0
    nbits = 0
    raw = bytearray()
    for c in codes:
        bits |= c << nbits
        nbits += 3
        while nbits >= 8:
            raw.append(bits & 0xFF)
            bits >>= 8
            nbits -= 8
    if nbits:
        raw.append(bits & 0xFF)
    return bytes([2]) + sub_blocks(raw)


def sub_blocks(data):
    out = bytearray()
    for k in range(0, len(data), 255):
        part = data[k:k + 255]
        out += bytes([len(part)]) + part
    return bytes(out) + b"\x00"


def screen(w, h, version=b"GIF89a"):
    gct = b"\x00\x00\x00\xff\x00\x00\x00\xff\x00\x00\x00\xff"   # 4 colors
    return version + struct.pack("<HHBBB", w, h, 0x81, 0, 0) + gct


def frame(x, y, fw, fh, color):
    return b"\x2c" + struct.pack("<HHHHB", x, y, fw, fh, 0) + image_data([color] * (fw * fh))


def gce(delay=10):
    return b"\x21\xf9\x04\x04" + struct.pack("<H", delay) + b"\x00\x00"


def netscape(loops=0):
    return b"\x21\xff\x0bNETSCAPE2.0\x03\x01" + struct.pack("<H", loops) + b"\x00"


def comment(text):
    return b"\x21\xfe" + sub_blocks(text)


def app(ident, data):
    return b"\x21\xff\x0b" + ident + sub_blocks(data)


def plain_text():
    return b"\x21\x01\x0c" + bytes(12) + sub_blocks(b"hello")


XMP = app(b"XMP DataXMP", b"<x:xmpmeta><dc:creator>Someone</dc:creator><exif:GPSLatitude>37.7N</exif:GPSLatitude></x:xmpmeta>")
ICC = app(b"ICCRGBG1012", b"profile bytes, a device name")


def anim(n=2, w=4, h=4, extra_front=b"", extra_mid=b""):
    b = screen(w, h) + netscape() + extra_front
    for k in range(n):
        b += gce() + frame(0, 0, w, h, k % 4)
        if k == 0:
            b += extra_mid
    return b + b"\x3b"


def cases():
    plain = anim()
    good = [
        ("two frames, looping", plain, plain),
        ("comment, XMP and ICC blocks removed",
         screen(4, 4) + comment(b"made on my phone at home") + netscape() + XMP + gce() + frame(0, 0, 4, 4, 0) + ICC + gce() + frame(0, 0, 4, 4, 1)
         + comment(b"second") + b"\x3b", plain),
        ("loop block moved to the front, written in its usual form",
         screen(4, 4) + gce() + frame(0, 0, 4, 4, 0) + b"\x21\xff\x0bANIMEXTS1.0\x03\x01\x00\x00\x00" + gce() + frame(0, 0, 4, 4, 1) + b"\x3b", plain),
        ("a GIF87a still becomes GIF89a", screen(2, 2, b"GIF87a") + frame(0, 0, 2, 2, 3) + b"\x3b", screen(2, 2) + frame(0, 0, 2, 2, 3) + b"\x3b"),
        ("plain text block and its control block removed",
         screen(4, 4) + netscape() + gce(50) + plain_text() + gce() + frame(0, 0, 4, 4, 0) + gce() + frame(0, 0, 4, 4, 1) + b"\x3b", plain),
        ("a broken control block is dropped", screen(2, 2) + b"\x21\xf9\x03\x00\x00\x00\x00" + frame(0, 0, 2, 2, 1) + b"\x3b",
         screen(2, 2) + frame(0, 0, 2, 2, 1) + b"\x3b"),
        ("anything after the trailer is dropped", plain + b"GPS 37.7N trailing bytes", plain),
        ("no trailer: one is added", plain[:-1], plain),
        ("a smaller frame inside the screen", screen(4, 4) + frame(1, 1, 2, 3, 2) + b"\x3b", screen(4, 4) + frame(1, 1, 2, 3, 2) + b"\x3b"),
        ("400 frames", anim(400, 1, 1), anim(400, 1, 1)),
    ]
    refuse = [
        ("not a GIF", b"\xff\xd8\xff\xe0\x00\x10JFIF", "gif"),
        ("too short", b"GIF89a\x01\x00", "gif"),
        ("no frames", screen(4, 4) + netscape() + b"\x3b", "gif"),
        ("zero width", screen(0, 4) + frame(0, 0, 1, 1, 0) + b"\x3b", "gif"),
        ("wider than 2048", screen(2049, 4) + frame(0, 0, 1, 1, 0) + b"\x3b", "big"),
        ("401 frames", anim(401, 1, 1), "frames"),
        ("a frame outside the screen", screen(4, 4) + frame(2, 0, 4, 4, 0) + b"\x3b", "gif"),
        ("cut off inside a frame", plain[:60], "gif"),
        ("cut off inside a comment", screen(4, 4) + b"\x21\xfe\x20abc", "gif"),
        ("LZW code size 0", screen(2, 2) + b"\x2c" + struct.pack("<HHHHB", 0, 0, 2, 2, 0) + b"\x00\x01\x00\x00\x3b", "gif"),
        ("an unknown block", screen(2, 2) + b"\x99" + frame(0, 0, 2, 2, 0) + b"\x3b", "gif"),
        ("color table past the end", b"GIF89a\x02\x00\x02\x00\x87\x00\x00\x00\x00", "gif"),
    ]
    return good, refuse


def verify(v):
    for c in v["good"]:
        out, frames = clean(base64.b64decode(c["in"]))
        if out != base64.b64decode(c["out"]) or frames != c["frames"]:
            raise SystemExit("good %s" % c["name"])
        if b"XMP" in out or b"made on my phone" in out or b"GPS" in out or b"ICCRGBG" in out:
            raise SystemExit("metadata left in %s" % c["name"])
        if clean(out)[0] != out:
            raise SystemExit("cleaning twice changes %s" % c["name"])
    for c in v["refuse"]:
        try:
            clean(base64.b64decode(c["in"]))
        except Refused as e:
            if str(e) != c["why"]:
                raise SystemExit("refuse %s: %s" % (c["name"], e))
        else:
            raise SystemExit("refuse %s: passed" % c["name"])


def main():
    good, refuse = cases()
    v = {"note": "cap a1, code/shared/docs/protocol.md section 25; written by code/shared/tools/gif_ref.py",
         "limits": {"bytes": MAX_BYTES, "frames": MAX_FRAMES, "side": MAX_SIDE, "area": MAX_AREA},
         "good": [{"name": n, "in": base64.b64encode(i).decode(), "out": base64.b64encode(o).decode(), "frames": clean(i)[1]} for n, i, o in good],
         "refuse": [{"name": n, "in": base64.b64encode(i).decode(), "why": w} for n, i, w in refuse]}
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "gif-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "gif-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

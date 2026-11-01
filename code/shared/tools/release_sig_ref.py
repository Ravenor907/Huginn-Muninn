#!/usr/bin/env python3
"""Reference for the project release key (code/shared/docs/releases.md): Ed25519 signatures (RFC 8032) over the raw bytes
of each release file, and the test vectors both apps check (code/shared/vectors/release-sig-vectors.json). Run it to write the
vectors; it checks every case twice (building, then from the file).

The vectors hold the RFC's own test cases 1 to 3, one signature from a throwaway key over a longer file, and
cases that must be refused (a changed file, a changed signature, a wrong key, an over-large S, a key that is not
a point on the curve, a short signature). Neither app ever signs: CI signs with openssl in the release job.

Each release file also has <file>.vsig: the Ed25519 signature of
    UTF-8("huginn-muninn release v1" "\n" version "\n" file name "\n" lowercase hex SHA-256 of the file)
An app builds that text from the release tag it is installing (v0.97 -> "0.97"), the file's name and the SHA-256 of
what it downloaded, so an older signed file posted again under a newer tag doesn't check out. It installs only a
version newer than its own, by order() below (a stable version is newer than its own betas). A pre-release's
"version" is its label ("0.99 beta 12"). Vectors: "versioned" (signatures) and "newer" (which versions may be installed).

The release notes carry the signatures as one block (parse_notes below), so a release lists only its three downloads:
    huginn-muninn signatures v1
    label <version, or the pre-release's label>
    <file name> sha256 <lowercase hex> sig <base64 of .sig> vsig <base64 of .vsig>   (one line per download)
Vectors: "notes" (which texts give a block, and what it holds). Run with --check-notes FILE LABEL release-key.pem in the
folder holding the downloads to check that a release body's block parses and that every signature in it checks out.
"""
import base64, hashlib, json, os, re, sys

P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, P - 2, P) % P
SQRT_M1 = pow(2, (P - 1) // 4, P)


def sha512(b):
    return hashlib.sha512(b).digest()


def add(p, q):
    x1, y1, z1, t1 = p
    x2, y2, z2, t2 = q
    a = (y1 - x1) * (y2 - x2) % P
    b = (y1 + x1) * (y2 + x2) % P
    c = t1 * 2 * D * t2 % P
    d = z1 * 2 * z2 % P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % P, g * h % P, f * g % P, e * h % P)


def mul(s, p):
    q = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            q = add(q, p)
        p = add(p, p)
        s >>= 1
    return q


def equal(p, q):
    x1, y1, z1, _ = p
    x2, y2, z2, _ = q
    return (x1 * z2 - x2 * z1) % P == 0 and (y1 * z2 - y2 * z1) % P == 0


def recover_x(y, sign):
    if y >= P:
        return None
    x2 = (y * y - 1) * pow(D * y * y + 1, P - 2, P)
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (P + 3) // 8, P)
    if (x * x - x2) % P != 0:
        x = x * SQRT_M1 % P
    if (x * x - x2) % P != 0:
        return None
    if (x & 1) != sign:
        x = P - x
    return x


GY = 4 * pow(5, P - 2, P) % P
GX = recover_x(GY, 0)
G = (GX, GY, 1, GX * GY % P)


def compress(p):
    x, y, z, _ = p
    zi = pow(z, P - 2, P)
    x, y = x * zi % P, y * zi % P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def decompress(b):
    if len(b) != 32:
        return None
    y = int.from_bytes(b, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = recover_x(y, sign)
    return None if x is None else (x, y, 1, x * y % P)


def secret_expand(seed):
    h = sha512(seed)
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def public_key(seed):
    a, _ = secret_expand(seed)
    return compress(mul(a, G))


def sign(seed, msg):
    a, prefix = secret_expand(seed)
    pub = compress(mul(a, G))
    r = int.from_bytes(sha512(prefix + msg), "little") % L
    rs = compress(mul(r, G))
    h = int.from_bytes(sha512(rs + pub + msg), "little") % L
    return rs + int.to_bytes((r + h * a) % L, 32, "little")


def verify(pub, msg, sig):
    """RFC 8032 section 5.1.7: what both apps do (OpenSSL in Huginn, Ed25519.kt in Muninn)."""
    if len(pub) != 32 or len(sig) != 64:
        return False
    a = decompress(pub)
    r = decompress(sig[:32])
    if a is None or r is None:
        return False
    s = int.from_bytes(sig[32:], "little")
    if s >= L:
        return False
    h = int.from_bytes(sha512(sig[:32] + pub + msg), "little") % L
    return equal(mul(s, G), add(r, mul(h, a)))


# RFC 8032 section 7.1, tests 1 to 3: (secret seed, public key, message, signature).
RFC = [
    ("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
     "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
     "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
    ("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
     "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
     "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
    ("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
     "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
     "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
]


def flip(hexs, i):
    b = bytearray(bytes.fromhex(hexs))
    b[i] ^= 0x01
    return b.hex()


def cases():
    out = []
    for n, (seed, pub, msg, sig) in enumerate(RFC, 1):
        s = bytes.fromhex(seed)
        if public_key(s).hex() != pub or sign(s, bytes.fromhex(msg)).hex() != sig:
            raise SystemExit("RFC 8032 test %d doesn't match" % n)
        out.append({"name": "RFC 8032 test %d" % n, "public": pub, "message": msg, "signature": sig, "valid": True})
    # A throwaway key (not the project's) over a file longer than one hash block, as a release file is.
    seed = hashlib.sha256(b"Huginn and Muninn throwaway test key").digest()
    pub = public_key(seed).hex()
    msg, block = b"", b"release"
    while len(msg) < 3000:
        block = hashlib.sha256(block).digest()
        msg += block
    msg = msg[:3000].hex()
    sig = sign(seed, bytes.fromhex(msg)).hex()
    out.append({"name": "throwaway key, 3000-byte file", "public": pub, "message": msg, "signature": sig, "valid": True})
    s_big = int.from_bytes(bytes.fromhex(sig[64:]), "little") + L
    off_curve = next(y for y in range(2, 100) if recover_x(y, 0) is None)
    out += [
        {"name": "file changed", "public": pub, "message": flip(msg, 2999), "signature": sig, "valid": False},
        {"name": "signature R changed", "public": pub, "message": msg, "signature": flip(sig, 3), "valid": False},
        {"name": "signature S changed", "public": pub, "message": msg, "signature": flip(sig, 40), "valid": False},
        {"name": "S plus L (not reduced)", "public": pub, "message": msg,
         "signature": sig[:64] + int.to_bytes(s_big, 32, "little").hex(), "valid": False},
        {"name": "someone else's key", "public": RFC[1][1], "message": msg, "signature": sig, "valid": False},
        {"name": "key not on the curve", "public": int.to_bytes(off_curve, 32, "little").hex(), "message": msg,
         "signature": sig, "valid": False},
        {"name": "short signature", "public": pub, "message": msg, "signature": sig[:126], "valid": False},
        {"name": "RFC test 2 signature on test 3's message", "public": RFC[1][1], "message": RFC[2][2],
         "signature": RFC[1][3], "valid": False},
    ]
    return out


DOMAIN = "huginn-muninn release v1"


def version_message(version, name, sha_hex):
    return ("%s\n%s\n%s\n%s" % (DOMAIN, version, name, sha_hex)).encode("utf-8")


def order(v):
    """Where a version sorts. Versions have one dot and read as decimals (0.3 < 0.31 < 0.4 < 1.0). A pre-release
    "<version> beta <n>" sits below that version and above every version before it; betas of one version sort by n.
    Anything else sorts below everything."""
    import re
    m = re.match(r"^(\d+)\.(\d+)(?: beta (\d+))?$", v)
    if not m:
        return (-1, 0, 0)
    num = int(m.group(1)) * 1000000 + int((m.group(2) + "000000")[:6])
    return (num, 0, int(m.group(3))) if m.group(3) else (num, 1, 0)


def versioned():
    seed = hashlib.sha256(b"Huginn and Muninn throwaway release key").digest()
    pub = public_key(seed).hex()
    data = hashlib.sha256(b"Muninn.apk 0.96").digest() * 40
    sha = hashlib.sha256(data).hexdigest()
    sig = sign(seed, version_message("0.96", "Muninn.apk", sha)).hex()
    other = hashlib.sha256(data + b"!").hexdigest()

    def case(name, version, file, sha_hex, signature, valid, public=pub):
        return {"name": name, "public": public, "version": version, "file": file, "sha256": sha_hex,
                "message": version_message(version, file, sha_hex).hex(), "signature": signature, "valid": valid}
    return [
        case("signed for 0.96", "0.96", "Muninn.apk", sha, sig, True),
        case("the 0.96 file posted again as 0.97", "0.97", "Muninn.apk", sha, sig, False),
        case("the 0.96 file posted again as 0.9", "0.9", "Muninn.apk", sha, sig, False),
        case("another file name", "0.96", "Huginn.flatpak", sha, sig, False),
        case("another file", "0.96", "Muninn.apk", other, sig, False),
        case("signature changed", "0.96", "Muninn.apk", sha, flip(sig, 10), False),
        case("someone else's key", "0.96", "Muninn.apk", sha, sig, False, RFC[0][1]),
        case("Huginn's zip", "1.2", "Huginn-Windows.zip", other,
             sign(seed, version_message("1.2", "Huginn-Windows.zip", other)).hex(), True),
        case("signed for 0.99 beta 12", "0.99 beta 12", "Muninn.apk", other,
             sign(seed, version_message("0.99 beta 12", "Muninn.apk", other)).hex(), True),
        case("beta 12 posted again as beta 13", "0.99 beta 13", "Muninn.apk", other,
             sign(seed, version_message("0.99 beta 12", "Muninn.apk", other)).hex(), False),
    ]


def newer_cases():
    pairs = [("0.97", "0.96"), ("0.96", "0.96"), ("0.9", "0.96"), ("1.0", "0.99"),
             ("0.31", "0.3"), ("0.3", "0.31"), ("0.4", "0.39"),
             ("0.99 beta 3", "0.98"), ("0.99 beta 7", "0.99 beta 3"), ("0.99 beta 3", "0.99 beta 7"),
             ("0.99 beta 7", "0.99 beta 7"), ("0.99 beta 10", "0.99 beta 9"), ("0.99", "0.99 beta 7"),
             ("0.99 beta 12", "0.99"), ("0.98", "0.99 beta 3"), ("1.0 beta 1", "0.99")]
    return [{"tag": t, "current": c, "install": order(t) > order(c)} for t, c in pairs]


NOTES_FIRST = "huginn-muninn signatures v1"
NOTES_FILES = ("Huginn-Windows.zip", "Huginn.flatpak", "Muninn.apk")
NOTES_MAX_TEXT = 4 * 1024 * 1024   # the most of a release body or release page an app reads
NOTES_MAX_BLOCK = 2048             # the block itself, first line to its end
LABEL_LINE = re.compile(r"label ([0-9]{1,6}\.[0-9]{1,6}(?: beta [1-9][0-9]{0,5})?)")
FILE_LINE = re.compile(r"(Huginn-Windows\.zip|Huginn\.flatpak|Muninn\.apk) sha256 ([0-9a-f]{64})"
                       r" sig ([A-Za-z0-9+/]{86}==) vsig ([A-Za-z0-9+/]{86}==)")


def sig64(b64):
    """The 64 bytes of a signature in the block; None unless the base64 is the one canonical spelling of them."""
    raw = base64.b64decode(b64, validate=True)
    return raw if len(raw) == 64 and base64.b64encode(raw).decode() == b64 else None


def parse_notes(text, page):
    """The signatures block in a release body (page False: the API's markdown) or its release page (page True: GitHub's
    HTML), or None. The first line must start the text or a line (body) or follow a ">" (page: the start of the code
    element; an attribute or a page description never qualifies), and exactly once. The block ends at the first "<"
    or "```" after it, or at the end of the text. Every line must have one of the shapes above: the first line, then
    the label, then one to three downloads, each named once; empty lines only at the end; a "\r" before a "\n" is
    dropped. Returns {"label": label, "files": {name: (sha256 hex, sig bytes, vsig bytes)}}."""
    if len(text) > NOTES_MAX_TEXT:
        return None
    starts, i = [], text.find(NOTES_FIRST)
    while i >= 0:
        before = text[i - 1] if i > 0 else None
        if (before == ">") if page else (before is None or before == "\n"):
            starts.append(i)
        i = text.find(NOTES_FIRST, i + 1)
    if len(starts) != 1:
        return None
    s, end = starts[0], len(text)
    for t in ("<", "```"):
        j = text.find(t, s)
        if j >= 0:
            end = min(end, j)
    if end - s > NOTES_MAX_BLOCK:
        return None
    lines = [l[:-1] if l.endswith("\r") else l for l in text[s:end].split("\n")]
    while lines and lines[-1] == "":
        lines.pop()
    if not 3 <= len(lines) <= 2 + len(NOTES_FILES) or lines[0] != NOTES_FIRST:
        return None
    m = LABEL_LINE.fullmatch(lines[1])
    if not m:
        return None
    out = {"label": m.group(1), "files": {}}
    for line in lines[2:]:
        m = FILE_LINE.fullmatch(line)
        if not m or m.group(1) in out["files"]:
            return None
        sig, vsig = sig64(m.group(3)), sig64(m.group(4))
        if sig is None or vsig is None:
            return None
        out["files"][m.group(1)] = (m.group(2), sig, vsig)
    return out


def notes_block(label, files):
    """The block as the release job writes it: files is [(name, sha256 hex, sig bytes, vsig bytes)]."""
    lines = [NOTES_FIRST, "label " + label]
    for name, sha, sig, vsig in files:
        lines.append("%s sha256 %s sig %s vsig %s" % (name, sha, base64.b64encode(sig).decode(), base64.b64encode(vsig).decode()))
    return "\n".join(lines) + "\n"


def notes_cases():
    seed = hashlib.sha256(b"Huginn and Muninn throwaway release key").digest()
    pub = public_key(seed).hex()

    def signed(label, names):
        out = []
        for name in names:
            data = hashlib.sha256(("%s %s" % (name, label)).encode()).digest() * 5
            sha = hashlib.sha256(data).hexdigest()
            out.append((name, data, sha, sign(seed, data), sign(seed, version_message(label, name, sha))))
        return out

    beta = signed("1.2 beta 310", NOTES_FILES)
    stable = signed("1.3", NOTES_FILES)
    block = notes_block("1.2 beta 310", [(n, h, s, v) for n, _, h, s, v in beta])
    sblock = notes_block("1.3", [(n, h, s, v) for n, _, h, s, v in stable])
    human = "Pre-release 1.2 beta 310. Apps on the Pre-release channel install it by themselves.\n\n"
    body = human + "```\n" + block + "```\n"
    lines = block.rstrip("\n").split("\n")

    def page(b, before=""):
        # The shape of GitHub's release page: page descriptions and the copy button hold the text too, never after a ">".
        flat = b.replace("\n", "&#10;")
        return ('<meta name="description" content="%s%s" />\n<div class="markdown-body"><p>%s</p>\n'
                '<div class="snippet-clipboard-content" data-snippet-clipboard-copy-content="%s">'
                '<pre class="notranslate"><code class="notranslate">%s</code></pre></div>\n</div>'
                % (before, b, human.strip(), flat, b))

    def with_line(i, new):
        ls = list(lines)
        if new is None:
            del ls[i]
        else:
            ls[i] = new
        return human + "```\n" + "\n".join(ls) + "\n```\n"

    def case(name, text, is_page, valid, made=None, label=None, pad=0):
        # pad: that many spaces go after text (kept out of the file to keep it small).
        c = {"name": name, "page": is_page, "text": text, "pad": pad, "valid": valid}
        if valid:
            c["public"] = pub
            c["label"] = label
            c["files"] = [{"name": n, "data": d.hex(), "sha256": h, "sig": s.hex(), "vsig": v.hex()} for n, d, h, s, v in made]
        return c

    sig_b64 = base64.b64encode(beta[0][3]).decode()
    noncanon = sig_b64[:85] + chr(ord(sig_b64[85]) + 1) + "=="   # the same 64 bytes, other padding bits
    if base64.b64decode(noncanon) != beta[0][3] or noncanon == sig_b64:
        raise SystemExit("non-canonical base64 case")
    out = [
        case("beta body", body, False, True, beta, "1.2 beta 310"),
        case("beta body with CRLF", body.replace("\n", "\r\n"), False, True, beta, "1.2 beta 310"),
        case("stable body, block at the very start, no fence", sblock, False, True, stable, "1.3"),
        case("release page", page(block), True, True, beta, "1.2 beta 310"),
        case("release page, page description holds the whole block", page(block, "x\n"), True, True, beta, "1.2 beta 310"),
        case("one download", human + "```\n" + "\n".join(lines[:3]) + "\n```\n", False, True, beta[:1], "1.2 beta 310"),
        case("release page read as a body", page(block), False, False),
        case("body read as a release page", body, True, False),
        case("two blocks", body + "\n```\n" + block + "```\n", False, False),
        case("no block", human, False, False),
        case("first line inside a line", human + "see huginn-muninn signatures v1\n" + "\n".join(lines[1:]), False, False),
        case("first line changed", with_line(0, "huginn-muninn signatures v2"), False, False),
        case("label missing", with_line(1, None), False, False),
        case("label beta 0", with_line(1, "label 1.2 beta 0"), False, False),
        case("label with a v", with_line(1, "label v1.2"), False, False),
        case("label with a space at the end", with_line(1, "label 1.2 beta 310 "), False, False),
        case("no downloads", human + "```\n" + "\n".join(lines[:2]) + "\n```\n", False, False),
        case("unknown file", with_line(2, lines[2].replace("Huginn-Windows.zip", "Huginn-Linux.zip", 1)), False, False),
        case("same file twice", with_line(3, lines[2]), False, False),
        case("upper-case SHA-256", with_line(2, lines[2].replace(beta[0][2], beta[0][2].upper())), False, False),
        case("short SHA-256", with_line(2, lines[2].replace(beta[0][2], beta[0][2][:63])), False, False),
        case("non-canonical base64", with_line(2, lines[2].replace(sig_b64, noncanon)), False, False),
        case("unpadded base64", with_line(2, lines[2].replace(sig_b64, sig_b64[:86])), False, False),
        case("extra word", with_line(2, lines[2] + " extra"), False, False),
        case("two spaces", with_line(2, lines[2].replace(" sha256 ", "  sha256 ")), False, False),
        case("tab", with_line(2, lines[2].replace(" sig ", "\tsig ")), False, False),
        case("unknown line", with_line(4, lines[4] + "\nnote hello"), False, False),
        case("empty line inside", with_line(2, lines[2] + "\n"), False, False),
        case("indented", human + "```\n" + block.replace("\nlabel", "\n label") + "```\n", False, False),
        case("block too long", human + "```\n" + block + "\n" * 1500 + "```\n", False, False),
        case("just short enough", body, False, True, beta, "1.2 beta 310", NOTES_MAX_TEXT - len(body)),
        case("text too long", body, False, False, pad=NOTES_MAX_TEXT - len(body) + 1),
    ]
    return out


def check_notes(cases):
    for c in cases:
        got = parse_notes(c["text"] + " " * c["pad"], c["page"])
        if (got is not None) != c["valid"]:
            raise SystemExit("notes %r: got %s" % (c["name"], got))
        if got is None:
            continue
        if got["label"] != c["label"] or list(got["files"]) != [f["name"] for f in c["files"]]:
            raise SystemExit("notes %r: label or files" % c["name"])
        pub = bytes.fromhex(c["public"])
        for f in c["files"]:
            sha, sig, vsig = got["files"][f["name"]]
            data = bytes.fromhex(f["data"])
            if (sha, sig.hex(), vsig.hex()) != (f["sha256"], f["sig"], f["vsig"]) or hashlib.sha256(data).hexdigest() != sha \
                    or not verify(pub, data, sig) or not verify(pub, version_message(c["label"], f["name"], sha), vsig):
                raise SystemExit("notes %r: %s" % (c["name"], f["name"]))


def check_release_notes(path, label, pem):
    """CI: the body the release job is about to publish holds a block for all three downloads, under label, and
    every SHA-256 and signature in it checks out against the files here and the project key (release-key.pem)."""
    with open(path, encoding="utf-8") as f:
        got = parse_notes(f.read(), False)
    if got is None or got["label"] != label or sorted(got["files"]) != sorted(NOTES_FILES):
        raise SystemExit("::error title=Signatures block::%s has no whole signatures block for %s." % (path, label))
    with open(pem, encoding="utf-8") as f:
        pub = base64.b64decode("".join(l.strip() for l in f.read().splitlines() if not l.startswith("-----")))[-32:]
    for name, (sha, sig, vsig) in got["files"].items():
        with open(name, "rb") as f:
            data = f.read()
        if hashlib.sha256(data).hexdigest() != sha or not verify(pub, data, sig) \
                or not verify(pub, version_message(label, name, sha), vsig):
            raise SystemExit("::error title=Signatures block::The block's line for %s doesn't check out." % name)
    print("The signatures block for %s checks out." % label)


def check(v):
    for c in v["cases"]:
        got = verify(bytes.fromhex(c["public"]), bytes.fromhex(c["message"]), bytes.fromhex(c["signature"]))
        if got != c["valid"]:
            raise SystemExit("case %r: got %s" % (c["name"], got))
    if v["domain"] != DOMAIN:
        raise SystemExit("domain")
    for c in v["versioned"]:
        msg = version_message(c["version"], c["file"], c["sha256"])
        if msg.hex() != c["message"]:
            raise SystemExit("versioned %r: message" % c["name"])
        got = verify(bytes.fromhex(c["public"]), msg, bytes.fromhex(c["signature"]))
        if got != c["valid"]:
            raise SystemExit("versioned %r: got %s" % (c["name"], got))
    for c in v["newer"]:
        if (order(c["tag"]) > order(c["current"])) != c["install"]:
            raise SystemExit("newer %r" % c)
    check_notes(v["notes"])


def main():
    v = {"note": "Ed25519 release signatures (RFC 8032), code/shared/docs/releases.md; written by code/shared/tools/release_sig_ref.py",
         "cases": cases(), "domain": DOMAIN, "versioned": versioned(), "newer": newer_cases(),
         "notes_first": NOTES_FIRST, "notes": notes_cases()}
    check(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "release-sig-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "release-sig-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            check(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "--check-notes":
        check_release_notes(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        main()

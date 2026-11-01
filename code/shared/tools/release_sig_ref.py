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
"""
import hashlib, json, os

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


def main():
    v = {"note": "Ed25519 release signatures (RFC 8032), code/shared/docs/releases.md; written by code/shared/tools/release_sig_ref.py",
         "cases": cases(), "domain": DOMAIN, "versioned": versioned(), "newer": newer_cases()}
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
    main()

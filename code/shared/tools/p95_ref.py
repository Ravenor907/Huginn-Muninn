#!/usr/bin/env python3
"""Reference for caps x1, j1 and k6 (code/shared/docs/protocol.md, sections 7.11, 17 and 18), and the test vectors both
apps check (code/shared/vectors/p95-vectors.json). Run it to write the vectors; it checks every case twice (building, then from the file).

 - x1 (18): moving to another device. The key from the two one-off keys and the secret in the QR, the 4 emoji, the hello
   and status checks, a sealed part, the sealed link code, and the payload's layout.
 - j1 (17): a link's id, whether a link still lets someone in, and "mi" in a group state.
 - k6 (7.11): which Kids' TV reconnect messages are well-formed, and when the new phone takes over (24 hours, nobody blocked).
"""
import base64, hashlib, hmac, json, os, struct

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import i1f1_ref
import proto06_ref

MOVE_PREFIX = "MUNINNM1:"
MOVE_INFO = b"huginn-muninn move v1"
PART = 512 * 1024


def b64(b): return base64.b64encode(b).decode()
def b64d(s): return base64.b64decode(s)
def b64url(b): return base64.urlsafe_b64encode(b).decode().rstrip("=")
def b64url_d(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def key_pair(scalar):
    priv = ec.derive_private_key(scalar, ec.SECP256R1())
    return priv, priv.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


# ------------------------------------------------------------------ x1: moving

def move_key(my_priv, their_pub_der, secret, new_pub_der, old_pub_der):
    """K = SHA-256(info || ECDH x || secret || SHA-256(new one-off key) || SHA-256(old one-off key)). The same on both sides."""
    z = my_priv.exchange(ec.ECDH(), serialization.load_der_public_key(their_pub_der))
    return hashlib.sha256(MOVE_INFO + z + secret + hashlib.sha256(new_pub_der).digest() + hashlib.sha256(old_pub_der).digest()).digest()


def mac(key, text): return b64(hmac.new(key, text.encode(), hashlib.sha256).digest())


def hello_mac(key, old_pub_b64): return mac(key, "hello|" + old_pub_b64)
def ready_mac(key, old_pub_b64): return mac(key, "ready|" + old_pub_b64)
def status_mac(key, state, h): return mac(key, "status|" + state + "|" + h)


def seal(key, nonce, plain, aad): return b64(nonce + AESGCM(key).encrypt(nonce, plain, aad.encode()))


def open_sealed(key, d, aad):
    raw = b64d(d)
    if len(raw) < 12 + 16: raise ValueError("short")
    return AESGCM(key).decrypt(raw[:12], raw[12:], aad.encode())


def part_aad(i, n, h): return "move|%d|%d|%s" % (i, n, h)
def parts(size): return max(1, (size + PART - 1) // PART)


def pack(entries):
    """The payload: each entry as [uint32 BE name length][name, UTF-8][uint32 BE data length][data], one after another."""
    out = b""
    for name, data in entries:
        n = name.encode()
        out += struct.pack(">I", len(n)) + n + struct.pack(">I", len(data)) + data
    return out


def unpack(blob):
    at, out = 0, []
    while at < len(blob):
        if at + 4 > len(blob): raise ValueError("cut")
        (ln,) = struct.unpack(">I", blob[at:at + 4]); at += 4
        name = blob[at:at + ln].decode(); at += ln
        if at + 4 > len(blob): raise ValueError("cut")
        (ld,) = struct.unpack(">I", blob[at:at + 4]); at += 4
        if at + ld > len(blob): raise ValueError("cut")
        out.append((name, blob[at:at + ld])); at += ld
    return out


def lan_ip(ip):
    """An IPv4 literal on a home network: 10/8, 172.16/12, 192.168/16 or link-local 169.254/16. Never a name."""
    p = ip.split(".")
    if len(p) != 4 or any(not x.isdigit() or len(x) > 3 or int(x) > 255 for x in p): return False
    a, b = int(p[0]), int(p[1])
    return a == 10 or (a == 172 and 16 <= b <= 31) or (a == 192 and b == 168) or (a == 169 and b == 254)


def lan_addr(addr):
    ip, _, port = addr.rpartition(":")
    return lan_ip(ip) and port.isdigit() and 1 <= int(port) <= 65535


def same_network(ip, mine, prefix):
    if not lan_ip(ip) or not lan_ip(mine) or not 8 <= prefix <= 32: return False
    n = lambda s: int.from_bytes(bytes(int(x) for x in s.split(".")), "big")
    mask = (0xFFFFFFFF << (32 - prefix)) & 0xFFFFFFFF
    return n(ip) & mask == n(mine) & mask


def move_code(o): return MOVE_PREFIX + b64url(json.dumps(o, separators=(",", ":")).encode())


def read_move_code(text):
    i = text.find(MOVE_PREFIX)
    if i < 0: return None
    body = ""
    for c in text[i + len(MOVE_PREFIX):]:
        if not (c.isalnum() or c in "-_"): break
        body += c
    o = json.loads(b64url_d(body))
    if o.get("v") != 1 or len(b64url_d(o["s"])) != 16 or not o.get("a") or o.get("app") not in ("muninn", "huginn"): return None
    serialization.load_der_public_key(b64d(o["e"]))
    return o


# ------------------------------------------------------------------ j1: links

def link_id(token): return hashlib.sha256(("link|" + token).encode()).hexdigest()[:16]


def link_ok(x, mx, uses, off, maker_in, maker_may, now):
    return (not off) and (x == 0 or x > now) and (mx == 0 or uses < mx) and maker_in and maker_may


# ------------------------------------------------------------------ k6: Kids' TV reconnect

WAIT_MS = 24 * 3_600_000


def tv_ok(o):
    def key(f): return isinstance(o.get(f), str) and 0 < len(o[f]) <= 200 and len(b64d(o[f])) > 0
    def name(f): return isinstance(o.get(f), str) and o[f].strip() != "" and len(o[f]) <= 40
    try:
        t, keys = o["t"], set(o)
        if t == "rejoin": return keys == {"t", "n", "r"} and name("n") and isinstance(o["r"], str) and 16 <= len(o["r"]) <= 64
        if t == "ask": return keys == {"t", "k", "n"} and key("k") and name("n")
        if t in ("approve", "block"): return keys == {"t", "k"} and key("k")
        if t == "swap": return keys == {"t", "k", "n", "p"} and key("k") and name("n") and isinstance(o["p"], str) and len(o["p"]) in (0, 64)
        if t == "orphan": return keys == {"t", "cfg"} and isinstance(o["cfg"], dict)
        if t == "blocked": return keys == {"t"}
        return False
    except Exception:
        return False


def takeover_due(at, now, blocked): return (not blocked) and now - at >= WAIT_MS


# ------------------------------------------------------------------ vectors

def build():
    new_priv, new_pub = key_pair(0x5001)
    old_priv, old_pub = key_pair(0x6001)
    secret = b"\x77" * 16
    k_new = move_key(new_priv, old_pub, secret, new_pub, old_pub)
    k_old = move_key(old_priv, new_pub, secret, new_pub, old_pub)
    assert k_new == k_old
    k = k_new
    code_obj = {"v": 1, "a": ["192.168.1.20:53317"], "e": b64(new_pub), "s": b64url(secret), "n": "Tablet", "app": "muninn"}
    entries = [("contacts.json", b'[{"k":"x"}]'), ("@move.json", b'{"v":1}')]
    blob = pack(entries)
    h = hashlib.sha256(blob).hexdigest()
    nonce = b"\x09" * 12
    n = parts(len(blob))
    return {
        "about": "code/shared/docs/protocol.md 7.11, 17 and 18. Written by code/shared/tools/p95_ref.py.",
        "move": {
            "newScalar": "5001", "oldScalar": "6001", "newPub": b64(new_pub), "oldPub": b64(old_pub),
            "newPriv": b64(new_priv.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())),
            "oldPriv": b64(old_priv.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())),
            "secret": b64url(secret), "key": k.hex(), "emoji": i1f1_ref.emoji_code(k),
            "code": move_code(code_obj), "codeFields": code_obj,
            "helloMac": hello_mac(k, b64(old_pub)), "readyMac": ready_mac(k, b64(old_pub)),
            "statusDone": status_mac(k, "done", h),
            "payload": b64(blob), "entries": [[a, b64(b)] for a, b in entries], "sha": h, "parts": n, "partSize": PART,
            "nonce": b64(nonce), "part0": seal(k, nonce, blob, part_aad(0, n, h)), "aad0": part_aad(0, n, h),
            "link": seal(k, nonce, b"MUNINNL1:abc", "move|link"), "linkText": "MUNINNL1:abc",
            "partCounts": [[0, 1], [1, 1], [PART, 1], [PART + 1, 2], [3 * PART, 3]],
            # Addresses a move may go to: home networks only, never a name or a public address (18.1).
            "lan": [[a, lan_addr(a)] for a in ("192.168.1.20:53317", "10.0.0.5:53317", "172.16.4.1:53317", "172.31.255.1:1", "169.254.3.9:53317",
                                                "172.32.0.1:53317", "8.8.8.8:53317", "100.64.0.1:53317", "evil.example:53317", "192.168.1.20",
                                                "192.168.1.256:53317", "192.168.1.20:70000", "::1:53317")],
            "sameNetwork": [[a, b, n, same_network(a, b, n)] for a, b, n in (("192.168.1.20", "192.168.1.7", 24, ), ("192.168.2.20", "192.168.1.7", 24),
                                                                              ("10.1.2.3", "10.9.9.9", 8), ("192.168.1.20", "192.168.1.7", 4))],
        },
        "links": {
            "ids": [[t, link_id(t)] for t in ("AAAAAAAAAAAAAAAAAAAAAA", "q83vEjRWeJAAAAAAAAAAAA")],
            # x, max, uses, off, makerIn, makerMay, now, ok
            "ok": [[c[0], c[1], c[2], c[3], c[4], c[5], c[6], link_ok(*c)] for c in (
                (0, 0, 999, False, True, True, 5000),          # never, no limit
                (6000, 5, 4, False, True, True, 5000),         # one use left
                (6000, 5, 5, False, True, True, 5000),         # used up
                (5000, 0, 0, False, True, True, 5000),         # expired at that moment
                (0, 1, 0, True, True, True, 5000),             # revoked
                (0, 1, 0, False, False, True, 5000),           # its maker left
                (0, 1, 0, False, True, False, 5000),           # a member's link after "Members can invite" went off
            )],
        },
        "tv": {
            "good": [{"t": "rejoin", "n": "Mom", "r": "0123456789abcdef0123456789abcdef"}, {"t": "ask", "k": "QUJD", "n": "Mom"},
                     {"t": "approve", "k": "QUJD"}, {"t": "block", "k": "QUJD"}, {"t": "swap", "k": "QUJD", "n": "Mom", "p": ""},
                     {"t": "orphan", "cfg": {"v": 1}}, {"t": "blocked"}],
            "refuse": [{"t": "rejoin", "n": "Mom"}, {"t": "rejoin", "n": "", "r": "0123456789abcdef"}, {"t": "rejoin", "n": "Mom", "r": "short"},
                       {"t": "ask", "k": "QUJD"}, {"t": "approve", "k": "QUJD", "x": 1}, {"t": "here", "k": "QUJD"},
                       {"t": "swap", "k": "QUJD", "n": "Mom", "p": "abc"}, {"t": "swap", "k": "QUJD", "n": "Mom", "p": "", "gone": []},
                       {"t": "orphan", "cfg": "x"}, {"t": "other"}],
            # asked at, now, blocked, takes over
            "due": [[0, WAIT_MS - 1, False, False], [0, WAIT_MS, False, True], [0, 3 * WAIT_MS, True, False]],
        },
    }


def verify(v):
    m = v["move"]
    new_priv = serialization.load_der_private_key(b64d(m["newPriv"]), None)
    old_priv = serialization.load_der_private_key(b64d(m["oldPriv"]), None)
    new_pub, old_pub = b64d(m["newPub"]), b64d(m["oldPub"])
    secret = b64url_d(m["secret"])
    k = move_key(new_priv, old_pub, secret, new_pub, old_pub)
    assert k.hex() == m["key"] and move_key(old_priv, new_pub, secret, new_pub, old_pub) == k
    assert i1f1_ref.emoji_code(k) == m["emoji"]
    assert read_move_code(m["code"]) == m["codeFields"]
    assert hello_mac(k, m["oldPub"]) == m["helloMac"] and ready_mac(k, m["oldPub"]) == m["readyMac"]
    blob = b64d(m["payload"])
    assert [[a, b64(b)] for a, b in unpack(blob)] == m["entries"] and pack([(a, b64d(b)) for a, b in m["entries"]]) == blob
    assert hashlib.sha256(blob).hexdigest() == m["sha"] and status_mac(k, "done", m["sha"]) == m["statusDone"]
    assert seal(k, b64d(m["nonce"]), blob, m["aad0"]) == m["part0"] and open_sealed(k, m["part0"], m["aad0"]) == blob
    try:
        open_sealed(k, m["part0"], part_aad(1, m["parts"], m["sha"])); raise AssertionError("wrong place opened")
    except Exception as e:
        assert not isinstance(e, AssertionError)
    assert open_sealed(k, m["link"], "move|link").decode() == m["linkText"]
    for size, n in m["partCounts"]: assert parts(size) == n
    for a, ok in m["lan"]: assert lan_addr(a) == ok, a
    for a, b, n, ok in m["sameNetwork"]: assert same_network(a, b, n) == ok
    for t, i in v["links"]["ids"]: assert link_id(t) == i
    for c in v["links"]["ok"]: assert link_ok(*c[:7]) == c[7]
    # "mi" in a group state: only ever true (17.1).
    s = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "vectors", "proto06-vectors.json")))["group"]["states"]["s1"]["state"]
    proto06_ref.check_state(dict(s, mi=True))
    for bad in (False, 1, "yes"):
        try:
            proto06_ref.check_state(dict(s, mi=bad)); raise AssertionError("mi %r taken" % (bad,))
        except ValueError:
            pass
    for o in v["tv"]["good"]: assert tv_ok(o), o
    for o in v["tv"]["refuse"]: assert not tv_ok(o), o
    for at, now, blocked, due in v["tv"]["due"]: assert takeover_due(at, now, blocked) == due
    return True


def main():
    v = build()
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "p95-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "p95-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Reference for caps i1 and f1 and linked devices (code/shared/docs/protocol.md, sections 13, 14 and 15), and the test
vectors both apps check (code/shared/vectors/i1f1-vectors.json). Run it to write the vectors; it checks every case twice
(building, then from the file).

 - i1: what an intro, yes or add looks like, the id of a group-member request, and the 4 emoji for a pair.
 - f1: the transfer key from the two one-off keys, sealed UDP packets, which pieces an ack names, and what an
   offer or accept must look like.
 - Linked devices: a MUNINNL1: link code and what it carries.
"""
import base64, hashlib, json, os

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

# ChatCrypto.EMOJI: the order is fixed (13.5).
EMOJI = ["🦊", "🌙", "⚓", "🎈", "🐢", "🍎", "🚲", "🔑", "🌵", "🐝", "🎸", "🍄", "🚀", "🐙", "🌻", "🧲",
         "🐘", "🍋", "⛵", "🔔", "🦉", "🍇", "🚂", "🎩", "🐬", "🌽", "🏀", "🪁", "🦋", "🍒", "🛶", "📚",
         "🐧", "🥕", "🏰", "🎺", "🐳", "🍉", "🚁", "🧩", "🦒", "🥨", "🗿", "🎯", "🐞", "🍓", "🛸", "🧭",
         "🐿️", "🍍", "🏕️", "🥁", "🦀", "🌶️", "🚜", "🪐", "🦓", "🍩", "⛺", "🎲", "🐌", "🥑", "🗻", "🧸"]

CHUNK = 1100
WINDOW = 96
MAX_BYTES = 4 * 1024 ** 3
LINK_PREFIX = "MUNINNL1:"
PROBE, DATA, ACK, DONE = 1, 2, 3, 4


class Refused(ValueError):
    pass


def b64(b): return base64.b64encode(b).decode()


def key_pair(scalar):
    priv = ec.derive_private_key(scalar, ec.SECP256R1())
    pub = priv.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    der = priv.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return priv, der, pub


def shared_key(priv, my_pub, their_pub):
    """ChatCrypto.sharedKey: ECDH, salt = SHA-256 of both public keys sorted as unsigned bytes."""
    theirs = serialization.load_der_public_key(their_pub)
    secret = priv.exchange(ec.ECDH(), theirs)
    a, b = sorted([my_pub, their_pub])
    return HKDF(hashes.SHA256(), 32, hashlib.sha256(a + b).digest(), b"huginn-muninn chat v1").derive(secret)


# ------------------------------------------------------------------ i1 (section 13)

def emoji_code(key):
    d = hashlib.sha256("emoji code".encode() + key).digest()
    return " ".join(EMOJI[d[i] % len(EMOJI)] for i in range(4))


def group_id(g, a, b):
    k = sorted([a, b])
    return hashlib.sha256(("add|%s|%s|%s" % (g, k[0], k[1])).encode()).hexdigest()[:32]


def is_logical_id(s): return isinstance(s, str) and len(s) == 32 and all(c in "0123456789abcdef" for c in s)


def is_mailbox(s): return isinstance(s, str) and len(s) == 64 and all(c in "0123456789abcdef" for c in s)


def key_ok(k):
    if not isinstance(k, str) or not 40 <= len(k) <= 200: return False
    try:
        serialization.load_der_public_key(base64.b64decode(k, validate=True))
        return True
    except Exception:
        return False


def intro_ok(p):
    t = p.get("t")
    if t == "intro":
        n = p.get("n")
        return (is_logical_id(p.get("id")) and key_ok(p.get("k")) and isinstance(n, str) and n.strip() != "" and len(n) <= 40
                and all(ord(c) >= 0x20 for c in n) and isinstance(p.get("p"), str) and (p["p"] == "" or is_mailbox(p["p"])))
    if t == "yes": return is_logical_id(p.get("id"))
    if t == "add": return isinstance(p.get("g"), str) and 1 <= len(p["g"]) <= 40
    return False


# ------------------------------------------------------------------ f1 (section 14)

def file_key(shared, ident): return hashlib.sha256("muninn file v1".encode() + shared + ident.encode()).digest()


def nonce(direction, seq): return direction.to_bytes(4, "big") + seq.to_bytes(8, "big")


def seal(key, ident, typ, direction, seq, payload):
    head = bytes([typ])
    return head + seq.to_bytes(8, "big") + AESGCM(key).encrypt(nonce(direction, seq), payload, head + ident.encode())


def open_packet(key, ident, direction, p):
    if len(p) < 9 + 16: return None
    try:
        return p[0], AESGCM(key).decrypt(nonce(direction, int.from_bytes(p[1:9], "big")), p[9:], p[:1] + ident.encode())
    except Exception:
        return None


def ack_of(have, n):
    below = 0
    while below < n and below in have: below += 1
    bits = 0
    for b in range(64):
        if below + b < n and below + b in have: bits |= 1 << b
    return below, bits


def pieces(size): return max(1, (size + CHUNK - 1) // CHUNK)


def file_ok(p):
    if not is_logical_id(p.get("id")): return False
    t = p.get("t")
    if t == "offer":
        name, size, sha = p.get("name"), p.get("size"), p.get("sha")
        return (isinstance(name, str) and name.strip() != "" and len(name) <= 200 and all(ord(c) >= 0x20 and c not in "/\\" for c in name)
                and isinstance(size, int) and not isinstance(size, bool) and 1 <= size <= MAX_BYTES
                and isinstance(sha, str) and len(sha) == 64 and all(c in "0123456789abcdef" for c in sha)
                and isinstance(p.get("e"), str) and isinstance(p.get("c"), list))
    if t == "accept": return isinstance(p.get("e"), str) and isinstance(p.get("c"), list)
    return t in ("decline", "done")


# ------------------------------------------------------------------ linked devices (section 15)

def link_code(k, p, n, i):
    j = json.dumps({"k": k, "p": p, "n": n, "i": i}, separators=(",", ":"), ensure_ascii=False)
    return LINK_PREFIX + base64.urlsafe_b64encode(j.encode()).decode().rstrip("=")


def read_link(code):
    at = code.find(LINK_PREFIX)
    if at < 0: raise Refused("no prefix")
    body = ""
    for c in code[at + len(LINK_PREFIX):]:
        if not (c.isalnum() or c in "-_"): break
        body += c
    try:
        o = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
    except Exception:
        raise Refused("damaged")
    if not key_ok(o.get("k")): raise Refused("bad key")
    return {"k": o["k"], "p": o.get("p", ""), "n": (o.get("n") or "")[:40], "i": o.get("i", "")}


# ------------------------------------------------------------------ vectors

def build():
    a_priv, a_der, a_pub = key_pair(0x1F2E3D4C5B6A79881726354453627180)
    b_priv, b_der, b_pub = key_pair(0x0A1B2C3D4E5F60718293A4B5C6D7E8F9)
    pair = shared_key(a_priv, a_pub, b_pub)
    assert pair == shared_key(b_priv, b_pub, a_pub)
    ka, kb = b64(a_pub), b64(b_pub)
    ident = "0123456789abcdef0123456789abcdef"
    fk = file_key(pair, ident)
    mailbox = "ab" * 32

    emoji = [{"keyHex": pair.hex(), "emoji": emoji_code(pair)}]
    for i in range(6):
        k = hashlib.sha256(b"emoji %d" % i).digest()
        emoji.append({"keyHex": k.hex(), "emoji": emoji_code(k)})

    groups = [{"g": "AAECAwQFBgcICQoLDA0ODw", "a": ka, "b": kb, "id": group_id("AAECAwQFBgcICQoLDA0ODw", ka, kb)},
              {"g": "AAECAwQFBgcICQoLDA0ODw", "a": kb, "b": ka, "id": group_id("AAECAwQFBgcICQoLDA0ODw", ka, kb)}]

    intro_good = [{"t": "intro", "id": ident, "k": kb, "n": "Astrid", "p": mailbox},
                  {"t": "intro", "id": ident, "k": kb, "n": "Bjorn", "p": ""},
                  {"t": "yes", "id": ident},
                  {"t": "add", "g": "AAECAwQFBgcICQoLDA0ODw"}]
    intro_bad = [{"t": "intro", "id": "x", "k": kb, "n": "Astrid", "p": ""},
                 {"t": "intro", "id": ident, "k": "not a key at all, not a key at all, nope", "n": "Astrid", "p": ""},
                 {"t": "intro", "id": ident, "k": kb, "n": "", "p": ""},
                 {"t": "intro", "id": ident, "k": kb, "n": "A" * 41, "p": ""},
                 {"t": "intro", "id": ident, "k": kb, "n": "Line\nbreak", "p": ""},
                 {"t": "intro", "id": ident, "k": kb, "n": "Astrid", "p": "short"},
                 {"t": "yes", "id": "0123"},
                 {"t": "add", "g": ""},
                 {"t": "add", "g": "g" * 41},
                 {"t": "hello"}]

    packets = []
    for typ, direction, seq, payload in [(PROBE, 0, 1, b"\x01"), (DATA, 0, 1000, (7).to_bytes(4, "big") + b"piece seven"),
                                         (ACK, 1, 1001, (3).to_bytes(4, "big") + (0b1010).to_bytes(8, "big")), (DONE, 1, 1002, b"")]:
        p = seal(fk, ident, typ, direction, seq, payload)
        packets.append({"type": typ, "dir": direction, "seq": seq, "payloadHex": payload.hex(), "packetHex": p.hex()})

    acks = [{"n": 100, "have": [0, 1, 2, 4, 6], "below": 3, "bits": 0b1010},
            {"n": 5, "have": [0, 1, 2, 3, 4], "below": 5, "bits": 0},
            {"n": 200, "have": [1, 64], "below": 0, "bits": 2 ** 1}]
    for c in acks: assert ack_of(set(c["have"]), c["n"]) == (c["below"], c["bits"]), c

    offer = {"t": "offer", "id": ident, "name": "notes.pdf", "size": 5, "sha": "a" * 64, "e": ka, "c": []}
    file_good = [offer, dict(offer, size=MAX_BYTES), {"t": "accept", "id": ident, "e": kb, "c": [{"t": "srflx", "a": "203.0.113.7", "p": 51820}]},
                 {"t": "decline", "id": ident}]
    file_bad = [dict(offer, name="../a"), dict(offer, name="a\\b"), dict(offer, name=""), dict(offer, name="x" * 201),
                dict(offer, size=0), dict(offer, size=MAX_BYTES + 1), dict(offer, sha="A" * 64), dict(offer, id="x"),
                {"t": "accept", "id": ident, "e": kb}, {"t": "nope", "id": ident}]

    link = {"k": ka, "p": mailbox, "n": "Kitchen tablet", "i": "00112233445566778899aabbccddeeff"}
    code = link_code(**link)
    links = {"good": [{"code": code, "read": read_link(code)},
                      {"code": "Link this: " + code + " (10 minutes)", "read": read_link(code)}],
             "bad": ["MUNINNL1:", "MUNINNL1:!!!", link_code("nope", "", "x", "y"), "MUNINN1:" + code[len(LINK_PREFIX):]]}

    return {"note": "caps i1 and f1 and linked devices, code/shared/docs/protocol.md sections 13 to 15; written by code/shared/tools/i1f1_ref.py",
            "limits": {"chunk": CHUNK, "window": WINDOW, "maxBytes": MAX_BYTES},
            "pair": {"aPriv": b64(a_der), "aPub": ka, "bPub": kb, "sharedHex": pair.hex()},
            "emoji": emoji, "groupId": groups,
            "intro": {"good": intro_good, "bad": intro_bad},
            "file": {"id": ident, "keyHex": fk.hex(), "pieces": [[1, 1], [1100, 1], [1101, 2], [2201, 3]],
                     "packets": packets, "acks": acks, "good": file_good, "bad": file_bad},
            "link": links}


def verify(v):
    pr = v["pair"]
    priv = serialization.load_der_private_key(base64.b64decode(pr["aPriv"]), None)
    a_pub, b_pub = base64.b64decode(pr["aPub"]), base64.b64decode(pr["bPub"])
    pair = shared_key(priv, a_pub, b_pub)
    if pair.hex() != pr["sharedHex"]: raise SystemExit("shared key")
    for c in v["emoji"]:
        if emoji_code(bytes.fromhex(c["keyHex"])) != c["emoji"]: raise SystemExit("emoji %r" % c)
    for c in v["groupId"]:
        if group_id(c["g"], c["a"], c["b"]) != c["id"]: raise SystemExit("group id %r" % c)
    for p in v["intro"]["good"]:
        if not intro_ok(p): raise SystemExit("intro should pass %r" % p)
    for p in v["intro"]["bad"]:
        if intro_ok(p): raise SystemExit("intro should fail %r" % p)
    f = v["file"]
    key = file_key(pair, f["id"])
    if key.hex() != f["keyHex"]: raise SystemExit("file key")
    for size, n in f["pieces"]:
        if pieces(size) != n: raise SystemExit("pieces %d" % size)
    for c in f["packets"]:
        p = bytes.fromhex(c["packetHex"])
        if seal(key, f["id"], c["type"], c["dir"], c["seq"], bytes.fromhex(c["payloadHex"])) != p: raise SystemExit("seal %r" % c)
        if open_packet(key, f["id"], c["dir"], p) != (c["type"], bytes.fromhex(c["payloadHex"])): raise SystemExit("open %r" % c)
        if open_packet(key, f["id"], 1 - c["dir"], p) is not None: raise SystemExit("wrong direction opened")
    for c in f["acks"]:
        if ack_of(set(c["have"]), c["n"]) != (c["below"], c["bits"]): raise SystemExit("ack %r" % c)
    for p in f["good"]:
        if not file_ok(p): raise SystemExit("file should pass %r" % p)
    for p in f["bad"]:
        if file_ok(p): raise SystemExit("file should fail %r" % p)
    for c in v["link"]["good"]:
        if read_link(c["code"]) != c["read"]: raise SystemExit("link %r" % c)
    for code in v["link"]["bad"]:
        try:
            read_link(code)
        except Refused:
            continue
        raise SystemExit("link should fail %r" % code)


def main():
    v = build()
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "i1f1-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "i1f1-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

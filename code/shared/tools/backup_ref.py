#!/usr/bin/env python3
"""Reference for the encrypted backup file (code/shared/docs/backup.md), the same in Huginn and Muninn.

  "MUNB1" | uint32 BE header length | header JSON | 12-byte nonce | AES-256-GCM ciphertext | 16-byte tag

v3 (what both apps write): scrypt N=65536 r=8 p=2 makes the key from the password; the GCM additional data is every
byte before the nonce, so a changed header is refused like a changed body. Inside: [uint32 name length][name]
[uint32 data length][data], one file after another, with "@about.json" and "@move.json" among them.
v2 (scrypt, no additional data) and v1 (PBKDF2-SHA256) are older and still restore in the app that made them.

Writes code/shared/vectors/backup-vectors.json and the Android test copy. Salts and nonces are fixed so the file
stays the same; the apps use random ones.
"""
import base64
import hashlib
import json
import os
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"MUNB1"
PASSWORD = "correct horse battery"
WRONG = "wrong horse battery"
CREATED = "2026-11-02T10:00:00Z"


def b64(b):
    return base64.b64encode(b).decode()


def key(h, password):
    salt = base64.b64decode(h["salt"])
    if h["v"] == 1:
        return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, h["rounds"], 32)
    return hashlib.scrypt(password.encode(), salt=salt, n=h["N"], r=h["r"], p=h["p"], maxmem=1 << 28, dklen=32)


def pack(files):
    out = b""
    for name, data in files:
        n = name.encode()
        out += struct.pack(">I", len(n)) + n + struct.pack(">I", len(data)) + data
    return out


def unpack(plain):
    out, at = [], 0
    while at < len(plain):
        (n,) = struct.unpack(">I", plain[at:at + 4])
        name = plain[at + 4:at + 4 + n].decode()
        at += 4 + n
        (d,) = struct.unpack(">I", plain[at:at + 4])
        out.append((name, plain[at + 4:at + 4 + d]))
        at += 4 + d
    assert at == len(plain)
    return out


def write(v, app, files, salt, nonce, password=PASSWORD):
    if v == 1:
        h = {"v": 1, "salt": b64(salt), "rounds": 100000, "created": CREATED, "app": app}
    elif v == 2:
        h = {"v": 2, "salt": b64(salt), "kdf": "scrypt", "N": 65536, "r": 8, "p": 1, "created": CREATED, "app": app}
    else:
        h = {"v": 3, "salt": b64(salt), "kdf": "scrypt", "N": 65536, "r": 8, "p": 2, "created": CREATED, "app": app}
    hb = json.dumps(h, separators=(",", ":")).encode()
    head = MAGIC + struct.pack(">I", len(hb)) + hb
    sealed = AESGCM(key(h, password)).encrypt(nonce, pack(files), head if v >= 3 else None)
    return head + nonce + sealed


def read(b, password):
    assert b[:5] == MAGIC
    (n,) = struct.unpack(">I", b[5:9])
    h = json.loads(b[9:9 + n])
    at = 9 + n
    return h, unpack(AESGCM(key(h, password)).decrypt(b[at:at + 12], b[at + 12:], b[:at] if h["v"] >= 3 else None))


def shared_form():
    """A small "@move.json" (protocol.md 18.4): 2 contacts, 3 chats, 1 group, 1 folder."""
    return {"v": 1, "me": {"name": "Raven", "pub": "", "priv": "", "nostr": ""},
            "contacts": [{"k": "a", "n": "Ann"}, {"k": "b", "n": "Bo"}], "ratchets": {},
            "chats": {"a": [], "b": [], "c": []}, "groups": [{"s": {}, "h": []}], "gchats": {},
            "folders": {"folders": [{"id": "f1", "n": "Family", "k": ["a"], "c": False}], "top": []}}


def files_for(app, device):
    about = {"v": 1, "app": app, "device": device, "created": CREATED, "files": True}
    return [("chats.json", b'{"a":[{"text":"hi"}]}'), ("photos/p1", bytes(range(256)) * 3),
            ("@about.json", json.dumps(about, separators=(",", ":")).encode()),
            ("@move.json", json.dumps(shared_form(), separators=(",", ":")).encode())]


def entry(v, app, device, salt_byte, nonce_byte):
    files = files_for(app, device) if v >= 3 else [("chats.json", b'{"old":true}'), ("contacts.json", b"[]")]
    b = write(v, app, files, bytes([salt_byte]) * 16, bytes([nonce_byte]) * 12)
    return {"v": v, "app": app, "file": b64(b), "files": [{"name": n, "data": b64(d)} for n, d in files]}


def verify(v):
    for k in ("v3huginn", "v3muninn", "v2huginn", "v1muninn"):
        e = v[k]
        b = base64.b64decode(e["file"])
        h, got = read(b, v["password"])
        assert h["v"] == e["v"] and h["app"] == e["app"]
        assert [(n, b64(d)) for n, d in got] == [(f["name"], f["data"]) for f in e["files"]]
        for bad in (lambda: read(b, v["wrong"]),
                    lambda: read(b[:-20] + bytes([b[-20] ^ 1]) + b[-19:], v["password"])):
            try:
                bad()
                raise SystemExit(k + ": a wrong password or a changed body opened")
            except Exception as ex:
                if isinstance(ex, SystemExit):
                    raise
        if e["v"] >= 3:
            # A changed header (the date) is refused too.
            i = b.index(b"2026-11-02")
            try:
                read(b[:i] + b"2027" + b[i + 4:], v["password"])
                raise SystemExit(k + ": a changed header opened")
            except SystemExit:
                raise
            except Exception:
                pass


def main():
    v = {
        "password": PASSWORD,
        "wrong": WRONG,
        "summary": {"created": CREATED, "contacts": 2, "chats": 3, "groups": 1, "folders": 1, "items": 2, "files": True},
        "v3huginn": entry(3, "huginn", "Test PC", 1, 2),
        "v3muninn": entry(3, "muninn", "Test phone", 3, 4),
        "v2huginn": entry(2, "huginn", "", 5, 6),
        "v1muninn": entry(1, "muninn", "", 7, 8),
    }
    v["v3huginn"]["device"] = "Test PC"
    v["v3muninn"]["device"] = "Test phone"
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "vectors", "backup-vectors.json"),
                 os.path.join(here, "..", "..", "muninn", "app", "src", "test", "resources", "backup-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()

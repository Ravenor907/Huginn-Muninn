#!/usr/bin/env python3
"""Reference for the Huginn/Muninn message ratchet (forward secrecy), and the test vectors both apps
are checked against (code/shared/vectors/ratchet-vectors.json). Run it to regenerate the vectors.

Spec:
  Keys: P-256. Public keys travel as base64 SubjectPublicKeyInfo DER, like the chat keys.
  SK: the existing pair key, ChatCrypto.sharedKey(my static, their static).
  KDF_RK(rk, dh) = HKDF-SHA256(salt=rk, ikm=dh, info="huginn-muninn ratchet", 64) -> (rk', ck)
  KDF_CK(ck)     = (HMAC-SHA256(ck, 0x01) as mk, HMAC-SHA256(ck, 0x02) as ck')
  Message key    = HKDF-SHA256(salt=32 zero bytes, ikm=mk, info="huginn-muninn message", 44)
                   -> AES-256-GCM key (32) and nonce (12)
  Header         = {"b": base, "d": dh, "pn": pn, "n": n}; AAD = "v1|b|d|pn|n" in UTF-8
  Alice (first to send): eph = new key; (rk, cks) = KDF_RK(SK, DH(eph, their static)); base = eph.
  Bob (first message with an unknown base, where d == b): dhs = my static, rk = SK, then a DH step.
  DH step on a new "d": pn = ns; ns = nr = 0; dhr = d; (rk, ckr) = KDF_RK(rk, DH(dhs, dhr));
                        dhs = new key; (rk, cks) = KDF_RK(rk, DH(dhs, dhr)).
  Skipped message keys are kept (at most 1000 per session) for messages that arrive out of order.
"""
import base64, hashlib, hmac, json, os, sys
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_SKIP = 1000

def b64(b): return base64.b64encode(b).decode()
def pub_der(k): return k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
def load_pub(b): return serialization.load_der_public_key(base64.b64decode(b))
def priv_from_int(n): return ec.derive_private_key(n, ec.SECP256R1())
def dh(priv, pub_b64): return priv.exchange(ec.ECDH(), load_pub(pub_b64))
def hkdf(salt, ikm, info, n): return HKDF(hashes.SHA256(), n, salt, info.encode()).derive(ikm)

def shared_key(my_priv, my_pub_der, their_pub_b64):
    """ChatCrypto.sharedKey: ECDH, salt = SHA-256 of both public keys sorted as unsigned bytes."""
    secret = dh(my_priv, their_pub_b64)
    a, b = sorted([my_pub_der, base64.b64decode(their_pub_b64)])
    return hkdf(hashlib.sha256(a + b).digest(), secret, "huginn-muninn chat v1", 32)

def kdf_rk(rk, d):
    out = hkdf(rk, d, "huginn-muninn ratchet", 64)
    return out[:32], out[32:]

def kdf_ck(ck):
    return hmac.new(ck, b"\x01", hashlib.sha256).digest(), hmac.new(ck, b"\x02", hashlib.sha256).digest()

def aad(h): return f"v1|{h['b']}|{h['d']}|{h['pn']}|{h['n']}".encode()

def seal(mk, h, plain):
    k = hkdf(b"\x00" * 32, mk, "huginn-muninn message", 44)
    return AESGCM(k[:32]).encrypt(k[32:], plain, aad(h))

def unseal(mk, h, c):
    k = hkdf(b"\x00" * 32, mk, "huginn-muninn message", 44)
    return AESGCM(k[:32]).decrypt(k[32:], c, aad(h))

class Session:
    def __init__(self): self.skipped = {}

    @staticmethod
    def alice(sk, their_static_b64, eph):
        s = Session()
        s.dhs = eph; s.dhr = their_static_b64; s.base = b64(pub_der(eph))
        s.rk, s.cks = kdf_rk(sk, dh(eph, their_static_b64))
        s.ckr = None; s.ns = s.nr = s.pn = 0
        return s

    @staticmethod
    def bob(sk, my_static, base):
        s = Session()
        s.dhs = my_static; s.dhr = None; s.base = base; s.rk = sk
        s.cks = s.ckr = None; s.ns = s.nr = s.pn = 0
        return s

    def encrypt(self, plain):
        mk, self.cks = kdf_ck(self.cks)
        h = {"b": self.base, "d": b64(pub_der(self.dhs)), "pn": self.pn, "n": self.ns}
        self.ns += 1
        return h, seal(mk, h, plain)

    def _skip(self, until):
        if self.ckr is None: return
        if until - self.nr > MAX_SKIP: raise ValueError("too many skipped")
        while self.nr < until:
            mk, self.ckr = kdf_ck(self.ckr)
            self.skipped[(self.dhr, self.nr)] = mk
            self.nr += 1
        while len(self.skipped) > MAX_SKIP: self.skipped.pop(next(iter(self.skipped)))

    def decrypt(self, h, c, new_key):
        """new_key() makes the next ratchet key (fixed keys in the vectors). Caller keeps a copy and
        commits the new state only if this returns."""
        k = (h["d"], h["n"])
        if k in self.skipped:
            return unseal(self.skipped.pop(k), h, c)
        if h["d"] != self.dhr:
            self._skip(h["pn"])
            self.pn = self.ns; self.ns = 0; self.nr = 0; self.dhr = h["d"]
            self.rk, self.ckr = kdf_rk(self.rk, dh(self.dhs, self.dhr))
            self.dhs = new_key()
            self.rk, self.cks = kdf_rk(self.rk, dh(self.dhs, self.dhr))
        self._skip(h["n"])
        mk, self.ckr = kdf_ck(self.ckr)
        self.nr += 1
        return unseal(mk, h, c)

def main():
    v = json.load(open(os.path.join(os.path.dirname(__file__), "..", "vectors", "chat-vectors.json")))
    a_priv = serialization.load_der_private_key(base64.b64decode(v["privA"]), None)
    b_priv = serialization.load_der_private_key(base64.b64decode(v["privB"]), None)
    a_pub, b_pub = v["pubA"], v["pubB"]
    sk_a = shared_key(a_priv, base64.b64decode(a_pub), b_pub)
    sk_b = shared_key(b_priv, base64.b64decode(b_pub), a_pub)
    assert sk_a == sk_b and sk_a.hex() == v["key"]
    # Fixed "random" ratchet keys so both apps can reproduce every byte.
    a_keys = [0x1000 + i for i in range(1, 20)]
    b_keys = [0x2000 + i for i in range(1, 20)]
    a_next = lambda: priv_from_int(a_keys.pop(0))
    b_next = lambda: priv_from_int(b_keys.pop(0))
    alice = Session.alice(sk_a, b_pub, a_next())
    bob = None
    def deliver_to_bob(step):
        nonlocal bob
        h = step["header"]
        if bob is None:
            assert h["d"] == h["b"]
            bob = Session.bob(sk_b, b_priv, h["b"])
        p = bob.decrypt(h, base64.b64decode(step["c"]), b_next)
        assert p.decode() == step["text"], (p, step["text"])
    def deliver_to_alice(step):
        p = alice.decrypt(step["header"], base64.b64decode(step["c"]), a_next)
        assert p.decode() == step["text"]
    # Re-run the whole conversation in delivery order, producing the real wire messages.
    steps = []
    def send(who, text):
        s = alice if who == "A" else bob
        h, c = s.encrypt(text.encode())
        st = {"from": who, "text": text, "header": h, "c": b64(c)}
        steps.append(st)
        return st
    m0 = send("A", "hello 1"); m1 = send("A", "hello 2")
    deliver_to_bob(m0); deliver_to_bob(m1)
    m2 = send("B", "reply 1"); deliver_to_alice(m2)
    m3 = send("A", "after reply"); m4 = send("A", "late one"); m5 = send("A", "on time")
    deliver_to_bob(m3); deliver_to_bob(m5); deliver_to_bob(m4)   # m4 arrives after m5
    m6 = send("B", "reply 2"); m7 = send("B", "reply 3")
    deliver_to_alice(m7); deliver_to_alice(m6)                   # and the other way round
    m8 = send("A", "ᚺᚢᚷᛁᚾᚾ unicode ok"); deliver_to_bob(m8)
    order = [["B", 0], ["B", 1], ["A", 2], ["B", 3], ["B", 5], ["B", 4], ["A", 7], ["A", 6], ["B", 8]]
    vectors = {
        "about": "Ratchet test vectors. Static keys and SK are those of chat-vectors.json. Ratchet keys are P-256 private keys "
                 "with these integer values, used in order: Alice 0x1001, 0x1002, ...; Bob 0x2001, 0x2002, .... "
                 "Alice is A (pubA), Bob is B (pubB). 'messages' are in the order they were encrypted; 'deliver' says who "
                 "decrypts which message, in that order (out-of-order delivery included). Every header and ciphertext must "
                 "match exactly, and every decryption must give 'text'.",
        "pubA": a_pub, "pubB": b_pub, "sk": sk_a.hex(),
        "aliceKeys": "0x1001+", "bobKeys": "0x2001+",
        "messages": steps, "deliver": order,
    }
    path = os.path.join(os.path.dirname(__file__), "..", "vectors", "ratchet-vectors.json")
    json.dump(vectors, open(path, "w"), indent=2, ensure_ascii=False)
    print("wrote", os.path.normpath(path), len(steps), "messages; every delivery decrypted")

if __name__ == "__main__":
    main()

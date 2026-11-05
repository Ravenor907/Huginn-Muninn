package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Message ratchet (Double Ratchet style) so old messages stay safe if a key leaks later.
 * Matches shared/tools/ratchet_ref.py byte for byte; shared/vectors/ratchet-vectors.json checks it.
 */
object Ratchet {
    const val MAX_SKIP = 1000
    private const val INFO_RK = "huginn-muninn ratchet"
    private const val INFO_MSG = "huginn-muninn message"

    /**
     * What travels in the clear next to each message. b = session base, d = sender's ratchet key.
     * [bind]: cap s1, the envelope's bound clear fields ([Proof.bound]), sealed in too; never sent as part of the header.
     */
    data class Header(val b: String, val d: String, val pn: Int, val n: Int, val bind: String = "") {
        fun aad(): ByteArray = "v1|$b|$d|$pn|$n".toByteArray(Charsets.UTF_8).let { if (bind.isEmpty()) it else Proof.ratchetAad(it, bind) }
        fun toJson(): JSONObject = JSONObject().put("b", b).put("d", d).put("pn", pn).put("n", n)

        companion object {
            fun fromJson(o: JSONObject) = Header(o.getString("b"), o.getString("d"), o.getInt("pn"), o.getInt("n"))
        }
    }

    class Session private constructor(
        val base: String,
        var rk: ByteArray,
        var dhs: KeyPair,
        var dhr: String?,
        var cks: ByteArray?,
        var ckr: ByteArray?,
        var ns: Int,
        var nr: Int,
        var pn: Int,
        // Key is "dh|n"; insertion order so the oldest goes first when full.
        val skipped: LinkedHashMap<String, ByteArray>,
    ) {
        fun copy() = Session(base, rk.copyOf(), dhs, dhr, cks?.copyOf(), ckr?.copyOf(), ns, nr, pn,
            LinkedHashMap(skipped.mapValues { it.value.copyOf() }))

        private fun takeFrom(o: Session) {
            rk = o.rk; dhs = o.dhs; dhr = o.dhr; cks = o.cks; ckr = o.ckr
            ns = o.ns; nr = o.nr; pn = o.pn
            skipped.clear(); skipped.putAll(o.skipped)
        }

        fun encrypt(plain: ByteArray, bind: String = ""): Pair<Header, ByteArray> {
            val ck = cks ?: throw IllegalStateException("no sending chain yet")
            val (mk, next) = kdfCk(ck)
            cks = next
            val h = Header(base, pubB64(dhs.public), pn, ns, bind)
            ns++
            return h to seal(mk, h, plain)
        }

        /**
         * Null on any failure (bad tag, too many skipped, bad key), and then nothing changes.
         * newKey makes the next ratchet key pair.
         */
        fun decrypt(h: Header, cipher: ByteArray, newKey: () -> KeyPair): ByteArray? {
            if (h.b != base) return null
            val s = copy()
            val plain = try {
                s.step(h, cipher, newKey)
            } catch (e: Exception) {
                null
            } ?: return null
            takeFrom(s)
            return plain
        }

        // Works on this object directly: only ever called on a copy.
        private fun step(h: Header, cipher: ByteArray, newKey: () -> KeyPair): ByteArray? {
            val k = "${h.d}|${h.n}"
            skipped.remove(k)?.let { return unseal(it, h, cipher) }
            if (h.d != dhr) {
                skip(h.pn)
                pn = ns; ns = 0; nr = 0; dhr = h.d
                kdfRk(rk, dh(dhs.private, h.d)).let { rk = it.first; ckr = it.second }
                dhs = newKey()
                kdfRk(rk, dh(dhs.private, h.d)).let { rk = it.first; cks = it.second }
            }
            skip(h.n)
            val (mk, next) = kdfCk(ckr ?: return null)
            ckr = next
            nr++
            return unseal(mk, h, cipher)
        }

        private fun skip(until: Int) {
            var ck = ckr ?: return
            if (until - nr > MAX_SKIP) throw IllegalStateException("too many skipped")
            while (nr < until) {
                val (mk, next) = kdfCk(ck)
                ck = next
                skipped["$dhr|$nr"] = mk
                nr++
            }
            ckr = ck
            while (skipped.size > MAX_SKIP) skipped.remove(skipped.keys.first())
        }

        fun toJson(): JSONObject {
            val sk = JSONArray()
            for ((k, v) in skipped) {
                val cut = k.lastIndexOf('|')
                sk.put(JSONObject().put("d", k.substring(0, cut)).put("n", k.substring(cut + 1).toInt()).put("mk", b64(v)))
            }
            return JSONObject()
                .put("base", base).put("rk", b64(rk))
                .put("dhsPriv", b64(dhs.private.encoded)).put("dhsPub", pubB64(dhs.public))
                .put("dhr", dhr ?: JSONObject.NULL)
                .put("cks", cks?.let { b64(it) } ?: JSONObject.NULL)
                .put("ckr", ckr?.let { b64(it) } ?: JSONObject.NULL)
                .put("ns", ns).put("nr", nr).put("pn", pn).put("skipped", sk)
        }

        companion object {
            /** First to send: a fresh ephemeral key, which also names the session. */
            fun alice(sk: ByteArray, theirStaticB64: String, eph: KeyPair): Session {
                val (rk, cks) = kdfRk(sk, dh(eph.private, theirStaticB64))
                return Session(pubB64(eph.public), rk, eph, theirStaticB64, cks, null, 0, 0, 0, LinkedHashMap())
            }

            /** Answering a new session: start from my static key; the DH step happens on decrypt. */
            fun bob(sk: ByteArray, myStatic: KeyPair, base: String): Session =
                Session(base, sk.copyOf(), myStatic, null, null, null, 0, 0, 0, LinkedHashMap())

            fun fromJson(o: JSONObject): Session {
                val skipped = LinkedHashMap<String, ByteArray>()
                val arr = o.optJSONArray("skipped") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    skipped["${e.getString("d")}|${e.getInt("n")}"] = b64d(e.getString("mk"))
                }
                return Session(
                    o.getString("base"), b64d(o.getString("rk")),
                    KeyPair(publicFrom(o.getString("dhsPub")), privateFrom(o.getString("dhsPriv"))),
                    optStr(o, "dhr"), optStr(o, "cks")?.let { b64d(it) }, optStr(o, "ckr")?.let { b64d(it) },
                    o.getInt("ns"), o.getInt("nr"), o.getInt("pn"), skipped,
                )
            }
        }
    }

    /**
     * All sessions with one contact. Keeps the 3 most recently used. If both sides start at once,
     * each ends up with two working sessions; whichever last decrypted becomes active.
     */
    class Peer {
        // Access order: least recently used first.
        private val sessions = LinkedHashMap<String, Session>(8, 0.75f, true)
        var active: String? = null
            private set

        fun session(base: String): Session? = sessions[base]
        val bases: List<String> get() = sessions.keys.toList()

        fun encrypt(plain: ByteArray, sk: ByteArray, theirStaticB64: String, newKey: () -> KeyPair, bind: String = ""): Pair<Header, ByteArray> {
            val s = active?.let { sessions[it] }?.takeIf { it.cks != null }
                ?: Session.alice(sk, theirStaticB64, newKey()).also { put(it); active = it.base }
            return s.encrypt(plain, bind)
        }

        fun decrypt(h: Header, cipher: ByteArray, sk: ByteArray, myStatic: KeyPair, newKey: () -> KeyPair): ByteArray? {
            val known = sessions[h.b]
            val s = known ?: if (h.d == h.b) Session.bob(sk, myStatic, h.b) else return null
            val plain = s.decrypt(h, cipher, newKey) ?: return null
            if (known == null) put(s)
            active = s.base
            return plain
        }

        private fun put(s: Session) {
            sessions[s.base] = s
            while (sessions.size > 3) {
                val oldest = sessions.keys.first()
                sessions.remove(oldest)
                if (active == oldest) active = null
            }
        }

        fun toJson(): JSONObject {
            val arr = JSONArray()
            for (s in sessions.values.toList()) arr.put(s.toJson())
            return JSONObject().put("active", active ?: JSONObject.NULL).put("sessions", arr)
        }

        companion object {
            fun fromJson(o: JSONObject): Peer {
                val p = Peer()
                val arr = o.getJSONArray("sessions")
                for (i in 0 until arr.length()) Session.fromJson(arr.getJSONObject(i)).let { p.sessions[it.base] = it }
                p.active = optStr(o, "active")?.takeIf { p.sessions.containsKey(it) }
                return p
            }
        }
    }

    // Crypto helpers. HKDF here matches ChatCrypto's (which is private there).

    fun pubB64(k: PublicKey): String = b64(k.encoded)
    private fun b64(b: ByteArray) = ChatCrypto.b64(b)
    private fun b64d(s: String) = ChatCrypto.b64d(s)
    private fun publicFrom(s: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64d(s)))
    private fun privateFrom(s: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b64d(s)))
    private fun optStr(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.getString(k)

    private fun dh(mine: PrivateKey, theirB64: String): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mine)
        ka.doPhase(publicFrom(theirB64), true)
        return ka.generateSecret()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun kdfRk(rk: ByteArray, dh: ByteArray): Pair<ByteArray, ByteArray> {
        val o = hkdf(rk, dh, INFO_RK, 64)
        return o.copyOfRange(0, 32) to o.copyOfRange(32, 64)
    }

    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> = hmac(ck, byteArrayOf(1)) to hmac(ck, byteArrayOf(2))

    private fun gcm(mode: Int, mk: ByteArray, h: Header): Cipher {
        val k = hkdf(ByteArray(32), mk, INFO_MSG, 44)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(k.copyOfRange(0, 32), "AES"), GCMParameterSpec(128, k.copyOfRange(32, 44)))
            updateAAD(h.aad())
        }
    }

    private fun seal(mk: ByteArray, h: Header, plain: ByteArray): ByteArray = gcm(Cipher.ENCRYPT_MODE, mk, h).doFinal(plain)
    private fun unseal(mk: ByteArray, h: Header, c: ByteArray): ByteArray = gcm(Cipher.DECRYPT_MODE, mk, h).doFinal(c)
}

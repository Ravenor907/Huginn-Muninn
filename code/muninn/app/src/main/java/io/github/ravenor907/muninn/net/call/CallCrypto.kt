package io.github.ravenor907.muninn.net.call

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Proto06
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The deterministic parts of 1:1 calls (shared/docs/protocol.md, 5.2 to 5.4), matching shared/tools/proto06_ref.py
 * byte for byte: signed offer and answer, call keys per direction and epoch, media packets and the
 * replay window. No Android here, so all of it runs in unit tests (shared/vectors/proto06-vectors.json, "call").
 */
object CallCrypto {
    const val D_CALL = "huginn-muninn call v1"
    const val EPOCH_BITS = 14
    const val REPLAY_WINDOW = 1024
    const val MAX_EPOCH_AHEAD = 2
    const val VERSION = 0x81
    const val HEADER_LEN = 12
    const val TAG_LEN = 16
    const val MAX_PACKET = 1200
    const val AUDIO = 0
    const val PING = 1
    const val PONG = 2
    // 3 to 6 only between two c2 apps (shared/docs/protocol.md 5.8). An app without c2 refuses them as "type".
    const val VIDEO = 3
    const val REPORT = 4
    const val KEY_REQUEST = 5
    const val NACK = 6
    const val CODEC = "opus16"
    private val random = SecureRandom()

    // ------------------------------------------------------------------ ids and keys

    fun newCallId(): String = Proto06.b64url(ByteArray(16).also { random.nextBytes(it) })
    fun isCallId(s: String) = s.length == 22 && try { Proto06.b64urlD(s).size == 16 } catch (e: Exception) { false }

    /** A fresh P-256 key pair for one call. The private half lives only in memory. */
    fun newEphemeral(): KeyPair = ChatCrypto.newKeyPair()

    fun tag(callId: String): ByteArray =
        sha256("huginn-muninn call tag".toByteArray(Charsets.UTF_8) + Proto06.b64urlD(callId)).copyOf(2)

    fun ecdh(mine: PrivateKey, theirB64: String): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(mine); doPhase(ChatCrypto.publicFrom(theirB64), true); generateSecret() }

    fun root(mine: PrivateKey, theirEph: String, callId: String, offerEph: String, answerEph: String): ByteArray {
        val ikm = ecdh(mine, theirEph)
        val salt = sha256(Proto06.b64urlD(callId) + ChatCrypto.b64d(offerEph) + ChatCrypto.b64d(answerEph))
        return hkdf(salt, ikm, D_CALL.toByteArray(Charsets.UTF_8), 32).also { ikm.fill(0) }
    }

    fun nextChain(c: ByteArray): ByteArray = hmac256(c, byteArrayOf(2))

    class Epoch(val o2aKey: ByteArray, val o2aSalt: ByteArray, val a2oKey: ByteArray, val a2oSalt: ByteArray) {
        fun key(o2a: Boolean) = if (o2a) o2aKey else a2oKey
        fun salt(o2a: Boolean) = if (o2a) o2aSalt else a2oSalt
        fun wipe() { o2aKey.fill(0); o2aSalt.fill(0); a2oKey.fill(0); a2oSalt.fill(0) }
    }

    fun epochFrom(chain: ByteArray): Epoch {
        val km = hkdf(ByteArray(32), chain, "huginn-muninn call keys".toByteArray(Charsets.UTF_8), 72)
        return Epoch(km.copyOfRange(0, 32), km.copyOfRange(32, 36), km.copyOfRange(36, 68), km.copyOfRange(68, 72)).also { km.fill(0) }
    }

    /**
     * The key schedule of one call, shared by sending and receiving. Epochs are derived on demand from the
     * oldest chain value still kept; [forgetBefore] deletes older keys and chain values (5.3) so a phone
     * captured mid-call can't decrypt earlier minutes.
     */
    class Keys(root: ByteArray) {
        private var baseEpoch = 0
        private var baseChain = root.copyOf()
        private val epochs = HashMap<Int, Epoch>()

        @Synchronized fun epoch(e: Int): Epoch? {
            if (e < baseEpoch) return null
            epochs[e]?.let { return it }
            var c = baseChain.copyOf()
            for (i in baseEpoch until e) { val n = nextChain(c); c.fill(0); c = n }
            return epochFrom(c).also { c.fill(0); epochs[e] = it }
        }

        /** The chain value of epoch [e] (tests only compare it; never leaves the object otherwise). */
        @Synchronized fun chain(e: Int): ByteArray? {
            if (e < baseEpoch) return null
            var c = baseChain.copyOf()
            for (i in baseEpoch until e) { val n = nextChain(c); c.fill(0); c = n }
            return c
        }

        @Synchronized fun forgetBefore(e: Int) {
            if (e <= baseEpoch) return
            var c = baseChain
            for (i in baseEpoch until e) { val n = nextChain(c); c.fill(0); c = n }
            baseChain = c; baseEpoch = e
            epochs.keys.filter { it < e }.forEach { epochs.remove(it)?.wipe() }
        }

        @Synchronized fun oldest() = baseEpoch

        @Synchronized fun wipe() { baseChain.fill(0); epochs.values.forEach { it.wipe() }; epochs.clear(); baseEpoch = Int.MAX_VALUE }
    }

    // ------------------------------------------------------------------ signed offer and answer (5.2)

    /** The canonical text that is signed: exactly {"t", "call", "eph"}. */
    fun signedPart(t: String, callId: String, eph: String) = Proto06.canon(mapOf("t" to t, "call" to callId, "eph" to eph))

    fun sign(chatKey: PrivateKey, t: String, callId: String, eph: String) = Proto06.sign(chatKey, D_CALL, signedPart(t, callId, eph))

    fun verify(fromKey: String, t: String, callId: String, eph: String, sig: String) =
        try { Proto06.verify(fromKey, D_CALL, signedPart(t, callId, eph), sig) } catch (e: Exception) { false }

    // ------------------------------------------------------------------ media packets (5.4)

    fun epochOf(seq: Long) = (seq ushr EPOCH_BITS).toInt()

    private fun nonce(salt: ByteArray, seq: Long) = ByteBuffer.allocate(12).put(salt).putInt(0).putInt(seq.toInt()).array()

    fun header(type: Int, tag: ByteArray, seq: Long, ts: Long): ByteArray =
        ByteBuffer.allocate(HEADER_LEN).put(VERSION.toByte()).put(type.toByte()).put(tag, 0, 2).putInt(seq.toInt()).putInt(ts.toInt()).array()

    /** Seals one packet for direction [o2a] (offerer to answerer). Throws if it would be over 1200 bytes. */
    fun seal(keys: Keys, o2a: Boolean, tag: ByteArray, type: Int, seq: Long, ts: Long, payload: ByteArray): ByteArray {
        require(HEADER_LEN + payload.size + TAG_LEN <= MAX_PACKET) { "packet too big" }
        val e = keys.epoch(epochOf(seq)) ?: throw IllegalStateException("epoch forgotten")
        val h = header(type, tag, seq, ts)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(e.key(o2a), "AES"), GCMParameterSpec(128, nonce(e.salt(o2a), seq)))
        c.updateAAD(h)
        return h + c.doFinal(payload)
    }

    class Opened(val type: Int, val seq: Long, val ts: Long, val payload: ByteArray)

    /** Why a packet was dropped; the words match the reference's reasons. */
    class Dropped(val why: String) : Exception(why)

    /** Packet type and tag without decrypting, or null if it isn't a media packet of a known type. */
    fun peekTag(p: ByteArray): Int? =
        if (p.size >= HEADER_LEN + TAG_LEN && p.size <= MAX_PACKET && (p[0].toInt() and 0xFF) == VERSION && p[1].toInt() in AUDIO..NACK)
            ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF) else null

    /**
     * One direction of one call (reference Receiver.open). [o2a] is the direction of the packets it opens.
     * Every copy of a packet, on whichever path it came, goes through the same receiver: that is the dedupe.
     */
    class Receiver(private val keys: Keys, private val o2a: Boolean, private val tag: ByteArray) {
        /** Highest seq accepted so far, -1 before the first. */
        @Volatile var high = -1L; private set
        // slot[seq % 1024] holds the seq accepted there; any other value in the window means "not seen".
        private val slot = LongArray(REPLAY_WINDOW) { -1L }

        @Synchronized fun open(p: ByteArray): Opened {
            if (p.size < HEADER_LEN + TAG_LEN || p.size > MAX_PACKET) throw Dropped("size")
            val b = ByteBuffer.wrap(p)
            val ver = b.get().toInt() and 0xFF
            val type = b.get().toInt() and 0xFF
            val t0 = b.get(); val t1 = b.get()
            val seq = b.int.toLong() and 0xFFFFFFFFL
            val ts = b.int.toLong() and 0xFFFFFFFFL
            if (ver != VERSION) throw Dropped("version")
            if (type !in AUDIO..NACK) throw Dropped("type")
            if (t0 != tag[0] || t1 != tag[1]) throw Dropped("other call")
            if (high >= 0 && seq + REPLAY_WINDOW <= high) throw Dropped("too old")
            if (seq <= high && slot[(seq % REPLAY_WINDOW).toInt()] == seq) throw Dropped("duplicate")
            if (epochOf(seq) > epochOf(maxOf(high, 0)) + MAX_EPOCH_AHEAD) throw Dropped("too far ahead")
            val e = keys.epoch(epochOf(seq)) ?: throw Dropped("too old")
            val plain = try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(e.key(o2a), "AES"), GCMParameterSpec(128, nonce(e.salt(o2a), seq)))
                c.updateAAD(p, 0, HEADER_LEN)
                c.doFinal(p, HEADER_LEN, p.size - HEADER_LEN)
            } catch (x: Exception) { throw Dropped("not authentic") }
            // Only an authentic packet moves the window.
            slot[(seq % REPLAY_WINDOW).toInt()] = seq
            if (seq > high) high = seq
            return Opened(type, seq, ts, plain)
        }

        /** The oldest epoch this receiver can still need: the one holding the bottom of its window. */
        fun neededEpoch(): Int = epochOf(maxOf(0L, high - REPLAY_WINDOW + 1))
    }

    // ------------------------------------------------------------------ primitives

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hmac256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac256(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; i++
        }
        prk.fill(0)
        return out
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

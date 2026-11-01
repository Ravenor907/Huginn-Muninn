package io.github.ravenor907.muninn.net.call

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * STUN messages as TURN uses them (RFC 8489 framing, RFC 8656 methods and attributes), plus ChannelData.
 * Only what the relay client needs; no Android, so RFC 5769's test vectors check it in unit tests.
 */
object Stun {
    /** The STUN servers for the address lookup, tried in this order: the next one is asked if one gives no answer.
     *  Calls and files both use this list, so another server can be added here with one line. */
    val SERVERS: List<Pair<String, Int>> = listOf("stun.cloudflare.com" to 3478)

    const val MAGIC = 0x2112A442
    const val HEADER = 20

    // Methods
    const val BINDING = 0x001
    const val ALLOCATE = 0x003
    const val REFRESH = 0x004
    const val SEND = 0x006
    const val DATA = 0x007
    const val CREATE_PERMISSION = 0x008
    const val CHANNEL_BIND = 0x009

    // Classes
    const val REQUEST = 0x000
    const val INDICATION = 0x010
    const val SUCCESS = 0x100
    const val ERROR = 0x110

    // Attributes
    const val A_MAPPED_ADDRESS = 0x0001
    const val A_USERNAME = 0x0006
    const val A_MESSAGE_INTEGRITY = 0x0008
    const val A_ERROR_CODE = 0x0009
    const val A_CHANNEL_NUMBER = 0x000C
    const val A_LIFETIME = 0x000D
    const val A_XOR_PEER_ADDRESS = 0x0012
    const val A_DATA = 0x0013
    const val A_REALM = 0x0014
    const val A_NONCE = 0x0015
    const val A_XOR_RELAYED_ADDRESS = 0x0016
    const val A_REQUESTED_TRANSPORT = 0x0019
    const val A_DONT_FRAGMENT = 0x001A
    const val A_XOR_MAPPED_ADDRESS = 0x0020
    const val A_PRIORITY = 0x0024
    const val A_SOFTWARE = 0x8022
    const val A_FINGERPRINT = 0x8028
    const val A_ICE_CONTROLLED = 0x8029

    private const val FINGERPRINT_XOR = 0x5354554EL
    private val random = SecureRandom()

    fun type(method: Int, cls: Int): Int =
        (method and 0x000F) or ((method and 0x0070) shl 1) or ((method and 0x0F80) shl 2) or cls

    fun methodOf(type: Int): Int = (type and 0x000F) or ((type and 0x00E0) shr 1) or ((type and 0x3E00) shr 2)
    fun classOf(type: Int): Int = type and 0x0110

    fun newTid(): ByteArray = ByteArray(12).also { random.nextBytes(it) }

    class Attr(val type: Int, val value: ByteArray)

    class Message(val type: Int, val tid: ByteArray, val attrs: List<Attr>, val raw: ByteArray? = null) {
        val method get() = methodOf(type)
        val cls get() = classOf(type)
        fun attr(t: Int) = attrs.firstOrNull { it.type == t }?.value
        fun text(t: Int) = attr(t)?.let { String(it, Charsets.UTF_8) }
        fun int(t: Int) = attr(t)?.takeIf { it.size >= 4 }?.let { ByteBuffer.wrap(it).int.toLong() and 0xFFFFFFFFL }
        fun xorAddress(t: Int) = attr(t)?.let { decodeXorAddress(it, tid) }
        /** ERROR-CODE as (code, reason), e.g. (401, "Unauthorized"). */
        fun error(): Pair<Int, String>? = attr(A_ERROR_CODE)?.takeIf { it.size >= 4 }?.let {
            (it[2].toInt() and 0x07) * 100 + (it[3].toInt() and 0xFF) to String(it, 4, it.size - 4, Charsets.UTF_8)
        }
    }

    /** True if [b] looks like a STUN message: top two bits 00 and the magic cookie in bytes 4..7. */
    fun isStun(b: ByteArray, len: Int = b.size): Boolean =
        len >= HEADER && (b[0].toInt() and 0xC0) == 0 && ByteBuffer.wrap(b, 4, 4).int == MAGIC

    /**
     * Encodes a message. [integrityKey]: adds MESSAGE-INTEGRITY (HMAC-SHA1) after the attributes;
     * [fingerprint]: adds FINGERPRINT last. [pad]: the byte used to pad attributes to 4 (any value is valid).
     */
    fun encode(type: Int, tid: ByteArray, attrs: List<Attr>, integrityKey: ByteArray? = null, fingerprint: Boolean = false, pad: Byte = 0): ByteArray {
        require(tid.size == 12)
        val body = ByteArrayOutputStream()
        for (a in attrs) writeAttr(body, a.type, a.value, pad)
        var b = body.toByteArray()
        if (integrityKey != null) {
            val head = header(type, b.size + 24, tid)
            val mac = Mac.getInstance("HmacSHA1").run { init(SecretKeySpec(integrityKey, "HmacSHA1")); update(head); doFinal(b) }
            b = b + attrBytes(A_MESSAGE_INTEGRITY, mac, pad)
        }
        if (fingerprint) {
            val head = header(type, b.size + 8, tid)
            val crc = CRC32().apply { update(head); update(b) }.value xor FINGERPRINT_XOR
            b = b + attrBytes(A_FINGERPRINT, ByteBuffer.allocate(4).putInt(crc.toInt()).array(), pad)
        }
        return header(type, b.size, tid) + b
    }

    private fun header(type: Int, length: Int, tid: ByteArray) =
        ByteBuffer.allocate(HEADER).putShort(type.toShort()).putShort(length.toShort()).putInt(MAGIC).put(tid).array()

    private fun attrBytes(t: Int, v: ByteArray, pad: Byte) = ByteArrayOutputStream().also { writeAttr(it, t, v, pad) }.toByteArray()

    private fun writeAttr(out: ByteArrayOutputStream, t: Int, v: ByteArray, pad: Byte) {
        out.write(t shr 8); out.write(t); out.write(v.size shr 8); out.write(v.size)
        out.write(v)
        repeat((4 - v.size % 4) % 4) { out.write(pad.toInt()) }
    }

    /** Decodes [len] bytes of [b]; null if it isn't a well-formed STUN message. */
    fun decode(b: ByteArray, len: Int = b.size): Message? {
        if (!isStun(b, len)) return null
        val bb = ByteBuffer.wrap(b, 0, len)
        val type = bb.short.toInt() and 0xFFFF
        val length = bb.short.toInt() and 0xFFFF
        bb.int
        if (length % 4 != 0 || HEADER + length > len) return null
        val tid = ByteArray(12).also { bb.get(it) }
        val attrs = ArrayList<Attr>()
        var pos = HEADER
        val end = HEADER + length
        while (pos + 4 <= end) {
            val t = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            val l = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            if (pos + 4 + l > end) return null
            attrs += Attr(t, b.copyOfRange(pos + 4, pos + 4 + l))
            pos += 4 + l + (4 - l % 4) % 4
        }
        if (pos != end) return null
        return Message(type, tid, attrs, b.copyOf(end))
    }

    /** Offset of attribute [t] in [raw] (its 4-byte header), or -1. */
    private fun offsetOf(raw: ByteArray, t: Int): Int {
        val end = HEADER + (((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF))
        var pos = HEADER
        while (pos + 4 <= end) {
            val at = ((raw[pos].toInt() and 0xFF) shl 8) or (raw[pos + 1].toInt() and 0xFF)
            val l = ((raw[pos + 2].toInt() and 0xFF) shl 8) or (raw[pos + 3].toInt() and 0xFF)
            if (at == t) return pos
            pos += 4 + l + (4 - l % 4) % 4
        }
        return -1
    }

    /** Checks MESSAGE-INTEGRITY of a decoded message with [key] (the long-term key or a short-term password). */
    fun checkIntegrity(m: Message, key: ByteArray): Boolean {
        val raw = m.raw ?: return false
        val at = offsetOf(raw, A_MESSAGE_INTEGRITY)
        if (at < 0 || at + 24 > raw.size) return false
        val head = raw.copyOf(HEADER)
        val len = at - HEADER + 24
        head[2] = (len shr 8).toByte(); head[3] = len.toByte()
        val mac = Mac.getInstance("HmacSHA1").run { init(SecretKeySpec(key, "HmacSHA1")); update(head); update(raw, HEADER, at - HEADER); doFinal() }
        return MessageDigest.isEqual(mac, raw.copyOfRange(at + 4, at + 24))
    }

    /** Checks FINGERPRINT, which must be the last attribute. */
    fun checkFingerprint(m: Message): Boolean {
        val raw = m.raw ?: return false
        val at = raw.size - 8
        if (at < HEADER || offsetOf(raw, A_FINGERPRINT) != at) return false
        val crc = CRC32().apply { update(raw, 0, at) }.value xor FINGERPRINT_XOR
        return ByteBuffer.wrap(raw, at + 4, 4).int == crc.toInt()
    }

    /** The long-term credential key: MD5(username ":" realm ":" password). Cloudflare's logins are plain ASCII. */
    fun longTermKey(username: String, realm: String, password: String): ByteArray =
        MessageDigest.getInstance("MD5").digest("$username:$realm:$password".toByteArray(Charsets.UTF_8))

    // ------------------------------------------------------------------ addresses

    fun encodeXorAddress(a: InetSocketAddress, tid: ByteArray): ByteArray {
        val ip = a.address.address
        val out = ByteBuffer.allocate(4 + ip.size)
        out.put(0).put(if (ip.size == 4) 1 else 2).putShort((a.port xor (MAGIC ushr 16)).toShort())
        val mask = ByteBuffer.allocate(16).putInt(MAGIC).put(tid).array()
        for (i in ip.indices) out.put((ip[i].toInt() xor mask[i].toInt()).toByte())
        return out.array()
    }

    fun decodeXorAddress(v: ByteArray, tid: ByteArray): InetSocketAddress? {
        if (v.size < 8) return null
        val family = v[1].toInt()
        val n = when (family) { 1 -> 4; 2 -> 16; else -> return null }
        if (v.size < 4 + n) return null
        val port = (((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)) xor (MAGIC ushr 16)
        val mask = ByteBuffer.allocate(16).putInt(MAGIC).put(tid).array()
        val ip = ByteArray(n) { (v[4 + it].toInt() xor mask[it].toInt()).toByte() }
        return try { InetSocketAddress(InetAddress.getByAddress(ip), port and 0xFFFF) } catch (e: Exception) { null }
    }

    fun u32(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()
    /** REQUESTED-TRANSPORT: UDP (17). */
    val UDP_TRANSPORT = byteArrayOf(17, 0, 0, 0)
    fun channelNumber(ch: Int) = byteArrayOf((ch shr 8).toByte(), ch.toByte(), 0, 0)


    // ------------------------------------------------------------------ ChannelData (RFC 8656, 12.4)

    fun isChannelData(b: ByteArray, len: Int = b.size) = len >= 4 && (b[0].toInt() and 0xC0) == 0x40

    /** ChannelData framing. Over TCP and TLS it is padded to a multiple of 4 ([pad]). */
    fun channelData(ch: Int, data: ByteArray, pad: Boolean): ByteArray {
        val padding = if (pad) (4 - data.size % 4) % 4 else 0
        return ByteBuffer.allocate(4 + data.size + padding).putShort(ch.toShort()).putShort(data.size.toShort()).put(data).array()
    }

    /** (channel, data) from a ChannelData message, or null. */
    fun parseChannelData(b: ByteArray, len: Int = b.size): Pair<Int, ByteArray>? {
        if (!isChannelData(b, len)) return null
        val ch = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        val l = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        if (ch !in 0x4000..0x4FFF || 4 + l > len) return null
        return ch to b.copyOfRange(4, 4 + l)
    }
}

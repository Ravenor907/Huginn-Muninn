package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Moving to another device (cap x1, shared/docs/protocol.md section 18), the parts with no Android in them, so the tests
 * check them against shared/tools/p95_ref.py. Huginn's move.cpp is the same.
 *
 * The new device shows a code: MUNINNM1:<base64url of {"v":1, "a":[its "ip:port" on the home Wi-Fi], "e": a one-off
 * P-256 key, "s": 16 random bytes, "n": its name, "app": "muninn" or "huginn"}>. The old device takes it, makes its
 * own one-off key, and both get K from the two keys and "s". Both show the 4 emoji of K. Everything after is sealed
 * with K (AES-256-GCM) and goes straight over the Wi-Fi, never the internet.
 */
object MoveWire {
    const val CAP = "x1"
    const val PREFIX = "MUNINNM1:"
    const val PATH = "/api/huginn/v1/move"
    const val PART = 512 * 1024
    private val INFO = "huginn-muninn move v1".toByteArray()
    private val random = SecureRandom()

    data class Code(val a: List<String>, val e: String, val s: ByteArray, val n: String, val app: String)

    fun code(c: Code): String = PREFIX + b64url(JSONObject().put("v", 1).put("a", JSONArray(c.a)).put("e", c.e).put("s", b64url(c.s))
        .put("n", c.n).put("app", c.app).toString().toByteArray())

    /** An IPv4 literal on a home network: 10/8, 172.16/12, 192.168/16, or link-local 169.254/16. Never a name. */
    fun lanIp(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size != 4 || p.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || it.toInt() > 255 }) return false
        val (a, b) = p[0].toInt() to p[1].toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    /** "ip:port" with [lanIp] and a real port. */
    fun lanAddr(addr: String): Boolean {
        val i = addr.lastIndexOf(':')
        if (i < 0) return false
        val port = addr.substring(i + 1).toIntOrNull() ?: return false
        return lanIp(addr.substring(0, i)) && port in 1..65535
    }

    /** [ip] is on the same network as this device's address [mine] with [prefix] bits. */
    fun sameNetwork(ip: String, mine: String, prefix: Int): Boolean {
        if (!lanIp(ip) || !lanIp(mine) || prefix !in 8..32) return false
        fun n(s: String) = s.split('.').fold(0L) { acc, x -> (acc shl 8) or x.toLong() }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (n(ip) and mask) == (n(mine) and mask)
    }

    /** A move code anywhere in [text], or null. Only home-network addresses are kept; none left, no code. */
    fun parse(text: String): Code? {
        val i = text.indexOf(PREFIX)
        if (i < 0) return null
        val body = text.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return try {
            val o = JSONObject(String(Base64.getUrlDecoder().decode(body)))
            val a = o.getJSONArray("a")
            val c = Code((0 until minOf(a.length(), 8)).map { a.getString(it) }.filter { lanAddr(it) }, o.getString("e"), Base64.getUrlDecoder().decode(o.getString("s")),
                o.optString("n").take(40).ifBlank { "your other device" }, o.getString("app"))
            ChatCrypto.publicFrom(c.e)
            if (o.optInt("v") != 1 || c.s.size != 16 || c.a.isEmpty() || c.app !in setOf("muninn", "huginn")) null else c
        } catch (e: Exception) { null }
    }

    /** K = SHA-256(info || ECDH x || secret || SHA-256(new one-off key) || SHA-256(old one-off key)), the same on both sides. */
    fun key(mine: PrivateKey, theirs: String, secret: ByteArray, newPub: String, oldPub: String): ByteArray {
        val z = KeyAgreement.getInstance("ECDH").run { init(mine); doPhase(ChatCrypto.publicFrom(theirs), true); generateSecret() }
        return sha256(INFO, z, secret, sha256(ChatCrypto.b64d(newPub)), sha256(ChatCrypto.b64d(oldPub)))
    }

    fun emoji(k: ByteArray) = ChatCrypto.emojiCode(k)

    fun mac(k: ByteArray, text: String): String = ChatCrypto.b64(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(text.toByteArray()) })
    fun helloMac(k: ByteArray, oldPub: String) = mac(k, "hello|$oldPub")
    fun readyMac(k: ByteArray, oldPub: String) = mac(k, "ready|$oldPub")
    fun statusMac(k: ByteArray, state: String, h: String) = mac(k, "status|$state|$h")
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun partAad(i: Int, n: Int, h: String) = "move|$i|$n|$h"
    const val LINK_AAD = "move|link"
    fun parts(size: Long): Int = maxOf(1L, (size + PART - 1) / PART).toInt()

    fun seal(k: ByteArray, plain: ByteArray, aad: String, nonce: ByteArray = ByteArray(12).also { random.nextBytes(it) }): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad.toByteArray()) }
        return ChatCrypto.b64(nonce + c.doFinal(plain))
    }

    /** Null when it doesn't open (another key, another place in the payload, or changed). */
    fun open(k: ByteArray, d: String, aad: String): ByteArray? = try {
        val raw = ChatCrypto.b64d(d)
        if (raw.size < 28) null
        else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12))); updateAAD(aad.toByteArray())
            doFinal(raw, 12, raw.size - 12)
        }
    } catch (e: Exception) { null }

    /** The payload: each entry as [uint32 BE name length][name][uint32 BE data length][data]. */
    fun pack(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val n = name.toByteArray()
            out.write(ByteBuffer.allocate(4).putInt(n.size).array()); out.write(n)
            out.write(ByteBuffer.allocate(4).putInt(data.size).array()); out.write(data)
        }
        return out.toByteArray()
    }

    fun unpack(blob: ByteArray): List<Pair<String, ByteArray>>? = try {
        val b = ByteBuffer.wrap(blob)
        val out = ArrayList<Pair<String, ByteArray>>()
        while (b.hasRemaining()) {
            val ln = b.int; if (ln < 0 || ln > 4096 || ln > b.remaining()) throw IllegalArgumentException()
            val name = String(ByteArray(ln).also { b.get(it) })
            val ld = b.int; if (ld < 0 || ld > b.remaining()) throw IllegalArgumentException()
            out += name to ByteArray(ld).also { b.get(it) }
        }
        out
    } catch (e: Exception) { null }

    fun sha256Hex(b: ByteArray) = ChatWire.hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

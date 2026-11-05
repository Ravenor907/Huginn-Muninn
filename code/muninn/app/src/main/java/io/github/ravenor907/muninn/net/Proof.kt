package io.github.ravenor907.muninn.net

import org.json.JSONObject
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cap s1 (shared/docs/protocol.md section 21; shared/tools/s1_ref.py writes shared/vectors/s1-vectors.json):
 *  - route proofs: a device signs, with its chat key, the TLS certificate it answers on (Wi-Fi) or the other side's
 *    one-off number (Bluetooth LE and Nearby). Only a proven route is taken as a contact's; one without a proof
 *    (an app without s1) is only ever an extra route next to the internet.
 *  - hidden hint: hidden from people nearby, a daily hint goes out instead of the chat key and mailbox.
 *  - bound envelopes: room, id, fromKey, toKey, mailbox, caps, days and invite are sealed in as AES-GCM associated
 *    data, so none of them can be changed and an envelope can't be sent again as someone else's or to someone else.
 */
object Proof {
    const val CAP = "s1"
    const val D_ROUTE = "huginn-muninn route v1"
    const val D_CLOSE = "huginn-muninn close v1"
    private const val HINT = "huginn-muninn hint v1|"
    private const val BOUND = "huginn-muninn s1\n"
    private val BOUND_TEXT = listOf("room", "id", "fromKey", "mailbox", "caps", "invite")
    private val random = SecureRandom()

    // ------------------------------------------------------------------ route proofs

    /** A one-off number: 16 random bytes as 32 lowercase hex characters. Nothing else is ever signed for close by. */
    fun isNonce(n: String) = n.length == 32 && n.all { it in '0'..'9' || it in 'a'..'f' }
    fun newNonce(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun isFingerprint(fp: String) = fp.length == 64 && fp.all { it in '0'..'9' || it in 'A'..'F' }

    private fun routeText(key: String, fp: String) = Proto06.canon(mapOf("fp" to fp, "k" to key))
    private fun closeText(key: String, n: String) = Proto06.canon(mapOf("k" to key, "n" to n))

    /** Wi-Fi: my chat key vouches for my certificate (its SHA-256, [fp]). Null if it can't be made. */
    fun route(priv: PrivateKey, key: String, fp: String): String? = try {
        fp.uppercase().takeIf { isFingerprint(it) }?.let { Proto06.sign(priv, D_ROUTE, routeText(key, it)) }
    } catch (e: Exception) { null }

    fun routeOk(key: String, fp: String, sig: String): Boolean {
        val f = fp.uppercase()
        return sig.isNotEmpty() && isFingerprint(f) && try { Proto06.verify(key, D_ROUTE, routeText(key, f), sig) } catch (e: Exception) { false }
    }

    /** Bluetooth LE and Nearby: my chat key answers the number the other side picked. Null for anything but a nonce. */
    fun close(priv: PrivateKey, key: String, n: String): String? = try {
        if (isNonce(n)) Proto06.sign(priv, D_CLOSE, closeText(key, n)) else null
    } catch (e: Exception) { null }

    fun closeOk(key: String, n: String, sig: String): Boolean =
        sig.isNotEmpty() && isNonce(n) && try { Proto06.verify(key, D_CLOSE, closeText(key, n), sig) } catch (e: Exception) { false }

    // ------------------------------------------------------------------ hidden hint

    const val DAY = 86_400_000L

    /** What a hidden device sends instead of its chat key on [day] (days since 1970, UTC). */
    fun hint(key: String, day: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal((HINT + day).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun hintNow(key: String, now: Long = System.currentTimeMillis()) = hint(key, now / DAY)

    /** Yesterday, today and tomorrow all match, so clocks a little apart still find each other. */
    fun hintMatches(key: String, h: String, now: Long = System.currentTimeMillis()): Boolean {
        if (h.length != 32) return false
        val d = now / DAY
        return (d - 1..d + 1).any { java.security.MessageDigest.isEqual(hint(key, it).toByteArray(), h.toByteArray()) }
    }

    /** The key among [keys] whose hint [h] is, or null. */
    fun keyForHint(h: String, keys: Collection<String>, now: Long = System.currentTimeMillis()): String? =
        if (h.length != 32) null else keys.firstOrNull { hintMatches(it, h, now) }

    // ------------------------------------------------------------------ bound envelopes

    /**
     * Once someone's envelopes come bound, anything they sealed unbound before that (kept for resending for up to a
     * day) still has a day to arrive; after that only bound ones are taken from them.
     */
    const val GRACE = DAY
    fun strictSince(boundAt: Long, now: Long) = boundAt > 0 && now - boundAt > GRACE
    fun firstBound(a: Long, b: Long) = if (a > 0 && b > 0) minOf(a, b) else maxOf(a, b)

    /** The marker on a bound envelope: "aad":"s1". */
    const val MARK = "aad"

    fun isBound(o: JSONObject) = o.optString(MARK) == CAP

    /** The clear fields a bound envelope's seal covers, as canonical JSON; null when one has the wrong type. */
    fun bound(o: JSONObject, toKey: String): String? {
        val b = LinkedHashMap<String, Any>()
        b["toKey"] = toKey
        for (k in BOUND_TEXT) if (o.has(k)) b[k] = o.opt(k) as? String ?: return null
        if (o.has("days")) {
            val d = o.opt("days")
            val n = when (d) { is Int -> d.toLong(); is Long -> d; else -> return null }
            if (n < 0 || n >= (1L shl 53)) return null
            b["days"] = n
        }
        return try { Proto06.canon(b) } catch (e: Exception) { null }
    }

    /** v1's associated data for a bound envelope. */
    fun aad(text: String): ByteArray = (BOUND + text).toByteArray(Charsets.UTF_8)

    /** The ratchet's: its header, then the same as v1. */
    fun ratchetAad(header: ByteArray, text: String): ByteArray = header + "\n".toByteArray() + aad(text)
}

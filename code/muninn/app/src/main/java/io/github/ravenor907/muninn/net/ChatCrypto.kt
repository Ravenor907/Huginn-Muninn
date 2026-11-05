package io.github.ravenor907.muninn.net

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Messages between Huginn and Muninn: each device has a P-256 key pair. Two devices agree on a
 * key with ECDH, stretch it with HKDF-SHA256, and seal every message with AES-256-GCM.
 * Huginn does exactly the same with OpenSSL; shared/vectors/chat-vectors.json proves they match.
 */
object ChatCrypto {
    private const val INFO = "huginn-muninn chat v1"
    private val AAD = "huginn-muninn".toByteArray()
    private val random = SecureRandom()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicFrom(b64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64d(b64)))
    fun privateFrom(b64: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b64d(b64)))
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64d(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** The shared 32-byte key between my private key and their public key (same on both ends). */
    fun sharedKey(mine: PrivateKey, myPublic: PublicKey, theirs: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mine)
        ka.doPhase(theirs, true)
        val secret = ka.generateSecret()
        val (first, second) = listOf(myPublic.encoded, theirs.encoded).sortedWith(::compareUnsigned)
        val salt = MessageDigest.getInstance("SHA-256").digest(first + second)
        return hkdf(secret, salt, INFO.toByteArray(), 32)
    }

    /** The 6-digit code both screens show the first time two devices talk privately. */
    fun pairingCode(key: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest("pairing code".toByteArray() + key)
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    /** 64 emoji easy to tell apart and to say out loud ("fox, moon, anchor, balloon"). Their order is part of the protocol (13.5). */
    val EMOJI = listOf("🦊", "🌙", "⚓", "🎈", "🐢", "🍎", "🚲", "🔑", "🌵", "🐝", "🎸", "🍄", "🚀", "🐙", "🌻", "🧲",
        "🐘", "🍋", "⛵", "🔔", "🦉", "🍇", "🚂", "🎩", "🐬", "🌽", "🏀", "🪁", "🦋", "🍒", "🛶", "📚",
        "🐧", "🥕", "🏰", "🎺", "🐳", "🍉", "🚁", "🧩", "🦒", "🥨", "🗿", "🎯", "🐞", "🍓", "🛸", "🧭",
        "🐿️", "🍍", "🏕️", "🥁", "🦀", "🌶️", "🚜", "🪐", "🦓", "🍩", "⛺", "🎲", "🐌", "🥑", "🗻", "🧸")

    /** The same 4 emoji on both phones of a pair (from their shared key), for checking a contact by eye. */
    fun emojiCode(key: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest("emoji code".toByteArray() + key)
        return (0 until 4).joinToString(" ") { EMOJI[(d[it].toInt() and 0xff) % EMOJI.size] }
    }

    data class Sealed(val nonce: String, val data: String)

    fun seal(key: ByteArray, text: String, nonce: ByteArray = ByteArray(12).also { random.nextBytes(it) }): Sealed {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(AAD)
        return Sealed(b64(nonce), b64(c.doFinal(text.toByteArray(Charsets.UTF_8))))
    }

    /** Null if the message was tampered with or isn't for this key. */
    fun open(key: ByteArray, sealed: Sealed): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d(sealed.nonce)))
        c.updateAAD(AAD)
        String(c.doFinal(b64d(sealed.data)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Same as seal/open, for raw bytes (photo pieces), so they aren't turned into text twice. [aad]: cap s1's bound fields, else the constant. */
    fun sealBytes(key: ByteArray, plain: ByteArray, aad: ByteArray? = null, nonce: ByteArray = ByteArray(12).also { random.nextBytes(it) }): Sealed {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad ?: AAD)
        return Sealed(b64(nonce), b64(c.doFinal(plain)))
    }

    fun openBytes(key: ByteArray, sealed: Sealed, aad: ByteArray? = null): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d(sealed.nonce)))
        c.updateAAD(aad ?: AAD)
        c.doFinal(b64d(sealed.data))
    } catch (e: Exception) {
        null
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}

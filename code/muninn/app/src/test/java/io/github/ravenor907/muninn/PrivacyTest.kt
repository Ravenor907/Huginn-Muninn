package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Vault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PrivacyTest {
    @Test fun vaultSealsAndOpens() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "secret chats".toByteArray()
        val sealed = Vault.Crypto.seal(key, plain)
        assertEquals(12 + plain.size + 16, sealed.size)
        assertArrayEquals(plain, Vault.Crypto.open(key, sealed))
    }

    @Test fun wrongKeyOrTamperingFails() {
        val key = ByteArray(32) { 1 }
        val sealed = Vault.Crypto.seal(key, "hello".toByteArray())
        assertNull(Vault.Crypto.open(ByteArray(32) { 2 }, sealed))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(Vault.Crypto.open(key, sealed))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** RFC 7914 section 12: the scrypt that locks the vault, written in Muninn itself. */
    @Test fun scryptMatchesRfc7914() {
        assertEquals("77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            hex(Vault.Crypto.scrypt(ByteArray(0), ByteArray(0), 16, 1, 1, 64)))
        assertEquals("fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            hex(Vault.Crypto.scrypt("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64)))
        assertEquals("7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
            hex(Vault.Crypto.scrypt("pleaseletmein".toByteArray(), "SodiumChloride".toByteArray(), 16384, 8, 1, 64)))
    }

    /** The lock's own settings run, and make a 32-byte key that depends on the secret. */
    @Test fun scryptLockSettings() {
        val salt = ByteArray(16) { 9 }
        val a = Vault.Crypto.scrypt("pattern:0,1,2,5,8,7".toByteArray(), salt, Vault.SCRYPT_N, Vault.SCRYPT_R, Vault.SCRYPT_P)
        assertEquals(32, a.size)
        assert(!a.contentEquals(Vault.Crypto.scrypt("pattern:0,1,2,5,8,6".toByteArray(), salt, Vault.SCRYPT_N, Vault.SCRYPT_R, Vault.SCRYPT_P)))
    }

    /** The byte PBKDF2 inside scrypt is the same as the platform's (which opens PBKDF2 locks). */
    @Test fun pbkdf2MatchesThePlatform() {
        val salt = ByteArray(16) { 7 }
        assertArrayEquals(Vault.Crypto.derive("correct horse", salt, 1000), Vault.Crypto.pbkdf2("correct horse".toByteArray(), salt, 1000, 32))
    }

    @Test fun passwordKeyIsStable() {
        val salt = ByteArray(16) { 7 }
        val a = Vault.Crypto.derive("correct horse", salt, 1000)
        assertEquals(32, a.size)
        assertArrayEquals(a, Vault.Crypto.derive("correct horse", salt, 1000))
        assert(!a.contentEquals(Vault.Crypto.derive("wrong horse", salt, 1000)))
    }

    /** A tiny JPEG layout: SOI, APP0 (JFIF), APP1 (EXIF with "GPS"), COM, DQT, SOS + data, EOI. */
    private fun fakeJpeg(body: Int): ByteArray {
        fun seg(m: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), m.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2) + ByteArray(body) { 0x11 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xE0, "JFIF".toByteArray()) + seg(0xE1, "Exif GPS 37.7N".toByteArray()) +
            seg(0xFE, "my comment".toByteArray()) + seg(0xDB, ByteArray(65) { 1 }) + sos
    }

    @Test fun photoMetadataIsRemoved() {
        val clean = io.github.ravenor907.muninn.net.Photos.scrub(fakeJpeg(100))!!
        val text = String(clean, Charsets.ISO_8859_1)
        assert(!text.contains("GPS") && !text.contains("my comment"))
        assert(text.contains("JFIF"))                                   // the picture's own header stays
        assertEquals(fakeJpeg(100).size - (4 + 14) - (4 + 10), clean.size)
        assertNull(io.github.ravenor907.muninn.net.Photos.scrub(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun photoPiecesRoundTrip() {
        val jpeg = io.github.ravenor907.muninn.net.Photos.scrub(fakeJpeg(50_000))!!
        val parts = io.github.ravenor907.muninn.net.Photos.split(jpeg)
        assertEquals(4, parts.size)
        assert(parts.all { it.size <= io.github.ravenor907.muninn.net.Photos.PIECE })
        assertArrayEquals(jpeg, io.github.ravenor907.muninn.net.Photos.join(parts))
        assertNull(io.github.ravenor907.muninn.net.Photos.join(listOf(byteArrayOf(1, 2, 3))))   // not a JPEG
    }

    @Test fun photoPieceSealing() {
        val key = ByteArray(32) { 3 }
        val piece = ByteArray(16 * 1024) { it.toByte() }
        val sealed = ChatCrypto.sealBytes(key, piece)
        assertArrayEquals(piece, ChatCrypto.openBytes(key, sealed))
        assertNull(ChatCrypto.openBytes(ByteArray(32) { 4 }, sealed))
        // A sealed piece plus its message wrapping stays under Nearby's 32 KB limit.
        assert(sealed.data.length + sealed.nonce.length + 600 < 32 * 1024)
    }

    @Test fun contactCodeRoundTrip() {
        val key = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val code = Contacts.code("Raven 1234", key, "ab".repeat(32))
        val c = Contacts.parse("here's mine: $code")
        assertNotNull(c)
        assertEquals("Raven 1234", c!!.name)
        assertEquals(key, c.chatKey)
        assertEquals("ab".repeat(32), c.mailbox)
        assertNull(Contacts.parse("MUNINN1:garbage"))
    }
}

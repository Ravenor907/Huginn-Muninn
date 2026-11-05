package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same vectors Huginn checks in CI: both apps must agree byte for byte. */
class ChatCryptoTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText())

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun bothSidesDeriveTheSameKeyAndCode() {
        val aPriv = ChatCrypto.privateFrom(v.getString("privA"))
        val aPub = ChatCrypto.publicFrom(v.getString("pubA"))
        val bPriv = ChatCrypto.privateFrom(v.getString("privB"))
        val bPub = ChatCrypto.publicFrom(v.getString("pubB"))
        val fromA = ChatCrypto.sharedKey(aPriv, aPub, bPub)
        val fromB = ChatCrypto.sharedKey(bPriv, bPub, aPub)
        assertEquals(v.getString("key"), hex(fromA))
        assertEquals(v.getString("key"), hex(fromB))
        assertEquals(v.getString("code"), ChatCrypto.pairingCode(fromA))
        // The 4 emoji: the same on both phones, from 64 that are all different.
        assertEquals(ChatCrypto.emojiCode(fromA), ChatCrypto.emojiCode(fromB))
        assertEquals(4, ChatCrypto.emojiCode(fromA).split(" ").size)
        assertEquals(64, ChatCrypto.EMOJI.toSet().size)
    }

    @Test
    fun opensWhatTheOtherSideSealed() {
        val key = ChatCrypto.sharedKey(ChatCrypto.privateFrom(v.getString("privB")), ChatCrypto.publicFrom(v.getString("pubB")),
            ChatCrypto.publicFrom(v.getString("pubA")))
        val text = ChatCrypto.open(key, ChatCrypto.Sealed(v.getString("nonce"), v.getString("data")))
        assertEquals(v.getString("text"), text)
        val again = ChatCrypto.seal(key, "round trip")
        assertEquals("round trip", ChatCrypto.open(key, again))
    }

    @Test
    fun rejectsTampering() {
        val key = ByteArray(32) { 7 }
        val s = ChatCrypto.seal(key, "hi")
        val bytes = ChatCrypto.b64d(s.data).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(ChatCrypto.open(key, ChatCrypto.Sealed(s.nonce, ChatCrypto.b64(bytes))))
    }
}

package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.tv.Quick
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A parent taken off the TV, the TV's update in its status, the first setup's prompts and the quick message editor. */
class TvParentsTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kB = keys.getString("B")
    private val kTV = keys.getString("TV")

    @Test
    fun aRemovedParentsPhoneLearnsItFromTheSignedConfig() {
        val privA = ChatCrypto.privateFrom(chat.getString("privA"))
        val privB = ChatCrypto.privateFrom(chat.getString("privB"))
        val quick = TvRules.DEFAULT_QUICK.map { Quick(TvRules.quickId(), it) }
        val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)), TvPerson(kB, "Mom", "b".repeat(64)))
        // A takes B off: the config A signs lists only A; B stays someone the TV can reach.
        val onlyA = TvRules.config(privA, kTV, 6, kA, listOf(kA), quick, people, false, 1761500000006)
        val msg = JSONObject(TvRules.removedMessage(onlyA))
        assertEquals("removed", msg.getString("t"))
        assertEquals(listOf(kB), TvRules.dropped(listOf(kA, kB), TvRules.parse(onlyA).parents))
        assertTrue(TvRules.dropped(listOf(kA), listOf(kA, kB)).isEmpty())

        // From the TV itself (no list needed), or from a parent B's phone knew, at or past what B has.
        assertNotNull(TvRules.removedFrom(kTV, kB, null, 0, msg))
        assertNotNull(TvRules.removedFrom(kTV, kB, listOf(kA, kB), 6, msg))
        // Not for B: it still lists B, it's for another TV, it's older than what B has, or signed by someone B doesn't know.
        val both = TvRules.config(privA, kTV, 7, kA, listOf(kA, kB), quick, people, false, 1761500000007)
        assertNull(TvRules.removedFrom(kTV, kB, null, 0, JSONObject(TvRules.removedMessage(both))))
        assertNull(TvRules.removedFrom(kA, kB, null, 0, msg))
        assertNull(TvRules.removedFrom(kTV, kB, listOf(kA, kB), 7, msg))
        assertNull(TvRules.removedFrom(kTV, kB, listOf(kB), 0, msg))
        // A changed config (the signature no longer matches) or another message type.
        val forged = JSONObject(TvRules.removedMessage(JSONObject(onlyA.toString()).put("dictation", true)))
        assertNull(TvRules.removedFrom(kTV, kB, null, 0, forged))
        assertNull(TvRules.removedFrom(kTV, kB, null, 0, JSONObject(TvRules.appliedMessage(6, null, onlyA))))
        // B signing a config without B is still one B's own phone can't be fooled by: it lists no B, but B isn't its parent.
        val byB = TvRules.config(privB, kTV, 8, kB, listOf(kB), quick, people, false, 1761500000008)
        assertNull(TvRules.removedFrom(kTV, kB, null, 0, JSONObject(TvRules.removedMessage(byB))))
    }

    @Test
    fun theTvsUpdateGoesInItsStatus() {
        for (u in TvRules.UPDATE_STATES) {
            val s = TvRules.status(JSONObject(TvRules.statusMessage(50, 1000, 5, update = u)))!!
            assertEquals(u, s.update)
            val words = TvRules.updateText(u)
            assertTrue(u, words.isNotEmpty())
            assertFalse(u, words.contains('\u2014'))
        }
        // Without one (or one this app doesn't know): nothing, and the rest of the status still reads.
        val plain = JSONObject(TvRules.statusMessage(50, 1000, 5))
        assertFalse(plain.has("update"))
        assertNull(TvRules.status(plain)!!.update)
        assertFalse(JSONObject(TvRules.statusMessage(50, 1000, 5, update = "later")).has("update"))
        val odd = TvRules.status(plain.put("update", "later"))!!
        assertNull(odd.update)
        assertEquals(1000L, odd.used)
        assertEquals("", TvRules.updateText(null))
        assertTrue(TvRules.updateText("needs").contains("Install updates"))
    }

    @Test
    fun pairingAsksForTheMicrophoneAndNotificationsAtOnce() {
        assertEquals(listOf("mic", "notify"), TvRules.setupAsks(hasMic = true, micOn = false, notifyPrompt = true, notifyOn = false))
        assertEquals(listOf("mic"), TvRules.setupAsks(hasMic = true, micOn = false, notifyPrompt = false, notifyOn = false))
        assertEquals(listOf("notify"), TvRules.setupAsks(hasMic = false, micOn = false, notifyPrompt = true, notifyOn = false))
        assertTrue(TvRules.setupAsks(hasMic = true, micOn = true, notifyPrompt = true, notifyOn = true).isEmpty())
        for (id in listOf("mic", "notify")) assertTrue(id in TvRules.PERMS)
    }

    @Test
    fun aQuickMessageFitsItsTile() {
        assertTrue(TvRules.QUICK_TILE in 1..TvRules.MAX_QUICK_TEXT)
        for (q in TvRules.DEFAULT_QUICK) assertTrue(q, q.length <= TvRules.QUICK_TILE)
        val thirty = "a".repeat(30)
        assertEquals(thirty, TvRules.quickEdit("", thirty))
        assertEquals(thirty, TvRules.quickEdit(thirty, thirty + "b"))   // one more letter doesn't go in
        // An older, longer one only gets shorter.
        val long = "b".repeat(40)
        assertEquals(long.dropLast(1), TvRules.quickEdit(long, long.dropLast(1)))
        assertEquals(long, TvRules.quickEdit(long, long + "c"))
        assertEquals("hi", TvRules.quickEdit(long, "hi"))
    }
}

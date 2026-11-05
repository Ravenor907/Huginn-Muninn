package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatFlags
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.Permissions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a person may do here (the press-and-hold sheet), caps rules for the screens, and pinned chats. */
class PermissionsTest {
    @Test
    fun choicesAreKeptInTheContactAndSurviveAddingAgain() {
        val c = Contact("k", "Mom", null, false, noCalls = true, noFiles = true, hideActive = true)
        val back = ChatWire.contactFromJson(JSONObject(ChatWire.contactToJson(c).toString()))
        assertEquals(c, back)
        // Nothing set: nothing extra written, and a file without it reads as everything allowed.
        val plain = ChatWire.contactToJson(Contact("k", "Mom", null, false))
        assertFalse(plain.has("xc") || plain.has("xf") || plain.has("xa"))
        val again = ChatWire.merge(c, Contact("k", "Mom", "a".repeat(64), true))
        assertTrue(again.noCalls && again.noFiles && again.hideActive)
    }

    @Test
    fun capsThatBringOthers() {
        assertTrue(Permissions.has(setOf("c2"), "c1"))
        assertTrue(Permissions.has(setOf("v1"), "c1"))
        assertTrue(Permissions.has(setOf("m2"), "m1"))
        assertFalse(Permissions.has(setOf("m1"), "m2"))
        assertFalse(Permissions.has(setOf("r1", "g1"), "c2"))
    }

    @Test
    fun onlyRingsAreHeldBack() {
        assertTrue(Permissions.isRing("""{"t":"offer","call":"x"}"""))
        assertTrue(Permissions.isRing("""{"t":"vring","room":"x"}"""))
        assertFalse(Permissions.isRing("""{"t":"offer","call":"x","g":"y","ch":"z"}"""))
        assertFalse(Permissions.isRing("""{"t":"hangup","call":"x"}"""))
        assertFalse(Permissions.isRing("not json"))
    }

    @Test
    fun pinnedChatsNewestFirstAndKeptAcrossARestart() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.pin("a", true); f.pin("b", true)
        assertEquals(listOf("b", "a"), f.all.value.pinned)
        f.orderPinned(listOf("a", "b"))
        assertEquals(listOf("a", "b"), ChatFlags({ stored }, { stored = it }).all.value.pinned)
        f.forget("a")
        assertFalse(f.pinned("a"))
        assertTrue(f.pinned("b"))
    }
}

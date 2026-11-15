package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Permissions
import io.github.ravenor907.muninn.net.SafeJson
import io.github.ravenor907.muninn.net.Sharing
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Hostile input: JSON nested too deep, ids that would be paths, names that hide their real kind. */
class SafeJsonTest {
    @Test
    fun deepNestingIsRefusedNotCrashed() {
        val deep = "[".repeat(2_000_000)
        assertFalse(SafeJson.ok(deep))
        try { SafeJson.arr(deep); fail("parsed") } catch (_: JSONException) {}
        try { SafeJson.obj("{\"a\":" + "[".repeat(100_000)); fail("parsed") } catch (_: JSONException) {}
        assertNull(SafeJson.objOrNull("{\"a\":" + "{\"b\":".repeat(70) + "1" + "}".repeat(71)))
    }

    @Test
    fun normalJsonStillParses() {
        val ok = "{\"a\":" + "[".repeat(SafeJson.MAX_DEPTH - 1) + "1" + "]".repeat(SafeJson.MAX_DEPTH - 1) + "}"
        assertTrue(SafeJson.ok(ok))
        SafeJson.obj(ok)
        // Brackets inside strings don't count, escaped quotes don't end them.
        val text = "{\"t\":\"" + "[[[[{{{{".repeat(100) + "\\\"[[[\",\"n\":[1,2]}"
        assertEquals(2, SafeJson.obj(text).getJSONArray("n").length())
        assertEquals(3, SafeJson.arr("[1,{\"x\":[]},\"]\"]").length())
    }

    @Test
    fun bracketsCantHideBehindLenientSyntax() {
        // org.json takes single quotes and comments; brackets hidden behind them would get past a scan.
        assertFalse(SafeJson.ok("[a',[[[" + "[".repeat(200)))
        assertFalse(SafeJson.ok("[/*\"*/" + "[".repeat(200)))
        assertFalse(SafeJson.ok("[#\"\n" + "[".repeat(200)))
        try { SafeJson.arr("['x']"); fail("parsed") } catch (_: JSONException) {}
    }

    @Test
    fun sizeCap() {
        assertFalse(SafeJson.ok("[" + " ".repeat(100) + "]", maxChars = 50))
        try { SafeJson.obj("{\"a\":\"" + "x".repeat(100) + "\"}", maxChars = 50); fail("parsed") } catch (_: JSONException) {}
    }

    @Test
    fun pieceIdsAreUuidsOnly() {
        assertTrue(ChatWire.pieceIdOk("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertTrue(ChatWire.pieceIdOk("0F8FAD5B-D9CB-469F-A165-70867728950E"))
        for (bad in listOf("", "../../shared_prefs/muninn.xml", "..", "a/b", "0f8fad5b-d9cb-469f-a165-70867728950e/..",
                "0f8fad5b-d9cb-469f-a165-70867728950e\n", "g-abc", "0f8fad5bd9cb469fa16570867728950e"))
            assertFalse(bad, ChatWire.pieceIdOk(bad))
        assertTrue(ChatWire.blobIdOk("g-AbCdEf_123"))
        for (bad in listOf("", ".", "..", "../x", "a\\b", "a/b", ".hidden", "a\u0000b")) assertFalse(bad, ChatWire.blobIdOk(bad))
    }

    @Test
    fun namesLoseHiddenDirectionAndControls() {
        assertEquals("photogpj.apk", Sharing.cleanName("photo‮gpj.apk"))
        assertEquals("ab.txt", Sharing.cleanName("a\u0085‏b⁦.txt"))
        assertEquals("photogpj.apk", Sharing.safeName("photo‮gpj.apk", "file"))
        assertEquals("👨‍👩.jpg", Sharing.cleanName("👨‍👩.jpg"))
    }

    @Test
    fun directOfferOnlyWithoutGroup() {
        assertTrue(Permissions.isDirectOffer("{\"t\":\"offer\",\"call\":\"x\"}"))
        assertFalse(Permissions.isDirectOffer("{\"t\":\"offer\",\"call\":\"x\",\"g\":\"y\"}"))
        assertFalse(Permissions.isDirectOffer("{\"t\":\"answer\"}"))
        assertFalse(Permissions.isDirectOffer("[".repeat(100_000)))
    }
}

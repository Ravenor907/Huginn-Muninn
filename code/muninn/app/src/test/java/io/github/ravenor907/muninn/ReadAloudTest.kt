package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ReadAloudText
import io.github.ravenor907.muninn.tv.KidVoice
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * Read aloud (code/shared/docs/voices.md): the same cases as Huginn's --readaloud-selftest (src/readaloud.cpp), so both
 * apps read a message the same way, and cap k15 (the voice a parent picks for a Kids' Device).
 */
class ReadAloudTest {
    @Test fun plainText() {
        assertEquals("See link now", ReadAloudText.plain("See https://example.com/a?b=1 now"))
        assertEquals("Hi ava, so good day", ReadAloudText.plain("*Hi* @ava, ~so~ `good` _day_"))
        assertEquals("Dinner ready", ReadAloudText.plain("Dinner 🍕👍🏽 ready ❤️"))
        assertEquals("one two\nthree", ReadAloudText.plain("  one   two \n\n > three "))
        assertTrue(ReadAloudText.plain("😀 😀").isEmpty())
        // Letters from any language stay; a mention in another script loses only its @.
        assertEquals("Hej Åsa, takk", ReadAloudText.plain("Hej @Åsa, takk"))
    }

    @Test fun pieces() {
        assertEquals(listOf("Hello there.", "How are you?", "Fine!", "New line"), ReadAloudText.pieces("Hello there. How are you? Fine!\nNew line"))
        val long = "a".repeat(300) + ", " + "b".repeat(300)
        val lp = ReadAloudText.pieces(long)
        assertEquals(2, lp.size)
        assertEquals(301, lp[0].length)
        assertEquals("b".repeat(300), lp[1])
        assertTrue(ReadAloudText.pieces("x".repeat(1000)).all { it.length <= ReadAloudText.MAX_PIECE })
        assertTrue(ReadAloudText.pieces("").isEmpty())
    }

    @Test fun hashesAndFiles() {
        val a64 = "a".repeat(64)
        val h = ReadAloudText.hashes("# comment\n$a64  voice-female-1.tar\nnothex  voice-male-1.tar\n${"b".repeat(64)} one-space.tar\n")
        assertEquals(mapOf("voice-female-1.tar" to a64), h)
        assertEquals("voice-female-1.tar", ReadAloudText.fileFor("f"))
        assertEquals("voice-male-1.tar", ReadAloudText.fileFor("m"))
        assertEquals("voice-female-1.tar", ReadAloudText.fileFor("x"))
        assertEquals("f", ReadAloudText.voiceOf(null))
        assertEquals("m", ReadAloudText.voiceOf("m"))
        // The file both apps build in parses.
        val shared = File("../../shared/voices.sha256").takeIf { it.isFile } ?: File("../shared/voices.sha256")
        if (shared.isFile) ReadAloudText.hashes(shared.readText()).values.forEach { assertTrue(Regex("[0-9a-f]{64}").matches(it)) }
    }

    /** Round 19 (security 7): the same filters as Huginn's --readaloud-selftest, before anything reaches the engine. */
    @Test fun engineText() {
        assertEquals("a b speak c /speak d", ReadAloudText.plain("a\u0007b<speak>c</speak>\u202Ed"))
        assertEquals("x".repeat(40) + " " + "x".repeat(40) + " " + "x".repeat(20), ReadAloudText.plain("x".repeat(100)))
        assertTrue(ReadAloudText.plain("ab ".repeat(5000)).length <= ReadAloudText.MAX_TEXT)
        assertEquals("h 'loU speak Hi /speak \u00C5sa and co 's", ReadAloudText.forEngine("[[h@'loU]] <speak>Hi</speak> \u00C5sa & co \u2019s"))
        assertEquals("123", ReadAloudText.forEngine("\u041F\u0440\u0438 \u65E5\u672C 123"))
        assertEquals("a\u030A\u0301b", ReadAloudText.forEngine("a\u030A\u0301\u0300\u0302b"))
    }

    @Test fun names() {
        assertTrue(ReadAloudText.safeName("model.onnx"))
        assertTrue(ReadAloudText.safeName("espeak-ng-data/lang/gmw/en"))
        assertTrue(ReadAloudText.safeName("espeak-ng-data"))
        for (bad in listOf("../x", "/etc/passwd", "espeak-ng-data/../../x", "other.txt", "model.onnx/x", "C:x", "espeak-ng-data\\x", ""))
            assertFalse(bad, ReadAloudText.safeName(bad))
    }

    private fun entry(name: String, data: ByteArray, type: Char): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "0000644".toByteArray().copyInto(h, 100)
        "0000000".toByteArray().copyInto(h, 108)
        "0000000".toByteArray().copyInto(h, 116)
        data.size.toString(8).padStart(11, '0').toByteArray().copyInto(h, 124)
        "00000000000".toByteArray().copyInto(h, 136)
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257); h[262] = 0; h[263] = '0'.code.toByte(); h[264] = '0'.code.toByte()
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xFF }
        sum.toString(8).padStart(6, '0').toByteArray().copyInto(h, 148); h[154] = 0; h[155] = ' '.code.toByte()
        val pad = ByteArray((512 - data.size % 512) % 512)
        return h + data + pad
    }

    @Test fun unpack() {
        val tmp = Files.createTempDirectory("readaloud").toFile()
        try {
            val end = ByteArray(1024)
            val good = entry("espeak-ng-data", ByteArray(0), '5') + entry("espeak-ng-data/phontab", "p".toByteArray(), '0') +
                entry("model.onnx", ByteArray(700) { 'm'.code.toByte() }, '0') + entry("tokens.txt", "a 1\n".toByteArray(), '0') + end
            val dir = File(tmp, "good")
            assertNull(ReadAloudText.unpack(ByteArrayInputStream(good), dir))
            assertEquals(700, File(dir, "model.onnx").readBytes().size)
            assertTrue(ReadAloudText.complete(dir))
            // A path out of the folder, a link and a damaged header: refused, nothing written outside.
            val evil = entry("model.onnx", "m".toByteArray(), '0') + entry("../evil.txt", "x".toByteArray(), '0') + end
            assertNotNull(ReadAloudText.unpack(ByteArrayInputStream(evil), File(tmp, "evil")))
            assertFalse(File(tmp, "evil.txt").exists())
            assertNotNull(ReadAloudText.unpack(ByteArrayInputStream(entry("tokens.txt", ByteArray(0), '2') + end), File(tmp, "link")))
            val broken = entry("model.onnx", "m".toByteArray(), '0') + end
            broken[3] = 'X'.code.toByte()
            assertNotNull(ReadAloudText.unpack(ByteArrayInputStream(broken), File(tmp, "broken")))
            // Cut short.
            assertNotNull(ReadAloudText.unpack(ByteArrayInputStream(good.copyOf(700)), File(tmp, "short")))
        } finally { tmp.deleteRecursively() }
    }

    @Test fun kidVoiceField() {
        // k15: "voice" is "f" or "m"; anything else is malformed (the reference's reason is the field's name).
        KidVoice.check(JSONObject().put("voice", "f"))
        KidVoice.check(JSONObject().put("voice", "m"))
        KidVoice.check(JSONObject())
        for (bad in listOf<Any>("x", "", 1, true, "F"))
            assertEquals("voice", runCatching { KidVoice.check(JSONObject().put("voice", bad)) }.exceptionOrNull()?.message)
        assertEquals("m", KidVoice.of(JSONObject().put("voice", "m")))
        assertNull(KidVoice.of(JSONObject()))
        assertNull(KidVoice.of(JSONObject().put("voice", "x")))
        // Sent only to a device with k15, and only once a parent picked one.
        assertEquals("m", KidVoice.toSend("m", true))
        assertNull(KidVoice.toSend("m", false))
        assertNull(KidVoice.toSend("", true))
        assertEquals("k15", KidVoice.CAP)
        assertTrue("k15" in io.github.ravenor907.muninn.net.Proto06.caps(io.github.ravenor907.muninn.net.Proto06.CAPS))
    }

    @Test fun kidVoiceInSignedConfig() {
        val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
        val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
        val kA = all.getJSONObject("keys").getString("A")
        val kTV = all.getJSONObject("keys").getString("TV")
        val privA = io.github.ravenor907.muninn.net.ChatCrypto.privateFrom(chat.getString("privA"))
        val people = listOf(io.github.ravenor907.muninn.tv.TvPerson(kA, "Dad", "a".repeat(64)))
        fun cfg(extras: io.github.ravenor907.muninn.tv.TvExtras) =
            TvRules.config(privA, kTV, 2, kA, listOf(kA), emptyList(), people, false, 1761500000002, extras)
        val withVoice = cfg(io.github.ravenor907.muninn.tv.TvExtras(voice = "m"))
        assertEquals("m", withVoice.getString("voice"))
        TvRules.check(withVoice)
        assertEquals(true to "newer", TvRules.accept(kTV, listOf(kA), null, withVoice))
        // No pick (or a device without k15): left out, so an older device still takes the config.
        assertFalse(cfg(io.github.ravenor907.muninn.tv.TvExtras()).has("voice"))
        // A config with a voice that isn't one of the two is malformed.
        val bad = JSONObject(withVoice.toString()).put("voice", "x")
        assertEquals("malformed: voice", TvRules.accept(kTV, listOf(kA), null, bad).second)
    }

    @Test fun speedRules() {
        // 50% to 150%, 80% until moved; older saved ints and odd values read safely.
        assertEquals(80f, ReadAloudText.speedOf(null), 0f)
        assertEquals(80f, ReadAloudText.speedOf(Float.NaN), 0f)
        assertEquals(50f, ReadAloudText.speedOf(10), 0f)
        assertEquals(150f, ReadAloudText.speedOf(400f), 0f)
        assertEquals(83.4f, ReadAloudText.speedOf(83.4f), 0f)
        assertEquals(120f, ReadAloudText.speedOf(120), 0f)
        assertEquals("83%", ReadAloudText.speedText(83.4f))
        assertEquals("84%", ReadAloudText.speedText(83.6f))
        assertEquals("80%", ReadAloudText.speedText(ReadAloudText.SPEED_DEFAULT))
        assertEquals(0.8f, ReadAloudText.engineSpeed(80f), 1e-6f)
        assertEquals(1.5f, ReadAloudText.deviceRate(150f), 1e-6f)
        // Qt's rate: 50% is -1, 100% is 0, 150% is 1, 80% is -0.4.
        assertEquals(-1f, ReadAloudText.qtRate(50f), 1e-6f)
        assertEquals(0f, ReadAloudText.qtRate(100f), 1e-6f)
        assertEquals(1f, ReadAloudText.qtRate(150f), 1e-6f)
        assertEquals(-0.4f, ReadAloudText.qtRate(80f), 1e-6f)
        // Settings' picks: Female, Male or Device; the device's own never reaches a Kids' Device.
        assertEquals("d", ReadAloudText.choiceOf("d"))
        assertEquals("f", ReadAloudText.choiceOf("x"))
        assertEquals("f", ReadAloudText.voiceOf("d"))
        assertFalse("d" in ReadAloudText.VOICES)
        assertEquals(listOf("f", "m", "d"), ReadAloudText.CHOICES.map { it.first })
    }

    @Test fun kidSpeedField() {
        // "speed" is a whole percent from 50 to 150; anything else is malformed.
        KidVoice.check(JSONObject().put("speed", 50))
        KidVoice.check(JSONObject().put("speed", 150))
        KidVoice.check(JSONObject().put("speed", 80L))
        for (bad in listOf<Any>(49, 151, 80.5, "80", true, -1))
            assertEquals("speed", runCatching { KidVoice.check(JSONObject().put("speed", bad)) }.exceptionOrNull()?.message)
        assertEquals(120, KidVoice.speedOf(JSONObject().put("speed", 120)))
        assertNull(KidVoice.speedOf(JSONObject()))
        assertNull(KidVoice.speedOf(JSONObject().put("speed", 200)))
        assertEquals(84, KidVoice.speedOut(83.6f))
        assertEquals(150, KidVoice.speedOut(170f))
        // Cap k16: sent only when the device says k16 and every co-parent's app does too.
        assertEquals(90, KidVoice.speedToSend(90f, true, listOf(setOf("k15", "k16"))))
        assertNull(KidVoice.speedToSend(90f, false, emptyList()))
        assertNull(KidVoice.speedToSend(90f, true, listOf(setOf("k15"))))
        assertNull(KidVoice.speedToSend(90f, true, listOf(setOf("k14"))))
        assertNull(KidVoice.speedToSend(90f, true, listOf(null)))
        assertNull(KidVoice.speedToSend(null, true, emptyList()))
    }

    @Test fun kidSpeedInSignedConfig() {
        val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
        val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
        val kA = all.getJSONObject("keys").getString("A")
        val kTV = all.getJSONObject("keys").getString("TV")
        val privA = io.github.ravenor907.muninn.net.ChatCrypto.privateFrom(chat.getString("privA"))
        val people = listOf(io.github.ravenor907.muninn.tv.TvPerson(kA, "Dad", "a".repeat(64)))
        fun cfg(extras: io.github.ravenor907.muninn.tv.TvExtras) =
            TvRules.config(privA, kTV, 2, kA, listOf(kA), emptyList(), people, false, 1761500000002, extras)
        val with = cfg(io.github.ravenor907.muninn.tv.TvExtras(voice = "f", speed = 120))
        assertEquals(120, with.getInt("speed"))
        TvRules.check(with)
        assertEquals(true to "newer", TvRules.accept(kTV, listOf(kA), null, with))
        assertEquals(120, KidVoice.speedOf(with))
        // None set, or out of range: left out (the device reads at 80%).
        assertFalse(cfg(io.github.ravenor907.muninn.tv.TvExtras(voice = "f")).has("speed"))
        assertFalse(cfg(io.github.ravenor907.muninn.tv.TvExtras(speed = 300)).has("speed"))
        val bad = JSONObject(with.toString()).put("speed", 10)
        assertEquals("malformed: speed", TvRules.accept(kTV, listOf(kA), null, bad).second)
    }
}

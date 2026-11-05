package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.tv.Canon
import io.github.ravenor907.muninn.tv.Quick
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The kids' TV against shared/vectors/proto06-vectors.json ("canonical" and "tv"): Huginn and the reference agree. */
class TvConfigTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val tv = all.getJSONObject("tv")
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kB = keys.getString("B")
    private val kTV = keys.getString("TV")

    private fun cfg(name: String): JSONObject = tv.getJSONObject("configs").getJSONObject(name).getJSONObject("config")

    @Test
    fun canonicalForm() {
        val c = all.getJSONObject("canonical")
        val good = c.getJSONArray("good")
        for (i in 0 until good.length()) {
            val g = good.getJSONObject(i)
            assertEquals(g.getString("canonical"), Canon.of(g.get("value")))
        }
        val refuse = c.getJSONArray("refuse")
        for (i in 0 until refuse.length()) {
            val text = refuse.getString(i)
            try { Canon.of(JSONObject(text)); fail("should refuse $text") } catch (e: IllegalArgumentException) {}
        }
    }

    @Test
    fun configsSignAndHashLikeTheReference() {
        val configs = tv.getJSONObject("configs")
        for (name in configs.keys()) {
            val e = configs.getJSONObject(name)
            val c = e.getJSONObject("config")
            assertEquals(name, e.getString("signed"), String(Canon.signedBytes(TvRules.CONFIG, c), Charsets.UTF_8))
            assertEquals(name, e.getString("hash"), Canon.hash(c))
            assertTrue(name, Canon.verify(c.getString("by"), TvRules.CONFIG, c))
            TvRules.check(c)   // every config in the file is well-formed
        }
        val q = TvRules.parse(cfg("cfg2")).quick.map { it.text }
        assertEquals(listOf("I love you", "I'm hungry", "Can you come here?"), q)   // array order is the order on screen
    }

    @Test
    fun acceptsAndRefusesInOrder() {
        val acc = tv.getJSONArray("accept")
        assertEquals(9, acc.length())
        for (i in 0 until acc.length()) {
            val a = acc.getJSONObject(i)
            val parents = a.getJSONArray("parents").let { p -> (0 until p.length()).map { if (p.getString(it) == "A") kA else kB } }
            val cur = if (a.isNull("current")) null else cfg(a.getString("current"))
            val newName = a.getString("new")
            val new = if (newName.startsWith("cfg2 with dictation")) JSONObject(cfg("cfg2").toString()).put("dictation", false) else cfg(newName)
            val (got, why) = TvRules.accept(kTV, parents, cur, new)
            assertEquals("step $i: $why", a.getBoolean("accept"), got)
            assertEquals("step $i", a.getString("why"), why)
        }
    }

    @Test
    fun disconnectOnlyForTheCurrentVersion() {
        val d = tv.getJSONObject("disconnect")
        val msg = d.getJSONObject("msg")
        assertEquals(d.getString("signed"), String(Canon.signedBytes(TvRules.DISCONNECT, msg), Charsets.UTF_8))
        assertTrue(TvRules.acceptDisconnect(kTV, listOf(kA, kB), d.getLong("acceptWhenCurrentV"), msg).first)
        assertFalse(TvRules.acceptDisconnect(kTV, listOf(kA, kB), d.getLong("refuseWhenCurrentV"), msg).first)
        assertFalse(TvRules.acceptDisconnect(kTV, listOf(kA), 3, msg).first)   // not from a parent
        assertFalse(TvRules.acceptDisconnect(kA, listOf(kA, kB), 3, msg).first)   // for another TV
        // A config's signature never works as a disconnect: the domains differ.
        val asConfig = Canon.sign(ChatCrypto.privateFrom(chat.getString("privB")), TvRules.CONFIG, Canon.unsigned(msg))
        assertFalse(TvRules.acceptDisconnect(kTV, listOf(kA, kB), 3, asConfig).first)
    }

    @Test
    fun whatAParentBuildsTheTvTakes() {
        val privA = ChatCrypto.privateFrom(chat.getString("privA"))
        val quick = TvRules.DEFAULT_QUICK.map { Quick(TvRules.quickId(), it) }
        val people = listOf(TvPerson(kB, "Grandma", "b".repeat(64)), TvPerson(kA, "Dad", "a".repeat(64)))
        val built = TvRules.config(privA, kTV, 1, kA, listOf(kA), quick, people, true, 1761500000000)
        TvRules.check(built)
        // Through the wire: org.json text, then read back as the TV does.
        val wire = JSONObject(TvRules.configMessage(built))
        assertEquals("config", wire.getString("t"))
        val got = wire.getJSONObject("cfg")
        assertEquals(Canon.of(built), Canon.of(got))
        assertEquals(true to "newer", TvRules.accept(kTV, listOf(kA), null, got))
        val parsed = TvRules.parse(got)
        assertEquals(TvRules.DEFAULT_QUICK, parsed.quick.map { it.text })
        assertEquals(listOf(kA, kB).sorted(), parsed.contacts.map { it.k })
        assertTrue(parsed.dictation)
        // The next version from the same parent replaces it; the same one again doesn't.
        val v2 = TvRules.config(privA, kTV, 2, kA, listOf(kA), quick.reversed(), people, false, 1761500000001)
        assertTrue(TvRules.accept(kTV, listOf(kA), got, v2).first)
        assertFalse(TvRules.accept(kTV, listOf(kA), v2, v2).first)
        // With k2 extras the TV takes it and reads them back.
        val v3 = TvRules.config(privA, kTV, 3, kA, listOf(kA), quick, people, false, 1761500000003, TvExtras(calls = false, mic = 90))
        assertTrue(TvRules.accept(kTV, listOf(kA), v2, v3).first)
        assertEquals(90, TvRules.parse(v3).mic); assertFalse(TvRules.parse(v3).calls)
        assertEquals("0.61", JSONObject(TvRules.appliedMessage(3, "0.61")).getString("app"))
        assertFalse(JSONObject(TvRules.appliedMessage(3)).has("app"))
        // Disconnect built here verifies too.
        val d = TvRules.disconnect(privA, kTV, 2, kA, 1761500000002)
        assertTrue(TvRules.acceptDisconnect(kTV, listOf(kA), 2, JSONObject(TvRules.disconnectMessage(d)).getJSONObject("d")).first)
    }

    @Test
    fun parentsShareTheConfigTheTvRuns() {
        val privA = ChatCrypto.privateFrom(chat.getString("privA"))
        val privB = ChatCrypto.privateFrom(chat.getString("privB"))
        val quick = TvRules.DEFAULT_QUICK.map { Quick(TvRules.quickId(), it) }
        val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)), TvPerson(kB, "Mom", "b".repeat(64)))
        // A adds B as a parent: B's phone takes the config A signed, through the TV's "applied" or A's card.
        val both = TvRules.config(privA, kTV, 4, kA, listOf(kA, kB), quick, people, false, 1761500000004)
        val applied = JSONObject(TvRules.appliedMessage(4, "0.9", both))
        val got = TvRules.shared(kTV, kB, applied.getJSONObject("cfg"))!!
        assertEquals(listOf(kA, kB).sorted(), got.parents)
        assertEquals(4L, got.v)
        val card = JSONObject(TvRules.cardMessage("MUNINN1:x", both))
        assertEquals(Canon.of(both), Canon.of(card.getJSONObject("cfg")))
        assertFalse(JSONObject(TvRules.cardMessage("MUNINN1:x")).has("cfg"))
        // B's own change at the next version is one the TV takes from a parent.
        val fromB = TvRules.config(privB, kTV, 5, kB, got.parents, quick, people, true, 1761500000005)
        assertTrue(TvRules.accept(kTV, got.parents, both, fromB).first)
        // Not for this phone: another TV, a config that doesn't list it, or a forged signature.
        assertEquals(null, TvRules.shared(kA, kB, both))
        val onlyA = TvRules.config(privA, kTV, 4, kA, listOf(kA), quick, people, false, 1761500000004)
        assertEquals(null, TvRules.shared(kTV, kB, onlyA))
        val forged = JSONObject(both.toString()).put("dictation", true)
        assertEquals(null, TvRules.shared(kTV, kB, forged))
        assertEquals(null, TvRules.shared(kTV, kB, null))
        // A free disconnect carries the flag outside the signed part; a plain one doesn't.
        val d = TvRules.disconnect(privA, kTV, 5, kA, 1761500000006)
        assertTrue(JSONObject(TvRules.disconnectMessage(d, free = true)).getBoolean("free"))
        assertFalse(JSONObject(TvRules.disconnectMessage(d)).has("free"))
    }

    @Test
    fun refusesMalformedConfigs() {
        val good = cfg("cfg2")
        fun bad(why: String, f: (JSONObject) -> Unit) {
            val c = JSONObject(good.toString()); f(c)
            try { TvRules.check(c); fail("should refuse: $why") } catch (e: IllegalArgumentException) {}
        }
        bad("unsorted parents") { it.put("parents", JSONArray().put(kA).put(kB).let { a -> if (kA < kB) JSONArray().put(kB).put(kA) else a }) }
        bad("signer not a parent") { it.put("by", keys.getString("C")) }
        bad("extra field") { it.put("x", 1) }
        bad("missing field") { it.remove("ts") }
        bad("version 0") { it.put("v", 0) }
        bad("dictation not true or false") { it.put("dictation", 1) }
        bad("long quick message") { it.getJSONArray("quick").getJSONObject(0).put("text", "a".repeat(81)) }
        bad("blank quick message") { it.getJSONArray("quick").getJSONObject(0).put("text", "   ") }
        bad("control character") { it.getJSONArray("quick").getJSONObject(0).put("text", "a\nb") }
        bad("short quick id") { it.getJSONArray("quick").getJSONObject(0).put("id", "AAAA") }
        bad("repeated quick id") { val q = it.getJSONArray("quick"); q.put(JSONObject(q.getJSONObject(0).toString())) }
        bad("too many quick messages") { val q = JSONArray(); repeat(25) { q.put(JSONObject().put("id", TvRules.quickId()).put("text", "hi")) }; it.put("quick", q) }
        bad("long name") { it.getJSONArray("contacts").getJSONObject(0).put("n", "n".repeat(41)) }
        bad("contacts unsorted") { val c = it.getJSONArray("contacts"); it.put("contacts", JSONArray().put(c.get(1)).put(c.get(0))) }
        // k2 (7.5): calls and mic may be there, well-formed; nothing else extra.
        val k2 = cfg("cfg4")
        val parsed = TvRules.parse(k2)
        assertFalse(parsed.calls); assertEquals(80, parsed.mic)
        assertTrue(TvRules.parse(good).calls); assertEquals(TvRules.DEFAULT_MIC, TvRules.parse(good).mic)
        fun badK2(why: String, f: (JSONObject) -> Unit) {
            val c = JSONObject(k2.toString()); f(c)
            try { TvRules.check(c); fail("should refuse: $why") } catch (e: IllegalArgumentException) {}
        }
        badK2("mic over 100") { it.put("mic", 101) }
        badK2("mic not a number") { it.put("mic", true) }
        badK2("calls not true or false") { it.put("calls", 1) }
        badK2("extra field next to k2") { it.put("x", 1) }
        // k5 (7.10): noise removal, 0 to 3; absent means both.
        val k5 = cfg("cfg5")
        assertEquals(TvRules.NR_STANDARD, TvRules.parse(k5).nr)
        assertEquals(TvRules.NR_BOTH, TvRules.parse(k2).nr)
        TvRules.check(k5)
        fun badK5(why: String, f: (JSONObject) -> Unit) {
            val c = JSONObject(k5.toString()); f(c)
            try { TvRules.check(c); fail("should refuse: $why") } catch (e: IllegalArgumentException) {}
        }
        badK5("nr over 3") { it.put("nr", 4) }
        badK5("nr not a number") { it.put("nr", true) }
        badK5("nr as text") { it.put("nr", "1") }
        // k7 (7.12): automatic update checks, true or false; absent means on.
        val k7 = cfg("cfg6")
        assertFalse(TvRules.parse(k7).upd)
        assertTrue(TvRules.parse(k5).upd)
        TvRules.check(k7)
        for (bad in listOf<Any>(0, "off")) {
            val c = JSONObject(k7.toString()).put("upd", bad)
            try { TvRules.check(c); fail("should refuse upd $bad") } catch (e: IllegalArgumentException) {}
        }
        // 80 characters counted as code points: 80 runes are fine.
        val ok = JSONObject(good.toString()); ok.getJSONArray("quick").getJSONObject(0).put("text", "ᚺ".repeat(80)); TvRules.check(ok)
    }

    @Test
    fun k3StatusIsCheckedAndUsageAndClearAreSimple() {
        val m = JSONObject(TvRules.statusMessage(70, 123_456_789L, 1_761_500_100_000L))
        assertEquals(TvRules.Status(70, 123_456_789L, 1_761_500_100_000L, own = false), TvRules.status(m))
        assertFalse(m.has("own"))   // apps without "own" never see it unless the level was set on the TV
        // Out of range levels are held to 0..100 when made; anything else is refused when read.
        assertEquals(100, TvRules.status(JSONObject(TvRules.statusMessage(250, 1, 1)))!!.mic)
        assertEquals(null, TvRules.status(JSONObject(m.toString()).put("mic", 101)))
        assertEquals(null, TvRules.status(JSONObject(m.toString()).put("used", -1)))
        assertEquals(null, TvRules.status(JSONObject(m.toString()).put("ts", "soon")))
        assertEquals(null, TvRules.status(JSONObject(m.toString()).put("t", "applied")))
        assertEquals(null, TvRules.status(JSONObject(m.toString()).apply { remove("used") }))
        // "own": the level was set on the TV. Must be a boolean when present.
        val own = JSONObject(TvRules.statusMessage(70, 1, 5, own = true))
        assertEquals(true, own.get("own"))
        assertEquals(TvRules.Status(70, 1, 5, own = true), TvRules.status(own))
        assertEquals(null, TvRules.status(JSONObject(own.toString()).put("own", 1)))
        assertEquals(false, TvRules.status(JSONObject(own.toString()).put("own", false))!!.own)
        assertEquals("usage", JSONObject(TvRules.usageMessage()).getString("t"))
        assertEquals("clear", JSONObject(TvRules.clearMessage()).getString("t"))
    }

    @Test
    fun k3MicAdoptionNeverComparesTheTvClockWithThePhones() {
        // Set on the TV: taken whatever the TV's clock says (here far behind the phone's), once the TV has
        // this phone's latest config.
        val own = TvRules.Status(mic = 70, used = 1, ts = 1_000, own = true)
        assertTrue(TvRules.adoptMic(own, myMic = 50, myMicAt = 9_999_999_999L, synced = true))
        // A change made here still on its way to the TV wins over the TV's older level.
        assertFalse(TvRules.adoptMic(own, myMic = 50, myMicAt = 0, synced = false))
        // Same level: nothing to do.
        assertFalse(TvRules.adoptMic(own.copy(mic = 50), myMic = 50, myMicAt = 0, synced = true))
        // A TV without "own": the ts rule.
        val old = TvRules.Status(mic = 70, used = 1, ts = 2_000)
        assertTrue(TvRules.adoptMic(old, myMic = 50, myMicAt = 1_000, synced = true))
        assertFalse(TvRules.adoptMic(old, myMic = 50, myMicAt = 3_000, synced = true))
    }

    @Test
    fun k8KeepAllowAndPermissionStatus() {
        val privA = ChatCrypto.privateFrom(chat.getString("privA"))
        val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)))
        // "keep" goes in only when asked for (a TV that said k8), 1 to 7 days; absent: the TV keeps its own.
        val without = TvRules.config(privA, kTV, 1, kA, listOf(kA), emptyList(), people, false, 1761500000000, TvExtras(calls = true, mic = 50))
        assertFalse(without.has("keep"))
        assertEquals(null, TvRules.parse(without).keep)
        val withKeep = TvRules.config(privA, kTV, 2, kA, listOf(kA), emptyList(), people, false, 1761500000001, TvExtras(keep = 1))
        TvRules.check(withKeep)
        assertEquals(1, TvRules.parse(withKeep).keep)
        assertTrue(TvRules.accept(kTV, listOf(kA), without, withKeep).first)
        assertEquals(7, TvRules.parse(TvRules.config(privA, kTV, 3, kA, listOf(kA), emptyList(), people, false, 1, TvExtras(keep = 30))).keep)
        for (bad in listOf<Any>(0, 8, "1", true)) {
            val c = JSONObject(withKeep.toString()).put("keep", bad)
            try { TvRules.check(c); fail("should refuse keep $bad") } catch (e: IllegalArgumentException) {}
        }
        assertEquals(86_400_000L, TvRules.keepMs(1))
        assertEquals(0L, TvRules.keepMs(7))
        // "allow": exactly t and p, p one of the four permissions.
        for (p in TvRules.PERMS) assertEquals(p, TvRules.allowIn(JSONObject(TvRules.allowMessage(p))))
        assertEquals(null, TvRules.allowIn(JSONObject().put("t", "allow").put("p", "camera")))
        assertEquals(null, TvRules.allowIn(JSONObject(TvRules.allowMessage("mic")).put("x", 1)))
        assertEquals(null, TvRules.allowIn(JSONObject().put("t", "update").put("p", "mic")))
        try { TvRules.allowMessage("camera"); fail("should refuse camera") } catch (e: IllegalArgumentException) {}
        // The status lists what is still off; a phone that doesn't know "off" reads the rest as before.
        val s = JSONObject(TvRules.statusMessage(50, 10, 20, off = listOf("mic", "fullscreen", "install")))
        assertEquals(listOf("mic", "install"), TvRules.status(s)!!.off)
        assertEquals(null, TvRules.status(JSONObject(TvRules.statusMessage(50, 10, 20)))!!.off)
        assertEquals(emptyList<String>(), TvRules.status(JSONObject(TvRules.statusMessage(50, 10, 20, off = emptyList())))!!.off)
        assertEquals(null, TvRules.status(JSONObject(s.toString()).put("off", "mic")))
        assertEquals(null, TvRules.status(JSONObject(s.toString()).put("off", JSONArray().put(1))))
    }
}

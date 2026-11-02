package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.net.ModLogic
import io.github.ravenor907.muninn.net.PeopleSound
import io.github.ravenor907.muninn.net.Proto06
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair

/**
 * Cap g2 (shared/docs/protocol.md section 26): shared/vectors/g2-vectors.json (signed timeouts and bans, forged and
 * tampered ones refused, order, expiry, volume), then three phones running [Groups] against each other.
 */
class ModTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("g2-vectors.json")!!.readText())
    private val state = Proto06.parseState(v.getJSONObject("state"))
    private fun rec(name: String) = v.getJSONObject("records").getJSONObject(name).getJSONObject("record")

    private fun signedText(r: JSONObject): String {
        val o = JSONObject()
        r.keys().forEach { if (it != "sig") o.put(it, r.get(it)) }
        return ModLogic.DOMAIN + "\n" + Proto06.canon(o)
    }

    @Test
    fun signedRecordsVerify() {
        assertEquals(ModLogic.DOMAIN, v.getString("domain"))
        val all = v.getJSONObject("records")
        for (k in all.keys()) {
            val x = all.getJSONObject(k)
            val r = x.getJSONObject("record")
            assertEquals(k, x.getString("signed"), signedText(r))
            assertEquals(k, "ok", ModLogic.accept(state, r))
        }
        assertEquals("unknown group", ModLogic.accept(null, rec("ban")))
    }

    @Test
    fun forgedAndTamperedAreRefused() {
        fun refused(st: Proto06.State, list: JSONObject) {
            for (k in list.keys()) {
                val x = list.getJSONObject(k)
                val got = ModLogic.accept(st, x.getJSONObject("record"))
                assertNotEquals(k, "ok", got)
                assertEquals(k, x.getString("verdict"), got)
            }
        }
        refused(state, v.getJSONObject("refuse"))
        val admins = v.getJSONObject("admins")
        refused(Proto06.parseState(admins.getJSONObject("state")), admins.getJSONObject("refuse"))
        // Changing anything after signing breaks it.
        val ban = rec("ban")
        assertEquals("bad signature", ModLogic.accept(state, JSONObject(ban.toString()).put("ts", ban.getLong("ts") + 1)))
        assertEquals("bad signature", ModLogic.accept(state, JSONObject(ban.toString()).put("a", "unban")))
    }

    @Test
    fun removeForEveryoneRecord() {
        val rm = rec("remove")
        assertTrue(ModLogic.isRemove(rm))
        assertFalse(ModLogic.banned(rm))
        assertFalse(ModLogic.timedOut(rm, rm.getLong("ts")))
        assertTrue(Proto06.isLogicalId(rm.getString("m")))
        // Moved to another message after signing: refused.
        assertEquals("bad signature", ModLogic.accept(state, JSONObject(rm.toString()).put("m", "f".repeat(32))))
    }

    /** Cap g3 (section 28): the group's about text and "only admins post" in its state, taken or refused for the reference's reason. */
    @Test
    fun aboutTextInTheState() {
        val about = v.getJSONObject("g3")
        assertTrue(about.length() >= 11)
        for (k in about.keys()) {
            val c = about.getJSONObject(k)
            val got = try { Proto06.check(Proto06.parseState(c.getJSONObject("state"))); "" } catch (e: IllegalArgumentException) { e.message ?: "" }
            assertEquals(k, c.getString("verdict"), got)
        }
        assertEquals("No spam. Be kind.", Proto06.parseState(about.getJSONObject("short").getJSONObject("state")).about)
        assertTrue(Proto06.parseState(about.getJSONObject("adminsPost").getJSONObject("state")).channels.last().ao)
    }

    @Test
    fun orderAndTimeoutExpiry() {
        val order = v.getJSONArray("order")
        for (i in 0 until order.length()) {
            val c = order.getJSONObject(i)
            val cur = if (c.isNull("cur")) null else rec(c.getString("cur"))
            assertEquals(c.toString(), c.getBoolean("newer"), ModLogic.newer(cur, rec(c.getString("new"))))
        }
        val active = v.getJSONArray("active")
        for (i in 0 until active.length()) {
            val c = active.getJSONObject(i)
            val r = rec(c.getString("record"))
            assertEquals(c.toString(), c.getBoolean("banned"), ModLogic.banned(r))
            assertEquals(c.toString(), c.getBoolean("timedOut"), ModLogic.timedOut(r, c.getLong("now")))
        }
        val offered = v.getJSONObject("limits").getJSONArray("timeouts")
        assertEquals(ModLogic.TIMEOUTS, (0 until offered.length()).map { offered.getLong(it) })
        assertTrue(ModLogic.TIMEOUTS.all { it <= ModLogic.LONGEST })
    }

    @Test
    fun volumeClampAndGain() {
        val vol = v.getJSONArray("volume")
        for (i in 0 until vol.length()) {
            val c = vol.getJSONObject(i)
            assertEquals(c.toString(), c.getInt("out"), ModLogic.clampVolume(c.opt("in").takeIf { it != JSONObject.NULL }))
        }
        val gain = v.getJSONArray("gain")
        for (i in 0 until gain.length()) {
            val c = gain.getJSONObject(i)
            assertEquals(c.toString(), c.getInt("out"), ModLogic.gain(c.getInt("sample"), c.getInt("pct")))
        }
        val frame = shortArrayOf(1000, -1000, 20000, -20000)
        ModLogic.applyGain(frame, 4, 100)
        assertEquals(listOf<Short>(1000, -1000, 20000, -20000), frame.toList())
        ModLogic.applyGain(frame, 4, 200)
        assertEquals(listOf<Short>(2000, -2000, 32767, -32768), frame.toList())
        ModLogic.applyGain(frame, 4, 0)
        assertTrue(frame.all { it == 0.toShort() })
        assertEquals(200, ModLogic.clampVolume(900))
        assertEquals(0, ModLogic.clampVolume(-1))
    }

    @Test
    fun muteAndVolumeAreSavedPerPerson() {
        var saved: String? = null
        val s = PeopleSound({ saved }, { saved = it })
        assertEquals(100, s.gain("k"))
        s.setVolume("k", 250)
        assertEquals(200, s.volume("k"))
        s.setMuted("k", true)
        assertEquals(0, s.gain("k"))
        assertTrue(s.fold("k"))
        s.setHides("k", false)
        assertFalse(s.fold("k"))
        val again = PeopleSound({ saved }, { saved = it })   // after a restart
        assertEquals(200, again.volume("k"))
        assertTrue(again.muted("k"))
        assertFalse(again.hides("k"))
        again.setMuted("k", false)
        assertEquals(200, again.gain("k"))   // the volume holds after Unmute
        again.setVolume("k", 100); again.setHides("k", true)
        assertEquals("{}", saved)            // back to the usual: nothing kept
    }

    // ------------------------------------------------------------------ three phones

    private var clock = 1_762_000_000_000L
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText())

    private inner class Phone(val name: String, val keys: KeyPair, val mailbox: String) : Groups.Host {
        val key: String = ChatCrypto.b64(keys.public.encoded)
        val contacts = HashSet<String>()
        val notices = ArrayList<String>()
        val files = HashMap<String, String>()
        var g2 = true
        lateinit var groups: Groups
        fun start() { groups = Groups(keys, key, this, { files[it] }, { f, t -> files[f] = t }, { clock }) }
        override val myName get() = name
        override val myMailbox get() = mailbox
        override val readReceipts get() = true
        override fun send(o: Groups.Out) { wire.add(this to o) }
        override fun drop(env: String) {}
        override fun isContact(k: String) = k in contacts
        override fun peer(k: String, name: String, mailbox: String) {}
        override fun notice(text: String) { notices.add(text) }
        override fun arrived(g: String, title: String, text: String) {}
        override fun deleteBlob(id: String) {}
        override fun hasM1(k: String) = true
        override fun hasD1(k: String) = true
        override fun hasJ1(k: String) = true
        override fun hasG2(k: String) = phones.first { it.key == k }.g2
    }

    private val wire = ArrayList<Pair<Phone, Groups.Out>>()
    private val a = Phone("Raven", KeyPair(ChatCrypto.publicFrom(chat.getString("pubA")), ChatCrypto.privateFrom(chat.getString("privA"))), "a1".repeat(32))
    private val b = Phone("Moose", KeyPair(ChatCrypto.publicFrom(chat.getString("pubB")), ChatCrypto.privateFrom(chat.getString("privB"))), "b2".repeat(32))
    private val c = Phone("Bear", P256.keyPair(BigInteger(1, ByteArray(32) { 0x33 })), "c3".repeat(32))
    private val phones = listOf(a, b, c)

    init {
        phones.forEach { it.start() }
        a.contacts.add(b.key); b.contacts.add(a.key)
    }

    private fun pump(rounds: Int = 50) {
        repeat(rounds) {
            if (wire.isEmpty()) return
            val now = wire.toList(); wire.clear()
            for ((from, o) in now) {
                val to = phones.first { it.key == o.to }
                if (o.room == "gphoto") to.groups.receivedBlob(from.key, from.name, o.g, o.ch, o.id, "blob-" + o.id, o.voice)
                else to.groups.receive(o.room, from.key, from.name, from.mailbox, JSONObject(o.plain))
            }
        }
    }

    private fun group(): String {
        val g = a.groups.create("Folk")!!
        assertNull(a.groups.invite(g, b.key)); pump()
        assertNull(b.groups.acceptInvite(b.groups.pending.value.single())); pump()
        assertNull(c.groups.joinByCode(Proto06.parseCode(a.groups.code(g)!!)!!)); pump()
        assertTrue(a.groups.group(g)!!.state.has(c.key))
        return g
    }

    private fun texts(p: Phone, g: String) = p.groups.messages.value[g].orEmpty().map { it.text }

    @Test
    fun timeoutDropsPostsUntilItEnds() {
        val g = group()
        assertFalse(a.groups.canModerate(g, a.key))            // not myself
        assertFalse(b.groups.canModerate(g, c.key))            // B isn't an admin
        assertNull(a.groups.timeout(g, c.key, 10 * ModLogic.MINUTE)); pump()
        val until = clock + 10 * ModLogic.MINUTE
        assertEquals(until, b.groups.timedOutUntil(g, c.key))
        assertEquals(until, c.groups.timedOutUntil(g, c.key))
        assertTrue(c.groups.myStatus(g).startsWith("You're timed out until "))
        assertTrue(c.notices.any { it.startsWith("You're timed out in Folk until ") })
        // C's own app sends nothing; an old app's post (sent anyway) is dropped by everyone with g2.
        assertFalse(c.groups.sendText(g, Proto06.MAIN, "let me talk"))
        assertTrue(wire.isEmpty())
        val id = Proto06.newLogicalId()
        b.groups.receive("gmsg", c.key, "Bear", c.mailbox, JSONObject().put("g", g).put("ch", Proto06.MAIN).put("id", id)
            .put("sv", b.groups.group(g)!!.state.v).put("text", "from an old app").put("ts", clock))
        assertFalse("from an old app" in texts(b, g))
        assertFalse(b.groups.receivedBlob(c.key, "Bear", g, Proto06.MAIN, Proto06.newLogicalId(), "blob", 0))
        // Shown in A's settings with the time left.
        assertEquals(listOf(c.key), a.groups.timeoutList(g).map { it.key })
        // It ends by itself: a millisecond before, still timed out; at the end, free to post.
        clock = until - 1
        assertTrue(b.groups.barred(g, c.key))
        clock = until
        assertFalse(b.groups.barred(g, c.key))
        assertEquals("", c.groups.myStatus(g))
        assertTrue(c.groups.sendText(g, Proto06.MAIN, "back again")); pump()
        assertTrue("back again" in texts(b, g))
        // Cancel ends one early.
        assertNull(a.groups.timeout(g, c.key, ModLogic.DAY)); pump()
        assertTrue(b.groups.barred(g, c.key))
        assertNull(a.groups.cancelTimeout(g, c.key)); pump()
        assertFalse(b.groups.barred(g, c.key))
        assertTrue(a.groups.timeoutList(g).isEmpty())
    }

    @Test
    fun banRemovesAndKeepsThemOut() {
        val g = group()
        assertNull(a.groups.ban(g, b.key)); pump()
        assertFalse(a.groups.group(g)!!.state.has(b.key))
        assertTrue(b.groups.group(g)!!.readOnly)
        assertTrue(b.groups.isBanned(g, b.key))
        assertTrue(b.notices.contains("You were banned from Folk"))
        assertTrue(c.groups.isBanned(g, b.key))
        assertEquals(listOf("Moose"), a.groups.bannedList(g).map { it.name })
        // No way back in: an invite is refused, and a code's join is turned away.
        assertNotNull(a.groups.invite(g, b.key))
        assertNull(b.groups.joinByCode(Proto06.parseCode(a.groups.code(g)!!)!!)); pump()
        assertFalse(a.groups.group(g)!!.state.has(b.key))
        assertTrue(a.notices.any { it.contains("they're banned") })
        // Unban: they can join again.
        assertNull(a.groups.unban(g, b.key)); pump()
        assertFalse(a.groups.isBanned(g, b.key))
        assertNull(b.groups.joinByCode(Proto06.parseCode(a.groups.code(g)!!)!!)); pump()
        assertTrue(a.groups.group(g)!!.state.has(b.key))
        assertTrue(b.groups.sendText(g, Proto06.MAIN, "thanks")); pump()
        assertTrue("thanks" in texts(c, g))
    }

    @Test
    fun forgedRecordsChangeNothing() {
        val g = group()
        // C (not an admin) signs a ban of B and sends it as if it were real.
        val forged = ModLogic.make(c.keys.private, "ban", g, b.key, c.key, clock)
        val id = Proto06.newLogicalId()
        a.groups.receive("group", c.key, "Bear", c.mailbox, JSONObject().put("t", "mod").put("g", g).put("id", id).put("list", JSONArray().put(forged)))
        assertFalse(a.groups.isBanned(g, b.key))
        // One that says it's from A but C signed it.
        val fake = ModLogic.make(c.keys.private, "timeout", g, b.key, a.key, clock, clock + ModLogic.MINUTE)
        a.groups.receive("group", c.key, "Bear", c.mailbox, JSONObject().put("t", "mod").put("g", g).put("id", Proto06.newLogicalId()).put("list", JSONArray().put(fake)))
        b.groups.receive("group", c.key, "Bear", c.mailbox, JSONObject().put("t", "mod").put("g", g).put("id", Proto06.newLogicalId()).put("list", JSONArray().put(fake)))
        assertFalse(a.groups.barred(g, b.key))
        assertFalse(b.groups.barred(g, b.key))
        // An empty list is malformed.
        assertEquals(400, a.groups.receive("group", c.key, "Bear", c.mailbox,
            JSONObject().put("t", "mod").put("g", g).put("id", Proto06.newLogicalId()).put("list", JSONArray())))
        // Nobody may time out the owner.
        assertNotNull(a.groups.timeout(g, a.key, ModLogic.MINUTE))
    }

    @Test
    fun appsWithoutG2GetNothingAndRecordsSurviveRestarts() {
        c.g2 = false
        val g = group()
        assertNull(a.groups.timeout(g, b.key, ModLogic.DAY))
        assertTrue(wire.none { it.second.to == c.key && JSONObject(it.second.plain).optString("t") == "mod" })
        pump()
        assertFalse(c.groups.barred(g, b.key))   // its app doesn't know about timeouts
        assertTrue(b.groups.barred(g, b.key))
        b.start()                                // a restart reads them back
        assertTrue(b.groups.barred(g, b.key))
    }
}

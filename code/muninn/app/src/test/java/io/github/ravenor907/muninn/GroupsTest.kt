package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.Rich
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair

/** Three phones running [Groups] against each other in memory: everything section 3 asks for, end to end. */
class GroupsTest {
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText())
    private var clock = 1_761_500_000_000L

    private inner class Phone(val name: String, val keys: KeyPair, val mailbox: String) : Groups.Host {
        val key: String = ChatCrypto.b64(keys.public.encoded)
        val contacts = HashSet<String>()
        val peers = HashMap<String, String>()
        val notices = ArrayList<String>()
        val files = HashMap<String, String>()
        var online = true
        var readOn = true
        lateinit var groups: Groups
        fun start() { groups = Groups(keys, key, this, { files[it] }, { f, t -> files[f] = t }, { clock }) }
        override val myName get() = name
        override val myMailbox get() = mailbox
        override val readReceipts get() = readOn
        override fun send(o: Groups.Out) { wire.add(this to o) }
        override fun drop(env: String) {}
        override fun isContact(k: String) = k in contacts
        override fun peer(k: String, name: String, mailbox: String) { peers[k] = name }
        override fun notice(text: String) { notices.add(text) }
        override fun arrived(g: String, title: String, text: String) {}
        override fun deleteBlob(id: String) {}
        override fun hasM1(k: String) = k !in noM1
        override fun hasD1(k: String) = k !in noD1
        override fun hasJ1(k: String) = k !in noJ1
    }
    /** Phones whose app doesn't have j1 (no links, and "Members can invite" can't be on with them in). */
    private val noJ1 = HashSet<String>()
    /** Phones whose app doesn't have d1 (they can't be in a Family or Work group). */
    private val noD1 = HashSet<String>()
    /** Phones whose app doesn't have m1 (reactions and edits aren't sent to them). */
    private val noM1 = HashSet<String>()

    private val wire = ArrayList<Pair<Phone, Groups.Out>>()
    private val a = Phone("Raven", KeyPair(ChatCrypto.publicFrom(chat.getString("pubA")), ChatCrypto.privateFrom(chat.getString("privA"))), "a1".repeat(32))
    private val b = Phone("Moose", KeyPair(ChatCrypto.publicFrom(chat.getString("pubB")), ChatCrypto.privateFrom(chat.getString("privB"))), "b2".repeat(32))
    private val c = Phone("Bear", P256.keyPair(BigInteger(1, ByteArray(32) { 0x33 })), "c3".repeat(32))
    private val phones = listOf(a, b, c)

    init {
        phones.forEach { it.start() }
        // A and B are contacts; C knows nobody.
        a.contacts.add(b.key); b.contacts.add(a.key)
    }

    /** Delivers everything on the wire (and what that causes) to phones that are online. Offline ones lose it, like a dropped route. */
    private fun pump(rounds: Int = 50, log: MutableList<Pair<Phone, Groups.Out>>? = null) {
        repeat(rounds) {
            if (wire.isEmpty()) return
            val now = wire.toList(); wire.clear()
            log?.addAll(now)
            for ((from, o) in now) {
                val to = phones.first { it.key == o.to }
                if (!to.online) continue
                if (o.room == "gphoto") to.groups.receivedBlob(from.key, from.name, o.g, o.ch, o.id, "blob-" + o.id, o.voice)
                else to.groups.receive(o.room, from.key, from.name, from.mailbox, JSONObject(o.plain))
            }
        }
    }

    private fun Phone.state(g: String) = groups.group(g)!!.state
    private fun same(g: String, vararg p: Phone) { val h = p.first().state(g).hash; p.forEach { assertEquals("${it.name} on the same state", h, it.state(g).hash) } }

    /** A makes Family, invites B (a contact) and lets C in with the group code. */
    private fun family(): String {
        val g = a.groups.create("Family")!!
        assertEquals(1L, a.state(g).v)
        assertTrue(wire.isEmpty())   // making a group sends nothing
        assertNull(a.groups.invite(g, b.key)); pump()
        val inv = b.groups.pending.value.single()
        assertEquals("Family", inv.name)
        assertTrue(b.notices.contains("Raven invited you to Family"))
        assertNull(b.groups.acceptInvite(inv)); pump()
        same(g, a, b)
        assertEquals(2, a.state(g).members.size)
        val code = a.groups.code(g)!!
        assertNull(c.groups.joinByCode(Proto06.parseCode(code)!!)); pump()
        same(g, a, b, c)
        assertEquals(3, c.state(g).members.size)
        assertEquals("Bear", b.peers[c.key])   // members who aren't contacts become group peers
        assertEquals("Raven", c.peers[a.key])
        assertFalse(b.key in a.peers)            // a contact stays a contact
        return g
    }

    @Test
    fun createInviteJoinAndTalk() {
        val g = family()
        assertTrue(a.groups.sendText(g, Proto06.MAIN, "Dinner at six"))
        pump()
        assertEquals("Dinner at six", b.groups.messages.value[g]!!.single().text)
        assertEquals("Raven", c.groups.messages.value[g]!!.single().name)
        val mine = a.groups.messages.value[g]!!.single()
        assertEquals(setOf(b.key, c.key), mine.delivered)
        assertEquals("Delivered", Groups.status(mine))
        assertTrue(a.groups.waiting().isEmpty())   // every member confirmed: nothing left to resend
        assertTrue(g in b.groups.unreadGroups.value)
        b.groups.markRead(g); pump()
        assertEquals("Read by 1", Groups.status(a.groups.messages.value[g]!!.single()))
        c.readOn = false
        c.groups.markRead(g); pump()
        assertEquals("Read by 1", Groups.status(a.groups.messages.value[g]!!.single()))   // C has read receipts off
        // C answers in the group: A and B both get it, A never learns what B read of C's.
        assertTrue(c.groups.sendText(g, Proto06.MAIN, "On my way"))
        pump()
        assertEquals("On my way", a.groups.messages.value[g]!!.last().text)
        assertEquals("Delivered", Groups.status(c.groups.messages.value[g]!!.last()))
    }

    @Test
    fun readByListsEachMemberWithTimesAndPrivacy() {
        val g = family()
        c.readOn = false                       // C's delivered receipt says it won't send reads
        assertTrue(a.groups.sendText(g, Proto06.MAIN, "Dinner at six")); pump()
        var mine = a.groups.messages.value[g]!!.single()
        assertEquals(setOf(c.key), mine.noRead)
        var r = Groups.readBy(mine)
        assertEquals(listOf(b.key), r.delivered)
        assertEquals(listOf(c.key), r.notShown)
        assertTrue(r.read.isEmpty() && r.notYet.isEmpty())
        clock += 60_000
        val readAt = clock
        b.groups.markRead(g); c.groups.markRead(g); pump()
        mine = a.groups.messages.value[g]!!.single()
        r = Groups.readBy(mine)
        assertEquals(listOf(b.key to readAt), r.read)
        assertEquals(listOf(c.key), r.notShown)
        assertEquals("Read by 1", Groups.status(mine))
        // Kept across a restart.
        a.start()
        assertEquals(r, Groups.readBy(a.groups.messages.value[g]!!.single()))
    }

    @Test
    fun readByWithoutNewFields() {
        // An older app's receipts: no "at", no "nr". Read time is when the receipt came in; others are Not yet.
        val m = Groups.GMsg("id", "g", Proto06.MAIN, "me", "Me", "hi", true, 1000, to = setOf("x", "y", "z"),
            delivered = setOf("x", "y"), read = setOf("x"))
        val r = Groups.readBy(m)
        assertEquals(listOf("x" to 0L), r.read)
        assertEquals(listOf("y"), r.delivered)
        assertEquals(listOf("z"), r.notYet)
        assertTrue(r.notShown.isEmpty())
        assertEquals(5000L, Groups.readTime(0, 1000, 5000))
        assertEquals(3000L, Groups.readTime(3000, 1000, 5000))
        assertEquals(5000L, Groups.readTime(9000, 1000, 5000))   // the reader's clock ahead: not after it came in
        assertEquals(1000L, Groups.readTime(10, 1000, 5000))     // nor before the message was sent
    }

    @Test
    fun readReceiptCarriesWhenAndDeliveredCarriesNr() {
        val g = family()
        c.readOn = false
        assertTrue(a.groups.sendText(g, Proto06.MAIN, "Hi"))
        val sent = ArrayList<Pair<Phone, Groups.Out>>()
        pump(log = sent)
        val fromC = sent.filter { it.first === c && it.second.room == "greceipt" }.map { JSONObject(it.second.plain) }
        assertTrue(fromC.isNotEmpty() && fromC.all { it.optInt("nr") == 1 })
        val fromB = sent.filter { it.first === b && it.second.room == "greceipt" }.map { JSONObject(it.second.plain) }
        assertTrue(fromB.isNotEmpty() && fromB.none { it.has("nr") })
        sent.clear()
        b.groups.markRead(g)
        pump(log = sent)
        val read = sent.map { JSONObject(it.second.plain) }.single { it.optString("s") == "read" }
        assertEquals(clock, read.getLong("at"))
    }

    @Test
    fun reactionsEditsRepliesAndDirectAdd() {
        val g = family()
        assertTrue(a.groups.sendText(g, Proto06.MAIN, "Dinner at six")); pump()
        val id = a.groups.messages.value[g]!!.single().id
        assertTrue(b.groups.react(g, id, "❤️")); pump()
        assertEquals(mapOf(b.key to "❤️"), Rich.shown(a.groups.messages.value[g]!!.single().reactions))
        assertEquals(mapOf(b.key to "❤️"), Rich.shown(c.groups.messages.value[g]!!.single().reactions))
        clock += 1000   // a later reaction replaces it; one at the same moment would change nothing
        assertTrue(b.groups.react(g, id, "")); pump()
        assertTrue(Rich.shown(a.groups.messages.value[g]!!.single().reactions).isEmpty())
        // A reply carries the id it answers; an app without m1 just sees the text.
        assertTrue(c.groups.sendText(g, Proto06.MAIN, "Count me in", re = id)); pump()
        assertEquals(id, a.groups.messages.value[g]!!.last().re)
        // Only the author can edit; everyone who got it sees the new text, marked edited.
        assertNull(a.groups.editText(g, id, "Dinner at seven")); pump()
        val atB = b.groups.messages.value[g]!!.first { it.id == id }
        assertEquals("Dinner at seven", atB.text); assertTrue(atB.edited > 0)
        assertNotNull(b.groups.editText(g, id, "hacked"))
        assertEquals("Dinner at seven", c.groups.messages.value[g]!!.first { it.id == id }.text)
        // Someone without m1 gets no reactions (their app would refuse them).
        noM1.add(c.key)
        clock += 1000
        assertTrue(b.groups.react(g, id, "👍"))
        assertTrue(wire.none { it.second.to == c.key && it.second.room == "group" })
        pump()
        // A contact puts someone straight in a new group (op "add"), no invite to accept.
        val g2 = a.groups.create("Pals")!!
        assertNull(a.groups.addDirect(g2, b.key, "Moose", b.mailbox))
        // A group call waits for the state to reach B (B's delivered receipt), then rings.
        assertEquals(setOf(b.key), a.groups.stateWaiting(g2))
        pump()
        assertEquals(emptySet<String>(), a.groups.stateWaiting(g2))
        same(g2, a, b)
        assertTrue(b.notices.contains("Raven added you to Pals"))
        // Someone who isn't a contact can't.
        val g3 = c.groups.create("Spam")!!
        assertNull(c.groups.addDirect(g3, b.key, "Moose", b.mailbox)); pump()
        assertNull(b.groups.group(g3))
    }

    @Test
    fun renameChannelsAdminsAndConflict() {
        val g = family()
        assertNull(a.groups.rename(g, "The Family")); pump()
        assertNull(a.groups.addChannel(g, "Living room", voice = true)); pump()
        same(g, a, b, c)
        assertEquals("The Family", c.state(g).name)
        assertEquals("voice", c.state(g).channels.last().t)
        // Only admins: C can't rename (nothing is sent).
        assertNotNull(c.groups.rename(g, "Mine now"))
        assertTrue(wire.isEmpty())
        assertNull(a.groups.setAdmin(g, b.key, true)); pump()
        assertTrue(c.state(g).isAdmin(b.key))
        // Both admins change the same version at once: everyone ends on one state with both changes.
        val v = a.state(g).v
        assertNull(a.groups.rename(g, "Family Hall"))
        assertNull(b.groups.addChannel(g, "news", voice = false))
        pump()
        same(g, a, b, c)
        val s = a.state(g)
        assertEquals(v + 2, s.v)
        assertEquals("Family Hall", s.name)
        assertTrue(s.channels.any { it.n == "news" })
        // Removing a channel moves nothing; messages for it show in main.
        val news = s.channels.first { it.n == "news" }.id
        assertNull(b.groups.removeChannel(g, news)); pump()
        same(g, a, b, c)
        assertNull(a.state(g).channel(news))
        assertNull(a.groups.removeChannel(g, Proto06.MAIN))   // main can't go: nothing changes
        assertNotNull(a.state(g).channel(Proto06.MAIN))
    }

    @Test
    fun leaveRemoveAndOwnership() {
        val g = family()
        // The owner can't just leave.
        assertNotNull(a.groups.leave(g))
        assertNull(c.groups.leave(g)); pump()
        same(g, a, b)
        assertFalse(a.state(g).has(c.key))
        assertTrue(c.groups.group(g)!!.readOnly)
        assertFalse(c.groups.sendText(g, Proto06.MAIN, "still here?"))
        assertNull(a.groups.remove(g, b.key)); pump()
        assertTrue(b.groups.group(g)!!.readOnly)
        assertTrue(b.notices.contains("You were removed from Family"))
        assertFalse(a.state(g).has(b.key))
        // A message to the group now reaches nobody who left.
        assertTrue(a.groups.sendText(g, Proto06.MAIN, "just me"))
        assertTrue(wire.isEmpty())
        // B is invited back, joins again, and A hands the group over; then A may leave.
        assertNull(a.groups.invite(g, b.key)); pump()
        assertNull(b.groups.acceptInvite(b.groups.pending.value.single())); pump()
        assertFalse(b.groups.group(g)!!.readOnly)
        assertNull(a.groups.handOver(g, b.key)); pump()
        assertEquals(b.key, b.state(g).owner)
        assertNull(a.groups.leave(g)); pump()
        assertFalse(b.state(g).has(a.key))
        assertTrue(a.groups.group(g)!!.readOnly)
        assertEquals(b.key, b.state(g).owner)
        // A (not the owner after the handover) can't take it back.
        assertNotNull(a.groups.handOver(g, a.key))
    }

    @Test
    fun unsendPhotosAndRetries() {
        val g = family()
        assertTrue(a.groups.sendBlob(g, Proto06.MAIN, "p1", 0)); pump()
        assertEquals("blob-" + a.groups.messages.value[g]!!.single().id, b.groups.messages.value[g]!!.single().photo)
        assertEquals("Delivered", Groups.status(a.groups.messages.value[g]!!.single()))
        val id = a.groups.messages.value[g]!!.single().id
        a.groups.unsend(g, id); pump()
        assertEquals("Raven", b.groups.messages.value[g]!!.single().unsent)
        assertEquals("", c.groups.messages.value[g]!!.single().photo)
        assertTrue(a.groups.waiting().isEmpty())
        // C is away: the message waits and goes again later, the same envelope.
        c.online = false
        a.groups.sendText(g, Proto06.MAIN, "Are you there?"); pump()
        assertEquals(1, a.groups.waiting(c.key).size)
        val env = a.groups.waiting(c.key).single().env
        assertTrue(a.groups.due().isEmpty())   // not yet
        clock += 6_000
        val again = a.groups.due()
        assertEquals(listOf(env), again.map { it.env })
        assertEquals(2, again.single().tries)
        c.online = true
        again.forEach { a.send(it) }; pump()
        assertEquals("Are you there?", c.groups.messages.value[g]!!.last().text)
        assertTrue(a.groups.waiting().isEmpty())
        // A copy arriving again is not a second message.
        again.forEach { a.send(it) }; pump()
        assertEquals(1, c.groups.messages.value[g]!!.count { it.text == "Are you there?" })
    }

    @Test
    fun behindMemberCatchesUp() {
        val g = a.groups.create("Family")!!
        assertNull(a.groups.invite(g, b.key)); pump()
        b.groups.acceptInvite(b.groups.pending.value.single()); pump()
        // B is away while C joins and says hi: B hasn't seen the state with C in it.
        b.online = false
        c.groups.joinByCode(Proto06.parseCode(a.groups.code(g)!!)!!); pump()
        b.online = true
        assertFalse(b.state(g).has(c.key))
        c.groups.sendText(g, Proto06.MAIN, "Hi all"); pump()
        // B held it, asked C for the states in between, and shows it now.
        assertTrue(b.state(g).has(c.key))
        same(g, a, b, c)
        assertEquals("Hi all", b.groups.messages.value[g]!!.single().text)
    }

    @Test
    fun strangersAndBadStatesAreTurnedAway() {
        val g = family()
        // C is no contact of B: an invite from C is refused.
        val s = c.state(g)
        assertEquals(403, b.groups.receive("group", c.key, "Bear", null, JSONObject().put("t", "invite").put("g", g)
            .put("id", Proto06.newLogicalId()).put("state", s.json()).put("inv", Proto06.newToken()).put("x", clock / 1000 + 3600)))
        // A join with a made-up token is refused.
        assertEquals(403, a.groups.receive("group", c.key, "Bear", null, JSONObject().put("t", "join").put("g", g)
            .put("id", Proto06.newLogicalId()).put("inv", Proto06.newToken())))
        // A state signed by a plain member (C) changes nothing.
        val forged = s.copy(v = s.v + 1, name = "Mine now", admins = (s.admins + c.key).sorted()).signedBy(c.keys.private, c.key)
        assertEquals(200, b.groups.receive("group", c.key, "Bear", null, JSONObject().put("t", "state").put("g", g)
            .put("id", Proto06.newLogicalId()).put("op", "rename").put("state", forged.json())))
        assertEquals("Family", b.state(g).name)
        // Too long a message is refused, not cut.
        assertEquals(400, b.groups.receive("gmsg", a.key, "Raven", null, JSONObject().put("g", g).put("ch", "main")
            .put("id", Proto06.newLogicalId()).put("sv", s.v).put("text", "x".repeat(2001)).put("ts", clock)))
        // A message from someone who isn't in the group (and not ahead of it) is dropped.
        val stranger = Phone("Wolf", ChatCrypto.newKeyPair(), "")
        assertEquals(200, b.groups.receive("gmsg", stranger.key, "Wolf", null, JSONObject().put("g", g).put("ch", "main")
            .put("id", Proto06.newLogicalId()).put("sv", s.v).put("text", "hello").put("ts", clock)))
        assertTrue(b.groups.messages.value[g].isNullOrEmpty())
        // An expired group code doesn't work.
        clock += 2 * 3600_000
        assertNotNull(c.groups.joinByCode(Proto06.parseCode(a.groups.code(g)!!)!!.copy(x = clock / 1000 - 1)))
    }

    @Test
    fun keptAcrossRestarts() {
        val g = family()
        a.groups.sendText(g, Proto06.MAIN, "remember me"); pump()
        val before = b.state(g)
        b.start()   // a fresh Groups on the same stored files
        assertEquals(before, b.state(g))
        assertEquals("remember me", b.groups.messages.value[g]!!.single().text)
        c.online = false
        a.groups.sendText(g, Proto06.MAIN, "later"); pump()
        a.start()
        assertEquals(1, a.groups.waiting(c.key).size)   // still to be resent after a restart
        assertEquals(1, a.groups.due().size)
    }

    @Test
    fun fiftyAtMost() {
        val g = a.groups.create("Big")!!
        val s = a.state(g)
        val more = (1..50).map { Proto06.Member("k%03d".format(it), "P$it", "") }
        val full = s.copy(v = 2, members = Proto06.sortedMembers(s.members + more.take(49)), sig = "").signedBy(a.keys.private, a.key)
        Proto06.check(full)
        val over = s.copy(v = 2, members = Proto06.sortedMembers(s.members + more), sig = "").signedBy(a.keys.private, a.key)
        assertFalse(Proto06.accept(s, over).first)
        assertTrue(Proto06.accept(s, full).first)
    }

    /** Cap d1 (section 16.2): the type and limit ride in the signed state; old messages go by each group's own limit. */
    @Test
    fun groupTypeAndMessageLimit() {
        val g = family()
        assertEquals(Limits.DEFAULT, Limits.groupDays(a.state(g)))
        // Someone's app lacks d1: the group can't become Family yet.
        noD1.add(c.key)
        assertNotNull(a.groups.setLimit(g, "family", 30))
        assertEquals("", a.state(g).kind)
        noD1.clear()
        assertNull(a.groups.setLimit(g, "family", 30)); pump()
        same(g, a, b, c)
        phones.forEach { assertEquals(30, Limits.groupDays(it.state(g))); assertEquals("family", it.state(g).kind) }
        // Only admins change it.
        assertNotNull(c.groups.setLimit(g, "folk", 14))
        // An app without d1 can't be invited into a Family group.
        val d = a.groups.create("Other", "family")!!
        assertEquals("family", a.state(d).kind)
        noD1.add(b.key)
        assertNotNull(a.groups.invite(d, b.key))
        noD1.clear()
        // Messages older than the limit go; newer ones stay.
        assertTrue(a.groups.sendText(g, Proto06.MAIN, "Old news")); pump()
        clock += 20 * Limits.DAY
        assertTrue(a.groups.sendText(g, Proto06.MAIN, "Fresh")); pump()
        b.groups.pruneOlder { days -> Limits.cutoff(clock, days) }
        assertEquals(listOf("Old news", "Fresh"), b.groups.messages.value[g].orEmpty().map { it.text })
        assertNull(a.groups.setLimit(g, "family", 14)); pump()
        b.groups.pruneOlder { days -> Limits.cutoff(clock, days) }
        assertEquals(listOf("Fresh"), b.groups.messages.value[g].orEmpty().map { it.text })
        // A late copy of what went doesn't come back.
        val old = a.groups.messages.value[g].orEmpty().first { it.text == "Old news" }
        assertNotNull(b.groups.knownEnvelope(old.id + "-00000000"))
        // Back to Folk: the fields go and apps without d1 take the state again.
        assertNull(a.groups.setLimit(g, "folk", 7)); pump()
        assertEquals(Limits.DEFAULT, Limits.groupDays(b.state(g)))
        assertFalse(b.state(g).canonSigned().contains("kind"))
    }

    /** Group types (16.2, 17.1): Folk lets members invite by default; Family has no links and only admins add people. */
    @Test
    fun folkAndFamilyRules() {
        val g = family()   // made as Folk (the default)
        assertEquals(Limits.FOLK, Limits.groupType(a.state(g)))
        assertTrue(a.state(g).mi)
        assertTrue(Limits.membersMayInvite(b.state(g)))
        // A member's link and an admin's link work in a Folk group.
        val (link, err) = a.groups.createLink(g, 0, 0)
        assertNotNull(link); assertNull(err); pump()
        assertEquals(1, a.groups.links(g).size)
        // Family: the links stop, "Members can invite" goes, and new ones are refused.
        assertNull(a.groups.setLimit(g, Limits.FAMILY, 30)); pump()
        same(g, a, b, c)
        phones.forEach { assertEquals(Limits.FAMILY, Limits.groupType(it.state(g))); assertFalse(it.state(g).mi) }
        assertTrue(a.groups.links(g).isEmpty())
        assertNotNull(a.groups.createLink(g, 0, 0).second)
        assertNotNull(a.groups.setMembersInvite(g, true))
        assertNotNull(b.groups.createLink(g, 0, 0).second)
        // An admin's group code still works.
        assertNotNull(a.groups.code(g))
        // Back to Folk: 7 days, members can invite again.
        assertNull(a.groups.setLimit(g, Limits.FOLK, 7)); pump()
        assertEquals(Limits.DEFAULT, Limits.groupDays(c.state(g)))
        assertTrue(c.state(g).mi)
        // Someone without j1: Folk is made without "Members can invite".
        assertNull(a.groups.setLimit(g, Limits.FAMILY, 14)); pump()
        noJ1.add(c.key)
        assertNull(a.groups.setLimit(g, Limits.FOLK, 7)); pump()
        assertFalse(a.state(g).mi)
        noJ1.clear()
        // A new Family group: no "mi" from the start.
        assertFalse(a.state(a.groups.create("Kin", Limits.FAMILY)!!).mi)
    }
}

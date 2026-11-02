package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.BackupFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupFormatTest {
    /** PBKDF2 backups (v1): written here only to check they still open. */
    private val rounds = 100_000

    private fun files() = listOf(
        "chats.json" to """{"abc":[{"text":"hello"}]}""".toByteArray(),
        "contacts.json" to "[]".toByteArray(),
        "photos/msg-1" to ByteArray(200_000) { (it * 31).toByte() },
        "empty" to ByteArray(0),
        "voice/ünïcode name" to byteArrayOf(1, 2, 3),
    )

    private fun make(password: String = "correct horse", list: List<Pair<String, ByteArray>> = files(), app: String = "muninn",
                     v1: Boolean = false, v2: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val steps = ArrayList<Float>()
        val n = BackupFormat.write(out, password, list.map { (n, d) -> BackupFormat.Entry(n) { d } }, app = app,
            created = "2026-10-26T12:00:00Z", rounds = if (v1) rounds else 0, legacy = v2) { steps += it }
        assertEquals(list.size, n)
        if (list.isNotEmpty()) assertEquals(1f, steps.last(), 0f)
        return out.toByteArray()
    }

    @Test fun roundTrip() {
        val opened = BackupFormat.open(make(), "correct horse")
        assertEquals(files().map { it.first }, opened.files.map { it.first })
        files().zip(opened.files).forEach { (a, b) -> assertArrayEquals(a.second, b.second) }
        assertEquals(3, opened.header.v)
        assertEquals("scrypt", opened.header.kdf)
        assertEquals(65_536, opened.header.n); assertEquals(8, opened.header.r); assertEquals(2, opened.header.p)
        assertEquals("muninn", opened.header.app)
        assertEquals("2026-10-26T12:00:00Z", opened.header.created)
        assertEquals(16, opened.header.salt.size)
    }

    /** Backups made with PBKDF2 (v1) still restore. */
    @Test fun pbkdf2BackupStillOpens() {
        val opened = BackupFormat.open(make(v1 = true), "correct horse")
        assertEquals(1, opened.header.v)
        assertEquals(rounds, opened.header.rounds)
        files().zip(opened.files).forEach { (a, b) -> assertArrayEquals(a.second, b.second) }
    }

    /** Backups made before v3 (scrypt, header not covered by the tag) still restore. */
    @Test fun v2BackupStillOpens() {
        val b = make(v2 = true)
        val opened = BackupFormat.open(b, "correct horse")
        assertEquals(2, opened.header.v)
        assertEquals(1, opened.header.p)
        files().zip(opened.files).forEach { (a, c) -> assertArrayEquals(a.second, c.second) }
        try { BackupFormat.open(b, "wrong horse"); fail() } catch (e: BackupFormat.WrongPassword) {}
    }

    @Test fun newBackupsNeedTwelveCharacters() {
        assertEquals(12, BackupFormat.MIN_PASSWORD)
        assertEquals(0, BackupFormat.strength("short pass"))
        assertEquals(0, BackupFormat.strength("abcdefghijk"))
        assertEquals(1, BackupFormat.strength("aaaaaaaaaaaaaaaa"))
        assertEquals(2, BackupFormat.strength("abcdefghijkl"))
        assertEquals(3, BackupFormat.strength("Abcdefghijk1"))
        assertEquals(3, BackupFormat.strength("abcdefghijklmnop"))
        assertEquals(4, BackupFormat.strength("correct horse battery staple"))
        assertEquals(4, BackupFormat.strength("Correct-horse-42!"))
    }

    /** Weakened key settings in the (unprotected) header are refused, not used. */
    @Test fun weakKeySettingsAreRefused() {
        val salt = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        listOf(
            """{"v":2,"salt":"$salt","kdf":"scrypt","N":1024,"r":8,"p":1,"created":"","app":"muninn"}""",
            """{"v":2,"salt":"$salt","kdf":"scrypt","N":65537,"r":8,"p":1,"created":"","app":"muninn"}""",
            """{"v":2,"salt":"$salt","kdf":"argon2id","m":1024,"t":3,"p":1,"created":"","app":"huginn"}""",
            """{"v":2,"salt":"$salt","created":"","app":"muninn"}""",
        ).forEach { j ->
            val b = j.toByteArray()
            try { BackupFormat.header("MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(b.size).array() + b + ByteArray(40)); fail(j) }
            catch (e: BackupFormat.NotABackup) {}
        }
        // v3 is scrypt only, so both apps can open it.
        val v3a = """{"v":3,"salt":"$salt","kdf":"argon2id","m":65536,"t":3,"p":4,"created":"","app":"huginn"}""".toByteArray()
        try { BackupFormat.header("MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(v3a.size).array() + v3a + ByteArray(40)); fail() }
        catch (e: BackupFormat.NotABackup) {}
        // What older Huginns wrote reads (its Argon2id backups then only open in Huginn).
        val ok = """{"v":2,"salt":"$salt","kdf":"argon2id","m":65536,"t":3,"p":4,"created":"","app":"huginn"}""".toByteArray()
        val (h, _) = BackupFormat.header("MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(ok.size).array() + ok + ByteArray(40))
        assertEquals("argon2id", h.kdf); assertEquals(65_536, h.m); assertEquals(3, h.t); assertEquals(4, h.p)
    }

    @Test fun emptyBackupOpens() {
        assertEquals(0, BackupFormat.open(make(list = emptyList()), "correct horse").files.size)
    }

    @Test fun skippedEntriesAreLeftOut() {
        val out = ByteArrayOutputStream()
        val n = BackupFormat.write(out, "pw-12345678", listOf(BackupFormat.Entry("a") { null }, BackupFormat.Entry("b") { byteArrayOf(9) }))
        assertEquals(1, n)
        assertEquals(listOf("b"), BackupFormat.open(out.toByteArray(), "pw-12345678").files.map { it.first })
    }

    /** The exact bytes Huginn expects: magic, BE length, header, nonce + ciphertext + tag (the header as extra data), records. */
    @Test fun layoutMatchesTheSpec() {
        val b = make(list = listOf("x/y" to byteArrayOf(7, 8)))
        assertArrayEquals("MUNB1".toByteArray(), b.copyOfRange(0, 5))
        val len = ByteBuffer.wrap(b, 5, 4).int
        val json = String(b, 9, len)
        assertTrue(json, json.startsWith("{\"v\":3,\"salt\":\""))
        assertTrue(json, json.contains("\"kdf\":\"scrypt\",\"N\":65536,\"r\":8,\"p\":2") && json.contains("\"app\":\"muninn\"") &&
            json.contains("\"created\":\"2026-10-26T12:00:00Z\""))
        val at = 9 + len
        val (h, start) = BackupFormat.header(b)
        assertEquals(at, start)
        val key = io.github.ravenor907.muninn.net.Vault.Crypto.scrypt("correct horse".toByteArray(), h.salt, 65_536, 8, 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, at, 12))
        c.updateAAD(b, 0, at)
        val plain = c.doFinal(b, at + 12, b.size - at - 12)
        assertEquals(b.size - at - 12 - 16, plain.size)   // 12-byte nonce in front, 16-byte tag at the end
        val p = ByteBuffer.wrap(plain)
        assertEquals(3, p.int); assertEquals("x/y", String(ByteArray(3).also { p.get(it) }))
        assertEquals(2, p.int); assertArrayEquals(byteArrayOf(7, 8), ByteArray(2).also { p.get(it) })
        assertFalse(p.hasRemaining())
    }

    /** A header written by another program (spaces, other key order) still reads. */
    @Test fun headerWithSpacesReads() {
        val salt = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 3 })
        val json = """{ "app": "muninn", "created": "2026-01-02T03:04:05Z", "rounds": 310000, "salt": "$salt", "v": 1 }""".toByteArray()
        val b = "MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(json.size).array() + json + ByteArray(40)
        val (h, at) = BackupFormat.header(b)
        assertEquals(310_000, h.rounds); assertEquals("2026-01-02T03:04:05Z", h.created); assertEquals(9 + json.size, at)
    }

    @Test fun wrongPasswordIsRefused() {
        try { BackupFormat.open(make(), "wrong horse"); fail() } catch (e: BackupFormat.WrongPassword) {}
    }

    @Test fun tamperingIsRefused() {
        val b = make()
        b[b.size - 40] = (b[b.size - 40].toInt() xor 1).toByte()
        try { BackupFormat.open(b, "correct horse"); fail() } catch (e: BackupFormat.WrongPassword) {}
        // A changed header (here the date, and which app made it) is refused too: the tag covers it.
        val h = make()
        val json = String(h, 9, ByteBuffer.wrap(h, 5, 4).int)
        val i = 9 + json.indexOf("2026-10-26")
        h[i + 3] = '7'.code.toByte()
        try { BackupFormat.open(h, "correct horse"); fail() } catch (e: BackupFormat.WrongPassword) {}
        val a = make()
        val j = 9 + String(a, 9, ByteBuffer.wrap(a, 5, 4).int).indexOf("muninn")
        "huginn".toByteArray().copyInto(a, j)
        try { BackupFormat.open(a, "correct horse"); fail() } catch (e: BackupFormat.WrongPassword) {}
    }

    /** A v3 backup opens in either app; an older one only in the app that made it, and says where to restore it. */
    @Test fun otherFilesAndOlderHuginnBackupsAreRefused() {
        try { BackupFormat.open("hello there, not a backup".toByteArray(), "x"); fail() } catch (e: BackupFormat.NotABackup) {}
        assertEquals("huginn", BackupFormat.open(make(app = "huginn"), "correct horse").header.app)
        try { BackupFormat.open(make(app = "huginn", v2 = true), "correct horse"); fail() } catch (e: BackupFormat.NotABackup) {
            assertTrue(e.message!!.contains("Huginn"))
        }
        // A header that asks for too few rounds is not trusted.
        val weak = """{"v":1,"salt":"${java.util.Base64.getEncoder().encodeToString(ByteArray(16))}","rounds":1,"created":"","app":"muninn"}""".toByteArray()
        try { BackupFormat.header("MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(weak.size).array() + weak); fail() } catch (e: BackupFormat.NotABackup) {}
    }

    /** What the screen before Restore shows: from [BackupFormat.ABOUT] and the counts in [BackupFormat.SHARED]. */
    @Test fun summaryListsWhatsInside() {
        val shared = """{"v":1,"me":{},"contacts":[{"k":"a"}],"chats":{"a":[],"b":[]},"groups":[],"folders":{"folders":[{"id":"f"}],"top":[]}}"""
        val list = listOf("chats.json" to "{}".toByteArray(), "photos/p" to byteArrayOf(1),
            BackupFormat.ABOUT to BackupFormat.about("muninn", "Pixel", "2026-11-02T10:00:00Z", false), BackupFormat.SHARED to shared.toByteArray())
        val s = BackupFormat.summary(BackupFormat.open(make(list = list), "correct horse"))
        assertEquals("muninn", s.app); assertEquals("Pixel", s.device); assertEquals("2026-11-02T10:00:00Z", s.created)
        assertEquals(false, s.files); assertEquals(1, s.contacts); assertEquals(2, s.chats); assertEquals(0, s.groups); assertEquals(1, s.folders)
        assertEquals(2, s.items)
        // An older backup has no list: just how many items.
        val old = BackupFormat.summary(BackupFormat.open(make(v1 = true), "correct horse"))
        assertEquals(null, old.contacts); assertEquals(null, old.files); assertEquals(files().size, old.items)
        assertTrue(BackupFormat.isMedia("photos/x") && BackupFormat.isMedia("mirror-media/y") && !BackupFormat.isMedia("chats.json"))
    }

    /** shared/vectors/backup-vectors.json (written by shared/tools/backup_ref.py): the same files Huginn checks. */
    @Test fun sharedVectors() {
        val v = org.json.JSONObject(javaClass.classLoader!!.getResource("backup-vectors.json")!!.readText())
        val pw = v.getString("password")
        fun bytes(k: String) = java.util.Base64.getDecoder().decode(v.getJSONObject(k).getString("file"))
        fun want(k: String) = v.getJSONObject(k).getJSONArray("files").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { f -> f.getString("name") to java.util.Base64.getDecoder().decode(f.getString("data")) } }
        }
        // Made in Huginn and in Muninn: both open here, the same files come out.
        for (k in listOf("v3huginn", "v3muninn")) {
            val o = BackupFormat.open(bytes(k), pw)
            assertEquals(want(k).map { it.first }, o.files.map { it.first })
            want(k).zip(o.files).forEach { (a, b) -> assertArrayEquals(k, a.second, b.second) }
            val s = BackupFormat.summary(o)
            val e = v.getJSONObject("summary")
            assertEquals(v.getJSONObject(k).getString("app"), s.app); assertEquals(v.getJSONObject(k).getString("device"), s.device)
            assertEquals(e.getString("created"), s.created); assertEquals(e.getInt("contacts"), s.contacts)
            assertEquals(e.getInt("chats"), s.chats); assertEquals(e.getInt("groups"), s.groups); assertEquals(e.getInt("folders"), s.folders)
            assertEquals(e.getInt("items"), s.items); assertEquals(true, s.files)
            try { BackupFormat.open(bytes(k), v.getString("wrong")); fail(k) } catch (x: BackupFormat.WrongPassword) {}
            val b = bytes(k); b[b.size - 20] = (b[b.size - 20].toInt() xor 1).toByte()
            try { BackupFormat.open(b, pw); fail(k) } catch (x: BackupFormat.WrongPassword) {}
        }
        // The old format: Muninn's PBKDF2 one opens; an older Huginn one says to restore it in Huginn.
        val old = BackupFormat.open(bytes("v1muninn"), pw)
        assertEquals(want("v1muninn").map { it.first }, old.files.map { it.first })
        try { BackupFormat.open(bytes("v2huginn"), pw); fail() } catch (x: BackupFormat.NotABackup) { assertTrue(x.message!!.contains("Huginn")) }
    }

    @Test fun damagedContainerIsRefused() {
        try { BackupFormat.unpack(ByteBuffer.allocate(8).putInt(100).putInt(0).array()); fail() } catch (e: BackupFormat.NotABackup) {}
        try { BackupFormat.unpack(ByteBuffer.allocate(9).putInt(1).put(65).putInt(50).array()); fail() } catch (e: BackupFormat.NotABackup) {}
    }

    @Test fun namesStayInsideTheFolder() {
        listOf("chats.json", "photos/a", "ratchet/s-1.json").forEach { assertTrue(it, BackupFormat.safeName(it)) }
        listOf("", "/etc/passwd", "../x", "a/../../b", "a//b", "a\\b", "./a", "a/").forEach { assertFalse(it, BackupFormat.safeName(it)) }
    }
}

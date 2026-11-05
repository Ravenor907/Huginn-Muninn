package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.AppFiles
import io.github.ravenor907.muninn.tv.TvApps
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Sending apps to a TV: how the files are named and grouped, and what a Kids' TV agrees to take (7.9). */
class SendAppTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @After fun tidy() = TvApps.clear()

    @Test
    fun partsOfOneAppGoTogetherMainFileFirst() {
        val names = listOf("Notes.split.config.arm64_v8a.apk", "photo.jpg", "Notes.apk", "Game.apk", "Notes.split.config.en.apk", "Photos/Notes.split.config.xxhdpi.apk")
        val g = AppFiles.group(names) { it }
        assertEquals(listOf("Notes", "Game"), g.map { it.first })
        assertEquals("Notes.apk", g[0].second.first())
        assertEquals(4, g[0].second.size)
        assertEquals(listOf("Game.apk"), g[1].second)
    }

    @Test
    fun namesAndCleaning() {
        assertEquals("Notes", AppFiles.appOf("Notes.apk"))
        assertEquals("Notes", AppFiles.appOf("Notes.split.config.en.apk"))
        assertEquals("Notes", AppFiles.appOf("folder/Notes.APK"))
        assertEquals("App", AppFiles.appOf(".apk"))
        assertTrue(AppFiles.isApk("x.APK"))
        assertEquals("My App 2", AppFiles.clean("My/App:  2"))
        assertEquals("App", AppFiles.clean(" \\ "))
    }

    @Test
    fun sha256OfAFile() {
        val f = Files.createTempFile("app", ".apk").toFile()
        f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppFiles.sha256(f))
        f.delete()
    }

    @Test
    fun aParentsWordRoundTrips() {
        val parts = listOf(TvApps.Part(a, 10), TvApps.Part(b, 20))
        val (name, back) = TvApps.parse(JSONObject(TvApps.message("Notes", parts)))!!
        assertEquals("Notes", name)
        assertEquals(parts, back)
    }

    @Test
    fun malformedWordsAreRefused() {
        fun bad(s: String) = assertNull(s, TvApps.parse(JSONObject(s)))
        bad("""{"t":"apps","files":[{"h":"$a","s":1}]}""")                       // no name
        bad("""{"t":"apps","name":"","files":[{"h":"$a","s":1}]}""")             // empty name
        bad("""{"t":"apps","name":"N","files":[]}""")                             // no files
        bad("""{"t":"apps","name":"N","files":[{"h":"${a.uppercase()}","s":1}]}""") // hex must be lowercase
        bad("""{"t":"apps","name":"N","files":[{"h":"abc","s":1}]}""")            // not a SHA-256
        bad("""{"t":"apps","name":"N","files":[{"h":"$a","s":0}]}""")             // empty file
        bad("""{"t":"apps","name":"N","files":[{"h":"$a","s":"5"}]}""")           // size as text
        bad("""{"t":"apps","name":"N","files":[{"h":"$a","s":1.5}]}""")           // not an integer
        bad("""{"t":"apps","name":7,"files":[{"h":"$a","s":1}]}""")               // name not text
    }

    @Test
    fun theTvTakesExactlyTheNamedFilesOnce() {
        val parts = listOf(TvApps.Part(a, 10), TvApps.Part(b, 20))
        TvApps.allow("parent", "Notes", parts, now = 0)
        // A file left out, one added, or a size changed: not what the parent named.
        assertNull(TvApps.take(listOf(TvApps.Part(a, 10)), now = 1))
        assertNull(TvApps.take(parts + TvApps.Part(c, 5), now = 1))
        assertNull(TvApps.take(listOf(TvApps.Part(a, 10), TvApps.Part(b, 21)), now = 1))
        // In any order, and the hex in either case as the sender wrote it.
        val got = TvApps.take(listOf(TvApps.Part(b.uppercase(), 20), TvApps.Part(a, 10)), now = 2)
        assertNotNull(got)
        assertEquals("Notes", got!!.name)
        // Used up: the same files again need a new word from the parent.
        assertNull(TvApps.take(parts, now = 3))
    }

    @Test
    fun aParentsWordRunsOut() {
        val parts = listOf(TvApps.Part(a, 10))
        TvApps.allow("parent", "Notes", parts, now = 0)
        assertNull(TvApps.take(parts, now = TvApps.KEEP_MS))
        TvApps.allow("parent", "Notes", parts, now = 0)
        assertNotNull(TvApps.take(parts, now = TvApps.KEEP_MS - 1))
    }

    @Test
    fun disconnectForgetsEveryWord() {
        TvApps.allow("parent", "Notes", listOf(TvApps.Part(a, 10)), now = System.currentTimeMillis())
        TvApps.clear()
        assertNull(TvApps.take(listOf(TvApps.Part(a, 10))))
    }

    @Test
    fun receivedAppsGroupAsFiles() {
        val dir = Files.createTempDirectory("apps").toFile()
        val files = listOf("Notes.apk", "Notes.split.config.en.apk").map { File(dir, it).apply { writeText("x") } }
        val g = AppFiles.group(files.reversed()) { it.name }
        assertEquals(1, g.size)
        assertEquals("Notes.apk", g[0].second[0].name)
        dir.deleteRecursively()
    }
}

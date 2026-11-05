package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.AppStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Labs › App storage: only the allowlisted folders can ever be listed, moved or deleted. */
class AppStorageTest {
    @Test
    fun allowlistTakesOnlySafeFolders() {
        for (id in listOf("c/apps/12/game.apk", "c/outgoing/1700/a.jpg", "c/app/Muninn.apk", "c/updates/Muninn-1.1.apk",
            "c/files/incoming-ab", "c/camera/shot.jpg", "c/app-info-123.apk", "e/Download/a.pdf", "f/Download/Photos/b.jpg"))
            assertTrue(id, AppStorage.allowed(id))
        assertEquals(AppStorage.Kind.RECEIVED, AppStorage.spotOf("c/apps/1/x.apk")?.kind)
        assertEquals(AppStorage.Kind.SENT, AppStorage.spotOf("c/app/x.apk")?.kind)
        assertEquals(AppStorage.Kind.UPDATE, AppStorage.spotOf("c/updates/x.apk")?.kind)
        assertEquals(AppStorage.Kind.CACHE, AppStorage.spotOf("c/camera/x.jpg")?.kind)
    }

    @Test
    fun allowlistRefusesEverythingElse() {
        for (id in listOf(
            // The vault, keys, chats, settings and the rest of the files folder.
            "f/vault.key", "f/secrets.json", "f/chats.json", "f/contacts.json", "f/ratchet/abc", "f/photos/p1", "f/tls/key.pem",
            "f/labs/x", "f/groups/g", "f/outbox/o", "f/ring-key.json", "f/apps/1/x.apk", "c/move-in/part1",
            // Shared preferences and other roots.
            "d/shared_prefs/muninn.xml", "x/apps/1/a", "n/vault-open.key",
            // Climbing out, odd names, folders themselves.
            "c/apps/../../files/vault.key", "c/apps/./a", "c/apps//a", "c/apps/", "c/apps", "c/updates", "c/app-info-",
            "c\\apps\\a", "c/apps/a\u0000b", "/c/apps/a", "c//apps/a", "", "c", "c/", "c/apps/a\\..\\b",
            "c/Download/a", "e/apps/1/a", "c/appsx/a", "c/xapp-info-1.apk", "f/app-info-1.apk",
        )) assertFalse(id, AppStorage.allowed(id))
        assertFalse(AppStorage.allowed("c/apps/" + "a".repeat(AppStorage.MAX_ID)))
    }

    @Test
    fun scanAndDeleteStayInside() {
        val tmp = Files.createTempDirectory("appstorage").toFile()
        try {
            val cache = File(tmp, "cache").apply { mkdirs() }
            val files = File(tmp, "files").apply { mkdirs() }
            val roots = mapOf('c' to cache, 'f' to files)
            val old = System.currentTimeMillis() - 10 * 60_000L
            fun put(f: File, text: String = "x") = f.also { it.parentFile?.mkdirs(); it.writeText(text); it.setLastModified(old) }
            val apk = put(File(cache, "apps/7/game.apk"), "apk!")
            put(File(cache, "updates/Muninn-1.1.apk"))
            put(File(cache, "camera/shot.jpg"))
            val key = put(File(files, "vault.key"))
            val chats = put(File(files, "chats.json"))
            put(File(cache, "move-in/part"))
            // Just written: still busy, so not listed.
            File(cache, "files").mkdirs(); File(cache, "files/incoming-1").writeText("half")

            val items = AppStorage.scan(roots, System.currentTimeMillis())
            assertEquals(setOf("c/apps/7/game.apk", "c/updates/Muninn-1.1.apk", "c/camera/shot.jpg"), items.map { it.id }.toSet())
            assertEquals(4L, items.first { it.id == "c/apps/7/game.apk" }.size)
            assertEquals("apps", items.first { it.id == "c/apps/7/game.apk" }.folder)
            assertFalse(items.any { it.folder.contains(tmp.path) })

            // Nothing outside the allowlist can be deleted, even by climbing out.
            assertFalse(AppStorage.delete(roots, "f/vault.key"))
            assertFalse(AppStorage.delete(roots, "c/apps/../../files/chats.json"))
            assertTrue(key.exists() && chats.exists())

            // A link inside an allowed folder that points at the vault is refused.
            val link = File(cache, "outgoing/1/k")
            link.parentFile?.mkdirs()
            try { Files.createSymbolicLink(link.toPath(), key.toPath()) } catch (_: Exception) {}
            if (Files.isSymbolicLink(link.toPath())) {
                assertNull(AppStorage.resolve(roots, "c/outgoing/1/k"))
                assertFalse(AppStorage.delete(roots, "c/outgoing/1/k"))
                assertTrue(key.exists())
            }

            assertTrue(AppStorage.delete(roots, "c/apps/7/game.apk"))
            assertFalse(apk.exists())
            assertFalse(File(cache, "apps/7").exists())   // the empty session folder goes
            assertTrue(File(cache, "apps").exists())      // the spot's own folder stays

            assertEquals(1, AppStorage.deleteKind(roots, AppStorage.Kind.CACHE, System.currentTimeMillis()))
            assertTrue(File(cache, "updates/Muninn-1.1.apk").exists())
            assertTrue(File(cache, "move-in/part").exists())
            assertTrue(key.exists() && chats.exists())
        } finally { tmp.deleteRecursively() }
    }
}

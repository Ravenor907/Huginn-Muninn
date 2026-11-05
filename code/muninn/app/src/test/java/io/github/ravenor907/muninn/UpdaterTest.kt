package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun readsReleaseTags() {
        assertEquals("0.3", Updater.versionOf("v0.3"))
        assertEquals("0.31", Updater.versionOf("v0.31"))
        assertEquals("1.0", Updater.versionOf("v1.0"))
        assertNull(Updater.versionOf("v0.3-test1"))         // test tags are skipped
        assertNull(Updater.versionOf("muninn-v0.1-test1"))
        assertNull(Updater.versionOf("v0.3.1"))
        assertNull(Updater.versionOf("nightly"))
    }

    @Test
    fun versionsReadAsDecimals() {
        assertTrue(Updater.rank("0.31") > Updater.rank("0.3"))
        assertTrue(Updater.rank("0.32") > Updater.rank("0.31"))
        assertTrue(Updater.rank("0.4") > Updater.rank("0.39"))
        assertTrue(Updater.rank("1.0") > Updater.rank("0.99"))
        assertEquals(Updater.rank("0.3"), Updater.rank("0.30"))
    }

    @Test
    fun oldTestBuildsUpdateToTheRelease() {
        assertTrue(Updater.rank("0.3") > Updater.rank("0.3 Test 1"))
        assertTrue(Updater.rank("0.3 Test 1") > Updater.rank("0.2"))
    }

    @Test
    fun readsReleaseDigests() {
        val hex = "B730D4368CFCDC2A07F1951B0900DD2FFA927FDED665BA862F2E8A9824BB1554"
        assertEquals(hex.lowercase(), Updater.shaOf("sha256:$hex"))                 // GitHub's asset digest
        assertEquals(hex.lowercase(), Updater.shaOf("${hex.lowercase()}  Muninn.apk\n"))  // sha256sum's line
        assertEquals(hex.lowercase(), Updater.shaOf(hex))
        assertNull(Updater.shaOf(""))
        assertNull(Updater.shaOf("sha1:" + "a".repeat(40)))
        assertNull(Updater.shaOf("a".repeat(63)))
        assertNull(Updater.shaOf("a".repeat(65)))
    }

    @Test
    fun readsTagFromLatestLink() {
        assertEquals("v0.6", Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases/tag/v0.6"))
        assertEquals("v0.61", Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases/tag/v0.61?x=1"))
        assertNull(Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases"))
    }
}

package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Contacts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class NetTest {
    /**
     * The rules for the internet side: one OkHttp setup (Net.kt) that everything builds on, no
     * HttpURLConnection outside the Wi-Fi code (LocalSend, same network), and no WebView anywhere.
     */
    @Test fun oneHttpSetupAndNoWebView() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val files = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assert(files.isNotEmpty())
        val offenders = files.flatMap { f ->
            val text = f.readText()
            buildList {
                if (f.name != "LocalSend.kt" && (text.contains("openConnection(") || text.contains("HttpURLConnection"))) add("${f.name}: HttpURLConnection")
                if (f.name != "Net.kt" && text.contains("OkHttpClient.Builder(")) add("${f.name}: own OkHttp client")
                if (text.contains("WebView")) add("${f.name}: WebView")
                // Nothing may send a lasting onion address of this phone (contact codes once had one in "o").
                if (Regex("""put\("o",|put\("onion",""").containsMatchIn(text)) add("${f.name}: onion address")
                // 1.42 (cap f4): the one exception is a one-time onion service for one file, and only its sealed `tor`
                // message (room `file`, end to end encrypted) carries it. Its field may be written only in TorFiles.kt.
                if (f.name != "TorFiles.kt" && Regex("""put\("tor",""").containsMatchIn(text)) add("${f.name}: onion address")
            }
        }
        assertEquals(emptyList<String>(), offenders)
        // That one place: exactly one `tor` field, in the `tor` message, and the service is one-time (its key thrown
        // away by Tor) and ends with the file. Sent only through FileLink's sealed signal (host.signal, room `file`).
        val tor = files.first { it.name == "TorFiles.kt" }.readText()
        assertEquals(1, Regex("""put\("tor",""").findAll(tor).count())
        assertTrue(tor.contains("""put("t", "tor")"""))
        val runner = files.first { it.name == "TorRunner.kt" }.readText()
        assertTrue(runner.contains("ADD_ONION NEW:ED25519-V3 Flags=DiscardPK"))
        assertFalse(runner.contains("Detach"))
        val link = files.first { it.name == "FileLink.kt" }.readText()
        assertTrue(link.contains("h.signal(o.peer, TorFiles.torMessage("))
        assertFalse(Regex("""torMessage\(""").findAll(files.filter { it.name != "TorFiles.kt" && it.name != "FileLink.kt" }.joinToString { it.readText() }).any())
    }

    /** A contact code that carries "o" (an onion address) still reads, the address ignored. */
    @Test fun readsA06CodeWithAnOnion() {
        val key = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val json = JSONObject().put("n", "Raven").put("k", key).put("p", "ab".repeat(32)).put("i", "tok").put("x", 1_800_000_000L)
            .put("o", "a".repeat(56)).toString()
        val code = "MUNINN1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val c = Contacts.parse(code)
        assertNotNull(c)
        assertEquals(key, c!!.chatKey)
        assertEquals("ab".repeat(32), c.mailbox)
        assertEquals("tok", c.invite)
    }
}

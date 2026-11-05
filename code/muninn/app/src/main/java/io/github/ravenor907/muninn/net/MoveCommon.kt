package io.github.ravenor907.muninn.net

import io.github.ravenor907.muninn.Muninn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Moving between Muninn and Huginn (cap x1, shared/docs/protocol.md 18.4): the two keep their files differently, so the
 * move carries one shared form, "@move.json". Between two Muninns the whole backup goes instead (everything, settings too).
 *
 *   {"v":1, "me":{"name","pub","priv","nostr"}, "contacts":[contact as Muninn keeps it], "ratchets":{key: ratchet},
 *    "chats":{key:[message]}, "groups":[{"s": state, "h":[older states]}], "gchats":{g:[{id,ch,f,n,x,m,t,st}]},
 *    "folders":{"folders":[{id,n,k,c}],"top":[...]}, "profile":{v,col,pic,st,cs}}
 *
 * Text messages, receipts, verified marks, nicknames, caps, the ratchets and my own profile (picture, colour, status; cap p1)
 * go across; photos, voice and video stay behind (their messages say "Photo" and so on).
 */
object MoveCommon {
    fun fromMuninn(a: Muninn): JSONObject {
        val v = a.vault
        fun file(name: String) = v.read(File(a.filesDir, name))
        val contacts = JSONArray().apply { a.contacts.all.value.forEach { put(ChatWire.contactToJson(it)) } }
        val ratchets = JSONObject()
        a.contacts.all.value.forEach { c -> v.read(File(File(a.filesDir, "ratchet"), ChatWire.fileName(c.chatKey)))?.let { ratchets.put(c.chatKey, JSONObject(it)) } }
        val chats = JSONObject()
        file("chats.json")?.let { t -> val o = JSONObject(t); o.keys().forEach { k -> chats.put(k, textOnly(o.getJSONArray(k))) } }
        val groups = JSONArray(); val gchats = JSONObject()
        file(Groups.FILE)?.let { t -> JSONObject(t).optJSONArray("groups")?.let { gs -> for (i in 0 until gs.length()) gs.getJSONObject(i).let {
            if (it.optString("gone").isEmpty()) groups.put(JSONObject().put("s", it.getJSONObject("s")).put("h", it.optJSONArray("h") ?: JSONArray()))
        } } }
        file(Groups.CHATS)?.let { t -> val o = JSONObject(t); o.keys().forEach { g -> gchats.put(g, JSONArray().apply {
            val l = o.getJSONArray(g)
            for (i in 0 until l.length()) l.getJSONObject(i).let { m ->
                val text = when { m.optInt("vo") > 0 -> "Voice message"; m.optInt("vd") > 0 -> "Video message"; m.optString("ph").isNotEmpty() -> "Photo"; else -> m.optString("x") }
                put(JSONObject().put("id", m.getString("id")).put("ch", m.optString("ch", Proto06.MAIN)).put("f", m.optString("f")).put("n", m.optString("n"))
                    .put("x", text).put("m", m.optBoolean("m")).put("t", m.optLong("t")).put("st", m.optString("st")))
            }
        }) } }
        val folders = file("folders.json")?.let { JSONObject(it) } ?: JSONObject()
        val me = JSONObject().put("name", a.identity.username).put("pub", a.identity.publicKey)
            .put("priv", ChatCrypto.b64(a.identity.keys.private.encoded)).put("nostr", a.identity.nostrSecret())
        val profile = file("profile/me.json")?.let { try { JSONObject(it) } catch (e: Exception) { null } }?.let { p ->
            JSONObject().put("v", p.optLong("v")).put("col", p.optString("col")).put("pic", p.optString("pic")).put("st", p.optString("st")).put("cs", p.optString("cs"))
        }
        return JSONObject().put("v", 1).put("me", me).put("contacts", contacts).put("ratchets", ratchets).put("chats", chats)
            .put("groups", groups).put("gchats", gchats).put("folders", folders).apply { if (profile != null) put("profile", profile) }
    }

    /** Text messages as they are; a photo, voice or video message becomes words. */
    private fun textOnly(a: JSONArray) = JSONArray().apply {
        for (i in 0 until a.length()) a.getJSONObject(i).let { m ->
            val words = when { m.optInt("voice") > 0 -> "Voice message"; m.optInt("vid") > 0 -> "Video message"; m.optString("fn").isNotEmpty() -> "File: " + m.optString("fn")
                m.optString("photo").isNotEmpty() -> "Photo"; else -> null }
            put(JSONObject().put("id", m.getString("id")).put("peer", m.optString("peer")).put("name", m.optString("name")).put("text", words ?: m.optString("text"))
                .put("mine", m.optBoolean("mine")).put("time", m.optLong("time")).put("failed", m.optBoolean("failed")).put("via", m.optString("via"))
                .put("state", m.optString("state")).put("photo", "").put("unsent", m.optString("unsent")).put("voice", 0))
        }
    }

    /** The shared form as Muninn's own files (and settings), for Backup.restore. */
    fun toMuninn(a: Muninn, o: JSONObject): List<Pair<String, ByteArray>> {
        val me = o.getJSONObject("me")
        ChatCrypto.privateFrom(me.getString("priv"))   // a real key, or nothing happens
        val out = ArrayList<Pair<String, ByteArray>>()
        fun add(name: String, text: String) { out += name to text.toByteArray() }
        add("secrets.json", JSONObject().put("chatPriv", me.getString("priv")).put("chatPub", me.getString("pub")).put("nostrSecret", me.getString("nostr")).toString())
        val contacts = o.optJSONArray("contacts") ?: JSONArray()
        add("contacts.json", JSONArray().apply { for (i in 0 until contacts.length()) put(ChatWire.contactToJson(ChatWire.contactFromJson(contacts.getJSONObject(i)))) }.toString())
        o.optJSONObject("ratchets")?.let { r -> r.keys().forEach { k ->
            try { Ratchet.Peer.fromJson(r.getJSONObject(k)); add("ratchet/" + ChatWire.fileName(k), r.getJSONObject(k).toString()) } catch (_: Exception) {}
        } }
        add("chats.json", (o.optJSONObject("chats") ?: JSONObject()).toString())
        val gs = o.optJSONArray("groups") ?: JSONArray()
        add(Groups.FILE, JSONObject().put("groups", JSONArray().apply { for (i in 0 until gs.length()) gs.getJSONObject(i).let {
            put(JSONObject().put("s", it.getJSONObject("s")).put("h", it.optJSONArray("h") ?: JSONArray()).put("gone", ""))
        } }).toString())
        val gc = o.optJSONObject("gchats") ?: JSONObject()
        add(Groups.CHATS, JSONObject().apply { gc.keys().forEach { g -> put(g, JSONArray().apply {
            val l = gc.getJSONArray(g)
            for (i in 0 until l.length()) l.getJSONObject(i).let { m -> put(JSONObject().put("id", m.getString("id")).put("g", g).put("ch", m.optString("ch", Proto06.MAIN))
                .put("f", m.optString("f")).put("n", m.optString("n")).put("x", m.optString("x")).put("m", m.optBoolean("m")).put("t", m.optLong("t"))
                .put("ph", "").put("vo", 0).put("u", "").put("st", m.optString("st")).put("to", JSONArray()).put("dl", JSONArray()).put("rd", JSONArray()).put("sn", true)) }
        }) } }.toString())
        o.optJSONObject("folders")?.let { add("folders.json", it.toString()) }
        // My profile picture, colour and status (cap p1), as Profile keeps them.
        o.optJSONObject("profile")?.let { p ->
            add("profile/me.json", JSONObject().put("k", "me").put("v", p.optLong("v")).put("col", p.optString("col")).put("pic", p.optString("pic"))
                .put("st", p.optString("st")).put("cs", p.optString("cs")).toString())
        }
        // Settings stay this device's, with the identity and name of the one that moved here.
        val prefs = JSONObject(Backup.prefsJson(a))
        prefs.remove("chatPub")
        prefs.put("username", JSONObject().put("t", "s").put("v", me.optString("name").take(24).ifBlank { a.identity.username }))
        add(Backup.PREFS_ENTRY, prefs.toString())
        return out
    }
}

package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cap l1, the full account on a linked companion (shared/docs/protocol.md section 15.1): the rules, kept apart from
 * the state so they can be tested off the phone (Huginn's Link::Acct is the same).
 *
 * The account is a set of items, each `{"k": kind, "id", "ts": ms, "v": {...}}` or, once deleted, a tombstone
 * `{"k", "id", "ts", "del": true}`. Each side keeps the last item it knows for every (k, id) and the later `ts` wins
 * (last writer wins, per item); a tie goes to the phone's copy. Tombstones are kept 30 days so a delete never comes back.
 */
object LinkAccount {
    const val CAP = "l1"
    /**
     * me {n, st, cs}; pic {pic: base64 JPEG} for "me" or a contact key; contact {n, nn, p, vf, bl}; group {n, role, gone, st}; folder {n, k};
     * mute {f: true (until turned back on) or u: when it ends, ms} for a contact key or "g:" + group id.
     */
    val KINDS = setOf("me", "pic", "contact", "group", "folder", "mute")
    /** A tombstone is kept this long, then dropped. */
    const val TOMB_MS = 30 * 86_400_000L
    /** An "acct" page over the home Wi-Fi, at most (the LAN takes up to 512 KB for room "link"). */
    const val PAGE_BYTES = 200 * 1024
    /** A change frame may use the internet when it's at most this big and carries no picture. */
    const val LIVE_BYTES = 16 * 1024
    const val ID_MAX = 200

    data class Item(val k: String, val id: String, val ts: Long, val del: Boolean = false, val v: JSONObject? = null) {
        val key: String get() = keyOf(k, id)
        fun json(): JSONObject = JSONObject().put("k", k).put("id", id).put("ts", ts).apply { if (del) put("del", true) else put("v", v) }
    }

    fun keyOf(k: String, id: String) = "$k|$id"

    /** One item as it came, or null when it isn't one. */
    fun read(o: JSONObject?): Item? {
        if (o == null) return null
        val k = o.optString("k")
        val id = o.optString("id")
        val ts = o.optLong("ts", 0L)
        if (k !in KINDS || id.isEmpty() || id.length > ID_MAX || ts <= 0L) return null
        if (o.optBoolean("del")) return Item(k, id, ts, true)
        val v = o.optJSONObject("v") ?: return null
        return Item(k, id, ts, false, v)
    }

    fun readAll(a: JSONArray?): List<Item> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { read(a.optJSONObject(it)) }

    /** The same value, written the same way every time (keys sorted), so two copies compare as text. */
    fun canon(x: Any?): String = when (x) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> x.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(x.opt(it)) }
        is JSONArray -> (0 until x.length()).joinToString(",", "[", "]") { canon(x.opt(it)) }
        is String -> JSONObject.quote(x)
        is Boolean -> x.toString()
        is Number -> if (x.toDouble() == Math.floor(x.toDouble()) && !x.toDouble().isInfinite()) x.toLong().toString() else x.toString()
        else -> JSONObject.quote(x.toString())
    }

    /** [incoming] wins over [local]: a later ts, or the same ts and [winTies] (the phone's copy). */
    fun wins(local: Item?, incoming: Item, winTies: Boolean): Boolean =
        local == null || incoming.ts > local.ts || (incoming.ts == local.ts && winTies && !same(local, incoming))

    private fun same(a: Item, b: Item) = a.del == b.del && (a.del || canon(a.v) == canon(b.v))

    /** Merges [incoming] into [local]: the new set, and the items that won (to apply here). */
    fun merge(local: Map<String, Item>, incoming: List<Item>, winTies: Boolean): Pair<Map<String, Item>, List<Item>> {
        val out = LinkedHashMap(local)
        val won = ArrayList<Item>()
        for (i in incoming) {
            if (!wins(out[i.key], i, winTies)) continue
            out[i.key] = i
            won.removeAll { it.key == i.key }
            won += i
        }
        return out to won
    }

    /**
     * The phone's side: what it has now ([current], key to value) against what it last said ([replica]). Changed or new
     * values get a new ts, gone ones a tombstone; returns the new set and those items (to send).
     */
    fun observe(replica: Map<String, Item>, current: Map<String, JSONObject>, now: Long): Pair<Map<String, Item>, List<Item>> {
        val out = LinkedHashMap(replica)
        val changed = ArrayList<Item>()
        for ((key, v) in current) {
            val old = replica[key]
            if (old != null && !old.del && canon(old.v) == canon(v)) continue
            val k = key.substringBefore('|')
            val id = key.substringAfter('|')
            if (k !in KINDS || id.isEmpty()) continue
            val i = Item(k, id, maxOf(now, (old?.ts ?: 0L) + 1), false, v)
            out[key] = i
            changed += i
        }
        for ((key, old) in replica) {
            if (old.del || key in current) continue
            val i = Item(old.k, old.id, maxOf(now, old.ts + 1), true)
            out[key] = i
            changed += i
        }
        return out to changed
    }

    /** Tombstones older than 30 days go. */
    fun prune(set: Map<String, Item>, now: Long): Map<String, Item> = set.filterValues { !it.del || now - it.ts <= TOMB_MS }

    /** The companion's first full set from a phone it was just linked to: the phone's account replaces everything here. */
    fun adopt(incoming: List<Item>): Map<String, Item> = LinkedHashMap<String, Item>().apply { incoming.forEach { put(it.key, it) } }

    fun hasPic(items: List<JSONObject>) = items.any { it.optString("k") == "pic" && !it.optBoolean("del") }

    /** Items in pages of at most [max] bytes of UTF-8 JSON each (one bigger item goes alone). */
    fun pages(items: List<JSONObject>, max: Int = PAGE_BYTES): List<List<JSONObject>> {
        val out = ArrayList<List<JSONObject>>()
        var cur = ArrayList<JSONObject>()
        var size = 2
        for (o in items) {
            val n = o.toString().toByteArray(Charsets.UTF_8).size + 1
            if (cur.isNotEmpty() && size + n > max) { out += cur; cur = ArrayList(); size = 2 }
            cur += o; size += n
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** An "acct" frame may use the internet: a change (not the full set), no picture, small. */
    fun internetOk(o: JSONObject): Boolean {
        if (o.optString("t") != "acct" || o.optBoolean("full")) return false
        val a = o.optJSONArray("items") ?: return false
        if (hasPic((0 until a.length()).mapNotNull { a.optJSONObject(it) })) return false
        return o.toString().toByteArray(Charsets.UTF_8).size <= LIVE_BYTES
    }

    /** "l1" is among the space-separated tokens of a frame's "caps". */
    fun says(caps: String) = caps.split(' ').any { it == CAP }

    fun save(set: Map<String, Item>): String = JSONObject().put("items", JSONArray().apply { set.values.forEach { put(it.json()) } }).toString()
    fun load(text: String?): Map<String, Item> = try {
        if (text == null) emptyMap() else LinkedHashMap<String, Item>().apply { readAll(JSONObject(text).optJSONArray("items")).forEach { put(it.key, it) } }
    } catch (e: Exception) { emptyMap() }
}

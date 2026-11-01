package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.AppStorage
import io.github.ravenor907.muninn.screens.AppStorageOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * App storage on a Kids' TV, from a parent's phone (cap k10, protocol.md 7.16): a Kids' TV has no Settings, so a parent
 * in Labs mode sees the TV's [AppStorage] list under Kids' TV › Storage and asks the TV to delete or move files. All in
 * room tvconfig, sealed pairwise like the rest of section 7; the TV takes them only from a current parent, and only for
 * ids on [AppStorage]'s allowlist.
 */
object TvFiles {
    const val CAP = "k10"
    /** At most this many files in one list (newest first); "count" and "total" cover all of them. */
    const val MAX = 40
    const val MAX_NAME = 80
    const val MAX_FOLDER = 40
    private const val MAX_INT = (1L shl 53) - 1

    /** What a parent's phone knows of a TV's files. [move]: the TV can move received files to its Downloads. */
    data class Listing(val items: List<AppStorage.Item>, val count: Int, val total: Long, val move: Boolean)

    // ---- messages
    /** Parent to TV: "send me your files". */
    fun askMessage(): String = JSONObject().put("t", "files").toString()

    /** Parent to TV: delete these. */
    fun deleteMessage(ids: List<String>): String = JSONObject().put("t", "filedel").put("ids", JSONArray(ids.take(MAX))).toString()

    /** Parent to TV: delete every file of [kind] (received or cache only). */
    fun deleteAllMessage(kind: AppStorage.Kind): String = JSONObject().put("t", "filedel").put("all", kind.id).toString()

    /** Parent to TV: move these received files to the TV's Downloads. */
    fun moveMessage(ids: List<String>): String = JSONObject().put("t", "filemove").put("ids", JSONArray(ids.take(MAX))).toString()

    /** TV to parent: its list. */
    fun listMessage(items: List<AppStorage.Item>, move: Boolean): String {
        val a = JSONArray()
        for (i in items.take(MAX)) a.put(JSONObject().put("id", i.id).put("n", i.name.take(MAX_NAME)).put("s", i.size.coerceIn(0L, MAX_INT))
            .put("at", i.at.coerceIn(0L, MAX_INT)).put("k", i.kind.id).put("w", i.folder.take(MAX_FOLDER)))
        return JSONObject().put("t", "filelist").put("files", a).put("count", items.size)
            .put("total", AppStorage.total(items).coerceIn(0L, MAX_INT)).put("mv", move).toString()
    }

    // ---- reading them (malformed: null, and ignored)
    private fun text(v: Any?, max: Int): String? = (v as? String)?.takeIf { it.isNotEmpty() && it.length <= max && it.none { c -> c < ' ' || c == '\u007f' } }
    private fun num(v: Any?): Long? = (v as? Number)?.toLong()?.takeIf { (v is Int || v is Long) && it in 0..MAX_INT }

    fun parseList(o: JSONObject): Listing? {
        if (o.opt("t") != "filelist") return null
        val a = o.opt("files") as? JSONArray ?: return null
        if (a.length() > MAX) return null
        val items = (0 until a.length()).map { n ->
            val f = a.opt(n) as? JSONObject ?: return null
            val id = text(f.opt("id"), AppStorage.MAX_ID)?.takeIf { AppStorage.allowed(it) } ?: return null
            val kind = AppStorage.Kind.of(f.opt("k") as? String) ?: return null
            // The kind is the allowlist's, not whatever the TV said.
            if (AppStorage.spotOf(id)?.kind != kind) return null
            AppStorage.Item(id, text(f.opt("n"), MAX_NAME) ?: return null, num(f.opt("s")) ?: return null, num(f.opt("at")) ?: return null,
                kind, text(f.opt("w"), MAX_FOLDER) ?: return null)
        }
        val count = num(o.opt("count"))?.takeIf { it >= items.size && it <= AppStorage.MAX_ITEMS } ?: return null
        val total = num(o.opt("total")) ?: return null
        val move = o.opt("mv") as? Boolean ?: return null
        return Listing(items, count.toInt(), total, move)
    }

    /** The ids in a "filedel" or "filemove", each on the allowlist, or null. */
    fun parseIds(o: JSONObject): List<String>? {
        val a = o.opt("ids") as? JSONArray ?: return null
        if (a.length() !in 1..MAX) return null
        return (0 until a.length()).map { n -> (a.opt(n) as? String)?.takeIf { AppStorage.allowed(it) } ?: return null }.distinct()
    }

    /** The kind in a "filedel" with "all": received or cache, nothing else. */
    fun parseAll(o: JSONObject): AppStorage.Kind? =
        AppStorage.Kind.of(o.opt("all") as? String)?.takeIf { it == AppStorage.Kind.RECEIVED || it == AppStorage.Kind.CACHE }

    // ---- the TV's side
    /** A parent's "files", "filedel" or "filemove", done on the TV (off the main thread): the list to send back. */
    fun serve(app: Muninn, o: JSONObject): String? {
        when (o.optString("t")) {
            "files" -> {}
            "filedel" -> {
                val all = if (o.has("all")) parseAll(o) ?: return null else null
                if (all != null) AppStorageOps.deleteKind(app, all)
                else (parseIds(o) ?: return null).forEach { AppStorageOps.delete(app, it) }
            }
            "filemove" -> (parseIds(o) ?: return null).forEach { AppStorageOps.move(app, it) }
            else -> return null
        }
        return listMessage(AppStorageOps.scan(app), AppStorageOps.canMove(app))
    }

    // ---- the parent's side
    private val _lists = MutableStateFlow<Map<String, Listing>>(emptyMap())
    /** Per TV key, its last list. */
    val lists: StateFlow<Map<String, Listing>> = _lists.asStateFlow()

    /** A TV's "filelist" (only from a TV this phone looks after). */
    fun take(tv: String, o: JSONObject): Boolean {
        val l = parseList(o) ?: return true
        _lists.value = _lists.value + (tv to l)
        return true
    }

    fun forget(tv: String) { _lists.value = _lists.value - tv }
}

package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Labs reports that came to this phone: only ever used when its chat key is the Labs key ([LabsWire.KEY]).
 * Kept encrypted in the vault (labs.json, screenshots in labs/), for 30 days.
 */
class LabsInbox(context: Context, private val vault: Vault) {
    data class Report(val id: String, val from: String, val name: String, val mailbox: String?, val app: String, val version: String,
                      val os: String, val model: String, val text: String, val at: Long, val shot: String)

    private val store = File(context.filesDir, "labs.json")
    private val dir = File(context.filesDir, "labs")
    private val _reports = MutableStateFlow<List<Report>>(emptyList())
    val reports: StateFlow<List<Report>> = _reports.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    /** Screenshot pieces on their way: id to (sender, pieces). */
    private class Pieces(val from: String, val parts: Array<ByteArray?>, val started: Long)
    private val incoming = ConcurrentHashMap<String, Pieces>()
    /** Screenshots that came before their report: id to (sender, JPEG, when). In memory only, a few at most. */
    private val early = ConcurrentHashMap<String, Triple<String, ByteArray, Long>>()

    fun load() = synchronized(lock) {
        if (loaded) return@synchronized
        loaded = true
        val list = try {
            vault.read(store)?.let { t ->
                val a = JSONArray(t)
                (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Report(it.getString("id"), it.getString("k"), it.optString("n"), it.optString("p").ifEmpty { null }, it.optString("a"),
                        it.optString("v"), it.optString("os"), it.optString("m"), it.optString("t"), it.optLong("at"), it.optString("s"))
                }
            }.orEmpty()
        } catch (e: Exception) { emptyList() }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val keep = list.filter { it.at >= cutoff }
        list.filter { it.at < cutoff }.forEach { shotFile(it.shot)?.delete() }
        _reports.value = keep
        if (keep.size != list.size) save()
    }

    private fun save() {
        val a = JSONArray()
        _reports.value.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("k", r.from).put("n", r.name).put("p", r.mailbox ?: "").put("a", r.app).put("v", r.version)
                .put("os", r.os).put("m", r.model).put("t", r.text).put("at", r.at).put("s", r.shot))
        }
        try { vault.write(store, a.toString()) } catch (_: Exception) {}
    }

    private fun shotFile(id: String): File? = if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' }) null else File(dir, id)

    fun screenshot(r: Report): ByteArray? = shotFile(r.shot)?.let { if (it.exists()) vault.readBytes(it) else null }

    /** May [from] send another report now (pieces count too)? */
    fun allowed(from: String, now: Long): Boolean {
        load()
        return LabsWire.allow(_reports.value.filter { it.from == from }.map { it.at }, now)
    }

    fun known(id: String) = _reports.value.any { it.id == id }

    /** A report arrived (already opened and checked): 200, or 429 past the day's limit. */
    fun add(r: Report): Int = synchronized(lock) {
        load()
        if (known(r.id)) return@synchronized 200
        if (!allowed(r.from, r.at)) return@synchronized 429
        // Its screenshot: the one it names ("s"), else (a report that names none) one from the same sender just before.
        val shotId = r.shot.ifEmpty { early.entries.filter { it.value.first == r.from && r.at - it.value.third in 0..MERGE_MS }.maxByOrNull { it.value.third }?.key ?: "" }
        early.remove(shotId)?.takeIf { it.first == r.from }?.let { (_, jpeg, _) -> shotFile(shotId)?.let { dir.mkdirs(); vault.writeBytes(it, jpeg) } }
        _reports.value = listOf(r.copy(shot = shotId)) + _reports.value
        save()
        200
    }

    /** One opened piece of a screenshot: 200 when taken. */
    fun piece(from: String, id: String, i: Int, n: Int, part: ByteArray, now: Long): Int {
        if (n < 1 || n > LabsWire.MAX_SHOT_PIECES || i !in 0 until n || shotFile(id) == null) return 400
        if (_reports.value.any { it.shot == id && it.from == from && shotFile(id)?.exists() == true }) return 200
        incoming.entries.removeIf { now - it.value.started > 15 * 60_000 }
        if (incoming.size >= 10 && !incoming.containsKey(id)) return 429
        val p = incoming.getOrPut(id) { Pieces(from, arrayOfNulls(n), now) }
        if (p.from != from || p.parts.size != n) return 400
        synchronized(p) {
            p.parts[i] = part
            if (p.parts.any { it == null }) return 200
            incoming.remove(id)
        }
        val jpeg = Photos.join(p.parts.map { it!! }) ?: return 400
        synchronized(lock) {
            load()
            // Its report: the one that names it, else one from the same sender just before that names none.
            val named = _reports.value.any { it.shot == id && it.from == from }
            val unnamed = if (named) null else _reports.value.firstOrNull { it.from == from && it.shot.isEmpty() && now - it.at in 0..MERGE_MS }
            if (named || unnamed != null) {
                shotFile(id)?.let { dir.mkdirs(); vault.writeBytes(it, jpeg) }
                if (unnamed != null) { _reports.value = _reports.value.map { if (it.id == unnamed.id) it.copy(shot = id) else it }; save() }
            } else { if (early.size >= 10) early.clear(); early[id] = Triple(from, jpeg, now) }
        }
        return 200
    }

    fun delete(id: String) = synchronized(lock) {
        val r = _reports.value.firstOrNull { it.id == id } ?: return@synchronized
        shotFile(r.shot)?.delete()
        _reports.value = _reports.value.filterNot { it.id == id }
        save()
    }

    /** Erase everything: every report and screenshot. */
    fun clear() = synchronized(lock) {
        _reports.value = emptyList(); early.clear(); incoming.clear()
        store.delete(); dir.deleteRecursively()
    }

    companion object {
        const val KEEP_MS = 30 * 86_400_000L
        /** A screenshot and a report that names none go together when they came this close (from the same sender). */
        const val MERGE_MS = 15 * 60_000L
    }
}

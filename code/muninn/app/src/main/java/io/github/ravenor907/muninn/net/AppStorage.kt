package io.github.ravenor907.muninn.net

import java.io.File

/**
 * Labs › App storage: files Muninn keeps in its own private folders that can go without losing anything that
 * matters: received files left from before they went to Downloads, copies made for sending, update downloads and
 * caches. A hard allowlist: only the folders in [SPOTS] are ever listed, moved or deleted from. The vault, keys,
 * chats, contacts, settings and everything else in Muninn's folders stay hidden and can't be named.
 *
 * A file is named by an id: its root ('c' the cache folder, 'f' the files folder, 'e' Muninn's folder on shared
 * storage), a slash, then its path there ("c/apps/12/game.apk"). Only folder names are shown, never device paths.
 */
object AppStorage {
    enum class Kind(val id: String, val word: String) {
        RECEIVED("received", "Received"), SENT("sent", "Sent copy"), UPDATE("update", "Update"), CACHE("cache", "Cache");
        companion object { fun of(id: String?): Kind? = entries.firstOrNull { it.id == id } }
    }

    /**
     * One folder that is safe to empty: [dir] in [root], or with [dir] empty, files right in [root] whose names
     * start with [prefix]. [folder] is what the screen calls it.
     */
    data class Spot(val root: Char, val dir: String, val kind: Kind, val folder: String, val prefix: String = "")

    val SPOTS: List<Spot> = listOf(
        // Apps sent to a Kids' TV, kept here before received files went to Downloads.
        Spot('c', "apps", Kind.RECEIVED, "apps"),
        // Received files on Android 8 and 9 while the storage permission is off.
        Spot('e', "Download", Kind.RECEIVED, "Muninn Downloads"),
        Spot('f', "Download", Kind.RECEIVED, "Muninn Downloads"),
        // Copies of files picked or shared for sending, and of apps sent from here.
        Spot('c', "outgoing", Kind.SENT, "outgoing"),
        // Share the app: a copy of Muninn's own installer.
        Spot('c', "app", Kind.SENT, "app"),
        // Muninn's own update downloads.
        Spot('c', "updates", Kind.UPDATE, "updates"),
        // Files on their way in or out over the internet, and camera shots before sending.
        Spot('c', "files", Kind.CACHE, "files"),
        Spot('c', "camera", Kind.CACHE, "camera"),
        // An app's details read on a Kids' TV.
        Spot('c', "", Kind.CACHE, "cache", prefix = "app-info-"),
    )

    /** One file: [folder] is a plain folder name, [at] when it last changed (ms). */
    data class Item(val id: String, val name: String, val size: Long, val at: Long, val kind: Kind, val folder: String)

    const val MAX_ID = 400
    /** Changed in the last minute: still being written (a download, a file on its way), so not listed. */
    const val BUSY_MS = 60_000L
    const val MAX_ITEMS = 2000
    private const val MAX_DEPTH = 8

    /** The spot [id] is in, or null when it doesn't name a file inside one of [SPOTS]. Checks the name only. */
    fun spotOf(id: String): Spot? {
        if (id.length !in 3..MAX_ID || id[1] != '/') return null
        if (id.any { it == '\\' || it < ' ' || it == '\u007f' || it == ':' }) return null
        val segs = id.substring(2).split('/')
        if (segs.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segs.size > MAX_DEPTH + 1) return null
        val root = id[0]
        return SPOTS.firstOrNull { s ->
            s.root == root && if (s.dir.isEmpty()) segs.size == 1 && segs[0].startsWith(s.prefix) && segs[0].length > s.prefix.length
                else segs.size >= 2 && segs[0] == s.dir
        }
    }

    fun allowed(id: String): Boolean = spotOf(id) != null

    /** The file [id] names under [roots], or null: not on the allowlist, not a plain file, or reached through a link. */
    fun resolve(roots: Map<Char, File>, id: String): File? {
        spotOf(id) ?: return null
        val root = roots[id[0]] ?: return null
        return try {
            val base = root.canonicalFile
            val want = File(base, id.substring(2))
            val real = want.canonicalFile
            // A link anywhere along the way would point somewhere else: refused.
            if (real.path != want.path || !real.isFile) null else real
        } catch (e: Exception) { null }
    }

    /** Everything on the allowlist under [roots], newest first. */
    fun scan(roots: Map<Char, File>, now: Long): List<Item> {
        val out = ArrayList<Item>()
        for (s in SPOTS) {
            val root = roots[s.root] ?: continue
            try {
                if (s.dir.isEmpty()) root.listFiles()?.forEach { f -> if (!link(f) && f.isFile && f.name.startsWith(s.prefix)) add(out, root, f, s, now) }
                else walk(File(root, s.dir), root, s, now, out, 0)
            } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.at }
    }

    private fun walk(dir: File, root: File, s: Spot, now: Long, out: MutableList<Item>, depth: Int) {
        if (depth >= MAX_DEPTH || out.size >= MAX_ITEMS || link(dir)) return
        dir.listFiles()?.forEach { f ->
            if (link(f)) return@forEach
            if (f.isDirectory) walk(f, root, s, now, out, depth + 1) else if (f.isFile) add(out, root, f, s, now)
        }
    }

    private fun add(out: MutableList<Item>, root: File, f: File, s: Spot, now: Long) {
        if (out.size >= MAX_ITEMS) return
        val at = f.lastModified()
        if (now - at in 0 until BUSY_MS) return
        val rel = f.path.removePrefix(root.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
        val id = "${s.root}/$rel"
        if (spotOf(id) != s) return
        out += Item(id, f.name, f.length(), at, s.kind, s.folder)
    }

    private fun link(f: File): Boolean = try { java.nio.file.Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { true }

    /** Deletes [id]; folders it leaves empty inside its spot go too (the spot's own folder stays). */
    fun delete(roots: Map<Char, File>, id: String): Boolean {
        val f = resolve(roots, id) ?: return false
        if (!f.delete()) return false
        val s = spotOf(id) ?: return true
        if (s.dir.isEmpty()) return true
        val top = try { File(roots.getValue(id[0]).canonicalFile, s.dir) } catch (e: Exception) { return true }
        var d = f.parentFile
        while (d != null && d.path.startsWith(top.path + File.separator)) {
            if (d.list()?.isEmpty() != true || !d.delete()) break
            d = d.parentFile
        }
        return true
    }

    /** Deletes every listed file of [kind]: how many went. */
    fun deleteKind(roots: Map<Char, File>, kind: Kind, now: Long): Int =
        scan(roots, now).filter { it.kind == kind }.count { delete(roots, it.id) }

    fun total(items: List<Item>): Long = items.sumOf { it.size }
}

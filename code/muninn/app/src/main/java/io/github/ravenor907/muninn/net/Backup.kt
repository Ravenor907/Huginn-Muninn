package io.github.ravenor907.muninn.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.ravenor907.muninn.Muninn
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Settings › Devices and backup and first setup's Restore from a backup. The file format is [BackupFormat] (shared/docs/backup.md);
 * this is the phone side: what goes in, where it's written, and putting it back. Call these off the main thread.
 */
object Backup {
    /** Muninn's settings and public identity ride along as one entry with a name no file can have. */
    const val PREFS_ENTRY = "@prefs.json"
    private const val PREFS = "muninn"
    /** Settings that belong to this phone, not to the person: never copied over. */
    private val phoneOnly = setOf("fingerprint", "lockFails", "lockAt", "lockWait", "lockBoot", "lockErase", "unlockRecall", "strongerAsked", "receiveFolder", "recent", "oldToolsGone",
        RESTORED_AT, RESTORE_ANNOUNCE, "lockSeq", "lockLeft", "lockWallEnd", "lockChecking", ERASE_NOTE,
        "preRelease", "labs", "meterMonth", "meterBytes", "meterShared", "meterWaiting")
    /**
     * "Erase after too many tries" belongs to the phone and never comes along, so a backup or move made with it on says
     * so, and the device it's put back on says once that it's off there ([ERASE_NOTE] then set; MainActivity shows it).
     */
    const val ERASE_NOTE = "eraseNote"
    /** When a backup was last restored here (ms): for 7 days a relay envelope for a day key not here yet waits (see Chat). */
    const val RESTORED_AT = "restoredAt"
    /** A backup was restored: a new day key goes to every e1 contact once the chats open (see Chat). */
    const val RESTORE_ANNOUNCE = "restoreAnnounce"

    /** Daily Hávamál's own imported translation: kept on this device only, so never in a backup or a move. */
    private const val OWN_VERSES = io.github.ravenor907.muninn.screens.DailyHav.FILE

    /** The name the Save picker suggests. */
    fun fileName(): String = "Muninn backup ${java.time.LocalDate.now()}.${BackupFormat.EXT}"

    /** What's here now, in bytes: everything, and the part that is files and pictures. */
    class Sizes(val all: Long, val media: Long)
    fun sizes(vault: Vault): Sizes {
        var all = 0L; var media = 0L
        vault.sealedFiles().forEach { (name, f) -> val n = f.length(); all += n; if (BackupFormat.isMedia(name)) media += n }
        return Sizes(all, media)
    }

    /** A backup just written: where (as the phone shows it), how big, and whether files and pictures are in it. */
    class Saved(val where: String, val bytes: Long, val files: Boolean)

    /** Writes the backup to [uri] (from the Save picker). Files and pictures go in only when [withFiles]. */
    fun create(app: Muninn, uri: Uri, password: String, withFiles: Boolean, progress: (Float) -> Unit): Saved {
        val vault = app.vault
        val created = java.time.Instant.now().toString()
        val entries = vault.sealedFiles().filter { it.first != "move-out.bin" && it.first != OWN_VERSES && (withFiles || !BackupFormat.isMedia(it.first)) }
            .map { (name, f) -> BackupFormat.Entry(name) { vault.readBytes(f) } } +
            BackupFormat.Entry(PREFS_ENTRY) { prefsJson(app).toByteArray() } +
            BackupFormat.Entry(BackupFormat.SHARED) { MoveCommon.fromMuninn(app).toString().toByteArray() } +
            BackupFormat.Entry(BackupFormat.ABOUT) { BackupFormat.about("muninn", app.identity.deviceName, created, withFiles) }
        val raw = app.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Couldn't write there.")
        val out = Counting(raw)
        BackupFormat.write(out, password, entries, created = created, progress = progress)
        return Saved(where(app, uri), out.count, withFiles)
    }

    /** Everything a backup holds, read now (moving to another device, cap x1). */
    fun entries(context: Context, vault: Vault): List<Pair<String, ByteArray>> =
        vault.sealedFiles().filter { it.first != "move-out.bin" && it.first != OWN_VERSES }.mapNotNull { (name, f) -> vault.readBytes(f)?.let { name to it } } +
            (PREFS_ENTRY to prefsJson(context).toByteArray())

    private class Counting(o: OutputStream) : FilterOutputStream(o) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /** "Download/Muninn backup 2026-11-02.hmbak" when the picker says the folder, else just the name. */
    fun where(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null } ?: uri.lastPathSegment ?: "the file you chose"
        val id = try { DocumentsContract.getDocumentId(uri) } catch (e: Exception) { "" }
        // The phone's own storage: "primary:Download/name"; a raw path: "raw:/storage/emulated/0/Download/name".
        val path = when {
            id.startsWith("raw:") -> id.removePrefix("raw:").substringAfter("/storage/emulated/0/")
            id.contains(':') && id.substringAfter(':').contains('/') -> id.substringAfter(':')
            uri.authority == "com.android.providers.downloads.documents" -> "Download/$name"
            else -> ""
        }
        return path.ifEmpty { name }
    }

    /** Reads and checks a backup. Throws [BackupFormat.WrongPassword] or [BackupFormat.NotABackup]. */
    fun open(context: Context, uri: Uri, password: String): BackupFormat.Opened {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupFormat.NotABackup("Couldn't open that file.")
        return BackupFormat.open(bytes, password, "muninn")
    }

    /** Readable date for "Made ...". */
    fun madeOn(opened: BackupFormat.Opened): String = madeOn(BackupFormat.summary(opened).created)
    fun madeOn(created: String): String = try {
        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .format(java.time.Instant.parse(created).atZone(java.time.ZoneId.systemDefault()))
    } catch (e: Exception) { created }

    /**
     * Muninn's own files from a backup: as they are when Muninn made it, made from the shared form when Huginn did
     * (text, contacts, groups, folders and profile come across; pictures and files stay behind).
     */
    fun prepare(app: Muninn, o: BackupFormat.Opened): BackupFormat.Opened {
        if (BackupFormat.summary(o).app == "muninn") return o
        val shared = o.files.firstOrNull { it.first == BackupFormat.SHARED }?.second
            ?: throw BackupFormat.NotABackup("This backup has nothing Muninn can read.")
        return BackupFormat.Opened(o.header, MoveCommon.toMuninn(app, JSONObject(String(shared, Charsets.UTF_8))))
    }

    /**
     * Settings › Devices and backup › Restore: a copy of what's here goes into a "kept-" folder first, then [restore]. Returns
     * the kept folder's name. Then call [restart].
     */
    fun restoreKeeping(app: Muninn, o: BackupFormat.Opened): String {
        val files = prepare(app, o)
        val kept = app.vault.keepCopy()
        restore(app, app.vault, files)
        return kept.name
    }

    /**
     * Replaces everything here with [opened]: files are staged in the vault (swapped in on the next
     * open), settings and identity are written now. Then call [restart].
     */
    fun restore(context: Context, vault: Vault, opened: BackupFormat.Opened) {
        vault.stageRestore(opened.files.filter { !it.first.startsWith("@") && it.first != "huginn-settings.dat" })
        opened.files.firstOrNull { it.first == PREFS_ENTRY }?.let { applyPrefs(context, String(it.second)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(RESTORED_AT, System.currentTimeMillis()).putBoolean(RESTORE_ANNOUNCE, true).commit()
    }

    /** Closes Muninn and opens it again, so everything starts from the restored data. */
    fun restart(context: Context) {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let {
            context.startActivity(Intent.makeRestartActivityTask(it))
        }
        Runtime.getRuntime().exit(0)
    }

    internal fun prefsJson(context: Context): String {
        val o = JSONObject()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (k in phoneOnly || v == null) return@forEach
            val t = when (v) {
                is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"
                is Set<*> -> "set"; else -> return@forEach
            }
            o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
        }
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lockErase", false)) o.put(ERASE_NOTE, JSONObject().put("t", "b").put("v", true))
        return o.toString()
    }

    private fun applyPrefs(context: Context, json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = prefs.edit()
        prefs.all.keys.filter { it !in phoneOnly }.forEach { e.remove(it) }
        // Erase was on where this came from: said once here (it stays off until turned on here).
        if (o.optJSONObject(ERASE_NOTE)?.optBoolean("v") == true) e.putBoolean(ERASE_NOTE, true)
        o.keys().forEach { k ->
            if (k in phoneOnly) return@forEach
            val x = o.optJSONObject(k) ?: return@forEach
            when (x.optString("t")) {
                "s" -> e.putString(k, x.optString("v"))
                "b" -> e.putBoolean(k, x.optBoolean("v"))
                "i" -> e.putInt(k, x.optInt("v"))
                "l" -> e.putLong(k, x.optLong("v"))
                "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                "set" -> x.optJSONArray("v")?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
            }
        }
        e.commit()
    }
}

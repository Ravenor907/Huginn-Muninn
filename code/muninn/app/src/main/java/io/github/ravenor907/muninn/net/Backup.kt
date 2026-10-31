package io.github.ravenor907.muninn.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings › Back up chats / Restore from backup. The file format is [BackupFormat]; this is the
 * phone side: what goes in, where it's written, and putting it back. Call these off the main thread.
 */
object Backup {
    /** Muninn's settings and public identity ride along as one entry with a name no file can have. */
    const val PREFS_ENTRY = "@prefs.json"
    private const val PREFS = "muninn"
    /** Settings that belong to this phone, not to the person: never copied over. */
    private val phoneOnly = setOf("fingerprint", "lockFails", "lockUntil", "lockAt", "lockWait", "lockBoot", "strongerAsked", "receiveFolder", "recent", "oldToolsGone",
        RESTORED_AT, RESTORE_ANNOUNCE)
    /** When a backup was last restored here (ms): for 7 days a relay envelope for a day key not here yet waits (see Chat). */
    const val RESTORED_AT = "restoredAt"
    /** A backup was restored: a new day key goes to every e1 contact once the chats open (see Chat). */
    const val RESTORE_ANNOUNCE = "restoreAnnounce"

    fun fileName(): String = "muninn-backup-${java.time.LocalDate.now()}.mbk"

    /** Writes the backup to [uri]. Returns how many items went in. */
    fun create(context: Context, vault: Vault, uri: Uri, password: String, progress: (Float) -> Unit): Int {
        val entries = vault.sealedFiles().map { (name, f) -> BackupFormat.Entry(name) { vault.readBytes(f) } } +
            BackupFormat.Entry(PREFS_ENTRY) { prefsJson(context).toByteArray() }
        val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Couldn't write there.")
        return BackupFormat.write(out, password, entries, progress = progress)
    }

    /** Everything a backup holds, read now (moving to another device, cap x1). */
    fun entries(context: Context, vault: Vault): List<Pair<String, ByteArray>> =
        vault.sealedFiles().filter { it.first != "move-out.bin" }.mapNotNull { (name, f) -> vault.readBytes(f)?.let { name to it } } +
            (PREFS_ENTRY to prefsJson(context).toByteArray())

    /** Reads and checks a backup. Throws [BackupFormat.WrongPassword] or [BackupFormat.NotABackup]. */
    fun open(context: Context, uri: Uri, password: String): BackupFormat.Opened {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupFormat.NotABackup("Couldn't open that file.")
        return BackupFormat.open(bytes, password, "muninn")
    }

    /** Readable date for "Backup from ...". */
    fun madeOn(opened: BackupFormat.Opened): String = try {
        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .format(java.time.Instant.parse(opened.header.created).atZone(java.time.ZoneId.systemDefault()))
    } catch (e: Exception) { opened.header.created }

    /**
     * Replaces everything here with [opened]: files are staged in the vault (swapped in on the next
     * open), settings and identity are written now. Then call [restart].
     */
    fun restore(context: Context, vault: Vault, opened: BackupFormat.Opened) {
        vault.stageRestore(opened.files.filter { it.first != PREFS_ENTRY })
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
        return o.toString()
    }

    private fun applyPrefs(context: Context, json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = prefs.edit()
        prefs.all.keys.filter { it !in phoneOnly }.forEach { e.remove(it) }
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

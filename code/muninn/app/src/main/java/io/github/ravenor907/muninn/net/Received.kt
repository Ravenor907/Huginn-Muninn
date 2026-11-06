package io.github.ravenor907.muninn.net

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Where received files go: the phone's Downloads folder (Download/Muninn/<chat>), or a folder picked in Settings ›
 * Save files to (Android's folder picker; <folder>/<chat> there). Android 10 and newer save through MediaStore,
 * so Muninn needs no storage permission. Android 8 and 9 write into the folder with the storage
 * permission, or into Muninn's own Downloads folder if it was refused.
 */
object Received {
    /** One file being saved. Write to [out], then [finish] it, or [discard] it if it didn't arrive whole. */
    class Target internal constructor(
        /** The name it got (a clashing name gets " (2)" or similar). */
        val name: String,
        val out: OutputStream,
        private val context: Context,
        /** Its MediaStore entry (Android 10 and newer). */
        val uri: Uri?,
        /** Where it's written, when that's a plain file (not MediaStore). */
        val file: File?,
        /** [uri] is a document in the picked folder (not MediaStore). */
        private val doc: Boolean = false,
    ) {
        fun finish() {
            try { out.close() } catch (_: Exception) {}
            if (uri != null && !doc && Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                try { context.contentResolver.update(uri, v, null, null) } catch (_: Exception) {}
            }
        }

        fun discard() {
            try { out.close() } catch (_: Exception) {}
            if (uri != null) try {
                if (doc) android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) else context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
            file?.delete()
        }
    }

    /** A URI other apps can open it by (Open): its MediaStore entry, or the file through Muninn's file provider. */
    fun shareUri(context: Context, t: Target): String = t.uri?.toString() ?: t.file?.let { f ->
        try { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f).toString() } catch (_: Exception) { "" }
    } ?: ""

    /** Open: the app the person picked for this kind of file. Never called by itself for something received. */
    fun open(context: Context, uri: String, name: String): Boolean {
        if (uri.isEmpty()) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        return try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), type)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    /** A saved file is still there (Files: "Moved or deleted" when it isn't). */
    fun exists(context: Context, uri: String): Boolean = uri.isNotEmpty() && try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
    } catch (_: Exception) { false }

    /**
     * Show in folder: the folder picked in Settings › Save files to (in the Files app), else the system's Downloads
     * list. False when neither opens (some TVs): say [where] it is instead.
     */
    fun showFolder(context: Context): Boolean {
        chosenTree(context)?.let { tree ->
            try {
                val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(doc, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {}
            return false   // not Downloads: the file isn't there
        }
        return try {
            context.startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    /** Saves [bytes] in Downloads (under [under], a chat's folder) as [name]: the name it got and its URI, or null. */
    fun save(context: Context, name: String, bytes: ByteArray, under: String = ""): Pair<String, String>? = try {
        val t = create(context, name, under)
        try { t.out.write(bytes); t.finish(); t.name to shareUri(context, t) } catch (e: Exception) { t.discard(); null }
    } catch (_: Exception) { null }

    /** Like [save], the bytes written by [write] straight into the new file (a long video never whole in memory). */
    fun saveStream(context: Context, name: String, under: String, write: (java.io.OutputStream) -> Boolean): Pair<String, String>? = try {
        val t = create(context, name, under)
        try { if (write(t.out)) { t.finish(); t.name to shareUri(context, t) } else { t.discard(); null } } catch (e: Exception) { t.discard(); null }
    } catch (_: Exception) { null }

    private fun oldPhoneAllowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** True on Android 8 and 9 until the storage permission is allowed (ask before accepting a transfer). */
    fun needsPermission(context: Context) = Build.VERSION.SDK_INT < 29 && !oldPhoneAllowed(context)

    /** Where received files end up, in words for the screen. */
    fun place(context: Context): String =
        if (chosenTree(context) != null) shownRoot()
        else if (Build.VERSION.SDK_INT < 29 && !oldPhoneAllowed(context)) "Muninn's own Downloads folder" else shownRoot()

    /** Free space where received files are saved. */
    fun freeBytes(context: Context): Long = try {
        StatFs((context.getExternalFilesDir(null) ?: context.filesDir).absolutePath).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }

    /** [name] with " (2)", " (3)"... added until nothing in [dir] has it. */
    fun free(dir: File, name: String): File {
        var t = File(dir, name)
        var n = 2
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
        while (t.exists()) { t = File(dir, "$base ($n)$ext"); n++ }
        return t
    }

    // ------------------------------------------------------------------ Save files to (a picked folder)

    private const val PREF = "saveFolder"
    /** The picked folder's name for the screen ("" while files go to Download/Muninn). */
    @Volatile private var rootName = ""

    /** The folder picked in Settings › Save files to, while Muninn may still write there; else null (Download/Muninn). */
    fun chosenTree(context: Context): Uri? {
        val s = context.getSharedPreferences("muninn", Context.MODE_PRIVATE).getString(PREF, null)?.takeIf { it.isNotEmpty() } ?: return null
        val u = Uri.parse(s)
        if (context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }) return u
        rootName = ""   // the folder went away: files go to Download/Muninn again, and the screen says so
        return null
    }

    /** Saves the folder Android's picker returned (null: back to Download/Muninn) and keeps the right to write there. */
    fun choose(context: Context, tree: Uri?) {
        val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
        val old = prefs.getString(PREF, null)?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        if (old != null && old != tree) try {
            context.contentResolver.releasePersistableUriPermission(old, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        if (tree != null) try {
            context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        prefs.edit().putString(PREF, tree?.toString() ?: "").apply()
        load(context)
    }

    /** Reads the picked folder's name (at start and after choosing). */
    fun load(context: Context) {
        val tree = chosenTree(context)
        rootName = if (tree == null) "" else try {
            val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Your folder"
        } catch (_: Exception) { "Your folder" }
    }

    /** Where a chat's files go, in words: "Download/Muninn/Sam", or "<picked folder>/Sam". [folder]: Sharing.folderFor's. */
    fun shown(folder: String): String = rootName.let { r -> if (r.isEmpty()) "Download/$folder" else "$r/${folder.substringAfter('/')}" }

    /** Settings: the picked folder's name, or Download/Muninn. */
    fun shownRoot(): String = rootName.ifEmpty { "Download/Muninn" }

    /** A document named [name] in [parent] (a folder), made when it isn't there. */
    private fun child(context: Context, tree: Uri, parent: Uri, name: String, dir: Boolean, mime: String): Uri? {
        val r = context.contentResolver
        if (dir) try {
            val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getDocumentId(parent))
            r.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == name && c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                    return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        } catch (_: Exception) {}
        return try { android.provider.DocumentsContract.createDocument(r, parent, mime, name) } catch (_: Exception) { null }
    }

    /** Into the picked folder: <folder>/<chat name>/<sender's folders>/<name>. Null when that didn't work (Downloads then). */
    private fun createInTree(context: Context, tree: Uri, folders: List<String>, name: String): Target? {
        var parent = try {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        } catch (_: Exception) { return null }
        for (f in folders) parent = child(context, tree, parent, f, true, android.provider.DocumentsContract.Document.MIME_TYPE_DIR) ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val uri = child(context, tree, parent, name, false, mime) ?: return null
        val out = try { context.contentResolver.openOutputStream(uri) } catch (_: Exception) { null }
        if (out == null) { try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) {}; return null }
        // The folder's app renames a clash itself ("a (1).jpg"): show the name it really got.
        val given = try {
            context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
        return Target(given ?: name, out, context, uri, null, doc = true)
    }

    /**
     * Starts saving a file. [path] is the name the sender gave; folders in it ("Photos/a.jpg") are kept
     * under Downloads (or the picked folder), and nothing in it can climb out of there.
     */
    fun create(context: Context, path: String, under: String = ""): Target {
        val parts = path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }.ifEmpty { listOf("from-phone") }
            .map { Sharing.safeName(it, "file") }
        val name = parts.last()
        // Cap f2: a chat's files go in Download/Muninn/<chat name> ([under], already made safe by Sharing.folderFor).
        val folders = under.split('/').filter { it.isNotEmpty() }.map { Sharing.safeName(it, "Chat") } + parts.dropLast(1)
        // A picked folder: <folder>/<chat name>/..., without the "Muninn" level.
        chosenTree(context)?.let { tree -> createInTree(context, tree, folders.drop(if (under.isNotEmpty()) 1 else 0), name)?.let { return it } }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val ext = name.substringAfterLast('.', "").lowercase()
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, (listOf(Environment.DIRECTORY_DOWNLOADS) + folders).joinToString("/") + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Couldn't save $name in Downloads.")
            val out = try { resolver.openOutputStream(uri) } catch (e: Exception) { null }
            if (out == null) { try { resolver.delete(uri, null, null) } catch (_: Exception) {}; throw IOException("Couldn't save $name in Downloads.") }
            // MediaStore renames a clash itself ("a (1).jpg"): show the name it really got.
            val given = try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null }
            return Target(given ?: name, out, context, uri, null)
        }
        val root = if (oldPhoneAllowed(context)) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Download")
        val dir = if (folders.isEmpty()) root else File(root, folders.joinToString("/"))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't make a folder for $name.")
        val target = free(dir, name)
        return Target(target.name, FileOutputStream(target), context, null, target)
    }
}

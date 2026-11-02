package io.github.ravenor907.muninn.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.ravenor907.muninn.net.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import io.github.ravenor907.muninn.net.Net
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Checks GitHub for a newer Muninn every time the app is opened (not just when its process starts:
 * the background service keeps the process alive for days). A newer release downloads by itself and
 * goes straight to Android's installer, which always asks the person to tap Install. The download must
 * match the release's SHA-256, carry the project's signature (.sig, Ed25519 over the file, see shared/docs/releases.md)
 * and its signature of the release's version (.vsig, [versionMessage]), and be newer than this copy, so a broken, swapped
 * or older file is never installed. The SHA-256, .sig and .vsig come from the signatures block at the end of the
 * release notes ([parseSignatures]); a release without one has Muninn.apk.sig and Muninn.apk.vsig beside the APK.
 * GitHub's own digest for the file, when the API gives one, must match too.
 */
class Updater(private val context: Context, private val identity: Identity) {
    data class State(val status: String = "", val latest: String = "", val busy: Boolean = false, val ready: Boolean = false, val progress: Float = 0f)
    /** One download's line in a release's signatures block: SHA-256 (lowercase hex), .sig and .vsig (64 bytes each). */
    class SignedFile(val sha: String, val sig: ByteArray, val vsig: ByteArray)
    /** A release's signatures block ([parseSignatures]): its label line and its downloads' lines. */
    class Signatures(val label: String, val files: Map<String, SignedFile>)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val pool = Executors.newSingleThreadExecutor()
    private var url: String? = null
    /** The SHA-256 (hex) the download must have, from the same release. */
    private var sha: String? = null
    /** The version the release's tag names ("0.97" for v0.97): the one its .vsig must sign. */
    private var version: String? = null
    /** Muninn.apk's line in the release's signatures block, or null (then the separate .sig and .vsig files). */
    private var signed: SignedFile? = null
    private var lastCheck = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** A Kids' TV installs through its own PackageInstaller session (tv/TvUpdate); true when it took the file. */
    @Volatile var installer: ((File) -> Boolean)? = null

    /** Called whenever Muninn comes to the front. Checks at most every few minutes. */
    fun checkOnOpen() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < RECHECK_MS) return
        check(installAfter = true)
    }

    val current: String get() = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1"
    } catch (e: Exception) { "0.1" }

    fun check(installAfter: Boolean = false) {
        if (_state.value.busy) return
        lastCheck = android.os.SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(busy = true, status = "Checking for updates…")
        pool.execute {
            try {
                val api = fromApi()
                // GitHub's API allows few checks per address an hour: then ask the website instead.
                val viaPage = api.version == null
                var found = if (viaPage) fromWebsite() else api
                // The Pre-release channel (Labs): the "beta" pre-release too, when it's newer than the latest release.
                if (identity.preRelease) fromBeta(viaPage)?.let { b ->
                    if (found.version == null || Versions.newer(b.version!!, found.version!!)) found = b
                }
                val best = found.version
                url = found.url
                sha = found.sha
                version = best
                signed = found.signed
                val newer = best != null && mayInstall(best!!, current)
                // Data saver on mobile data: the download waits for Wi-Fi, unless the person taps to get it now.
                val wait = newer && installAfter && (context.applicationContext as? io.github.ravenor907.muninn.Muninn)?.power?.savingData() == true
                _state.value = State(if (!newer) "Muninn is up to date" else if (wait) "Muninn $best is ready. It downloads on Wi-Fi, or tap to get it now"
                    else "Muninn $best is ready to install", best ?: "", false, newer)
                if (newer && installAfter && !wait) download()
            } catch (e: Exception) {
                lastCheck = 0L   // try again next time Muninn opens
                _state.value = State("Couldn't reach GitHub. Check the connection.")
            }
        }
    }

    /**
     * Labs, "Go back to stable": the latest release, installed even though it's older than this pre-release. Its
     * file, SHA-256, .sig and .vsig are checked as always. The channel goes back to Stable and Labs mode turns off.
     */
    fun backToStable() {
        if (_state.value.busy) return
        identity.preRelease = false
        identity.labs = false
        _state.value = _state.value.copy(busy = true, status = "Checking for updates…")
        pool.execute {
            try {
                val api = fromApi()
                val found = if (api.version == null) fromWebsite() else api
                val best = found.version
                if (best == null || found.url == null) { _state.value = State("Couldn't find the latest release."); return@execute }
                url = found.url; sha = found.sha; version = best; signed = found.signed
                _state.value = State("Muninn $best is ready to install", best, false, true)
                download(back = true)
            } catch (e: Exception) {
                _state.value = State("Couldn't reach GitHub. Check the connection.")
            }
        }
    }

    /** [back]: going back to stable ([backToStable]), the one time an older release may be installed. */
    fun download(back: Boolean = false) {
        val u = url ?: return
        val block = signed
        val ver = version
        // The signatures block's SHA-256; GitHub's digest, when the API gave one, must say the same.
        if (block != null && sha != null && sha != block.sha) {
            _state.value = _state.value.copy(status = "The download didn't match the release, so it wasn't installed.")
            return
        }
        val want = block?.sha ?: sha
        _state.value = _state.value.copy(busy = true, status = "Downloading Muninn ${_state.value.latest}…")
        pool.execute {
            try {
                // The project's signatures, of the file (.sig) and of the release's version (.vsig): from the release
                // notes' signatures block, or else the files beside the APK. Both are always required.
                val sig = block?.sig ?: signature("$u.sig")
                val vsig = block?.vsig ?: signature("$u.vsig")
                if (sig == null || vsig == null || ver == null) {
                    _state.value = _state.value.copy(busy = false, status = NOT_SIGNED)
                    return@execute
                }
                // Never an older (or the same) version, whatever a release's tag says. Going back to stable takes the
                // latest release (never a pre-release) even when it's older.
                if (!(back && Versions.isStable(ver) && !u.contains("/$BETA_TAG/")) && !mayInstall(ver, current)) {
                    _state.value = _state.value.copy(busy = false, status = NOT_NEWER)
                    return@execute
                }
                val signed = Ed25519.Check(hex(RELEASE_KEY), sig)
                val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
                val out = File(dir, "Muninn.apk")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                // GitHub's download links redirect to another host; OkHttp follows them.
                val c = Net.client.newBuilder().readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
                c.newCall(Request.Builder().url(u).header("User-Agent", AGENT).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("No body")
                    val total = body.contentLength()
                    body.byteStream().use { ins ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(1 shl 16); var got = 0L
                            while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); digest.update(buf, 0, n); signed.update(buf, 0, n); got += n
                                if (total > 0) _state.value = _state.value.copy(progress = got.toFloat() / total) }
                        }
                    }
                }
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (want == null || got != want) {
                    out.delete()
                    _state.value = _state.value.copy(busy = false, status = if (want == null) "This update can't be checked, so it wasn't installed." else "The download didn't match the release, so it wasn't installed.")
                    return@execute
                }
                if (!signed.valid()) {
                    out.delete()
                    _state.value = _state.value.copy(busy = false, status = NOT_SIGNED)
                    return@execute
                }
                // The version signed in .vsig must be the one this release's tag names: an older signed file posted
                // again under a newer tag doesn't check out.
                if (!versionSigned(ver, u.substringAfterLast('/'), got, vsig)) {
                    out.delete()
                    _state.value = _state.value.copy(busy = false, status = VERSION_NOT_SIGNED)
                    return@execute
                }
                // A chat file on its way: installing closes Muninn and would stop it, so the update waits for it.
                val waitUntil = System.currentTimeMillis() + 6 * 3_600_000L
                while (io.github.ravenor907.muninn.net.FileLink.movingCount() > 0 && System.currentTimeMillis() < waitUntil) {
                    _state.value = _state.value.copy(busy = true, status = "Installs when the file on its way is done")
                    Thread.sleep(2000)
                }
                _state.value = _state.value.copy(busy = false, status = "Tap Install when Android asks")
                main.post { if (installer?.invoke(out) != true) install(out) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, status = "The download stopped. Try again.")
            }
        }
    }

    /** What a check found: version, download link, SHA-256 and the APK's line in the signatures block (all may be null). */
    private data class Found(val version: String?, val url: String?, val sha: String?, val signed: SignedFile? = null)

    /** The newest release with an APK, from GitHub's API, or all nulls if the API said no. */
    private fun fromApi(): Found {
        betaSha = null
        betaBlock = null
        val req = Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").header("User-Agent", AGENT).build()
        val text = Net.client.newCall(req).execute().use { r ->
            if (r.code == 403 || r.code == 429) return Found(null, null, null)   // rate limited
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("No body")
        }
        val a = JSONArray(text)
        var best: String? = null
        var bestUrl: String? = null
        var bestSha: String? = null
        var bestSigned: SignedFile? = null
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            // The "beta" pre-release: its APK's SHA-256 and its signatures block, for the Pre-release channel ([fromBeta]).
            if (r.optString("tag_name") == BETA_TAG && r.optBoolean("prerelease")) {
                val assets = r.optJSONArray("assets")
                if (assets != null) betaSha = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "Muninn.apk" }?.let { shaOf(it.optString("digest")) }
                betaBlock = parseSignatures(r.optString("body"), false)
            }
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            val name = versionOf(tag) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            if (best == null || rank(name) > rank(best)) {
                best = name; bestUrl = apk.optString("browser_download_url")
                // The release notes' signatures block; none means the separate files.
                bestSigned = parseSignatures(r.optString("body"), false)?.files?.get(APK)
                bestSha = shaOf(apk.optString("digest")) ?: bestSigned?.sha ?: sidecar("$DOWNLOAD/$tag/${apk.optString("name")}.sha256")
            }
        }
        return Found(best, bestUrl, bestSha, bestSigned)
    }

    /** The "beta" pre-release's APK SHA-256 and signatures block, from the last [fromApi]. */
    @Volatile private var betaSha: String? = null
    @Volatile private var betaBlock: Signatures? = null

    /**
     * The "beta" pre-release, or null when there is none. Its label ("0.99 beta 12", which its .vsig signs) is the label
     * line of its signatures block: from the API, or its web page when the API said no ([viaPage]). A beta without a
     * block has version.txt and the separate files.
     */
    private fun fromBeta(viaPage: Boolean): Found? {
        val block = betaBlock ?: if (viaPage) page(BETA_TAG) else null
        val label = block?.label ?: try {
            Net.client.newCall(Request.Builder().url("$DOWNLOAD/$BETA_TAG/version.txt").header("User-Agent", AGENT).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null } ?: return null
        if (!Versions.isBeta(label)) return null
        val line = block?.files?.get(APK)
        return Found(label, "$DOWNLOAD/$BETA_TAG/$APK", betaSha ?: line?.sha ?: sidecar("$DOWNLOAD/$BETA_TAG/$APK.sha256"), line)
    }

    /** The signatures block on a release's web page (github.com/.../releases/tag/<tag>), or null. Reads at most [MAX_NOTES]. */
    private fun page(tag: String): Signatures? = try {
        Net.client.newCall(Request.Builder().url("$PAGE/$tag").header("User-Agent", AGENT).build()).execute().use { r ->
            val src = if (r.isSuccessful) r.body?.source() else null
            if (src == null) null else {
                val buf = okio.Buffer()
                while (buf.size <= MAX_NOTES && src.read(buf, 1L shl 16) != -1L) { }
                if (buf.size > MAX_NOTES) null else parseSignatures(buf.readUtf8(), true)
            }
        }
    } catch (e: Exception) { null }

    /** The hex SHA-256 in a release's ".sha256" file (as sha256sum writes it), or null. */
    private fun sidecar(u: String): String? = try {
        Net.client.newCall(Request.Builder().url(u).header("User-Agent", AGENT).build()).execute().use { r ->
            if (r.isSuccessful) shaOf(r.body?.string().orEmpty()) else null
        }
    } catch (e: Exception) { null }

    /** The 64 bytes of a release's ".sig" file, or null when there is none. */
    private fun signature(u: String): ByteArray? = try {
        Net.client.newCall(Request.Builder().url(u).header("User-Agent", AGENT).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size == 64 } else null
        }
    } catch (e: Exception) { null }

    /**
     * The website's "latest release" link redirects to its tag; the APK is always named Muninn.apk. The release's page
     * has its signatures block.
     */
    private fun fromWebsite(): Found {
        val req = Request.Builder().url(LATEST).header("User-Agent", AGENT).head().build()
        val finalUrl = Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.request.url.toString()
        }
        val tag = tagFromLatest(finalUrl) ?: throw IOException("No release tag in $finalUrl")
        val name = versionOf(tag) ?: throw IOException("Unexpected tag $tag")
        val line = page(tag)?.files?.get(APK)
        return Found(name, "$DOWNLOAD/$tag/$APK", line?.sha ?: sidecar("$DOWNLOAD/$tag/$APK.sha256"), line)
    }

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.value = _state.value.copy(status = "Allow Muninn to install updates, then press Install again")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val RELEASES = "https://api.github.com/repos/Ravenor907/Huginn-Muninn/releases?per_page=100"
        private const val LATEST = "https://github.com/Ravenor907/Huginn-Muninn/releases/latest"
        private const val DOWNLOAD = "https://github.com/Ravenor907/Huginn-Muninn/releases/download"
        /** A release's web page, read for its signatures block when the API said no. */
        private const val PAGE = "https://github.com/Ravenor907/Huginn-Muninn/releases/tag"
        private const val APK = "Muninn.apk"
        private const val RECHECK_MS = 5 * 60_000L
        /** The pre-release's fixed tag, replaced on every push to the beta branch. */
        const val BETA_TAG = "beta"
        /** A plain agent with no app name or version, so GitHub can't tell which app or version is asking. */
        private const val AGENT = "Mozilla/5.0"
        private const val NOT_SIGNED = "This update isn't signed with the project's key, so it wasn't installed."
        private const val VERSION_NOT_SIGNED = "This update's version isn't signed with the project's key, so it wasn't installed."
        private const val NOT_NEWER = "This update isn't newer than the Muninn you have, so it wasn't installed."
        /** The first line of the text a .vsig signs (shared/docs/releases.md). */
        const val VERSION_DOMAIN = "huginn-muninn release v1"
        /** The project's release key (Ed25519, shared/docs/release-key.pem). CI signs every release file with its private half. */
        const val RELEASE_KEY = "705d7c9eb0626439b6ab749bbeefc9271876249c701914d60902a6b2b7341d06"

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** True when [sig] is the project's signature of [data]. */
        fun signedByProject(data: ByteArray, sig: ByteArray): Boolean = Ed25519.verify(hex(RELEASE_KEY), data, sig)

        /**
         * What a release file's .vsig signs: [VERSION_DOMAIN], the version, the file's name and the lowercase hex SHA-256
         * of the file, one per line (no newline at the end). Built from the tag being installed.
         */
        fun versionMessage(version: String, file: String, sha: String): ByteArray =
            "$VERSION_DOMAIN\n$version\n$file\n${sha.lowercase()}".toByteArray(Charsets.UTF_8)

        /** True when [vsig] is [key]'s signature of [version] for [file] with SHA-256 [sha] (the project's key by default). */
        fun versionSigned(version: String, file: String, sha: String, vsig: ByteArray, key: ByteArray = hex(RELEASE_KEY)): Boolean =
            Ed25519.verify(key, versionMessage(version, file, sha), vsig)

        /** Only a version newer than the one installed ([current]) is ever installed, by [Versions] order. */
        fun mayInstall(version: String, current: String) = Versions.newer(version, current)

        /** The first line of the signatures block at the end of the release notes (shared/docs/releases.md). */
        const val SIGNATURES_FIRST = "huginn-muninn signatures v1"
        /** The most of a release body or release page read, and of the block itself. */
        const val MAX_NOTES = 4 * 1024 * 1024
        const val MAX_BLOCK = 2048
        private val LABEL_LINE = Regex("""label ([0-9]{1,6}\.[0-9]{1,6}(?: beta [1-9][0-9]{0,5})?)""")
        private val FILE_LINE =
            Regex("""(Huginn-Windows\.zip|Huginn\.flatpak|Muninn\.apk) sha256 ([0-9a-f]{64}) sig ([A-Za-z0-9+/]{86}==) vsig ([A-Za-z0-9+/]{86}==)""")

        /**
         * The signatures block in a release body from the API ([page] false: markdown) or in the release's web page ([page]
         * true: HTML), or null. Strict: the first line exactly once (starting a line in a body, right after a ">" in a
         * page: the page's descriptions and copy button hold the text too, never right after a ">"), ending at the
         * first "<" or "```" or the end; then the label and one to three downloads, each named once, in only these
         * shapes; empty lines only at the end; size caps.
         */
        fun parseSignatures(text: String, page: Boolean): Signatures? {
            if (text.length > MAX_NOTES) return null
            var start = -1
            var found = 0
            var i = text.indexOf(SIGNATURES_FIRST)
            while (i >= 0) {
                val before = if (i > 0) text[i - 1] else null
                if (if (page) before == '>' else (before == null || before == '\n')) { start = i; found++ }
                i = text.indexOf(SIGNATURES_FIRST, i + 1)
            }
            if (found != 1) return null
            var end = text.length
            for (t in listOf("<", "```")) { val j = text.indexOf(t, start); if (j in 0 until end) end = j }
            if (end - start > MAX_BLOCK) return null
            val lines = text.substring(start, end).split('\n').map { it.removeSuffix("\r") }.toMutableList()
            while (lines.isNotEmpty() && lines[lines.size - 1].isEmpty()) lines.removeAt(lines.size - 1)
            if (lines.size !in 3..5 || lines[0] != SIGNATURES_FIRST) return null
            val label = LABEL_LINE.matchEntire(lines[1])?.groupValues?.get(1) ?: return null
            val files = LinkedHashMap<String, SignedFile>()
            for (line in lines.drop(2)) {
                val (name, sha, sig, vsig) = FILE_LINE.matchEntire(line)?.destructured ?: return null
                if (name in files) return null
                files[name] = SignedFile(sha, sig64(sig) ?: return null, sig64(vsig) ?: return null)
            }
            return Signatures(label, files)
        }

        /** A signature in the block: the one canonical base64 spelling of 64 bytes, else null. */
        private fun sig64(b64: String): ByteArray? = try {
            java.util.Base64.getDecoder().decode(b64).takeIf { it.size == 64 && java.util.Base64.getEncoder().encodeToString(it) == b64 }
        } catch (e: IllegalArgumentException) { null }

        /** ".../releases/tag/v0.6" -> "v0.6" (where the "latest" link lands), else null. */
        fun tagFromLatest(url: String): String? =
            Regex("""/releases/tag/([^/?#]+)$""").find(url.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)

        /** The hex SHA-256 in GitHub's "sha256:<hex>" digest or a sha256sum line ("<hex>  Muninn.apk"), lowercase; else null. */
        fun shaOf(text: String): String? =
            Regex("""^(?:sha256:)?([0-9a-fA-F]{64})(?:\s|$)""").find(text.trim())?.groupValues?.get(1)?.lowercase()

        /** "v0.31" -> "0.31". Test tags (v0.3-test1) and anything else -> null. */
        fun versionOf(tag: String): String? =
            Regex("""^v(\d+\.\d+)$""").find(tag)?.groupValues?.get(1)

        /**
         * One dot, read as a decimal: 0.3 < 0.31 < 0.32 < 0.4 < 1.0. An installed test build
         * ("0.3 Test 1") sits just below its version. Matches versionCode in build.gradle.kts. Which version is newer is
         * [Versions] (it also orders pre-releases).
         */
        fun rank(v: String): Long {
            val m = Regex("""^(\d+)\.(\d+)( Test \d+)?""").find(v) ?: return 0
            val (a, b, t) = m.destructured
            return (a.toLong() * 1_000_000 + (b + "000000").take(6).toLong()) * 10 + if (t.isEmpty()) 5 else 0
        }
    }
}

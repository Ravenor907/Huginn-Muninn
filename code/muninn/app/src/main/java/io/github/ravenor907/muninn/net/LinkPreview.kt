package io.github.ravenor907.muninn.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Link previews (cap m2, shared/docs/protocol.md 12.4): made on the sending phone only, never on the receiving one,
 * so no website learns who got a link or when it was read. The page's title, description and picture come from its
 * Open Graph tags (or its <title>); the picture is shrunk to a small JPEG with nothing but pixels.
 */
object LinkPreview {
    private const val PAGE_MAX = 512 * 1024
    private const val IMAGE_MAX = 4 * 1024 * 1024
    private const val THUMB_SIDE = 240
    private const val THUMB_BYTES = 16 * 1024

    // Only https, never into this phone's own network: names that point at a private or LAN address are refused,
    // and every hop (redirects too) is checked again on the address actually connected to.
    private val client by lazy {
        Net.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false)
            .dns(object : okhttp3.Dns {
                // Ultra privacy turned on while a preview was on its way: no name is looked up here any more.
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    if (!UltraPrivacy.previewsAllowed) throw java.net.UnknownHostException("Ultra privacy") else okhttp3.Dns.SYSTEM.lookup(hostname).filter { publicAddress(it) }.ifEmpty { throw java.net.UnknownHostException("not a public address") }
            })
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val at = chain.connection()?.route()?.socketAddress?.address
                if (!req.url.isHttps || at == null || !publicAddress(at)) throw java.io.IOException("not a public https address")
                chain.proceed(req)
            }
            .build()
    }

    /** Only https addresses whose host isn't a private or LAN address written out. */
    fun allowedUrl(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (!u.isHttps) return false
        val literal = literalAddress(u.host) ?: return true   // a name: checked once it's looked up
        return publicAddress(literal)
    }

    private fun literalAddress(host: String): java.net.InetAddress? {
        val h = host.removePrefix("[").removeSuffix("]")
        val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        if (!v4.matches(h) && !h.contains(':')) return null
        return try { java.net.InetAddress.getByName(h) } catch (_: Exception) { null }
    }

    /** False for this device, the LAN, link-local, carrier-grade NAT, multicast and other non-internet addresses. */
    fun publicAddress(a: java.net.InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 0xff }
        if (b.size == 4) return v4Public(b)
        if (b.size == 16) {
            if (b[0] and 0xfe == 0xfc) return false                              // fc00::/7, unique local
            if (b[0] == 0xfe && b[1] and 0xc0 == 0xc0) return false              // fec0::/10, old site-local
            if ((0 until 10).all { b[it] == 0 } && b[10] == 0xff && b[11] == 0xff) return v4Public(b.subList(12, 16))   // ::ffff:a.b.c.d
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return false   // 2001:db8::/32, documentation
            if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b) return v4Public(b.subList(12, 16))   // 64:ff9b::/96
            return true
        }
        return false
    }

    private fun v4Public(b: List<Int>): Boolean = when {
        b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 -> false
        b[0] == 100 && b[1] in 64..127 -> false        // carrier-grade NAT
        b[0] == 169 && b[1] == 254 -> false
        b[0] == 172 && b[1] in 16..31 -> false
        b[0] == 192 && b[1] == 168 -> false
        b[0] == 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2) -> false
        b[0] == 198 && (b[1] == 18 || b[1] == 19) -> false
        b[0] == 198 && b[1] == 51 && b[2] == 100 -> false
        b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
        else -> true
    }

    /** Null when the page couldn't be read or has no title, and always while Ultra privacy is on. Call off the main thread. */
    fun fetch(url: String): More.Preview? = if (!UltraPrivacy.previewsAllowed) null else try {
        val html = get(url, PAGE_MAX, "text/html")?.let { String(it, Charsets.UTF_8) } ?: null
        html?.let { page ->
            val title = (meta(page, "og:title") ?: meta(page, "twitter:title") ?: tag(page, "title"))?.let { clean(it, More.TITLE_MAX) }
            if (title.isNullOrEmpty()) null else {
                val desc = (meta(page, "og:description") ?: meta(page, "description"))?.let { clean(it, More.DESC_MAX) } ?: ""
                val img = meta(page, "og:image")?.let { resolve(url, it) }
                val thumb = img?.let { u -> get(u, IMAGE_MAX, "image/")?.let { thumbnail(it) } }
                More.Preview(url, title, desc, thumb).takeIf { More.ok("rich", org.json.JSONObject().put("text", url).put("lp", More.previewJson(it))) }
            }
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun get(url: String, max: Int, type: String): ByteArray? {
        if (!UltraPrivacy.previewsAllowed || !allowedUrl(url)) return null
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; link preview)").header("Accept", if (type.endsWith("/")) "image/*" else type).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            val ct = body.contentType()?.toString().orEmpty()
            if (!ct.startsWith(type)) return null
            val out = ByteArrayOutputStream()
            body.byteStream().use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() >= max) break   // the head of a page is enough; a huge picture isn't worth it
                }
            }
            return out.toByteArray()
        }
    }

    private fun attr(t: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(t)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    /** <meta property="og:title" content="..."> or name="...", in either order. */
    private fun meta(page: String, key: String): String? {
        for (m in Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(page)) {
            val t = m.value
            val k = attr(t, "property") ?: attr(t, "name") ?: continue
            if (k.equals(key, ignoreCase = true)) return attr(t, "content")?.let { unescape(it) }?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun tag(page: String, name: String): String? =
        Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(page)?.groupValues?.get(1)?.let { unescape(it) }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }

    /** One line, no control characters, at most [max] code points. */
    private fun clean(s: String, max: Int): String {
        val one = s.replace(Regex("\\s+"), " ").filter { it.code >= 0x20 && it.code != 0x7F }.trim()
        return if (one.codePointCount(0, one.length) <= max) one else one.substring(0, one.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
    }

    private fun resolve(base: String, link: String): String? = try { java.net.URI(base).resolve(link.trim()).toString() } catch (e: Exception) { null }

    private fun thumbnail(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_SIDE) sample *= 2
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val long = maxOf(b.width, b.height)
        if (long > THUMB_SIDE) { val f = THUMB_SIDE.toFloat() / long; b = Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) }
        for (q in intArrayOf(70, 55, 40)) {
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= THUMB_BYTES) return out.toByteArray()
        }
        return null
    }
}

package io.github.ravenor907.muninn.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp setup for everything Muninn does on the internet: the Nostr relays, the call
 * relay's Worker and updates from GitHub. Build variants from it with newBuilder() (they share its
 * connection pool and threads). Nearby Wi-Fi (LocalSend) has its own code for the local network.
 */
object Net {
    /**
     * Ultra privacy: every client built from this one asks [UltraPrivacy.selector] on each connection (Tor's SOCKS5 port
     * while it's on, the normal way while it's off) and never looks a name up here while it's on ([UltraPrivacy.dns]).
     * A variant that sets its own Dns (LinkPreview) is off while Ultra privacy is on.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(UltraPrivacy.selector).dns(UltraPrivacy.dns)
            // A pooled connection made the normal way just before Ultra privacy went on is never used for anything after.
            .addNetworkInterceptor { chain ->
                if (UltraPrivacy.on && chain.connection()?.route()?.proxy?.type() != java.net.Proxy.Type.SOCKS)
                    throw java.io.IOException("Ultra privacy: not through Tor")
                chain.proceed(chain.request())
            }
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Ultra privacy turned on or off (or Tor became ready): pooled connections made the old way are never used again. */
    fun forgetConnections() {
        try { client.connectionPool.evictAll() } catch (_: Exception) {}
    }
}

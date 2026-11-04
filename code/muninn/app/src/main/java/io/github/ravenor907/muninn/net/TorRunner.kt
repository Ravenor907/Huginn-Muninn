package io.github.ravenor907.muninn.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import org.torproject.jni.TorService

/**
 * Tor for Ultra privacy, inside Muninn's own process: the Guardian Project's tor-android (TorService, BSD-3-Clause),
 * no VPN and no other app. Its SOCKS5 port is on 127.0.0.1 only, picked by Tor ("auto"); Muninn finds it on Tor's
 * control socket (a private file in Muninn's own folder, so no password is needed and no other app can reach it).
 *
 * Bound from the app, so Tor runs as long as Muninn's process does (the message service keeps it alive), and stops
 * when Ultra privacy is turned off. A watcher reads Tor's start every second and tells [UltraPrivacy]: ready, starting,
 * slow or blocked.
 */
object TorRunner {
    private val lock = Any()
    private var conn: ServiceConnection? = null
    @Volatile private var service: TorService? = null
    @Volatile private var watching: Thread? = null
    @Volatile private var since = 0L

    /** Reads the saved switch (first thing at app start, before the relays connect) and starts Tor if it's on. */
    fun init(context: Context) {
        val on = context.getSharedPreferences("muninn", Context.MODE_PRIVATE).getBoolean(UltraPrivacy.PREF, false)
        UltraPrivacy.use(on)
        if (on) start(context)
    }

    /** Settings: Ultra privacy on or off, saved at once, Tor started or stopped. */
    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("muninn", Context.MODE_PRIVATE).edit().putBoolean(UltraPrivacy.PREF, on).commit()
        UltraPrivacy.use(on)
        if (on) start(context) else stop(context)
    }

    /** The banner's Wait: the slow or blocked clock starts over. */
    fun waitLonger() { since = SystemClock.elapsedRealtime() }

    private fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (conn != null) return
            try { writeTorrc(app) } catch (_: Exception) {}
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = (binder as? TorService.LocalBinder)?.service
                }
                override fun onServiceDisconnected(name: ComponentName?) { service = null }
            }
            since = SystemClock.elapsedRealtime()
            val bound = try { app.bindService(Intent(app, TorService::class.java), c, Context.BIND_AUTO_CREATE) } catch (_: Throwable) { false }
            if (!bound) { UltraPrivacy.missing(); return }
            conn = c
            watch()
        }
    }

    private fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val c = conn ?: return
            conn = null
            service = null
            watching = null
            try { app.unbindService(c) } catch (_: Exception) {}
        }
    }

    /**
     * Tor's own settings, beside the ones TorService writes: the SOCKS port on 127.0.0.1 picked by Tor (never 9050, so
     * another Tor app keeps its own), no HTTP tunnel, and as little written to storage as Tor allows.
     */
    private fun writeTorrc(context: Context) {
        TorService.getTorrc(context).writeText("SOCKSPort 127.0.0.1:auto\nHTTPTunnelPort 0\nAvoidDiskWrites 1\nClientOnly 1\n")
    }

    /** Every second while Tor runs: its SOCKS port and whether it has a circuit, or how far its start is. */
    private fun watch() {
        val t = Thread({
            val me = Thread.currentThread()
            while (watching === me) {
                val s = service
                try {
                    val port = s?.getSocksPort() ?: -1
                    val circuit = s?.getInfo("status/circuit-established")?.trim() == "1"
                    if (port > 0 && circuit) UltraPrivacy.ready(port)
                    else {
                        val phase = s?.getInfo("status/bootstrap-phase").orEmpty()
                        UltraPrivacy.starting(UltraPrivacy.percentOf(phase), SystemClock.elapsedRealtime() - since, phase.startsWith("WARN"))
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "muninn-tor-watch").apply { isDaemon = true }
        watching = t
        t.start()
    }
}

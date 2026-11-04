package io.github.ravenor907.muninn.net

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/**
 * Tor for Ultra privacy: the Guardian Project's tor-android (TorService, BSD-3-Clause), no VPN and no other app.
 *
 * Round 19: Tor runs in its own process (":tor", see the manifest), never in Muninn's. Tor can't be started twice in one
 * process, so turning Ultra privacy off ends that process, and every start gets a fresh one. Muninn never touches
 * TorService's objects (they live in the other process): it binds the service only to keep it running, and talks to Tor
 * over Tor's control socket (a private file in Muninn's own folder, so no password is needed and no other app can reach
 * it). Tor's SOCKS5 port is on 127.0.0.1 only, picked by Tor ("auto"), and read back over the control socket.
 *
 * A watcher asks Tor every second for its SOCKS port and whether it has a circuit, and tells [UltraPrivacy]: ready,
 * starting, slow or blocked. It is also the watchdog: a Tor that went away, or that lost its circuits and doesn't get
 * them back (after the phone slept), is woken, then started again in a fresh process.
 */
object TorRunner {
    /** The process Tor runs in (the manifest's android:process for TorService). */
    const val PROCESS = ":tor"
    private const val SERVICE = "org.torproject.jni.TorService"
    /** TorService's own folder name (Context.getDir), with its torrc, and its data folder with the control socket. */
    private const val DIR = "TorService"

    /** No circuit this long after having had one: Tor is told to wake up ("SIGNAL ACTIVE") and to build new circuits. */
    const val NUDGE_MS = 60_000L
    /** Still none this long after that: Tor starts again, in a fresh process. */
    const val RESTART_MS = 4 * 60_000L
    /** The control socket not answering this long while Tor should run: started again. */
    const val DEAD_MS = 60_000L
    /** Never more often than this between two restarts. */
    const val RESTART_GAP_MS = 5 * 60_000L
    /** Starts in a row that never got a SOCKS port: Tor can't run on this device (MISSING; the banner offers Turn off). */
    const val MAX_FAILED_STARTS = 3
    /** A gap this long between two looks means the phone slept: the clocks above start over, Tor gets a moment. */
    private const val SLEPT_MS = 10_000L

    private val lock = Any()
    private var conn: ServiceConnection? = null
    @Volatile private var watching: Thread? = null
    /** When this start (or the current wait for a circuit) began, for slow and blocked; the banner's Wait sets it again. */
    @Volatile private var since = 0L
    /** Turning on and off, and restarts: one at a time, never on the main thread. */
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-tor").apply { isDaemon = true } }
    /** Ultra privacy's listeners (closing sockets, the relays dialling again): never on the main thread. */
    private val listeners = Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-ultra").apply { isDaemon = true } }
    @Volatile private var hooked = false

    /** True in Tor's own process: Muninn's Application must do nothing else there (Muninn.onCreate returns early). */
    fun isTorProcess(context: Context): Boolean {
        val name = try {
            if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        } catch (_: Exception) { "" }
        return name.isNotEmpty() && name == context.packageName + PROCESS
    }

    /** Reads the saved switch (first thing at app start, before the relays connect) and starts Tor if it's on. */
    fun init(context: Context) {
        // Tor's own process runs nothing of Muninn's (Muninn.onCreate returns before this). Should anything get here
        // anyway, it is closed: every internet connection made in this process fails instead of leaving directly.
        if (isTorProcess(context)) { UltraPrivacy.use(true); return }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("muninn", Context.MODE_PRIVATE)
        // A Kids' Device never runs Ultra privacy (it has no Settings to turn it off again): a switch left on from
        // before the device became one is turned off here.
        val kids = try { io.github.ravenor907.muninn.tv.KidMode.saved(app) } catch (_: Exception) { false }
        val on = prefs.getBoolean(UltraPrivacy.PREF, false) && !kids
        if (kids && prefs.getBoolean(UltraPrivacy.PREF, false)) prefs.edit().putBoolean(UltraPrivacy.PREF, false).apply()
        if (!hooked) {
            hooked = true
            // Turned on: whatever already goes straight to someone stops now (a call, a voice room, a file waiting or
            // on its way, a download that started the normal way), and pending files go through the relays instead.
            UltraPrivacy.onTurnedOn {
                try { Net.dropDirect() } catch (_: Exception) {}
                try { FileLink.ultraOn() } catch (_: Exception) {}
                try { io.github.ravenor907.muninn.net.call.CallManager.ultraOn() } catch (_: Exception) {}
                try { io.github.ravenor907.muninn.net.call.RoomManager.ultraOn() } catch (_: Exception) {}
            }
        }
        UltraPrivacy.use(on)
        // From now on the listeners run on their own thread (at start they ran right here, before anything connected).
        UltraPrivacy.listenerThread = listeners
        if (on) bg.execute { start(app) }
    }

    /** Settings: Ultra privacy on or off, saved at once, Tor started or stopped (in the background). */
    fun set(context: Context, on: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences("muninn", Context.MODE_PRIVATE).edit().putBoolean(UltraPrivacy.PREF, on).commit()
        UltraPrivacy.use(on)
        if (on) { failedStarts = 0; lastRestart = 0L }   // turned on again: Tor gets its full chances again
        bg.execute { if (on) start(app) else stop(app) }
    }

    /** The banner's Wait: the slow or blocked clock starts over. */
    fun waitLonger() { since = SystemClock.elapsedRealtime() }

    /**
     * Erase everything: Tor stops, its process ends, and its folders (guard state, the cached directory, torrc) go.
     * Blocking: run it off the main thread, before the rest of the erase.
     */
    fun erase(context: Context) {
        val app = context.applicationContext
        stop(app)
        try { app.getDir(DIR, Context.MODE_PRIVATE).deleteRecursively() } catch (_: Exception) {}
        try { File(app.cacheDir, DIR).deleteRecursively() } catch (_: Exception) {}
    }

    private fun torDir(app: Context): File = app.getDir(DIR, Context.MODE_PRIVATE)
    private fun controlSocket(app: Context): File = File(File(torDir(app), "data"), "ControlSocket")

    private fun start(app: Context, again: Boolean = false) {
        synchronized(lock) {
            if (!UltraPrivacy.on) return
            if (conn != null && !again) return
            conn?.let { c -> conn = null; watching = null; try { app.unbindService(c) } catch (_: Exception) {} }
            // A Tor left from before (turned off, a restart, a stop that didn't finish): gone first, so this start gets
            // a fresh process and Tor's data folder isn't in use twice.
            killTor(app)
            try { controlSocket(app).delete() } catch (_: Exception) {}
            try { writeTorrc(app) } catch (_: Exception) {}
            val c = object : ServiceConnection {
                // Nothing to take from the binder: it belongs to the other process. Tor is reached on its control socket.
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}
                // Tor's process died: Android starts it again (it's still bound); the watcher sees to the rest.
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            since = SystemClock.elapsedRealtime()
            val bound = try {
                app.bindService(Intent().setComponent(ComponentName(app.packageName, SERVICE)), c, Context.BIND_AUTO_CREATE)
            } catch (_: Throwable) { false }
            if (!bound) { UltraPrivacy.missing(); return }
            conn = c
            watch(app)
        }
    }

    private fun stop(app: Context) {
        synchronized(lock) {
            val c = conn
            conn = null
            watching = null
            if (c != null) try { app.unbindService(c) } catch (_: Exception) {}
            // Unbound, Tor's service ends; its process stays cached by Android, so it's ended here: the next start
            // can't be a second Tor in the same process.
            killTor(app)
        }
    }

    /** Ends Tor's process if it runs, and waits (up to 3 s) until it's gone. Own processes only (same app). */
    private fun killTor(app: Context) {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val name = app.packageName + PROCESS
        val until = SystemClock.elapsedRealtime() + 3_000
        var killed = false
        while (true) {
            val p = try { am.runningAppProcesses?.firstOrNull { it.processName == name } } catch (_: Exception) { null } ?: return
            if (!killed) { try { android.os.Process.killProcess(p.pid) } catch (_: Exception) {}; killed = true }
            if (SystemClock.elapsedRealtime() > until) return
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Tor's own settings, beside the ones TorService writes: the SOCKS port on 127.0.0.1 picked by Tor (never 9050, so
     * another Tor app keeps its own), each relay and site on its own circuit (IsolateDestAddr), no HTTP tunnel, not
     * dormant after a start, and as little written to storage as Tor allows.
     */
    private fun writeTorrc(app: Context) {
        File(torDir(app), "torrc").writeText(
            "SOCKSPort 127.0.0.1:auto IsolateDestAddr\nHTTPTunnelPort 0\nAvoidDiskWrites 1\nClientOnly 1\nDormantCanceledByStartup 1\n")
    }

    /** Every second while Tor runs: its SOCKS port and whether it has a circuit, or how far its start is; and the watchdog. */
    private fun watch(app: Context) {
        val t = Thread({
            val me = Thread.currentThread()
            var ctl: Control? = null
            val started = SystemClock.elapsedRealtime()
            var lastLook = started
            var lastAnswer = started   // the control socket last answered
            var hadPort = false
            var hadCircuit = false
            var lostAt = 0L            // when the circuit went (0: has one, or never had one)
            var nudged = false
            while (watching === me) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastLook > SLEPT_MS) {
                    // The phone slept: Tor slept too. The clocks start over, and Tor is woken now.
                    lastAnswer = now
                    if (lostAt != 0L) lostAt = now
                    since = now
                    nudged = false
                    try { ctl?.signal("ACTIVE") } catch (_: Exception) { ctl?.close(); ctl = null }
                }
                lastLook = now
                try {
                    val c = ctl ?: Control.open(controlSocket(app)).also { ctl = it }
                    val port = c.socksPort()
                    val circuit = c.info("status/circuit-established") == "1"
                    lastAnswer = now
                    if (port > 0) { hadPort = true; failedStarts = 0 }
                    if (port > 0 && circuit) {
                        hadCircuit = true; lostAt = 0L; nudged = false
                        UltraPrivacy.ready(port)
                    } else {
                        if (hadCircuit && lostAt == 0L) { lostAt = now; since = now }
                        val phase = c.info("status/bootstrap-phase")
                        val ms = now - since
                        if (port > 0) UltraPrivacy.waiting(port, UltraPrivacy.percentOf(phase), ms, phase.startsWith("WARN"))
                        else UltraPrivacy.starting(UltraPrivacy.percentOf(phase), ms, phase.startsWith("WARN"))
                        // Had a circuit, lost it (idle, the phone slept, the network changed): wake Tor, then start over.
                        if (lostAt != 0L && !nudged && now - lostAt >= NUDGE_MS) {
                            nudged = true
                            try { c.signal("ACTIVE"); c.signal("NEWNYM") } catch (_: Exception) {}
                        }
                        if (lostAt != 0L && now - lostAt >= RESTART_MS && restartAllowed(now)) { restart(app, me); return@Thread }
                    }
                } catch (_: Exception) {
                    ctl?.close(); ctl = null
                    UltraPrivacy.starting(0, now - since)
                    // Tor isn't answering at all (its process died, or Tor stopped inside it): a fresh start.
                    if (now - lastAnswer >= DEAD_MS && restartAllowed(now)) {
                        if (!hadPort) failedStarts++
                        if (failedStarts >= MAX_FAILED_STARTS) {
                            // m13: Tor can't run here (a crash, a missing library). Fail closed and say so; nothing more is tried.
                            UltraPrivacy.missing()
                            if (watching === me) bg.execute { synchronized(lock) { if (watching === me) stop(app) } }
                            return@Thread
                        }
                        restart(app, me); return@Thread
                    }
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
            ctl?.close()
        }, "muninn-tor-watch").apply { isDaemon = true }
        watching = t
        t.start()
    }

    @Volatile private var failedStarts = 0
    @Volatile private var lastRestart = 0L

    private fun restartAllowed(now: Long) = lastRestart == 0L || now - lastRestart >= RESTART_GAP_MS

    private fun restart(app: Context, from: Thread) {
        lastRestart = SystemClock.elapsedRealtime()
        bg.execute { if (watching === from) start(app, again = true) }
    }

    /** Tor's control protocol over its control socket: just what the watcher needs (no password: the socket is private). */
    private class Control(private val s: LocalSocket) {
        private val input = s.inputStream.bufferedReader(Charsets.UTF_8)
        private val output = s.outputStream

        fun close() { try { s.close() } catch (_: Exception) {} }

        /** Sends [line] and reads the reply: its "250" lines, or throws on an error code. */
        @Synchronized private fun ask(line: String): List<String> {
            output.write((line + "\r\n").toByteArray(Charsets.UTF_8)); output.flush()
            val out = ArrayList<String>()
            while (true) {
                val l = input.readLine() ?: throw java.io.IOException("closed")
                if (l.length < 4) throw java.io.IOException("bad reply")
                val code = l.substring(0, 3)
                if (!code.startsWith("2")) throw java.io.IOException("Tor said $code")
                when (l[3]) {
                    ' ' -> return out
                    '-' -> out += l.substring(4)
                    '+' -> {
                        // A value over several lines, ending with ".".
                        val sb = StringBuilder(l.substring(4))
                        while (true) { val d = input.readLine() ?: throw java.io.IOException("closed"); if (d == ".") break; sb.append('\n').append(d) }
                        out += sb.toString()
                    }
                    else -> throw java.io.IOException("bad reply")
                }
            }
        }

        /** GETINFO [key]: its value ("" when Tor gives none). */
        fun info(key: String): String {
            val pre = "$key="
            val all = ask("GETINFO $key")
            val l = all.firstOrNull { it.startsWith(pre) } ?: all.firstOrNull { it.startsWith(key) } ?: return ""
            return l.substringAfter('=').trim().removeSurrounding("\"")
        }

        /** Tor's SOCKS port on 127.0.0.1, or 0 while it has none. */
        fun socksPort(): Int {
            val v = info("net/listeners/socks")   // "127.0.0.1:12345", maybe several, each in quotes
            val first = v.split(' ').firstOrNull { it.isNotBlank() }?.trim('"') ?: return 0
            return first.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
        }

        fun signal(name: String) { ask("SIGNAL $name") }

        companion object {
            fun open(path: File): Control {
                if (!path.exists()) throw java.io.IOException("no control socket yet")
                val s = LocalSocket(LocalSocket.SOCKET_STREAM)
                try {
                    s.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    s.soTimeout = 5_000
                    val c = Control(s)
                    c.ask("AUTHENTICATE")
                    return c
                } catch (e: Exception) { try { s.close() } catch (_: Exception) {}; throw e }
            }
        }
    }
}

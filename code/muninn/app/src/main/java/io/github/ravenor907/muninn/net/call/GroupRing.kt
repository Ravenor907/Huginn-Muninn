package io.github.ravenor907.muninn.net.call

import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.screens.RoomActivity
import io.github.ravenor907.muninn.ui.Ui
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * When a group call made from the chat list (Group call on picked people) rings. Plain Kotlin so the tests
 * can check it. People put straight in (op "add") only know the group once its state reached them, so the
 * call waits for their delivered receipts (at most [WAIT_MS]); anyone the group reaches later (a slow
 * route, or someone invited who accepts) is rung as soon as it does, while the call is still ringing.
 */
object GroupRingLogic {
    /** Longest wait for the group to reach everyone before ringing anyway. */
    const val WAIT_MS = 8_000L
    const val POLL_MS = 500L
    /** Late people are rung for as long as the call itself rings. */
    const val LATE_MS = RoomLogic.RING_MS

    /** Ring now: everyone who can be rung has the group, or the wait is over. */
    fun ready(ringable: Set<String>, waiting: Set<String>, waitedMs: Long): Boolean =
        waitedMs >= WAIT_MS || (ringable.isNotEmpty() && ringable.none { it in waiting })

    /** Who to ring now: can be rung, has the group, not rung yet. */
    fun late(ringable: Set<String>, waiting: Set<String>, rung: Set<String>): Set<String> = ringable - waiting - rung

    /** Picked people who were never rung, in the order they were picked. */
    fun notRung(wanted: List<String>, rung: Set<String>): List<String> = wanted.filter { it !in rung }
}

/** Runs [GroupRingLogic] for a group just made from the chat list. */
object GroupRing {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "group-ring").apply { isDaemon = true } }
    @Volatile private var job: ScheduledFuture<*>? = null

    /** Ring [wanted] (the people picked) into a new group call of [g] once the group has reached them. */
    fun start(app: Muninn, g: String, wanted: List<String>) {
        job?.cancel(false)
        val began = System.currentTimeMillis()
        var calledAt = 0L
        val rung = HashSet<String>()
        var self: ScheduledFuture<*>? = null
        fun names(keys: List<String>) = keys.joinToString(", ") { app.chat.nameOf(it) }
        self = exec.scheduleWithFixedDelay({
            try {
                val now = System.currentTimeMillis()
                val ringable = RoomManager.ringable(g)
                val waiting = app.chat.groups.stateWaiting(g)
                if (calledAt == 0L) {
                    if (!GroupRingLogic.ready(ringable, waiting, now - began)) return@scheduleWithFixedDelay
                    if (ringable.isEmpty()) {
                        Ui.say("Couldn't ring ${names(wanted)}: they get the call once they have the group.", true)
                        self?.cancel(false); return@scheduleWithFixedDelay
                    }
                    calledAt = now
                    // Everyone who can join is rung; those the group hasn't reached yet are rung again once it does.
                    rung += ringable - waiting
                    RoomActivity.call(app, g, emptyList())
                    val missing = GroupRingLogic.notRung(wanted, rung)
                    if (missing.isNotEmpty()) Ui.say("Not rung yet: ${names(missing)}. They're rung once the group reaches them.")
                    return@scheduleWithFixedDelay
                }
                val late = GroupRingLogic.late(ringable, waiting, rung)
                if (late.isNotEmpty()) rung += RoomManager.ringMore(g, late)
                val missing = GroupRingLogic.notRung(wanted, rung)
                if (missing.isEmpty()) { self?.cancel(false); return@scheduleWithFixedDelay }
                if (now - calledAt >= GroupRingLogic.LATE_MS) {
                    Ui.say("Couldn't ring ${names(missing)}.", true)
                    self?.cancel(false)
                }
            } catch (_: Exception) {}
        }, GroupRingLogic.POLL_MS, GroupRingLogic.POLL_MS, TimeUnit.MILLISECONDS)
        job = self
    }
}

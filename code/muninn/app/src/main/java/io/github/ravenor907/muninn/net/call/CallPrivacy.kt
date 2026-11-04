package io.github.ravenor907.muninn.net.call

import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.AppLock

/**
 * 1.42: who a 1:1 call shows. While Muninn's own lock is up (or the vault is still closed after a restart), the call
 * screen and the call's notifications name nobody and show no picture: "Incoming call" while it rings, "Muninn call"
 * after. Once Muninn is unlocked the name and picture come back. The rules are pure, so they're tested off the phone.
 */
object CallPrivacy {
    const val RINGING = "Incoming call"
    const val RINGING_VIDEO = "Incoming video call"
    const val ON_CALL = "Muninn call"

    /**
     * Muninn's lock counts for a call: it's up ([raised]), or the vault hasn't been opened since Muninn started
     * ([vaultClosed]), or, while the call rings ([ringing]), it's due ([due]: Muninn would ask for it the moment a
     * window showed). Only with a lock set ([hasPassword]); the raised lock counts even so (a panic lock).
     */
    fun locked(raised: Boolean, hasPassword: Boolean, vaultClosed: Boolean, due: Boolean, ringing: Boolean): Boolean =
        raised || (hasPassword && (vaultClosed || (ringing && due)))

    /**
     * The name hides when Muninn is panic-locked ([panic]), when Muninn's lock counts ([muninnLocked], see [locked]),
     * or on the phone's lock screen ([keyguard]) when notifications don't show names ([previews] off).
     */
    fun hide(panic: Boolean, muninnLocked: Boolean, previews: Boolean, keyguard: Boolean): Boolean =
        panic || muninnLocked || (!previews && keyguard)

    /** The title the call screen shows: the [name], or, hidden, "Incoming call" (or video) while it rings, else "Muninn call". */
    fun title(name: String, hide: Boolean, ringing: Boolean, video: Boolean): String = when {
        !hide -> name
        ringing -> if (video) RINGING_VIDEO else RINGING
        else -> ON_CALL
    }

    /** The line under a hidden ringing title: it already says "Incoming call", so only "Muninn" (as the notification says). */
    fun statusUnder(title: String, status: String): String = if (title == status) "Muninn" else status

    /** Muninn's lock counts now for a call that is ringing ([ringing]) or not. Hidden if anything can't be read. */
    fun lockedNow(app: Muninn?, ringing: Boolean): Boolean {
        if (AppLock.isLocked) return true
        val a = app ?: return false
        return try {
            val pw = a.vault.hasPassword
            locked(false, pw, a.vault.locked.value,
                AppLock.due(pw, AppLock.awaySince, android.os.SystemClock.elapsedRealtime(), a.identity.lockAfter), ringing)
        } catch (_: Exception) { true }
    }
}

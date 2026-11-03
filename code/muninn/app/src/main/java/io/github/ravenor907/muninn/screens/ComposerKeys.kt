package io.github.ravenor907.muninn.screens

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager

/** What a key does in the message box. Pure, so it can be tested; [composerKey] applies it. */
object ComposerKeys {
    const val NONE = 0
    const val SEND = 1
    const val NEXT = 2
    const val PREV = 3
    /** android.view.KeyEvent's codes. */
    const val ENTER = 66
    const val NUMPAD_ENTER = 160
    const val TAB = 61

    /**
     * Enter (or the number pad's) on a real keyboard sends; with Shift it's a new line. A keyboard on the screen keeps
     * Enter for new lines. Tab moves on to the next control and Shift+Tab back, so Tab is never typed into a message.
     */
    fun action(keyCode: Int, shift: Boolean, physical: Boolean): Int = when (keyCode) {
        ENTER, NUMPAD_ENTER -> if (physical && !shift) SEND else NONE
        TAB -> if (shift) PREV else NEXT
        else -> NONE
    }
}

/**
 * What the message box does with a value it reports after a hardware Enter sent [sent]. Pure, so it can be tested;
 * the Composer applies it. The box's report can run before the screen has caught up with the send, so a report of the
 * text just sent is never typing (it would put the sent text back and make it go twice). An IME that had letters
 * open (Korean, Japanese) may put them back into the emptied box a moment later: a piece of the end of the sent text,
 * right after the box was seen empty, is cleared again.
 */
class BoxEcho {
    private var sent: String? = null
    private var at = 0L
    private var sawEmpty = false

    /** Enter sent [text] at [now] (ms). */
    fun send(text: String, now: Long) { sent = text; at = now; sawEmpty = false }

    /** The box now holds [box]; [text] is the last text the screen has. One of [PASS], [SKIP] or [CLEAR]. */
    fun seen(box: String, text: String, now: Long): Int {
        val s = sent
        if (s != null) {
            val fresh = now - at in 0..ECHO_MS
            when {
                box == s -> return SKIP
                box.isEmpty() -> sawEmpty = true
                fresh && sawEmpty && s.endsWith(box) -> { sent = null; return CLEAR }
                else -> sent = null
            }
            if (!fresh) sent = null
        }
        return if (box != text) PASS else SKIP
    }

    companion object {
        const val PASS = 0
        const val SKIP = 1
        const val CLEAR = 2
        /** How long after Enter an IME's letters coming back count as an echo. */
        const val ECHO_MS = 400L
    }
}

/** The message box's keys ([ComposerKeys]): true when the key was used here. Acts on key down; its key up is used too. */
internal fun composerKey(ev: androidx.compose.ui.input.key.KeyEvent, focus: FocusManager, send: () -> Unit): Boolean {
    val n = ev.nativeKeyEvent
    val down = n.action == android.view.KeyEvent.ACTION_DOWN
    val physical = n.device?.isVirtual == false
    return when (ComposerKeys.action(n.keyCode, n.isShiftPressed, physical)) {
        ComposerKeys.SEND -> { if (down && n.repeatCount == 0) send(); true }
        ComposerKeys.NEXT -> { if (down) focus.moveFocus(FocusDirection.Next); true }
        ComposerKeys.PREV -> { if (down) focus.moveFocus(FocusDirection.Previous); true }
        else -> false
    }
}

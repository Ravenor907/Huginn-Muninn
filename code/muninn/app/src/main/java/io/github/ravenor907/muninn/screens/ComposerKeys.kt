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

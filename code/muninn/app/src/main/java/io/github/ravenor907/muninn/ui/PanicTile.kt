package io.github.ravenor907.muninn.ui

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile "Lock Muninn": one tap from anywhere locks Muninn at once. */
class PanicTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (Panic.lock(this)) refresh(justLocked = true)
        else refresh()
    }

    private fun refresh(justLocked: Boolean = false) {
        val tile = qsTile ?: return
        val noLock = Panic.needsLock(this)
        val on = Panic.enabled(this) && !noLock
        tile.label = "Lock Muninn"
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            noLock -> "Set a lock first"
            !on -> "Off in Settings"
            justLocked || Panic.locked.value -> "Locked"
            else -> null
        }
        tile.updateTile()
    }
}

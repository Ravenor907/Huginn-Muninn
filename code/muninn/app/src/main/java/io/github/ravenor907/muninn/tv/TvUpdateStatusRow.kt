package io.github.ravenor907.muninn.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.ravenor907.muninn.Muninn

/**
 * Settings › Updates and about: one status line per paired Kids' TV ("Living room TV", "Muninn 1.33 · Up to date"), each
 * a tap to that TV's own page. A link only: the TV's Update now and Auto-update stay on its page (decision D1).
 * Drawn inside the Updates card, under "Update automatically". Nothing when this phone looks after no TV.
 */
@Composable
fun TvUpdateStatusRow(app: Muninn) {
    val tvs by TvParent.tvs.collectAsState()
    @Suppress("UNUSED_VARIABLE") val up by app.updater.state.collectAsState()   // "Update available" follows this phone's checks
    val contacts by app.contacts.all.collectAsState()
    for (t in tvs.values.filter { !it.disconnecting }) {
        val name = contacts.firstOrNull { it.chatKey == t.key }?.shown ?: "Kids' TV"
        io.github.ravenor907.muninn.ui.NavRow(name, tvUpdateLine(app, t), icon = "tv") {
            io.github.ravenor907.muninn.screens.SettingsNav.folder = "tv:" + t.key
        }
    }
}

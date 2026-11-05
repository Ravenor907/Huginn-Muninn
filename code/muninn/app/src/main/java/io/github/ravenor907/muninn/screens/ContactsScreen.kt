package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Palette
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * The Contacts tab: everyone you're in touch with or met close by, and the blocked list. Tap someone to open
 * their chat; their three dots (or holding them) open the same menu as everywhere else. Group peers (in a group with
 * you, not contacts) aren't listed. [back]: shows a Back button (null in the tab, where the header names the page).
 */
@Composable
fun ContactsScreen(app: Muninn, back: (() -> Unit)? = null, onChats: () -> Unit = {}) {
    val contacts by app.chat.contacts.all.collectAsState()
    val met by app.chat.met.collectAsState()
    val blocked by app.chat.blocked.collectAsState()
    val trashed by app.chat.trash.all.collectAsState()
    data class P(val key: String, val name: String, val detail: String)
    // A nickname shows as "Nickname (their name)". Group peers aren't contacts: they're not listed, and nor is anyone in the trash.
    val real = contacts.filter { !it.groupOnly && it.chatKey !in trashed }
    val people = (real.map { P(it.chatKey, ChatWire.listName(it), if (it.keyChanged) "Security key changed" else "") } +
        met.filterKeys { k -> real.none { it.chatKey == k } && k !in trashed }.map { (k, m) -> P(k, m.name, "Met close by") })
    val active = people.filter { it.key !in blocked }.sortedBy { it.name.lowercase() }
    val off = blocked.map { k -> people.firstOrNull { it.key == k } ?: P(k, app.chat.nameOf(k), "") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (back != null) Section("Contacts") { MButton("Back", icon = "back") { back() } }
        LazyColumn(Modifier.weight(1f)) {
            if (active.isEmpty()) item {
                Text("No one yet.", style = Type.normal, modifier = Modifier.padding(top = 16.dp))
                MButton("Add someone", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, icon = "plus") { ChatNav.open = "add"; onChats() }
            } else item {
                io.github.ravenor907.muninn.ui.Framed {
                    active.forEach { p ->
                        PersonLine(p.key, p.name, p.detail, onMenu = { ChatNav.menuGroup = ""; ChatNav.hold = p.key }) { ChatNav.space = ""; ChatNav.open = p.key; onChats() }
                    }
                }
            }
            if (off.isNotEmpty()) item {
                io.github.ravenor907.muninn.ui.Framed("Blocked") {
                    off.forEach { p ->
                        PersonLine(p.key, p.name, "Tap to unblock") {
                            Ui.sure(Ask("Unblock ${p.name}?", "Their messages reach you again.", "Unblock",
                                onConfirm = { app.chat.block(p.key, false) }))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** One person in the list: their face with the status dot, name and a line under it, then their three dots ([onMenu], also on hold). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PersonLine(key: String, name: String, detail: String, onMenu: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(io.github.ravenor907.muninn.ui.Corner).combinedClickable(onLongClickLabel = if (onMenu != null) "Menu" else null,
            onLongClick = onMenu, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.ravenor907.muninn.ui.Avatar(key, name, 40.dp, showDot = true)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = Type.small, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (onMenu != null) io.github.ravenor907.muninn.ui.DotsButton("Menu for $name", Palette.textMuted) { onMenu() }
    }
}

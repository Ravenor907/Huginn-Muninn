package io.github.ravenor907.muninn.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Trash
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * Contacts in the trash: each can be put back (with their chat) or deleted for good, or the whole trash
 * emptied. Anyone left here 30 days is deleted for good on its own. From the chat list's More row.
 */
@Composable
fun TrashScreen(app: Muninn, back: () -> Unit) {
    BackHandler { back() }
    val trash by app.chat.trash.all.collectAsState()
    val contacts by app.chat.contacts.all.collectAsState()
    val convos by app.chat.conversations.collectAsState()
    val now = System.currentTimeMillis()
    val people = trash.entries.sortedByDescending { it.value }.map { (k, t) ->
        Triple(k, contacts.firstOrNull { it.chatKey == k }?.let { ChatWire.listName(it) } ?: app.chat.nameOf(k), t)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Section("Trash") { MButton("Back", icon = "back") { back() } }
        Text("Deleted for good after ${Trash.DAYS} days.", style = Type.small, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (people.isEmpty()) item { Text("The trash is empty.", style = Type.normal) }
            items(people, key = { it.first }) { (k, name, since) ->
                val days = Trash.daysLeft(since, now)
                val n = convos[k].orEmpty().size
                Panel(Modifier.padding(bottom = 8.dp)) {
                    Text(name, style = Type.big)
                    Text((if (days == 1) "Under a day left" else "$days days left") +
                        (if (n == 0) "" else " · ${if (n == 1) "1 message" else "$n messages"}"), style = Type.small)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Restore", Modifier.weight(1f), Kind.Accent, icon = "undo") { app.chat.restoreContact(k); Ui.say("$name is back, with your chat.") }
                        MButton("Delete for good", Modifier.weight(1f), Kind.Danger) {
                            Ui.sure(Ask("Delete $name for good?", "This can't be undone.",
                                "Delete", danger = true, hold = true, spoken = "Hold for 5 seconds to delete $name for good",
                                onConfirm = { app.chat.deleteForGood(k); Ui.say("$name deleted.") }))
                        }
                    }
                }
            }
            // Last, after everyone in it.
            if (people.isNotEmpty()) item {
                MButton("Empty trash", Modifier.fillMaxWidth().padding(vertical = 12.dp), Kind.Danger, icon = "delete") {
                    Ui.sure(Ask("Empty the trash?", "This can't be undone.", "Hold to empty",
                        danger = true, hold = true, spoken = "Hold for 5 seconds to empty the trash") { app.chat.emptyTrash(); Ui.say("Trash emptied.") })
                }
            }
        }
    }
}

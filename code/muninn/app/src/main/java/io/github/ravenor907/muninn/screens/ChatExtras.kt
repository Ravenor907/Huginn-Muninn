package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Rich
import io.github.ravenor907.muninn.net.Voice
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.hcBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Messenger-style extras for conversations (cap m1): reactions, the emoji picker, replies, the message
// sheet, search in a chat and the photos and voice of a chat.

private val small get() = TextStyle(fontFamily = Type.body, fontSize = 13.sp, color = Palette.textMuted)
private val body get() = TextStyle(fontFamily = Type.body, fontSize = 16.sp, color = Palette.text)

/** The emoji the picker offers, most used first. Any of them can be a reaction or go in a message. */
object Emoji {
    val ALL: List<String> = (
        "❤️ 😂 👍 😮 😢 🙏 😀 😃 😄 😁 😆 😅 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 " +
        "😣 😖 😫 😩 🥺 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 " +
        "🤒 🤕 🤑 🤠 😈 👿 👻 💀 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾 👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👎 ✊ 👊 🤛 🤜 👏 🙌 " +
        "👐 🤲 🤝 💪 🧠 👀 👁️ 💋 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟 ⚡ ☀️ 🌙 🌈 ☁️ ❄️ " +
        "🌊 🌲 🌳 🌿 🍀 🍁 🍂 🌸 🌹 🌻 🐺 🦅 🐦 🐻 🐗 🐴 🐍 🐉 🐟 🐈 🐕 🍎 🍕 🍔 🌮 🍗 🍖 🧀 🍞 🍺 🍻 🍷 ☕ 🍵 🎂 🍰 🍪 🎉 🎊 🎁 🎈 🏆 🥇 " +
        "⚔️ 🛡️ 🏹 🪓 🔨 ⚒️ 🪵 🔮 🕯️ 🎮 🕹️ 🎲 🎧 🎵 🎶 🎸 🥁 📷 📞 💻 📱 🔋 💡 📌 📎 ✏️ 📝 📚 🗺️ 🚗 🚀 ✈️ 🏠 ⛺ 🏔️ ⛰️ 🌋 🏕️ ✅ ❌ ❓ ❗ " +
        "⚠️ 🚫 💤 🆗 🆒 🆕 ➕ ➖ 👏🏽 👍🏻 👍🏽 👍🏿 🙏🏽 💪🏽"
        ).split(' ').filter { it.isNotBlank() }
}

/** Pick any emoji, for a reaction or to put in the message being written. */
@Composable
internal fun EmojiPicker(title: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            LazyVerticalGrid(GridCells.Adaptive(46.dp), Modifier.heightIn(max = 380.dp).fillMaxWidth()) {
                items(Emoji.ALL) { e ->
                    Box(Modifier.size(46.dp).clip(CircleShape).clickable(role = Role.Button) { onPick(e) }.semantics { contentDescription = e },
                        contentAlignment = Alignment.Center) { Text(e, style = TextStyle(fontSize = 26.sp)) }
                }
            }
        },
        confirmButton = { MButton("Close") { onDismiss() } },
    )
}

/**
 * One thing the message sheet can do. [folder] "" puts it in the main list, a name puts it in that sub-sheet
 * ("Message"); [info] is a line of facts, not a button; [danger] ones go at the bottom in red.
 */
internal data class SheetAction(val label: String, val icon: String, val danger: Boolean = false, val folder: String = "",
                                val info: Boolean = false, val run: () -> Unit = {})

/** Where each action goes in the message sheet: the main rows, the folders (by name), and the red ones at the bottom. */
internal object SheetLayout {
    data class Plan(val primary: List<SheetAction>, val folders: List<Pair<String, List<SheetAction>>>, val danger: List<SheetAction>)

    /** A folder with one plain action shows it as a main row instead; a folder with facts in it stays a folder. */
    fun plan(actions: List<SheetAction>): Plan {
        val danger = actions.filter { it.danger }
        val rest = actions.filter { !it.danger }
        val primary = ArrayList(rest.filter { it.folder.isEmpty() })
        val folders = ArrayList<Pair<String, List<SheetAction>>>()
        rest.filter { it.folder.isNotEmpty() }.groupBy { it.folder }.forEach { (name, list) ->
            if (list.size == 1 && !list[0].info) primary.add(list[0]) else folders.add(name to list)
        }
        return Plan(primary, folders, danger)
    }
}

@Composable
private fun SheetRow(label: String, icon: String, danger: Boolean = false, more: Boolean = false, onClick: (() -> Unit)?) {
    val shape = io.github.ravenor907.muninn.ui.Corner
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button) { onClick() } else Modifier)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, if (danger) Palette.danger else Palette.accent, 22.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = body.copy(fontSize = 17.sp, color = if (danger) Palette.danger else Palette.text), modifier = Modifier.weight(1f))
        if (more) Text("›", style = body.copy(fontSize = 20.sp, color = Palette.textMuted))
    }
}

@Composable
private fun SheetHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction("back", "Back", Palette.accent, size = 20.dp) { onBack() }
        Spacer(Modifier.width(6.dp))
        Text(title, style = Type.big)
    }
}

/**
 * Hold a message: the quick reactions and + for any emoji (tap the one you gave to take it back), the main
 * actions as large rows, then folders ("Message ›", "Read by ›") that open their own page, and the red ones last.
 * [readBy] draws the "Read by" page (groups, my messages only).
 */
@Composable
internal fun MessageSheet(mineReaction: String?, canReact: Boolean, actions: List<SheetAction>, onReact: (String) -> Unit,
                          readBy: (@Composable () -> Unit)? = null, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("") }
    if (picking) {
        EmojiPicker("React with", { e -> picking = false; onDismiss(); onReact(if (e == mineReaction) "" else e) }) { picking = false }
        return
    }
    val plan = remember(actions) { SheetLayout.plan(actions) }
    val shape = io.github.ravenor907.muninn.ui.Corner
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.panel,
        shape = shape,
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val folder = plan.folders.firstOrNull { it.first == page }?.second
                when {
                    page == READ_BY && readBy != null -> { SheetHeader("Read by") { page = "" }; readBy() }
                    folder != null -> {
                        SheetHeader(page) { page = "" }
                        folder.filter { !it.info }.forEach { a -> SheetRow(a.label, a.icon) { onDismiss(); a.run() } }
                        val facts = folder.filter { it.info }
                        if (facts.isNotEmpty()) {
                            Text("INFO", style = Type.section, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp))
                            facts.forEach { a ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp).semantics(mergeDescendants = true) {},
                                    verticalAlignment = Alignment.CenterVertically) {
                                    MIcon(a.icon, Palette.textMuted, 18.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Text(a.label, style = body.copy(color = Palette.textSoft))
                                }
                            }
                        }
                    }
                    else -> {
                        if (canReact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            (Rich.QUICK + listOfNotNull(mineReaction?.takeIf { it !in Rich.QUICK })).forEach { e ->
                                val on = e == mineReaction
                                Box(Modifier.size(48.dp).clip(shape).background(if (on) Palette.goldTint else Palette.raised)
                                    .then(if (on) Modifier.border(2.dp, Palette.gold, shape) else Modifier)
                                    .clickable(role = Role.Button) { onDismiss(); onReact(if (on) "" else e) }
                                    .semantics { contentDescription = if (on) "Take back $e" else "React $e" },
                                    contentAlignment = Alignment.Center) { Text(e, style = TextStyle(fontSize = 24.sp)) }
                            }
                            Box(Modifier.size(48.dp).clip(shape).background(Palette.raised).clickable(role = Role.Button) { picking = true }
                                .semantics { contentDescription = "More reactions" }, contentAlignment = Alignment.Center) { MIcon("plus", Palette.accent, 20.dp, weight = 2f) }
                        }
                        plan.primary.forEach { a -> SheetRow(a.label, a.icon) { onDismiss(); a.run() } }
                        plan.folders.forEach { (name, list) -> SheetRow(name, if (list.all { it.info }) "info" else "more", more = true) { page = name } }
                        if (readBy != null) SheetRow(READ_BY, "check", more = true) { page = READ_BY }
                        if (plan.danger.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Palette.raised))
                            plan.danger.forEach { a -> SheetRow(a.label, a.icon, danger = true) { onDismiss(); a.run() } }
                        }
                    }
                }
            }
        },
        confirmButton = { MButton(if (page.isEmpty()) "Cancel" else "Close") { onDismiss() } },
    )
}

private const val READ_BY = "Read by"

/**
 * The "Read by" page of a group message of mine: who read it (picture, name, when), whose phone has it, whose
 * app doesn't send read receipts ("Not shown"), and who doesn't have it yet.
 */
@Composable
internal fun ReadByList(r: io.github.ravenor907.muninn.net.Groups.ReadBy, nameOf: (String) -> String, timeOf: (Long) -> String) {
    @Composable fun part(title: String, keys: List<Pair<String, Long>>) {
        if (keys.isEmpty()) return
        Text(title.uppercase(), style = Type.section, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp))
        keys.forEach { (k, t) ->
            val n = nameOf(k)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                io.github.ravenor907.muninn.ui.Avatar(k, n, 34.dp)
                Spacer(Modifier.width(12.dp))
                Text(n, style = body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (t > 0) Text(timeOf(t), style = small)
            }
        }
    }
    part("Read", r.read)
    part("Delivered to", r.delivered.map { it to 0L })
    part("Not yet", r.notYet.map { it to 0L })
    part("Not shown", r.notShown.map { it to 0L })
    if (r.read.isEmpty() && r.delivered.isEmpty() && r.notYet.isEmpty() && r.notShown.isEmpty())
        Text("Nobody else is in this group.", style = small, modifier = Modifier.padding(4.dp))
}

/** The reactions under a bubble: each emoji once with how many chose it; mine is outlined. Tap to see or change. */
@Composable
internal fun ReactionChips(all: Map<String, Rich.Reaction>, me: String, alignEnd: Boolean, onTap: () -> Unit) {
    val counts = Rich.counts(all)
    if (counts.isEmpty()) return
    val mine = all[me]?.e?.takeIf { it.isNotEmpty() }
    Row(Modifier.padding(top = 2.dp).fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        val shape = io.github.ravenor907.muninn.ui.Corner
        Row(Modifier.clip(shape).background(Palette.raised).then(if (mine != null) Modifier.border(1.dp, Palette.gold, shape) else Modifier.hcBorder(shape))
            .clickable(role = Role.Button) { onTap() }.padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = counts.joinToString(", ") { (e, n) -> "$e $n" } + (if (mine != null) ", yours $mine" else "") },
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            counts.take(6).forEach { (e, n) ->
                Text(e, style = TextStyle(fontSize = 15.sp))
                if (n > 1) Text("$n", style = small.copy(fontSize = 12.sp))
            }
        }
    }
}

/** The quoted message at the top of a reply. */
@Composable
internal fun QuoteBlock(name: String, text: String, onClick: () -> Unit) {
    val shape = io.github.ravenor907.muninn.ui.Corner
    // The gold bar runs the full height of the quote, however tall large text makes it.
    Row(Modifier.padding(bottom = 4.dp).height(IntrinsicSize.Min).clip(shape).background(Palette.groundDeep).clickable(role = Role.Button, onClickLabel = "Show the message") { onClick() }) {
        Box(Modifier.width(3.dp).heightIn(min = 38.dp).fillMaxHeight().background(Palette.gold))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(name, style = small.copy(fontWeight = FontWeight.SemiBold, color = Palette.goldBright), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Over the message box: "Replying to Raven" or "Editing", with an X. */
@Composable
internal fun ComposeBanner(title: String, text: String, icon: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp).clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.raised)
        .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, Palette.gold, 18.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(title, style = small.copy(fontWeight = FontWeight.SemiBold, color = Palette.goldBright), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction("close", "Cancel", Palette.textMuted, size = 18.dp) { onClose() }
    }
}

/** Search inside one chat: the box, how many match, and up and down through them (newest first). */
@Composable
internal fun ChatSearchBar(query: String, onQuery: (String) -> Unit, found: Int, at: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val shape = io.github.ravenor907.muninn.ui.Corner
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 42.dp).clip(shape).background(Palette.field).hcBorder(shape).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MIcon("search", Palette.textMuted, 18.dp)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 9.dp)) {
                if (query.isEmpty()) Text("Search in this chat", style = body.copy(color = Palette.textMuted), maxLines = 1)
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                BasicTextField(query, onQuery, singleLine = true, textStyle = body, cursorBrush = SolidColor(Palette.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search in this chat" })
                androidx.compose.runtime.LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
            }
            if (query.isNotEmpty()) Text(if (found == 0) "None" else "${at + 1} of $found", style = small, maxLines = 1)
        }
        IconAction("back", "Older match", if (found > 0) Palette.text else Palette.textFaint, Modifier.padding(start = 2.dp), 18.dp) { onPrev() }
        IconAction("forward", "Newer match", if (found > 0) Palette.text else Palette.textFaint, size = 18.dp) { onNext() }
        IconAction("close", "Close search", Palette.textMuted, size = 18.dp) { onClose() }
    }
}

/** "Raven is typing" under the last message. */
@Composable
internal fun TypingRow(name: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp).semantics { contentDescription = "$name is typing" },
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.field).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.textMuted)) }
        }
        Text("  $name is typing", style = small)
    }
}

/** A number in a small circle: how many in a group have read a message. */
@Composable
internal fun Circled(n: Int) {
    Box(Modifier.size(17.dp).clip(CircleShape).border(1.dp, Palette.gold, CircleShape), contentAlignment = Alignment.Center) {
        // Sized to the circle, not the font size, so the number always fits inside it.
        val digits = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toSp() }
        Text(if (n > 99) "99" else "$n", style = small.copy(fontSize = digits, lineHeight = digits, fontWeight = FontWeight.SemiBold, color = Palette.gold), maxLines = 1, softWrap = false)
    }
}

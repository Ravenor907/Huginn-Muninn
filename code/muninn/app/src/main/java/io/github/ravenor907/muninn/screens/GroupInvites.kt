package io.github.ravenor907.muninn.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * Group invite links, Discord style: an admin (or any member, if the group allows it) makes a link that works for a
 * while and a number of times, shares it, and can take it back.
 *
 * With [ready] true, the functions below use the links (create, list, revoke, join, and the group's "members can
 * invite" switch). With it false, every call here uses the one-hour group code (Groups.code, revokeCodes and joinByCode).
 */
object GroupLinks {
    /** True when the links are used (cap j1, shared/docs/protocol.md section 17). */
    const val ready = true

    /** One link: who made it, how often it was used and may be, and when it stops working (0: never). */
    data class Link(val id: String, val text: String, val by: String, val uses: Int, val max: Int, val expires: Long)

    /** A link someone opened: which group, how many are in it, and what joining needs. */
    data class Preview(val name: String, val members: Int, val by: String, val expired: Boolean, val code: Proto06.Code?)

    /** Expire after, in ms (0: never), with its words. 7 days is picked first. */
    val EXPIRES = listOf(30 * 60_000L to "30 minutes", 3_600_000L to "1 hour", 6 * 3_600_000L to "6 hours", 12 * 3_600_000L to "12 hours",
        86_400_000L to "1 day", 7 * 86_400_000L to "7 days", 0L to "Never")
    /** Max uses (0: no limit). */
    val USES = listOf(0 to "No limit", 1 to "1", 5 to "5", 10 to "10", 25 to "25", 50 to "50", 100 to "100")
    const val DEFAULT_EXPIRE = Proto06.LINK_TTL_DEFAULT

    // ---- the links (Groups.createLink, links, revokeLink, setMembersInvite)

    /** Whether I may make links for [g]: an admin, or anyone when the group lets members invite. */
    fun canInvite(app: Muninn, g: String): Boolean {
        val grp = app.chat.groups.group(g) ?: return false
        if (grp.readOnly || !io.github.ravenor907.muninn.net.Limits.linksAllowed(grp.state)) return false   // Family: no links
        return grp.state.isAdmin(app.identity.publicKey) || membersCanInvite(app, g)
    }

    /** A new link, or why not. The admin it names counts its uses and expiry. */
    fun create(app: Muninn, g: String, expireMs: Long, maxUses: Int): Pair<Link?, String?> {
        val (link, err) = app.chat.groups.createLink(g, expireMs, maxUses)
        if (link == null) return null to (err ?: "Couldn't make a link.")
        val token = Proto06.parseCode(link)?.i ?: ""
        return Link(Proto06.linkId(token), link, app.identity.publicKey, 0, maxUses, if (expireMs == 0L) 0L else System.currentTimeMillis() + expireMs) to null
    }

    /** The group's active links: admins see all, members their own. */
    fun list(app: Muninn, g: String): List<Link> =
        app.chat.groups.links(g).map { Link(it.id, it.link, it.byName.ifBlank { app.chat.nameOf(it.by) }, it.uses, it.max, it.x) }

    fun revoke(app: Muninn, g: String, id: String) { app.chat.groups.revokeLink(g, id)?.let { Ui.say(it, true) } }

    fun membersCanInvite(app: Muninn, g: String): Boolean = app.chat.groups.group(g)?.state?.let { io.github.ravenor907.muninn.net.Limits.membersMayInvite(it) } == true
    fun setMembersCanInvite(app: Muninn, g: String, on: Boolean): String? = app.chat.groups.setMembersInvite(g, on)

    /** What a pasted or scanned link says, or null if it isn't one. Falls back to a group code. */
    fun parse(app: Muninn, text: String): Preview? {
        val c = Proto06.parseCode(text) ?: return null
        val known = app.chat.groups.group(c.g)
        return Preview(c.gn, known?.state?.members?.size ?: 0, c.an, c.x * 1000 < System.currentTimeMillis(), c)
    }

    /** Join from a link. Null when asked, else why not. */
    fun join(app: Muninn, p: Preview): String? = p.code?.let { app.chat.groups.joinByCode(it) } ?: "That link doesn't work any more."

    // ---- words

    fun left(expires: Long): String {
        if (expires <= 0) return "never ends"
        val ms = expires - System.currentTimeMillis()
        return when {
            ms <= 0 -> "ended"
            ms < 3_600_000L -> "${(ms + 59_999) / 60_000} min left"
            ms < 86_400_000L -> "${ms / 3_600_000L} h left"
            else -> "${ms / 86_400_000L} days left"
        }
    }
}

/** "Invite people" at the top of a group: pick how long and how often, then the link with Copy, Share and a QR code. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InviteLinkDialog(app: Muninn, g: String, groupName: String, done: () -> Unit) {
    val context = LocalContext.current
    var expire by remember { mutableStateOf(GroupLinks.DEFAULT_EXPIRE) }
    var uses by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf<GroupLinks.Link?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Invite people to $groupName", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val l = link
                if (l == null) {
                    Text("Expire after", style = Type.normal.copy(color = Palette.text))
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GroupLinks.EXPIRES.forEach { (ms, w) -> MButton(w, kind = if (expire == ms) Kind.Accent else Kind.Normal) { expire = ms } }
                    }
                    Text("Max uses", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 12.dp))
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GroupLinks.USES.forEach { (n, w) -> MButton(w, kind = if (uses == n) Kind.Accent else Kind.Normal) { uses = n } }
                    }
                    Text("Revoke it any time in Add people.", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                } else {
                    Text("Anyone with this link can join $groupName (${GroupLinks.left(l.expires)}" +
                        (if (l.max > 0) ", ${l.max} ${if (l.max == 1) "use" else "uses"})." else ")."), style = Type.normal)
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Qr(l.text, 220.dp) }
                    MButton("Copy", Modifier.fillMaxWidth(), icon = "copy") {
                        io.github.ravenor907.muninn.ui.Clip.copy(context, "Muninn invite link", l.text)
                        Ui.say("Invite link copied.")
                    }
                    MButton("Share", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, icon = "send") {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Join $groupName on Muninn. Open Muninn, tap + › Add someone, and scan or paste this:\n${l.text}"), "Share the invite link"))
                    }
                }
            }
        },
        confirmButton = {
            if (link == null) MButton("Make the link", kind = Kind.Accent) {
                val (l, why) = GroupLinks.create(app, g, expire, uses)
                if (l == null) Ui.say(why ?: "That didn't work.", true) else link = l
            } else MButton("Done", kind = Kind.Accent) { done() }
        },
        dismissButton = { if (link == null) MButton("Cancel") { done() } },
    )
}

/** Add people › Active links: each link with who made it, uses and time left, and Revoke. Nothing when there are none. */
@Composable
fun InviteLinksSection(app: Muninn, g: String, admin: Boolean) {
    var tick by remember { mutableStateOf(0) }
    val links = remember(tick) { GroupLinks.list(app, g) }
    if (links.isEmpty() && !( !GroupLinks.ready && admin)) return
    io.github.ravenor907.muninn.ui.Framed("Active links") {
        links.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("By ${if (l.by == app.identity.publicKey) "you" else app.chat.nameOf(l.by)}", style = Type.normal.copy(color = Palette.text))
                    Text("${l.uses}${if (l.max > 0) " of ${l.max}" else ""} used · ${GroupLinks.left(l.expires)}", style = Type.small)
                }
                MButton("Revoke", kind = Kind.Danger) {
                    Ui.sure(Ask("Revoke this link?", "It stops working at once.", "Revoke", danger = true,
                        onConfirm = { GroupLinks.revoke(app, g, l.id); tick++ }))
                }
            }
        }
        if (!GroupLinks.ready && admin) MButton("Stop every link", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Danger) {
            Ui.sure(Ask("Stop every link?", "They stop working at once.", "Stop", danger = true,
                onConfirm = { GroupLinks.revoke(app, g, ""); Ui.say("Those links don't work any more.") }))
        }
    }
}

/** Group settings › Who can invite: admins only, or everyone in the group. */
@Composable
fun MembersInviteToggle(app: Muninn, g: String) {
    var on by remember { mutableStateOf(GroupLinks.membersCanInvite(app, g)) }
    ToggleRow("Members can invite", "Not just admins", on) {
        val why = GroupLinks.setMembersCanInvite(app, g, it)
        if (why != null) Ui.say(why) else on = it
    }
}

/** A scanned or pasted invite link: the group, how many are in it, Join or Not now. */
@Composable
fun JoinCard(app: Muninn, p: GroupLinks.Preview, onDone: () -> Unit) {
    Column {
        Text(p.name, style = Type.title.copy(fontSize = 20.sp))
        Text(buildString {
            if (p.members > 0) append("${p.members} ${if (p.members == 1) "person" else "people"} · ")
            append("Invited by ${p.by.ifBlank { "an admin" }}")
        }, style = Type.small, modifier = Modifier.padding(top = 4.dp))
        if (p.expired) Text("That link has ended. Ask for a new one.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
        else Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Not now", Modifier.weight(1f)) { onDone() }
            MButton("Join", Modifier.weight(1f), Kind.Accent) {
                val err = GroupLinks.join(app, p)
                if (err != null) Ui.say(err, true)
                else { Ui.say("Asked to join ${p.name}."); onDone() }
            }
        }
    }
}

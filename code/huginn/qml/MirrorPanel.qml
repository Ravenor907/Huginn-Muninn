import QtQuick
import Huginn

// On a companion (linked devices, section 15): the phone's chats, over the conversation. Up and Down pick a
// chat, Right goes into its messages (Up and Down pick one, A answers it, or plays or saves its photo, voice, video or
// file), A in the list goes to the message box, X asks for everything again (Refresh, at most once in 10 minutes),
// B goes back. The message box is the usual one (Enter sends, a reply shows above it); files go from the phone.
// Opening never asks the phone for history. History, photos, voice, video and files come over the home Wi-Fi; until then
// media shows as words with "On home Wi-Fi". A message sent from here shows at once as Sending, and as "Phone didn't
// answer · Retry" (or why) when the phone doesn't take it. Cap l1: group chats too (who wrote each message), with my role,
// and the phone's folders as sections; the call button says calls ring on the phone. Cap l2 (15.2): unread counts and
// read state both ways, mute (on the phone too), and the phone's status. A copy kept after unlinking is read only.
// A group with more than one text channel shows them as tabs over its messages (LB and RB on a controller); a message
// goes in the channel on screen. A tab shows a dot for unread and a count for mentions of me; only the tab on screen is
// read, and a message in another tab notifies as usual. Both lists change in place (LiveList), so a link change never
// moves the view or drops a text selection. Drafts are kept (Linked.drafts) per chat and channel until sent.
Rectangle {
    id: panel
    visible: false
    color: Theme.ground
    // The chat picked, kept by its key: the list re-sorts as messages come and go, and the chat on screen stays.
    property string peer: ""
    property int at: -1               // where it was picked (a chat in two folders shows twice)
    property var chanFor: ({})        // per group chat: the channel picked (its id)
    property var aboutOpen: ({})      // per group chat: its about box shown in full (folded to one line until then)
    property string zone: "chats"     // "chats" or "talk"
    property int msgSel: -1
    property string replyId: ""
    property real clock: Date.now()
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    // The phone's chats in its folders (a chat in two folders shows in both), then the rest; head: a section's name.
    readonly property var chats: {
        if (!visible) return []
        const all = Linked.mirror, folders = Linked.account.folders || [], out = []
        const placed = {}
        // sec: which section a row is in, so a chat in two folders is two rows to LiveList.
        folders.forEach((f, fi) => {
            const keys = f.keys || []
            const inIt = all.filter(c => keys.indexOf(c.peer) >= 0)
            inIt.forEach((c, i) => { placed[c.peer] = true; out.push(Object.assign({ head: i === 0 ? f.name : "", sec: "f:" + (f.id || fi) }, c)) })
        })
        const rest = all.filter(c => !placed[c.peer])
        rest.forEach((c, i) => out.push(Object.assign({ head: i === 0 && out.length ? "Chats" : "", sec: "" }, c)))
        return out
    }
    readonly property int row: peer === "" ? -1 : chats[at] && chats[at].peer === peer ? at : chats.findIndex(c => c.peer === peer)
    readonly property var current: row >= 0 ? chats[row] : null
    // A group's text channels (shown as tabs when there's more than one), and the one on screen ("" for none).
    readonly property var chans: current && current.group && current.chans ? current.chans : []
    readonly property string chan: {
        if (chans.length < 2) return ""
        const want = chanFor[peer]
        return chans.some(c => c.id === want) ? want : chans[0].id
    }
    readonly property var chanNow: chan === "" ? null : chans.find(c => c.id === chan) || null
    // Per channel id: {n: unread, me: mentions of me and replies to mine}.
    readonly property var tabMarks: current && current.tabs ? current.tabs : ({})
    // The group's about text and rules, over its first channel (as in the group itself).
    readonly property string about: !!current && !!current.about && (chan === "" || chan === chans[0].id) ? String(current.about) : ""
    // Only admins post there, and on the phone I'm a member: no message box, as in the group itself.
    readonly property bool chanLocked: !!chanNow && !!chanNow.ao && !!current && current.role === "Member"
    readonly property var allMsgs: current ? (Linked.mirror, Linked.messages(current.peer)) : []
    readonly property var msgs: chan === "" ? allMsgs : allMsgs.filter(m => (m.ch || "main") === chan)
    // The newest message on screen: a new one here (not in another tab) is what gets read.
    readonly property string lastShown: msgs.length ? msgs[msgs.length - 1].id : ""
    readonly property var picked: zone === "talk" ? msgs[msgSel] || null : null
    readonly property var replyMsg: replyId !== "" ? allMsgs.find(m => m.id === replyId) || null : null
    readonly property var prompts: (zone === "talk"
        ? [{ key: "a", label: picked && picked.failed ? "Retry" : picked && picked.media ? actionWord(picked) : "Reply" }, { key: "x", label: "Refresh" }, { key: "b", label: "Back" }]
        : [{ key: "a", label: "Write" }, { key: "x", label: "Refresh" }, { key: "b", label: "Close" }])
        .concat(chans.length > 1 ? [{ key: "lb", label: "Channel" }] : [])

    // Picks the chat at [i] in the list: its draft comes back, the last one's is kept.
    function pick(i) {
        const c = chats[i]
        if (!c) return
        at = i
        if (c.peer === peer) return
        keepDraft()
        peer = c.peer
        loadDraft()
        msgSel = -1
        if (zone === "talk") zone = "chats"
        talk.stick = true
        talk.newBelow = false
        talk.positionViewAtEnd()
        looked()
    }
    // Drafts and replies are kept per channel: "<peer>#<channel>" in a group with tabs, else the chat's key.
    function draftKey() { return peer === "" ? "" : chan !== "" ? peer + "#" + chan : peer }
    // A chat row's draft: the chat's own, or one in any of its channels.
    function draftOf(p) {
        const all = Linked.drafts
        if (all[p] && all[p].text) return all[p]
        for (const k in all) if (k.indexOf(p + "#") === 0 && all[k].text) return all[k]
        return null
    }
    // The draft for the chat and channel on screen comes back into the box (with what it answers).
    function loadDraft() {
        const k = draftKey()
        let d = Linked.drafts[k] || null
        // One kept for the whole group before channels had their own: it comes into the channel opened first.
        if (!d && k !== peer && Linked.drafts[peer]) { d = Linked.drafts[peer]; Linked.setDraft(peer, "", "") }
        compose.text = d ? d.text || "" : ""
        replyId = d ? d.re || "" : ""
    }
    // The half-written message, kept in the vault so closing Huginn doesn't lose it.
    function keepDraft() {
        draftSoon.stop()
        const k = draftKey()
        if (k !== "") Linked.setDraft(k, compose.text, replyId)
    }
    Timer { id: draftSoon; interval: 1500; onTriggered: panel.keepDraft() }
    onReplyIdChanged: if (visible) draftSoon.restart()
    // Another channel of the group on screen.
    // Its own draft and reply come with it: nothing typed or answered in one channel is sent in another.
    function pickChan(id) {
        if (!current || id === chan || !chans.some(c => c.id === id)) return
        keepDraft()
        const m = Object.assign({}, chanFor)
        m[peer] = id
        chanFor = m
        loadDraft()
        msgSel = -1
        talk.stick = true
        talk.newBelow = false
        talk.positionViewAtEnd()
        looked()
    }
    function stepChan(d) {
        const i = chans.findIndex(c => c.id === chan)
        if (i >= 0 && chans.length > 1) pickChan(chans[(i + d + chans.length) % chans.length].id)
    }
    // Opens on chat [p] ("<peer>#<channel>": on that channel's tab, as a notification's click does), else the one
    // picked before (or the first). Nothing is read until it's been on screen a moment.
    function open(p) {
        zone = "chats"; msgSel = -1
        visible = true
        const cut = p ? p.indexOf("#") : -1
        const ch = cut > 0 ? p.substring(cut + 1) : ""
        if (cut > 0) p = p.substring(0, cut)
        let i = p ? chats.findIndex(c => c.peer === p) : -1
        if (i < 0) i = row >= 0 ? row : 0
        pick(i)
        if (ch !== "") pickChan(ch)
        Linked.ping()
        looked()
    }
    function close() {
        keepDraft()
        readSoon.stop()
        visible = false; zone = "chats"; msgSel = -1
        // The chat behind (if any) is the one on screen again.
        Chat.openChat = parent && parent.openKey !== undefined ? parent.openKey : ""
        if (Ui.main) Ui.main.restoreFocus()
    }
    // Unlinked with its copy kept: read only.
    readonly property bool kept: !!Linked.phone.gone
    // The chat on screen (a group with tabs: only the tab on screen, "mirror:<peer>#<channel>"): no sound or toast for
    // it. It's read (here and, cap l2, on the phone, whose read receipts follow its own setting) only once it has stayed
    // on screen, with this window in front, for a moment: arrowing past a chat, or a menu opening the panel, reads
    // nothing. In a group with tabs only that channel is read; the others keep their unread until their tab is viewed.
    function looked() {
        if (!visible || !current) return
        Chat.openChat = "mirror:" + current.peer + (chan !== "" ? "#" + chan : "")
        readSoon.restart()
    }
    Timer {
        id: readSoon
        interval: 1200
        onTriggered: if (panel.visible && panel.current && Qt.application.state === Qt.ApplicationActive) Linked.markRead(panel.current.peer, panel.chan)
    }
    onLastShownChanged: if (visible && current && !readSoon.running) readSoon.start()
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive) { panel.looked(); if (panel.checksIn) Linked.ping() }
            else readSoon.stop()
        }
    }
    // While open: the time moves on, and the phone is asked whether it's there (at most once a minute), only while
    // Huginn is in front and in use: never minimized, hidden or behind a game (Linked.ping checks that too).
    readonly property bool checksIn: panel.visible && !panel.kept && Qt.application.state === Qt.ApplicationActive && Window.active
                                     && Window.visibility !== Window.Minimized && Window.visibility !== Window.Hidden
    Timer { interval: 5000; repeat: true; running: panel.visible; onTriggered: panel.clock = Date.now() }
    Timer { interval: 60000; repeat: true; running: panel.checksIn; onTriggered: Linked.ping() }

    function send(t) {
        if (!current) return
        if (kept) { Ui.say("Unlinked. Link your phone again to send.", true); Qt.callLater(() => { compose.text = t }); return }
        if (chanLocked) { Ui.say("Only admins post in #" + chanNow.name + ".", true); Qt.callLater(() => { compose.text = t }); return }
        const re = replyId
        const r = Linked.send(current.peer, t, re, chan)
        if (r !== "") {
            // Refused at once: the text and the reply stay in the box (Composer empties it after sending).
            Ui.say(r, true)
            Qt.callLater(() => { compose.text = t; replyId = re })
            return
        }
        replyId = ""
        draftSoon.stop()
        Linked.setDraft(draftKey(), "", "")
        talk.stick = true
    }
    // What a message answers, as one short line ("" when it answers nothing).
    function quoteOf(m) {
        if (!m || !m.re) return ""
        const q = allMsgs.find(x => x.id === m.re)
        if (!q) return "Replying to an earlier message"
        const who = q.mine ? "you" : q.from || (current ? current.name : "")
        return "Replying to " + who + ": " + String(q.text || "").replace(/\s+/g, " ").substring(0, 80)
    }
    function write() { if (current && !kept) { zone = "chats"; compose.focusField() } }
    // To the newest message (the "New messages" chip); with a controller, the newest one is picked.
    function toEnd() {
        talk.stick = true
        talk.newBelow = false
        talk.positionViewAtEnd()
        if (zone === "talk") msgSel = msgs.length - 1
    }
    function replyTo(m) { if (!m || kept) return; replyId = m.id; compose.focusField() }
    function refresh() { Ui.say(Linked.refresh()) }
    function stateWord(m) { return m.failed ? (m.err || "Didn't send") + " · Retry" : "Sending" }
    function actionWord(m) { return m.kind === "voice" ? (Voice.playing === m.media ? "Stop" : "Play") : "Save" }
    // A photo, voice, video or file that's here: voice plays, the rest is saved in Downloads.
    function act(m) {
        if (!m || !m.media || !current) return
        if (m.kind === "voice") { if (Voice.playing === m.media) Voice.stop(); else Voice.play(m.media); return }
        const r = Linked.saveMedia(current.peer, m.id)
        if (r !== "") { Ui.say(r, true); return }
        const path = Linked.lastSaved()
        Ui.pickFrom("Saved in Downloads", [{ header: "", items: [{ name: "Open", icon: "file", url: "open" }, { name: "Show in folder", icon: "folder", url: "show" }] }], a => {
            if (a === "open" && !FileLink.openFile(path)) Ui.say("It can't be opened here.", true)
            else if (a === "show" && !FileLink.showInFolder(path)) Ui.say("The folder can't be opened here.", true)
        })
    }
    // Web links in a message's text, to open (asked first).
    function linksIn(text) { return String(text || "").match(/https?:\/\/[^\s<>"]+/g) || [] }
    // A group invite: joining here would join as this PC's own account, so it's for the phone.
    function isInvite(url) { return String(url).indexOf("MUNINNG1:") >= 0 }
    function linkLabel(url) { return isInvite(url) ? "Group invite: open it on your phone" : "Open " + Chat.linkHost(url) }
    function openLink(url) {
        if (isInvite(url)) { Ui.say("Open the invite on your phone to join as you."); return }
        Ui.sure("Open " + Chat.linkHost(url) + "?", url, "Open", () => { if (!Chat.openLink(url)) Ui.say("That link can't be opened here.", true) })
    }
    function messageMenu(m) {
        if (!m) return
        const items = []
        if (!kept && !m.pending) items.push({ name: "Reply", icon: "reply", url: "reply" })
        if (m.text) items.push({ name: "Copy text", icon: "file", url: "copy" })
        linksIn(m.text).forEach((u, i) => items.push({ name: linkLabel(u), icon: "attach", url: "l" + i }))
        if (m.failed) items.push({ name: "Retry", icon: "send", url: "retry" })
        if (!items.length) return
        Ui.pickFrom("Message", [{ header: "", items: items }], a => {
            if (a === "reply") replyTo(m)
            else if (a === "copy") { Chat.copyText(m.text); Ui.say("Copied.") }
            else if (a === "retry") Linked.retrySend(m.id)
            else if (a.indexOf("l") === 0) openLink(linksIn(m.text)[Number(a.substring(1))])
        })
    }
    // A chat's menu: mute it (here, and on the phone too when it keeps the account in step). Always a menu, even while
    // muted (Unmute is its first row), so a stray click never unmutes and a muted group's notifications can be reached.
    function chatMenu(c) {
        if (!c || kept) return
        const items = c.muted ? [{ name: "Unmute", icon: "bell", url: "unmute" }]
                    : [{ name: "For 15 minutes", url: "900000" }, { name: "For 1 hour", url: "3600000" },
                       { name: "For 8 hours", url: "28800000" }, { name: "Until I turn it back on", url: "on" }]
        // A group's menu is about its notifications: muting is one part of it (J4-5).
        const sections = [{ header: c.muted ? "" : c.group ? (Linked.phone.l1 ? "Mute, on your phone too" : "Mute on this computer") : Linked.phone.l1 ? "On your phone too." : "On this computer.", items: items }]
        // A group: every message, or only @mentions and replies to me (on this computer).
        const key = "mirror:" + c.peer, mode = Chat.notifyMode(key)
        if (c.group) sections.push({ header: "Notifications on this computer", items: [
            { name: "Every message", url: "n:every", check: mode !== "mentions" },
            { name: "Only mentions and replies to me", url: "n:mentions", check: mode === "mentions" }] })
        Ui.pickFrom((c.group ? "Notifications for " : c.muted ? "" : "Mute ") + c.name, sections, a => {
            if (a === "unmute") {
                const u = Linked.setMute(c.peer, 0)
                Ui.say(u === "" ? c.name + " isn't muted." : u, u !== "")
                return
            }
            if (a.indexOf("n:") === 0) {
                Chat.setNotifyMode(key, a === "n:mentions" ? "mentions" : "")
                Ui.say(a === "n:mentions" ? c.name + ": only mentions and replies to you." : c.name + ": every message.")
                return
            }
            const r = Linked.setMute(c.peer, a === "on" ? 8640000000000000 : Date.now() + Number(a))   // the huge one: until unmuted
            Ui.say(r === "" ? c.name + " is muted." : r, r !== "")
        })
    }
    function handleButton(b) {
        if (zone === "talk") {
            switch (b) {
            case "up": msgSel = Math.max(0, msgSel - 1); talk.positionViewAtIndex(msgSel, ListView.Contain); return true
            case "down": msgSel = Math.min(msgs.length - 1, msgSel + 1); talk.positionViewAtIndex(msgSel, ListView.Contain); return true
            case "a": if (picked && picked.failed) Linked.retrySend(picked.id); else if (picked && picked.media) act(picked); else if (picked) replyTo(picked); else write(); return true
            case "x": refresh(); return true
            case "left": case "b": zone = "chats"; msgSel = -1; return true
            case "lb": stepChan(-1); return true
            case "rb": stepChan(1); return true
            }
            return true
        }
        switch (b) {
        case "up": pick(Math.max(0, row - 1)); return true
        case "down": pick(Math.min(chats.length - 1, row + 1)); return true
        case "right": if (msgs.length) { zone = "talk"; msgSel = msgs.length - 1; talk.positionViewAtEnd() } return true
        case "a": write(); return true
        case "x": refresh(); return true
        case "b": close(); return true
        case "lb": stepChan(-1); return true
        case "rb": stepChan(1); return true
        }
        return true
    }

    // ---- the phone's status (cap l2), in plain words. Never an address.
    readonly property var ph: Linked.phone
    readonly property bool phoneHere: !!ph.answered || (ph.heard > 0 && clock - ph.heard < 120000)
    readonly property string status: {
        if (kept) return "Unlinked. A copy, read only."
        if (!ph.ok) return "Check the 4 emoji on both screens first."
        if (phoneHere) return "Your phone is online" + (ph.lan ? " on this Wi-Fi" : "")
        if (ph.asked > 0 && clock - ph.asked > 20000) return "Your phone isn't answering. It may be asleep or offline. Messages wait for it. In Muninn, Settings › Battery: choose Right away."
        if (ph.heard > 0) return "Phone last heard " + ago(ph.heard)
        return "Checking your phone"
    }
    function ago(t) {
        const m = Math.max(1, Math.round((clock - t) / 60000))
        return m < 60 ? m + " min ago" : Qt.formatTime(new Date(t), "h:mm AP")
    }
    // It answers, but not over this Wi-Fi: history and photos can't come. Windows Firewall is the usual reason.
    readonly property bool wifiTrouble: !kept && !!ph.ok && !!ph.home && !ph.lan && phoneHere
    function whyWifi() {
        Ui.note("Can't reach your phone on home Wi-Fi",
                "Messages still come and go over the internet. History, photos and files come over home Wi-Fi only.\n\n"
                + "Check that your phone and this computer are on the same Wi-Fi.\n\n"
                + (Qt.platform.os === "windows"
                   ? "If they are, Windows Firewall may be blocking Huginn. If Windows asked about Huginn, choose Allow. Or open Windows Security › Firewall & network protection › Allow an app through firewall, and tick Huginn for Private networks."
                   : "If they are, a firewall on this computer may be blocking Huginn. Allow it on your home network."))
    }
    // A chat list time: today's as a time, this week's as a day, older as a date.
    function when(t) {
        if (!t) return ""
        const d = new Date(t), now = new Date(clock)
        if (d.toDateString() === now.toDateString()) return Qt.formatTime(d, "h:mm AP")
        if (clock - t < 6 * 86400000) return Qt.formatDate(d, "ddd")
        return Qt.formatDate(d, "d MMM")
    }
    function dayOf(t) {
        const d = new Date(t), now = new Date(clock)
        if (d.toDateString() === now.toDateString()) return "Today"
        if (d.toDateString() === new Date(clock - 86400000).toDateString()) return "Yesterday"
        return Qt.formatDate(d, "dddd d MMMM")
    }

    PageHeader {
        id: head
        width: parent.width
        z: 2   // the call button's tip shows over the lists
        title: (Linked.phone.name || "Your phone") + "'s chats" + (panel.kept ? " (kept copy)" : "")
        subtitle: panel.status
        // Mute the chat on screen.
        Rectangle {
            visible: !!panel.current && !panel.kept
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(34); height: width
            radius: Theme.radius
            color: muteHover.hovered ? Theme.raised : "transparent"
            Accessible.role: Accessible.Button
            Accessible.name: panel.current && panel.current.muted ? "Unmute " + panel.current.name : "Mute"
            Accessible.onPressAction: panel.chatMenu(panel.current)
            Icon { anchors.centerIn: parent; name: panel.current && panel.current.muted ? "belloff" : "bell"; size: Theme.px(18)
                   color: panel.current && panel.current.muted ? Theme.accent : muteHover.hovered ? Theme.text : Theme.textMuted }
            HoverHandler { id: muteHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: panel.chatMenu(panel.current) }
        }
        // Calls stay on the phone: the button only says so. Groups: voice and screen share too.
        Rectangle {
            id: callBtn
            readonly property string tipText: panel.current && panel.current.group ? "Group voice and screen share are on your phone for now" : "Calls ring on your phone"
            visible: !!panel.current && !panel.kept
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(34); height: width
            radius: Theme.radius
            color: callHover.hovered ? Theme.raised : "transparent"
            Accessible.role: Accessible.Button
            Accessible.name: "Call"
            Accessible.description: callBtn.tipText
            Accessible.onPressAction: Ui.say(callBtn.tipText)
            Icon { anchors.centerIn: parent; name: "call"; size: Theme.px(18); color: callHover.hovered ? Theme.text : Theme.textMuted }
            HoverHandler { id: callHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: Ui.say(callBtn.tipText) }
            Rectangle {
                visible: callHover.hovered
                z: 30
                anchors.top: parent.bottom; anchors.topMargin: Theme.px(6)
                anchors.right: parent.right
                width: tip.implicitWidth + Theme.px(16); height: tip.implicitHeight + Theme.px(10)
                radius: Theme.radius
                color: Theme.panel
                border.color: Theme.line
                Text { id: tip; anchors.centerIn: parent; textFormat: Text.PlainText; text: callBtn.tipText; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
            }
        }
        HButton { visible: !panel.kept; anchors.verticalCenter: parent.verticalCenter; height: Theme.px(34); fontSize: 14; text: "Refresh"; onClicked: panel.refresh() }
        HButton { anchors.verticalCenter: parent.verticalCenter; height: Theme.px(34); fontSize: 14; text: "Close"; onClicked: panel.close() }
    }
    // Home Wi-Fi trouble: one line, the details on a click.
    Rectangle {
        id: wifiBar
        visible: panel.wifiTrouble
        anchors.top: head.bottom; anchors.left: parent.left; anchors.right: parent.right
        height: visible ? wifiText.implicitHeight + Theme.px(12) : 0
        color: Theme.panel
        Text {
            id: wifiText
            textFormat: Text.PlainText
            anchors.left: parent.left; anchors.leftMargin: Theme.px(16)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(16)
            anchors.verticalCenter: parent.verticalCenter
            wrapMode: Text.WordWrap
            text: "Can't reach your phone on home Wi-Fi, so history and photos wait. Why?"
            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        Accessible.role: Accessible.Button
        Accessible.name: wifiText.text
        Accessible.onPressAction: panel.whyWifi()
        HoverHandler { cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: panel.whyWifi() }
    }
    // Both lists change in place: a message elsewhere, a ping or a read never starts them over.
    LiveList { id: chatModel; keys: ["sec", "peer"]; items: panel.chats }
    LiveList { id: msgModel; keys: ["id"]; items: panel.msgs }
    ListView {
        id: side
        anchors.top: wifiBar.bottom; anchors.bottom: parent.bottom; anchors.left: parent.left
        width: Math.min(Theme.px(280), parent.width * 0.4)
        clip: true
        model: chatModel
        delegate: Item {
            id: chatRow
            readonly property bool on: index === panel.row
            // A half-written message (not for the chat on screen, whose box shows it).
            readonly property var draft: modelData.peer !== panel.peer ? panel.draftOf(modelData.peer) : null
            width: side.width
            height: headH + Theme.px(56)
            readonly property real headH: modelData.head ? Theme.px(28) : 0
            // A folder's name over its chats.
            Text {
                visible: !!modelData.head
                textFormat: Text.PlainText
                x: Theme.px(12); y: Theme.px(8)
                width: parent.width - Theme.px(24)
                elide: Text.ElideRight
                text: String(modelData.head || "").toUpperCase()
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1)
            }
            Rectangle {
                y: chatRow.headH
                width: parent.width; height: Theme.px(56)
                color: chatRow.on ? Theme.tile : rowHover.hovered ? Theme.raised : "transparent"
                border.width: chatRow.on && panel.pad && panel.zone === "chats" ? Theme.focusWidth : 0
                border.color: Theme.accent
                Accessible.role: Accessible.ListItem
                Accessible.name: modelData.name + (modelData.unread ? ", " + modelData.unread + " unread" : "") + (modelData.muted ? ", muted" : "")
                Avatar {
                    id: rowAva
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    size: Theme.px(36)
                    key: modelData.group ? "" : modelData.peer
                    name: modelData.name
                    backdrop: chatRow.on ? Theme.tile : Theme.ground
                }
                Column {
                    anchors.left: rowAva.right; anchors.leftMargin: Theme.px(10)
                    anchors.right: rowEnd.left; anchors.rightMargin: Theme.px(6)
                    anchors.verticalCenter: parent.verticalCenter
                    Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                           font.weight: modelData.unread ? Font.Bold : Font.DemiBold }
                    Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; color: chatRow.draft && chatRow.draft.text ? Theme.accent : modelData.unread ? Theme.textSoft : Theme.textMuted
                           font.family: Theme.body; font.pixelSize: Theme.fs(13)
                           text: chatRow.draft && chatRow.draft.text ? "Draft: " + String(chatRow.draft.text).replace(/\s+/g, " ")
                                 : modelData.last || [modelData.role || "", modelData.folders || ""].filter(x => x !== "").join(" · ") || "No messages yet" }
                }
                // When, then the unread count or a struck-through bell.
                Column {
                    id: rowEnd
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(4)
                    Text { anchors.right: parent.right; textFormat: Text.PlainText; text: panel.when(modelData.time); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(11) }
                    Rectangle {
                        anchors.right: parent.right
                        visible: !!modelData.unread && !modelData.muted
                        width: Math.max(height, unreadN.implicitWidth + Theme.px(10)); height: Theme.px(18)
                        radius: height / 2
                        color: Theme.accent
                        Text { id: unreadN; anchors.centerIn: parent; textFormat: Text.PlainText; text: modelData.unread > 99 ? "99+" : String(modelData.unread || 0)
                               color: Theme.onAccent; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold }
                    }
                    Icon { anchors.right: parent.right; visible: !!modelData.muted; name: "belloff"; size: Theme.px(14); color: Theme.textMuted }
                }
                HoverHandler { id: rowHover }
                TapHandler {
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onTapped: (point, button) => { panel.pick(index); panel.zone = "chats"; if (button === Qt.RightButton) panel.chatMenu(modelData) }
                    onLongPressed: { panel.pick(index); panel.chatMenu(modelData) }
                }
            }
        }
        Text {
            textFormat: Text.PlainText
            visible: panel.chats.length === 0
            anchors.centerIn: parent; width: parent.width - Theme.px(20); wrapMode: Text.WordWrap
            text: "Nothing yet. History comes over on home Wi-Fi, while the phone is on."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }
    // A group's text channels, as tabs: messages show, and go, in the one picked.
    ListView {
        id: chanBar
        visible: panel.chans.length > 1
        anchors.top: wifiBar.bottom; anchors.left: side.right; anchors.right: parent.right
        anchors.leftMargin: Theme.px(14); anchors.rightMargin: Theme.px(14); anchors.topMargin: visible ? Theme.px(8) : 0
        height: visible ? Theme.px(32) : 0
        orientation: ListView.Horizontal
        spacing: Theme.px(6)
        clip: true
        model: panel.chans
        delegate: Rectangle {
            id: tab
            readonly property bool on: modelData.id === panel.chan
            // Unread in this channel: a dot and bold; mentions of me (and replies to mine): how many, in red.
            readonly property var mark: panel.tabMarks[modelData.id] || null
            readonly property int unreadN: mark ? mark.n || 0 : 0
            readonly property int meN: mark ? mark.me || 0 : 0
            width: tabRow.implicitWidth + Theme.px(20); height: chanBar.height
            radius: Theme.radius
            color: on ? Theme.tile : tabHover.hovered ? Theme.raised : "transparent"
            border.width: on ? 1 : 0
            border.color: Theme.accent
            Accessible.role: Accessible.PageTab
            Accessible.name: "#" + modelData.name + (modelData.ao ? ", only admins post" : "")
                             + (tab.meN > 0 ? ", " + tab.meN + (tab.meN === 1 ? " mention" : " mentions") : tab.unreadN > 0 ? ", unread" : "")
            Accessible.onPressAction: panel.pickChan(modelData.id)
            Row {
                id: tabRow
                anchors.centerIn: parent
                spacing: Theme.px(6)
                Text {
                    id: tabText
                    anchors.verticalCenter: parent.verticalCenter
                    textFormat: Text.PlainText
                    text: "#" + modelData.name + (modelData.ao ? " (admins)" : "")
                    color: tab.on || tab.unreadN > 0 ? Theme.text : Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: tab.on || tab.unreadN > 0 ? Font.DemiBold : Font.Normal
                }
                Rectangle {
                    visible: tab.unreadN > 0 || tab.meN > 0
                    anchors.verticalCenter: parent.verticalCenter
                    width: tab.meN > 0 ? Math.max(height, meText.implicitWidth + Theme.px(10)) : Theme.px(8)
                    height: tab.meN > 0 ? Theme.px(18) : Theme.px(8)
                    radius: height / 2
                    color: tab.meN > 0 ? Theme.danger : Theme.accent
                    Text {
                        id: meText
                        visible: tab.meN > 0
                        anchors.centerIn: parent
                        textFormat: Text.PlainText
                        text: tab.meN > 99 ? "99+" : String(tab.meN)
                        color: Theme.ground
                        font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold
                    }
                }
            }
            HoverHandler { id: tabHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: panel.pickChan(modelData.id) }
        }
    }
    // The group's about text and rules (cap g3), over its first channel: one line until clicked, as in the group.
    Rectangle {
        id: aboutBox
        readonly property bool full: !!panel.aboutOpen[panel.peer]
        visible: panel.about !== ""
        anchors.top: chanBar.bottom; anchors.left: side.right; anchors.right: parent.right
        anchors.leftMargin: Theme.px(14); anchors.rightMargin: Theme.px(14); anchors.topMargin: visible ? Theme.px(8) : 0
        height: visible ? aboutText.implicitHeight + Theme.px(16) : 0
        radius: Theme.radius; color: Theme.raised; border.color: Theme.line; border.width: 1
        Accessible.role: Accessible.Button
        Accessible.name: "About this group: " + panel.about
        Accessible.description: full ? "Click to fold it to one line." : "Folded. Click to show it all."
        Accessible.onPressAction: aboutBox.flip()
        function flip() { const o = Object.assign({}, panel.aboutOpen); o[panel.peer] = !aboutBox.full; panel.aboutOpen = o }
        Text {
            id: aboutText
            textFormat: Text.PlainText
            x: Theme.px(12); y: Theme.px(8); width: parent.width - Theme.px(24) - foldWord.width
            wrapMode: Text.WordWrap
            maximumLineCount: aboutBox.full ? 200 : 1
            elide: Text.ElideRight
            text: panel.about
            color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        Text {
            id: foldWord
            textFormat: Text.PlainText
            anchors.right: parent.right; anchors.rightMargin: Theme.px(10); y: Theme.px(8)
            text: aboutBox.full ? "Fold" : "Show"
            color: aboutHover.hovered ? Theme.accent : Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
        }
        HoverHandler { id: aboutHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: aboutBox.flip() }
    }
    ListView {
        id: talk
        // Follows new messages only while it's at the end: scrolled up to read, it stays put, and a chip says new ones came.
        property bool stick: true
        property bool newBelow: false
        property int lastCount: 0
        anchors.top: aboutBox.bottom; anchors.bottom: composeCol.top; anchors.left: side.right; anchors.right: parent.right
        anchors.margins: Theme.px(14)
        clip: true
        spacing: Theme.px(6)
        model: msgModel
        onMovementEnded: { stick = atYEnd; if (atYEnd) newBelow = false }
        onAtYEndChanged: if (atYEnd) newBelow = false
        onCountChanged: {
            if (panel.zone !== "talk" && stick) positionViewAtEnd()
            else if (count > lastCount && !atYEnd) newBelow = true
            lastCount = count
        }
        delegate: Column {
            id: msgItem
            readonly property bool sel: panel.zone === "talk" && index === panel.msgSel
            readonly property bool newDay: index === 0 || new Date(panel.msgs[index - 1].time).toDateString() !== new Date(modelData.time).toDateString()
            readonly property var links: panel.linksIn(modelData.text)
            width: talk.width
            spacing: Theme.px(4)
            // The day, once at its first message.
            Text {
                visible: msgItem.newDay
                textFormat: Text.PlainText
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                topPadding: Theme.px(6)
                text: panel.dayOf(modelData.time)
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
            }
            Rectangle {
                width: parent.width
                height: body.implicitHeight + Theme.px(8)
                radius: Theme.radius
                color: msgItem.sel ? Theme.tile : "transparent"
                border.width: msgItem.sel && panel.pad ? Theme.focusWidth : 0
                border.color: Theme.accent
                // Right click (or a long press): Reply, Copy text, Open a link.
                TapHandler {
                    acceptedButtons: Qt.RightButton
                    onTapped: panel.messageMenu(modelData)
                }
                TapHandler {
                    acceptedDevices: PointerDevice.TouchScreen
                    onLongPressed: panel.messageMenu(modelData)
                }
                Column {
                    id: body
                    x: Theme.px(6); y: Theme.px(4)
                    width: parent.width - Theme.px(12)
                    spacing: Theme.px(4)
                    Text {
                        textFormat: Text.PlainText
                        width: parent.width
                        elide: Text.ElideRight
                        text: (modelData.mine ? "You" : modelData.from ? modelData.from : panel.current ? panel.current.name : "") + " · " + Qt.formatTime(new Date(modelData.time), "h:mm AP")
                              + (modelData.edited > 0 ? " · (edited)" : "") + (modelData.pending ? " · " + panel.stateWord(modelData) : "")
                        color: modelData.failed ? Theme.danger : modelData.mine ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                        // Not answered: a click sends it again (the phone never sends one twice).
                        TapHandler { enabled: !!modelData.failed; onTapped: Linked.retrySend(modelData.id) }
                    }
                    // What it answers.
                    Text {
                        readonly property string q: panel.quoteOf(modelData)
                        visible: q !== ""
                        textFormat: Text.PlainText
                        width: parent.width
                        elide: Text.ElideRight
                        text: q
                        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.italic: true
                    }
                    // A photo that's here.
                    Image {
                        visible: modelData.kind === "photo" && modelData.media !== ""
                        width: Math.min(parent.width, Theme.px(260)); height: visible ? Math.max(Theme.px(40), width * (implicitWidth > 0 ? implicitHeight / implicitWidth : 0.75)) : 0
                        fillMode: Image.PreserveAspectFit
                        asynchronous: true
                        source: visible ? "image://thumb/" + modelData.media : ""
                        TapHandler { onTapped: panel.act(modelData) }
                    }
                    // The text, to select and copy.
                    TextEdit {
                        width: parent.width; wrapMode: TextEdit.Wrap; textFormat: TextEdit.PlainText
                        visible: !(modelData.kind === "photo" && modelData.media !== "")
                        readOnly: true; selectByMouse: true
                        selectionColor: Theme.accentFill; selectedTextColor: Theme.text
                        // Deleted for everyone: muted and slanted, as on the phone.
                        readonly property bool gone: modelData.text === "Message deleted" && !modelData.pending && modelData.kind === ""
                        text: modelData.text; color: gone ? Theme.textMuted : Theme.text; font.italic: gone
                        font.family: Theme.body; font.pixelSize: Theme.fs(16)
                    }
                    // Its links, to open.
                    Repeater {
                        model: msgItem.links
                        Text {
                            required property var modelData
                            textFormat: Text.PlainText
                            text: panel.linkLabel(modelData)
                            color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.underline: true
                            Accessible.role: Accessible.Link
                            Accessible.name: text
                            HoverHandler { cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: panel.openLink(modelData) }
                        }
                    }
                    // Voice, video and files: play or save once here; until then, where they come from.
                    Row {
                        visible: modelData.kind !== ""
                        spacing: Theme.px(8)
                        HButton {
                            visible: modelData.media !== "" && modelData.kind !== "photo"
                            implicitHeight: Theme.px(28); fontSize: 12; kind: "accent"
                            text: panel.actionWord(modelData)
                            onClicked: panel.act(modelData)
                        }
                        Text {
                            textFormat: Text.PlainText
                            visible: modelData.media === ""
                            text: "On home Wi-Fi"
                            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.italic: true
                        }
                    }
                }
            }
        }
    }
    // Scrolled up and new messages came: a chip at the bottom of the messages goes to them.
    Rectangle {
        visible: talk.newBelow
        z: 3
        anchors.horizontalCenter: talk.horizontalCenter
        anchors.bottom: talk.bottom; anchors.bottomMargin: Theme.px(6)
        width: newText.implicitWidth + Theme.px(20); height: Theme.px(28)
        radius: height / 2
        color: Theme.accent
        Accessible.role: Accessible.Button
        Accessible.name: "New messages. Go to them."
        Accessible.onPressAction: panel.toEnd()
        Text {
            id: newText
            anchors.centerIn: parent
            textFormat: Text.PlainText
            text: "New messages \u2193"
            color: Theme.onAccent; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
        }
        HoverHandler { cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: panel.toEnd() }
    }
    // The message box, as in every chat. Files go from the phone.
    Column {
        id: composeCol
        anchors.left: side.right; anchors.right: parent.right; anchors.bottom: parent.bottom
        anchors.leftMargin: Theme.px(14); anchors.rightMargin: Theme.px(14); anchors.bottomMargin: Theme.px(12)
        spacing: Theme.px(4)
        visible: !!panel.current
        Composer {
            id: compose
            width: parent.width
            visible: !panel.kept && !panel.chanLocked
            placeholder: !panel.current ? "" : panel.chanNow ? "Message #" + panel.chanNow.name : "Message " + panel.current.name
            bannerTitle: panel.replyMsg ? "Replying to " + (panel.replyMsg.mine ? "yourself" : panel.replyMsg.from || (panel.current ? panel.current.name : "")) : ""
            bannerText: panel.replyMsg ? (panel.replyMsg.text || "") : ""
            onSend: t => panel.send(t)
            onTextChanged: draftSoon.restart()
            showAttach: false
            pasteRefusal: "Send pictures from your phone."
            onAttach: Ui.say("Send files from your phone.")
            onCancelBanner: panel.replyId = ""
            onEscaped: compose.leave()
        }
        Text {
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.WordWrap
            text: panel.kept ? "Unlinked. Link your phone again to send."
                  : panel.chanLocked ? "Only admins post here."
                  : "Sent by your phone as its own message. Send files from your phone."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
    }
}

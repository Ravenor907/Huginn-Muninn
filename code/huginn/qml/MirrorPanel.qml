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
Rectangle {
    id: panel
    visible: false
    color: Theme.ground
    // The chat picked, kept by its key: the list re-sorts as messages come and go, and the chat on screen stays.
    property string peer: ""
    property int at: -1               // where it was picked (a chat in two folders shows twice)
    property var drafts: ({})         // per chat: the half-written message and what it answers
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
        folders.forEach(f => {
            const keys = f.keys || []
            const inIt = all.filter(c => keys.indexOf(c.peer) >= 0)
            inIt.forEach((c, i) => { placed[c.peer] = true; out.push(Object.assign({ head: i === 0 ? f.name : "" }, c)) })
        })
        const rest = all.filter(c => !placed[c.peer])
        rest.forEach((c, i) => out.push(Object.assign({ head: i === 0 && out.length ? "Chats" : "" }, c)))
        return out
    }
    readonly property int row: peer === "" ? -1 : chats[at] && chats[at].peer === peer ? at : chats.findIndex(c => c.peer === peer)
    readonly property var current: row >= 0 ? chats[row] : null
    readonly property var msgs: current ? (Linked.mirror, Linked.messages(current.peer)) : []
    readonly property var picked: zone === "talk" ? msgs[msgSel] || null : null
    readonly property var replyMsg: replyId !== "" ? msgs.find(m => m.id === replyId) || null : null
    readonly property var prompts: zone === "talk"
        ? [{ key: "a", label: picked && picked.failed ? "Retry" : picked && picked.media ? actionWord(picked) : "Reply" }, { key: "x", label: "Refresh" }, { key: "b", label: "Back" }]
        : [{ key: "a", label: "Write" }, { key: "x", label: "Refresh" }, { key: "b", label: "Close" }]

    // Picks the chat at [i] in the list: its draft comes back, the last one's is kept.
    function pick(i) {
        const c = chats[i]
        if (!c) return
        at = i
        if (c.peer === peer) return
        keepDraft()
        peer = c.peer
        const d = drafts[peer]
        compose.text = d ? d.text : ""
        replyId = d ? d.re : ""
        msgSel = -1
        if (zone === "talk") zone = "chats"
        looked()
    }
    function keepDraft() {
        if (peer === "") return
        if (compose.text !== "" || replyId !== "") drafts[peer] = { text: compose.text, re: replyId }
        else delete drafts[peer]
    }
    // Opens on chat [p], else the one picked before (or the first). Nothing is read until it's been on screen a moment.
    function open(p) {
        zone = "chats"; msgSel = -1
        visible = true
        let i = p ? chats.findIndex(c => c.peer === p) : -1
        if (i < 0) i = row >= 0 ? row : 0
        pick(i)
        Linked.ping()
        looked()
    }
    function close() {
        readSoon.stop()
        visible = false; zone = "chats"; msgSel = -1
        // The chat behind (if any) is the one on screen again.
        Chat.openChat = parent && parent.openKey !== undefined ? parent.openKey : ""
        if (Ui.main) Ui.main.restoreFocus()
    }
    // Unlinked with its copy kept: read only.
    readonly property bool kept: !!Linked.phone.gone
    // The chat on screen: no sound or toast for it. It's read (here and, cap l2, on the phone, whose read receipts
    // follow its own setting) only once it has stayed on screen, with this window in front, for a moment: arrowing
    // past a chat, or a menu opening the panel, reads nothing.
    function looked() {
        if (!visible || !current) return
        Chat.openChat = "mirror:" + current.peer
        readSoon.restart()
    }
    Timer {
        id: readSoon
        interval: 1200
        onTriggered: if (panel.visible && panel.current && Qt.application.state === Qt.ApplicationActive) Linked.markRead(panel.current.peer)
    }
    onMsgsChanged: if (visible && current && !readSoon.running) readSoon.start()
    Connections {
        target: Qt.application
        function onStateChanged() { if (Qt.application.state === Qt.ApplicationActive) panel.looked(); else readSoon.stop() }
    }
    // While open: the time moves on, and the phone is asked whether it's there (at most once a minute).
    Timer { interval: 5000; repeat: true; running: panel.visible; onTriggered: panel.clock = Date.now() }
    Timer { interval: 60000; repeat: true; running: panel.visible && !panel.kept; onTriggered: Linked.ping() }

    function send(t) {
        if (!current) return
        if (kept) { Ui.say("Unlinked. Link your phone again to send.", true); return }
        const r = Linked.send(current.peer, t, replyId)
        replyId = ""
        delete drafts[current.peer]
        if (r !== "") Ui.say(r, true)
    }
    function write() { if (current && !kept) { zone = "chats"; compose.focusField() } }
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
    // A chat's menu: mute it (here, and on the phone too when it keeps the account in step).
    function chatMenu(c) {
        if (!c || kept) return
        if (c.muted) {
            const r = Linked.setMute(c.peer, 0)
            Ui.say(r === "" ? c.name + " isn't muted." : r, r !== "")
            return
        }
        const items = [{ name: "For 15 minutes", url: "900000" }, { name: "For 1 hour", url: "3600000" },
                       { name: "For 8 hours", url: "28800000" }, { name: "Until I turn it back on", url: "on" }]
        Ui.pickFrom("Mute " + c.name, [{ header: Linked.phone.l1 ? "On your phone too." : "On this computer.", items: items }], a => {
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
        if (ph.asked > 0 && clock - ph.asked > 20000) return "Your phone isn't answering. It may be asleep or offline. Messages wait for it."
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
    ListView {
        id: side
        anchors.top: wifiBar.bottom; anchors.bottom: parent.bottom; anchors.left: parent.left
        width: Math.min(Theme.px(280), parent.width * 0.4)
        clip: true
        model: panel.chats
        delegate: Item {
            id: chatRow
            readonly property bool on: index === panel.row
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
                    Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; color: modelData.unread ? Theme.textSoft : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                           text: modelData.last || [modelData.role || "", modelData.folders || ""].filter(x => x !== "").join(" · ") || "No messages yet" }
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
    ListView {
        id: talk
        anchors.top: wifiBar.bottom; anchors.bottom: composeCol.top; anchors.left: side.right; anchors.right: parent.right
        anchors.margins: Theme.px(14)
        clip: true
        spacing: Theme.px(6)
        model: panel.msgs
        onCountChanged: if (panel.zone !== "talk") positionViewAtEnd()
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
                              + (modelData.pending ? " · " + panel.stateWord(modelData) : "")
                        color: modelData.failed ? Theme.danger : modelData.mine ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                        // Not answered: a click sends it again (the phone never sends one twice).
                        TapHandler { enabled: !!modelData.failed; onTapped: Linked.retrySend(modelData.id) }
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
                        text: modelData.text; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16)
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
            visible: !panel.kept
            placeholder: panel.current ? "Message " + panel.current.name : ""
            bannerTitle: panel.replyMsg ? "Replying to " + (panel.replyMsg.mine ? "yourself" : panel.replyMsg.from || (panel.current ? panel.current.name : "")) : ""
            bannerText: panel.replyMsg ? (panel.replyMsg.text || "") : ""
            onSend: t => panel.send(t)
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
            text: panel.kept ? "Unlinked. Link your phone again to send." : "Sent by your phone as its own message. Send files from your phone."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
    }
}

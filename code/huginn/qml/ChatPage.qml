import QtQuick
import Huginn

// Chats, the same as Muninn's. Middle column: the title with + (add someone, my code, a one-time
// link) and the list's three dots (New folder, Trash, and the Labs entries), then requests, people in no folder, each
// folder as a section that folds away (like Discord categories), and at the very bottom a small Nearby section: the
// Nearby room and everyone nearby who isn't a contact (PeopleActions.nearbyMenu: Hide, Block, Mute, Delete chat).
// Every contact has one menu (PeopleActions.contactMenu): their three
// dots, a right click, LT or X. Main column: the open conversation (name, Call and the same menu in
// its header) and its message box, with a thread or the pins beside it. A portrait window shows one
// at a time. Locked with a pattern or password like Muninn.
Rectangle {
    id: page
    // Autumn: a frosted pane the leaves fall behind; the other looks are solid.
    color: Theme.sheet
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int row: 0
    property string zone: Theme.portrait || Theme.listShown ? "list" : "messages"   // "list", "messages" or "side" (a thread or the pins)
    // A chat (or the Nearby room) has been picked; until then the main column says how to start.
    property bool picked: false
    // A person being dragged with the mouse (from which section), and where they'd land: the line
    // (in the list's content), the section, and whether that's onto a folder's header.
    property string dragKey: ""
    property string dragFrom: ""
    property string dragName: ""
    property real dragY: 0
    property real dropLine: -1
    property string dropSection: ""
    property int dropAt: -1
    property bool dropOnHeader: false
    property string openKey: ""       // "" = Nearby room, otherwise a private chat
    property int sel: -1              // selected message, for its options
    property int sideSel: -1          // the message picked in the thread or pins panel
    property bool showCode: false
    property string viewing: ""       // a photo open full size
    property bool tour: false
    property bool changeLock: false
    property bool lockOff: false      // Settings › Turn off the lock: the current lock is asked for first
    property bool ownLock: false      // Settings › Linked devices › This device's own chats: the lock is asked for first
    // A direct message never shows the member column.
    readonly property bool isGroup: false
    // The controller or its pointer is in use: what opens takes the controller's focus, not a text cursor.
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    // Narrow screens (a Steam Deck at handheld sizes, or a big Size): a thread or the pins slide over
    // the conversation instead of sitting beside it, so it keeps room to read.
    readonly property bool sideDocked: width - middle.width - Theme.px(360) >= Theme.px(420)
    // The message box the controller is typing in, if any (picking a message takes it out).
    readonly property var typingIn: compose.hasFocus && zone === "messages" && sel < 0 ? compose
                                  : threadBox.hasFocus && zone === "side" && sideSel < 0 ? threadBox : null
    // Cap m1: the message being answered or edited, where the "New messages" line goes, the message
    // whose reaction picker is open.
    property string replyId: ""
    property string editId: ""
    property int newFrom: -1
    property string reactFor: ""
    readonly property var replyMsg: replyId === "" ? null : messages.find(m => m.id === replyId) || null
    // The side panel (a 1:1 chat only): "thread" for one message and its replies, "pins" for this
    // chat's pins (only on this computer), the same as in groups.
    property string side: ""
    property string threadRoot: ""
    readonly property bool isTour: tour
    readonly property var pins: tour || locked || openKey === "" ? [] : (Chat.chats, Chat.pinned(openKey))
    readonly property var pinnedMsgs: pins.map(id => messages.find(m => m.id === id)).filter(m => !!m)
    readonly property var threadMsgs: threadRoot === "" ? [] : messages.filter(m => m.id === threadRoot || m.re === threadRoot)
    readonly property bool theyType: openKey !== "" && !locked && Chat.typing[openKey] !== undefined
    readonly property bool theyActive: openKey !== "" && !locked && Chat.active[openKey] !== undefined
    // The open chat's own color (a contact's menu › Details › Chat color), else the accent.
    readonly property string chatColor: openKey === "" || tour || locked ? "" : (Chat.chats, Chat.chatColor(openKey))
    readonly property color tint: Theme.chatAccent(chatColor)
    readonly property bool tinted: Theme.validColor(chatColor)
    // The conversation header's buttons for a controller (Up from the first message): Call, Video and the menu.
    property int headSel: 0
    // The header's buttons: Call (Voice call and Video call in its popup), Find and the menu. Mute is in the menu.
    readonly property var headButtons: openKey === "" || locked ? [] : ["call", "find", "menu"]
    // The open chat is muted (said in the header's subtitle; Mute is in the menu).
    readonly property bool openMuted: openKey !== "" && !tour && !locked && (Chat.chats, Chat.isMuted(openKey))
    // Find in this chat (the search icon or Ctrl+F).
    function openFind() { if (picked && !locked) { zone = "messages"; sel = -1; finder.open() } }

    function setArgs(a) {
        tour = !!a.tour
        if (tour) picked = true
        changeLock = !!a.changeLock
        lockOff = !!a.lockOff
        ownLock = !!a.ownChats
        if (a.zone) zone = a.zone
        if (a.folders) { zone = "list"; row = Math.max(0, entries.findIndex(e => e.kind === "folder")) }
        if (a.open) { openKey = a.open; picked = true; zone = "messages" }   // from a contact's menu › Message
        // Opened from a notification: no "New from" bar on the way back to the list, until the next new message.
        if (a.noticed) newFromClosed = Date.now()
        if (a.add) Qt.callLater(addSomeone)   // Contacts › +
        if (a.mirror) { const p = a.mirror; Qt.callLater(() => mirror.open(p)) }   // Contacts › a phone contact › Message
        // A contact's menu › Find in chat, Media and files › Media, or More › Pinned messages.
        if (a.open && a.show === "find") Qt.callLater(openFind)
        if (a.open && a.show === "pins") Qt.callLater(openPins)
        if (a.open && a.show === "shared") Qt.callLater(() => page.more.openShared())
        // A chat's Files › Show in chat.
        if (a.open && a.showFile) { const f = a.showFile; Qt.callLater(() => page.showFile(f)) }
    }

    readonly property bool locked: !tour && (!Vault.exists || Vault.screenLocked)
    // The lock's own LB (pattern or password) wins over moving along the rail, and so does a phone group's LB (next channel).
    readonly property bool ownsShoulders: (lock.visible && !lock.asks) || (mirror.visible && mirror.chans.length > 1)

    // The "New from" bar (plan 2.14): the unread chats newest first, its words, and when its X was last pressed (this run only).
    property real newFromClosed: 0
    readonly property var unreadChats: tour || locked ? [] : Chat.chats.filter(c => c.contact && !c.archived && (c.newCount || 0) > 0)
                                                                 .sort((a, b) => (b.time || 0) - (a.time || 0))
    readonly property real newestUnread: unreadChats.length ? Number(unreadChats[0].time || 0) : 0
    readonly property string newFromText: unreadChats.length === 0 ? ""
        : "New from " + unreadChats.slice(0, 2).map(c => c.name).join(", ") + (unreadChats.length > 2 ? " +" + (unreadChats.length - 2) : "")
    readonly property bool newFromShown: newFromText !== "" && newestUnread > newFromClosed
    // The bar comes and goes above the rows: the controller's focus stays on the same row.
    onNewFromShownChanged: row = Math.max(0, row + (newFromShown ? 1 : -1))
    // Daily Havamal (Settings › Look): today's verse as the first row. A opens it, X (or its own X) hides it for today.
    readonly property bool verseShown: !locked && DailyVerse.cardShown && !!DailyVerse.today.n
    onVerseShownChanged: row = Math.max(0, row + (verseShown ? 1 : -1))
    function openNewFrom() {
        if (unreadChats.length === 1) { openKey = unreadChats[0].key; picked = true; zone = "messages"; return }
        // Several: the first unread row (they sit at the top of their section), or a folded folder holding one.
        let i = entries.findIndex(x => x.kind === "chat" && (x.newCount || 0) > 0)
        if (i < 0) i = entries.findIndex(x => x.kind === "folder" && x.unread)
        if (i < 0) return
        zone = "list"
        row = i
        listPane.positionViewAtIndex(i, ListView.Beginning)
    }
    function hideNewFrom() { newFromClosed = newestUnread }
    function hideVerse() { DailyVerse.hideToday(); Ui.say("Hidden until tomorrow.") }
    // Today's verse (or an earlier one, v) big: what it means, Read aloud (when it can speak), Earlier verses. Every
    // answer is a controller button: A reads aloud, X lists the earlier verses, Y goes back to today's, B closes.
    function openVerse(v) {
        const today = DailyVerse.today
        const shown = v || today
        if (!shown || !shown.n) return
        const isToday = shown.n === today.n
        const speaks = typeof ReadAloud !== "undefined" && !!ReadAloud && ReadAloud.available
        const quiet = () => { if (typeof ReadAloud !== "undefined" && ReadAloud) ReadAloud.stop() }
        const body = (isToday ? "Stanza " + shown.n + "\n\n" : "") + shown.verse + "\n\nWhat it means\n" + shown.means
        const listEarlier = () => { quiet(); page.earlierVerses() }
        Ui.sure(isToday ? "Today's verse" : "Stanza " + shown.n, body, speaks ? "Read aloud" : "Earlier verses",
                speaks ? () => { ReadAloud.speak(shown.verse.replace(/\n/g, " ") + ". What it means. " + shown.means); page.openVerse(shown)
                                  if (!ReadAloud.speaking && ReadAloud.status !== "") Ui.say(ReadAloud.status) } : listEarlier,
                false, false, quiet)
        const c = Ui.confirm
        if (!c) return
        c.cancelText = "Close"
        if (speaks) { c.otherText = "Earlier verses"; c.otherCallback = listEarlier }
        if (!isToday) { c.extraText = "Today's verse"; c.extraCallback = () => { quiet(); page.openVerse(null) } }
    }
    function earlierVerses() {
        const list = DailyVerse.earlier()
        if (!list.length) { Ui.note("Earlier verses", "New verses show here after today."); return }
        Ui.pickFrom("Earlier verses", [{ header: "", items: list.map(v => ({ name: "Stanza " + v.n, detail: v.first, icon: "text", url: String(v.n) })) }],
                    n => { const v = list.find(x => String(x.n) === n); if (v) page.openVerse(v) }, () => page.openVerse(null))
    }

    // A person shows under every folder they're in ("section" is the folder, "" for none). Folders
    // keep their own order; people in no folder go newest first until they're moved.
    readonly property var entries: {
        // Rows waiting for an answer carry a short tag instead of a second line.
        const out = []
        // Daily Hávamál first: today's verse and its first line.
        if (verseShown) out.push({ kind: "verse", name: "Today's verse · Stanza " + DailyVerse.today.n, last: DailyVerse.today.first || "" })
        // "New from Mom, Taylor +3" at the top (plan 2.14): A opens the one unread chat, or goes to the unread ones.
        if (newFromShown) out.push({ kind: "newfrom", name: newFromText })
        // A linked phone (this device as its companion): its chats in their own section at the top, then this device's own.
        if (Linked.phone.key && !tour) {
            const phoneChats = Linked.phone.ok ? Linked.mirror : []
            phoneChats.forEach((c, i) => out.push({ kind: "mirror", peer: c.peer, name: c.name, label: i === 0 ? "From your phone" : "",
                                                    unread: (c.unread || 0) > 0 && !c.muted, muted: !!c.muted }))
            if (!phoneChats.length) out.push({ kind: "mirror", peer: "", name: Linked.phone.name + "'s chats", tag: "Phone", label: "From your phone" })
        }
        // Cap g4: a join the admin's app couldn't take says so ("Not let in"), with why when the row is opened.
        Chat.groupInvites.forEach(i => out.push({ kind: "ginvite", g: i.g, name: i.name, waiting: i.waiting, fromName: i.fromName, refused: i.refused || "",
                                                  refusedText: i.refusedText || "", tag: i.refused ? "Not let in" : i.waiting ? "Waiting" : "Invite" }))
        Chat.requests.forEach(r => out.push({ kind: "request", key: r.key, name: r.name, replaces: r.replaces || "", replacesName: r.replacesName || "",
                                              tag: r.replaces ? "New key" : "Request" }))
        // Introductions, group members asking and Family offers (i1); a linked phone's chats (companion).
        PeopleActions.pendingEntries().forEach(e => out.push(Object.assign({ unread: true, tag: e.pendingKind === "intro" ? "Intro" : "Request" }, e)))
        // Archived chats wait behind one Archived row; pinned ones sit at the top.
        // Contacts only: anyone else is in the Nearby section at the bottom.
        const all = Chat.chats, chats = all.filter(c => !c.archived && c.contact), byKey = {}
        chats.forEach(c => byKey[c.key] = c)
        // A chat's row: its name, the last message under it, and how many are new.
        const person = (c, section) => ({ kind: "chat", key: c.key, name: c.name, unread: c.unread, newCount: c.newCount || 0, last: c.last || "",
                                          route: c.route, section: section, tag: c.pinned && section === "" ? "Pinned" : "", muted: !!c.muted })
        // Plan 2.14: pinned first, then the unread ones newest first, then the rest as before (newest first, or as moved).
        const fresh = c => (c.newCount || 0) > 0
        const newestFirst = (a, b) => (b.time || 0) - (a.time || 0)
        const order = Chat.topOrder
        const loose = chats.filter(c => !(c.folders || []).length)
        loose.filter(c => c.pinned).forEach(c => out.push(person(c, "")))
        loose.filter(c => !c.pinned && fresh(c)).sort(newestFirst).forEach(c => out.push(person(c, "")))
        loose.filter(c => !c.pinned && !fresh(c) && order.indexOf(c.key) < 0).forEach(c => out.push(person(c, "")))
        order.forEach(k => { const c = byKey[k]; if (c && !c.pinned && !fresh(c) && !(c.folders || []).length) out.push(person(c, "")) })
        Chat.folders.forEach(f => {
            const keys = (f.keys || []).filter(k => !!byKey[k])
            out.push({ kind: "folder", id: f.id, name: f.name, collapsed: !!f.collapsed, count: keys.length,
                       unread: !!f.collapsed && keys.some(k => byKey[k].unread) })
            const shown = keys.filter(k => fresh(byKey[k])).sort((a, b) => newestFirst(byKey[a], byKey[b])).concat(keys.filter(k => !fresh(byKey[k])))
            if (!f.collapsed) shown.forEach(k => out.push(person(byKey[k], f.id)))
        })
        const archived = all.filter(c => c.archived)
        if (archived.length) out.push({ kind: "archived", name: "Archived", tag: String(archived.length), unread: archived.some(c => c.unread) })
        // Nearby always last, in a small section of its own below every chat and folder, never mixed in: the room, then
        // everyone who isn't a contact (chatted with, then just around). Nearby off: none of it.
        if (Chat.visibleNearby) {
            out.push({ kind: "nearby", name: "Nearby room", label: "Nearby", tag: Chat.nearbyOn ? "" : "Off" })
            all.filter(c => !c.contact && !c.archived).forEach(c => out.push({ kind: "near", key: c.key, name: c.name, unread: c.unread, route: c.route,
                                                                               hasChat: true, muted: !!c.muted }))
            Chat.around.forEach(p => out.push({ kind: "near", key: p.key, name: p.shown, alt: p.name, route: p.route, hasChat: false, muted: Chat.isMuted(p.key) }))
        }
        return out
    }
    readonly property var messages: {
        if (tour && openKey === "")
            return [{ id: "1", name: "Raven 4821", text: "Anyone have the Emerald save from yesterday?", mine: false, time: Date.now() - 60000 },
                    { id: "2", name: Ui.myName, text: "Sending it over LocalSend now", mine: true, time: Date.now(), state: "read" }]
        return openKey === "" ? (picked && Chat.visibleNearby ? Chat.nearby : []) : (Chat.chats, Chat.messagesWith(openKey))
    }
    readonly property string openName: openKey === "" ? "" : (Chat.chats, Chat.nameOf(openKey))   // Chat.chats: follows renames
    readonly property bool keyWarning: openKey !== "" && !tour && (Chat.chats, Chat.keyChanged(openKey))

    // What the hint bar shows (Main.qml adds LB). LT opens the menu (the page gets it as X).
    readonly property var prompts: mirror.visible ? mirror.prompts
        : more.prompts.length ? more.prompts
        : finder.visible && !Voice.recording ? [{ key: "dpad", label: "Older or newer" }, { key: "a", label: "Type" }, { key: "b", label: "Close" }]
        : Voice.recording ? [{ key: "y", label: "Let go: send" }, { key: "b", label: "Cancel" }]
        : lock.visible ? lock.prompts   // only the buttons that do something on its current step (LockPanel)
        : showCode ? [{ key: "a", label: "Copy link" }, { key: "x", label: "New code" }, { key: "b", label: "Close" }]
        : zone === "messages" && fileStrip.focusIndex >= 0 ? [{ key: "a", label: fileStrip.focusLabel() }, { key: "b", label: "Back" }]
        : reactPicker.visible || compose.emojiShown || threadBox.emojiShown ? [{ key: "a", label: "Pick" }, { key: "b", label: "Close" }]
        : typingIn ? [{ key: "a", label: "Send" }].concat(System.keyboardAvailable ? [{ key: "y", label: "Keyboard" }] : [], [{ key: "lt", label: "Options" }, { key: "b", label: "Done" }])
        : zone === "head" ? [{ key: "dpad", label: "Choose" }, { key: "a", label: headButtons[headSel] === "menu" ? "Menu"
                                                                                  : headButtons[headSel] === "find" ? "Find" : "Call" }, { key: "b", label: "Back" }]
        : zone === "side" ? (side === "thread" ? [{ key: "a", label: "Reply in the thread" }] : [{ key: "a", label: "Go to it" }])
                            .concat([{ key: "lt", label: "Options" }, { key: "b", label: "Close" }])
        : zone === "list" && entries[row] && entries[row].kind === "verse" ? [{ key: "a", label: "Open" }, { key: "lt", label: "Options" }, { key: "b", label: "Back" }]
        : zone === "list" && entries[row] && entries[row].kind === "newfrom" ? [{ key: "a", label: unreadChats.length === 1 ? "Open" : "Go to them" },
                                                                             { key: "lt", label: "Hide" }, { key: "b", label: "Back" }]
        : zone === "list" ? [{ key: "a", label: entries[row] && entries[row].kind === "folder" ? (entries[row].collapsed ? "Expand" : "Collapse") : "Open" },
                             { key: "lt", label: "Menu" }].concat(entries[row] && (entries[row].kind === "chat" || entries[row].kind === "near") ? [{ key: "y", label: "Quick menu" }] : [])
                             .concat([{ key: "b", label: "Back" }])
        : !picked ? [{ key: "a", label: "Add someone" }, { key: "b", label: "Back" }]
        : [{ key: "a", label: keep.visible && sel < 0 ? "Keep in touch" : sel >= 0 && messages[sel] && messages[sel].call && openKey !== "" ? "Call back" : "Write" }].concat(openKey !== "" ? [{ key: "y", label: "Hold to record" }] : [])
            .concat(sel >= 0 ? [{ key: "lt", label: "Options" }] : openKey !== "" ? [{ key: "lt", label: "Menu" }] : []).concat([{ key: "b", label: "Back" }])
    readonly property string status: lock.visible ? "" : Chat.netStatus

    function clock(ms) { return Qt.formatTime(new Date(ms), "h:mm AP") }
    function stateLabel(m) {
        if (!m.mine) return ""
        if (m.failed) return " · Not sent, tap to retry"
        if (m.state === "unsending") return " · Removing from their phone"
        if (m.unsent) return ""
        return m.state === "read" ? " · Read" : m.state === "delivered" ? " · Delivered" : m.state === "sent" ? " · Sent" : m.state === "sending" ? " · Sending" : ""
    }
    // Discord-style grouping: the same person again within 7 minutes shows no name or picture.
    function groupedAt(i) {
        if (i <= 0 || i === newFrom) return false
        const a = messages[i - 1], b = messages[i]
        if (!a || !b || a.system || b.system) return false
        if (!!a.mine !== !!b.mine || (!b.mine && (a.name || "") !== (b.name || ""))) return false
        return Math.abs((b.time || 0) - (a.time || 0)) < 420000
    }
    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    function senderName(m) { return m.mine ? Ui.myName : openKey === "" ? (m.name || "Someone") : openName }

    onOpenKeyChanged: {
        sel = -1; Voice.stop(); finder.close()
        // A voice message held on Y (or the microphone) belongs to the chat it started in: another chat (a tap in the
        // list, a notification) throws it away, never sends it here.
        if (Voice.recording) { Voice.cancel(); Ui.say("Recording cancelled.") }
        if (zone === "head") zone = "messages"
        replyId = ""; editId = ""; reactFor = ""; threadRoot = ""; side = ""
        compose.text = ""
        // Where the unread ones start (after my last message), for the "New messages" line.
        newFrom = -1
        if (openKey !== "" && !tour && !locked) {
            const c = Chat.chats.find(x => x.key === openKey)
            if (c && c.unread) {
                let i = messages.length
                while (i > 0 && !messages[i - 1].mine) i--
                newFrom = i < messages.length ? i : -1
            }
        }
        if (newFrom >= 0) Qt.callLater(() => list.positionViewAtIndex(page.newFrom, ListView.Beginning))
        if (openKey !== "") { Chat.markRead(openKey); if (!tour && Chat.isContact(openKey)) Chat.probeCaps(openKey) }
        Chat.openChat = openKey
        // The first time this chat opens: how long its messages are kept (said once).
        if (openKey !== "" && !tour && !locked) { const keep = Chat.keepNoteOnce(openKey); if (keep !== "") Ui.say(keep) }
    }
    // Read only counts when someone is actually looking: not while a game is in front.
    onMessagesChanged: if (openKey !== "" && !locked && Qt.application.state === Qt.ApplicationActive) Chat.markRead(openKey)
    onSideSelChanged: if (sideSel >= 0) sideList.positionViewAtIndex(sideSel, ListView.Contain)
    onSideChanged: {
        sideSel = -1
        if (side === "" && zone === "side") zone = "messages"
        // A hidden thread box can't keep the keyboard: back to the page (the chat's box keeps it).
        if (side !== "thread" && !compose.hasFocus && Ui.main) Ui.main.restoreFocus()
    }
    Component.onCompleted: showLimitsNote()
    // Cap d1: told once, on the chat list, that messages don't stay forever. The one place this is explained.
    function showLimitsNote() {
        if (tour || locked || !Chat.ready || !Chat.limitsNote()) return
        Chat.dismissLimitsNote()
        Ui.sure("Messages delete after 7 days", "On every device, so old chats can't leak. To keep a chat longer, put the person in Family or Work (Folders and role) and pick up to 30 days. Family groups can keep up to 30 days too.", "Got it", () => {})
    }
    Connections { target: Chat; function onChanged() { page.showLimitsNote() } }
    Component.onDestruction: { Chat.openChat = ""; Voice.cancel(); Voice.stop() }
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive) { if (page.openKey !== "" && !page.locked) Chat.markRead(page.openKey) }
        }
    }

    // ============================================================= middle column: the list
    // The desktop can hide it (Ctrl+Shift+L); the controller still opens it with Left.
    readonly property bool listHidden: !middle.visible
    Rectangle {
        id: middle
        // Portrait: the list on its own, until a chat opens (the lock shows in the main column).
        visible: Theme.portrait ? page.zone === "list" && !lock.visible : Theme.listShown || page.zone === "list" || lock.visible
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Ui.dock
        width: visible ? (Theme.portrait ? page.width : Theme.listWidth) : 0
        color: Theme.column

        PageHeader {
            id: listHead
            width: parent.width
            carved: false
            title: "Chats"
            // Add someone, my code or a one-time link.
            Rectangle {
                visible: !page.locked
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(28); height: width
                radius: Theme.radius
                color: addHover.hovered ? Theme.raised : "transparent"
                Accessible.role: Accessible.Button
                Accessible.name: "Add someone"
                Accessible.onPressAction: page.addMenu()
                Icon { anchors.centerIn: parent; name: "plus"; size: Theme.px(18); lineWidth: 2; color: addHover.hovered ? Theme.text : Theme.textMuted }
                HoverHandler { id: addHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: page.addMenu() }
            }
            // The list's own menu: New folder and Trash.
            MoreButton {
                visible: !page.locked
                anchors.verticalCenter: parent.verticalCenter
                label: "Chats menu"
                onClicked: page.listMenu()
            }
        }

        // The rows change in place (LiveList), so the list keeps its place as messages arrive.
        LiveList { id: entryModel; keys: ["kind", "key", "id", "g", "section", "name"]; items: page.entries }
        ListView {
            id: listPane
            visible: !page.locked
            anchors.top: listHead.bottom
            anchors.topMargin: Theme.px(8)
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(6)
            x: Theme.px(8)
            width: parent.width - Theme.px(16)
            spacing: Theme.px(2)
            clip: true
            model: entryModel
            currentIndex: page.row
            highlightFollowsCurrentItem: false
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            // A right click (or a long press) on the empty space under the rows: New chat, New group and New folder.
            MouseArea {
                id: spaceArea
                z: -1
                y: listPane.originY
                width: listPane.width
                height: Math.max(listPane.contentHeight, listPane.height)
                acceptedButtons: Qt.RightButton
                onClicked: mouse => page.spaceMenuAt(spaceArea, Qt.point(mouse.x, mouse.y))
                TapHandler {
                    acceptedDevices: PointerDevice.TouchScreen
                    onLongPressed: page.spaceMenuAt(spaceArea, point.position)
                }
            }
            delegate: Item {
                id: entry
                // The row's item, kept while the delegate goes away: a removed row (today's verse hidden, the "New from" bar
                // gone) has no modelData any more, yet its bindings on page.row still run once before it's destroyed.
                readonly property var rowData: modelData !== undefined && modelData !== null ? modelData : ({})
                readonly property bool on: page.active && page.zone === "list" && page.row === index
                readonly property bool isHeader: entry.rowData.kind === "folder"
                readonly property real labelHeight: entry.rowData.label ? Theme.px(30) : 0
                // A chat with messages: two lines (the name, then the last message).
                readonly property bool twoLines: (entry.rowData.kind === "chat" || entry.rowData.kind === "verse") && !!entry.rowData.last
                readonly property real rowHeight: twoLines ? Theme.px(54) : Theme.px(42)
                width: listPane.width
                height: labelHeight + (isHeader ? Theme.px(30) : rowHeight)

                // The start of the bottom section (Nearby), or of the people around you.
                Item {
                    visible: !!entry.rowData.label
                    width: parent.width
                    height: entry.labelHeight
                    Rectangle { visible: entry.rowData.kind === "nearby"; anchors.top: parent.top; anchors.topMargin: Theme.px(6); width: parent.width; height: 1; color: Theme.line }
                    Text {
                        textFormat: Text.PlainText
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(4)
                        text: String(entry.rowData.label || "").toUpperCase()
                        color: Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1)
                    }
                }

                // A folder's header: tap (or A) folds it away; its three dots, a right click or X for its menu.
                Rectangle {
                    visible: entry.isHeader
                    anchors.bottom: parent.bottom
                    width: parent.width
                    height: Theme.px(30)
                    radius: Theme.radius
                    color: entry.on || (page.dragKey !== "" && page.dropSection === entry.rowData.id && page.dropOnHeader) ? Theme.tile : headHover.hovered ? Theme.raised : "transparent"
                    border.width: entry.on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.Button
                    Accessible.name: entry.rowData.name + (entry.rowData.collapsed ? ", folded away" : "")
                    Icon {
                        id: chevron
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(8)
                        anchors.verticalCenter: parent.verticalCenter
                        name: "down"; size: Theme.px(14)
                        rotation: entry.rowData.collapsed ? -90 : 0
                        color: Theme.textMuted
                    }
                    Text {
                        textFormat: Text.PlainText
                        anchors.left: chevron.right; anchors.leftMargin: Theme.px(6)
                        anchors.right: headDot.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight
                        text: String(entry.rowData.name || "").toUpperCase() + "  " + (entry.rowData.count || 0)
                        color: entry.on || entry.rowData.unread ? Theme.text : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(0.5)
                    }
                    Rectangle {
                        id: headDot
                        anchors.right: folderMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(8); height: width; radius: width / 2
                        // A chat with its own color shows it here too.
                        color: entry.rowData.kind === "chat" && !page.isTour ? Theme.chatAccent((Chat.chats, Chat.chatColor(entry.rowData.key))) : Theme.accent
                        visible: !!entry.rowData.unread
                    }
                    HoverHandler { id: headHover }
                    TapHandler {
                        id: headTap
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onTapped: (point, button) => { page.zone = "list"; page.row = index; if (button === Qt.RightButton) page.rowContextAt(headTap.parent, point.position, entry.rowData); else page.choose() }
                        onLongPressed: { page.zone = "list"; page.row = index; page.rowContextAt(headTap.parent, point.position, entry.rowData) }
                    }
                    MoreButton {
                        id: folderMore
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(2)
                        anchors.verticalCenter: parent.verticalCenter
                        implicitWidth: Theme.px(24); implicitHeight: Theme.px(24)
                        label: "Folder menu"
                        onClicked: { page.zone = "list"; page.row = index; page.folderOptions(entry.rowData) }
                    }
                }

                Rectangle {
                    readonly property bool on: entry.on
                    readonly property bool here: (entry.rowData.kind === "nearby" && page.openKey === "") || (entry.rowData.key !== undefined && entry.rowData.key === page.openKey)
                    readonly property bool person: entry.rowData.kind === "chat" || entry.rowData.kind === "near" || entry.rowData.kind === "request"
                    visible: !entry.isHeader
                    anchors.bottom: parent.bottom
                    width: parent.width
                    height: entry.rowHeight
                    radius: Theme.radius
                    // The one being dragged stays dim in its old place until it's dropped.
                    opacity: page.dragKey !== "" && entry.rowData.kind === "chat" && entry.rowData.key === page.dragKey && entry.rowData.section === page.dragFrom ? 0.4 : 1
                    // The "New from" bar sits on the accent's soft fill.
                    color: on || here ? Theme.tile : entry.rowData.kind === "newfrom" || entry.rowData.kind === "verse" ? Theme.accentFill : rowHover.hovered ? Theme.raised : "transparent"
                    border.width: on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: entry.rowData.kind === "newfrom" || entry.rowData.kind === "verse" ? Accessible.Button : Accessible.ListItem
                    Accessible.name: entry.rowData.name + (entry.rowData.tag ? ", " + entry.rowData.tag : "") + (entry.rowData.muted ? ", muted" : "")
                                     + ((entry.rowData.newCount || 0) > 0 ? ", " + entry.rowData.newCount + " unread" : entry.rowData.unread ? ", unread" : "")
                                     + (entry.twoLines ? ", " + entry.rowData.last : "")
                    Rectangle {
                        id: ava
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(32); height: width; radius: Theme.radius
                        color: parent.person ? "transparent" : Theme.raised
                        // A person: their round picture, with their status in the corner.
                        Avatar {
                            visible: parent.parent.person
                            anchors.fill: parent
                            size: parent.width
                            key: page.isTour || entry.rowData.kind !== "chat" ? "" : entry.rowData.key || ""
                            name: entry.rowData.name
                            showDot: entry.rowData.kind === "chat" && Chat.activeStatus
                            backdrop: parent.parent.on || parent.parent.here ? Theme.tile : Theme.column
                        }
                        Icon {
                            visible: !parent.parent.person
                            anchors.centerIn: parent
                            size: Theme.px(18)
                            name: entry.rowData.kind === "ginvite" ? "hash" : entry.rowData.kind === "mirror" ? "phone" : entry.rowData.kind === "archived" ? "folder"
                                : entry.rowData.kind === "newfrom" ? "bell" : entry.rowData.kind === "verse" ? "text" : "people"
                            color: parent.parent.here || parent.parent.on || entry.rowData.kind === "newfrom" || entry.rowData.kind === "verse" ? Theme.accent : Theme.textMuted
                        }
                        // Requests wait for an answer: a lock badge so they stand out.
                        Rectangle {
                            visible: entry.rowData.kind === "request" || (entry.rowData.kind === "ginvite" && !entry.rowData.waiting)
                            anchors.right: parent.right; anchors.bottom: parent.bottom
                            anchors.margins: -Theme.px(2)
                            width: Theme.px(14); height: width; radius: width / 2
                            color: Theme.accent
                        }
                    }
                    // The name, a short tag for what waits for an answer, the unread count (or dot) and the three dots.
                    // Unread (plan 2.14): the name bold and the last message in full text color; read: lighter and muted.
                    Text {
                        id: nameText
                        textFormat: Text.PlainText
                        anchors.left: ava.right; anchors.leftMargin: Theme.px(10)
                        anchors.right: closeNew.visible ? closeNew.left : mutedIcon.visible ? mutedIcon.left : tagChip.visible ? tagChip.left : dot.visible ? dot.left : rowMore.left
                        anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.verticalCenterOffset: entry.twoLines ? -Theme.px(9) : 0
                        elide: Text.ElideRight; text: entry.rowData.name || ""
                        color: parent.on || parent.here || entry.rowData.unread || entry.rowData.kind === "newfrom" || entry.rowData.kind === "verse" ? Theme.text : Theme.textSoft
                        font.family: Theme.body; font.pixelSize: Theme.fs(15)
                        font.weight: (entry.rowData.newCount || 0) > 0 ? Font.Bold : entry.rowData.unread || entry.rowData.kind === "newfrom" || entry.rowData.kind === "verse" ? Font.DemiBold : Font.Normal
                    }
                    // The last message, under the name.
                    Text {
                        visible: entry.twoLines
                        textFormat: Text.PlainText
                        anchors.left: nameText.left
                        anchors.right: closeNew.visible ? closeNew.left : dot.visible ? dot.left : rowMore.left
                        anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.verticalCenterOffset: Theme.px(10)
                        elide: Text.ElideRight; maximumLineCount: 1
                        text: String(entry.rowData.last || "").replace(/\s+/g, " ")
                        color: (entry.rowData.newCount || 0) > 0 ? Theme.text : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(13)
                    }
                    // The "New from" bar's X: hidden until the next new message (a controller uses X). Today's verse: hidden for today.
                    Rectangle {
                        id: closeNew
                        readonly property bool verse: entry.rowData.kind === "verse"
                        visible: entry.rowData.kind === "newfrom" || verse
                        anchors.right: rowMore.left; anchors.rightMargin: Theme.px(2)
                        anchors.verticalCenter: parent.verticalCenter
                        width: visible ? Theme.px(28) : 0; height: Theme.px(28)
                        radius: Theme.radius
                        color: closeArea.containsMouse ? Theme.raised : "transparent"
                        Accessible.role: Accessible.Button
                        Accessible.name: closeNew.verse ? "Hide for today" : "Hide until the next message"
                        Accessible.onPressAction: closeNew.verse ? page.hideVerse() : page.hideNewFrom()
                        Icon { anchors.centerIn: parent; name: "close"; size: Theme.px(14); lineWidth: 2; color: closeArea.containsMouse ? Theme.text : Theme.textMuted }
                        // A MouseArea, so the click never reaches the bar underneath.
                        MouseArea { id: closeArea; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: closeNew.verse ? page.hideVerse() : page.hideNewFrom() }
                    }
                    // A muted chat: a bell struck through.
                    Icon {
                        id: mutedIcon
                        visible: !!entry.rowData.muted
                        anchors.right: tagChip.visible ? tagChip.left : dot.visible ? dot.left : rowMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        name: "belloff"; size: Theme.px(14)
                        color: Theme.textMuted
                    }
                    Rectangle {
                        id: tagChip
                        visible: !!entry.rowData.tag
                        anchors.right: dot.visible ? dot.left : rowMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        width: tagText.implicitWidth + Theme.px(12); height: tagText.implicitHeight + Theme.px(4)
                        radius: Theme.radiusSmall
                        color: "transparent"
                        border.color: entry.rowData.kind === "nearby" || entry.rowData.waiting ? Theme.lineMuted : Theme.accent
                        Text {
                            textFormat: Text.PlainText
                            id: tagText
                            anchors.centerIn: parent
                            text: entry.rowData.tag || ""
                            color: entry.rowData.kind === "nearby" || entry.rowData.waiting ? Theme.textMuted : Theme.accent
                            font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold
                        }
                    }
                    // Unread: how many in the accent (a chat with something new), else a dot.
                    Rectangle {
                        id: dot
                        readonly property int n: entry.rowData.kind === "chat" ? entry.rowData.newCount || 0 : 0
                        anchors.right: rowMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        height: n > 0 ? Theme.px(18) : Theme.px(8)
                        width: n > 0 ? Math.max(height, countText.implicitWidth + Theme.px(10)) : height
                        radius: height / 2
                        // A chat with its own color shows it here too.
                        color: entry.rowData.kind === "chat" && !page.isTour ? Theme.chatAccent((Chat.chats, Chat.chatColor(entry.rowData.key))) : Theme.accent
                        visible: !!entry.rowData.unread
                        Text {
                            id: countText
                            visible: dot.n > 0
                            anchors.centerIn: parent
                            textFormat: Text.PlainText
                            text: dot.n > 99 ? "99+" : String(dot.n)
                            color: Theme.onAccent
                            font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold
                        }
                    }
                    HoverHandler { id: rowHover }
                    // Someone nearby and the other rows: a right click or a long press for their menu (rowContext).
                    TapHandler {
                        id: rowTap
                        enabled: entry.rowData.kind !== "chat"
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onTapped: (point, button) => { page.zone = "list"; page.row = index; if (button === Qt.RightButton) page.rowContextAt(rowTap.parent, point.position, entry.rowData); else page.choose() }
                        onLongPressed: { page.zone = "list"; page.row = index; page.rowContextAt(rowTap.parent, point.position, entry.rowData) }
                    }
                    // People: a click opens, a right click shows their options, and a mouse drag moves
                    // them up or down or into another folder. Touch still scrolls the list.
                    MouseArea {
                        id: dragArea
                        anchors.fill: parent
                        enabled: entry.rowData.kind === "chat"
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        property real startY: 0
                        property real startX: 0
                        property bool moved: false
                        onPressed: mouse => {
                            startY = mouse.y
                            startX = mouse.x
                            moved = false
                            preventStealing = mouse.button === Qt.LeftButton && mouse.source === Qt.MouseEventNotSynthesized
                        }
                        onPositionChanged: mouse => {
                            if (!preventStealing) return
                            if (!moved && (Math.abs(mouse.y - startY) > Theme.px(8) || Math.abs(mouse.x - startX) > Theme.px(8))) { moved = true; page.startDrag(entry.rowData) }
                            if (!moved) return
                            page.dragTo(mapToItem(listPane, mouse.x, mouse.y).y)
                            // Over a group in the rail: let go there to invite them.
                            const w = mapToItem(null, mouse.x, mouse.y)
                            if (Ui.main) Ui.main.railDropHint(Ui.main.railGroupAt(w.x, w.y))
                        }
                        onReleased: mouse => {
                            if (!moved) return
                            const w = mapToItem(null, mouse.x, mouse.y)
                            const g = Ui.main ? Ui.main.railGroupAt(w.x, w.y) : ""
                            if (Ui.main) Ui.main.railDropHint("")
                            if (g !== "") { const k = page.dragKey, n = page.dragName; page.cancelDrag(); page.inviteTo(g, k, n) }
                            else page.dropHere()
                        }
                        onCanceled: { if (moved) { page.cancelDrag(); if (Ui.main) Ui.main.railDropHint("") } moved = false }
                        onClicked: mouse => {
                            if (moved) return
                            page.zone = "list"; page.row = index
                            if (mouse.button === Qt.RightButton) page.rowContextAt(dragArea, Qt.point(mouse.x, mouse.y), entry.rowData); else page.choose()
                        }
                        // Press and hold: the same menu (a touch screen has no right click).
                        onPressAndHold: mouse => { if (!moved && mouse.source !== Qt.MouseEventNotSynthesized) { page.row = index; page.rowContextAt(dragArea, Qt.point(mouse.x, mouse.y), entry.rowData) } }
                        Component.onDestruction: if (moved) page.cancelDrag()
                    }
                    // A person's one menu (only people have one; the rest answer A or a click).
                    MoreButton {
                        id: rowMore
                        visible: entry.rowData.kind === "chat" || entry.rowData.kind === "near"
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(4)
                        anchors.verticalCenter: parent.verticalCenter
                        width: visible ? implicitWidth : 0
                        label: "More for " + (entry.rowData.name || "")
                        onClicked: { page.zone = "list"; page.row = index; page.rowMenu(entry.rowData) }
                    }
                }
            }
            // Where a dragged person would land.
            Rectangle {
                visible: page.dragKey !== "" && page.dropLine >= 0 && !page.dropOnHeader
                y: page.dropLine - height / 2
                z: 5
                width: listPane.width
                height: Theme.px(3)
                radius: height / 2
                color: Theme.accent
            }
        }
        // The person being dragged, under the pointer.
        Rectangle {
            visible: page.dragKey !== ""
            z: 6
            x: listPane.x + Theme.px(16)
            y: listPane.y + page.dragY - height / 2
            width: listPane.width - Theme.px(32)
            height: Theme.px(36)
            radius: Theme.radius
            color: Theme.raised
            border.width: 1
            border.color: Theme.accent
            opacity: 0.95
            Text {
                textFormat: Text.PlainText
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                elide: Text.ElideRight
                text: page.dragName
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
            }
        }
        // Near the top or bottom edge while dragging: the list scrolls.
        Timer {
            interval: 30
            repeat: true
            running: page.dragKey !== ""
            onTriggered: {
                const edge = Theme.px(36), maxY = Math.max(0, listPane.contentHeight - listPane.height)
                let dy = 0
                if (page.dragY < edge) dy = -Theme.px(10)
                else if (page.dragY > listPane.height - edge) dy = Theme.px(10)
                if (dy === 0) return
                listPane.contentY = Math.max(listPane.originY, Math.min(listPane.originY + maxY, listPane.contentY + dy))
                page.dragTo(page.dragY)
            }
        }
        Text {
            textFormat: Text.PlainText
            visible: page.locked
            anchors.top: listHead.bottom
            anchors.topMargin: Theme.px(16)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: Vault.lockNeeded ? "Set a new lock to start." : Vault.exists ? "Locked." : "Set a lock to start."
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
    }

    // ============================================================= main column: the conversation
    Item {
        id: main
        visible: !Theme.portrait || !middle.visible
        anchors.left: middle.right
        anchors.right: sidePanel.visible && page.sideDocked ? sidePanel.left : members.visible ? members.left : parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom

        PageHeader {
            id: convHead
            width: parent.width
            carved: page.locked || !page.picked
            backShown: Theme.portrait && !page.locked
            onBack: { page.zone = "list"; page.sel = -1 }
            title: page.locked ? "Locked" : !page.picked ? "Chats" : page.openKey === "" ? "Nearby room" : page.openName
            // Like Muninn's: Muted first, then Active now, or a changed security key. How messages travel and Verified are
            // in the person's menu (More).
            subtitle: page.locked || !page.picked ? "" : page.openKey === "" ? "People close by"
                    : (page.openMuted ? ["Muted"] : []).concat(page.theyActive ? ["Active now"] : [])
                      .concat(page.keyWarning ? ["Security key changed"] : []).join(" · ")
            warn: !page.locked && page.picked && page.keyWarning
            accent: page.tinted && page.picked ? page.tint : "transparent"
            // Name, one Call button (Voice call and Video call in its popup), Find and the person's one menu.
            // Mute is in the menu, so the name keeps its room.
            // A plain phone, like Muninn's; Video call is one of the choices it opens.
            Rectangle {
                id: callBtn
                anchors.verticalCenter: parent.verticalCenter
                visible: !page.locked && page.openKey !== ""
                width: Theme.px(28); height: width
                radius: Theme.radius
                color: callHover.hovered ? Theme.raised : "transparent"
                border.width: page.zone === "head" && page.pad && page.headButtons[page.headSel] === "call" ? Theme.focusWidth : 0
                border.color: Theme.text
                Accessible.role: Accessible.Button
                Accessible.name: "Call"
                Accessible.onPressAction: page.callMenu()
                Icon { anchors.centerIn: parent; name: "call"; size: Theme.px(18); color: page.tinted ? page.tint : callHover.hovered ? Theme.text : Theme.textMuted }
                HoverHandler { id: callHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: page.callMenu() }
            }
            // Find in this chat (Ctrl+F too).
            Rectangle {
                visible: !page.locked && page.picked
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(28); height: width
                radius: Theme.radius
                color: findHover.hovered || finder.visible ? Theme.raised : "transparent"
                border.width: page.zone === "head" && page.pad && page.headButtons[page.headSel] === "find" ? Theme.focusWidth : 0
                border.color: Theme.text
                Accessible.role: Accessible.Button
                Accessible.name: "Find in this chat"
                Accessible.onPressAction: page.openFind()
                Icon { anchors.centerIn: parent; name: "search"; size: Theme.px(18); color: findHover.hovered || finder.visible ? Theme.text : Theme.textMuted }
                HoverHandler { id: findHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: page.openFind() }
            }
            MoreButton {
                visible: !page.locked && page.openKey !== ""
                anchors.verticalCenter: parent.verticalCenter
                label: "More for " + page.openName
                onClicked: page.chatMenu()
                Rectangle {
                    anchors.fill: parent
                    visible: page.zone === "head" && page.pad && page.headButtons[page.headSel] === "menu"
                    color: "transparent"; radius: Theme.radius
                    border.width: Theme.focusWidth; border.color: Theme.text
                }
            }
        }

        // ------------------------------------------------------- locked
        LockPanel {
            id: lock
            visible: page.locked || page.changeLock || page.lockOff || page.ownLock
            mode: page.ownLock && !page.locked ? "own" : page.lockOff && !page.locked ? "off" : page.changeLock && !page.locked ? "change"
                : Vault.lockNeeded ? "renew" : Vault.spareStep !== "" ? "spare" : Vault.exists ? "unlock" : "setup"
            anchors.top: convHead.bottom
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width - Theme.px(32), Theme.px(520))
            onFinished: how => {
                if (how === "change") page.changeLock = false   // after an unlock, the change step follows
                if (how === "off") page.lockOff = false
                if (how === "own") { page.ownLock = false; Ui.say("This device's own chats are open until Huginn locks.") }
                // The first time: link a phone, when nothing is linked yet.
                // The first time: the name people see, then link a phone when nothing is linked yet.
                if (how === "setup") Qt.callLater(page.askFirstName)
                // A lock shorter than new ones may be: suggest a stronger one, once.
                if (how === "unlock" && Vault.askStronger())
                    Ui.sure("Make your lock stronger?", "Your " + (Vault.usesPattern ? "pattern" : "password") + " is shorter than new ones may be. One with at least 6 "
                            + (Vault.usesPattern ? "dots" : "characters") + " is much harder to guess.", "Change it", () => { page.changeLock = true })
            }
            onCancelled: {
                if (Vault.lockNeeded) return   // a new lock after "Forgot it?" can't be skipped
                if (page.ownLock) page.ownLock = false
                else if (page.lockOff) page.lockOff = false
                else if (page.changeLock) page.changeLock = false
                else Ui.closePage()
            }
        }

        Item {
            id: pane
            visible: !lock.visible
            anchors.top: convHead.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: compose.visible ? compose.top : parent.bottom
            clip: true

            // Keep in touch: someone met close by, after 10 messages or once they're out of range.
            Rectangle {
                id: keep
                visible: page.openKey !== "" && Chat.shouldAskKeep(page.openKey) && (Chat.chats, true)
                anchors.top: parent.top; anchors.left: parent.left; anchors.right: parent.right
                anchors.margins: Theme.px(12)
                height: visible ? keepCol.implicitHeight + Theme.px(20) : 0
                radius: Theme.radius; color: Theme.accentFill; border.color: Theme.accent; border.width: 1
                Column {
                    id: keepCol
                    x: Theme.px(14); y: Theme.px(10); width: parent.width - Theme.px(28)
                    spacing: Theme.px(6)
                    Text { textFormat: Text.PlainText; width: parent.width; wrapMode: Text.WordWrap; text: Chat.theySaidKeep(page.openKey) ? page.openName + " wants to keep in touch" : "Keep in touch with " + page.openName + "?"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold }
                    Text { textFormat: Text.PlainText; width: parent.width; wrapMode: Text.WordWrap; text: "Then message from anywhere."; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                    Row {
                        spacing: Theme.px(8)
                        HButton { text: "Yes"; kind: "primary"; accent: page.tint; focused: page.pad && page.active && page.zone === "messages" && page.sel < 0; implicitHeight: Theme.px(32); fontSize: 13; onClicked: Chat.keepInTouch(page.openKey) }
                        HButton { text: "Not now"; kind: "plain"; implicitHeight: Theme.px(32); fontSize: 13; onClicked: Chat.notNow(page.openKey) }
                    }
                }
            }

            // Files on their way to or from them (cap f1).
            FileStrip {
                id: fileStrip
                peer: page.tour ? "" : page.openKey
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.leftMargin: Theme.px(14); anchors.rightMargin: Theme.px(14)
            }
            // Find in this chat: over the messages, which start under it.
            FindBar {
                id: finder
                anchors.top: keep.visible ? keep.bottom : parent.top
                anchors.left: parent.left; anchors.right: parent.right
                anchors.margins: Theme.px(12)
                messages: page.messages
                accent: page.tint
                onJump: i => list.positionViewAtIndex(i, ListView.Center)
                onClosed: if (Ui.main && !compose.hasFocus) Ui.main.restoreFocus()
            }
            // Messages change in place (LiveList): a new one adds a row instead of redrawing them all.
            LiveList { id: messageModel; keys: ["id"]; items: page.messages }
            ListView {
                id: list
                anchors.top: finder.visible ? finder.bottom : keep.visible ? keep.bottom : parent.top
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: fileStrip.visible ? fileStrip.top : parent.bottom
                anchors.topMargin: Theme.px(12); anchors.bottomMargin: Theme.px(12)
                spacing: 0
                model: messageModel
                onCountChanged: positionViewAtEnd()
                delegate: Rectangle {
                    id: msg
                    readonly property bool picked: page.active && page.zone === "messages" && page.sel === index
                    // The "New messages" line sits over the first unread one.
                    readonly property int lineH: index === page.newFrom ? Theme.px(30) : 0
                    // Grouped under the one before (same person within 7 minutes): no name or picture again.
                    readonly property bool grouped: page.groupedAt(index)
                    readonly property int gap: grouped ? Theme.px(1) : Theme.px(Theme.compact ? 4 : Theme.spacious ? 16 : 10)
                    width: list.width
                    height: modelData.system ? note.height + Theme.px(12) : lineH + gap + Math.max(face.visible ? face.height : 0, col.implicitHeight) + Theme.px(grouped ? 2 : 4)
                    radius: 0
                    // Find in this chat: every match lit, the one shown more.
                    readonly property bool found: finder.visible && finder.hits.indexOf(index) >= 0
                    readonly property bool foundNow: found && finder.current === index
                    // Show in chat (Media and files): lit for a moment.
                    readonly property bool flash: page.flashId !== "" && page.flashId === modelData.id
                    color: flash ? Theme.accentFill : picked ? Theme.focusFill : foundNow ? Theme.accentFill : msgHover.hovered ? Theme.panelLow : found ? Theme.panel : "transparent"
                    border.width: picked ? Theme.focusWidth : 0
                    border.color: page.tint
                    Rectangle { visible: msg.found && !msg.picked; x: 0; y: msg.lineH; width: Theme.px(3); height: Math.max(0, parent.height - msg.lineH); color: msg.foundNow ? page.tint : Theme.lineMuted }
                    // My own messages in a chat with its own color: a thin bar of it down the side.
                    Rectangle {
                        visible: page.tinted && !!modelData.mine && !modelData.system
                        x: Theme.px(Theme.compact ? 6 : 60); y: msg.lineH + msg.gap
                        width: Theme.px(3); height: Math.max(0, parent.height - msg.lineH - msg.gap - Theme.px(2))
                        color: page.tint
                    }

                    Item {
                        visible: msg.lineH > 0
                        width: parent.width; height: msg.lineH
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width; height: 1; color: Theme.danger }
                        Rectangle {
                            anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                            width: newText.implicitWidth + Theme.px(14); height: Math.max(Theme.px(20), newText.implicitHeight + Theme.px(4)); radius: Theme.radius; color: Theme.danger
                            Text { textFormat: Text.PlainText; id: newText; anchors.centerIn: parent; text: "NEW MESSAGES"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold }
                        }
                    }

                    // A note from Huginn itself (a changed security key) sits in the middle.
                    Rectangle {
                        id: note
                        visible: !!modelData.system
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.verticalCenter: parent.verticalCenter
                        // As wide as the words up to 90% of the row; measured apart from noteText, whose width follows this one.
                        width: Math.min(parent.width * 0.9, Math.ceil(noteMetrics.advanceWidth) + Theme.px(30))
                        height: noteText.implicitHeight + Theme.px(16)
                        TextMetrics { id: noteMetrics; font: noteText.font; text: noteText.text }
                        radius: Theme.radius
                        // A call's line is a quiet one, and a click (or A) calls back; other notes (a changed key) stand out.
                        color: modelData.call ? Theme.panel : Theme.dangerFill
                        border.width: 1
                        border.color: modelData.call ? (noteHover.hovered ? page.tint : Theme.lineMuted) : Theme.danger
                        Text {
                            textFormat: Text.PlainText
                            id: noteText
                            anchors.centerIn: parent
                            width: Math.max(0, note.width - Theme.px(28))
                            horizontalAlignment: Text.AlignHCenter
                            wrapMode: Text.Wrap
                            text: modelData.system ? modelData.text + (modelData.call && page.openKey !== "" ? "  ·  Call back" : "") : ""
                            font.italic: true
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                        }
                        HoverHandler { id: noteHover; enabled: !!modelData.call; cursorShape: Qt.PointingHandCursor }
                        TapHandler { enabled: !!modelData.call && page.openKey !== ""; onTapped: page.startCall() }
                    }

                    Avatar {
                        id: face
                        visible: !modelData.system && !msg.grouped && !Theme.compact
                        x: Theme.px(16); y: msg.lineH + msg.gap + Theme.px(2)
                        size: Theme.px(40)
                        key: page.isTour ? "" : modelData.mine ? "me" : page.openKey !== "" ? page.openKey : (modelData.from || "")
                        name: page.senderName(modelData)
                    }
                    Column {
                        id: col
                        visible: !modelData.system
                        // Discord's gutter: 72 px from the edge with pictures, less in Compact.
                        x: Theme.px(Theme.compact ? 16 : 72)
                        width: parent.width - x - Theme.px(48)
                        y: msg.lineH + msg.gap
                        spacing: Theme.px(2)
                        Row {
                            visible: !msg.grouped
                            spacing: Theme.px(8)
                            Text {
                                textFormat: Text.PlainText
                                id: who
                                width: Math.min(implicitWidth, Math.round(col.width * 0.6))
                                elide: Text.ElideRight
                                text: page.senderName(modelData)
                                color: modelData.mine && page.tinted ? page.tint : Theme.avatar(page.senderName(modelData), modelData.mine).fg
                                font.family: Theme.body; font.pixelSize: Theme.ms(16); font.weight: Font.Medium
                            }
                            Text {
                                textFormat: Text.PlainText
                                anchors.baseline: who.baseline
                                text: page.clock(modelData.time) + (modelData.isEdited ? " · (edited)" : "") + page.stateLabel(modelData)
                                color: modelData.failed ? Theme.danger : modelData.state === "read" && modelData.mine ? page.tint : Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(12)
                            }
                        }
                        // A reply: the message it answers, tap to go there.
                        QuoteLine {
                            visible: !!modelData.reName && !modelData.unsent
                            who: modelData.reName || ""
                            what: modelData.reText || ""
                            onJump: page.jumpTo(modelData.re)
                        }
                        Image {
                            visible: !!modelData.photo && !modelData.voice && !modelData.unsent && !modelData.video && !modelData.album
                            width: Math.min(Theme.px(280), col.width); height: Math.round(width * 170 / 280)
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                            sourceSize.width: Math.ceil(width * 2)
                            sourceSize.height: Math.ceil(height * 2)
                            source: visible ? "image://thumb/" + modelData.photo : ""
                        }
                        Row {
                            visible: !!modelData.voice && !modelData.unsent
                            spacing: Theme.px(10)
                            Icon { name: Voice.playing === modelData.id ? "stop" : "play"; color: page.tint }
                            Column {
                                anchors.verticalCenter: parent.verticalCenter
                                Rectangle {
                                    width: Theme.px(180); height: Theme.px(4); color: Theme.line
                                    Rectangle { width: parent.width * (Voice.playing === modelData.id ? Voice.progress : 0); height: parent.height; color: page.tint }
                                }
                                Text { textFormat: Text.PlainText; text: "Voice message · " + Voice.length(modelData.voice || 0); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                            }
                        }
                        Text {
                            visible: (!modelData.photo || !!modelData.unsent) && !(modelData.pollView && !modelData.unsent)
                            width: parent.width; wrapMode: Text.Wrap
                            textFormat: Text.PlainText
                            text: (modelData.unsent ? (modelData.mine ? "Message deleted by you" : "Message deleted by " + modelData.unsent) : (modelData.text || ""))
                                  + (msg.grouped && (modelData.isEdited || page.stateLabel(modelData) !== "") ? "  " : "")
                            font.italic: !!modelData.unsent
                            lineHeight: 1.2
                            color: modelData.unsent ? Theme.textMuted : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.ms(16)
                        }
                        // A grouped message keeps its state (edited, sent, read) small under it.
                        Text {
                            textFormat: Text.PlainText
                            visible: msg.grouped && (!!modelData.isEdited || page.stateLabel(modelData) !== "" || !!modelData.failed)
                            text: (modelData.isEdited ? "(edited)" : "") + page.stateLabel(modelData)
                            color: modelData.failed ? Theme.danger : Theme.textMuted
                            font.family: Theme.body; font.pixelSize: Theme.fs(11)
                        }
                        // Cap m2: an album, a video message, a poll or a link preview. Made only when the message has one.
                        Loader {
                            active: !modelData.unsent && !modelData.system && (!!modelData.album || modelData.video > 0 || !!modelData.pollView || !!modelData.lp)
                            visible: active
                            width: parent.width
                            sourceComponent: MoreParts { host: page; m: modelData; where: page.openKey }
                        }
                        // Reactions, made only when there are some.
                        Loader {
                            active: (modelData.rxList || []).length > 0
                            visible: active
                            width: parent.width
                            sourceComponent: Reactions {
                                list: modelData.rxList || []
                                add: page.openKey !== ""
                                onToggled: (e, mine) => page.reactTo(modelData.id, mine ? "" : e)
                                onMore: page.openReact(modelData.id, modelData.myReaction || "")
                            }
                        }
                        // A thread: how many replies, opening them beside the chat.
                        Text {
                            textFormat: Text.PlainText
                            visible: page.openKey !== "" && (modelData.replies || 0) > 0 && !modelData.unsent
                            text: (modelData.replies === 1 ? "1 reply" : modelData.replies + " replies") + " ›"
                            color: page.tint; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                            Accessible.role: Accessible.Button
                            Accessible.name: "Open the thread"
                            HoverHandler { cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: page.openThread(modelData.id) }
                        }
                    }
                    // React, reply, edit and more over the corner while the pointer is on it or the
                    // controller has it picked (made only then).
                    Loader {
                        id: tools
                        active: page.openKey !== "" && (msgHover.hovered || msg.picked) && !modelData.system && !modelData.unsent
                        visible: active
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        y: msg.lineH - Theme.px(6)
                        z: 2
                        sourceComponent: HoverBar {
                            id: bar
                            canEdit: !!modelData.mine && !modelData.photo && !modelData.failed
                            canThread: true
                            canPin: true
                            pinnedNow: page.pins.indexOf(modelData.id) >= 0
                            onReact: page.openReact(modelData.id, modelData.myReaction || "")
                            onReply: page.startReply(modelData.id)
                            onThread: page.openThread(modelData.id)
                            onPin: page.togglePin(modelData.id)
                            onEdit: page.startEdit(modelData)
                            onMore: { page.zone = "messages"; page.sel = index; Ui.menuAt(bar, bar.width, bar.height, () => page.options()) }
                        }
                    }
                    HoverHandler { id: msgHover }
                    // A click (a mouse or the right stick's pointer) picks it and opens a photo; a
                    // right click opens the options. A finger has no right click, so a tap opens them.
                    TapHandler {
                        acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad | PointerDevice.Stylus
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onTapped: (point, button) => {
                            // The tools over the corner answer their own clicks.
                            if (tools.visible && tools.contains(tools.mapFromItem(msg, point.position.x, point.position.y))) return
                            page.zone = "messages"; page.sel = index
                            if (button === Qt.RightButton) Ui.menuAt(msg, point.position.x, point.position.y, () => page.options())
                            else if (modelData.mine && modelData.failed && page.openKey !== "") Chat.retry(page.openKey, modelData.id)
                            else if (modelData.photo && !modelData.voice && !modelData.unsent && !modelData.video && !modelData.album) page.viewing = modelData.photo
                        }
                    }
                    TapHandler {
                        acceptedDevices: PointerDevice.TouchScreen
                        onTapped: (point, button) => { page.zone = "messages"; page.sel = index; Ui.menuAt(msg, point.position.x, point.position.y, () => page.options()) }
                    }
                }
            }
            Text {
                textFormat: Text.PlainText
                anchors.centerIn: parent
                width: parent.width * 0.7
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                visible: list.count === 0 && page.picked
                text: page.openKey === "" ? (Chat.nearbyOn ? (Chat.nearbyCount === 0 ? "Nobody nearby yet." : "Say hello.") : "Nearby room is off.")
                                          : "No messages yet."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
            // Nothing picked yet: how to start, instead of landing in the public Nearby room.
            Column {
                visible: !page.picked && !page.locked
                anchors.centerIn: parent
                width: Math.min(parent.width - Theme.px(40), Theme.px(420))
                spacing: Theme.px(14)
                Image {
                    anchors.horizontalCenter: parent.horizontalCenter
                    source: "assets/raven-gold.png"
                    width: Theme.px(72); height: width
                    sourceSize.width: 256; sourceSize.height: 256
                    opacity: 0.8
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap
                    text: "PICK A CHAT"
                    color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap
                    text: "Or add someone with +"
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
            }

            // A photo full size.
            Rectangle {
                anchors.fill: parent
                visible: page.viewing !== ""
                color: "black"
                id: fullPhoto
                property bool asked: false
                // Cap a1: a GIF moves full size too (held still with Less motion or Battery saver until played); "" for a photo.
                readonly property string gif: page.viewing !== "" && !page.isTour ? Chat.gifUrl(page.viewing) : ""
                onVisibleChanged: asked = false
                Image { anchors.fill: parent; anchors.margins: Theme.px(10); fillMode: Image.PreserveAspectFit; visible: fullPhoto.gif === ""; source: page.viewing !== "" && fullPhoto.gif === "" ? Chat.photoUrl(page.viewing) : "" }
                AnimatedImage {
                    anchors.fill: parent; anchors.margins: Theme.px(10); fillMode: Image.PreserveAspectFit; cache: false
                    visible: fullPhoto.gif !== ""
                    source: fullPhoto.gif
                    playing: visible && (Theme.motion || fullPhoto.asked) && Qt.application.state === Qt.ApplicationActive
                }
                TapHandler { onTapped: page.viewing = "" }
                Rectangle {
                    visible: fullPhoto.gif !== "" && !Theme.motion
                    anchors.centerIn: parent
                    width: Theme.px(64); height: width; radius: width / 2
                    color: Qt.rgba(0, 0, 0, 0.55)
                    Icon { anchors.centerIn: parent; name: fullPhoto.asked ? "stop" : "play"; color: "white"; size: Theme.px(30) }
                    Accessible.role: Accessible.Button
                    Accessible.name: fullPhoto.asked ? "Stop the GIF" : "Play the GIF"
                    MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: fullPhoto.asked = !fullPhoto.asked }
                }
            }

            // Recording a voice message.
            Rectangle {
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.margins: Theme.px(12)
                height: Theme.px(52)
                visible: Voice.recording && !compose.micHeld
                radius: Theme.radius; color: Theme.dangerFill; border.color: Theme.danger; border.width: 1
                Row {
                    anchors.verticalCenter: parent.verticalCenter
                    x: Theme.px(16); spacing: Theme.px(12)
                    Rectangle { width: Theme.px(12); height: width; radius: width / 2; color: Theme.danger; anchors.verticalCenter: parent.verticalCenter }
                    Text { textFormat: Text.PlainText; width: Math.min(implicitWidth, parent.parent.width - Theme.px(56)); elide: Text.ElideRight; text: Voice.length(Voice.elapsed) + (page.pad ? "   Let go: send · B: cancel" : "   Recording"); color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                }
            }

            // "Raven is typing" under the last message (cap m1).
            Text {
                textFormat: Text.PlainText
                visible: page.theyType
                anchors.left: parent.left; anchors.leftMargin: Theme.px(22)
                anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(2)
                width: Math.min(implicitWidth, parent.width - Theme.px(44))
                elide: Text.ElideRight
                text: page.openName + " is typing…"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.italic: true
            }

            // The "New messages" line is out of view: a button to go there.
            Rectangle {
                readonly property bool lineInView: {
                    list.contentY; list.height
                    const it = page.newFrom >= 0 ? list.itemAtIndex(page.newFrom) : null
                    return !!it && it.y + it.height > list.contentY && it.y < list.contentY + list.height
                }
                visible: page.newFrom >= 0 && !lineInView && list.count > 0
                anchors.horizontalCenter: parent.horizontalCenter
                y: Theme.px(10)
                width: jumpText.implicitWidth + Theme.px(28); height: Theme.px(28); radius: Theme.radius
                color: Theme.danger
                z: 3
                Accessible.role: Accessible.Button
                Accessible.name: "Jump to new messages"
                Text { textFormat: Text.PlainText; id: jumpText; anchors.centerIn: parent; text: "New messages · jump"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold }
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: list.positionViewAtIndex(page.newFrom, ListView.Beginning) }
            }

            // Drop pictures (or other files) on the chat to send them.
            DropArea {
                anchors.fill: parent
                enabled: page.openKey !== "" && !page.locked
                onDropped: drop => { const u = Ui.dropUrls(drop); if (u.length) page.dropFiles(u); drop.accept() }
                Rectangle {
                    anchors.fill: parent
                    visible: parent.containsDrag
                    color: Qt.rgba(0, 0, 0, 0.55)
                    border.color: Theme.accent; border.width: 2; radius: Theme.radius
                    Text { textFormat: Text.PlainText; anchors.centerIn: parent; text: "Drop to send to " + page.openName; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(20) }
                }
            }

            // Reacting: the quick five and any emoji.
            EmojiPicker {
                id: reactPicker
                quick: true
                anchors.centerIn: parent
                onPicked: e => { page.reactTo(page.reactFor, e === current ? "" : e); visible = false }
            }
        }

        // The message box, docked at the bottom and typed into directly (A puts the controller in it).
        Composer {
            id: compose
            visible: !lock.visible && page.picked
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(16); anchors.rightMargin: Theme.px(16); anchors.bottomMargin: Theme.px(24)
            height: implicitHeight
            placeholder: page.openKey === "" ? "Message everyone nearby" : "Message " + page.openName
            target: page.openKey
            voiceTo: page.tour ? "" : page.openKey
            micShown: page.openKey !== ""
            accent: page.tint
            editing: page.editId !== ""
            bannerTitle: page.editId !== "" ? "Editing your message" : page.replyMsg ? "Replying to " + (page.replyMsg.mine ? "yourself" : page.openName) : ""
            bannerText: page.editId !== "" ? "Enter saves, Esc cancels" : page.replyMsg ? (page.replyMsg.photo ? (page.replyMsg.voice ? "Voice message" : "Photo") : page.replyMsg.text || "") : ""
            onSend: t => page.sendText(t)
            onHasFocusChanged: if (hasFocus) { page.zone = "messages"; page.sel = -1 }
            onTyping: on => { if (page.openKey !== "" && !page.tour) Chat.typingTo(page.openKey, on) }
            onEditLast: page.editLast()
            onEscaped: page.escapeKey()
            onCancelBanner: { page.replyId = ""; if (page.editId !== "") { page.editId = ""; compose.text = "" } }
            onAttach: page.pickPicture()
        }
    }

    // ============================================================= side panel: a thread or the pins
    property alias threadCompose: threadBox
    // The chat's own message box (the screenshot tour's long draft).
    property alias mainCompose: compose
    Rectangle {
        id: sidePanel
        visible: !lock.visible && page.openKey !== "" && page.side !== ""
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Math.min(Theme.px(360), page.width - middle.width - Theme.px(48))
        color: Theme.column
        // Over the conversation on a narrow screen: an edge so it reads as on top.
        Rectangle { visible: !page.sideDocked; width: 1; height: parent.height; color: Theme.lineStrong }
        PageHeader {
            id: sideHead
            width: parent.width
            title: page.side === "thread" ? "Thread" : "Pinned"
            subtitle: page.openName
            HButton { anchors.verticalCenter: parent.verticalCenter; text: "Close"; kind: "plain"; implicitHeight: Theme.px(30); fontSize: 13; onClicked: { page.side = ""; page.threadRoot = "" } }
        }
        ListView {
            id: sideList
            anchors.top: sideHead.bottom; anchors.topMargin: Theme.px(10)
            anchors.bottom: threadBox.visible ? threadBox.top : parent.bottom; anchors.bottomMargin: Theme.px(10)
            x: Theme.px(8); width: parent.width - Theme.px(16)
            spacing: Theme.px(6)
            clip: true
            model: page.side === "thread" ? page.threadMsgs : page.pinnedMsgs
            onCountChanged: if (page.side === "thread") positionViewAtEnd()
            delegate: MessageRow {
                host: page
                accent: page.tint
                width: sideList.width
                m: modelData
                at: index
                who: page.senderName(modelData)
                inThread: true
                picked: page.active && page.zone === "side" && page.sideSel === index
                hoverTools: true
                onChosen: { page.zone = "side"; page.sideSel = index }
                onOptions: page.sideOptions(index)
            }
        }
        Text {
            textFormat: Text.PlainText
            visible: page.side === "pins" && page.pinnedMsgs.length === 0
            anchors.top: sideHead.bottom; anchors.topMargin: Theme.px(16)
            x: Theme.px(16); width: parent.width - Theme.px(32)
            wrapMode: Text.WordWrap
            text: "Nothing pinned."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        Composer {
            id: threadBox
            visible: page.side === "thread"
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.margins: Theme.px(12)
            height: implicitHeight
            placeholder: "Reply in the thread"
            target: page.openKey
            accent: page.tint
            onSend: t => page.sendText(t, page.threadRoot)
            onHasFocusChanged: if (hasFocus) { page.zone = "side"; page.sideSel = -1 }
            onTyping: on => { if (page.openKey !== "" && !page.tour) Chat.typingTo(page.openKey, on) }
            onEscaped: { page.side = ""; page.threadRoot = ""; compose.focusField() }
            onAttach: page.pickPicture()
        }
    }

    // ============================================================= member column (groups)
    Rectangle {
        id: members
        visible: Theme.desktop && page.isGroup
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(210)
        color: Theme.column
    }

    // ============================================================= my code
    Rectangle {
        anchors.fill: parent
        visible: page.showCode && !lock.visible
        color: Qt.rgba(0.03, 0.035, 0.04, 0.82)
        TapHandler { onTapped: page.showCode = false }
        Rectangle {
            anchors.centerIn: parent
            width: Math.min(parent.width - Theme.px(32), Theme.px(440))
            height: Math.min(parent.height - Theme.px(32), codeCol.implicitHeight + Theme.px(40))
            color: Theme.panel; radius: Theme.radius; border.color: Theme.line; border.width: 1
            TapHandler { }   // a click inside doesn't close it
            Column {
                id: codeCol
                x: Theme.px(20); y: Theme.px(20); width: parent.width - Theme.px(40)
                spacing: Theme.px(10)
                Text { textFormat: Text.PlainText; text: "MY CODE"; color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2) }
                Text {
                    textFormat: Text.PlainText
                    property real now: Date.now()
                    Timer { interval: 10000; repeat: true; running: page.showCode; onTriggered: parent.now = Date.now() }
                    width: parent.width; wrapMode: Text.WordWrap
                    text: Chat.linkExpires > now ? "Scan to add me. Works for 1 hour." : "Expired. Make a new code."
                    color: Chat.linkExpires > now ? Theme.textMuted : Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    // The same light paper the code is drawn on, in every look, so it always scans.
                    width: Math.min(parent.width, Theme.px(260), Math.max(Theme.px(120), Math.round(page.height * 0.4))); height: width; color: "#E4E1DA"
                    Image { anchors.fill: parent; anchors.margins: Theme.px(12); smooth: false; source: page.showCode ? "image://qr/" + encodeURIComponent(Chat.myLink) : "" }
                }
                Row {
                    anchors.right: parent.right
                    spacing: Theme.px(8)
                    HButton { text: "Close"; kind: "plain"; onClicked: page.showCode = false }
                    HButton { text: "New code"; onClicked: Chat.newLink() }
                    HButton { text: "Copy link"; kind: "primary"; onClicked: { Chat.copyLink(); Ui.say("Link copied.") } }
                }
            }
        }
    }

    // ============================================================= actions
    function write() {
        if (compose.visible) { zone = "messages"; sel = -1; compose.writeHere(); return }
        if (openKey === "") {
            if (!Chat.nearbyOn) { Ui.say("Nearby room is off: Settings › Privacy and lock.", true); return }
            Ui.askMessage("Message everyone nearby", "You're " + Ui.myName, "", "Send", t => Chat.sendNearby(t))
        } else {
            const k = openKey, re = replyId
            Ui.askMessage(re !== "" && replyMsg ? "Reply to " + (replyMsg.mine ? "yourself" : openName) : "Message " + openName, re !== "" && replyMsg ? (replyMsg.text || "Photo") : "", "", "Send",
                   t => { Chat.sendPrivate(k, t, re); page.replyId = "" })
        }
    }
    // The + beside the title: add someone, join a group, show my code, or copy a one-time link.
    function addMenu() {
        if (locked) return
        // While a phone is linked these are this PC's own account (its own code and contacts), not the phone's.
        const own = !!Linked.phone.key && !Linked.phone.gone
        Ui.pickFrom("Add", [{ header: own ? "This PC's own account: same name and picture as you, but separate from your phone, so people would see a second " + Ui.myName + ". Add people on your phone." : "", items: [{ name: "Add someone", icon: "plus", url: "add" },
                                                   { name: "Join a group", icon: "people", url: "join" },
                                                   { name: "My code", icon: "qr", url: "code" },
                                                   { name: "One-time link", icon: "attach", url: "once" }] }], a => {
            if (a === "add") addSomeone()
            else if (a === "join") joinGroup()
            else if (a === "code") { Chat.newLink(); showCode = true }
            else if (a === "once") PeopleActions.inviteLink()
        })
    }
    // The list's three dots: New folder and Trash; Report a bug in Labs mode; Labs reports on the Huginn with the Labs key.
    function listMenu() {
        if (locked) return
        const n = Chat.trash.length
        const items = [{ name: "New folder", icon: "folder", url: "folder" },
                       { name: "Trash", icon: "close", detail: n ? String(n) : "", url: "trash" }]
        if (Settings.labs) items.push({ name: "Report a bug", icon: "info", url: "report" })
        if (Labs.inbox) items.push({ name: "Labs reports", icon: "file", detail: Labs.reports.length ? String(Labs.reports.length) : "", url: "labs" })
        Ui.pickFrom("Chats", [{ header: "", items: items }], a => {
            if (a === "folder") newFolder("")
            else if (a === "trash") openTrash()
            else if (a === "report") Ui.main.openReport()
            else if (a === "labs") Ui.main.openLabsReports()
        })
    }
    // Join a group: the same paste box, for a group's invite link or code (a person's code works here too).
    // A copied invite fills the box in (no hunting for Paste on a controller).
    function joinGroup() {
        const copied = Chat.clipboardInvite()
        Ui.ask("Join a group", ownNote("To join as you, open the invite on your phone.") || (copied !== "" ? "The copied invite is filled in" : "Paste the invite link or code"), copied, "Join", t => takePaste(t))
    }
    function addSomeone() { Ui.ask("Add someone or join a group", ownNote("To add people as you, use your phone.") || "Paste a code or invite link", "", "Add", t => takePaste(t)) }
    // While a phone is linked, the paste box works for this PC's own account, which looks just like the phone's.
    function ownNote(tail) {
        if (!Linked.phone.key || Linked.phone.gone) return ""
        return "This adds to this PC's own account: same name and picture as you, but separate from your phone, so people see a second "
            + Ui.myName + ". " + tail
    }
    function takePaste(t) {
            // A group's code or invite link: its name and size, then Join or Not now.
            if (PeopleActions.joinGroup(t, null)) return
            const c = Chat.parseCode(t)
            if (!c.key) { Ui.say("That code didn't work.", true); return }
            if (c.mine) { Ui.say("That's your own code.", true); return }
            if (c.expired) { Ui.say("That code expired. Ask for a new one.", true); return }
            const r = Chat.replaces(t)
            const added = () => { Ui.say(c.name + " added."); openKey = c.key; picked = true; zone = "messages" }
            if (!r.key) { Chat.addContact(t); PeopleActions.added(t, c.key); added(); return }
            // A contact with this inbox or name already has another security key: never replaced quietly.
            if (r.sameInbox) {
                Ui.sure(r.name + "'s key changed?", "Check with them in person first.", "Use the new key",
                        () => { Chat.addContact(t, r.key); openKey = c.key; picked = true; zone = "messages" }, false, true)
                return
            }
            Ui.pickFrom("Is this " + r.name + "?", [{ header: "", items: [
                { name: "Someone new", url: "new" },
                { name: "Same person, new key", url: "same" }] }],
                a => {
                    if (a === "same") { Chat.addContact(t, r.key); openKey = c.key; picked = true; zone = "messages" }
                    else { Chat.addContact(t); PeopleActions.added(t, c.key); added() }
                })
    }
    // ------------------------------------------------------------- right-click menus (Ui.menuAt)
    // A row's right-click menu, at p in item: a person's (PeopleActions.personMenu), a folder's, or Open and the list's own.
    function rowContextAt(item, p, e) { Ui.menuAt(item, p.x, p.y, () => page.rowContext(e)) }
    function rowContext(e) {
        if (!e || locked) return
        if (e.kind === "chat" || e.kind === "near") {
            const k = e.key, at = entries.indexOf(e)
            PeopleActions.personMenu(k, e.name, { tour: tour, near: e.kind === "near", hasChat: !!e.hasChat, alt: e.alt || "",
                                                  section: e.section, keys: e.kind === "chat" ? sectionKeys(e.section) : [], listRow: true,
                                                  removed: () => page.closeChatWith(k),
                                                  moved: () => page.focusEntry(x => x.kind === "chat" && x.key === k),
                                                  open: () => { if (at >= 0) page.row = at; page.choose() } })
            return
        }
        if (e.kind === "folder") { folderOptions(e); return }
        // Today's verse: open, or hide it for today.
        if (e.kind === "verse") {
            Ui.pickFrom("Today's verse", [{ header: "", items: [{ name: "Open", icon: "text", url: "open" },
                                                                { name: "Hide for today", icon: "close", url: "hide" }] }], a => {
                if (a === "open") page.openVerse(null)
                else if (a === "hide") page.hideVerse()
            })
            return
        }
        // The "New from" bar: open, or hide it until the next new message.
        if (e.kind === "newfrom") {
            Ui.pickFrom("New messages", [{ header: "", items: [{ name: unreadChats.length === 1 ? "Open" : "Go to them", icon: "thread", url: "open" },
                                                               { name: "Hide until the next message", icon: "close", url: "hide" }] }], a => {
                if (a === "open") page.openNewFrom()
                else if (a === "hide") page.hideNewFrom()
            })
            return
        }
        // A chat from the phone: mute it (on the phone too). The chat isn't opened, so nothing in it is marked read.
        if (e.kind === "mirror" && e.peer) {
            const c = Linked.mirror.find(x => x.peer === e.peer)
            if (c) mirror.chatMenu(c)
            return
        }
        spaceMenu(true)
    }
    // The empty space under the rows (a row there has its own menu).
    function spaceMenuAt(area, p) {
        if (locked || listPane.indexAt(p.x + area.x, p.y + area.y) >= 0) return
        Ui.menuAt(area, p.x, p.y, () => page.spaceMenu(false))
    }
    // New chat, New group and New folder (withOpen: Open first, for a row with no menu of its own).
    function spaceMenu(withOpen) {
        if (locked) return
        const items = withOpen ? [{ name: "Open", icon: "thread", url: "open" }] : []
        items.push({ name: "New chat", icon: "plus", url: "chat" }, { name: "New group", icon: "people", url: "group" },
                   { name: "New folder", icon: "folder", url: "folder" })
        Ui.pickFrom("Chats", [{ header: "", items: items }], a => {
            if (tour) return
            if (a === "open") choose()
            else if (a === "chat") addSomeone()
            else if (a === "group") { if (Ui.main) Ui.main.newGroup() }
            else if (a === "folder") newFolder("")
        })
    }
    // The Menu key and Shift+F10 (Main.menuKey): the right-click menu for the focus, at menuAnchor().
    function contextMenu() { if (more.sharedOpen) more.contextMenu(); else if (zone === "list") rowContext(entries[row]); else handleButton("x") }
    function menuAnchor() { return more.sharedOpen ? more.anchorItem() : zone === "list" ? listPane.itemAtIndex(row) : zone === "messages" && sel >= 0 ? list.itemAtIndex(sel) : null }
    // A person's one menu (PeopleActions.contactMenu), from their row (moving it is in the row's right-click menu).
    function rowMenu(e) {
        if (e && e.kind === "near") {
            const n = e.key
            PeopleActions.nearbyMenu(n, e.name, { hasChat: !!e.hasChat, alt: e.alt || "", removed: () => page.closeChatWith(n) })
            return
        }
        if (!e || e.kind !== "chat") { listOptions(); return }
        const k = e.key
        PeopleActions.contactMenu(k, e.name, { section: e.section, removed: () => page.closeChatWith(k) })
    }
    // The same menu for the open chat (its header's three dots, or LT with no message picked).
    function chatMenu() {
        if (openKey === "") return
        const k = openKey, e = entries.find(x => x.kind === "chat" && x.key === k)
        PeopleActions.contactMenu(k, openName, { tour: tour, inChat: true, section: e ? e.section : "", removed: () => page.closeChatWith(k) })
    }
    function closeChatWith(k) { if (openKey === k) { openKey = ""; picked = false; zone = "list" } }
    function checkCode(k) { PeopleActions.checkCode(k) }
    function startCall() {
        const w = Calls.call(openKey)
        if (w !== "") Ui.say(w, true)
    }
    function startVideo() {
        const w = Calls.callVideo(openKey)
        if (w !== "") Ui.say(w, true)
    }
    // The header's Call button: a small popup under it with Voice call and, when they can take one, Video call
    // (a Kids' Device has no video, so only Voice call).
    function callMenu() {
        if (openKey === "" || locked) return
        const items = [{ name: "Voice call", icon: "call", url: "voice" }]
        if (Calls.canVideo(openKey)) items.push({ name: "Video call", icon: "video", url: "video" })
        Ui.menuAt(callBtn, 0, callBtn.height, () => Ui.pickFrom("", [{ header: "", items: items }], a => {
            if (a === "video") page.startVideo(); else page.startCall()
        }))
    }
    // The header's buttons with a controller: Left and Right pick, A presses, Down or B goes back to the messages.
    function headButton(b) {
        const n = headButtons.length
        if (!n) { zone = "messages"; return true }
        switch (b) {
        case "left": headSel = Math.max(0, headSel - 1); return true
        case "right": headSel = Math.min(n - 1, headSel + 1); return true
        case "a": {
            const w = headButtons[Math.min(headSel, n - 1)]
            zone = "messages"
            if (w === "call") callMenu(); else if (w === "find") openFind(); else chatMenu()
            return true
        }
        case "down": case "b": zone = "messages"; return true
        }
        return true
    }
    // ------------------------------------------------------------- sections: folders, order, trash
    function folderName(id) {
        const f = Chat.folders.find(x => x.id === id)
        return f ? f.name : ""
    }
    // Who is shown in a section, top to bottom.
    function sectionKeys(section) {
        return entries.filter(e => e.kind === "chat" && e.section === section).map(e => e.key)
    }
    // Back on an entry after the list changed under it.
    function focusEntry(test) {
        Qt.callLater(() => { const i = page.entries.findIndex(test); if (i >= 0) page.row = i })
    }
    function newFolder(key, from) {
        Ui.ask("New folder", "Only you see it", "", "Make it", t => {
            const f = Chat.addFolder(t)
            if (f === "") { Ui.say(Chat.saveFailed() ? "Couldn't save. Try again." : Chat.folders.length >= 30 ? "30 folders at most." : "Give the folder a name.", true); return }
            if (key) Chat.placeIn(key, from || "", f, [key])
            Ui.say("Made " + t + ".")
        }, false, true)
    }
    // X (or a right click) on a row of the list: a person's menu, a folder's, or the list's own.
    function listOptions() {
        const e = entries[row]
        if (!e) return
        if (e.kind === "chat" || e.kind === "near") { rowMenu(e); return }
        if (e.kind === "folder") { folderOptions(e); return }
        listMenu()
    }
    function folderOptions(e) {
        const id = e.id, ids = Chat.folders.map(f => f.id), at = ids.indexOf(id)
        const items = [{ name: e.collapsed ? "Expand" : "Collapse", url: "fold" }]
        if (at > 0) items.push({ name: "Move up", url: "up" })
        if (at >= 0 && at < ids.length - 1) items.push({ name: "Move down", url: "down" })
        // Mute all: everyone in it, until turned back on (Unmute all when they all are).
        const keys = tour ? [] : ((Chat.folders.find(f => f.id === id) || {}).keys || [])
        const allMuted = keys.length > 0 && keys.every(k => Chat.isMuted(k))
        items.push({ name: "Rename", icon: "edit", url: "rename" },
                   { name: allMuted ? "Unmute all" : "Mute all", icon: allMuted ? "bell" : "belloff", url: "muteall" },
                   { name: "Delete folder", url: "delete", danger: true })
        Ui.pickFrom(e.name, [{ header: "", items: items }], a => {
            if (a === "muteall") {
                if (!keys.length) { Ui.say("No one in " + e.name + " yet."); return }
                keys.forEach(k => Chat.muteChat(k, allMuted ? 0 : 8640000000000000))   // the huge one: until unmuted
                Ui.say(allMuted ? e.name + " isn't muted." : e.name + " is muted.")
            }
            else if (a === "fold") Chat.setCollapsed(id, !e.collapsed)
            else if (a === "up" || a === "down") { Chat.moveFolder(id, a === "up" ? -1 : 1); focusEntry(x => x.kind === "folder" && x.id === id) }
            else if (a === "rename") Ui.ask("Rename " + e.name, "Only you see it", e.name, "Save", t => Chat.renameFolder(id, t), false, true)
            else if (a === "delete") Ui.sure("Delete " + e.name + "?", "The people in it stay.", "Delete", () => Chat.deleteFolder(id), false, true)
        })
    }
    // First open, after the lock is set: what people see as your name (prefilled; Skip keeps it), then Link your phone.
    function askFirstName() {
        const next = () => { if (!Linked.phone.key && Linked.companions.length === 0 && Ui.main) Ui.main.showLinkPhone(true) }
        Ui.ask("What should people call you?", "A nickname is fine. Change it any time in Settings › Profile.", Chat.username, "Continue",
               t => { Chat.username = t; Qt.callLater(next) }, false, false)
        if (Ui.prompt) { Ui.prompt.cancelText = "Skip"; Ui.prompt.cancelCallback = () => Qt.callLater(next) }
    }
    // The chats put away with Archive: open one, or bring it back to the list.
    function openArchived() {
        const list = Chat.chats.filter(c => c.archived)
        if (!list.length) return
        Ui.pickFrom("Archived", [{ header: "A new message brings a chat back.", items: list.map(c => ({ name: c.name, detail: c.unread ? "New" : "", url: c.key })) }], k => {
            const c = list.find(x => x.key === k)
            Ui.pickFrom(c ? c.name : "Chat", [{ header: "", items: [{ name: "Open", icon: "thread", url: "open" }, { name: "Unarchive", url: "back" }] }], a => {
                if (a === "open") { openKey = k; picked = true; zone = "messages" }
                else if (a === "back") { Chat.archiveChat(k, false); Ui.say((c ? c.name : "The chat") + " is back in the list.") }
            }, () => page.openArchived())
        })
    }
    function openTrash() {
        const t = Chat.trash
        if (!t.length) { Ui.say("The Trash is empty."); return }
        const items = t.map(p => ({ name: p.name, detail: p.daysLeft + (p.daysLeft === 1 ? " day left" : " days left"), url: p.key }))
        items.push({ name: "Empty trash", url: "!empty", danger: true })
        Ui.pickFrom("Trash", [{ header: "Restore within 30 days.", items: items }], k => {
            if (k === "!empty") {
                Ui.sure("Empty trash?", "Can't be undone.", "Hold to empty", () => { Chat.emptyTrash(); Ui.say("Trash emptied.") }, true, true)
                return
            }
            const p = t.find(x => x.key === k)
            if (!p) return
            Ui.pickFrom(p.name, [{ header: "", items: [
                { name: "Restore", url: "restore" },
                { name: "Delete for good", url: "delete", danger: true }] }], a => {
                if (a === "restore") { Chat.restore(k); Ui.say(p.name + " is back.") }
                else if (a === "delete") Ui.sure("Delete " + p.name + " for good?", "Can't be undone.", "Delete for good",
                                                 () => { Chat.deleteForGood(k); Ui.say(p.name + " deleted for good.") }, true, true)
            }, () => page.openTrash())
        })
    }
    // ------------------------------------------------------------- dragging people with the mouse
    function startDrag(e) {
        dragKey = e.key
        dragFrom = e.section
        dragName = e.name
        dropLine = -1
        dropAt = -1
    }
    // y in the list's own coordinates. Lands before or after a person (by which half of them the
    // pointer is on), at the top of a folder when over its header, or at the top of the list.
    function dragTo(y) {
        dragY = y
        dropLine = -1; dropAt = -1; dropOnHeader = false
        const cy = y + listPane.contentY
        const i = listPane.indexAt(listPane.width / 2, cy)
        const e = entries[i]
        const item = i >= 0 ? listPane.itemAtIndex(i) : null
        if (!e || !item) return
        if (e.kind === "folder") { dropSection = e.id; dropAt = i + 1; dropOnHeader = true; return }
        if (e.kind === "chat") {
            const after = cy > item.y + item.labelHeight + (item.height - item.labelHeight) / 2
            dropSection = e.section
            dropAt = after ? i + 1 : i
            dropLine = after ? item.y + item.height + listPane.spacing / 2 : item.y + item.labelHeight - listPane.spacing / 2
            return
        }
        if (e.kind === "ginvite" || e.kind === "request" || e.kind === "intro" || e.kind === "mirror") {
            const first = entries.findIndex(x => x.kind === "chat" && x.section === "")
            const at = first >= 0 ? first : i + 1
            const it = listPane.itemAtIndex(first >= 0 ? first : i)
            dropSection = ""
            dropAt = at
            if (it) dropLine = first >= 0 ? it.y - listPane.spacing / 2 : it.y + it.height + listPane.spacing / 2
        }
    }
    function dropHere() {
        const k = dragKey, from = dragFrom, to = dropSection, at = dropAt
        cancelDrag()
        if (k === "" || at < 0) return
        // The target's people as shown, with the dragged one taken out and put where it was let go.
        const before = [], after = []
        entries.forEach((x, j) => { if (x.kind === "chat" && x.section === to && x.key !== k) (j < at ? before : after).push(x.key) })
        Chat.placeIn(k, from, to, before.concat([k]).concat(after))
        focusEntry(x => x.kind === "chat" && x.key === k && x.section === to)
    }
    // A person let go over a group in the rail: invited to it.
    function inviteTo(g, k, name) {
        const info = Chat.groups.find(x => x.id === g)
        const r = Chat.inviteToGroup(g, k)
        Ui.say(r === "" ? "Invited " + name + " to " + (info ? info.name : "the group") + "." : r, r !== "")
    }
    function cancelDrag() {
        dragKey = ""
        dropLine = -1
        dropAt = -1
        dropOnHeader = false
    }
    function choose() {
        const e = entries[row]
        if (!e) return
        switch (e.kind) {
        case "newfrom": openNewFrom(); return
        case "verse": openVerse(null); return
        case "nearby": openKey = ""; picked = true; zone = "messages"; return
        case "folder": Chat.setCollapsed(e.id, !e.collapsed); return
        case "ginvite":
            if (e.refused) {
                Ui.sure("Not let in to " + e.name, e.refusedText + " To try again, ask " + (e.fromName || "them") + " for a new invite.",
                        "Remove", () => Chat.declineGroupInvite(e.g), false, false)
                return
            }
            if (e.waiting) {
                Ui.sure("Waiting for " + e.fromName, "You're in " + e.name + " as soon as " + e.fromName + " has the app open. If it takes a while, ask them to open it.",
                        "Stop waiting", () => Chat.declineGroupInvite(e.g), false, false)
                return
            }
            Ui.sure(e.fromName + " invited you to " + e.name, "Everyone there sees your name.", "Join",
                    () => { const r = Chat.acceptGroupInvite(e.g); if (r === "") Ui.say("Joining " + e.name + "…"); else Ui.say(r, true) }, false, false,
                    () => Chat.declineGroupInvite(e.g))
            Ui.confirm.cancelText = "Decline"
            return
        case "request":
            if (e.replaces) {
                Ui.sure(e.name + " says they're " + e.replacesName, "New key. Check with them first.", "Accept",
                        () => { Chat.acceptRequest(e.key); openKey = e.key; picked = true; zone = "messages" }, false, true, () => Chat.declineRequest(e.key))
                Ui.confirm.cancelText = "Decline"
                return
            }
            Ui.sure(e.name + " wants to chat", "Only accept if you know them.", "Accept",
                    () => { Chat.acceptRequest(e.key); openKey = e.key; picked = true; zone = "messages" }, false, false, () => Chat.declineRequest(e.key))
            Ui.confirm.cancelText = "Decline"   // backing out means no, so say so
            return
        case "near": if (!e.hasChat) Chat.meet(e.key, e.alt); openKey = e.key; picked = true; zone = "messages"; return
        case "intro": PeopleActions.answer(e); return
        case "mirror": mirror.open(e.peer || ""); return
        case "archived": openArchived(); return
        default: openKey = e.key; picked = true; zone = "messages"
        }
    }
    // Pictures (several at once as an album), a video message, a video file or a poll (MoreLayer).
    function pickPicture() { more.attach() }
    // A controller: the quick reactions in a list (the emoji panel needs a pointer).
    function pickReaction(m) {
        const k = openKey, id = m.id
        const items = Chat.quickReactions().map(e => ({ name: e, check: e === m.myReaction, url: e }))
        if (m.myReaction && Chat.quickReactions().indexOf(m.myReaction) < 0) items.push({ name: m.myReaction, check: true, url: m.myReaction })
        items.push({ name: "More emoji", url: "+" })
        Ui.pickFrom("React", [{ header: "", items: items }], e => {
            if (e === "+") { openReact(id, m.myReaction || ""); return }
            const r = Chat.react(k, id, e === m.myReaction ? "" : e)
            if (r !== "") Ui.say(r, true)
        })
    }
    function options() {
        const m = messages[sel]
        if (m && m.system) {
            const k = openKey
            const items = Chat.isVerified(k) ? [] : [{ name: "Check safety code", icon: "check", url: "code" }]
            items.push({ name: "Delete for me", url: "delete", danger: true })
            Ui.pickFrom("Security key", [{ header: "", items: items }], a => { if (a === "code") checkCode(k); else if (a === "delete") Chat.deleteMessage(k, m.id) })
            return
        }
        if (!m || m.unsent && !m.mine) { if (m && openKey !== "") Ui.sure("Delete this message?", "Only on this device.", "Delete", () => Chat.deleteMessage(openKey, m.id), false, true); return }
        const items = []
        // A group invite in the message: Join, first (its name and size, then Join or Not now).
        const invite = m.text && !m.unsent && !m.mine && !tour ? Chat.parseGroupCode(m.text) : ({})
        if (invite.name && !invite.mine && !invite.member) items.push({ name: "Join " + invite.name, icon: "people", url: "joingroup" })
        if (openKey !== "" && !m.unsent && !m.failed) {
            items.push({ name: "React", icon: "smile", detail: m.myReaction ? "Yours: " + m.myReaction : "", url: "react", sub: () => pickReaction(m) })
            items.push({ name: "Reply", icon: "reply", url: "reply" })
            items.push({ name: "Open thread", icon: "thread", detail: (m.replies || 0) ? m.replies + (m.replies === 1 ? " reply" : " replies") : "", url: "thread" })
        }
        if (openKey !== "" && !m.unsent && !tour) items.push({ name: pins.indexOf(m.id) >= 0 ? "Unpin" : "Pin", icon: "pin", url: "pin" })
        if (openKey !== "" && m.mine && !m.unsent && !m.failed && !m.photo) items.push({ name: "Edit", icon: "edit", url: "edit" })
        if (m.text && !m.unsent) items.push({ name: "Copy text", url: "copy" })
        // Read aloud (src/readaloud.h): the plain text, in the voice picked in Settings › Look.
        if (m.text && !m.unsent && ReadAloud.available) items.push({ name: ReadAloud.speaking ? "Stop reading" : "Read aloud", icon: "speaker", url: "readaloud" })
        // Right-click extras: the link, Forward (its text) and the saved file's folder.
        const link = m.unsent ? "" : PeopleActions.firstLink(m.text)
        if (link !== "") items.push({ name: "Copy link", icon: "attach", url: "link" })
        if (m.text && !m.unsent && !m.failed && !tour) items.push({ name: "Forward", icon: "forward", url: "forward", sub: () => PeopleActions.forwardMenu(m.text) })
        if (m.saved && !m.unsent) items.push({ name: "Open in folder", icon: "folder", url: "folder" })
        if (m.file && !m.unsent && !m.mine) items.push({ name: "Save to Downloads", icon: "file", url: "savefile" })
        else if (m.photo && !m.voice && !m.video && !m.file && !m.unsent) items.push({ name: "View full size", icon: "image", url: "view" }, { name: "Save to Pictures", icon: "save", url: "save" })
        else if (m.photo && (m.voice || m.video) && !m.file && !m.unsent) items.push({ name: "Save", icon: "save", detail: "To " + (Transfer.saveName || "Downloads") + " › Huginn", url: "savemedia" })
        // A photo, video or voice message: sent again as a new message.
        if (m.photo && !m.file && !m.unsent && !m.failed && !tour) items.push({ name: "Forward", icon: "forward", url: "forwardmedia", sub: () => PeopleActions.forwardMedia(m, m.voice ? "voice message" : m.video ? "video" : m.gif ? "GIF" : "photo") })
        if (m.voice && !m.unsent) items.push({ name: Voice.playing === m.id ? "Stop" : "Play", icon: "play", url: "play" })
        if (m.mine && m.failed) items.push({ name: "Try again", url: "retry" })
        if (openKey === "" && !m.mine) items.push({ name: "Mute " + m.name, url: "mute" })
        if (openKey !== "" && m.mine && !m.unsent && !m.failed) items.push({ name: "Unsend", detail: "Removes it for both", url: "unsend", danger: true })
        items.push({ name: openKey === "" ? "Delete" : "Delete for me", url: "delete", danger: true })
        const k = openKey
        Ui.pickFrom("Message", [{ header: "", items: items }], a => {
            if (a === "react") pickReaction(m)
            else if (a === "reply") startReply(m.id)
            else if (a === "thread") openThread(m.id)
            else if (a === "pin") togglePin(m.id)
            else if (a === "edit") startEdit(m)
            else if (a === "copy") { Chat.copyText(m.text, true); Ui.say("Copied. It's cleared from the clipboard in a minute.") }
            else if (a === "readaloud") { if (ReadAloud.speaking) ReadAloud.stop(); else { ReadAloud.speak(m.text); if (!ReadAloud.speaking && ReadAloud.status !== "") Ui.say(ReadAloud.status) } }
            else if (a === "link") { Chat.copyText(link); Ui.say("Link copied.") }
            else if (a === "joingroup") PeopleActions.joinGroup(m.text, null)
            else if (a === "forward") PeopleActions.forwardMenu(m.text)
            else if (a === "folder") { if (!FileLink.showInFolder(m.saved)) Ui.say("That file isn't there anymore.", true) }
            else if (a === "view") viewing = m.photo
            else if (a === "save") Ui.say(Chat.savePhoto(m.photo) ? "Saved in Pictures › Huginn." : "Couldn't save it.")
            else if (a === "savemedia") { const p = Chat.saveMedia(m.photo); Ui.say(p !== "" ? "Saved in " + (Transfer.saveName || "Downloads") + " › Huginn." : "Couldn't save it.", p === "") }
            else if (a === "forwardmedia") PeopleActions.forwardMedia(m, m.voice ? "voice message" : m.video ? "video" : m.gif ? "GIF" : "photo")
            else if (a === "play") { if (Voice.playing === m.id) Voice.stop(); else Voice.play(m.photo) }
            else if (a === "unsend") Ui.sure("Unsend this message?", "It's removed for both of you.", "Unsend", () => Chat.unsend(k, m.id), true, true)
            else if (a === "retry") Chat.retry(k, m.id)
            else if (a === "mute") Chat.block(m.peer, true)
            else if (a === "delete") {
                // A photo, video or voice message is held 5 seconds, as from Media and files.
                if (k !== "" && m.photo) Ui.sure("Delete this " + (m.voice ? "voice message" : m.video ? "video" : "photo") + "?", "It's deleted on this computer only.", "Delete",
                                                 () => Chat.deleteMessage(k, m.id), true, true)
                else if (k === "") Chat.deleteNearbyMessage(m.id)
                else Chat.deleteMessage(k, m.id)
            }
            else if (a === "savefile") PeopleActions.saveFile(k, m)
        })
    }

    // ------------------------------------------------------------- cap m1: replies, reactions, edits
    function jumpTo(id) {
        const i = messages.findIndex(m => m.id === id)
        if (i < 0) { Ui.say("That message isn't here anymore."); return }
        sel = i
        list.positionViewAtIndex(i, ListView.Center)
    }
    // Files › Show in chat: the newest message with that file.
    function showFile(name) {
        for (let i = messages.length - 1; i >= 0; i--)
            if (messages[i].file === name) { showInChat(messages[i].id); return }
        Ui.say("Its message isn't here anymore.")
    }
    // Media and files › Show in chat: there, and lit for a moment.
    property string flashId: ""
    Timer { id: flashTimer; interval: 1800; onTriggered: page.flashId = "" }
    function showInChat(id) {
        zone = "messages"
        jumpTo(id)
        if (messages.some(m => m.id === id)) { flashId = id; flashTimer.restart() }
    }
    function reactTo(id, e) {
        if (openKey === "" || id === "") return
        const r = Chat.react(openKey, id, e)
        if (r !== "") Ui.say(r, true)
    }
    function openReact(id, mine) {
        if (openKey === "") return
        reactFor = id
        reactPicker.current = mine
        reactPicker.visible = true
    }
    function startReply(id) {
        if (openKey === "") return
        editId = ""
        replyId = id
        write()
    }
    function startEdit(m) {
        if (!m || !m.mine || m.photo || m.unsent || openKey === "") return
        if (!Chat.hasM1(openKey)) { Ui.say(openName + " needs an update to see edits.", true); return }
        replyId = ""
        if (compose.visible) { editId = m.id; zone = "messages"; sel = -1; compose.setText(m.text); compose.writeHere() }
        else { const k = openKey, id = m.id; Ui.askMessage("Edit your message", "", m.text, "Save", t => { const r = Chat.editMessage(k, id, t); if (r !== "") Ui.say(r, true) }) }
    }
    // Up in an empty box: edit my last text message.
    function editLast() {
        for (let i = messages.length - 1; i >= 0; i--) {
            const m = messages[i]
            if (m.mine && !m.photo && !m.unsent && !m.failed && !m.system) { startEdit(m); return }
        }
    }
    // Esc: done with this chat for now: no reply or edit, and it's all read.
    function escapeKey() {
        replyId = ""; editId = ""; newFrom = -1
        if (openKey !== "") Chat.markRead(openKey)
    }
    // A thread: the message and its replies beside the chat, with its own reply box. The controller
    // (or its pointer) goes into the panel; a mouse gets a cursor in its reply box.
    function openThread(id) {
        if (openKey === "") return
        threadRoot = id
        side = "thread"
        if (pad) { zone = "side"; sideSel = threadMsgs.length - 1 }
        else Qt.callLater(() => threadBox.focusField())
    }
    function openPins() {
        if (openKey === "") return
        side = "pins"
        if (pad) { zone = "side"; sideSel = pinnedMsgs.length ? 0 : -1 }
    }
    function sideMsgs() { return side === "thread" ? threadMsgs : side === "pins" ? pinnedMsgs : [] }
    // Options for a message in the panel: the same as in the chat.
    function sideOptions(i) {
        const m = sideMsgs()[i]
        if (!m) return
        zone = "side"; sideSel = i
        const at = messages.findIndex(x => x.id === m.id)
        if (at >= 0) { sel = at; options() }
    }
    function togglePin(id) {
        if (tour || openKey === "") return
        const on = pins.indexOf(id) < 0
        Chat.setPinned(openKey, id, on)
        Ui.say(on ? "Pinned on this device." : "Unpinned.")
    }
    // re: the message answered; a reply in a thread passes the thread's first message.
    function sendText(t, re) {
        if (editId !== "") {
            const r = Chat.editMessage(openKey, editId, t)
            if (r !== "") Ui.say(r, true)
            editId = ""
            return
        }
        if (openKey === "") {
            if (!Chat.nearbyOn) { Ui.say("Nearby room is off: Settings › Privacy and lock.", true); return }
            Chat.sendNearby(t)
        } else Chat.sendPrivate(openKey, t, re !== undefined ? re : replyId)
        if (re === undefined) replyId = ""
        newFrom = -1
    }
    // Dropped files: pictures go in the chat; anything else goes to Files, ready to send over the Wi-Fi.
    function dropFiles(urls) { more.dropFiles(urls) }

    // The list the left stick scrolls when the pointer isn't showing: the one with the focus.
    function scrollView() { return zone === "messages" ? list : zone === "side" ? sideList : listPane }
    // The controller in a message box: A sends (or brings Steam's keyboard back), Y brings the
    // keyboard back, LT the box's options, B leaves it. Up and the rest leave it and act as usual.
    // The on-screen keyboard would cover the message box at the foot: A writes the message in a prompt at the top
    // instead, and its A sends.
    function writeAbove(box) { box.writeAbove() }
    function boxButton(box, b) {
        switch (b) {
        case "a":
            if (box.text.trim() !== "") box.enter()
            else if (System.keyboardCovers) writeAbove(box)
            else System.showSteamKeyboard()
            return true
        // Y: the keyboard again; where it would cover the box, the top prompt with what's typed so far.
        case "y":
            if (System.keyboardCovers) writeAbove(box)
            else System.showSteamKeyboard()
            return true
        case "x": boxOptions(box); return true
        case "down": return true
        }
        box.leave()
        return b === "b"
    }
    function boxOptions(box) {
        const items = [{ name: "Send", icon: "send", url: "send" }, { name: "Emoji", icon: "smile", url: "emoji" }]
        if (openKey !== "") items.push({ name: "Send file, pictures, video or poll", icon: "attach", url: "picture" })
        if (box.bannerTitle !== "") items.push({ name: editId !== "" ? "Stop editing" : "Don't reply", url: "cancel" })
        items.push({ name: "Done typing", url: "leave" })
        Ui.pickFrom(box.placeholder, [{ header: "", items: items }], a => {
            if (a === "send") box.enter()
            else if (a === "emoji") box.toggleEmoji()
            else if (a === "picture") pickPicture()
            else if (a === "cancel") box.cancelBanner()
            else if (a === "leave") box.leave()
        })
    }
    // A thread or the pins: up and down pick, A answers in the thread or goes to a pin, B closes.
    function sideButton(b) {
        const n = sideMsgs().length
        switch (b) {
        case "up": if (n) sideSel = sideSel < 0 ? n - 1 : Math.max(0, sideSel - 1); return true
        case "down": if (sideSel >= 0) sideSel = Math.min(n - 1, sideSel + 1); return true
        case "left": zone = "messages"; return true
        case "right": return true
        case "a":
            if (side === "thread") threadBox.writeHere()
            else if (side === "pins" && sideSel >= 0) { const m = sideMsgs()[sideSel]; zone = "messages"; if (m) jumpTo(m.id) }
            return true
        case "x": if (sideSel >= 0) sideOptions(sideSel); else chatMenu(); return true
        case "y": return true
        case "b": side = ""; threadRoot = ""; return true
        }
        return false
    }
    // Returns false for what this page doesn't use, so Main.qml can take it (Left or B to the rail).
    function handleButton(b) {
        if (lock.visible) return lock.handleButton(b)
        if (mirror.visible) return mirror.handleButton(b)
        if (more.busy) return more.handleButton(b)
        if (!picked && !locked && zone === "messages" && b === "a") { addMenu(); return true }
        if (showCode) {
            if (b === "a") { Chat.copyLink(); Ui.say("Link copied.") }
            else if (b === "x") Chat.newLink()
            else if (b === "b") showCode = false
            return true
        }
        if (viewing !== "") { if (b === "b" || b === "a") viewing = ""; return true }
        if (Voice.recording) { if (b === "b") { Voice.cancel(); Ui.say("Recording cancelled.") } return true }
        if (finder.visible && finder.handleButton(b)) return true
        if (reactPicker.visible) return reactPicker.handleButton(b)
        if (compose.emojiShown) return compose.emojiButton(b)
        if (threadBox.emojiShown) return threadBox.emojiButton(b)
        if (typingIn && boxButton(typingIn, b)) return true
        if (zone === "side") return sideButton(b)
        if (zone === "head") return headButton(b)
        // A file card: the D-pad is on its buttons (Up past it goes back to the messages).
        if (zone === "messages" && fileStrip.focusIndex >= 0) {
            if (fileStrip.handleButton(b)) return true
            if (b === "up") {
                if (messages.length > 0) { sel = messages.length - 1; list.positionViewAtIndex(sel, ListView.Contain) }
                return true
            }
        }
        // Keep in touch: A says yes (Menu always opens Settings).
        if (zone === "messages" && openKey !== "" && keep.visible && sel < 0 && b === "a") { Chat.keepInTouch(openKey); return true }
        switch (b) {
        case "up":
            if (zone === "list") row = Math.max(0, row - 1)
            else if (openKey !== "" && (sel === 0 || messages.length === 0)) { zone = "head"; sel = -1; headSel = 0 }
            else if (messages.length > 0) { sel = sel < 0 ? messages.length - 1 : Math.max(0, sel - 1); list.positionViewAtIndex(sel, ListView.Contain) }
            return true
        case "down":
            if (zone === "list") row = Math.min(entries.length - 1, row + 1)
            else if ((sel < 0 || sel === messages.length - 1) && fileStrip.enter()) sel = -1   // on to a file card's buttons
            else if (sel >= 0) { sel = Math.min(messages.length - 1, sel + 1); list.positionViewAtIndex(sel, ListView.Contain) }
            return true
        case "right":
            if (zone === "messages" && sidePanel.visible) { const n = sideMsgs().length; zone = "side"; sideSel = !n ? -1 : side === "thread" ? n - 1 : 0 }
            else zone = "messages"
            return true
        case "left":
            if (zone === "list") return false
            zone = "list"; sel = -1
            return true
        case "a":
            if (zone === "list") choose()
            else if (sel >= 0 && messages[sel] && messages[sel].call && openKey !== "") startCall()   // a call's line: call back
            else write()
            return true
        case "x":
            if (zone === "list" && entries[row] && entries[row].kind === "newfrom") hideNewFrom()   // the "New from" bar's X
            else if (zone === "list" && entries[row] && entries[row].kind === "verse") rowContext(entries[row])   // today's verse: Open or Hide for today (LT is X here, so one press never hides it)
            else if (zone === "list") listOptions()
            else if (zone === "messages" && sel >= 0) options()
            else if (zone === "messages" && openKey !== "") chatMenu()
            else if (zone === "messages") write()
            return true
        case "y":
            // On a person in the list: their right-click menu (Move up and Move down are there).
            if (zone === "list" && entries[row] && (entries[row].kind === "chat" || entries[row].kind === "near")) {
                const it = listPane.itemAtIndex(row)
                if (it) rowContextAt(it, Qt.point(Math.min(it.width / 2, Theme.px(140)), it.height / 2), entries[row])
                else rowContext(entries[row])
                return true
            }
            if (zone === "messages" && openKey !== "" && !Voice.recording) {
                if (!Voice.start(openKey)) Ui.say("The microphone isn't available right now.", true)
            }
            return true
        case "b":
            if (zone === "messages") { zone = "list"; sel = -1; return true }
            return false
        }
        return false
    }
    function handleRelease(b) {
        // Only to the chat it was started in: Voice throws it away (and says so) if this isn't that chat.
        if (b === "y" && Voice.recording) Voice.finish(openKey)
        return true
    }
    // Ctrl+F: find in this chat.
    Shortcut {
        sequences: [StandardKey.Find]
        enabled: page.picked && !page.locked && !page.tour && !mirror.visible && !!Ui.main && Ui.main.keysOn
        onActivated: page.openFind()
    }
    // A linked phone's chats, over everything (companion side of linked devices).
    MirrorPanel { id: mirror; anchors.fill: parent; z: 20 }
    // The phone's chats never stay on show under the lock.
    onLockedChanged: if (locked && mirror.visible) mirror.close()
    Connections {
        target: Voice
        function onErrorChanged() { if (Voice.error !== "") Ui.say(Voice.error, true) }
    }

    // Cap m2: albums full size, video messages, the camera, polls and the Shared page.
    property alias more: moreLayer
    MoreLayer {
        id: moreLayer
        visible: !lock.visible
        where: page.locked ? "" : page.openKey
        title: page.openName
        tour: page.tour
        onShowInChat: msgId => page.showInChat(msgId)
    }
}

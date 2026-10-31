import QtQuick
import Huginn

// Chats, the same as Muninn's. Middle column: the title with + (add someone, my code, a one-time
// link) and the list's three dots (New folder, Trash, and the Labs entries), then requests, people in no folder, each
// folder as a section that folds away (like Discord categories), and at the very bottom the Nearby
// room and people around you. Every person has one menu (PeopleActions.contactMenu): their three
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

    function setArgs(a) {
        tour = !!a.tour
        if (tour) picked = true
        changeLock = !!a.changeLock
        lockOff = !!a.lockOff
        if (a.zone) zone = a.zone
        if (a.folders) { zone = "list"; row = Math.max(0, entries.findIndex(e => e.kind === "folder")) }
        if (a.open) { openKey = a.open; picked = true; zone = "messages" }   // from a contact's menu › Message
        // A contact's menu › Chat › Pinned messages or Shared.
        if (a.open && a.show === "pins") Qt.callLater(openPins)
        if (a.open && a.show === "shared") Qt.callLater(() => page.more.openShared())
    }

    readonly property bool locked: !tour && (!Vault.exists || Vault.screenLocked)
    // The lock's own LB (pattern or password) wins over moving along the rail.
    readonly property bool ownsShoulders: lock.visible && !lock.asks

    // A person shows under every folder they're in ("section" is the folder, "" for none). Folders
    // keep their own order; people in no folder go newest first until they're moved.
    readonly property var entries: {
        // Rows waiting for an answer carry a short tag instead of a second line.
        const out = []
        Chat.groupInvites.forEach(i => out.push({ kind: "ginvite", g: i.g, name: i.name, waiting: i.waiting, fromName: i.fromName, tag: i.waiting ? "Waiting" : "Invite" }))
        Chat.requests.forEach(r => out.push({ kind: "request", key: r.key, name: r.name, replaces: r.replaces || "", replacesName: r.replacesName || "",
                                              tag: r.replaces ? "New key" : "Request" }))
        // Introductions, group members asking and Family offers (i1); a linked phone's chats (companion).
        PeopleActions.pendingEntries().forEach(e => out.push(Object.assign({ unread: true, tag: e.pendingKind === "intro" ? "Intro" : "Request" }, e)))
        if (Linked.phone.key) out.push({ kind: "mirror", name: Linked.phone.name + "'s chats", tag: "Phone" })
        const chats = Chat.chats, byKey = {}
        chats.forEach(c => byKey[c.key] = c)
        const person = (c, section) => ({ kind: "chat", key: c.key, name: c.name, unread: c.unread, route: c.route, section: section })
        const order = Chat.topOrder
        const loose = chats.filter(c => !(c.folders || []).length)
        loose.filter(c => order.indexOf(c.key) < 0).forEach(c => out.push(person(c, "")))
        order.forEach(k => { const c = byKey[k]; if (c && !(c.folders || []).length) out.push(person(c, "")) })
        Chat.folders.forEach(f => {
            const keys = (f.keys || []).filter(k => !!byKey[k])
            out.push({ kind: "folder", id: f.id, name: f.name, collapsed: !!f.collapsed, count: keys.length,
                       unread: !!f.collapsed && keys.some(k => byKey[k].unread) })
            if (!f.collapsed) keys.forEach(k => out.push(person(byKey[k], f.id)))
        })
        // Nearby always last, in its own section. Hidden from people nearby: none of it.
        if (Chat.visibleNearby) {
            out.push({ kind: "nearby", name: "Nearby room", label: "Nearby", tag: Chat.nearbyOn ? "" : "Off" })
            Chat.around.forEach((p, i) => out.push({ kind: "stranger", key: p.key, name: p.name, label: i === 0 ? "Around you" : "", route: p.route }))
        }
        return out
    }
    readonly property var messages: {
        if (tour && openKey === "")
            return [{ id: "1", name: "Raven 4821", text: "Anyone have the Emerald save from yesterday?", mine: false, time: Date.now() - 60000 },
                    { id: "2", name: Chat.username, text: "Sending it over LocalSend now", mine: true, time: Date.now(), state: "read" }]
        return openKey === "" ? (picked && Chat.visibleNearby ? Chat.nearby : []) : (Chat.chats, Chat.messagesWith(openKey))
    }
    readonly property string openName: openKey === "" ? "" : (Chat.chats, Chat.nameOf(openKey))   // Chat.chats: follows renames
    readonly property string openRoute: openKey === "" ? "" : (Chat.chats, Chat.route(openKey))
    readonly property bool keyWarning: openKey !== "" && !tour && (Chat.chats, Chat.keyChanged(openKey))
    function routeWord(r) { return r === "bluetooth" ? "Bluetooth" : r === "wifi" ? "Wi-Fi" : r === "internet" ? "Internet" : "Not reachable" }

    // What the hint bar shows (Main.qml adds LB). LT opens the menu (the page gets it as X).
    readonly property var prompts: lock.visible ? [{ key: "a", label: lock.usePattern ? "Start or finish" : "Type password" }].concat(!lock.asks ? [{ key: "lb", label: "Pattern or password" }] : []).concat([{ key: "b", label: "Back" }])
        : showCode ? [{ key: "a", label: "Copy link" }, { key: "x", label: "New code" }, { key: "b", label: "Close" }]
        : zone === "messages" && fileStrip.focusIndex >= 0 ? [{ key: "a", label: fileStrip.focusButton === 0 ? "Accept" : "Decline" }, { key: "b", label: "Back" }]
        : reactPicker.visible || compose.emojiShown || threadBox.emojiShown ? [{ key: "a", label: "Pick" }, { key: "b", label: "Close" }]
        : typingIn ? [{ key: "a", label: "Send" }, { key: "y", label: "Keyboard" }, { key: "lt", label: "Options" }, { key: "b", label: "Done" }]
        : zone === "side" ? (side === "thread" ? [{ key: "a", label: "Reply in the thread" }] : [{ key: "a", label: "Go to it" }])
                            .concat([{ key: "lt", label: "Options" }, { key: "b", label: "Close" }])
        : zone === "list" ? [{ key: "a", label: entries[row] && entries[row].kind === "folder" ? (entries[row].collapsed ? "Expand" : "Collapse") : "Open" },
                             { key: "lt", label: "Menu" }, { key: "b", label: "Back" }]
        : !picked ? [{ key: "a", label: "Add someone" }, { key: "b", label: "Back" }]
        : [{ key: "a", label: keep.visible && sel < 0 ? "Keep in touch" : "Write" }].concat(openKey !== "" ? [{ key: "y", label: "Hold to talk" }] : [])
            .concat(sel >= 0 ? [{ key: "lt", label: "Options" }] : openKey !== "" ? [{ key: "lt", label: "Menu" }] : []).concat([{ key: "b", label: "Back" }])
    readonly property string status: lock.visible ? "" : Chat.netStatus

    function clock(ms) { return Qt.formatTime(new Date(ms), "h:mm AP") }
    function stateLabel(m) {
        if (!m.mine) return ""
        if (m.failed) return " · Didn't send"
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
    function senderName(m) { return m.mine ? Chat.username : openKey === "" ? (m.name || "Someone") : openName }

    onOpenKeyChanged: {
        sel = -1; Voice.stop()
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
        if (openKey !== "" && !tour) Calls.prepare(openKey)   // the relay login is ready before Call is pressed
        Chat.openChat = openKey
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
    Component.onCompleted: { Vault.back(); showLimitsNote() }   // away more than a minute: locked again
    // Cap d1: told once, on the chat list, that messages don't stay forever. The one place this is explained.
    function showLimitsNote() {
        if (tour || locked || !Chat.ready || !Chat.limitsNote()) return
        Chat.dismissLimitsNote()
        Ui.sure("Messages disappear", "After 7 days, on every device. Family and Work can keep them up to 30 days.", "Got it", () => {})
    }
    Connections { target: Chat; function onChanged() { page.showLimitsNote() } }
    Component.onDestruction: { Chat.openChat = ""; Voice.cancel(); Voice.stop(); Vault.away() }
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive) { Vault.back(); if (page.openKey !== "" && !page.locked) Chat.markRead(page.openKey) }
            else Vault.away()
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
            delegate: Item {
                id: entry
                readonly property bool on: page.active && page.zone === "list" && page.row === index
                readonly property bool isHeader: modelData.kind === "folder"
                readonly property real labelHeight: modelData.label ? Theme.px(30) : 0
                width: listPane.width
                height: labelHeight + (isHeader ? Theme.px(30) : Theme.px(42))

                // The start of the bottom section (Nearby), or of the people around you.
                Item {
                    visible: !!modelData.label
                    width: parent.width
                    height: entry.labelHeight
                    Rectangle { visible: modelData.kind === "nearby"; anchors.top: parent.top; anchors.topMargin: Theme.px(6); width: parent.width; height: 1; color: Theme.line }
                    Text {
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(4)
                        text: String(modelData.label || "").toUpperCase()
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
                    color: entry.on || (page.dragKey !== "" && page.dropSection === modelData.id && page.dropOnHeader) ? Theme.tile : headHover.hovered ? Theme.raised : "transparent"
                    border.width: entry.on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.Button
                    Accessible.name: modelData.name + (modelData.collapsed ? ", folded away" : "")
                    Icon {
                        id: chevron
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(8)
                        anchors.verticalCenter: parent.verticalCenter
                        name: "down"; size: Theme.px(14)
                        rotation: modelData.collapsed ? -90 : 0
                        color: Theme.textMuted
                    }
                    Text {
                        anchors.left: chevron.right; anchors.leftMargin: Theme.px(6)
                        anchors.right: headDot.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight
                        text: String(modelData.name || "").toUpperCase() + "  " + (modelData.count || 0)
                        color: entry.on || modelData.unread ? Theme.text : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(0.5)
                    }
                    Rectangle {
                        id: headDot
                        anchors.right: folderMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(8); height: width; radius: width / 2
                        color: Theme.accent
                        visible: !!modelData.unread
                    }
                    HoverHandler { id: headHover }
                    TapHandler {
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onTapped: (point, button) => { page.zone = "list"; page.row = index; if (button === Qt.RightButton) page.listOptions(); else page.choose() }
                    }
                    MoreButton {
                        id: folderMore
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(2)
                        anchors.verticalCenter: parent.verticalCenter
                        implicitWidth: Theme.px(24); implicitHeight: Theme.px(24)
                        label: "Folder menu"
                        onClicked: { page.zone = "list"; page.row = index; page.folderOptions(modelData) }
                    }
                }

                Rectangle {
                    readonly property bool on: entry.on
                    readonly property bool here: (modelData.kind === "nearby" && page.openKey === "") || (modelData.key !== undefined && modelData.key === page.openKey)
                    readonly property bool person: modelData.kind === "chat" || modelData.kind === "stranger" || modelData.kind === "request"
                    visible: !entry.isHeader
                    anchors.bottom: parent.bottom
                    width: parent.width
                    height: Theme.px(42)
                    radius: Theme.radius
                    // The one being dragged stays dim in its old place until it's dropped.
                    opacity: page.dragKey !== "" && modelData.kind === "chat" && modelData.key === page.dragKey && modelData.section === page.dragFrom ? 0.4 : 1
                    color: on || here ? Theme.tile : rowHover.hovered ? Theme.raised : "transparent"
                    border.width: on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.ListItem
                    Accessible.name: modelData.name + (modelData.tag ? ", " + modelData.tag : "") + (modelData.unread ? ", unread" : "")
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
                            key: page.isTour || modelData.kind !== "chat" ? "" : modelData.key || ""
                            name: modelData.name
                            showDot: modelData.kind === "chat" && Chat.activeStatus
                            backdrop: parent.parent.on || parent.parent.here ? Theme.tile : Theme.column
                        }
                        Icon {
                            visible: !parent.parent.person
                            anchors.centerIn: parent
                            size: Theme.px(18)
                            name: modelData.kind === "ginvite" ? "hash" : modelData.kind === "mirror" ? "phone" : "people"
                            color: parent.parent.here || parent.parent.on ? Theme.accent : Theme.textMuted
                        }
                        // Requests wait for an answer: a lock badge so they stand out.
                        Rectangle {
                            visible: modelData.kind === "request" || (modelData.kind === "ginvite" && !modelData.waiting)
                            anchors.right: parent.right; anchors.bottom: parent.bottom
                            anchors.margins: -Theme.px(2)
                            width: Theme.px(14); height: width; radius: width / 2
                            color: Theme.accent
                        }
                    }
                    // One line: the name, a short tag for what waits for an answer, the unread dot and the three dots.
                    Text {
                        anchors.left: ava.right; anchors.leftMargin: Theme.px(10)
                        anchors.right: tagChip.visible ? tagChip.left : dot.visible ? dot.left : rowMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight; text: modelData.name || ""
                        color: parent.on || parent.here || modelData.unread ? Theme.text : Theme.textSoft
                        font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: modelData.unread ? Font.DemiBold : Font.Medium
                    }
                    Rectangle {
                        id: tagChip
                        visible: !!modelData.tag
                        anchors.right: dot.visible ? dot.left : rowMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        width: tagText.implicitWidth + Theme.px(12); height: tagText.implicitHeight + Theme.px(4)
                        radius: Theme.radiusSmall
                        color: "transparent"
                        border.color: modelData.kind === "nearby" || modelData.waiting ? Theme.lineMuted : Theme.accent
                        Text {
                            id: tagText
                            anchors.centerIn: parent
                            text: modelData.tag || ""
                            color: modelData.kind === "nearby" || modelData.waiting ? Theme.textMuted : Theme.accent
                            font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold
                        }
                    }
                    Rectangle {
                        id: dot
                        anchors.right: rowMore.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(8); height: width; radius: width / 2
                        color: Theme.accent
                        visible: !!modelData.unread
                    }
                    HoverHandler { id: rowHover }
                    TapHandler {
                        enabled: modelData.kind !== "chat"
                        onTapped: { page.zone = "list"; page.row = index; page.choose() }
                    }
                    // People: a click opens, a right click shows their options, and a mouse drag moves
                    // them up or down or into another folder. Touch still scrolls the list.
                    MouseArea {
                        id: dragArea
                        anchors.fill: parent
                        enabled: modelData.kind === "chat"
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        property real startY: 0
                        property bool moved: false
                        onPressed: mouse => {
                            startY = mouse.y
                            moved = false
                            preventStealing = mouse.button === Qt.LeftButton && mouse.source === Qt.MouseEventNotSynthesized
                        }
                        onPositionChanged: mouse => {
                            if (!preventStealing) return
                            if (!moved && Math.abs(mouse.y - startY) > Theme.px(8)) { moved = true; page.startDrag(modelData) }
                            if (moved) page.dragTo(mapToItem(listPane, mouse.x, mouse.y).y)
                        }
                        onReleased: { if (moved) page.dropHere() }
                        onCanceled: { if (moved) page.cancelDrag(); moved = false }
                        onClicked: mouse => {
                            if (moved) return
                            page.zone = "list"; page.row = index
                            if (mouse.button === Qt.RightButton) page.rowMenu(modelData); else page.choose()
                        }
                        // Press and hold: the person panel too (a touch screen has no right click).
                        onPressAndHold: mouse => { if (!moved && mouse.source !== Qt.MouseEventNotSynthesized) { page.row = index; page.rowMenu(modelData) } }
                        Component.onDestruction: if (moved) page.cancelDrag()
                    }
                    // A person's one menu (only people have one; the rest answer A or a click).
                    MoreButton {
                        id: rowMore
                        visible: modelData.kind === "chat"
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(4)
                        anchors.verticalCenter: parent.verticalCenter
                        width: visible ? implicitWidth : 0
                        label: "More for " + (modelData.name || "")
                        onClicked: { page.zone = "list"; page.row = index; page.rowMenu(modelData) }
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
            visible: page.locked
            anchors.top: listHead.bottom
            anchors.topMargin: Theme.px(16)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: Vault.exists ? "Locked." : "Set a lock to start."
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
            // How they're reached (never an address), Active, and Verified or a changed security key.
            subtitle: page.locked || !page.picked ? "" : page.openKey === "" ? "People close by"
                    : [page.routeWord(page.openRoute)].concat(page.theyActive ? ["Active"] : [])
                      .concat([page.keyWarning ? "Security key changed" : (Chat.chats, Chat.isVerified(page.openKey)) ? "Verified" : "Not verified"]).join(" · ")
            warn: !page.locked && page.picked && page.keyWarning
            // Name, Call and the person's one menu.
            HButton {
                anchors.verticalCenter: parent.verticalCenter
                visible: !page.locked && page.openKey !== ""
                text: "Call"
                implicitHeight: Theme.px(30); fontSize: 13; kind: "plain"
                onClicked: page.startCall()
            }
            MoreButton {
                visible: !page.locked && page.openKey !== ""
                anchors.verticalCenter: parent.verticalCenter
                label: "More for " + page.openName
                onClicked: page.chatMenu()
            }
        }

        // ------------------------------------------------------- locked
        LockPanel {
            id: lock
            visible: page.locked || page.changeLock || page.lockOff
            mode: page.lockOff && !page.locked ? "off" : page.changeLock && !page.locked ? "change" : Vault.exists ? "unlock" : "setup"
            anchors.top: convHead.bottom
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width - Theme.px(32), Theme.px(520))
            onFinished: how => {
                if (how === "change") page.changeLock = false   // after an unlock, the change step follows
                if (how === "off") page.lockOff = false
                // A lock shorter than new ones may be: suggest a stronger one, once.
                if (how === "unlock" && Vault.askStronger())
                    Ui.sure("Make your lock stronger?", "Your " + (Vault.usesPattern ? "pattern" : "password") + " is shorter than new ones may be. One with at least 6 "
                            + (Vault.usesPattern ? "dots" : "characters") + " is much harder to guess.", "Change it", () => { page.changeLock = true })
            }
            onCancelled: { if (page.lockOff) page.lockOff = false; else if (page.changeLock) page.changeLock = false; else Ui.closePage() }
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
                    Text { width: parent.width; wrapMode: Text.WordWrap; text: Chat.theySaidKeep(page.openKey) ? page.openName + " wants to keep in touch" : "Keep in touch with " + page.openName + "?"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold }
                    Text { width: parent.width; wrapMode: Text.WordWrap; text: "Then message from anywhere."; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                    Row {
                        spacing: Theme.px(8)
                        HButton { text: "Yes"; kind: "primary"; focused: page.pad && page.active && page.zone === "messages" && page.sel < 0; implicitHeight: Theme.px(32); fontSize: 13; onClicked: Chat.keepInTouch(page.openKey) }
                        HButton { text: "Not now"; kind: "plain"; implicitHeight: Theme.px(32); fontSize: 13; onClicked: Chat.notNow(page.openKey) }
                    }
                }
            }

            // Files going straight to or from them (cap f1).
            FileStrip {
                id: fileStrip
                peer: page.tour ? "" : page.openKey
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.leftMargin: Theme.px(14); anchors.rightMargin: Theme.px(14)
            }
            // Messages change in place (LiveList): a new one adds a row instead of redrawing them all.
            LiveList { id: messageModel; keys: ["id"]; items: page.messages }
            ListView {
                id: list
                anchors.top: keep.visible ? keep.bottom : parent.top
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
                    color: picked ? Theme.focusFill : msgHover.hovered ? Theme.panelLow : "transparent"
                    border.width: picked ? Theme.focusWidth : 0
                    border.color: Theme.accent

                    Item {
                        visible: msg.lineH > 0
                        width: parent.width; height: msg.lineH
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width; height: 1; color: Theme.danger }
                        Rectangle {
                            anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                            width: newText.implicitWidth + Theme.px(14); height: Math.max(Theme.px(20), newText.implicitHeight + Theme.px(4)); radius: Theme.radius; color: Theme.danger
                            Text { id: newText; anchors.centerIn: parent; text: "NEW MESSAGES"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold }
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
                        color: Theme.dangerFill
                        border.width: 1
                        border.color: Theme.danger
                        Text {
                            id: noteText
                            anchors.centerIn: parent
                            width: Math.max(0, note.width - Theme.px(28))
                            horizontalAlignment: Text.AlignHCenter
                            wrapMode: Text.Wrap
                            text: modelData.system ? modelData.text : ""
                            font.italic: true
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                        }
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
                                id: who
                                width: Math.min(implicitWidth, Math.round(col.width * 0.6))
                                elide: Text.ElideRight
                                text: page.senderName(modelData)
                                color: Theme.avatar(page.senderName(modelData), modelData.mine).fg
                                font.family: Theme.body; font.pixelSize: Theme.ms(16); font.weight: Font.Medium
                            }
                            Text {
                                anchors.baseline: who.baseline
                                text: page.clock(modelData.time) + (modelData.isEdited ? " · (edited)" : "") + page.stateLabel(modelData)
                                color: modelData.failed ? Theme.danger : modelData.state === "read" && modelData.mine ? Theme.accent : Theme.textMuted
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
                            source: visible ? Chat.photoUrl(modelData.photo) : ""
                        }
                        Row {
                            visible: !!modelData.voice && !modelData.unsent
                            spacing: Theme.px(10)
                            Icon { name: Voice.playing === modelData.id ? "stop" : "play"; color: Theme.accent }
                            Column {
                                anchors.verticalCenter: parent.verticalCenter
                                Rectangle {
                                    width: Theme.px(180); height: Theme.px(4); color: Theme.line
                                    Rectangle { width: parent.width * (Voice.playing === modelData.id ? Voice.progress : 0); height: parent.height; color: Theme.accent }
                                }
                                Text { text: "Voice message · " + Voice.length(modelData.voice || 0); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
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
                            visible: msg.grouped && (!!modelData.isEdited || page.stateLabel(modelData) !== "" || !!modelData.failed)
                            text: (modelData.isEdited ? "(edited)" : "") + page.stateLabel(modelData)
                            color: modelData.failed ? Theme.danger : Theme.textMuted
                            font.family: Theme.body; font.pixelSize: Theme.fs(11)
                        }
                        // Cap m2: an album, a video message, a poll or a link preview.
                        MoreParts { width: parent.width; host: page; m: modelData; where: page.openKey }
                        Reactions {
                            width: parent.width
                            list: modelData.rxList || []
                            add: page.openKey !== ""
                            onToggled: (e, mine) => page.reactTo(modelData.id, mine ? "" : e)
                            onMore: page.openReact(modelData.id, modelData.myReaction || "")
                        }
                        // A thread: how many replies, opening them beside the chat.
                        Text {
                            visible: page.openKey !== "" && (modelData.replies || 0) > 0 && !modelData.unsent
                            text: (modelData.replies === 1 ? "1 reply" : modelData.replies + " replies") + " ›"
                            color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                            Accessible.role: Accessible.Button
                            Accessible.name: "Open the thread"
                            HoverHandler { cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: page.openThread(modelData.id) }
                        }
                    }
                    // React, reply, edit and more over the corner while the pointer is on it or the
                    // controller has it picked.
                    HoverBar {
                        id: tools
                        visible: page.openKey !== "" && (msgHover.hovered || msg.picked) && !modelData.system && !modelData.unsent
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        y: msg.lineH - Theme.px(6)
                        z: 2
                        canEdit: !!modelData.mine && !modelData.photo && !modelData.failed
                        canThread: true
                        canPin: true
                        pinnedNow: page.pins.indexOf(modelData.id) >= 0
                        onReact: page.openReact(modelData.id, modelData.myReaction || "")
                        onReply: page.startReply(modelData.id)
                        onThread: page.openThread(modelData.id)
                        onPin: page.togglePin(modelData.id)
                        onEdit: page.startEdit(modelData)
                        onMore: { page.zone = "messages"; page.sel = index; page.options() }
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
                            if (button === Qt.RightButton) page.options()
                            else if (modelData.photo && !modelData.voice && !modelData.unsent && !modelData.video && !modelData.album) page.viewing = modelData.photo
                        }
                    }
                    TapHandler {
                        acceptedDevices: PointerDevice.TouchScreen
                        onTapped: { page.zone = "messages"; page.sel = index; page.options() }
                    }
                }
            }
            Text {
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
                    width: parent.width; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap
                    text: "PICK A CHAT"
                    color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
                }
                Text {
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
                Image { anchors.fill: parent; anchors.margins: Theme.px(10); fillMode: Image.PreserveAspectFit; source: page.viewing !== "" ? Chat.photoUrl(page.viewing) : "" }
                TapHandler { onTapped: page.viewing = "" }
            }

            // Recording a voice message.
            Rectangle {
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.margins: Theme.px(12)
                height: Theme.px(52)
                visible: Voice.recording
                radius: Theme.radius; color: Theme.dangerFill; border.color: Theme.danger; border.width: 1
                Row {
                    anchors.verticalCenter: parent.verticalCenter
                    x: Theme.px(16); spacing: Theme.px(12)
                    Rectangle { width: Theme.px(12); height: width; radius: width / 2; color: Theme.danger; anchors.verticalCenter: parent.verticalCenter }
                    Text { width: Math.min(implicitWidth, parent.parent.width - Theme.px(56)); elide: Text.ElideRight; text: Voice.length(Voice.elapsed) + (page.pad ? "   Release Y to send, B cancels" : "   Recording"); color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                }
            }

            // "Raven is typing" under the last message (cap m1).
            Text {
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
                Text { id: jumpText; anchors.centerIn: parent; text: "New messages · jump"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold }
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: list.positionViewAtIndex(page.newFrom, ListView.Beginning) }
            }

            // Drop pictures (or other files) on the chat to send them.
            DropArea {
                anchors.fill: parent
                enabled: page.openKey !== "" && !page.locked
                onDropped: drop => { if (drop.hasUrls) page.dropFiles(drop.urls); drop.accept() }
                Rectangle {
                    anchors.fill: parent
                    visible: parent.containsDrag
                    color: Qt.rgba(0, 0, 0, 0.55)
                    border.color: Theme.accent; border.width: 2; radius: Theme.radius
                    Text { anchors.centerIn: parent; text: "Drop to send to " + page.openName; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(20) }
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
                Text { text: "MY CODE"; color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2) }
                Text {
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
        if (compose.visible) { zone = "messages"; sel = -1; compose.focusField(); return }
        if (openKey === "") {
            if (!Chat.nearbyOn) { Ui.say("Nearby room is off: Settings › Privacy and lock.", true); return }
            Ui.ask("Message everyone nearby", "You're " + Chat.username, "", "Send", t => Chat.sendNearby(t), false, true)
        } else {
            const k = openKey, re = replyId
            Ui.ask(re !== "" && replyMsg ? "Reply to " + (replyMsg.mine ? "yourself" : openName) : "Message " + openName, re !== "" && replyMsg ? (replyMsg.text || "Photo") : "", "", "Send",
                   t => { Chat.sendPrivate(k, t, re); page.replyId = "" }, false, true)
        }
    }
    // The + beside the title: add someone, show my code, or copy a one-time link.
    function addMenu() {
        if (locked) return
        Ui.pickFrom("Add", [{ header: "", items: [{ name: "Add someone", icon: "plus", url: "add" },
                                                   { name: "My code", icon: "qr", url: "code" },
                                                   { name: "One-time link", icon: "attach", url: "once" }] }], a => {
            if (a === "add") addSomeone()
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
    function addSomeone() {
        Ui.ask("Add someone", "Paste their code or link", "", "Add", t => {
            // A group's code or invite link (cap j1 links work the same way): its name and size, then Join or Not now.
            const gc = Chat.parseGroupCode(t)
            if (gc && gc.name && !gc.mine && !gc.member && !gc.expired) {
                const n = gc.count || gc.members || 0
                Ui.sure("Join " + gc.name + "?", (n ? n + (n === 1 ? " member. " : " members. ") : "") + "They see your name.",
                        "Join", () => { const r = Chat.joinWithCode(t); Ui.say(r === "" ? "Asked to join " + gc.name + "." : r, r !== "") }, false, false)
                Ui.confirm.cancelText = "Not now"
                return
            }
            if (t.indexOf("MUNINNG1:") >= 0) {
                const gc = Chat.parseGroupCode(t)
                const r = Chat.joinWithCode(t)
                if (r === "") Ui.say("Asked to join " + gc.name + ".")
                else Ui.say(r, true)
                return
            }
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
                    else { Chat.addContact(t); added() }
                })
        })
    }
    // A person's one menu (PeopleActions.contactMenu), from their row: Move up and Down follow the
    // section they were opened in.
    function rowMenu(e) {
        if (!e || e.kind !== "chat") { listOptions(); return }
        const k = e.key
        PeopleActions.contactMenu(k, e.name, { section: e.section, keys: sectionKeys(e.section), removed: () => page.closeChatWith(k) })
    }
    // The same menu for the open chat (its header's three dots, or LT with no message picked).
    function chatMenu() {
        if (openKey === "") return
        const k = openKey, e = entries.find(x => x.kind === "chat" && x.key === k)
        PeopleActions.contactMenu(k, openName, { tour: tour, section: e ? e.section : "", keys: e ? sectionKeys(e.section) : [], removed: () => page.closeChatWith(k) })
    }
    function closeChatWith(k) { if (openKey === k) { openKey = ""; picked = false; zone = "list" } }
    function checkCode(k) { PeopleActions.checkCode(k) }
    function startCall() {
        const w = Calls.call(openKey)
        if (w !== "") Ui.say(w, true)
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
            if (f === "") { Ui.say("Give the folder a name.", true); return }
            if (key) Chat.placeIn(key, from || "", f, [key])
        }, false, true)
    }
    // X (or a right click) on a row of the list: a person's menu, a folder's, or the list's own.
    function listOptions() {
        const e = entries[row]
        if (!e) return
        if (e.kind === "chat") { rowMenu(e); return }
        if (e.kind === "folder") { folderOptions(e); return }
        listMenu()
    }
    function folderOptions(e) {
        const id = e.id, ids = Chat.folders.map(f => f.id), at = ids.indexOf(id)
        const items = [{ name: e.collapsed ? "Expand" : "Collapse", url: "fold" }]
        if (at > 0) items.push({ name: "Move up", url: "up" })
        if (at >= 0 && at < ids.length - 1) items.push({ name: "Move down", url: "down" })
        items.push({ name: "Rename", icon: "edit", url: "rename" },
                   { name: "Delete folder", url: "delete", danger: true })
        Ui.pickFrom(e.name, [{ header: "", items: items }], a => {
            if (a === "fold") Chat.setCollapsed(id, !e.collapsed)
            else if (a === "up" || a === "down") { Chat.moveFolder(id, a === "up" ? -1 : 1); focusEntry(x => x.kind === "folder" && x.id === id) }
            else if (a === "rename") Ui.ask("Rename " + e.name, "Only you see it", e.name, "Save", t => Chat.renameFolder(id, t), false, true)
            else if (a === "delete") Ui.sure("Delete " + e.name + "?", "The people in it stay.", "Delete", () => Chat.deleteFolder(id), false, true)
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
                                                 () => { Chat.deleteForGood(k); Ui.say(p.name + " deleted for good.") }, false, true)
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
        case "nearby": openKey = ""; picked = true; zone = "messages"; return
        case "folder": Chat.setCollapsed(e.id, !e.collapsed); return
        case "ginvite":
            if (e.waiting) {
                Ui.sure("Waiting for " + e.fromName, "Stop waiting to join " + e.name + "?", "Stop waiting", () => Chat.declineGroupInvite(e.g), false, false)
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
        case "stranger": Chat.meet(e.key, e.name); openKey = e.key; picked = true; zone = "messages"; return
        case "intro": PeopleActions.answer(e); return
        case "mirror": mirror.open(); return
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
        if (!m || m.unsent && !m.mine) { if (m && openKey !== "") Ui.sure("Delete this message?", "Only on this PC.", "Delete", () => Chat.deleteMessage(openKey, m.id), false, true); return }
        const items = []
        if (openKey !== "" && !m.unsent && !m.failed) {
            items.push({ name: "React", icon: "smile", detail: m.myReaction ? "Yours: " + m.myReaction : "", url: "react" })
            items.push({ name: "Reply", icon: "reply", url: "reply" })
            items.push({ name: "Open thread", icon: "thread", detail: (m.replies || 0) ? m.replies + (m.replies === 1 ? " reply" : " replies") : "", url: "thread" })
        }
        if (openKey !== "" && !m.unsent && !tour) items.push({ name: pins.indexOf(m.id) >= 0 ? "Unpin" : "Pin", icon: "pin", url: "pin" })
        if (openKey !== "" && m.mine && !m.unsent && !m.failed && !m.photo) items.push({ name: "Edit", icon: "edit", url: "edit" })
        if (m.text && !m.unsent) items.push({ name: "Copy text", url: "copy" })
        if (m.file && !m.unsent && !m.mine) items.push({ name: "Save to Downloads", icon: "file", url: "savefile" })
        else if (m.photo && !m.voice && !m.file && !m.unsent) items.push({ name: "View full size", icon: "image", url: "view" }, { name: "Save to Pictures", url: "save" })
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
            else if (a === "copy") { Chat.copyText(m.text); Ui.say("Copied.") }
            else if (a === "view") viewing = m.photo
            else if (a === "save") Ui.say(Chat.savePhoto(m.photo) ? "Saved in Pictures › Huginn." : "Couldn't save it.")
            else if (a === "play") { if (Voice.playing === m.id) Voice.stop(); else Voice.play(m.photo) }
            else if (a === "unsend") Chat.unsend(k, m.id)
            else if (a === "retry") Chat.retry(k, m.id)
            else if (a === "mute") Chat.block(m.peer, true)
            else if (a === "delete") { if (k === "") Chat.deleteNearbyMessage(m.id); else Chat.deleteMessage(k, m.id) }
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
        if (compose.visible) { editId = m.id; zone = "messages"; sel = -1; compose.setText(m.text) }
        else { const k = openKey, id = m.id; Ui.ask("Edit your message", "", m.text, "Save", t => { const r = Chat.editMessage(k, id, t); if (r !== "") Ui.say(r, true) }, false, true) }
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
        Ui.say(on ? "Pinned on this PC." : "Unpinned.")
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
    function boxButton(box, b) {
        switch (b) {
        case "a": if (box.text.trim() !== "") box.enter(); else System.showSteamKeyboard(); return true
        case "y": System.showSteamKeyboard(); return true
        case "x": boxOptions(box); return true
        case "down": return true
        }
        box.leave()
        return b === "b"
    }
    function boxOptions(box) {
        const items = [{ name: "Send", icon: "send", url: "send" }, { name: "Emoji", icon: "smile", url: "emoji" }]
        if (openKey !== "") items.push({ name: "Pictures, video or poll", icon: "image", url: "picture" })
        if (openKey !== "" && Chat.isContact(openKey)) items.push({ name: "Send a file", icon: "attach", url: "file" })
        if (box.bannerTitle !== "") items.push({ name: editId !== "" ? "Stop editing" : "Don't reply", url: "cancel" })
        items.push({ name: "Done typing", url: "leave" })
        Ui.pickFrom(box.placeholder, [{ header: "", items: items }], a => {
            if (a === "send") box.enter()
            else if (a === "emoji") box.toggleEmoji()
            else if (a === "picture") pickPicture()
            else if (a === "file") PeopleActions.sendFile(openKey)
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
            if (side === "thread") threadBox.focusField()
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
        if (reactPicker.visible) return reactPicker.handleButton(b)
        if (compose.emojiShown) return compose.emojiButton(b)
        if (threadBox.emojiShown) return threadBox.emojiButton(b)
        if (typingIn && boxButton(typingIn, b)) return true
        if (zone === "side") return sideButton(b)
        // A big file waiting: the D-pad is on its Accept and Decline (Up past it goes back to the messages).
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
            else if (messages.length > 0) { sel = sel < 0 ? messages.length - 1 : Math.max(0, sel - 1); list.positionViewAtIndex(sel, ListView.Contain) }
            return true
        case "down":
            if (zone === "list") row = Math.min(entries.length - 1, row + 1)
            else if ((sel < 0 || sel === messages.length - 1) && fileStrip.enter()) sel = -1   // on to a file's Accept and Decline
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
        case "a": if (zone === "list") choose(); else write(); return true
        case "x":
            if (zone === "list") listOptions()
            else if (zone === "messages" && sel >= 0) options()
            else if (zone === "messages" && openKey !== "") chatMenu()
            else if (zone === "messages") write()
            return true
        case "y":
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
        if (b === "y" && Voice.recording && openKey !== "") Voice.finish(openKey)
        return true
    }
    // A linked phone's chats, over everything (companion side of linked devices).
    MirrorPanel { id: mirror; anchors.fill: parent; z: 20 }
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
    }
}

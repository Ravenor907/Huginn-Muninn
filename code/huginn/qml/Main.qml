import QtQuick
import QtQuick.Window
import Huginn

// The window: a slim top bar with search, the rail on the left, the open section beside it (its own
// list and main columns), and you with your voice controls at the bottom left. A controller's button
// legend shows along the bottom only after Select (View) is pressed. Overlays sit on top. A portrait
// window shows one column at a time, like Muninn on a phone.
Window {
    id: app
    // A normal window: where it was last time, else 80% of the screen and centered. Full screen only when
    // Settings › Look › Open full screen is on (F11 switches), in game mode, or in Handheld mode.
    // Set once at start (never bound to the saved value, so the window manager stays in charge of where it is).
    width: 1280
    height: 800
    function placeWindow() {
        if (touring) { width = 1920; height = 1080; return }
        // Game mode and Handheld mode: the whole screen from the first frame (game mode shows one window at a time).
        if ((System.gameMode || System.handheld) && !System.windowed) { x = 0; y = 0; width = Screen.width; height = Screen.height; return }
        const r = Settings.windowRect
        const sw = Screen.desktopAvailableWidth, sh = Screen.desktopAvailableHeight
        if (r.width >= 320 && r.height >= 240 && r.width <= sw && r.height <= sh) {
            width = r.width; height = r.height
            if (r.x >= 0 && r.y >= 0 && r.x + 120 <= sw && r.y + 60 <= sh) { x = r.x; y = r.y }
        } else {
            width = Math.round(sw * 0.8); height = Math.round(sh * 0.8)
            x = Math.round((sw - width) / 2); y = Math.round((sh - height) / 2)
        }
    }
    minimumWidth: 480
    minimumHeight: 360
    visible: true
    // Full screen or a window, decided once as it opens (System.startsFullScreen reads no setting that changes later).
    // After that only F11, Settings › Look › Open full screen and Handheld mode switch it (applyWindowMode below):
    // a change to any other setting never touches it.
    visibility: System.startsFullScreen() ? Window.FullScreen : Window.Windowed
    title: "Huginn"
    color: Theme.ground

    readonly property bool touring: System.tourDir.length > 0
    // A controller is being used (its wording and focus rings); a mouse or keyboard turns it off. Handheld mode
    // starts with it on, before the first button.
    property bool padInUse: false
    // The controller's button legend: Select (View) shows it, Select again or any other button hides it. Handheld
    // mode shows it at start.
    property bool legend: false
    function controllerFirst() {
        if (touring) return
        padInUse = System.handheld
        legend = System.handheld && Gamepad.connected
    }
    Connections { target: System; function onHandheldChanged() { app.controllerFirst(); app.applyWindowMode() } }
    // The window's mode as last set: full screen or not. Only a change to what it should be switches it.
    property bool fullNow: System.startsFullScreen()
    function wantFullScreen() { return !System.windowed && (Settings.fullScreen || System.gameMode || System.handheld) }
    function applyWindowMode() {
        const want = wantFullScreen()
        if (want === fullNow) return
        fullNow = want
        app.visibility = want ? Window.FullScreen : Window.Windowed
    }
    Connections { target: Settings; function onChanged() { app.applyWindowMode() } }
    // Taller than wide: one column at a time.
    readonly property bool portrait: height > width
    Binding { target: Ui; property: "portrait"; value: app.portrait }
    Binding { target: Ui; property: "windowWidth"; value: app.width }
    // The Seasonal look's ground and falling leaves or snow, behind every pane (Leaves.qml draws nothing in the other looks).
    Leaves { anchors.fill: parent; z: -1 }

    // ------------------------------------------------------------- the rail
    // Chats, then every group (round initials), + for a new group; at the foot, always on show,
    // Contacts, Files, Settings (the valknut) and the power button (lock or close). Calls live in their chats.
    readonly property var tourGroups: [{ id: "tour-family", name: "Family", unread: 2, mentions: 1 }, { id: "tour-crew", name: "Gaming Crew", unread: 0 },
                                       { id: "tour-folk", name: "Folk", unread: 3 }]
    readonly property var groupList: touring ? tourGroups : Vault.screenLocked ? [] : Chat.groups
    readonly property var railItems: [
        { id: "dms", kind: "raven", label: "Chats", badge: Vault.screenLocked ? 0 : Chat.unreadCount + Chat.requests.length + Chat.groupInvites.filter(i => !i.waiting).length }
    // A group: its @mentions of me as a number, and a dot for anything else unread (like Discord).
    ].concat(groupList.map(g => ({ id: "g:" + g.id, kind: "group", label: g.name, letter: g.letter || String(g.name || "?").trim().charAt(0).toUpperCase() || "?",
                                   badge: g.mentions || 0, unread: g.unread || 0 })))
     .concat([
        { id: "newgroup", kind: "add", label: "New group", action: true },
        { id: "contacts", kind: "icon", icon: "person", label: "Contacts", bottom: true },
        { id: "files", kind: "icon", icon: "folder", label: "Files", bottom: true, badge: LocalSend.request.active ? 1 : 0 },
        { id: "settings", kind: "icon", icon: "gear", label: "Settings", bottom: true },
        // Lock Huginn or close it (a small menu).
        { id: "exit", kind: "icon", icon: "power", label: "Lock or close", bottom: true, action: true }
    ])
    // LB steps through the sections and groups (New group and the power menu are reached with the D-pad).
    readonly property var cycle: railItems.filter(e => !e.action).map(e => e.id)

    // ------------------------------------------------------------- pages
    property string pageName: "dms"
    property var pageArgs: ({})
    property string focusZone: "content"     // "rail" or "content"
    property int railIndex: 0
    // The rail item for the page on screen.
    readonly property string section: pageName

    function openPage(name, args) {
        if (name === "chat") name = "dms"
        pageArgs = args || {}
        focusZone = "content"
        if (name === pageName) {
            if (pageLoader.item && pageLoader.item.setArgs) pageLoader.item.setArgs(pageArgs)
            return
        }
        const groupToGroup = name.startsWith("g:") && pageName.startsWith("g:")
        pageName = name
        // From one group to another the page stays loaded: hand it the new arguments.
        if (groupToGroup && pageLoader.item && pageLoader.item.setArgs) pageLoader.item.setArgs(pageArgs)
    }
    function openRail(index) {
        const e = railItems[index]
        if (!e) return
        railIndex = index
        if (e.id === "exit") { rail.openPowerMenu(); return }
        if (e.id === "newgroup") { newGroup(); return }
        openPage(e.id, {})
    }
    function newGroup() {
        if (!Vault.exists) { Ui.say("Set a lock first.", true); openPage("dms", {}); return }
        if (Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); openPage("dms", {}); return }
        // Two types: Folk and Family, each a card with its one line; "What's the difference?" shows the rules.
        const types = Chat.groupTypes()
        Ui.askWith("New group", "Everyone in it sees the name", "", "Make the group",
                   types.map(t => ({ value: t.type, label: t.name, detail: t.line })), "folk",
                   "Type", (t, type) => {
            const g = Chat.createGroup(t, type)
            if (g === "") { Ui.say(Chat.saveFailed() ? "Couldn't save. Try again." : Chat.ready ? "Give the group a name." : "Unlock Huginn first.", true); return }
            Ui.say("Made " + t + ".")
            openPage("g:" + g, {})
        })
        prompt.moreLabel = "What's the difference?"
        prompt.moreText = types.map(t => t.name + "\n" + t.rules.map(r => "· " + r).join("\n")).join("\n\n")
        prompt.label = "Group name"
    }
    // A person dragged with the mouse: the group in the rail under a point of the window ("" when none), and its outline.
    function railGroupAt(wx, wy) { const id = rail.groupAt(wx, wy); return id.indexOf("g:") === 0 ? id.substring(2) : "" }
    function railDropHint(g) { rail.dropGroup = g !== "" ? "g:" + g : "" }
    function stepSection(dir) {
        const i = cycle.indexOf(section)
        openPage(cycle[(i + dir + cycle.length) % cycle.length], {})
        railIndex = railItems.findIndex(e => e.id === section)
    }
    function focusRail() {
        railIndex = Math.max(0, railItems.findIndex(e => e.id === section))
        focusZone = "rail"
        keys.forceActiveFocus()
    }
    // Back out of a page: to the rail.
    function closePage() {
        focusRail()
        keys.forceActiveFocus()
    }
    function restoreFocus() { keys.forceActiveFocus() }
    // The lock screen goes up (the power menu, or Lock in your card); messages still arrive behind it.
    function lockNow() {
        if (!Vault.hasLock || Vault.screenLocked) return
        if (meCard.visible) meCard.close()
        Vault.lockScreen()
        if (!Vault.screenLocked) return   // nothing locked: no toast
        openPage("dms", {})
        Ui.say("Huginn locked.")
    }
    // Every Exit goes through here (the window's X, Exit, B or Esc at the top), so a transfer is never cut off by
    // surprise. Huginn never locks on its own: closing asks. Lock and close (A) quits, and Huginn opens locked next
    // time; Minimize (X) keeps it running out of sight, still getting messages and calls; Stay (B).
    function requestExit() {
        const busy = LocalSend.busy
        const locks = Vault.hasLock && !Vault.screenLocked
        const why = busy ? LocalSend.status + " Closing now stops it partway." : "Minimized, messages and calls still arrive."
        Ui.sure("Leave Huginn?", why, locks ? "Lock and close" : "Close", () => { if (locks) Vault.lockScreen(); app.quitNow() }, busy, busy)
        confirm.otherText = "Minimize"
        confirm.otherCallback = () => System.minimize(app)
        confirm.cancelText = "Stay"
    }
    // Answered: Huginn closes (the window's close question isn't asked again).
    property bool quitting: false
    function quitNow() { quitting = true; Qt.quit() }
    // The window's X (or Alt+F4) asks first, like Exit. Not while an update restarts Huginn, or in the screenshot tour.
    onClosing: close => {
        if (app.quitting || app.touring || Updater.installing) return
        close.accepted = false
        app.requestExit()
    }

    // The slim bar along the top: HUGINN, search (Ctrl+K) and the shortcuts (Ctrl+/).
    TopBar {
        id: topBar
        width: parent.width
        onSearch: if (!app.touring) switcher.open()
        onShortcuts: shortcutsPanel.open()
        onTransfers: transfersPanel.open(app.touring)
    }

    Rail {
        id: rail
        z: 4   // its labels show over the page
        // Portrait: only beside the list, not over an open conversation.
        visible: !app.portrait || app.pageList
        anchors.top: topBar.bottom
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        anchors.bottomMargin: voicePanel.visible ? voicePanel.height + Theme.px(16) : 0
        anchors.left: parent.left
        items: app.railItems
        current: app.section
        focusIndex: app.railIndex
        focused: app.focusZone === "rail"
        onActivated: index => app.openRail(index)
        onLockRequested: app.lockNow()
        onExitRequested: app.requestExit()
    }
    // The power menu is open: a click anywhere else closes it.
    MouseArea {
        anchors.fill: parent
        z: 3.5
        visible: rail.menuOpen
        acceptedButtons: Qt.AllButtons
        onClicked: rail.closeMenu()
    }

    Loader {
        id: pageLoader
        anchors.top: topBar.bottom
        anchors.bottom: roomBar.visible ? roomBar.top : hints.visible ? hints.top : parent.bottom
        anchors.left: rail.visible ? rail.right : parent.left
        anchors.right: parent.right
        source: {
            if (app.pageName.startsWith("g:")) return "GroupPage.qml"
            switch (app.pageName) {
            case "dms": return "ChatPage.qml"
            case "files": return "FilesPage.qml"
            case "settings": return "SettingsPage.qml"
            case "contacts": return "ContactsPage.qml"
            }
            return "ChatPage.qml"
        }
        onLoaded: if (item.setArgs) item.setArgs(app.pageArgs)
    }

    // In a voice room or group call: who's in it and the controls, under every page.
    RoomBar {
        id: roomBar
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        anchors.left: voicePanel.visible ? voicePanel.right : rail.visible ? rail.right : parent.left
        anchors.right: parent.right
        // On the desktop the voice panel has the controls; a shared screen still shows above here.
        docked: voicePanel.visible
    }

    // ------------------------------------------------------------- the desktop panels
    // The page's own list column shows (ChatPage and GroupPage can hide theirs), and a group's member list.
    readonly property bool pageList: { const p = pageLoader.item; return !(p && p.listHidden === true) }
    readonly property bool pageMembers: { const p = pageLoader.item; return !!(p && p.membersVisible === true) }
    property bool tourVoice: false
    // You and your voice controls, at the bottom left under the rail and the list (wide windows).
    VoicePanel {
        id: voicePanel
        tour: app.tourVoice
        roomBar: roomBar
        cardOpen: meCard.visible
        onMeClicked: meCard.toggle()
        visible: app.pageList
        anchors.left: parent.left; anchors.leftMargin: Theme.px(8)
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        anchors.bottomMargin: Theme.px(8)
        width: rail.width + Theme.listWidth - Theme.px(16)
        z: 2
    }
    Binding { target: Ui; property: "dock"; value: voicePanel.visible ? voicePanel.height + Theme.px(16) : 0 }
    // Your card (a click on your name in the panel): your status. Name, picture, color and code are in Settings › You.
    MeCard { id: meCard; z: 24; above: voicePanel }
    // Drag the edges between the list, the conversation and the member list. The list handle sits on
    // the edge of the list the page shows (a page says where with listEdge; one whose list can't be
    // resized says so with listResizable: false).
    readonly property int pageListEdge: { const p = pageLoader.item; return p && p.listEdge !== undefined ? p.listEdge : Theme.listWidth }
    readonly property bool pageListResizable: { const p = pageLoader.item; return !(p && p.listResizable === false) }
    Splitter {
        anchors.top: topBar.bottom; anchors.bottom: pageLoader.bottom
        anchors.bottomMargin: Ui.dock
        x: rail.width + app.pageListEdge - width / 2
        z: 3
        visible: !app.portrait && app.pageList && app.pageListResizable
        label: "list"
        value: Ui.listW; minimum: 200; maximum: 360; fallback: 240
        onMoved: v => Ui.listW = v
        onDone: v => Settings.listWidth = v
    }
    Splitter {
        anchors.top: topBar.bottom; anchors.bottom: pageLoader.bottom
        x: app.width - Theme.memberWidth - width / 2
        z: 3
        visible: !app.portrait && app.pageMembers
        label: "member list"
        direction: -1
        value: Ui.memberW; minimum: 200; maximum: 360; fallback: 240
        onMoved: v => Ui.memberW = v
        onDone: v => Settings.memberWidth = v
    }
    // Show or hide the list beside the rail ("list") or a group's member list ("members"). The
    // controller's focus leaves a panel that's hidden.
    function togglePane(which) {
        if (which === "list") Settings.showList = !Settings.showList
        else Settings.showMembers = !Settings.showMembers
        const p = page()
        if (!p || p.zone === undefined) return
        if (which === "list" && !Settings.showList && (p.zone === "channels" || p.zone === "list")) p.zone = "messages"
        if (which === "members" && !Settings.showMembers && p.zone === "members") p.zone = "messages"
    }

    // ------------------------------------------------------------- your status
    // Do not disturb: no message sound, notification or ring (Settings.quiet is read by main.cpp and the ring).
    Binding { target: Settings; property: "quiet"; value: Ui.myStatus === "dnd" }
    // Away by itself after 10 minutes without a key, mouse or controller press while Online; back
    // to Online at the first press after. A status picked by hand on the card stays as picked.
    property real lastInput: Date.now()
    property bool autoAway: false
    readonly property int awayAfterMs: 600000
    function noteInput() {
        lastInput = Date.now()
        if (autoAway && Profile.myStatus === "away") { autoAway = false; Profile.setStatus("online") }
    }
    Timer {
        interval: 30000; running: !app.touring; repeat: true
        onTriggered: {
            if (Profile.myStatus !== "online" || Calls.inCall || Rooms.inRoom) return
            if (Date.now() - app.lastInput >= app.awayAfterMs) { app.autoAway = true; Profile.setStatus("away") }
        }
    }

    // ------------------------------------------------------------- who's online (member list, profile cards)
    // Active status (cap m1) says who has Huginn or Muninn open now; idle is someone seen in the last
    // half hour who isn't any more.
    property var lastActive: ({})
    property real clock: Date.now()
    Timer { interval: 60000; running: true; repeat: true; onTriggered: app.clock = Date.now() }
    function presenceOf(key) {
        if (!key || Vault.screenLocked) return "offline"
        if (Chat.active[key] !== undefined) return "online"
        const t = lastActive[key]
        return t !== undefined && clock - t < 1800000 ? "idle" : "offline"
    }

    // A controller's buttons and what they do, along the bottom: hidden until Select (View) is
    // pressed. It also shows sending and receiving progress while files move.
    PromptBar {
        id: hints
        anchors.bottom: parent.bottom
        z: callPage.visible || incomingCall.visible || moveCover.visible || linkPhone.visible || setup.visible || backupPanel.visible ? 38 : 0   // a call's, a move's, Link your phone's or first setup's buttons stay on show over it
        width: parent.width
        visible: (app.legend && Gamepad.connected) || LocalSend.busy
        showKeys: app.legend && Gamepad.connected
        status: incomingCall.visible ? incomingCall.status : callPage.visible ? callPage.status
              : app.focusZone === "rail" ? "" : pageLoader.item && pageLoader.item.status !== undefined ? pageLoader.item.status : ""
        prompts: {
            if (incomingCall.visible) return incomingCall.prompts
            if (callPage.visible) return callPage.prompts
            if (moveCover.visible && !prompt.visible && !confirm.visible) return moveCover.prompts.concat([{ key: "back", label: "Hide buttons" }])
            if (linkPhone.visible && !prompt.visible && !confirm.visible) return linkPhone.prompts.concat([{ key: "back", label: "Hide buttons" }])
            if (backupPanel.visible && !prompt.visible && !confirm.visible) return backupPanel.prompts.concat([{ key: "back", label: "Hide buttons" }])
            if (setup.visible && !prompt.visible && !confirm.visible) return setup.prompts.concat([{ key: "back", label: "Hide buttons" }])
            const lb = [{ key: "lb", label: "Next section" }]
            // Select only ever shows or hides this legend (Main.press), and Menu always opens Settings.
            const hide = [{ key: "start", label: "Settings" }, { key: "back", label: "Hide buttons" }]
            if (app.focusZone === "rail")
                return lb.concat([{ key: "a", label: "Open" }, { key: "b", label: "Exit" }], hide)
            const p = pageLoader.item && pageLoader.item.prompts ? pageLoader.item.prompts.filter(x => x.key !== "back" && x.key !== "start") : []
            return lb.concat(p, hide)
        }
        // A tap on a chip is a whole press: LB here can't be held, so it just goes to the next section.
        onPrompt: k => k === "lb" && !app.overlay() ? app.stepSection(1) : app.press(k)
    }

    // ------------------------------------------------------------- overlays
    ListPicker { id: picker; z: 25 }
    // The right-click menu (Ui.menuAt), over everything a right click can reach.
    RightClickMenu { id: ctxMenu }
    // Ctrl+K (desktop): jump to any chat or channel by typing part of its name.
    QuickSwitcher { id: switcher; z: 26 }
    Shortcut {
        sequence: "Ctrl+K"
        context: Qt.ApplicationShortcut
        enabled: !app.touring && !updating.visible && !setup.visible
        onActivated: switcher.visible ? switcher.close() : switcher.open()
    }
    // The rest of the keyboard shortcuts (all listed in ShortcutsPanel.qml).
    readonly property bool keysOn: !app.touring && !updating.visible && !setup.visible
    Shortcut { sequence: "F11"; context: Qt.ApplicationShortcut; enabled: !System.windowed && !System.gameMode && !System.handheld; onActivated: Settings.fullScreen = !Settings.fullScreen }
    // The window's place and size, kept a moment after it stops moving (only while it's a normal window).
    Timer { id: keepRect; interval: 800; onTriggered: if (app.placed && app.visibility === Window.Windowed && !app.touring) Settings.windowRect = Qt.rect(app.x, app.y, app.width, app.height) }
    property bool placed: false
    onXChanged: keepRect.restart()
    onYChanged: keepRect.restart()
    onWidthChanged: keepRect.restart()
    onHeightChanged: keepRect.restart()
    Shortcut { sequence: "Ctrl+/"; context: Qt.ApplicationShortcut; enabled: app.keysOn
               onActivated: shortcutsPanel.visible ? shortcutsPanel.close() : shortcutsPanel.open() }
    Shortcut { sequence: "Ctrl+Shift+L"; context: Qt.ApplicationShortcut; enabled: app.keysOn && !app.portrait; onActivated: app.togglePane("list") }
    Shortcut { sequence: "Ctrl+Shift+U"; context: Qt.ApplicationShortcut; enabled: app.keysOn && !app.portrait; onActivated: app.togglePane("members") }
    // Panic lock from the keyboard (a controller holds L3 and R3).
    Shortcut { sequence: "Ctrl+Shift+Q"; context: Qt.ApplicationShortcut; enabled: app.keysOn && Vault.panicLock; onActivated: Vault.panic() }
    Shortcut { sequence: "Ctrl+Shift+M"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: Ui.toggleMic() }
    Shortcut { sequence: "Ctrl+Shift+D"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: Ui.toggleDeaf() }
    Shortcut { sequence: "Ctrl+Shift+H"; context: Qt.ApplicationShortcut; enabled: app.keysOn && Rooms.inRoom; onActivated: Rooms.leave() }
    Shortcut { sequences: ["Ctrl+=", "Ctrl++"]; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.stepSize(1) }
    Shortcut { sequence: "Ctrl+-"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.stepSize(-1) }
    Shortcut { sequence: "Ctrl+0"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.stepSize(0) }
    Shortcut { sequence: "Ctrl+,"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.openPage("settings", {}) }
    function showShortcuts() { shortcutsPanel.open() }
    // The Menu key and Shift+F10: the right-click menu for what has the focus, where it is (a text box: Cut, Copy,
    // Paste, Select all at its cursor; a rail button; the page's current row; else the page's own menu).
    Shortcut { sequences: ["Menu", "Shift+F10"]; context: Qt.ApplicationShortcut; enabled: !app.touring && !updating.visible; onActivated: app.menuKey() }
    function menuKey() {
        if (ctxMenu.visible) { ctxMenu.close(); return }
        const f = app.activeFocusItem
        if (ctxMenu.isField(f)) { ctxMenu.forField(f, -1, -1, true); return }
        if (overlay() || setup.visible) return
        if (focusZone === "rail") {
            const e = railItems[railIndex], r = e ? rail.spotRect(e.id) : null
            if (e) railMenu(e, null, r ? r.x + r.width - Theme.px(8) : rail.width - Theme.px(8), r ? r.y + r.height / 2 : Theme.px(28), true)
            return
        }
        const p = page()
        const a = p && typeof p.menuAnchor === "function" ? p.menuAnchor() : null
        const item = a || pageLoader
        const x = a ? Math.min(a.width / 2, Theme.px(140)) : pageLoader.width / 2
        const y = a ? a.height / 2 : pageLoader.height / 3
        ctxMenu.openAt(item, x, y, () => { if (p && typeof p.contextMenu === "function") p.contextMenu(); else press("x") }, true)
    }
    // A rail button's right-click menu: a group's (PeopleActions.groupRowMenu) or a short one for each section.
    function railMenu(e, item, x, y, viaKey) {
        if (!e) return
        ctxMenu.openAt(item, x, y, () => {
            if (e.kind === "group") { PeopleActions.groupRowMenu(String(e.id).substring(2), e.label, { tour: app.touring }); return }
            const later = f => Qt.callLater(() => { const p = page(); if (p && typeof p[f] === "function") p[f]() })
            let items = []
            if (e.id === "dms") items = [{ name: "Open", icon: "thread", url: "open" }, { name: "New chat", icon: "plus", url: "chat" },
                                         { name: "New group", icon: "people", url: "group" }, { name: "New folder", icon: "folder", url: "folder" }]
            else if (e.id === "newgroup") items = [{ name: "New group", icon: "people", url: "group" }, { name: "Join with a code", icon: "qr", url: "chat" }]
            else if (e.id === "contacts") items = [{ name: "Open", icon: "person", url: "open" }, { name: "Add someone", icon: "plus", url: "chat" },
                                                   { name: "One-time link", icon: "attach", url: "once" }]
            else if (e.id === "files") items = [{ name: "Open", icon: "folder", url: "open" }, { name: "Look again", icon: "search", url: "look" }]
            else if (e.id === "settings") items = [{ name: "Look", icon: "image", url: "s:look" }, { name: "Notifications", icon: "bell", url: "s:notify" },
                                                   { name: "Privacy and lock", icon: "lock", url: "s:privacy" }, { name: "Devices", icon: "phone", url: "s:devices" },
                                                   { name: "Keyboard shortcuts", icon: "keyboard", url: "keys" }]
            else if (e.id === "exit") items = rail.menuItems.map(m => ({ name: m.label, icon: m.icon, url: m.id, danger: !!m.danger }))
            Ui.pickFrom(e.label || "", [{ header: "", items: items }], a => {
                if (app.touring) return
                if (a === "open") openPage(e.id, {})
                else if (a === "group") newGroup()
                else if (a === "chat") { openPage("dms", {}); later("addSomeone") }
                else if (a === "folder") { openPage("dms", {}); later("newFolder") }
                else if (a === "once") PeopleActions.inviteLink()
                else if (a === "look") { openPage("files", {}); LocalSend.refresh() }
                else if (a.indexOf("s:") === 0) openPage("settings", { section: a.substring(2) })
                else if (a === "keys") shortcutsPanel.open()
                else if (a === "lock" || a === "exit") rail.runMenu(a)
            })
        }, !!viaKey)
    }
    // The screenshot tour's right-click menus (nothing they offer is done in the tour).
    function tourMenu(what) {
        const p = page()
        const at = (x, y, f) => ctxMenu.openAt(null, x, y, f, false)
        const left = rail.width + Theme.px(140)
        if (what === "person") {
            at(left, Theme.px(160), () => PeopleActions.personMenu("tour", "Mira", { tour: true }))
            ctxMenu.openSubNamed("Mute")
        } else if (what === "group") {
            const r = rail.spotRect("g:tour-crew")
            at(r ? r.x + r.width / 2 : Theme.px(36), r ? r.y + r.height / 2 : Theme.px(140), () => PeopleActions.groupRowMenu("tour-crew", "Gaming Crew", { tour: true }))
        } else if (what === "folder" && p && typeof p.folderOptions === "function")
            at(left, Theme.px(140), () => p.folderOptions({ id: "tour-family", name: "Family", collapsed: false }))
        else if (what === "message" && p && typeof p.messageOptions === "function" && p.messages.length) {
            p.sel = p.messages.length - 1
            at(app.width * 0.55, app.height * 0.6, () => p.messageOptions())
        } else if (what === "member" && p && p.members) {
            const m = p.members.find(x => x.key === "s") || p.members.find(x => !x.me)
            if (m) at(app.width - Theme.px(200), Theme.px(220), () => PeopleActions.memberMenu(m, { group: p.gid, host: p, member: m, tour: true }))
        } else if (what === "settings") {
            const e = railItems.find(x => x.id === "settings"), r = rail.spotRect("settings")
            railMenu(e, null, r ? r.x + r.width / 2 : Theme.px(36), r ? r.y + r.height / 2 : app.height - Theme.px(120), false)
        } else if (what === "field") {
            prompt.open("Rename Family", "Only you see it", "Family", "Save", null, false, true)
            const f = app.activeFocusItem
            if (ctxMenu.isField(f)) { f.selectAll(); ctxMenu.forField(f, -1, -1, true) }
        } else if (what === "space" && p && typeof p.spaceMenu === "function")
            at(left, app.height - Theme.px(220), () => p.spaceMenu(false))
    }
    // Size of everything, like a web browser's zoom; saved at once.
    readonly property var sizes: [50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200]
    function stepSize(dir) {
        const now = Number(Settings.uiSize) || 100
        let next = 100
        if (dir > 0) next = sizes.find(v => v > now) || 200
        else if (dir < 0) next = sizes.slice().reverse().find(v => v < now) || 50
        Settings.uiSize = String(next)
        Ui.say("Size: " + next + "%")
    }
    // A click on a name: their profile card (under the lists it can open).
    ProfileCard { id: profileCard; z: 24 }
    // The bigger sub-pages of a contact's menu: Edit, and What they can do.
    PersonPanel { id: personPanel; z: 24 }
    // Ctrl+/: every keyboard shortcut.
    ShortcutsPanel { id: shortcutsPanel; z: 27 }
    // Every file coming or going (the top bar's Transfers), and a chat's Files (cap f2).
    TransfersPanel { id: transfersPanel; z: 27 }
    ChatFiles { id: chatFiles; z: 27 }
    function openFiles(where, title) { chatFiles.open(where, title, app.touring) }
    // Labs: Report a bug (its screenshot is taken first, so the report screen isn't in it), and on the Huginn
    // with the Labs key, Labs reports.
    LabsReport { id: labsReport; z: 28 }
    LabsInbox { id: labsInbox; z: 28 }
    // Labs › App storage: files Huginn keeps that can go.
    AppStoragePanel { id: appStorage; z: 28 }
    // Labs › Performance check (under Report a bug, which it can open with its report).
    PerfPanel { id: perfPanel; z: 27.5 }
    function openReport() {
        Labs.capture(app)
        labsReport.open(app.touring)
    }
    // Report a bug with a performance report going along (the person can switch it off there).
    function openReportWith(perfText) {
        Labs.capture(app)
        labsReport.openWith(perfText)
    }
    function openPerf() { perfPanel.open(app.touring) }
    function openLabsReports() { labsInbox.open(app.touring) }
    function openAppStorage() { appStorage.open(app.touring) }
    TextPrompt { id: prompt; z: 30 }
    CallPage { id: callPage; z: 36 }
    IncomingCall { id: incomingCall; z: 37 }
    ConfirmDialog { id: confirm; z: 40 }
    Toast { id: toast }
    // Moving to another device (cap x1): over the pages and panels while it's under way; questions, prompts,
    // calls and toasts show above it. Panels that were open close.
    MoveCover {
        id: moveCover
        anchors.fill: parent
        onVisibleChanged: if (visible) app.closePanels()
    }
    // Link your phone (linked devices): the QR code, the 4 emoji and Linked, over the whole window.
    LinkPhone { id: linkPhone; z: 28.5 }
    // sample: first setup's practice shows the screen with a sample code (nothing is linked or made).
    function showLinkPhone(first, sample) {
        if (sample) { if (!linkPhone.visible) linkPhone.open(first, "code"); return }
        if (app.touring || linkPhone.visible || moveCover.visible || Vault.screenLocked) return
        app.closePanels()
        linkPhone.open(first)
    }
    // First setup on a fresh install (and Labs › Replay first setup, practice: nothing is saved). Under Link your phone,
    // questions, calls and toasts; over the pages and panels.
    FirstSetup { id: setup; z: 28.3 }
    // Settings › Backup and first setup's Restore from a backup (over first setup; questions show above it).
    BackupPanel { id: backupPanel; z: 28.6 }
    function openBackup(mode, fromSetup, sample) {
        if (backupPanel.visible || (app.touring && !sample)) return
        if (!fromSetup) app.closePanels()
        backupPanel.open(mode, !!fromSetup, !!sample)
    }
    function openSetup(practice) {
        if (app.touring || setup.visible) return
        app.closePanels()
        setup.open(!!practice)
    }
    // Runs by itself only on a fresh install (no lock or chats yet), or to finish one that was started; never over the lock.
    property bool setupWaits: false
    function setupAtStart() {
        if (app.touring || Settings.setupState === "done") return
        if (!Vault.exists) { openSetup(false); return }
        if (Settings.setupState !== "started") return
        if (Vault.screenLocked) setupWaits = true
        else openSetup(false)
    }
    Connections {
        target: Vault
        function onChanged() { if (app.setupWaits && !Vault.screenLocked) { app.setupWaits = false; app.openSetup(false) } }
    }
    // The tour's stops on the real screen, in window coordinates ({x, y, width, height}), or null when not on show.
    function tourSpot(id) {
        const left = rail.visible ? rail.width : 0, top = topBar.height
        if (id === "groups" || id === "settings") return rail.spotRect(id)
        if (id === "chats") return app.pageList ? { x: left, y: top, width: app.pageListEdge, height: Math.max(0, pageLoader.height - Ui.dock) } : null
        if (id === "add") return app.pageList ? { x: left + app.pageListEdge - Theme.px(80), y: top, width: Theme.px(80), height: Theme.headerHeight } : null
        if (id === "attach") return { x: left + (app.pageList ? app.pageListEdge : 0), y: pageLoader.y + pageLoader.height - Theme.px(60), width: Theme.px(60), height: Theme.px(60) }
        if (id === "card") {
            if (!voicePanel.visible) return null
            const p = voicePanel.mapToItem(null, 0, 0)
            return { x: p.x, y: p.y, width: voicePanel.width, height: voicePanel.height }
        }
        return null
    }
    // keepReport: Report a bug stays (the panic lock): it hides behind its own lock, and what was written stays.
    function closePanels(keepReport) {
        if (picker.visible) picker.close()
        if (ctxMenu.visible) ctxMenu.close()
        if (switcher.visible) switcher.close()
        if (profileCard.visible) profileCard.close()
        if (personPanel.visible) personPanel.close()
        if (shortcutsPanel.visible) shortcutsPanel.close()
        if (transfersPanel.visible) transfersPanel.close()
        if (chatFiles.visible) chatFiles.close()
        if (labsReport.visible && !keepReport) labsReport.close()
        if (labsInbox.visible) labsInbox.close()
        if (appStorage.visible) appStorage.close()
        if (perfPanel.visible) perfPanel.close()
        if (meCard.visible) meCard.close()
        if (rail.menuOpen) rail.closeMenu()
        if (linkPhone.visible) linkPhone.close()
        if (backupPanel.visible) backupPanel.close()
    }
    // Linked devices: always says where this computer's chats are mirrored.
    Rectangle {
        readonly property var on: Linked.companions.filter(c => c.ok)
        visible: on.length > 0
        anchors { right: parent.right; bottom: parent.bottom; margins: Theme.px(10) }
        z: 30
        radius: Theme.radius
        color: Theme.raised
        border.color: Theme.lineMuted
        width: Math.min(mirroredText.implicitWidth + Theme.px(24), parent.width / 2)
        height: mirroredText.implicitHeight + Theme.px(12)
        Text {
            textFormat: Text.PlainText
            id: mirroredText
            anchors.centerIn: parent
            width: Math.min(implicitWidth, parent.width - Theme.px(24))
            elide: Text.ElideRight
            color: Theme.textSoft
            font.family: Theme.body
            font.pixelSize: Theme.fs(14)
            text: "Mirrored to " + parent.on.map(c => c.name).join(", ")
        }
    }
    // The right stick's mouse pointer, over everything.
    StickPointer {
        id: stick
        z: 1000
        host: app
        onClicked: (x, y) => { if (app.focusZone === "rail" && x > rail.width) app.focusZone = "content" }
    }

    // A checked update waiting: a slim bar along the top; Huginn installs it only on Restart now.
    Rectangle {
        id: updateBar
        anchors.top: parent.top; anchors.horizontalCenter: parent.horizontalCenter
        anchors.topMargin: Theme.px(8)
        z: 55
        visible: Updater.ready && !Updater.installing && !app.touring
        width: barRow.implicitWidth + Theme.px(24); height: barRow.implicitHeight + Theme.px(12)
        color: Theme.panel
        border.color: Theme.accent; border.width: Math.max(1, Theme.px(1))
        Row {
            id: barRow
            anchors.centerIn: parent
            spacing: Theme.px(12)
            Text {
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                text: "Huginn " + Updater.latest + " is ready"
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
            HButton { text: "Restart now"; kind: "primary"; onClicked: Updater.restartToUpdate() }
        }
    }

    // Some saved chat files couldn't be read this run: nothing is saved over them, and the screen says so (an empty
    // list here is not a loss). Try again starts Huginn again once they read.
    Rectangle {
        id: unreadableBar
        anchors.top: parent.top; anchors.horizontalCenter: parent.horizontalCenter
        anchors.topMargin: Theme.px(updateBar.visible ? 8 + 12 : 8) + (updateBar.visible ? updateBar.height : 0)
        z: 55
        visible: Vault.unreadable && !app.touring
        width: Math.min(app.width - Theme.px(24), unreadableRow.implicitWidth + Theme.px(24))
        height: unreadableRow.implicitHeight + Theme.px(12)
        color: Theme.panel
        border.color: Theme.danger; border.width: Math.max(1, Theme.px(1))
        Row {
            id: unreadableRow
            anchors.centerIn: parent
            spacing: Theme.px(12)
            Text {
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                width: Math.min(implicitWidth, app.width - Theme.px(200))
                wrapMode: Text.Wrap
                text: "Some saved chats couldn't be read. Nothing was changed or erased."
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
            HButton {
                text: "Try again"; kind: "primary"
                onClicked: if (!Vault.tryReadAgain()) Ui.say("Still can't read them. Close other programs using Huginn's folder, then try again.", true)
            }
        }
    }

    // Shown while an update installs; Huginn restarts on the new version after.
    Rectangle {
        id: updating
        anchors.fill: parent
        z: 100
        visible: Updater.installing
        color: Theme.ground
        MouseArea { anchors.fill: parent }
        Column {
            anchors.centerIn: parent
            spacing: Theme.px(16)
            Image {
                anchors.horizontalCenter: parent.horizontalCenter
                source: "assets/raven-gold.png"
                width: Theme.px(96); height: width
                sourceSize.width: 256; sourceSize.height: 256
            }
            Text {
                textFormat: Text.PlainText
                anchors.horizontalCenter: parent.horizontalCenter
                width: app.width - Theme.px(32)
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: "UPDATING HUGINN TO " + Updater.latest.toUpperCase()
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: Theme.fs(24)
                font.weight: Font.Bold
                font.letterSpacing: Theme.px(3)
            }
            Text {
                textFormat: Text.PlainText
                anchors.horizontalCenter: parent.horizontalCenter
                width: app.width - Theme.px(32)
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: Updater.status
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.fs(18)
            }
        }
    }

    Component.onCompleted: {
        placeWindow(); placed = true
        controllerFirst()
        Ui.main = app
        Ui.prompt = prompt
        Ui.confirm = confirm
        Ui.toast = toast
        Ui.picker = picker
        Ui.menu = ctxMenu
        Ui.profile = profileCard
        Ui.person = personPanel
        if (touring) tour.start()
        // Labs › Performance check: settings being tried kept Huginn from starting last time.
        else if (Perf.droppedNow) Qt.callLater(() => Ui.say("Tried settings didn't start. Back to Automatic.", true))
        if (!touring) Qt.callLater(app.setupAtStart)
    }

    // ------------------------------------------------------------- nearby sharing (LocalSend)
    // Someone wants to send files: ask on screen, wherever the person is in the app. If another
    // question is already open, wait for it to close instead of replacing it.
    function showSendRequest() {
        const r = LocalSend.request
        if (!r.active) {
            if (confirm.visible && confirm.tag === "localsend") confirm.dismiss()
            return
        }
        if (confirm.visible) {
            if (confirm.tag !== "localsend") Ui.say(r.from + " wants to send you files. Answer this first.")
            return
        }
        confirm.open(r.from + " wants to send " + (r.count === 1 ? "a file" : r.count + " files"),
                     r.names + " (" + r.sizeText + "). They'll be saved in " + (r.folder || Transfer.saveName) + ".",
                     "Accept", () => LocalSend.accept(), false, false, () => LocalSend.decline(), "localsend")
        confirm.cancelText = "Decline"
    }
    // Chat: notices, and a toast for a message unless that chat is on screen (main.cpp plays the sound).
    Connections {
        target: Chat
        function onNotice(text) { Ui.say(text) }
        function onLiveChanged() {
            const a = Chat.active, t = Date.now(), seen = Object.assign({}, app.lastActive)
            for (const k in a) seen[k] = t
            app.lastActive = seen
            app.clock = t
        }
        // A missed call's notification was clicked: its chat (a contact's key, or "g:<group>").
        function onShowChat(where) {
            if (Vault.screenLocked) { app.openPage("dms", {}); return }
            if (where.startsWith("g:")) app.openPage(where, {})
            else app.openPage("dms", { open: where })
        }
        function onArrived(name, text, key) {
            if (Chat.openChat === key && (app.pageName === "dms" || app.pageName.startsWith("g:"))) return
            // Muted, hidden or reduced (Chat.notifyFor): no toast either.
            if (!Chat.alerted(key)) return
            // An @mention always says so (without the text unless previews are on).
            const mention = name.indexOf(" mentioned you in ") > 0
            const hidden = Vault.screenLocked
            Ui.say(Chat.showPreview && !hidden ? name + ": " + text : mention && !hidden ? name + "." : "New message.")
        }
    }
    // Calls: how a call ended, as a toast.
    Connections {
        target: Calls
        function onFinished(t) { Ui.say(t) }
    }
    Connections {
        target: Rooms
        function onFinished(t) { Ui.say(t) }
    }
    // Panic lock (hold L3 and R3): close anything open over the screen. Report a bug stays, behind its own lock
    // (Labs › Performance check's results stay too, for when it's opened again).
    Connections {
        target: Vault
        function onPanicked() {
            if (prompt.visible) prompt.cancel()
            // First setup goes too (a real one picks up again after the next unlock or start).
            if (setup.visible) { setup.visible = false; app.setupWaits = Settings.setupState === "started" }
            app.closePanels(true)
            Ui.say("Huginn locked.")
        }
        function onNotice(text) { Ui.say(text, true) }
    }
    // An update that went in but couldn't start Huginn again, or didn't go in.
    Connections {
        target: Updater
        function onNotice(text) { Ui.say(text, true) }
    }
    Connections {
        target: LocalSend
        function onRequestChanged() { app.showSendRequest() }
        function onFinished(ok, message) { Ui.say(message, !ok) }
        function onPinNeeded(deviceId, wrong) {
            Ui.ask(wrong ? "That PIN didn't work. Try again" : "Enter the PIN shown on the other device", "LocalSend", "", "Send",
                   pin => LocalSend.send(deviceId, Transfer.shared, pin), false)
        }
    }
    Connections {
        target: confirm
        function onVisibleChanged() { if (!confirm.visible && LocalSend.request.active) Qt.callLater(app.showSendRequest) }
    }

    // ------------------------------------------------------------- input
    function overlay() {
        if (updating.visible) return updating
        if (ctxMenu.visible) return ctxMenu
        if (confirm.visible) return confirm
        if (incomingCall.visible) return incomingCall
        if (callPage.visible) return callPage
        if (roomBar.sharePicker.visible) return roomBar.sharePicker   // Share screen: what to share
        if (prompt.visible) return prompt
        if (moveCover.visible) return moveCover
        if (linkPhone.visible) return linkPhone
        if (backupPanel.visible) return backupPanel
        if (setup.visible) return setup
        if (picker.visible) return picker
        if (switcher.visible) return switcher
        if (shortcutsPanel.visible) return shortcutsPanel
        if (chatFiles.visible) return chatFiles
        if (transfersPanel.visible) return transfersPanel
        if (labsReport.visible) return labsReport
        if (labsInbox.visible) return labsInbox
        if (appStorage.visible) return appStorage
        if (perfPanel.visible) return perfPanel
        if (personPanel.visible) return personPanel
        if (profileCard.visible) return profileCard
        if (meCard.visible) return meCard
        if (rail.menuOpen) return rail
        return null
    }
    function page() { return pageLoader.item && pageLoader.status === Loader.Ready ? pageLoader.item : null }

    function railButton(b) {
        switch (b) {
        case "up": railIndex = Math.max(0, railIndex - 1); break
        case "down": railIndex = Math.min(railItems.length - 1, railIndex + 1); break
        case "a": case "right": openRail(railIndex); break
        case "b": requestExit(); break
        case "start": openPage("settings", {}); break
        }
    }
    // LB on its own goes to the next section when it's let go; held, the D-pad's left and right
    // go back and forward instead (and it doesn't move on when let go).
    property bool lbHeld: false
    property bool lbUsed: false
    function press(b) {
        // Select (View) shows or hides the button legend; any other button hides it and does its job.
        if (b === "back") { legend = !legend; return }
        legend = false
        const o = overlay()
        if (o) { if (o.handleButton) o.handleButton(b); return }
        const p = page()
        // The page has LB right now (the chat lock's pattern or password).
        const pageShoulders = p && p.ownsShoulders && focusZone === "content"
        if (b === "lb" && !pageShoulders) { lbHeld = true; lbUsed = false; return }
        if (lbHeld && (b === "left" || b === "right")) { lbUsed = true; stepSection(b === "left" ? -1 : 1); return }
        // Tab and Shift+Tab on the keyboard.
        if ((b === "next" || b === "prev") && !pageShoulders) { stepSection(b === "prev" ? -1 : 1); return }
        if (focusZone === "rail") { railButton(b); return }
        // LT opens the options, which pages know as X.
        if (b === "lt") b = "x"
        if (p && p.handleButton && p.handleButton(b)) return
        // What the page didn't use: Left and B go to the rail, Menu opens Settings.
        if (b === "left" || b === "b") focusRail()
        else if (b === "start") openPage("settings", {})
    }
    function release(b) {
        if (b === "lb" && lbHeld) {
            lbHeld = false
            if (!lbUsed && !overlay()) stepSection(1)
            return
        }
        const o = overlay()
        if (o) { if (o.handleRelease) o.handleRelease(b); return }
        const p = page()
        if (focusZone === "content" && p && p.handleRelease) p.handleRelease(b)
    }

    // The right stick's buttons belong to the pointer: RB and R3 click, RT right-clicks.
    // L3 goes along too, so R3 doesn't click when both are held for the panic lock.
    function isPointerButton(b) { return b === "rb" || b === "rt" || b === "rs" || b === "ls" }
    Connections {
        target: Gamepad
        function onPressed(button) {
            app.noteInput()
            app.padInUse = true
            if (app.isPointerButton(button)) { app.legend = false; stick.press(button) }
            else app.press(button)
        }
        function onReleased(button) {
            if (app.isPointerButton(button)) stick.release(button)
            else app.release(button)
        }
        function onConnectedChanged() { if (!Gamepad.connected) { app.lbHeld = false; app.legend = false; app.padInUse = false; stick.hide(true) } }
    }

    // ------------------------------------------------------------- left stick: scroll
    // The list in front: under the pointer while it shows, else what the page says has the
    // focus, else whatever is in the middle of the page (or of the window, under an overlay).
    function flickableAt(x, y) {
        let found = null
        let item = app.contentItem, px = x, py = y
        for (let depth = 0; item && depth < 64; depth++) {
            if (item instanceof Flickable && item.contentHeight > item.height + 1) found = item
            const child = item.childAt(px, py)
            if (!child) break
            const p = item.mapToItem(child, px, py)
            px = p.x; py = p.y
            item = child
        }
        return found
    }
    function scrollTarget() {
        if (stick.shown) { const f = flickableAt(stick.x, stick.y); if (f) return f }
        if (overlay()) return flickableAt(app.width / 2, app.height / 2)
        if (focusZone === "rail") return flickableAt(rail.width / 2, rail.height / 2)
        const p = page()
        if (p && p.scrollView) { const f = p.scrollView(); if (f) return f }
        const c = pageLoader.mapToItem(null, pageLoader.width / 2, pageLoader.height / 2)
        return flickableAt(c.x, c.y)
    }
    function scrollBy(dy) {
        const f = scrollTarget()
        if (!f) return
        const top = f.originY - f.topMargin
        const bottom = Math.max(top, f.originY + f.contentHeight + f.bottomMargin - f.height)
        f.contentY = Math.max(top, Math.min(bottom, f.contentY + dy))
    }
    Timer {
        readonly property real dead: 0.2
        interval: System.batterySaving ? 33 : 16   // Battery saver: 30 a second
        repeat: true
        running: Math.abs(Gamepad.stickY) > dead
        // Slow near the middle, quick pushed all the way.
        onTriggered: {
            const v = Gamepad.stickY
            const t = Math.min(1, (Math.abs(v) - dead) / (1 - dead))
            if (t > 0) app.scrollBy((v < 0 ? -1 : 1) * Theme.px(1500) * t * t * interval / 1000)
        }
    }
    // Keyboard stands in for the controller in Desktop Mode.
    Item {
        id: keys
        anchors.fill: parent
        focus: true
        // A mouse in use: the controller's wording and focus rings go.
        // Moving it over the columns also puts the focus back on them (clicks there act on the page).
        HoverHandler {
            onPointChanged: {
                if (app.touring) return
                // The stick pointer's own hover isn't a mouse.
                if (stick.isOwn(point.position.x, point.position.y)) return
                app.noteInput()
                stick.hide(false)
                if (app.padInUse) app.padInUse = false
                if (app.focusZone === "rail" && point.position.x > rail.width) app.focusZone = "content"
            }
        }
        Keys.onPressed: event => {
            app.noteInput()
            // A held Esc or Backspace is one press too (B asks twice before a call ends or a send stops).
            if (event.isAutoRepeat && (event.key === Qt.Key_Return || event.key === Qt.Key_Space
                                       || event.key === Qt.Key_Escape || event.key === Qt.Key_Backspace)) return
            const map = {}
            map[Qt.Key_Up] = "up"; map[Qt.Key_Down] = "down"; map[Qt.Key_Left] = "left"; map[Qt.Key_Right] = "right"
            map[Qt.Key_Return] = "a"; map[Qt.Key_Enter] = "a"; map[Qt.Key_Escape] = "b"; map[Qt.Key_Backspace] = "b"
            map[Qt.Key_Space] = "x"; map[Qt.Key_Menu] = "y"; map[Qt.Key_F10] = "y"; map[Qt.Key_Tab] = "next"
            map[Qt.Key_Backtab] = "prev"; map[Qt.Key_F1] = "start"
            // Page Up and Page Down scroll like the left stick, a screenful at a time.
            if (event.key === Qt.Key_PageUp || event.key === Qt.Key_PageDown) {
                const f = app.scrollTarget()
                if (f) app.scrollBy((event.key === Qt.Key_PageUp ? -0.9 : 0.9) * f.height)
                event.accepted = true
                return
            }
            // In a call, Space mutes (a controller's X still turns the camera on and off).
            const b = event.key === Qt.Key_Space && callPage.visible && !incomingCall.visible ? "a" : map[event.key]
            if (b) { if (!app.touring) app.padInUse = false; app.press(b); event.accepted = true }
            // A key that isn't a controller stand-in still puts the legend away.
            else app.legend = false
        }
        Keys.onReleased: event => {
            if (event.isAutoRepeat) return
            if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) app.release("a")
            if (event.key === Qt.Key_Menu || event.key === Qt.Key_F10) app.release("y")
            if (event.key === Qt.Key_Space) app.release("x")
        }
    }

    // ------------------------------------------------------------- screenshot tour (CI)
    // `huginn --tour DIR` opens every screen, saves a picture of each and quits. CI fails the
    // build on any QML warning, so a broken screen never reaches a release.
    Timer {
        id: tour
        property int step: 0
        property string savedTheme: "dark"
        property string savedSeason: "off"
        property string savedText: "100"
        property string savedDensity: "cozy"
        property string savedSize: "100"
        property bool savedLabs: false
        readonly property var steps: [
            { page: "dms", file: "00-direct-messages" },
            { page: "dms", zone: "messages", file: "01-conversation" },
            { page: "dms", rail: true, file: "02-rail" },
            { page: "files", file: "03-files" },
            { page: "settings", file: "05-settings" },
            { page: "settings", section: "privacy", file: "06-settings-lock" },
            { page: "contacts", file: "07-contacts" },
            { page: "dms", picker: true, file: "08-options" },
            { page: "dms", prompt: true, file: "09-write" },
            { page: "dms", hold: true, file: "10-confirm" },
            { page: "dms", zone: "messages", newGroup: true, file: "11-new-group" },
            { page: "settings", section: "look", file: "12-look" },
            { page: "g:tour-crew", file: "13-group" },
            { page: "g:tour-crew", zone: "messages", file: "14-group-messages" },
            { page: "g:tour-crew", zone: "channels", groupOptions: true, file: "15-group-options" },
            { page: "g:tour-crew", zone: "channels", voice: true, file: "16-voice-room" },
            { page: "dms", folders: true, file: "17-folders" },
            { page: "dms", zone: "messages", person: true, file: "18-person-panel" },
            { page: "g:tour-crew", zone: "messages", voicePanel: true, file: "19-voice-members" },
            { page: "g:tour-crew", zone: "members", profile: true, file: "20-profile-card" },
            { page: "dms", zone: "messages", shortcuts: true, file: "21-shortcuts" },
            { page: "settings", section: "devices", file: "22-devices" },
            { page: "g:tour-crew", zone: "messages", theme: "light", density: "compact", voicePanel: true, file: "23-light-compact" },
            { page: "dms", zone: "messages", theme: "black", file: "24-true-black" },
            { page: "g:tour-crew", zone: "messages", theme: "dark", season: "autumn", density: "spacious", file: "25-autumn" },
            { page: "g:tour-crew", zone: "messages", size: "150", file: "26-size-150" },
            { page: "dms", zone: "messages", portrait: true, file: "27-portrait" },
            { page: "dms", zone: "list", portrait: true, file: "28-portrait-list" },
            { page: "dms", changeLock: true, file: "29-lock" },
            // A small window (1024 by 768): the squeezed layouts, as on a laptop or handheld screen.
            { page: "settings", section: "look", small: true, file: "30-small-window" },
            // A contact's menu › Edit, the other sub-page.
            { page: "dms", zone: "messages", person: true, personPage: "edit", file: "31-edit" },
            { page: "files", filesMenu: true, file: "32-files-menu" },
            // Labs mode: its group in About, Report a bug, and Labs reports.
            { page: "settings", section: "about", labs: true, file: "33-labs" },
            { page: "settings", section: "about", labs: true, report: true, file: "34-labs-report" },
            { page: "dms", labsInbox: true, file: "35-labs-reports" },
            // Seasonal in Snow: the Look page with the season's settings, then a conversation.
            { page: "settings", section: "look", theme: "dark", season: "snow", file: "36-snow-look" },
            { page: "g:tour-crew", zone: "messages", theme: "dark", season: "snow", file: "37-snow" },
            // Link your phone: the code, the 4 emoji and Linked (the code on the Snow ground too).
            { page: "dms", linkPhone: "code", file: "38-link-phone" },
            { page: "dms", linkPhone: "match", file: "39-link-match" },
            { page: "dms", linkPhone: "linked", file: "40-linked" },
            { page: "dms", linkPhone: "code", theme: "dark", season: "snow", file: "41-link-phone-snow" },
            // Settings › Notifications, and Find in a channel with its matches lit.
            { page: "settings", section: "notify", file: "42-notifications" },
            { page: "g:tour-crew", zone: "messages", find: "in", file: "43-find" },
            // Labs › App storage (sample files: nothing on this computer is touched).
            { page: "settings", section: "about", labs: true, appStorage: true, file: "44-app-storage" },
            // Labs › Performance check with sample results (nothing is measured).
            { page: "settings", section: "about", labs: true, perf: true, file: "45-performance-check" },
            // Seasonal over True black: the Look page with leaves falling on pure black.
            { page: "settings", section: "look", theme: "black", season: "autumn", file: "46-look-seasonal-black" },
            // Look at the largest text size: the pickers stay whole, under their names or as a dropdown.
            { page: "settings", section: "look", textSize: "150", file: "47-look-largest-text" },
            { page: "settings", section: "look", textSize: "150", small: true, file: "48-look-largest-small" },
            // A member's menu with Mute for me, Volume, Timeout and Ban (an admin's), and the Volume slider.
            { page: "g:tour-crew", zone: "members", memberMenu: true, file: "49-member-menu" },
            { page: "g:tour-crew", zone: "messages", volume: true, file: "50-volume" },
            // First setup: each page, two stops of the tour on the real screen, and Done.
            // Transfers (sample rows: Direct, asking first, paused, saved, refused by a group's rules) and a group's Files.
            { page: "dms", zone: "messages", transfers: true, file: "49-transfers" },
            { page: "g:tour-crew", zone: "messages", chatFiles: true, file: "50-files-section" },
            // Right-click menus, each where a right click opens it: a person (with Mute open beside it), a group on the rail,
            // a folder, a message, a member, Settings on the rail, a text box, and the empty list.
            { page: "dms", folders: true, ctx: "person", file: "70-menu-person" },
            { page: "dms", ctx: "group", file: "71-menu-group" },
            { page: "dms", folders: true, ctx: "folder", file: "72-menu-folder" },
            { page: "g:tour-crew", zone: "messages", ctx: "message", file: "73-menu-message" },
            { page: "g:tour-crew", zone: "members", ctx: "member", file: "74-menu-member" },
            { page: "dms", ctx: "settings", file: "75-menu-settings" },
            { page: "dms", ctx: "field", file: "76-menu-text" },
            { page: "dms", ctx: "space", small: true, file: "77-menu-empty-list" },
            // Settings › Backup: what's inside, the password, saved; then Restore: choose a file, a wrong password, what's in it.
            { page: "settings", section: "backup", backup: "what", file: "80-backup" },
            { page: "settings", section: "backup", backup: "password", file: "81-backup-password" },
            { page: "settings", section: "backup", backup: "saved", file: "82-backup-saved" },
            { page: "settings", section: "backup", restore: "pick", file: "83-restore-choose" },
            { page: "settings", section: "backup", restore: "password", file: "84-restore-password" },
            { page: "settings", section: "backup", restore: "summary", file: "85-restore-summary" },
            { page: "dms", setup: "welcome", file: "60-setup-welcome" },
            { page: "dms", setup: "lock", file: "61-setup-lock" },
            { page: "dms", setup: "name", file: "62-setup-name" },
            { page: "dms", setup: "link", file: "63-setup-link" },
            { page: "dms", setup: "people", file: "64-setup-people" },
            { page: "dms", setup: "tour", stop: 0, file: "65-setup-tour-rail" },
            { page: "dms", setup: "tour", stop: 5, voicePanel: true, file: "66-setup-tour-card" },
            { page: "dms", setup: "done", file: "67-setup-done" }
        ]
        interval: 1500
        repeat: true
        onTriggered: {
            if (step > 0) System.grab(app, steps[step - 1].file)
            prompt.visible = false; confirm.visible = false; picker.visible = false; ctxMenu.close()
            profileCard.visible = false; shortcutsPanel.visible = false; personPanel.visible = false
            transfersPanel.visible = false; chatFiles.visible = false
            labsReport.visible = false; labsInbox.visible = false; appStorage.visible = false; perfPanel.visible = false; linkPhone.visible = false
            setup.visible = false; backupPanel.visible = false
            if (step === 0) { savedTheme = Settings.theme; savedSeason = Settings.season; savedDensity = Settings.density; savedSize = Settings.uiSize; savedLabs = Settings.labs; savedText = Settings.textSize }
            if (step >= steps.length) {
                Settings.theme = savedTheme; Settings.season = savedSeason; Settings.density = savedDensity; Settings.uiSize = savedSize; Settings.labs = savedLabs; Settings.textSize = savedText
                app.width = 1920; app.height = 1080
                stop(); System.tourFinished(); return
            }
            const s = steps[step]
            // The look changes once the new page is in (not while the old one is being taken down).
            const theme = s.theme || savedTheme, season = s.season || savedSeason, density = s.density || savedDensity, size = s.size || savedSize
            const textSize = s.textSize || savedText
            Qt.callLater(() => { Settings.theme = theme; Settings.season = season; Settings.density = density; Settings.uiSize = size; Settings.textSize = textSize })
            // A portrait window: the one-column layout.
            if (s.portrait) { app.width = 900; app.height = 1200 } else if (s.small) { app.width = 1024; app.height = 768 } else { app.width = 1920; app.height = 1080 }
            app.tourVoice = !!s.voicePanel
            Settings.labs = s.labs ? true : savedLabs
            const args = { tour: true }
            if (s.section) args.section = s.section
            if (s.zone) args.zone = s.zone
            if (s.groupOptions) args.groupOptions = true
            if (s.voice) args.voice = true
            if (s.folders) args.folders = true
            if (s.changeLock) args.changeLock = true
            app.openPage(s.page, args)
            if (s.rail) app.focusRail()
            // A contact's one menu, as the three dots, a right click or LT open it.
            if (s.picker) PeopleActions.contactMenu("tour", "Mira", { tour: true })
            if (s.prompt) prompt.open("Message everyone nearby", "You're " + Ui.myName, "", "Send", null, false, true)
            if (s.profile) Ui.showProfile("j", "Jake", { tour: true, group: "Gaming Crew", admin: true, room: "Lounge", note: "Hosts raid night on Fridays. Bring snacks." })
            if (s.shortcuts) shortcutsPanel.open()
            if (s.transfers) transfersPanel.open(true)
            if (s.chatFiles) chatFiles.open("g:tour-crew", "Gaming Crew", true)
            if (s.person) personPanel.show("tour", "Mira", { tour: true, page: s.personPage || "can" })
            if (s.filesMenu) Qt.callLater(() => { const p = app.page(); if (p && p.filesMenu) p.filesMenu() })
            if (s.newGroup) app.newGroup()
            if (s.report) Qt.callLater(app.openReport)
            if (s.labsInbox) app.openLabsReports()
            if (s.appStorage) app.openAppStorage()
            if (s.perf) app.openPerf()
            if (s.linkPhone) linkPhone.open(true, s.linkPhone)
            // First setup's pages and two tour stops, as practice (nothing is saved).
            if (s.setup) Qt.callLater(() => setup.open(false, s.setup, s.stop || 0))
            if (s.backup) Qt.callLater(() => backupPanel.open("backup", false, true, s.backup))
            if (s.restore) Qt.callLater(() => backupPanel.open("restore", false, true, s.restore))
            if (s.find) Qt.callLater(() => { const p = app.page(); if (p && p.openFind) p.openFind(s.find) })
            if (s.memberMenu) Qt.callLater(() => { const p = app.page(); if (p && p.memberMenu) p.memberMenu(p.members.find(m => m.key === "s")) })
            if (s.volume) Qt.callLater(() => ModActions.volumeMenu("s", "Sam", null, true))
            if (s.ctx) Qt.callLater(() => app.tourMenu(s.ctx))
            if (s.hold) confirm.open("Erase everything?", "Messages, photos, voice, contacts, keys and the lock are deleted for good, and Huginn closes.", "Hold to erase", null, true, true)
            step++
        }
    }
}

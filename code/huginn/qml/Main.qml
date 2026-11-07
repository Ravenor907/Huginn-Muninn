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
        // The screen most of the saved window was on (at least 120 by 60 of it showing), so one left on a monitor to
        // the left of or above the main one comes back there too.
        const screens = Qt.application.screens
        let home = null, most = 0
        for (let i = 0; i < screens.length; i++) {
            const s = screens[i]
            const ow = Math.min(r.x + r.width, s.virtualX + s.width) - Math.max(r.x, s.virtualX)
            const oh = Math.min(r.y + r.height, s.virtualY + s.height) - Math.max(r.y, s.virtualY)
            if (ow >= 120 && oh >= 60 && ow * oh > most) { home = s; most = ow * oh }
        }
        // Sized to one screen (that one, or the window's own), never to the whole desktop across every monitor.
        const sx = home ? home.virtualX : Screen.virtualX, sy = home ? home.virtualY : Screen.virtualY
        const sw = home ? home.width : Screen.width, sh = home ? home.height : Screen.height
        if (r.width >= 320 && r.height >= 240 && r.width <= sw && r.height <= sh) {
            width = r.width; height = r.height
            if (home) { x = r.x; y = r.y }
        } else {
            width = Math.round(sw * 0.8); height = Math.round(sh * 0.8)
            x = sx + Math.round((sw - width) / 2); y = sy + Math.round((sh - height) / 2)
        }
    }
    minimumWidth: 480
    minimumHeight: 360
    visible: true
    // Full screen or a window, decided once as it opens (System.startsFullScreen reads no setting that changes later).
    // After that only F11, Settings › Look › Open full screen and Handheld mode switch it (applyWindowMode below):
    // a change to any other setting never touches it.
    visibility: System.startsFullScreen() ? Window.FullScreen : Window.Windowed
    // Unread chats and @mentions in the title, as "(3) Huginn" (the taskbar shows it). Never while locked.
    readonly property int unreadTotal: touring || Vault.screenLocked ? 0
                                       : railItems.reduce((n, e) => n + (e.id === "dms" || e.kind === "group" ? (e.badge || 0) : 0), 0)
    title: unreadTotal > 0 ? "(" + unreadTotal + ") Huginn" : "Huginn"
    // Windows also shows it as a badge on the taskbar button and in the tray icon's tooltip, with the tray's mute.
    readonly property string unreadState: unreadTotal + (trayMuted ? " muted" : "")
    onUnreadStateChanged: System.setUnreadState(app, unreadTotal, trayMuted)
    color: Theme.ground

    readonly property bool touring: System.tourDir.length > 0
    // Locked (a lock is set and Huginn is behind it): the whole window is the lock. Only Chats (where the lock screen
    // is) and the power button stay on the rail; every other page, panel and menu is closed and refused until unlocked.
    readonly property bool lockedOut: !touring && Vault.exists && Vault.screenLocked
    onLockedOutChanged: {
        if (lockedOut) lockDown()
        else {
            Qt.callLater(showSendRequest)   // a nearby-sharing request that came while locked is asked now
            sayHeld()   // round 17: the latest toast held behind the lock
            // Back to the page that was open before the lock (a group, a channel, Settings), unless a notification
            // clicked while locked says where to go (afterUnlock), or everything was erased meanwhile.
            const back = beforeLock
            beforeLock = null
            if (back && Vault.exists && afterUnlock === "" && pageName === "dms") {
                openPage(back.name, back.args)
                railIndex = Math.max(0, railItems.findIndex(e => e.id === section))
            }
        }
    }
    // The page open when the lock came down: {name, args}, opened again once unlocked.
    property var beforeLock: null
    function lockDown() {
        // Round 17: only where it was (a channel, a part of the page, a Settings section), never one-shot asks like
        // showFile or groupOptions, which would run again on unlock.
        if (pageName !== "dms") {
            const kept = {}
            for (const k of ["channel", "zone", "section"]) if (pageArgs && pageArgs[k] !== undefined) kept[k] = pageArgs[k]
            beforeLock = { name: pageName, args: kept }; pageName = "dms"; pageArgs = ({})
        }
        else beforeLock = null
        focusZone = "content"
        railIndex = 0
        // Report a bug stays, behind its own lock. Every other panel, question and menu goes.
        closePanels(true)
        if (prompt.visible) prompt.cancel()
        if (confirm.visible) confirm.dismiss()
    }
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
        app.visibility = want ? Window.FullScreen : app.normalVisibility()
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
        { id: "dms", kind: "raven", label: "Chats", badge: Vault.screenLocked ? 0 : Chat.unreadCount + Linked.unread + Chat.requests.length + Chat.groupInvites.filter(i => !i.waiting).length }
    // A group: its @mentions of me as a number, and a dot for anything else unread (like Discord).
    ].concat(groupList.map(g => ({ id: "g:" + g.id, kind: "group", label: g.name, letter: g.letter || String(g.name || "?").trim().charAt(0).toUpperCase() || "?",
                                   badge: g.mentions || 0, unread: g.unread || 0 })))
     .concat([
        { id: "newgroup", kind: "add", label: "New group or join one", action: true },
        { id: "contacts", kind: "icon", icon: "person", label: "Contacts", bottom: true },
        { id: "files", kind: "icon", icon: "folder", label: "Files", bottom: true, badge: LocalSend.request.active ? 1 : 0 },
        { id: "settings", kind: "icon", icon: "gear", label: "Settings", bottom: true },
        // Lock Huginn or close it (a small menu).
        { id: "exit", kind: "icon", icon: "power", label: "Lock or close", bottom: true, action: true }
    ]).filter(e => !app.lockedOut || e.id === "dms" || e.id === "exit")   // locked: only the lock screen and the power button
    // LB steps through the sections and groups (New group and the power menu are reached with the D-pad).
    readonly property var cycle: railItems.filter(e => !e.action).map(e => e.id)

    // ------------------------------------------------------------- pages
    property string pageName: "dms"
    property var pageArgs: ({})
    property string focusZone: "content"     // "rail" or "content"
    property int railIndex: 0
    // The rail item for the page on screen.
    readonly property string section: pageName
    // Labs: where the person is, for the activity log (a group by a short id, never its name).
    onPageNameChanged: Labs.note("screen", pageName.indexOf("g:") === 0 ? "group " + Labs.shortId(pageName.substring(2)) : pageName)

    function openPage(name, args) {
        if (name === "chat") name = "dms"
        // Locked: the lock screen (Chats) is the only page.
        if (lockedOut && name !== "dms") { Ui.say("Unlock Huginn first.", true); name = "dms"; args = {} }
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
        if (e.id === "newgroup") { plusMenu(); return }
        openPage(e.id, {})
    }
    // The rail's +: the same three rows as the chat list's + (and Muninn's): New chat, New group, Add someone (its page
    // has My code, One-time link, Paste a code and Join a group).
    readonly property var plusItems: [{ name: "New chat", icon: "thread", url: "newchat" },
                                      { name: "New group", icon: "people", url: "group" },
                                      { name: "Add someone", icon: "plus", url: "addpage" }]
    function plusMenu() { Ui.pickFrom("Add", [{ header: "", items: plusItems }], a => plusAction(a)) }
    function plusAction(a) {
        if (app.touring) return
        const later = f => Qt.callLater(() => { const p = page(); if (p && typeof p[f] === "function") p[f]() })
        if (a === "group") newGroup()
        else if (a === "newchat") { openPage("dms", {}); later("newChat") }
        else if (a === "addpage") { openPage("dms", {}); later("addPage") }
    }
    function newGroup() {
        if (!Vault.exists) { Ui.say("Set a lock first.", true); openPage("dms", {}); return }
        if (Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); openPage("dms", {}); return }
        // Two types: Folk and Family, each a card with its one line; "What's the difference?" shows the rules.
        const types = Chat.groupTypes()
        // While a phone is linked, a group made here belongs to this PC's own account: said in the box.
        const own = !!Linked.phone.key && !Linked.phone.gone
        Ui.askWith("New group", own ? "Made by this PC's own account: same name and picture as you, but separate from your phone, so people see a second " + Ui.myName + ". Everyone in it sees the name" : "Everyone in it sees the name", "", "Make the group",
                   types.map(t => ({ value: t.type, label: t.name, detail: t.line })), "folk",
                   "Type", (t, type) => {
            const g = Chat.createGroup(t, type)
            if (g === "") { Ui.say(Chat.saveFailed() ? "Couldn't save. Try again." : Chat.ready ? "Give the group a name." : "Unlock Huginn first.", true); return }
            // Made: how long its messages are kept, said once here (the group page doesn't say it again).
            const keep = Chat.keepNoteOnce("g:" + g)
            Ui.say("Made " + t + "." + (keep !== "" ? " " + keep : ""))
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
    // Settings › Profile › Status: your status card, as a click on your name opens it.
    function openStatusCard() { if (!lockedOut) meCard.open() }
    function lockNow() {
        if (!Vault.hasLock || Vault.screenLocked) return
        if (meCard.visible) meCard.close()
        Vault.lockScreen()
        if (!Vault.screenLocked) return   // nothing locked: no toast
        openPage("dms", {})
        Ui.say("Huginn locked.")
    }
    // Every Exit goes through here (the window's X, Exit, B or Esc at the top), so a call, a voice room or a transfer is
    // never cut off by surprise. Huginn never locks on its own: closing asks. Lock and close (A) quits, and Huginn opens
    // locked next time; Minimize (X) keeps it running out of sight, still getting messages and calls; Stay (B).
    // fromX: the window's X (or Alt+F4). It asks once with "Don't ask again", and then does what was picked
    // (Settings › Look › Window › Close button changes it). A call, a voice room or a transfer still asks before closing;
    // a remembered Minimize still minimizes (that ends nothing).
    // What closing or restarting now would cut off, in words ("" when nothing). ending: "Closing" or "Restarting".
    function stakeText(ending) {
        const parts = []
        if (Rooms.inRoom) parts.push("You're in " + (Rooms.groupName ? Rooms.groupName + " › " + Rooms.title : Rooms.title) + ". " + ending + " leaves it.")
        else if (Calls.inCall) parts.push("You're in a call" + (Calls.peerName ? " with " + Calls.peerName : "") + ". " + ending + " ends it.")
        if (FileLink.closeWarning !== "") parts.push(FileLink.closeText(ending))
        if (LocalSend.busy) parts.push(LocalSend.status + " " + ending + " now stops it partway.")
        return parts.join(" ")
    }
    // A file still moving: Wait for it. Only paused ones: Resume and wait (paused, it would never finish).
    // { text, cb } for the dialog, or null when no file can be waited for.
    function waitChoice(what) {
        if (FileLink.runningCount > 0)
            return { text: "Wait for it", cb: () => app.waitThen(what) }
        if (FileLink.pausedCount > 0)
            return { text: "Resume and wait", cb: () => { FileLink.resumeAll(); app.waitThen(what) } }
        return null
    }
    function requestExit(fromX) {
        // A chat file on its way, paused or waiting for them to accept counts too.
        const files = FileLink.closeWarning !== ""
        const voice = app.inVoice
        const busy = LocalSend.busy || files || voice
        const locks = Vault.hasLock && !Vault.screenLocked
        const lockAndQuit = () => { if (locks) Vault.lockScreen(); app.quitNow() }
        // A remembered Minimize minimizes even during a call or a transfer: minimizing ends nothing.
        if (fromX && Settings.closeAction === "minimize") { System.minimize(app); return }
        if (fromX && !busy && Settings.closeAction === "quit") { lockAndQuit(); return }
        if (busy) {
            // A call, a voice room or a file on its way: Minimize is the main choice, as it keeps them going.
            // Only paused files: nothing is going, so Minimize just keeps them.
            const onlyPaused = files && !voice && !LocalSend.busy && FileLink.runningCount === 0
            Ui.sure("Leave Huginn?", app.stakeText("Closing") + (onlyPaused ? " Minimize keeps it." : " Minimize keeps it going."), "Minimize", () => {
                if (fromX && confirm.remember) Settings.closeAction = "minimize"
                System.minimize(app)
            }, false, false)
            confirm.otherText = "Close anyway"
            confirm.otherCallback = lockAndQuit
            // A file moving: Close anyway is red.
            confirm.otherDanger = FileLink.runningCount > 0 || LocalSend.busy
            const wait = files ? app.waitChoice(locks ? "lock" : "quit") : null
            if (wait) {
                confirm.extraText = wait.text
                confirm.extraCallback = wait.cb
            }
            confirm.cancelText = "Stay"
            if (fromX && !files && !LocalSend.busy) confirm.rememberText = "Don't ask again"
            return
        }
        Ui.sure("Leave Huginn?", "Minimize keeps messages and calls coming.", locks ? "Lock and close" : "Close", () => {
            if (fromX && confirm.remember) Settings.closeAction = "quit"
            lockAndQuit()
        }, false, false)
        confirm.otherText = "Minimize"
        confirm.otherCallback = () => {
            if (fromX && confirm.remember) Settings.closeAction = "minimize"
            System.minimize(app)
        }
        confirm.cancelText = "Stay"
        if (fromX) confirm.rememberText = "Don't ask again"
    }
    // Wait for it: what to do once no chat file is moving any more ("quit", "lock" or "restart" to update).
    property string afterFiles: ""
    // The files being waited for (their ids), so one that fails keeps Huginn open.
    property var waitIds: []
    function waitThen(what) {
        afterFiles = what
        waitIds = FileLink.transfers.filter(t => !t.relay && (t.state === "connecting" || t.state === "sending")).map(t => t.id)
        Ui.say(what === "restart" ? "Huginn restarts to update when the file is done." : "Huginn closes when the file is done.")
    }
    // Don't close (the pending-close bar), a call or voice room starting, or a file failing: Huginn stays open.
    function cancelWait(why) {
        if (afterFiles === "") return
        afterFiles = ""
        waitIds = []
        if (why !== "") Ui.say(why)
    }
    // What the pending-close bar waits for, in words: the file's name, or "2 files".
    readonly property var waitFiles: afterFiles === "" ? [] : FileLink.transfers.filter(t => waitIds.indexOf(t.id) >= 0)
    onInVoiceChanged: {
        if (inVoice && afterFiles !== "")
            cancelWait((Rooms.inRoom ? "You're in a voice room now" : "You're in a call now")
                       + (afterFiles === "restart" ? ", so the update waits. Restart now is in Settings › Updates and about." : ", so Huginn stays open."))
    }
    Connections {
        target: FileLink
        function onChanged() {
            if (app.afterFiles === "" || FileLink.runningCount > 0) return
            const what = app.afterFiles
            const failed = FileLink.transfers.filter(t => app.waitIds.indexOf(t.id) >= 0 && t.state === "failed")
            app.afterFiles = ""
            app.waitIds = []
            // A file that stopped before it was done: Huginn stays, so it can be retried.
            if (failed.length > 0) {
                app.sayOutOfSight("Huginn didn't close", failed[0].name + " stopped before it was done, so Huginn stays open.", true)
                return
            }
            // Paused while waiting: it would never finish, so Huginn stays (and says so) rather than cut it off.
            if (FileLink.pausedCount > 0) {
                app.sayOutOfSight(what === "restart" ? "Huginn didn't restart" : "Huginn didn't close",
                                  what === "restart" ? "A file is paused, so the update waits. Restart now is in Settings › Updates and about." : "A file is paused, so Huginn stays open.", false)
                return
            }
            // Something else started meanwhile (a call, a voice room, another file): ask again rather than cut it off.
            // Out of sight (minimized, or another app in front), a notification says Huginn didn't close.
            if (app.inVoice || LocalSend.busy || FileLink.closeWarning !== "") {
                if (app.outOfSight())
                    System.notifyNow(what === "restart" ? "Huginn didn't restart" : "Huginn didn't close",
                                     (app.inVoice ? (Rooms.inRoom ? "You're in a voice room." : "You're in a call.")
                                                  : "Something else is still on its way.") + " Open Huginn to choose.")
                if (what === "restart") app.restartForUpdate()
                else app.requestExit(false)
                return
            }
            if (what === "restart") Updater.restartToUpdate()
            else { if (what === "lock" && Vault.hasLock && !Vault.screenLocked) Vault.lockScreen(); app.quitNow() }
        }
    }
    // Huginn minimized, hidden or behind another app: a toast in the window wouldn't be seen.
    function outOfSight() {
        return Qt.application.state !== Qt.ApplicationActive || app.visibility === Window.Minimized || app.visibility === Window.Hidden
    }
    // A toast, and a system notification too while the window is out of sight.
    function sayOutOfSight(title, text, bad) {
        if (outOfSight()) System.notifyNow(title, text)
        Ui.say(text, bad)
    }
    // Restart now (the update bar, Settings › Updates and about): a call, a voice room or a file on its way asks first, and the
    // update can wait for the file.
    function restartForUpdate() {
        const files = FileLink.closeWarning !== ""
        if (!files && !app.inVoice && !LocalSend.busy) { Updater.restartToUpdate(); return }
        Ui.sure("Restart to update?", app.stakeText("Restarting"), "Restart anyway", () => Updater.restartToUpdate(), false, true)
        const wait = files ? app.waitChoice("restart") : null
        if (wait) {
            confirm.otherText = wait.text
            confirm.otherCallback = wait.cb
        }
        confirm.cancelText = "Later"
    }
    // Answered: Huginn closes (the window's close question isn't asked again).
    property bool quitting: false
    function quitNow() { quitting = true; Qt.quit() }
    // The window's X (or Alt+F4) asks first, like Exit. Not while an update restarts Huginn, or in the screenshot tour.
    onClosing: close => {
        if (app.quitting || app.touring || Updater.installing) return
        close.accepted = false
        app.requestExit(true)
    }

    // The slim bar along the top: HUGINN, search (Ctrl+K) and the shortcuts (Ctrl+/).
    TopBar {
        id: topBar
        width: parent.width
        onSearch: if (!app.touring) switcher.open()
        onShortcuts: shortcutsPanel.open()
        onTransfers: if (!app.lockedOut) transfersPanel.open(app.touring)
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
            if (app.lockedOut) return "ChatPage.qml"   // locked: only the lock screen, whatever page was asked for
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
        // Locked: who's in the room and what they share stay behind the lock too.
        visible: Rooms.inRoom && !app.lockedOut
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        anchors.left: voicePanel.visible ? voicePanel.right : rail.visible ? rail.right : parent.left
        anchors.right: parent.right
        // On the desktop the voice panel has the controls; a shared screen still shows above here.
        docked: voicePanel.visible
    }
    // For the pages' voice room menus (Bigger / Smaller on someone's shared screen).
    readonly property var screenBar: roomBar

    // ------------------------------------------------------------- the desktop panels
    // The page's own list column shows (ChatPage and GroupPage can hide theirs), and a group's member list.
    readonly property bool pageList: { const p = pageLoader.item; return !(p && p.listHidden === true) }
    readonly property bool pageMembers: { const p = pageLoader.item; return !!(p && p.membersVisible === true) }
    property bool tourVoice: false
    property bool tourLegend: false   // the tour's handheld stops show the button legend, as Handheld mode does at start
    // You and your voice controls, at the bottom left under the rail and the list (wide windows).
    VoicePanel {
        id: voicePanel
        tour: app.tourVoice
        roomBar: roomBar
        cardOpen: meCard.visible
        compact: app.height < Theme.px(620)
        onMeClicked: if (!app.lockedOut || meCard.visible) meCard.toggle()
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
    // The tray's Mute notifications: only this computer goes quiet (this run); your status, which others see, stays.
    property bool trayMuted: false
    Binding { target: Settings; property: "quiet"; value: Ui.myStatus === "dnd" || app.trayMuted }
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
    // Asked once: an install from before What notifications show was picked anywhere (no saved choice) that shows
    // Nothing. Not over the lock screen, a call, first setup or another dialog; it waits and looks again. It saves
    // Nothing as it opens, so Back (or a click outside) keeps Nothing and it never asks again. Settings › Notifications
    // › What notifications show changes it after.
    Timer {
        id: askWho
        interval: 5000; running: !app.touring; repeat: true
        onTriggered: {
            if (Chat.previewModeChosen() || Chat.previewMode !== 0) { stop(); return }
            if (!Chat.ready || Vault.screenLocked || Calls.inCall || Rooms.inRoom || setup.visible || Settings.setupState === "started" || app.overlay()) return
            stop()
            Chat.previewMode = 0
            Ui.pickFrom("Show who messages are from?", [{ header: "", items: [
                { name: "Name only (recommended)", detail: "Who it's from, not the message", url: "1" },
                { name: "Name and message", detail: "Who it's from and a short preview", url: "2" },
                { name: "Nothing", detail: "Only \"New message\"", url: "0" }] }], v => Chat.previewMode = Number(v))
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
        visible: (app.legend && Gamepad.connected) || app.tourLegend || LocalSend.busy
        showKeys: (app.legend && Gamepad.connected) || app.tourLegend
        status: incomingCall.visible ? incomingCall.status : callPage.visible ? callPage.status
              : app.focusZone === "rail" ? "" : pageLoader.item && pageLoader.item.status !== undefined ? pageLoader.item.status : ""
        prompts: app.withTalk(basePrompts)
        readonly property var basePrompts: {
            if (incomingCall.visible) return incomingCall.prompts
            if (callPage.visible) return callPage.prompts
            if (moveCover.visible && !prompt.visible && !confirm.visible) return moveCover.prompts.concat([{ key: "back", label: "Hide buttons" }])
            if (linkPhone.visible && !prompt.visible && !confirm.visible) return linkPhone.prompts.concat([{ key: "back", label: "Hide buttons" }])
            if (backupPanel.visible && !prompt.visible && !confirm.visible) return backupPanel.prompts.concat([{ key: "back", label: "Hide buttons" }])
            // Round 17 (B1): the lock asked before Back up, Move or linking.
            // Round 19: only the buttons that work on its current step (LockCheck's own list, glyphs by the pad's style).
            if (lockCheck.visible && !prompt.visible && !confirm.visible) return (lockCheck.prompts || []).concat([{ key: "back", label: "Hide buttons" }])
            if (setup.visible && !prompt.visible && !confirm.visible) return setup.prompts.concat([{ key: "back", label: "Hide buttons" }])
            if (prompt.visible && !confirm.visible) return prompt.prompts.concat([{ key: "back", label: "Hide buttons" }])
            if (app.overlay() === newsBanner) return newsBanner.prompts.concat([{ key: "back", label: "Hide buttons" }])
            // Round 17: no "Next section" while LB is the page's (its own LB chip says what it does) or while a voice
            // message is held on Y (LB is ignored then: Main.press).
            // Round 19 (FIX 17): behind the lock there are no sections to move to and Settings stays shut, so neither shows.
            const lb = app.pageOwnsLb() || Voice.recording || app.lockedOut ? [] : [{ key: "lb", label: "Next section" }]
            // Select only ever shows or hides this legend (Main.press), and Menu always opens Settings.
            const hide = (app.lockedOut ? [] : [{ key: "start", label: "Settings" }]).concat([{ key: "back", label: "Hide buttons" }])
            // In voice: hold LB, then Up or Down.
            const voice = app.inVoice ? [{ key: "lb+up", label: Ui.micMuted ? "Unmute mic" : "Mute mic" },
                                         { key: "lb+down", label: Ui.deafened ? "Undeafen" : "Deafen" }] : []
            if (app.focusZone === "rail")
                return lb.concat([{ key: "a", label: "Open" }, { key: "lt", label: "Options" }, { key: "b", label: "Exit" }],
                                 app.railIndex === 0 ? [{ key: "up", label: "Search, Transfers" }] : [], voice, hide)
            const p = pageLoader.item && pageLoader.item.prompts ? pageLoader.item.prompts.filter(x => x.key !== "back" && x.key !== "start") : []
            return lb.concat(p, voice, hide)
        }
        // A tap on a chip is a whole press: LB here can't be held, so it just goes to the next section. The talk
        // button's chip can't be held either, so a tap does nothing.
        onPrompt: k => {
            if (app.padTalks && k === TalkKey.padButton) return
            if (k === "lb" && !app.overlay() && !app.pageOwnsLb()) { if (!Voice.recording) app.stepSection(1) }
            else app.press(k)
        }
    }
    // In a voice room with push to talk on, the controller's talk button talks on every screen, so the legend shows
    // that instead of the button's usual job (Y's Keyboard, LT's Options), first in the row. X opens what LT did
    // (Options, Menu), so with LT talking its chip moves to X, unless X already has a job on that screen.
    readonly property bool padTalks: TalkKey.padButton !== "" && Settings.pushToTalk && Rooms.inRoom
    function withTalk(list) {
        if (!padTalks || !list) return list
        const k = TalkKey.padButton
        const hasX = list.some(c => c.key === "x")
        const rest = []
        for (const c of list) {
            if (c.key !== k) rest.push(c)
            else if (k === "lt" && !hasX) rest.push({ key: "x", label: c.label })
        }
        // A back paddle's pill is short ("P1"), so the label says what it is.
        return [{ key: k, label: /^p[1-4]$/.test(k) ? "Talk (back paddle)" : "Talk" }].concat(rest)
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
    // Maximized or not is kept too, so Huginn opens the way it was left (a minimized or full screen window keeps the last).
    function normalVisibility() { return Settings.windowMaximized && !app.touring ? Window.Maximized : Window.Windowed }
    onVisibilityChanged: {
        if (!app.placed || app.touring || app.quitting) return
        if (app.visibility === Window.Maximized) Settings.windowMaximized = true
        else if (app.visibility === Window.Windowed) Settings.windowMaximized = false
    }
    property bool placed: false
    onXChanged: keepRect.restart()
    onYChanged: keepRect.restart()
    onWidthChanged: keepRect.restart()
    onHeightChanged: keepRect.restart()
    Shortcut { sequence: "Ctrl+/"; context: Qt.ApplicationShortcut; enabled: app.keysOn
               onActivated: shortcutsPanel.visible ? shortcutsPanel.close() : shortcutsPanel.open() }
    // Alt+Up and Alt+Down: the chat above or below in the list (direct chats, then groups), like Discord.
    Shortcut { sequence: "Alt+Up"; context: Qt.ApplicationShortcut; enabled: app.keysOn && !app.overlay(); onActivated: app.stepChat(-1) }
    Shortcut { sequence: "Alt+Down"; context: Qt.ApplicationShortcut; enabled: app.keysOn && !app.overlay(); onActivated: app.stepChat(1) }
    function stepChat(dir) {
        if (Vault.screenLocked) return
        const list = Chat.chats.map(c => c.key).concat(Chat.groups.map(g => "g:" + g.id))
        if (!list.length) return
        const here = pageName.startsWith("g:") ? pageName : pageName === "dms" ? Chat.openChat : ""
        const i = list.indexOf(here)
        const next = list[i < 0 ? (dir > 0 ? 0 : list.length - 1) : (i + dir + list.length) % list.length]
        if (next.startsWith("g:")) openPage(next, { zone: "messages" })
        else openPage("dms", { open: next })
    }
    Shortcut { sequence: "Ctrl+Shift+L"; context: Qt.ApplicationShortcut; enabled: app.keysOn && !app.portrait; onActivated: app.togglePane("list") }
    Shortcut { sequence: "Ctrl+Shift+U"; context: Qt.ApplicationShortcut; enabled: app.keysOn && !app.portrait; onActivated: app.togglePane("members") }
    // Panic lock from the keyboard (a controller holds L3 and R3).
    Shortcut { sequence: "Ctrl+Shift+Q"; context: Qt.ApplicationShortcut; enabled: app.keysOn && Vault.panicLock; onActivated: Vault.panic() }
    // Mute and Deafen: Ctrl+Shift+M and D, or what Settings › Calls set (not while new ones are being picked there).
    Shortcut { sequence: GlobalKeys.muteSequence; context: Qt.ApplicationShortcut; enabled: app.keysOn && GlobalKeys.capturing === 0; onActivated: Ui.toggleMic() }
    Shortcut { sequence: GlobalKeys.deafenSequence; context: Qt.ApplicationShortcut; enabled: app.keysOn && GlobalKeys.capturing === 0; onActivated: Ui.toggleDeaf() }
    // The same two while a game is in front (GlobalKeys: in a voice room or a call, where the system allows it), with a
    // short sound, since a toast would be behind the game: rising when back on, falling when off.
    Connections {
        target: GlobalKeys
        function onMutePressed() { Ui.toggleMic(); GlobalKeys.cue(!Ui.micMuted) }
        function onDeafenPressed() { Ui.toggleDeaf(); GlobalKeys.cue(!Ui.deafened, true) }
    }
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
        // Locked: only the power button has a menu (Lock is already done; Close Huginn).
        if (lockedOut && e.id !== "exit") return
        ctxMenu.openAt(item, x, y, () => {
            if (e.kind === "group") { PeopleActions.groupRowMenu(String(e.id).substring(2), e.label, { tour: app.touring }); return }
            const later = f => Qt.callLater(() => { const p = page(); if (p && typeof p[f] === "function") p[f]() })
            let items = []
            if (e.id === "dms") items = [{ name: "Open", icon: "thread", url: "open" }, { name: "New chat", icon: "plus", url: "chat" },
                                         { name: "New group", icon: "people", url: "group" }, { name: "New folder", icon: "folder", url: "folder" }]
            else if (e.id === "newgroup") items = plusItems
            else if (e.id === "contacts") items = [{ name: "Open", icon: "person", url: "open" }, { name: "Add someone", icon: "plus", url: "chat" },
                                                   { name: "One-time link", icon: "attach", url: "once" }]
            else if (e.id === "files") items = [{ name: "Open", icon: "folder", url: "open" }, { name: "Transfers", icon: "down", url: "transfers" },
                                                { name: "Look again", icon: "search", url: "look" }]
            else if (e.id === "settings") items = [{ name: "Look", icon: "image", url: "s:look" }, { name: "Notifications", icon: "bell", url: "s:notify" },
                                                   { name: "Privacy and lock", icon: "lock", url: "s:privacy" }, { name: "Devices and backup", icon: "phone", url: "s:devices" },
                                                   { name: "Keyboard shortcuts", icon: "keyboard", url: "keys" }]
            else if (e.id === "exit") items = rail.menuItems.map(m => ({ name: m.label, icon: m.icon, url: m.id, danger: !!m.danger }))
            Ui.pickFrom(e.label || "", [{ header: "", items: items }], a => {
                if (app.touring) return
                if (a === "open") openPage(e.id, {})
                else if (a === "group") newGroup()
                else if (a === "chat") { openPage("dms", {}); later(e.id === "contacts" ? "addPage" : "newChat") }
                else if (a === "newchat" || a === "addpage") plusAction(a)
                else if (a === "join") { openPage("dms", {}); later("joinGroup") }
                else if (a === "folder") { openPage("dms", {}); later("newFolder") }
                else if (a === "once") PeopleActions.inviteLink()
                else if (a === "look") { openPage("files", {}); LocalSend.refresh() }
                else if (a.indexOf("s:") === 0) openPage("settings", { section: a.substring(2) })
                else if (a === "keys") shortcutsPanel.open()
                else if (a === "transfers") transfersPanel.open(false)
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
        else if (what === "call" && p && typeof p.callMenu === "function")
            Qt.callLater(() => p.callMenu())   // after the header has laid out its buttons
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
    function openFiles(where, title) { if (!lockedOut) chatFiles.open(where, title, app.touring) }
    // Files › Show in chat: the chat or group opens at the file's message, lit for a moment.
    function showFileInChat(where, name) {
        if (where.indexOf("g:") === 0) openPage(where, { showFile: name })
        else openPage("dms", { open: where, showFile: name })
    }
    // Labs: Report a bug (its screenshot is taken first, so the report screen isn't in it), and on the Huginn
    // with the Labs key, Labs reports.
    LabsReport { id: labsReport; z: 28 }
    LabsInbox { id: labsInbox; z: 28 }
    // Labs › App storage: files Huginn keeps that can go.
    AppStoragePanel { id: appStorage; z: 28 }
    // Labs › Performance check (under Report a bug, which it can open with its report).
    PerfPanel { id: perfPanel; z: 27.5 }
    function openReport() {
        if (lockedOut) return
        Labs.capture(app)
        labsReport.open(app.touring)
    }
    // Report a bug with a performance report going along (the person can switch it off there).
    function openReportWith(perfText) {
        Labs.capture(app)
        labsReport.openWith(perfText)
    }
    // Labs › Things to test › Report a problem: Report a bug with the test named at the start.
    function openReportText(t) {
        if (lockedOut) return
        Labs.capture(app)
        labsReport.open(false)
        labsReport.text = t
    }
    // Labs: the raven (Chats, at the top of the rail) held 7 seconds opens Report a bug with the screenshot and the
    // activity log. The mouse holds it on the rail (Rail.qml); A or Enter held on it with the rail focused does too.
    function openReportWithLog() {
        if (lockedOut || !Settings.labs || labsReport.visible) return
        Labs.capture(app)
        Labs.takeActivity()
        labsReport.open(app.touring)
    }
    Timer { id: ravenHold; interval: 7000; onTriggered: app.openReportWithLog() }
    function openPerf() { if (!lockedOut) perfPanel.open(app.touring) }
    function openLabsReports() { if (!lockedOut) labsInbox.open(app.touring) }
    function openAppStorage() { if (!lockedOut) appStorage.open(app.touring) }
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
    // Round 17 (B1): the current lock, asked before Back up, Restore, Move and linking another device (LockCheck.qml).
    LockCheck { id: lockCheck; z: 29 }
    // what: "backup", "restore", "movehere", "moveaway" or "link". then runs once the lock is given (at once without one).
    function askLock(title, why, what, then) {
        if (app.touring) { then(); return }
        lockCheck.open(title, why, what, then)
    }
    // forgot: the lock screen's "Forgot it?" (what's here is erased, not kept, and a new lock is set after).
    function openBackup(mode, fromSetup, sample, forgot) {
        if (backupPanel.visible || (app.touring && !sample)) return
        // Locked: only the lock screen's "Forgot it?" restore.
        if (lockedOut && !forgot) return
        // Round 17: a restore from first setup once a lock is set asks for it too (Vault.restoreOpened takes that grant).
        if (fromSetup && mode === "restore" && !sample && !forgot && Vault.hasLock && !Vault.lockConfirmed("restore")) {
            askLock("Restore", "This replaces what's here. A copy is kept first", "restore", () => app.openBackup(mode, fromSetup, sample, forgot))
            return
        }
        if (!fromSetup) app.closePanels()
        backupPanel.open(mode, !!fromSetup, !!sample, "", !!forgot)
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
    // A notification clicked while locked: its chat, opened once Huginn is unlocked (plan 2.14 point 3).
    property string afterUnlock: ""
    Connections {
        target: Vault
        function onChanged() {
            if (app.setupWaits && !Vault.screenLocked) { app.setupWaits = false; app.openSetup(false) }
            if (app.afterUnlock !== "" && !Vault.screenLocked) {
                const w = app.afterUnlock
                app.afterUnlock = ""
                Qt.callLater(() => app.openNoticed(w))
            }
        }
    }
    // Where a clicked notification goes: its chat (a contact's key, "g:<group>" or "g:<group>:<channel>", a phone's
    // "mirror:<peer>#<channel>"; "" the chat list). Locked: the chat list with the lock, then that chat once unlocked.
    function openNoticed(where) {
        if (Vault.screenLocked || where === "") {
            app.afterUnlock = Vault.screenLocked ? where : ""
            if (Vault.screenLocked && where !== "") app.beforeLock = null   // the clicked chat wins over the page before the lock
            app.openPage("dms", {})
            return
        }
        if (where.startsWith("mirror:")) app.openPage("dms", { mirror: where.substring(7) })   // a chat from the phone ("<peer>#<channel>" opens that tab)
        else if (where.startsWith("g:")) {
            const parts = where.split(":")
            app.openPage("g:" + parts[1], parts.length > 2 ? { channel: parts.slice(2).join(":"), zone: "messages" } : {})
        } else app.openPage("dms", { open: where, noticed: true })
    }
    // The tour's stops on the real screen, in window coordinates ({x, y, width, height}), or null when not on show.
    function tourSpot(id) {
        const left = rail.visible ? rail.width : 0, top = topBar.height
        if (id === "groups" || id === "settings" || id === "newgroup") return rail.spotRect(id)
        // The top bar: search (Ctrl+K) and Transfers.
        if (id === "search") return { x: Math.round(app.width / 4), y: 0, width: Math.round(app.width / 2), height: topBar.height }
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
        if (lockCheck.visible) lockCheck.close()
    }
    // Linked devices: always says where this computer's chats are mirrored.
    Rectangle {
        readonly property var on: Linked.companions.filter(c => c.ok)
        visible: on.length > 0 && !app.lockedOut   // round 17: the linked devices' names never show over the lock
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

    // A checked update waiting: a slim bar along the top; Huginn installs it only on Restart now. Later puts the bar
    // away until the next timed check (or the next start); Settings › Updates and about still has Restart now.
    property bool updateLater: false
    Connections {
        target: Updater
        function onRemind() { app.updateLater = false }
    }
    // Ultra privacy (src/ultraprivacy.h): Tor slow, blocked or not starting says so once each time, with Wait (or Try
    // again when it couldn't start) and Turn off Ultra privacy (back to the normal way). The same stay in Settings ›
    // Privacy and lock while it lasts. Round 19: it opens by itself, so never over the lock, a ring, a call, a prompt or
    // another question (it waits until they're gone), and it takes no press for its first half second. What its buttons
    // do is fixed when it opens, so a "Wait" never turns into Turn off.
    property bool ultraWarned: false
    function showUltraBanner() {
        if (!UltraPrivacy.bannerShows) {
            app.ultraWarned = false
            if (confirm.visible && confirm.tag === "ultra") confirm.dismiss()
            return
        }
        if (app.ultraWarned || app.touring || app.lockedOut || confirm.visible || incomingCall.visible || callPage.visible
                || prompt.visible || lockCheck.visible || updating.visible || ctxMenu.visible) return
        const missing = UltraPrivacy.torState === "missing"
        app.ultraWarned = true
        confirm.open("Ultra privacy", UltraPrivacy.bannerText, missing ? "Try again" : "Wait",
                     () => { if (missing) UltraPrivacy.tryAgain(); else UltraPrivacy.waitLonger() }, false, false, null, "ultra")
        confirm.otherText = "Turn off Ultra privacy"
        confirm.otherCallback = () => {
            if (app.lockedOut) return   // never from behind the lock
            UltraPrivacy.turnOff()
            Ui.say("Ultra privacy is off.")
        }
        app.guardOverlay(confirm)
    }
    Connections {
        target: UltraPrivacy
        function onChanged() { app.showUltraBanner() }
    }
    // A ring or a call that comes while it shows takes its place (the notice comes back once they're gone).
    Connections {
        target: incomingCall
        function onVisibleChanged() { if (incomingCall.visible) app.dropUltraBanner() }
    }
    Connections {
        target: callPage
        function onVisibleChanged() { if (callPage.visible) app.dropUltraBanner() }
    }
    function dropUltraBanner() {
        if (confirm.visible && confirm.tag === "ultra") { confirm.dismiss(); app.ultraWarned = false }
    }
    // Held back by the lock, a call or another question: shown once they're gone (onTopOverlayChanged too).
    Connections {
        target: app
        function onLockedOutChanged() {
            if (app.lockedOut) app.ultraWarned = false   // the lock took it away: said again once unlocked
            else {
                Qt.callLater(app.showUltraBanner)
                Qt.callLater(app.askTorChoice)
            }
        }
    }
    Rectangle {
        id: updateBar
        anchors.top: parent.top; anchors.horizontalCenter: parent.horizontalCenter
        anchors.topMargin: Theme.px(8)
        z: 55
        visible: Updater.ready && !Updater.installing && !app.touring && !app.updateLater
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
                width: Math.min(implicitWidth, app.width - Theme.px(280))
                elide: Text.ElideRight
                text: "Huginn " + Updater.latest + " is ready. Restart now installs it."
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
            HButton { text: "Later"; kind: "plain"; onClicked: { app.updateLater = true; Ui.say("Restart now is in Settings › Updates and about.") } }
            HButton { text: "Restart now"; kind: "primary"; onClicked: app.restartForUpdate() }
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

    // Wait for it was picked: a slim bar says Huginn closes (or restarts) when the file is done, and Don't close
    // takes it back.
    Rectangle {
        id: waitBar
        anchors.top: parent.top; anchors.horizontalCenter: parent.horizontalCenter
        anchors.topMargin: Theme.px(8) + (updateBar.visible ? updateBar.height + Theme.px(12) : 0)
                           + (unreadableBar.visible ? unreadableBar.height + Theme.px(12) : 0)
        z: 55
        visible: app.afterFiles !== "" && !app.touring
        width: Math.min(app.width - Theme.px(24), waitRow.implicitWidth + Theme.px(24))
        height: waitRow.implicitHeight + Theme.px(12)
        color: Theme.panel
        border.color: Theme.accent; border.width: Math.max(1, Theme.px(1))
        Row {
            id: waitRow
            anchors.centerIn: parent
            spacing: Theme.px(12)
            Text {
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                width: Math.min(implicitWidth, app.width - Theme.px(200))
                elide: Text.ElideMiddle
                text: (app.afterFiles === "restart" ? "Restarts to update when " : "Closes when ")
                      + (app.waitFiles.length > 1 ? app.waitFiles.length + " files are done" : (app.waitFiles.length === 1 ? app.waitFiles[0].name : "the file") + " is done")
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
            HButton {
                text: app.afterFiles === "restart" ? "Don't restart" : "Don't close"
                onClicked: app.cancelWait(app.afterFiles === "restart" ? "The update waits. Restart now is in Settings › Updates and about." : "Huginn stays open.")
            }
        }
    }

    // A message or missed call the system couldn't show as a notification (no notification service, or a game in front
    // in game mode): the newest one waits here, under the top bar, with how many came. It goes when its chat opens by
    // any route, on A or B (the controller), or 10 seconds after Huginn is in front again. With Huginn in front it's only
    // a toast, so A keeps doing what it was doing.
    Rectangle {
        id: newsBanner
        property string title: ""
        property string body: ""
        property string where: ""
        property int count: 0              // how many came since it showed (more than one: A opens Chats)
        property bool away: false          // it came while another window (a game) was in front
        function show(t, b, w) {
            if (Qt.application.state === Qt.ApplicationActive && !visible) {
                Ui.say(b !== "" ? t + ": " + b : t)
                return
            }
            count = visible ? count + 1 : 1
            title = t; body = b; where = w
            away = true
            if (!visible) app.guardOverlay(newsBanner)
            visible = true
            if (newsGone.running) newsGone.restart()
        }
        function hide() { visible = false; count = 0 }
        function open() {
            const w = count > 1 ? "" : where
            hide()
            if (!app.touring) Chat.showChat(w)
        }
        // The controller, while it shows: A opens it, B closes it, and any other button closes it and does its own job.
        readonly property var prompts: [{ key: "a", label: "Open" }, { key: "b", label: "Close" }]
        function handleButton(b) {
            if (b === "a") open()
            else if (b === "b") hide()
            else { hide(); app.press(b) }
        }
        // Its chat opened (from the list, a notification or anywhere else).
        readonly property string openNow: Chat.openChat
        onOpenNowChanged: if (visible && openNow !== "" && (openNow === where || openNow.startsWith(where + ":"))) hide()
        Timer {
            id: newsGone
            interval: 10000
            running: newsBanner.visible && Qt.application.state === Qt.ApplicationActive && !app.touring
            onTriggered: newsBanner.hide()
        }
        visible: false
        anchors.top: topBar.bottom; anchors.topMargin: Theme.px(8)
        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
        z: 54
        width: Math.min(app.width - Theme.px(24), Theme.px(380))
        height: newsCol.implicitHeight + Theme.px(20)
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.accent; border.width: Math.max(1, Theme.px(1))
        Accessible.role: Accessible.AlertMessage
        Accessible.name: title + ". " + body
        Column {
            id: newsCol
            x: Theme.px(14); y: Theme.px(10)
            width: parent.width - Theme.px(28) - newsClose.width
            spacing: Theme.px(2)
            Text {
                textFormat: Text.PlainText
                width: parent.width
                elide: Text.ElideRight
                text: newsBanner.title
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width
                elide: Text.ElideRight
                maximumLineCount: 2
                wrapMode: Text.Wrap
                text: newsBanner.body
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(14)
            }
            Text {
                textFormat: Text.PlainText
                visible: text !== ""
                width: parent.width
                elide: Text.ElideRight
                text: [newsBanner.count > 1 ? newsBanner.count + (System.gameMode ? " new while you were playing" : " new while you were away")
                       : newsBanner.away ? (System.gameMode ? "While you were playing" : "While you were away") : "",
                       app.padInUse ? (Gamepad.style, Gamepad.glyph("a")) + ": Open  " + (Gamepad.style, Gamepad.glyph("b")) + ": Close" : ""].filter(x => x !== "").join("  ·  ")
                color: Theme.accent
                font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
            }
        }
        // A click on the words opens that chat.
        MouseArea {
            anchors.left: parent.left; anchors.top: parent.top; anchors.bottom: parent.bottom
            anchors.right: newsClose.left
            cursorShape: Qt.PointingHandCursor
            onClicked: newsBanner.open()
        }
        HButton {
            id: newsClose
            anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            text: "Close"; kind: "plain"
            onClicked: newsBanner.hide()
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
        placeWindow()
        if (!fullNow && Settings.windowMaximized && !touring) app.visibility = Window.Maximized
        placed = true
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
        // Locked: who wants to send what waits behind the lock, and is asked once Huginn is unlocked.
        if (lockedOut && r.active) return
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
        app.guardOverlay(confirm)
    }
    // The phone unlinked this PC and its own name from before wasn't known: ask for one, once a run, so its own
    // account doesn't keep the phone's name (Linked.nameWanted).
    property bool askedOwnName: false
    function askOwnNameSoon() {
        if (askedOwnName || touring || !Linked.nameWanted || !Chat.ready || Vault.screenLocked) return
        askedOwnName = true
        Qt.callLater(() => PeopleActions.askOwnName("Saved."))
    }
    // Round 16 (cap l4): a linked device found on an older version (it sends only unstamped frames, and none is taken).
    // Asked once a run per device, with Link again through the usual 4 emoji (Linked.older).
    property var olderAsked: ({})
    function askOlderSoon() {
        if (touring || !Chat.ready || Vault.screenLocked || confirm.visible) return
        const d = Linked.older.find(o => !app.olderAsked[o.key])
        if (!d) return
        const asked = Object.assign({}, app.olderAsked)
        asked[d.key] = true
        app.olderAsked = asked
        Qt.callLater(() => {
            confirm.open(d.name, d.text, "Link again", () => app.relinkOlder(d), false, false, null, "older")
            confirm.cancelText = "Not now"
            app.guardOverlay(confirm)
        })
    }
    // The phone: Link your phone shows a code again. Another device: paste the code it shows.
    function relinkOlder(d) {
        if (d.phone) { showLinkPhone(false); return }
        // Round 17 (B1): linking another device shows it this PC's chats, so the lock is asked first.
        app.askLock("Link " + d.name + " again", "It will show this PC's chats", "link", () => {
            Ui.ask("Link " + d.name + " again", "Paste the code " + d.name + " shows.", "", "Link", t => {
                const r = Linked.link(t)
                Ui.say(r === "" ? "Check the 4 emoji match." : r, r !== "")
            })
        })
    }
    Connections {
        target: Linked
        function onChanged() { app.askOwnNameSoon(); app.askOlderSoon() }
        function onPicturesChanged() { app.askOwnNameSoon() }
    }
    // Round 17: while locked, a toast never says who or what ("File from Mira: ...", "Missed call from Mira"): only
    // "New activity". The latest real one is said once Huginn is unlocked.
    property string heldNotice: ""
    property int heldCount: 0
    function sayUnlessLocked(text, bad) {
        if (!app.lockedOut) { Ui.say(text, bad); return }
        if (text === "") return
        if (text.indexOf("New activity") < 0) { app.heldNotice = text; app.heldCount++ }
        Ui.say("New activity.")
    }
    function sayHeld() {
        if (heldNotice === "") return
        const t = heldNotice, more = heldCount - 1
        heldNotice = ""; heldCount = 0
        Qt.callLater(() => Ui.say(more > 0 ? t + " (and " + more + " more while locked)" : t))
    }
    // Chat: notices, and a toast for a message unless that chat is on screen (main.cpp plays the sound).
    Connections {
        target: Chat
        function onNotice(text) { app.sayUnlessLocked(text) }
        // A longer video's Save, written on its own thread.
        function onMediaSaved(path) { Ui.say(path !== "" ? "Saved in " + (Transfer.saveName || "Downloads") + " › Huginn." : "Couldn't save the video.", path === "") }
        function onLiveChanged() {
            const a = Chat.active, t = Date.now(), seen = Object.assign({}, app.lastActive)
            for (const k in a) seen[k] = t
            app.lastActive = seen
            app.clock = t
        }
        // A notification was clicked: its chat (a contact's key, "g:<group>" or "g:<group>:<channel>"; "" the chat list).
        function onShowChat(where) {
            if (newsBanner.visible && newsBanner.where === where) newsBanner.hide()
            app.openNoticed(where)
        }
        // The system couldn't show a notification: the banner at the top of the window says it instead.
        function onBanner(title, body, where) { newsBanner.show(title, body, where) }
        // The tray icon's menu (Windows).
        function onTrayAction(what) {
            // A click on an older notification Windows can't tell apart: the chat list, not the wrong chat.
            if (what === "chats") {
                if (newsBanner.visible) newsBanner.hide()
                app.openPage("dms", {})
                return
            }
            if (what === "mute") {
                // A local mute: Do not disturb (seen by others) is only ever set from your card.
                if (Ui.myStatus === "dnd") { Ui.say("Do not disturb is on. Your card changes it."); return }
                app.trayMuted = !app.trayMuted
                const said = app.trayMuted ? "Notifications are muted on this computer until Huginn restarts." : "Notifications are on again."
                // From the tray with the window out of sight, a toast in it wouldn't be seen.
                if (Qt.application.state !== Qt.ApplicationActive) System.notifyNow("Huginn", said)
                Ui.say(said)
                return
            }
            // A call, a voice room or a transfer: Huginn comes up and asks first, as the X does.
            if (LocalSend.busy || FileLink.closeWarning !== "" || app.inVoice) {
                if (app.visibility === Window.Minimized || app.visibility === Window.Hidden) app.visibility = app.fullNow ? Window.FullScreen : app.normalVisibility()
                app.raise(); app.requestActivate()
                app.requestExit(false)
                return
            }
            if (what === "lock" && Vault.hasLock && !Vault.screenLocked) Vault.lockScreen()
            app.quitNow()
        }
        function onArrived(name, text, key) {
            // A phone group's message names its channel ("mirror:<peer>#<channel>"): on screen as that tab, or as one list.
            const shown = Chat.openChat === key || Chat.openChat.startsWith("mirror:") && key.startsWith(Chat.openChat + "#")
            if (shown && (app.pageName === "dms" || app.pageName.startsWith("g:"))) return
            // Muted, hidden or reduced (Chat.notifyFor): no toast either.
            if (!Chat.alerted(key)) return
            // An @mention always says so (without the text unless previews are on).
            // Settings › Notifications › Show: Name and message, Name only, or Nothing.
            const mention = name.indexOf(" mentioned you in ") > 0 || name.indexOf("Report in ") === 0
            const hidden = Vault.screenLocked
            const mode = hidden ? 0 : Chat.previewMode
            Ui.say(mode === 2 ? name + ": " + text : mode === 1 || mention && !hidden ? name + "." : "New message.")
        }
    }
    // Calls: how a call ended, as a toast.
    Connections {
        target: Calls
        function onFinished(t) { app.sayUnlessLocked(t) }
    }
    Connections {
        target: Rooms
        function onFinished(t) { app.sayUnlessLocked(t) }
    }
    // Push to talk on Windows only hears the key while Huginn is in front (Huginn never watches keys in other apps, on
    // purpose), and on Linux too when the desktop offers no shortcut portal (GlobalKeys.everywhere false). It can't tell when the key was held behind a game, so the first time Huginn is back in front after being
    // behind in a voice room with push to talk on, a toast says so (once per room).
    readonly property bool inFront: Qt.application.state === Qt.ApplicationActive
    // A controller's talk button never works behind a game either (padLive), even with the shortcut portal.
    readonly property bool talkInFrontOnly: Qt.platform.os === "windows" || !GlobalKeys.everywhere || (TalkKey.key === 0 && TalkKey.padButton !== "")
    property bool talkWentBehind: false
    property bool talkToldOnce: false
    onInFrontChanged: {
        if (!app.talkInFrontOnly || app.touring || !Rooms.inRoom || !Settings.pushToTalk) return
        if (!app.inFront) { app.talkWentBehind = true; return }
        if (!app.talkWentBehind || app.talkToldOnce) return
        app.talkWentBehind = false
        app.talkToldOnce = true
        if (!TalkKey.hasKey) return
        Ui.say("Push to talk only hears " + (TalkKey.key !== 0 ? TalkKey.keyName : Gamepad.glyph(TalkKey.padButton)) + " while Huginn is in front. In a game, use voice activity (Settings › Calls › Voice rooms).")
    }
    // Push to talk on with no key or controller button picked, or only a controller button and no controller connected:
    // the mic uses voice activity, so joining a room says so (once per room a run).
    property var talkNoKeyTold: ({})
    Connections {
        target: Rooms
        function onChanged() {
            if (!Rooms.inRoom) {
                app.talkWentBehind = false; app.talkToldOnce = false
                // Muted only because the controller went away: the next call or room starts as before.
                if (app.padMutedMic) { app.padMutedMic = false; Settings.micMuted = false }
                return
            }
            if (app.touring || !Settings.pushToTalk || TalkKey.canTalk || TalkKey.padGone || Rooms.room === "") return
            const id = Rooms.group + "/" + Rooms.room
            if (app.talkNoKeyTold[id]) return
            app.talkNoKeyTold[id] = true
            if (TalkKey.padAway)
                Ui.say("Your talk button is " + Gamepad.glyph(TalkKey.padButton) + ", but no controller is connected: using voice activity.")
            else
                Ui.say("No talk key yet: using voice activity. Pick a key in Settings › Calls › Voice rooms.")
        }
    }
    // The controller with the talk button went away (or slept) mid-room: push to talk stays on and the mic is muted,
    // never an open mic. It comes back unmuted only if this muted it.
    property bool padMutedMic: false
    // Quitting (Quit, Restart to update) while muted only by that: the next run's rooms and calls don't start muted.
    Connections {
        target: Qt.application
        function onAboutToQuit() { if (app.padMutedMic) { app.padMutedMic = false; Settings.micMuted = false } }
    }
    // The talk key typed into a text box during a voice room (once a run): say how to talk while typing.
    Connections {
        target: TalkKey
        function onControllerGone() {
            if (!Ui.micMuted) { Ui.toggleMic(); app.padMutedMic = true }
            app.sayOutOfSight("Huginn", "Controller gone: mic muted. Connect it again to talk.", true)
        }
        function onControllerBack() {
            if (app.padMutedMic && Rooms.inRoom && Ui.micMuted) Ui.toggleMic()
            app.padMutedMic = false
            Ui.say("Controller back: hold " + Gamepad.glyph(TalkKey.padButton) + " to talk.")
        }
        function onTypedInstead() {
            Ui.say(TalkKey.keyName + " typed into the text box. To talk while typing, pick a key that doesn't type, like Insert, Pause or Scroll Lock (Settings › Calls › Voice rooms).")
        }
    }
    // Panic lock (hold L3 and R3): close anything open over the screen. Report a bug stays, behind its own lock
    // (Labs › Performance check's results stay too, for when it's opened again).
    Connections {
        target: Vault
        function onPanicked() {
            app.afterUnlock = ""   // a panic lock never opens a chat by itself after
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
        // Round 19 (FIX 8): antivirus took tor.exe away during the update. Asked once: update without Tor, or not now
        // (allow tor.exe, then Check now). Never over the lock, a call or another question: it waits for them.
        function onTorChoice() { app.torAskPending = true; app.askTorChoice() }
    }
    property bool torAskPending: false
    function askTorChoice() {
        if (!torAskPending || !Updater.torBlocked) { torAskPending = false; return }
        if (touring || lockedOut || overlay() !== null) return   // onTopOverlayChanged and the unlock ask again
        torAskPending = false
        confirm.open("Update without Tor?", Updater.torBlockedText(), "Update without Tor", () => Updater.updateWithoutTor(),
                     false, false, null, "updatetor")
        confirm.cancelText = "Not now"
        app.guardOverlay(confirm)
    }
    // Opening a received program (or a file whose name hides what it is) asks first, wherever Open was pressed.
    Connections {
        target: FileLink
        function onAskOpen(path, warning) {
            if (app.lockedOut) return
            Ui.sure("Open this file?", warning, "Open anyway", () => { if (!FileLink.openAnyway(path)) Ui.say("It can't be opened here.", true) }, false, true)
            confirm.otherText = "Show in folder"
            confirm.otherCallback = () => FileLink.showInFolder(path)
        }
    }
    Connections {
        target: LocalSend
        function onRequestChanged() { app.showSendRequest() }
        function onFinished(ok, message) { app.sayUnlessLocked(message, !ok) }
        function onPinNeeded(deviceId, wrong) {
            // Round 17: a send started before the lock never asks over it (the device's name, and a send from behind it).
            if (app.lockedOut) { LocalSend.cancelSend(); Ui.say("Sending stopped: Huginn is locked."); return }
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
        if (lockCheck.visible) return lockCheck   // round 17: the lock asked before Back up, Move or linking
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
        // Only one that came while Huginn was behind takes the buttons (a passing notice never changes what A does).
        if (newsBanner.visible && newsBanner.away && app.padInUse) return newsBanner
        return null
    }
    function page() { return pageLoader.item && pageLoader.status === Loader.Ready ? pageLoader.item : null }

    // A question that opens by itself (a ring, a nearby-sharing request, a notice that came while away) never takes a
    // press meant for what was on screen: for half a second after it shows, every button is ignored, and a button
    // already held when it showed is let go without answering. It takes a fresh press.
    // Each guarded question keeps its own half second and its own held buttons, so one that opens on top of another
    // (a message banner during a ring) never takes the other's guard away.
    property var guards: []
    // Buttons down now that will be let go (controller buttons, and Enter, Space and Menu on a keyboard).
    property var heldNow: ({})
    function guardOverlay(o) {
        const kept = guards.filter(g => g.o !== o && g.o.visible)
        kept.push({ o: o, at: Date.now(), held: Object.assign({}, heldNow) })
        guards = kept
    }
    function guardOf(o) {
        for (const g of guards)
            if (g.o === o) return g
        return null
    }
    // How long ago a guard started. A clock stepped backwards (after a sleep) counts as long ago, so it never keeps
    // every button ignored for as long as the step.
    function sinceGuard(g) { const d = Date.now() - g.at; return d < 0 ? 1e9 : d }
    function overlayGuarded() {
        const o = overlay()
        const g = o !== null ? guardOf(o) : null
        return g !== null && sinceGuard(g) < 500
    }
    // That overlay's own half second (a tap or click on its buttons is ignored then too: IncomingCall, ConfirmDialog).
    function guardedNow(o) {
        const g = o ? guardOf(o) : null
        return g !== null && sinceGuard(g) < 500
    }
    Connections {
        target: incomingCall
        function onVisibleChanged() { if (incomingCall.visible) app.guardOverlay(incomingCall) }
        // A ring that changes while it shows (a 1:1 call arriving over a group's ring: Join becomes Answer): a new
        // half second, so an A meant for the one before never answers the new one.
        function onWhoChanged() { if (incomingCall.visible) app.guardOverlay(incomingCall) }
        function onGroupChanged() { if (incomingCall.visible) app.guardOverlay(incomingCall) }
        function onVideoChanged() { if (incomingCall.visible) app.guardOverlay(incomingCall) }
    }
    // The overlay on top right now: a voice message held on Y stops when anything opens over the chat (a ring, a
    // question, a panel), and is thrown away, never sent by the Y release that the overlay takes.
    readonly property var topOverlay: overlay()
    onTopOverlayChanged: {
        if (topOverlay !== null) dropVoice()
        // Round 17: a question that opened by itself under another one (a ring under a menu or the Delete question)
        // gets its half second when that one goes and it comes to the top, not only when it first showed. (One
        // that opens with nothing over it is guarded where it opens.)
        if (topBefore !== null && selfOpening(topOverlay)) guardOverlay(topOverlay)
        topBefore = topOverlay
        // Round 19: the Ultra privacy notice held back by a question, ring or call shows once nothing is open.
        if (topOverlay === null && UltraPrivacy.bannerShows && !ultraWarned) Qt.callLater(showUltraBanner)
        if (topOverlay === null && torAskPending) Qt.callLater(askTorChoice)
    }
    property var topBefore: null
    function selfOpening(o) {
        if (o === null) return false
        if (o === incomingCall || o === newsBanner) return true
        return o === confirm && (confirm.tag === "localsend" || confirm.tag === "older" || confirm.tag === "ultra" || confirm.tag === "updatetor")
    }
    // The lock (the panic lock too) throws away a voice message being held, never sends it.
    readonly property bool screenDown: Vault.screenLocked
    onScreenDownChanged: if (screenDown) dropVoice()
    // Voice threw one away by itself (let go in another chat, or the device slept while it was held).
    Connections {
        target: Voice
        function onDropped() { Ui.say("Recording cancelled.") }
    }
    Connections {
        target: app
        function onInVoiceChanged() { if (app.inVoice) app.dropVoice() }
    }
    function dropVoice() {
        if (!Voice.recording) return
        Voice.cancel()
        Ui.say("Recording cancelled.")
    }

    function railButton(b) {
        switch (b) {
        // Up past Chats: the top bar's Search, Transfers and Keyboard shortcuts.
        case "up": if (railIndex === 0) topBarMenu(); else railIndex = railIndex - 1; break
        case "down": railIndex = Math.min(railItems.length - 1, railIndex + 1); break
        case "a": case "right": openRail(railIndex); break
        case "b": requestExit(); break
        case "start": openPage("settings", {}); break
        // LT (or X): the rail button's own menu, as a right click opens it.
        case "lt": case "x": menuKey(); break
        }
    }
    function topBarMenu() {
        if (lockedOut) return
        Ui.pickFrom("Top bar", [{ header: "", items: [{ name: "Go to a chat", icon: "search", url: "search" },
                                                      { name: "Transfers", icon: "down", url: "transfers" },
                                                      { name: "Keyboard shortcuts", icon: "keyboard", url: "keys" }] }], a => {
            if (app.touring) return
            if (a === "search") switcher.open()
            else if (a === "transfers") transfersPanel.open(false)
            else if (a === "keys") shortcutsPanel.open()
        })
    }
    // In a voice room or call: hold LB, then Up mutes and Down deafens (from any page).
    readonly property bool inVoice: Rooms.inRoom || Calls.inCall
    // The same short sounds as the mute and deafen keys (Settings › Calls › Mute and deafen sounds).
    function voiceKey(b) {
        if (b === "lb+up") {
            Ui.toggleMic()
            GlobalKeys.cue(!Ui.micMuted)
            Ui.say(Ui.micMuted ? "Muted." : "Unmuted.")
        } else {
            Ui.toggleDeaf()
            GlobalKeys.cue(!Ui.deafened, true)
            Ui.say(Ui.deafened ? "Deafened." : "Undeafened.")
        }
    }
    // LB on its own goes to the next section when it's let go; held, the D-pad's left and right
    // go back and forward instead (and it doesn't move on when let go).
    property bool lbHeld: false
    property bool lbUsed: false
    // The page has LB right now (the chat lock's pattern or password, a phone chat's channels).
    function pageOwnsLb() { const p = page(); return !!p && !!p.ownsShoulders && focusZone === "content" }
    function press(b) {
        // Select (View) shows or hides the button legend; any other button hides it and does its job.
        if (b === "back") { legend = !legend; return }
        legend = false
        // A tap on the legend's Mute or Deafen chip.
        if (b === "lb+up" || b === "lb+down") { if (inVoice) voiceKey(b); return }
        const o = overlay()
        // A question that just opened by itself: not yet (guardOverlay).
        if (o && overlayGuarded()) return
        if (o) { if (o.handleButton) o.handleButton(b); return }
        // Someone's shared screen made bigger: B makes it smaller again first.
        if (b === "b" && roomBar.screenBig && roomBar.screenOn) { roomBar.screenBig = false; return }
        const p = page()
        // The page has LB right now (the chat lock's pattern or password).
        const pageShoulders = p && p.ownsShoulders && focusZone === "content"
        // A voice message held on Y belongs to this chat: LB, Tab and Shift+Tab don't switch section or channel then.
        if (Voice.recording && (b === "lb" || b === "next" || b === "prev")) return
        if (b === "lb" && !pageShoulders) { lbHeld = true; lbUsed = false; return }
        if (lbHeld && (b === "left" || b === "right")) { lbUsed = true; stepSection(b === "left" ? -1 : 1); return }
        if (lbHeld && inVoice && (b === "up" || b === "down")) { lbUsed = true; voiceKey("lb+" + b); return }
        // Tab and Shift+Tab on the keyboard.
        if ((b === "next" || b === "prev") && !pageShoulders) { stepSection(b === "prev" ? -1 : 1); return }
        // Labs: A held on the raven for 7 seconds (only a press whose release comes back).
        if (focusZone === "rail" && b === "a" && railIndex === 0 && Settings.labs && heldNow["a"] && !ravenHold.running) ravenHold.start()
        if (focusZone === "rail") { railButton(b); return }
        // LT opens the options, which pages know as X.
        if (b === "lt") b = "x"
        if (p && p.handleButton && p.handleButton(b)) return
        // What the page didn't use: Left and B go to the rail, Menu opens Settings.
        if (b === "left" || b === "b") focusRail()
        else if (b === "start") openPage("settings", {})
    }
    function release(b) {
        delete heldNow[b]
        if (b === "a") ravenHold.stop()
        // Held from before a question opened by itself: its release isn't an answer (for the one on top).
        // (A question closed and asked again later doesn't keep eating them: only within 10 seconds of it showing.)
        let eaten = false
        for (const g of guards) {
            if (!g.held[b]) continue
            delete g.held[b]
            if (g.o === overlay() && sinceGuard(g) < 10000) eaten = true
        }
        // An eaten LB release still ends the hold (or the D-pad would keep switching sections), and never steps.
        if (eaten) { if (b === "lb") { lbHeld = false; lbUsed = true } return }
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
    // The controller reaches Huginn even while a game is in front: only presses made with Huginn active and
    // focused count. A press that counted still gets its release.
    readonly property bool padLive: Qt.application.state === Qt.ApplicationActive && app.active
    // The on-screen keyboard (Steam's, or Windows' touch keyboard) comes up for the controller or touch only
    // (System.keyboardAvailable), never over a mouse or a real keyboard, a docked handheld on the desktop too.
    onPadInUseChanged: System.setControllerInUse(padInUse)
    property var padDown: ({})
    Connections {
        target: Gamepad
        function onPressed(button) {
            if (!app.padLive) return
            app.padDown[button] = true
            app.noteInput()
            app.padInUse = true
            // Picking the talk button (B stops), or the talk button held in a voice room: nothing else happens. LB as
            // the talk button still mutes and deafens with Up or Down while it's held (and doesn't change section).
            const picking = TalkKey.capturing
            if (TalkKey.pad(button, true)) {
                if (button === "lb" && !picking) { app.lbHeld = true; app.lbUsed = true }
                return
            }
            // A question that just opened by itself (guardOverlay): the press is dropped, release and all.
            if (app.overlayGuarded()) { delete app.padDown[button]; return }
            if (app.isPointerButton(button)) { app.legend = false; stick.press(button) }
            else { app.heldNow[button] = true; app.press(button) }
        }
        function onReleased(button) {
            if (!app.padDown[button]) return
            delete app.padDown[button]
            if (TalkKey.pad(button, false)) {
                if (button === "lb") app.lbHeld = false
                return
            }
            if (app.isPointerButton(button)) stick.release(button)
            else app.release(button)
        }
        // Round 17: let go because the controller went away, not by a thumb: a cancel. A voice message held on Y is
        // thrown away, LB never steps a section, the pointer's button never clicks; holds (Hold to erase) just stop.
        function onLost(button) {
            if (!app.padDown[button]) return
            delete app.padDown[button]
            if (TalkKey.pad(button, false)) {
                if (button === "lb") app.lbHeld = false
                return
            }
            if (app.isPointerButton(button)) { stick.lose(button); return }
            if (button === "y") app.dropVoice()
            if (button === "lb") app.lbUsed = true
            app.release(button)
        }
        function onConnectedChanged() { if (!Gamepad.connected) { app.padDown = ({}); app.heldNow = ({}); app.lbHeld = false; app.legend = false; app.padInUse = false; stick.hide(true) } }
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
        running: app.padLive && Math.abs(Gamepad.stickY) > dead
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
            // Esc and Backspace on the rail do nothing from the keyboard (only B on a controller asks to leave).
            if ((event.key === Qt.Key_Escape || event.key === Qt.Key_Backspace) && app.focusZone === "rail" && !app.overlay()) { app.padInUse = false; event.accepted = true; return }
            // In a call, Space mutes (a controller's X still turns the camera on and off).
            const b = event.key === Qt.Key_Space && callPage.visible && !incomingCall.visible ? "a" : map[event.key]
            // Only the keys whose release comes back here (Enter, Menu or F10, Space) count as held.
            const releases = event.key === Qt.Key_Return || event.key === Qt.Key_Enter || event.key === Qt.Key_Menu || event.key === Qt.Key_F10
                             || (event.key === Qt.Key_Space && b === "x")
            if (b && releases) app.heldNow[b] = true
            if (b) { if (!app.touring) { app.padInUse = false; System.noteKeyboardUsed() } app.press(b); event.accepted = true }
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
            // Dictation (shown for the tour only, nothing recorded or downloaded): the microphone in the message box,
            // listening; then in the prompt at the top on a handheld.
            { page: "dms", zone: "messages", dictation: true, file: "01b-dictation" },
            { page: "dms", rail: true, file: "02-rail" },
            { page: "files", file: "03-files" },
            { page: "settings", file: "05-settings" },
            { page: "settings", section: "privacy", file: "06-settings-lock" },
            { page: "contacts", file: "07-contacts" },
            { page: "dms", picker: true, file: "08-options" },
            { page: "dms", prompt: true, file: "09-write" },
            { page: "dms", hold: true, file: "10-confirm" },
            // A hold let go too soon: "Keep holding for 5 seconds" under Hold to block.
            { page: "dms", holdEarly: true, file: "10b-confirm-keep-holding" },
            // A question that opened by itself, then a banner over it at once: A within half a second is still not an
            // answer (each keeps its own guard).
            { page: "dms", guarded: true, file: "10c-confirm-guarded" },
            // Round 17: a request that came under a menu gets its own half second when the menu closes, and an LB let
            // go inside it ends the LB hold. A failed check is a QML warning, which fails the tour.
            { page: "dms", uncovered: true, file: "10d-confirm-uncovered" },
            // Round 17: the prompt at the top that a chat's box opened closes unsent when that chat changes under it.
            { page: "dms", zone: "messages", promptDrop: true, file: "10e-prompt-chat-changed" },
            { page: "dms", zone: "messages", newGroup: true, file: "11-new-group" },
            { page: "settings", section: "look", file: "12-look" },
            // Daily Hávamál (shown for the tour only, nothing saved): the card at the top of the chats, the verse opened,
            // Earlier verses, and Settings › Look with it on; then on a handheld, as the controller sees it.
            { page: "dms", zone: "list", verse: "card", file: "12b-daily-verse" },
            { page: "dms", zone: "list", verse: "open", file: "12c-daily-verse-open" },
            { page: "dms", zone: "list", verse: "earlier", file: "12d-daily-verse-earlier" },
            { page: "settings", section: "look", verse: "card", file: "12e-look-daily-verse" },
            // Pages one level down: Look › Accessibility › Read aloud, and Calls › Voice rooms.
            { page: "settings", section: "aloud", file: "12r-read-aloud" },
            { page: "settings", section: "voicerooms", file: "12v-voice-rooms" },
            { page: "dms", zone: "list", verse: "open", handheld: true, file: "12f-handheld-daily-verse" },
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
            // A contact's menu › Details, the other sub-page.
            { page: "dms", zone: "messages", person: true, personPage: "edit", file: "31-edit" },
            { page: "files", filesMenu: true, file: "32-files-menu" },
            // Labs mode: the one Labs page (Report a bug, Labs reports, the update channel), Report a bug, and Labs reports.
            { page: "settings", section: "labs", labs: true, file: "33-labs" },
            { page: "settings", section: "labs", labs: true, report: true, file: "34-labs-report" },
            { page: "dms", labsInbox: true, file: "35-labs-reports" },
            // Seasonal in Snow: the Look page with the season's settings (under More), then a conversation.
            { page: "settings", section: "look", more: true, theme: "dark", season: "snow", file: "36-snow-look" },
            { page: "g:tour-crew", zone: "messages", theme: "dark", season: "snow", file: "37-snow" },
            // Link your phone: the code, the 4 emoji and Linked (the code on the Snow ground too).
            { page: "dms", linkPhone: "code", file: "38-link-phone" },
            { page: "dms", linkPhone: "match", file: "39-link-match" },
            { page: "dms", linkPhone: "linked", file: "40-linked" },
            { page: "dms", linkPhone: "code", theme: "dark", season: "snow", file: "41-link-phone-snow" },
            // Settings › Notifications, and Find in a channel with its matches lit.
            { page: "settings", section: "notify", file: "42-notifications" },
            { page: "g:tour-crew", zone: "messages", find: "in", file: "43-find" },
            // Devices and backup › More › App storage (sample files: nothing on this computer is touched).
            { page: "settings", section: "devices", more: true, appStorage: true, file: "44-app-storage" },
            // Labs › Performance check with sample results (nothing is measured).
            { page: "settings", section: "labs", labs: true, perf: true, file: "45-performance-check" },
            // Seasonal over True black: the Look page (its More open) with leaves falling on pure black.
            { page: "settings", section: "look", more: true, theme: "black", season: "autumn", file: "46-look-seasonal-black" },
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
            // A chat's Media and files: square tiles (a photo, a video, a GIF, one not on this device), an item's menu, and Voice.
            { page: "dms", zone: "messages", shared: "media", file: "51-shared-media" },
            { page: "dms", zone: "messages", shared: "media", sharedMenu: true, file: "52-shared-media-menu" },
            { page: "dms", zone: "messages", shared: "voice", file: "53-shared-voice" },
            // Y (Next tab) on Media and files, as the controller presses it: Media to Links.
            { page: "dms", zone: "messages", shared: "media", sharedY: true, file: "53b-shared-next-tab" },
            // A draft over 2,000 characters: the count under the box says it's too long, and it stays (never cut).
            { page: "dms", zone: "messages", longDraft: true, file: "54-long-message" },
            // Right-click menus, each where a right click opens it: a person (with Mute open beside it), a group on the rail,
            // a folder, a message, a member, Settings on the rail, a text box, the empty list, and a group's Call popup.
            { page: "dms", folders: true, ctx: "person", file: "70-menu-person" },
            { page: "dms", ctx: "group", file: "71-menu-group" },
            { page: "dms", folders: true, ctx: "folder", file: "72-menu-folder" },
            { page: "g:tour-crew", zone: "messages", ctx: "message", file: "73-menu-message" },
            { page: "g:tour-crew", zone: "members", ctx: "member", file: "74-menu-member" },
            { page: "dms", ctx: "settings", file: "75-menu-settings" },
            { page: "dms", ctx: "field", file: "76-menu-text" },
            { page: "dms", ctx: "space", small: true, file: "77-menu-empty-list" },
            // A group's one Call button, its popup open: Call everyone, Pick people and Join voice room for each room.
            { page: "g:tour-crew", zone: "messages", ctx: "call", file: "78-menu-call" },
            // Settings › Devices and backup › Back up: what's inside, the password, saved; then Restore: choose a file, a wrong password, what's in it.
            { page: "settings", section: "devices", backup: "what", file: "80-backup" },
            { page: "settings", section: "devices", backup: "password", file: "81-backup-password" },
            { page: "settings", section: "devices", backup: "saved", file: "82-backup-saved" },
            { page: "settings", section: "devices", restore: "pick", file: "83-restore-choose" },
            { page: "settings", section: "devices", restore: "password", file: "84-restore-password" },
            { page: "settings", section: "devices", restore: "summary", file: "85-restore-summary" },
            // The handheld's layout (a 7 inch 1080p screen at its fit: 914 by 514 design px) in a voice room.
            { page: "g:tour-crew", zone: "messages", voicePanel: true, handheld: true, file: "86-handheld-voice" },
            { page: "g:tour-crew", zone: "messages", voicePanel: true, handheld: true, textSize: "115", file: "86b-handheld-voice-large" },
            { page: "dms", setup: "lock", handheld: true, file: "86c-handheld-setup-lock" },
            // The handheld at the Steam Deck's 883 by 552: Media and files after Y, Settings, and a draft carried on in
            // the prompt at the top (cursor at the end, nothing selected).
            { page: "dms", zone: "messages", shared: "media", sharedY: true, handheld: true, deck: true, file: "87-handheld-shared" },
            { page: "settings", handheld: true, deck: true, file: "87b-handheld-settings" },
            { page: "dms", zone: "messages", draftPrompt: true, handheld: true, deck: true, file: "87c-handheld-draft-prompt" },
            { page: "dms", zone: "messages", draftPrompt: true, handheld: true, file: "87d-handheld-draft-prompt-1080p" },
            { page: "dms", zone: "messages", draftPrompt: true, dictation: true, handheld: true, deck: true, file: "87e-handheld-dictation-prompt" },
            // Open on a received program whose name hides what it is: asked first, never run by one click.
            { page: "dms", zone: "messages", openProgram: true, file: "88-open-program" },
            // Round 17 (B1): Back up (and Move, Restore, linking) asks for the current lock first.
            { page: "settings", section: "devices", lockCheck: true, file: "89-confirm-lock" },
            // 1.42 lock rules: a wait in plain words (1 hour, and what comes next), the last stage with Erase after too many
            // tries on, counting down, and setting a pattern's unlock password. Privacy and lock (06) shows the Erase switch.
            { page: "settings", section: "privacy", lockCheck: true, lockFails: 10, file: "89b-lock-wait" },
            { page: "settings", section: "privacy", lockCheck: true, lockFails: 22, lockErase: true, file: "89c-lock-last-tries" },
            { page: "settings", section: "privacy", lockSpare: true, file: "89d-unlock-password" },
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
            setup.visible = false; backupPanel.visible = false; lockCheck.visible = false
            const shown = app.page()
            if (shown && shown.more) shown.more.sharedOpen = false
            if (step === 0) { savedTheme = Settings.theme; savedSeason = Settings.season; savedDensity = Settings.density; savedSize = Settings.uiSize; savedLabs = Settings.labs; savedText = Settings.textSize }
            if (step >= steps.length) {
                Settings.theme = savedTheme; Settings.season = savedSeason; Settings.density = savedDensity; Settings.uiSize = savedSize; Settings.labs = savedLabs; Settings.textSize = savedText
                app.width = 1920; app.height = 1080
                System.setTourHandheld(false); app.tourLegend = false
                DailyVerse.tour = false
                Dictation.tour = false
                stop(); System.tourFinished(); return
            }
            const s = steps[step]
            // The look changes once the new page is in (not while the old one is being taken down).
            const theme = s.theme || savedTheme, season = s.season || savedSeason, density = s.density || savedDensity, size = s.size || savedSize
            const textSize = s.textSize || savedText
            Qt.callLater(() => { Settings.theme = theme; Settings.season = season; Settings.density = density; Settings.uiSize = size; Settings.textSize = textSize })
            // A portrait window: the one-column layout.
            if (s.portrait) { app.width = 900; app.height = 1200 } else if (s.small) { app.width = 1024; app.height = 768 }
            else if (s.handheld && s.deck) { app.width = 883; app.height = 552 }
            else if (s.handheld) { app.width = 914; app.height = 514 } else { app.width = 1920; app.height = 1080 }
            app.tourVoice = !!s.voicePanel
            // Handheld stops: Handheld mode's sizes (44 px targets) and its button legend, as on the device.
            System.setTourHandheld(!!s.handheld)
            app.tourLegend = !!s.handheld
            Settings.labs = s.labs ? true : savedLabs
            DailyVerse.tour = !!s.verse
            Dictation.tour = !!s.dictation
            const args = { tour: true }
            if (s.section) args.section = s.section
            if (s.more) args.more = true
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
            if (s.draftPrompt) Ui.askMessage("Message Mira", "", "See you at the dock at seven, and bring the", "Send", null)
            if (s.longDraft) Qt.callLater(() => { const p = app.page(); if (p && p.mainCompose) p.mainCompose.text = "All the notes from the trip, in one go. ".repeat(56) })
            if (s.profile) Ui.showProfile("j", "Jake", { tour: true, group: "Gaming Crew", admin: true, room: "Voice", note: "Hosts raid night on Fridays. Bring snacks." })
            if (s.shortcuts) shortcutsPanel.open()
            if (s.transfers) transfersPanel.open(true)
            if (s.chatFiles) chatFiles.open("g:tour-crew", "Gaming Crew", true)
            if (s.person) personPanel.show("tour", "Mira", { tour: true, page: s.personPage || "can" })
            if (s.filesMenu) Qt.callLater(() => { const p = app.page(); if (p && p.filesMenu) p.filesMenu() })
            if (s.newGroup) app.newGroup()
            // Today's verse opened from the top of the list, or its Earlier verses (a QML warning fails the tour).
            if (s.verse === "open" || s.verse === "earlier") {
                const how = s.verse
                Qt.callLater(() => {
                    const p = app.page()
                    if (!p || !p.openVerse || !p.verseShown) { console.warn("Tour check: Daily Hávamál's card isn't at the top of the chats"); return }
                    if (p.entries[0].kind !== "verse") console.warn("Tour check: Daily Hávamál's card isn't the first row")
                    if (how === "open") p.openVerse(null)
                    else p.earlierVerses()
                })
            }
            if (s.report) Qt.callLater(app.openReport)
            if (s.labsInbox) app.openLabsReports()
            if (s.appStorage) app.openAppStorage()
            if (s.perf) app.openPerf()
            if (s.linkPhone) linkPhone.open(true, s.linkPhone)
            // First setup's pages and two tour stops, as practice (nothing is saved).
            if (s.setup) Qt.callLater(() => setup.open(false, s.setup, s.stop || 0))
            if (s.backup) Qt.callLater(() => backupPanel.open("backup", false, true, s.backup))
            if (s.restore) Qt.callLater(() => backupPanel.open("restore", false, true, s.restore))
            if (s.lockCheck && s.lockFails !== undefined) Qt.callLater(() => lockCheck.sample("Back up", "A backup holds your whole account", s.lockFails, !!s.lockErase))
            else if (s.lockCheck) Qt.callLater(() => lockCheck.sample("Back up", "A backup holds your whole account"))
            if (s.lockSpare) Qt.callLater(() => lockCheck.sampleSpare())
            if (s.find) Qt.callLater(() => { const p = app.page(); if (p && p.openFind) p.openFind(s.find) })
            if (s.memberMenu) Qt.callLater(() => { const p = app.page(); if (p && p.memberMenu) p.memberMenu(p.members.find(m => m.key === "s")) })
            if (s.volume) Qt.callLater(() => ModActions.volumeMenu("s", "Sam", null, true))
            if (s.ctx) Qt.callLater(() => app.tourMenu(s.ctx))
            if (s.hold) confirm.open("Erase everything?", "Messages, photos, voice, contacts, keys and the lock are deleted for good, and Huginn closes.", "Hold to erase", null, true, true)
            if (s.uncovered) {
                confirm.open("Mira wants to send a file", "photo.jpg (2 MB). It'll be saved in Downloads.", "Accept", null, false, false, null, "localsend")
                confirm.cancelText = "Decline"
                app.guards = []   // its own half second long gone, as when it opened under the menu
                app.tourMenu("group")
                if (ctxMenu.visible && app.overlay() === ctxMenu) {
                    app.heldNow["lb"] = true   // LB held as the menu closes
                    app.lbHeld = true
                    app.lbUsed = false
                    ctxMenu.close()
                    if (app.overlay() !== confirm || !app.overlayGuarded()) console.warn("Tour check: a request uncovered by a menu has no half second")
                    app.press("a")
                    if (!confirm.visible) console.warn("Tour check: A answered a request just uncovered by a menu")
                    app.release("lb")
                    if (app.lbHeld) console.warn("Tour check: LB let go inside the half second stays held")
                }
            }
            if (s.promptDrop) Qt.callLater(() => {
                const p = app.page()
                if (!p || !p.mainCompose) return
                p.mainCompose.writeAbove()
                if (!prompt.visible) return
                p.mainCompose.dropAbove()
                if (prompt.visible) console.warn("Tour check: the prompt at the top stayed open when its chat changed")
            })
            if (s.guarded) {
                confirm.open("Mira wants to send a file", "photo.jpg (2 MB). It'll be saved in Downloads.", "Accept", null, false, false, null, "localsend")
                confirm.cancelText = "Decline"
                app.heldNow["a"] = true   // A held as it showed: its release is no answer either
                app.guardOverlay(confirm)
                app.guardOverlay(newsBanner)
                app.press("a")
                app.release("a")
            }
            if (s.holdEarly) { confirm.open("Block Mira?", "They aren't told.", "Block", null, true, true); confirm.startHold(); confirm.stopHold() }
            if (s.openProgram) {
                Ui.sure("Open this file?", "\"invoice.pdf.exe\" looks like a pdf document but is a program (.exe). Opening it runs it, and a program from someone else can take over this computer. Open it only if you were expecting it.",
                        "Open anyway", null, false, true)
                confirm.otherText = "Show in folder"
            }
            if (s.shared) {
                const tab = s.shared, menu = !!s.sharedMenu, next = !!s.sharedY
                Qt.callLater(() => {
                    const p = app.page()
                    if (!p || !p.more) return
                    p.more.openShared()
                    p.more.sharedTab = tab
                    if (next) p.more.handleButton("y")
                    if (menu) Qt.callLater(() => p.more.contextMenu())
                })
            }
            step++
        }
    }
}

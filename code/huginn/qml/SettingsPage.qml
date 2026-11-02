import QtQuick
import Huginn

// Settings: one flat list on the left (You, Notifications, Look, Calls, Privacy and lock, Devices,
// Controller, Backup, About, and Erase last in red). A choice opens its rows on the right, related
// ones together in framed sections. Changes save as you go. About starts with the raven: ten presses
// (a click or A) turn Labs mode on or off, which adds the Labs group (update channel, bug reports, App storage,
// Performance check).
Rectangle {
    id: page
    // Seasonal: a frosted pane, so what falls shows while Look's settings change.
    color: Theme.sheet
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property string section: "you"    // the open page: one of items' ids
    property int pick: 0              // the focused item on the left
    property int row: 0
    property bool inSection: false
    // Another page opens one by name ({ section: "privacy" }).
    function setArgs(a) {
        if (a.section === undefined) return
        const named = { "lock": "privacy", "keep": "privacy", "nearby": "privacy", "picture": "you", "access": "look", "panels": "look", "linked": "devices" }
        const id = named[a.section] || a.section
        const i = items.findIndex(m => m.id === id)
        if (i < 0) return
        section = id; row = a.row || 0; inSection = true; pick = i
    }
    // The desktop's list column edge, so Main.qml can put the resize handle on it.
    readonly property int listEdge: middle.visible ? middle.width : 0

    readonly property var items: [
        { id: "you", title: "You", icon: "person" },
        { id: "notify", title: "Notifications", icon: "speaker" },
        { id: "look", title: "Look", icon: "image" },
        { id: "calls", title: "Calls", icon: "call" },
        { id: "privacy", title: "Privacy and lock", icon: "lock" },
        { id: "devices", title: "Devices", icon: "phone" },
        { id: "controller", title: "Controller", icon: "keyboard" },
        { id: "backup", title: "Backup", icon: "shield" },
        { id: "about", title: "About", icon: "info" },
        { id: "erase", title: "Erase", icon: "close", danger: true }
    ]
    readonly property var current: items.find(m => m.id === section) || items[0]
    readonly property var prompts: inSection ? [{ key: "a", label: "Change" }, { key: "b", label: "Back" }]
                                             : [{ key: "a", label: "Open" }, { key: "b", label: "Back" }]
    readonly property string status: ""
    // Look › Amount: how many fall in this window now (the Amount picked, or the window's own).
    // The Seasonal leaves or snow are held still only because of the battery (Battery saver, or idle on battery).
    readonly property bool fallBatteryHeld: Theme.seasonal && Settings.seasonAnimation === "full" && !Settings.reduceMotion
                                            && !Perf.animationsOff && (System.batterySaving || System.onBattery)
    readonly property int fallShown: Theme.fallCount(Ui.main ? Ui.main.width : 1600, Ui.main ? Ui.main.height : 900)
    // Look › Amount and Size: where the slider sits (0 to 100) and what a position sets.
    // Gradient: 0 (flat) to 200%, the slider's 0 to 100.
    // Calls › Input sensitivity: -60 to -20 dBFS on the slider's 0 to 100.
    function fallPos(key) { return key === "gateThreshold" ? (Rooms.gateThreshold + 60) * 2.5 : key === "seasonGradient" ? Settings.seasonGradient / 2 : key === "fallCount" ? Theme.fallCountPos(fallShown) : Theme.fallSizePos(Settings.fallSize) }
    function setFall(key, v) {
        if (key === "gateThreshold") { Rooms.gateThreshold = Math.round(v / 2.5) - 60; return }
        if (key === "seasonGradient") Settings.seasonGradient = Math.round(v) * 2
        else if (key === "fallCount") Settings.fallCount = Theme.fallCountAt(v)
        else Settings.fallSize = Theme.fallSizeAt(v)
    }
    // One notch left or right: on to the next number the slider gives (near 1 several positions give
    // the same one). A puts it back: Amount and Size go back to their defaults (121, 57%), Gradient to 80%.
    function stepFall(key, dir) {
        // A on Input sensitivity never turns Automatic off: from Manual it goes back to Automatic, else it does nothing.
        if (key === "gateThreshold") { if (dir !== 0) Rooms.gateThreshold = Rooms.gateThreshold + dir; else if (Rooms.gateMode === "manual") Rooms.gateMode = "auto"; return }
        if (key === "seasonGradient") { Settings.seasonGradient = dir === 0 ? 80 : Math.max(0, Math.min(200, Settings.seasonGradient + dir * 10)); return }
        if (dir === 0) {
            if (key === "fallCount") Settings.fallCount = 0
            else Settings.fallSize = 57
            return
        }
        const now = key === "fallCount" ? fallShown : Settings.fallSize
        let v = fallPos(key)
        for (let i = 0; i < 100; ++i) {
            v = Math.max(0, Math.min(100, v + dir))
            const got = key === "fallCount" ? Theme.fallCountAt(v) : Theme.fallSizeAt(v)
            if (got !== now || v <= 0 || v >= 100) break
        }
        setFall(key, v)
    }
    // Color vision: only the ones that look different (Theme.distinctSwatches).
    readonly property var swatches: Theme.distinctSwatches(["#C2A25E", "#6E9CC8", "#6FAE8C", "#CF6F54", "#A58BC6", "#B07A5A"])

    // Each row: { kind: "segmented"|"toggle"|"accent"|"swatches"|"grounds"|"button"|"size"|"slider"|"info", group, title, note, ... }.
    // A segmented row's value is Settings[key] (or Chat's), unless the row gives its own value.
    // Rows with the same group sit in one framed section; notes are a few words at most.
    readonly property var rows: {
        switch (section) {
        case "you": return [
            { kind: "button", key: "chatName", title: "Name", note: Ui.myName, label: "Change" },
            { kind: "button", key: "picture", title: "Picture", label: Profile.hasMyPicture ? "Change" : "Choose" },
            { kind: "swatches", key: "myColour", title: "Avatar color" },
            { kind: "button", key: "myCode", title: "My code", label: "Show" }
        ]
        case "notify": return [
            // What a notification shows: who it's from and a short preview, who it's from, or only "New message".
            { kind: "segmented", key: "previewMode", chat: true, title: "Show in notifications",
              options: [{ value: "2", label: "Name and message" }, { value: "1", label: "Name only" }, { value: "0", label: "Nothing" }] },
            { kind: "toggle", key: "reduceNotifications", chat: true, title: "Reduce notifications", note: "One per chat until you open it" },
            { kind: "toggle", key: "messageSound", title: "Message sound" },
            // The same as Do not disturb on your status card.
            { kind: "toggle", key: "dnd", own: true, on: Ui.myStatus === "dnd", title: "Do not disturb", note: "No sounds or notifications" },
            { group: "Quiet hours", kind: "toggle", key: "quietHours", title: "Quiet hours",
              note: Settings.quietHours ? page.hourName(Settings.quietFrom) + " to " + page.hourName(Settings.quietTo) + ", no sounds" : "No sounds overnight" },
            { group: "Quiet hours", kind: "button", key: "quietFrom", hide: !Settings.quietHours, title: "From: " + page.hourName(Settings.quietFrom), label: "Change" },
            { group: "Quiet hours", kind: "button", key: "quietTo", hide: !Settings.quietHours, title: "To: " + page.hourName(Settings.quietTo), label: "Change" }
        ].filter(r => !r.hide)
        case "look": return [
            { group: "Theme", kind: "segmented", key: "theme", title: "Theme", value: Theme.choice,
              options: [{ value: "dark", label: "Dark" }, { value: "black", label: "True black" }, { value: "light", label: "Light" }] },
            // Seasonal, over any theme: the season, its background (on Dark), what falls and how much.
            { group: "Theme", kind: "segmented", key: "season", title: "Seasonal", note: "Over any theme",
              value: Theme.seasonPick, options: Theme.seasonChoices },
            { group: "Theme", kind: "grounds", key: "seasonGround", hide: !Theme.tinted, title: "Background" },
            { group: "Theme", kind: "slider", key: "seasonGradient", hide: !Theme.seasonal || Theme.flatGround,
              title: "Gradient: " + (Settings.seasonGradient === 0 ? "Flat" : Settings.seasonGradient + "%"), low: "Flat", high: "Strong" },
            { group: "Theme", kind: "segmented", key: "seasonAnimation", hide: !Theme.seasonal, title: "Animation",
              note: Settings.seasonAnimation === "off" ? "" : Settings.reduceMotion ? "Held still by Less motion" : Perf.animationsOff ? "Held still on this computer"
                    : page.fallBatteryHeld ? "" : Settings.seasonAnimation === "battery" ? "Moves on battery too, at a slower pace" : "Less motion and Battery saver hold it still",
              options: [{ value: "off", label: "Off" }, { value: "full", label: "On" }, { value: "battery", label: "On, even on battery" }] },
            // Held still on battery: why, in one line, and Move anyway switches Animation to On, even on battery.
            { group: "Theme", kind: "button", key: "fallAnyway", hide: !page.fallBatteryHeld,
              title: System.batterySaving ? "Held still by Battery saver" : "On battery, held still after 30 s without input", label: "Move anyway" },
            { group: "Theme", kind: "slider", key: "fallCount", hide: !Theme.seasonal || Settings.seasonAnimation === "off",
              title: "Amount: " + page.fallShown + (Theme.snow ? (page.fallShown === 1 ? " flake" : " flakes") : (page.fallShown === 1 ? " leaf" : " leaves")),
              low: "1", high: "400" },
            { group: "Theme", kind: "slider", key: "fallSize", hide: !Theme.seasonal || Settings.seasonAnimation === "off",
              title: "Size: " + Settings.fallSize + "%", low: "Tiny", high: "Huge" },
            { group: "Theme", kind: "accent", key: "accent", title: "Accent color" },
            { group: "Theme", kind: "segmented", key: "density", title: "Messages",
              options: [{ value: "compact", label: "Compact" }, { value: "cozy", label: "Default" }, { value: "spacious", label: "Spacious" }] },
            { group: "Size", kind: "size", key: "uiSize", title: "Size: " + Settings.uiSize + "%",
              note: System.screenScale > 1.001 ? "Already made bigger for this small screen. Ctrl+= and Ctrl+- too" : "Ctrl+= and Ctrl+- too" },
            { group: "Size", kind: "segmented", key: "textSize", title: "Text size",
              options: [{ value: "100", label: "Normal" }, { value: "115", label: "Large" }, { value: "130", label: "Larger" }, { value: "150", label: "Largest" }] },
            { group: "Accessibility", kind: "toggle", key: "highContrast", title: "High contrast" },
            // Color vision: every color that means something, remapped for it; the preview shows them.
            { group: "Accessibility", kind: "segmented", key: "colorVision", title: "Color vision", options: Theme.visionChoices },
            { group: "Accessibility", kind: "preview", key: "visionPreview", title: "Preview", hide: !Theme.cb },
            { group: "Accessibility", kind: "toggle", key: "reduceMotion", title: "Less motion" },
            { group: "Window", kind: "toggle", key: "fullScreen", title: "Open full screen", note: "F11 too", hide: System.windowed || System.gameMode || System.handheld },
            // The window's X: Leave Huginn? (with Don't ask again), or what was picked there.
            { group: "Window", kind: "segmented", key: "closeAction", title: "Close button", hide: System.gameMode,
              options: [{ value: "ask", label: "Ask" }, { value: "minimize", label: "Minimize" }, { value: "quit", label: "Close" }] },
            // Full screen, controller hints, the on-screen keyboard, bigger touch targets and lighter animation.
            { group: "Window", kind: "segmented", key: "handheldMode", title: "Handheld mode",
              note: "Detected: " + (System.handheldModel !== "" ? System.handheldModel : System.handheldDetected ? "a handheld" : "not a handheld"),
              options: [{ value: "auto", label: "Automatic" }, { value: "on", label: "On" }, { value: "off", label: "Off" }] },
            // No animations, fewer background checks and 30 fps timers. Automatic: on while on battery.
            { group: "Window", kind: "segmented", key: "batterySaver", title: "Battery saver",
              note: (System.onBattery ? "On battery" : "Plugged in") + (System.batterySaving ? ". Saving now" : ""),
              options: [{ value: "auto", label: "Automatic" }, { value: "on", label: "On" }, { value: "off", label: "Off" }] },
            { group: "Panels", kind: "toggle", key: "showList", title: "Show the chat list", note: "Ctrl+Shift+L" },
            { group: "Panels", kind: "toggle", key: "showMembers", title: "Show group members", note: "Ctrl+Shift+U" },
            { group: "Panels", kind: "button", key: "resetPanels", title: "Panel sizes", label: "Reset" }
        ].filter(r => !r.hide)
        case "calls": return [
            { kind: "toggle", key: "noiseRemoval", title: "Noise removal" },
            { kind: "toggle", key: "internetCalls", title: "Internet calls", note: "Call people anywhere" },
            // Voice rooms send while you talk (voice activity, the default), while a key is held, or all the time.
            // With push to talk on and no key or controller button yet, the mic uses voice activity (never dead), and says so.
            { group: "Talking", kind: "toggle", key: "pushToTalk", title: "Push to talk",
              note: Settings.pushToTalk && !TalkKey.hasKey ? "No talk key yet, so your mic uses voice activity until you pick one"
                    : Settings.pushToTalk && TalkKey.padAway ? "No controller connected, so your mic uses voice activity until one is" : GlobalKeys.talkNote },
            { group: "Talking", kind: "button", key: "talkKey", hide: !Settings.pushToTalk, title: TalkKey.key !== 0 ? "Talk key: " + TalkKey.keyName : "Talk key: none yet",
              note: TalkKey.capturing ? page.pickNote
                    : !TalkKey.hasKey ? "Pick the key you'll hold to talk, or a controller button. A key that doesn't type, like Insert, Pause or Scroll Lock, talks while you type too"
                    : TalkKey.key === 0 ? "A keyboard key too, if you like. One that doesn't type, like Insert, Pause or Scroll Lock, talks while you type too"
                    : TalkKey.keyTypes ? "In a text box it types. Keys that don't type, like Insert, Pause or Scroll Lock, always talk"
                    : "Talks even while you type", label: TalkKey.capturing ? "Cancel" : TalkKey.hasKey ? "Change" : "Pick a key" },
            // A keyboard key can go too: the controller button alone, or voice activity with push to talk still on.
            { group: "Talking", kind: "button", key: "clearTalkKey", hide: !Settings.pushToTalk || TalkKey.key === 0 || TalkKey.capturing,
              title: "No keyboard key", note: TalkKey.padButton !== "" ? "Only the controller button talks" : "Voice activity until you pick one", label: "Remove key" },
            { group: "Talking", kind: "button", key: "talkPad", hide: !Settings.pushToTalk || TalkKey.padButton === "",
              title: "Controller talk button: " + Gamepad.glyph(TalkKey.padButton), note: page.padNote(TalkKey.padButton), label: "Remove" },
            { group: "Talking", kind: "segmented", key: "gateMode", rooms: true, value: Rooms.gateMode, hide: Rooms.pushToTalk,
              title: "Voice activity", note: "Voice rooms only. Sends only while you talk, so game sound stays out",
              options: [{ value: "auto", label: "Automatic" }, { value: "manual", label: "Manual" }, { value: "off", label: "Always on" }] },
            // The bar under the slider is your microphone's level: lit while it goes out.
            { group: "Talking", kind: "slider", key: "gateThreshold", hide: Rooms.pushToTalk || Rooms.gateMode === "off",
              title: "Input sensitivity", note: Rooms.gateMode === "auto" ? "Set for you. Move it to set it yourself"
                                                : Ui.main && Ui.main.padInUse ? "A: back to Automatic" : "",
              low: "Picks up more", high: "Picks up less" },
            { group: "Talking", kind: "button", key: "micTest", hide: Rooms.pushToTalk || Rooms.gateMode === "off" || (Rooms.micLive && !Rooms.micTest), title: "Test your mic",
              note: Rooms.micTest ? "Talk: the bar lights while you'd be heard" : "Shows your level on the bar", label: Rooms.micTest ? "Stop" : "Test" },
            { group: "Talking", kind: "info", title: "Mute and deafen keys", note: GlobalKeys.keysNote },
            // Windows: the two combinations can be moved off keys a game or another app uses (still RegisterHotKey only).
            { group: "Talking", kind: "button", key: "muteKey", hide: !GlobalKeys.changeable, title: "Mute key: " + GlobalKeys.muteName,
              note: GlobalKeys.capturing === 1 ? GlobalKeys.captureNote : "", label: GlobalKeys.capturing === 1 ? "Press keys" : "Change" },
            { group: "Talking", kind: "button", key: "deafenKey", hide: !GlobalKeys.changeable, title: "Deafen key: " + GlobalKeys.deafenName,
              note: GlobalKeys.capturing === 2 ? GlobalKeys.captureNote : "", label: GlobalKeys.capturing === 2 ? "Press keys" : "Change" },
            { group: "Talking", kind: "button", key: "resetKeys", hide: !GlobalKeys.changeable || (GlobalKeys.muteSequence === "Ctrl+Shift+M" && GlobalKeys.deafenSequence === "Ctrl+Shift+D"),
              title: "Mute and deafen keys", note: "Back to Ctrl+Shift+M and Ctrl+Shift+D", label: "Reset" },
            { group: "Talking", kind: "toggle", key: "muteSounds", title: "Mute and deafen sounds",
              note: GlobalKeys.everywhere ? "A short sound when the keys turn them on or off, heard over a game"
                  : "A short sound when the keys (or LB with Up or Down) turn them on or off" },
            { group: "Devices", kind: "button", key: "micDevice", title: "Microphone", note: Settings.audioDeviceName(true, Settings.micDevice), label: "Change" },
            { group: "Devices", kind: "button", key: "speakerDevice", title: "Speaker", note: Settings.audioDeviceName(false, Settings.speakerDevice), label: "Change" }
        ].filter(r => !r.hide)
        case "privacy": return [
            { group: "Lock", kind: "button", key: "changeLock", title: Vault.exists && !Vault.hasLock ? "Lock: off" : "Lock",
              note: "Locks your chats. Can't be recovered.", label: Vault.exists && !Vault.hasLock ? "Set a lock" : "Change", enabled: Vault.exists },
            { group: "Lock", kind: "segmented", key: "lockPosition", title: "Pattern position",
              options: [{ value: "top", label: "Top" }, { value: "middle", label: "Middle" }, { value: "lower", label: "Lower" }] },
            { group: "Lock", kind: "toggle", key: "patternTrail", invert: true, title: "Hide the pattern as I draw" },
            { group: "Lock", kind: "info", hide: Vault.exists && !Vault.hasLock, title: "When it locks", note: "Only when you choose: Lock, or Lock and close" },
            { group: "Lock", kind: "toggle", key: "panicLock", vault: true, hide: Vault.exists && !Vault.hasLock, title: "Panic lock", note: "Ctrl+Shift+Q, or hold L3+R3" },
            { group: "Lock", kind: "button", key: "lockOff", hide: !Vault.hasLock, title: "Turn off the lock", label: "Turn off" },
            // One switch for everything nearby: off, nothing is announced or scanned for and nobody nearby is listed.
            { group: "Privacy", kind: "toggle", key: "visibleNearby", chat: true, title: "Nearby", note: "Off: contacts only, nothing announced" },
            { group: "Privacy", kind: "toggle", key: "readReceipts", chat: true, title: "Send read receipts" },
            { group: "Privacy", kind: "toggle", key: "activeStatus", chat: true, title: "Active status", note: "Shows when Huginn is open" },
            { group: "Privacy", kind: "toggle", key: "linkPreviews", chat: true, title: "Link previews", note: Settings.dataSaver ? "Off while Data saver is on" : "" },
            { group: "Privacy", kind: "toggle", key: "dataSaver", title: "Data saver", note: "Files ask first. No link previews" },
            // Windows only: screenshots and screen recordings leave Huginn's window out.
            { group: "Privacy", kind: "toggle", key: "blockScreenshots", hide: Qt.platform.os !== "windows", title: "Block screenshots", note: "Hides Huginn from captures" },
            { group: "Privacy", kind: "segmented", key: "deleteAfter", chat: true, title: "Keep messages",
              options: [{ value: "0", label: "Each chat's limit" }, { value: "86400000", label: "One day" }] },
            { group: "Nearby", kind: "toggle", key: "nearbyOn", chat: true, hide: !Chat.visibleNearby, title: "Nearby room" },
            { group: "Nearby", kind: "toggle", key: "keepNearby", chat: true, hide: !Chat.visibleNearby, title: "Keep Nearby messages" }
        ].filter(r => !r.hide)
        case "devices": return [
            { kind: "button", key: "linked", title: "Linked devices", note: !Linked.phone.key ? "" : (Linked.phone.gone ? "Copy kept from " + Linked.phone.name : "Linked to " + Linked.phone.name), label: "Open" },
            { kind: "toggle", key: "nearbySharing", title: "Share files nearby", note: "Works with LocalSend" },
            { kind: "button", key: "saveFolder", title: "Save files to", note: Transfer.saveName + "/Huginn", label: "Change" },
            { kind: "button", key: "saveFolderReset", hide: !Transfer.customSave, title: "Save in Downloads again", label: "Reset" },
            { kind: "button", key: "deviceName", title: "This computer's name", note: Settings.deviceName, label: "Change" }
        ].filter(r => !r.hide)
        case "controller": return [
            { kind: "info", title: "Show the buttons", note: "Press View (Select)" },
            { kind: "info", title: "Move and pick", note: "D-pad moves, A picks, B goes back" },
            { kind: "info", title: "Sections", note: "LB goes to the next one. Hold LB with Left or Right to step" },
            { kind: "info", title: "Options", note: "LT opens the options for what's picked" },
            { kind: "info", title: "Settings", note: "Menu (Start)" },
            { kind: "info", title: "Scroll", note: "Left stick" },
            { kind: "info", title: "Pointer", note: "Right stick moves, RB or R3 clicks, RT right-clicks" },
            { kind: "info", title: "Search and Transfers", note: "Up from the top of a list or the rail" },
            { kind: "info", title: "In voice", note: "While Huginn is in front: hold LB, then Up mutes and Down deafens" },
            { kind: "info", title: "Panic lock", note: Vault.panicLock ? "Hold L3 and R3" : "Off" },
            { kind: "button", key: "shortcuts", title: "Keyboard shortcuts", note: "Ctrl+/", label: "Open" }
        ]
        case "backup": return [
            { kind: "button", key: "backup", title: "Back up", note: "One encrypted file, saved where you choose", label: Vault.busy ? "Working" : "Back up", enabled: Vault.exists && !Vault.busy },
            { kind: "button", key: "restore", title: "Restore from a backup", note: "Any backup file from Huginn or Muninn", label: "Restore", enabled: Vault.exists && !Vault.busy }
        ]
        case "about": return [
            { kind: "raven", key: "raven", title: "Huginn " + System.version, note: Settings.labs ? "Labs mode on" : "" },
            // What draws the window. Drawn by the processor (no working graphics driver), everything is slow.
            { kind: "info", hide: System.graphics === "", title: System.softwareGraphics ? "Graphics: slow" : "Graphics",
              note: System.softwareGraphics ? "No graphics card in use, so Huginn is slow. " + System.graphics : System.graphics },
            { kind: "button", key: "check", title: Updater.status,
              label: Updater.ready ? "Restart now" : Updater.available ? "Install " + Updater.latest : "Check now", enabled: !Updater.busy },
            { kind: "toggle", key: "autoUpdate", title: "Update automatically" },
            // Where everything Huginn keeps is (the portable build's data folder beside Huginn.exe).
            { kind: "button", key: "dataFolder", title: "Your data: " + System.dataFolder,
              note: System.portable ? "Keep the data folder when you update by hand" : "", label: "Open folder" },
            { kind: "info", title: "Made by Ravenor907", note: "GPL-3.0. Fonts: SIL Open Font License" },
            { group: "Labs", kind: "segmented", key: "updateChannel", hide: !Settings.labs, title: "Update channel",
              options: [{ value: "stable", label: "Stable" }, { value: "beta", label: "Pre-release" }] },
            { group: "Labs", kind: "button", key: "goStable", hide: !Settings.labs, title: "Go back to stable", note: "Installs the latest stable",
              label: "Go back", enabled: !Updater.busy },
            { group: "Labs", kind: "button", key: "report", hide: !Settings.labs, title: "Report a bug", note: "Screenshot optional", label: "Report" },
            { group: "Labs", kind: "button", key: "storage", hide: !Settings.labs, title: "App storage", note: "Files Huginn keeps", label: "Open" },
            { group: "Labs", kind: "button", key: "perf", hide: !Settings.labs, title: "Performance check", note: "Lag and size", label: "Open" },
            { group: "Labs", kind: "button", key: "practice", hide: !Settings.labs, title: "Replay first setup (practice)", note: "Nothing is saved", label: "Start" }
        ].filter(r => !r.hide)
        case "erase": return [
            { kind: "button", key: "deleteMessages", title: "Delete all messages", note: "Contacts stay", label: "Delete" },
            { kind: "button", key: "eraseChat", title: "Erase everything", note: "Deletes everything on this device.", label: "Erase" }
        ]
        }
        return []
    }
    // The rows in their framed sections: [{ title, start (the first row's index), rows }].
    readonly property var groups: {
        const out = []
        rows.forEach((r, i) => {
            const g = r.group || ""
            if (!out.length || out[out.length - 1].title !== g) out.push({ title: g, start: i, rows: [] })
            out[out.length - 1].rows.push(r)
        })
        return out
    }
    readonly property var dangerKeys: ["lockOff", "deleteMessages", "eraseChat", "restore"]
    // Labs turned off under the focus: it stays on a row that's still there.
    onRowsChanged: if (row >= rows.length) row = Math.max(0, rows.length - 1)

    // The raven in About: ten presses in a row (a few seconds apart at most) turn Labs mode on or off.
    property int ravenTaps: 0
    Timer { id: ravenReset; interval: 3000; onTriggered: page.ravenTaps = 0 }
    function tapRaven() {
        ravenTaps++
        ravenReset.restart()
        if (ravenTaps < 10) return
        ravenTaps = 0
        Settings.labs = !Settings.labs
        Ui.say(Settings.labs ? "Labs mode on" : "Labs mode off")
    }

    // Scrolls the left column so the row the controller is on is in view.
    function revealNav(item) {
        const y = item.mapToItem(nav, 0, 0).y
        if (y < navView.contentY) navView.contentY = y
        else if (y + item.height > navView.contentY + navView.height) navView.contentY = Math.min(navView.contentHeight - navView.height, y + item.height - navView.height)
    }

    // ------------------------------------------------------------- left: the flat list
    Rectangle {
        id: middle
        visible: !Theme.portrait || !page.inSection
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Ui.dock
        width: Theme.portrait ? page.width : Math.min(Math.max(Theme.listWidth, Theme.px(260)), Math.round(page.width * 0.4))
        color: Theme.column
        PageHeader { id: header; width: parent.width; title: "Settings" }
        // Scrolls when the window is short or the Size is large.
        Flickable {
            id: navView
            anchors.top: header.bottom
            anchors.topMargin: Theme.px(12)
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(12)
            x: Theme.px(12)
            width: parent.width - Theme.px(24)
            contentWidth: width
            contentHeight: nav.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Rectangle {
                id: nav
                width: navView.width
                implicitHeight: navCol.implicitHeight + 2
                height: implicitHeight
                radius: Theme.radius
                color: Theme.panel
                border.color: Theme.line
                Column {
                    id: navCol
                    x: 1; y: 1
                    width: parent.width - 2
                    Repeater {
                        model: page.items
                        Rectangle {
                            id: navRow
                            readonly property bool here: page.section === modelData.id && !Theme.portrait
                            readonly property bool on: page.active && !page.inSection && page.pick === index && !!Ui.main && Ui.main.padInUse
                            width: navCol.width
                            onOnChanged: if (on) page.revealNav(navRow)
                            height: Math.max(Theme.px(44), navText.implicitHeight + Theme.px(16)) + (modelData.danger ? Theme.px(6) : 0)
                            color: here || on ? Theme.tile : itemHover.hovered ? Theme.raised : "transparent"
                            border.width: on ? Theme.focusWidth : 0
                            border.color: Theme.accent
                            Accessible.role: Accessible.PageTab
                            Accessible.name: modelData.title
                            // Erase sits apart, under a stronger line.
                            Rectangle { visible: index > 0; width: parent.width; height: modelData.danger ? 2 : 1; color: modelData.danger ? Theme.dangerLine : Theme.seam }
                            Icon {
                                id: navIcon
                                anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                                anchors.verticalCenter: parent.verticalCenter
                                name: modelData.icon; size: Theme.px(18)
                                color: modelData.danger ? Theme.danger : navRow.here ? Theme.accent : Theme.textMuted
                            }
                            Text {
                                textFormat: Text.PlainText
                                id: navText
                                anchors.left: navIcon.right; anchors.leftMargin: Theme.px(12)
                                anchors.right: chev.left; anchors.rightMargin: Theme.px(8)
                                anchors.verticalCenter: parent.verticalCenter
                                elide: Text.ElideRight
                                text: modelData.title
                                color: modelData.danger ? Theme.danger : Theme.text
                                font.family: Theme.body; font.pixelSize: Theme.fs(15)
                            }
                            Icon { id: chev; anchors.right: parent.right; anchors.rightMargin: Theme.px(10); anchors.verticalCenter: parent.verticalCenter; name: "forward"; size: Theme.px(14); color: Theme.textMuted }
                            HoverHandler { id: itemHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: { page.pick = index; page.openMain() } }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- right: the open page
    PageHeader {
        id: rowsHead
        visible: !Theme.portrait || page.inSection
        anchors.left: middle.visible ? middle.right : parent.left
        anchors.right: parent.right
        title: page.current.title
        backShown: Theme.portrait
        onBack: page.back()
    }
    // The rows of the open page, in framed sections. They scroll so the focused one is always on screen.
    Flickable {
        id: rowsView
        visible: rowsHead.visible
        anchors.top: rowsHead.bottom
        anchors.topMargin: Theme.px(16)
        anchors.left: rowsHead.left
        anchors.leftMargin: Theme.px(24)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(24)
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.px(10)
        contentWidth: width
        contentHeight: rowsCol.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: rowsCol
            width: Math.min(rowsView.width, Theme.px(760))
            spacing: Theme.px(16)
            Repeater {
                id: groupRepeater
                model: page.groups
                Column {
                    id: groupCol
                    readonly property var groupData: modelData
                    width: rowsCol.width
                    spacing: Theme.px(6)
                    Text {
                        textFormat: Text.PlainText
                        visible: groupCol.groupData.title.trim() !== ""
                        leftPadding: Theme.px(4)
                        text: groupCol.groupData.title.toUpperCase()
                        color: Theme.section
                        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
                    }
                    // The frame around the section.
                    Rectangle {
                        width: parent.width
                        height: frameCol.implicitHeight + 2
                        radius: Theme.radius
                        color: Theme.panel
                        border.color: groupCol.groupData.rows.some(r => page.dangerKeys.indexOf(r.key) >= 0) ? Theme.dangerLine : Theme.line
                        Column {
                            id: frameCol
                            x: 1; y: 1
                            width: parent.width - 2
                            Repeater {
                                model: groupCol.groupData.rows
                                SettingRow {
                                    width: frameCol.width
                                    at: groupCol.groupData.start + index
                                    first: index === 0
                                    r: modelData
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // One row: its words on the left, its control on the right (or under them when there isn't room).
    component SettingRow: Rectangle {
        id: rowItem
        property int at: 0
        property bool first: false
        property var r: ({})
        readonly property bool focused: page.active && page.inSection && page.row === at && !!Ui.main && Ui.main.padInUse
        readonly property bool danger: page.dangerKeys.indexOf(r.key) >= 0
        // Wide choices go under the words when there isn't room beside them; a picker then takes the full width
        // (and becomes a dropdown only if even that is too narrow, see Segmented).
        readonly property bool stacked: r.kind === "segmented" ? segCtl.naturalWidth > width * 0.5 : control.width > width * 0.5
        onFocusedChanged: if (focused) Qt.callLater(page.showRow, rowItem)
        height: stacked ? texts.implicitHeight + control.height + Theme.px(30)
                        : Math.max(Theme.px(52), texts.implicitHeight + Theme.px(20), rowItem.r.kind === "raven" ? control.height + Theme.px(16) : 0)
        color: focused ? Theme.focusFill : "transparent"
        border.width: focused ? Theme.focusWidth : 0
        border.color: Theme.accent
        Rectangle { visible: !rowItem.first; width: parent.width; height: 1; color: Theme.seam }
        Column {
            id: texts
            anchors.left: parent.left
            anchors.leftMargin: Theme.px(16)
            anchors.right: rowItem.stacked ? parent.right : control.left
            anchors.rightMargin: Theme.px(16)
            y: rowItem.stacked ? Theme.px(10) : (parent.height - implicitHeight) / 2
            spacing: Theme.px(2)
            Text { textFormat: Text.PlainText; width: parent.width; wrapMode: Text.Wrap; text: rowItem.r.title || ""; color: rowItem.danger ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
            Text { textFormat: Text.PlainText; visible: !!rowItem.r.note; width: parent.width; wrapMode: Text.WordWrap; text: rowItem.r.note || ""; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
        }
        Item {
            id: control
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(16)
            y: rowItem.stacked ? texts.y + texts.implicitHeight + Theme.px(10) : (parent.height - height) / 2
            // Never wider than the row: on a narrow window the choices squeeze to fit.
            width: Math.min(rowItem.width - Theme.px(32),
                   rowItem.r.kind === "segmented" ? (rowItem.stacked ? rowItem.width : segCtl.implicitWidth)
                 : rowItem.r.kind === "toggle" ? toggleCtl.implicitWidth
                 : rowItem.r.kind === "accent" ? accentCtl.naturalWidth
                 : rowItem.r.kind === "preview" ? rowItem.width
                 : rowItem.r.kind === "swatches" ? swatchCtl.implicitWidth
                 : rowItem.r.kind === "grounds" ? groundCtl.naturalWidth
                 : rowItem.r.kind === "size" ? sizeCtl.naturalWidth
                 : rowItem.r.kind === "slider" ? Theme.px(220)
                 : rowItem.r.kind === "raven" ? ravenCtl.width
                 : rowItem.r.kind === "button" ? buttonCtl.implicitWidth : 0)
            height: Math.max(Theme.px(36), segCtl.visible ? segCtl.implicitHeight : 0, buttonCtl.visible ? buttonCtl.implicitHeight : 0,
                             ravenCtl.visible ? ravenCtl.height : 0, sliderCtl.visible ? sliderCtl.implicitHeight : 0,
                             previewCtl.visible ? previewCtl.implicitHeight : 0)
            // About: the raven. Ten presses turn Labs mode on or off.
            Image {
                id: ravenCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "raven"
                source: rowItem.r.kind === "raven" ? "assets/raven-gold.png" : ""
                width: Theme.px(56); height: width
                sourceSize.width: 256; sourceSize.height: 256
                fillMode: Image.PreserveAspectFit
                Accessible.role: Accessible.Button
                Accessible.name: "Raven"
                Accessible.onPressAction: page.tapRaven()
                TapHandler { onTapped: page.tapRaven() }
            }
            // Look › Seasonal: how many fall, and their size.
            LookSlider {
                id: sliderCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "slider"
                width: parent.width
                value: rowItem.r.kind === "slider" ? page.fallPos(rowItem.r.key) : 0
                label: rowItem.r.title || ""
                low: rowItem.r.low || ""
                high: rowItem.r.high || ""
                focused: rowItem.focused
                // Calls › Input sensitivity: the microphone's level behind the track, in a room or while testing.
                meter: rowItem.r.key === "gateThreshold" && Rooms.micLive ? Math.max(0, Math.min(1, (Rooms.micLevel + 60) / 40)) : -1
                meterLit: Rooms.gateOpen
                onMoved: v => page.setFall(rowItem.r.key, v)
                onNudged: dir => page.stepFall(rowItem.r.key, dir)
            }
            Segmented {
                id: segCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "segmented"
                width: parent.width
                options: rowItem.r.options || []
                value: rowItem.r.kind !== "segmented" ? "" : rowItem.r.value !== undefined ? rowItem.r.value
                     : rowItem.r.chat ? String(Chat[rowItem.r.key]) : Settings[rowItem.r.key]
                onPicked: v => page.pickSegment(rowItem.r, v)
            }
            ToggleSwitch {
                id: toggleCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "toggle"
                // invert: the switch reads the other way round from the setting (Hide the pattern = trail off).
                checked: rowItem.r.kind !== "toggle" ? false : rowItem.r.own ? !!rowItem.r.on : (page.source(rowItem.r)[rowItem.r.key] !== !!rowItem.r.invert)
                onToggled: page.flip(rowItem.r)
            }
            Row {
                id: sizeCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "size"
                spacing: Theme.px(6)
                readonly property real naturalWidth: smallerBtn.implicitWidth + resetBtn.implicitWidth + biggerBtn.implicitWidth + spacing * 2
                readonly property bool squeezed: control.width < naturalWidth - 0.5
                readonly property real share: (control.width - spacing * 2) / 3
                HButton { id: smallerBtn; width: sizeCtl.squeezed ? sizeCtl.share : implicitWidth; text: "Smaller"; onClicked: page.press("sizeDown") }
                HButton { id: resetBtn; width: sizeCtl.squeezed ? sizeCtl.share : implicitWidth; text: "100%"; kind: "plain"; onClicked: page.press("sizeReset") }
                HButton { id: biggerBtn; width: sizeCtl.squeezed ? sizeCtl.share : implicitWidth; text: "Bigger"; onClicked: page.press("sizeUp") }
            }
            Row {
                id: accentCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "accent"
                spacing: Theme.px(8)
                // The named colors (only those that suit the Color vision picked) and Any color; smaller swatches on
                // a narrow window.
                readonly property int slots: Theme.accentNames.length + (Theme.mono ? 0 : 1)
                readonly property real naturalWidth: Theme.px(28) * slots + spacing * (slots - 1)
                readonly property int swatch: Math.max(Theme.px(20), Math.min(Theme.px(28), Math.floor((control.width - spacing * (slots - 1)) / slots)))
                Repeater {
                    model: rowItem.r.kind === "accent" ? Theme.accentNames : []
                    Rectangle {
                        width: accentCtl.swatch; height: width; radius: Theme.radius
                        color: Theme.mono ? Theme.accent : Theme.light ? Qt.darker(Theme.accents[modelData], 1.7) : Theme.accents[modelData]
                        border.width: Theme.accentKey === modelData ? Theme.px(2) : 1
                        border.color: Theme.accentKey === modelData ? Theme.text : Theme.lineMuted
                        Accessible.role: Accessible.RadioButton
                        Accessible.name: modelData.charAt(0).toUpperCase() + modelData.slice(1)
                        Accessible.checked: Theme.accentKey === modelData
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: Settings.accent = modelData }
                    }
                }
                // Any color: a code like #6E9CC8.
                Rectangle {
                    readonly property bool custom: Theme.accentNames.indexOf(Theme.accentKey) < 0
                    visible: !Theme.mono
                    width: accentCtl.swatch; height: width; radius: Theme.radius
                    color: custom ? Theme.accent : Theme.raised
                    border.width: custom ? Theme.px(2) : 1
                    border.color: custom ? Theme.text : Theme.lineMuted
                    Accessible.role: Accessible.Button
                    Accessible.name: "Any color"
                    Icon { visible: !parent.custom; anchors.centerIn: parent; name: "plus"; size: Theme.px(16); color: Theme.textMuted }
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: page.pickColor() }
                }
            }
            // Your color: the disc behind your initial, and your card's banner.
            Row {
                id: swatchCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "swatches"
                spacing: Theme.px(8)
                Repeater {
                    model: rowItem.r.kind === "swatches" ? page.swatches : []
                    Rectangle {
                        readonly property bool mine: Theme.sameSwatch(Profile.myColour, modelData)
                        width: Theme.px(28); height: width; radius: width / 2
                        color: modelData
                        border.width: mine ? Theme.px(2) : 1
                        border.color: mine ? Theme.text : Theme.lineMuted
                        Accessible.role: Accessible.RadioButton
                        Accessible.name: Theme.swatchName(modelData)
                        Accessible.checked: mine
                        Icon { visible: parent.mine; anchors.centerIn: parent; name: "check"; size: Theme.px(14); color: Theme.ground }
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: Profile.setMyColour(modelData) }
                    }
                }
            }
            // Look › Background: the season's own ground (Default) or one of the dark grounds.
            Row {
                id: groundCtl
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "grounds"
                spacing: Theme.px(6)
                readonly property real naturalWidth: defaultGround.naturalWidth + Theme.px(26) * Theme.groundSwatches.length + spacing * Theme.groundSwatches.length
                // Smaller swatches on a narrow window.
                readonly property int swatch: Math.max(Theme.px(16), Math.min(Theme.px(26),
                    Math.floor((control.width - defaultGround.width - spacing * Theme.groundSwatches.length) / Math.max(1, Theme.groundSwatches.length))))
                Rectangle {
                    id: defaultGround
                    readonly property bool on: Theme.groundSaved === ""
                    readonly property real naturalWidth: defaultText.implicitWidth + Theme.px(16)
                    width: naturalWidth; height: groundCtl.swatch
                    radius: Theme.radiusSmall
                    color: on ? Theme.accentFill : "transparent"
                    border.width: on ? Theme.px(2) : 1
                    border.color: on ? Theme.accent : Theme.lineMuted
                    Accessible.role: Accessible.RadioButton
                    Accessible.name: "Default"
                    Accessible.checked: on
                    Text {
                        textFormat: Text.PlainText
                        id: defaultText
                        anchors.centerIn: parent
                        text: "Default"
                        color: parent.on ? Theme.accent : Theme.textSoft
                        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
                    }
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: Settings.seasonGround = "" }
                }
                Repeater {
                    model: rowItem.r.kind === "grounds" ? Theme.groundSwatches : []
                    Rectangle {
                        readonly property bool on: Theme.groundSaved === modelData
                        width: groundCtl.swatch; height: width; radius: width / 2
                        color: modelData
                        border.width: on ? Theme.px(2) : 1
                        border.color: on ? Theme.accent : Theme.lineMuted
                        Accessible.role: Accessible.RadioButton
                        Accessible.name: (Theme.groundNames[index] || "Color") + " background"
                        Accessible.checked: on
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: Settings.seasonGround = modelData }
                    }
                }
            }
            // Look › Color vision's preview: the status dots, a failed thing, the accent and the call buttons.
            Flow {
                id: previewCtl
                visible: rowItem.r.kind === "preview"
                width: parent.width
                spacing: Theme.px(12)
                Repeater {
                    model: previewCtl.visible ? [{ id: "online", label: "Online" }, { id: "away", label: "Away" }, { id: "dnd", label: "Busy" }] : []
                    Row {
                        spacing: Theme.px(6)
                        height: Theme.px(28)
                        StatusDot { anchors.verticalCenter: parent.verticalCenter; width: Theme.px(16); status: modelData.id; backdrop: Theme.panel }
                        Text { textFormat: Text.PlainText; anchors.verticalCenter: parent.verticalCenter; text: modelData.label; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                    }
                }
                Rectangle {
                    width: failedText.implicitWidth + Theme.px(32); height: Theme.px(28); radius: Theme.radiusSmall
                    color: Theme.dangerFill; border.color: Theme.dangerLine
                    Icon { anchors.left: parent.left; anchors.leftMargin: Theme.px(6); anchors.verticalCenter: parent.verticalCenter; name: "close"; size: Theme.px(14); color: Theme.danger }
                    Text { id: failedText; textFormat: Text.PlainText; anchors.left: parent.left; anchors.leftMargin: Theme.px(24); anchors.verticalCenter: parent.verticalCenter
                           text: "Failed"; color: Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold }
                }
                Rectangle {
                    width: accentText.implicitWidth + Theme.px(16); height: Theme.px(28); radius: Theme.radiusSmall
                    color: Theme.accentFill; border.color: Theme.accent
                    Text { id: accentText; textFormat: Text.PlainText; anchors.centerIn: parent; text: "Accent"; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold }
                }
                Repeater {
                    model: previewCtl.visible ? [{ icon: "call", name: "Answer" }, { icon: "hangup", name: "Hang up" }] : []
                    Rectangle {
                        width: Theme.px(28); height: width; radius: width / 2
                        color: index === 0 ? Theme.success : Theme.danger
                        Accessible.role: Accessible.StaticText
                        Accessible.name: modelData.name
                        Icon { anchors.centerIn: parent; name: modelData.icon; size: Theme.px(16); lineWidth: 2; color: Theme.light ? "#FFFFFF" : "#000000" }
                    }
                }
            }
            HButton {
                id: buttonCtl
                anchors.right: parent.right
                width: Math.min(implicitWidth, parent.width)
                anchors.verticalCenter: parent.verticalCenter
                visible: rowItem.r.kind === "button"
                kind: rowItem.danger ? "danger" : "normal"
                text: rowItem.r.label || ""
                enabled: rowItem.r.enabled !== false
                onClicked: page.press(rowItem.r.key)
            }
        }
        TapHandler { onTapped: { page.inSection = true; page.row = rowItem.at } }
    }

    function showRow(item) {
        if (!item) return
        const y = item.mapToItem(rowsCol, 0, 0).y
        const top = page.row === 0 ? 0 : y - Theme.px(30)
        const bottom = y + item.height + Theme.px(10)
        if (top < rowsView.contentY) rowsView.contentY = Math.max(0, top)
        else if (bottom > rowsView.contentY + rowsView.height) rowsView.contentY = Math.min(bottom - rowsView.height, Math.max(0, rowsView.contentHeight - rowsView.height))
    }
    // "Pick a key": a back paddle has no other job, and with LT talking X opens the options instead. Y and LB have other
    // jobs, so they say what they give up in a voice room. Back paddles may need mapping in Steam Input to reach Huginn
    // (said only in game mode). The controller part shows only while one is connected, so a keyboard-only PC reads one
    // line. A handheld's controller button only works with Huginn in front, and a Linux desktop may ask to confirm a key.
    readonly property string pickNote: Gamepad.connected
        ? "Press the new key, or on a controller " + Gamepad.glyph("lt") + " or a back paddle ("
          + Gamepad.glyph("x") + " then opens the options instead of " + Gamepad.glyph("lt") + "). "
          + (System.gameMode ? "If a back paddle does nothing, set it up in Steam Input first. " : "")
          + Gamepad.glyph("y") + " or " + Gamepad.glyph("lb") + " work too, but in a voice room they only talk. Esc or " + Gamepad.glyph("b") + " keeps the old one"
          + (System.handheld || System.gameMode ? ". The controller button works while Huginn is in front; in a game, voice activity is better" : "")
          + (Qt.platform.os === "linux" && GlobalKeys.everywhere && !System.gameMode ? ". Your desktop may ask to confirm a key" : "")
        : "Press the new key. Esc keeps the old one"
          + (Qt.platform.os === "linux" && GlobalKeys.everywhere && !System.gameMode ? ". Your desktop may ask to confirm a key" : "")
    // The controller talk button's note: what it gives up in a voice room, when it has another job.
    function padNote(b) {
        const g = Gamepad.glyph(b), lt = Gamepad.glyph("lt"), x = Gamepad.glyph("x")
        const away = TalkKey.padAway ? ". No controller connected, so voice activity until one is" : ""
        if (b === "y") return g + " also opens the keyboard, records and calls. In a voice room it only talks. A back paddle has no other job" + away
        if (b === "lb") return g + " also switches sections and channels. In a voice room it only talks, and " + g + " with Up or Down still mutes and deafens without opening the mic" + away
        if (b === "x") return g + " opens the options, so it shouldn't talk. Remove it and pick " + lt + " or a back paddle" + away
        if (b === "lt") return "In a voice room it only talks, and " + x + " opens the options instead" + away
        return "Hold it in a voice room while Huginn is in front" + away
    }
    // Y or LB just picked: a clear warning, since each has another job.
    Connections {
        target: TalkKey
        function onPadPicked(button) {
            const g = Gamepad.glyph(button)
            if (button === "y") Ui.say(g + " also opens the keyboard: in a voice room it only talks. A back paddle has no other job.")
            else if (button === "lb") Ui.say(g + " also switches sections and channels: in a voice room it only talks. " + g + " with Up or Down still mutes and deafens, without opening the mic.")
        }
        // A button that can't talk, pressed while picking: why not.
        function onPadRefused(button) {
            const g = Gamepad.glyph(button)
            const why = button === "x" ? " opens the options"
                      : button === "a" ? " selects"
                      : button === "rb" || button === "rt" ? " clicks for the stick pointer"
                      : button === "start" ? " opens Settings"
                      : button === "back" ? " shows the buttons"
                      : " already has a job"
            Ui.say(g + why + ", so it can't talk. Try " + Gamepad.glyph("lt") + " or a back paddle.")
        }
    }
    // Leaving Settings (or this part of it) stops "Pick a key", so the next key pressed elsewhere never becomes the talk key.
    onSectionChanged: { rowsView.contentY = 0; Rooms.micTest = false; TalkKey.stopCapture() }
    onVisibleChanged: if (!visible) TalkKey.stopCapture()
    Component.onDestruction: { Rooms.micTest = false; TalkKey.stopCapture() }

    function openMain() {
        const m = items[pick]
        if (!m) return
        section = m.id; row = 0
        inSection = true
    }
    function back() { inSection = false }

    // Anything that closes or restarts Huginn checks first that it won't cut off a job.
    function running() {
        if (Ui.main && (Calls.inCall || Rooms.inRoom || FileLink.closeWarning !== "" || LocalSend.busy)) return Ui.main.stakeText("Closing")
        return ""
    }
    // Any accent color, as a code like #6E9CC8.
    function pickColor() {
        const now = Theme.validColor(Settings.accent) ? Settings.accent : String(Theme.accentBase).toUpperCase()
        Ui.ask("Accent color", "A code like #6E9CC8", now, "Use it", t => {
            const v = t.trim().startsWith("#") ? t.trim() : "#" + t.trim()
            if (Theme.validColor(v)) Settings.accent = v.toUpperCase()
            else Ui.say("That isn't a color code, like #6E9CC8.", true)
        })
    }
    // Where a toggle's value lives.
    function source(r) { return r.vault ? Vault : r.chat ? Chat : Settings }
    // A switch flipped. Do not disturb is your status (the same as the status card's).
    function flip(r) {
        if (r.key === "dnd") { Ui.setMyStatus(Ui.myStatus === "dnd" ? "online" : "dnd"); if (Ui.main) Ui.main.autoAway = false; return }
        const o = source(r)
        o[r.key] = !o[r.key]
    }
    // Quiet hours: an hour of the day, the way the clock shows it.
    function hourName(h) { return h === 0 ? "12 AM" : h === 12 ? "12 PM" : h < 12 ? h + " AM" : (h - 12) + " PM" }
    function pickHour(key) {
        const items = []
        for (let h = 0; h < 24; h++) items.push({ name: hourName(h), url: String(h), check: Settings[key] === h })
        Ui.pickFrom(key === "quietFrom" ? "Quiet from" : "Quiet until", [{ header: "", items: items }], v => Settings[key] = Number(v))
    }
    // Settings › Calls: a microphone or speaker, "System default" first.
    function pickDevice(key) {
        const input = key === "micDevice"
        const items = Settings.audioDevices(input).map(d => ({ name: d.name, url: d.id === "" ? "default" : d.id, check: d.id === Settings[key] }))
        Ui.pickFrom(input ? "Microphone" : "Speaker", [{ header: "", items: items }], v => Settings[key] = v === "default" ? "" : v)
    }
    // A choice in a row of choices.
    function pickSegment(r, v) {
        if (r.rooms) { Rooms[r.key] = v; return }
        if (r.chat) Chat[r.key] = Number(v)
        else Settings[r.key] = v
    }

    // ------------------------------------------------------------- chat backup (BackupPanel.qml)
    function startBackup() {
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
        Ui.main.openBackup("backup", false)
    }
    function startRestore() {
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
        Ui.main.openBackup("restore", false)
    }

    function press(key) {
        const job = running()
        if (key === "chatName") Ui.ask("Your name", "A nickname is fine", Ui.myName, "Save", t => Ui.setMyName(t))
        else if (key === "picture") { if (Ui.phoneIsMe) Ui.say("Your picture comes from your phone while it's linked. Change it there."); else if (Ui.profile) Ui.profile.pickPicture() }
        else if (key === "myCode") {
            if (!Chat.ready) { Ui.say("Unlock Huginn first.", true); return }
            Ui.openPage("dms", {})
            Qt.callLater(() => { const p = Ui.main.page(); if (p && p.showCode !== undefined) { Chat.newLink(); p.showCode = true } })
        }
        else if (key === "sizeDown") Ui.main.stepSize(-1)
        else if (key === "sizeUp") Ui.main.stepSize(1)
        else if (key === "sizeReset") Ui.main.stepSize(0)
        else if (key === "linked") PeopleActions.linkedDevices()
        else if (key === "quietFrom" || key === "quietTo") pickHour(key)
        else if (key === "micDevice" || key === "speakerDevice") pickDevice(key)
        else if (key === "saveFolder") { if (Transfer.pickSaveFolder()) Ui.say("Received files now go in " + Transfer.saveName + "/Huginn.") }
        else if (key === "saveFolderReset") { Transfer.resetSaveFolder(); Ui.say("Received files go in Downloads/Huginn again.") }
        else if (key === "deviceName") Ui.ask("This computer's name", "Your phone and nearby devices see it", Settings.deviceName, "Save", t => Settings.deviceName = t, false, true)
        else if (key === "micTest") Rooms.micTest = !Rooms.micTest
        else if (key === "talkKey") { if (TalkKey.capturing) TalkKey.stopCapture(); else TalkKey.capture() }
        else if (key === "talkPad") TalkKey.clearPad()
        else if (key === "clearTalkKey") TalkKey.clearKey()
        else if (key === "muteKey" || key === "deafenKey") {
            const which = key === "muteKey" ? 1 : 2
            if (GlobalKeys.capturing === which) GlobalKeys.stopCapture(); else GlobalKeys.capture(which)
        }
        else if (key === "resetKeys") { GlobalKeys.resetKeys(); Ui.say("Mute and deafen keys are Ctrl+Shift+M and Ctrl+Shift+D again.") }
        else if (key === "resetPanels") { Ui.resetPanels(); Ui.say("Panels reset.") }
        else if (key === "fallAnyway") { Settings.seasonAnimation = "battery"; Ui.say("Animation: On, even on battery. It moves slower on battery to save power.") }
        else if (key === "shortcuts") Ui.main.showShortcuts()
        else if (key === "backup") startBackup()
        else if (key === "restore") startRestore()
        else if (key === "changeLock") Ui.openPage("chat", { changeLock: true })
        else if (key === "lockOff") Ui.sure("Turn off the lock?", "Anyone here could read your chats.",
                                            "Turn off", () => Ui.openPage("chat", { lockOff: true }), false, true)
        else if (key === "deleteMessages" && !Chat.ready) Ui.say("Unlock Huginn first.", true)
        else if (key === "deleteMessages") Ui.sure("Delete all messages?", "Can't be undone.", "Hold to delete", () => { Chat.deleteAll(); Ui.say("All messages deleted.") }, true, true)
        else if (key === "eraseChat") Ui.sure("Erase everything?", "Deletes everything on this device. Can't be undone." + (job ? " " + job : ""),
                                              "Hold to erase", () => { Vault.eraseAll(); Ui.main.quitNow() }, true, true)
        else if (key === "raven") tapRaven()
        else if (key === "report") Ui.main.openReport()
        else if (key === "storage") Ui.main.openAppStorage()
        else if (key === "perf") Ui.main.openPerf()
        else if (key === "practice") Ui.main.openSetup(true)
        else if (key === "dataFolder") { if (!System.openDataFolder()) Ui.say("Couldn't open the folder.", true) }
        else if (key === "goStable") {
            if (Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
            Ui.sure("Go back to stable?", "Installs the latest stable Huginn, then restarts. Labs mode turns off." + (job ? " " + job : ""),
                    "Go back", () => Updater.goBackToStable(), false, true)
        }
        else if (key === "check") {
            if (Updater.ready) Ui.main.restartForUpdate()
            else if (!Updater.available) Updater.check()
            else if (job) Ui.sure("Update now?", job, "Update anyway", () => Updater.install(), true, true)
            else Updater.install()
        }
    }

    function change(dir) {
        const r = rows[row]
        if (!r) return
        if (r.kind === "toggle") flip(r)
        else if (r.kind === "segmented") {
            const opts = r.options
            const now = r.value !== undefined ? r.value : r.chat ? String(Chat[r.key]) : Settings[r.key]
            let i = opts.findIndex(o => o.value === now)
            // A on Voice activity goes between Automatic and Manual only: Always on (game sound too) takes Right.
            if (dir === 0 && r.key === "gateMode") { pickSegment(r, now === "auto" ? "manual" : "auto"); return }
            i = dir === 0 ? (i + 1) % opts.length : Math.max(0, Math.min(opts.length - 1, i + dir))
            pickSegment(r, opts[i].value)
        } else if (r.kind === "grounds") {
            // Default, then each background.
            const order = [""].concat(Theme.groundSwatches)
            const at = Math.max(0, order.indexOf(Theme.groundSaved))
            const i = dir === 0 ? (at + 1) % order.length : Math.max(0, Math.min(order.length - 1, at + dir))
            Settings.seasonGround = order[i]
        } else if (r.kind === "accent") {
            // The named colors, then Any color (A on it asks for a code).
            const order = Theme.accentNames.concat(Theme.mono ? [] : ["custom"])
            const at = Theme.accentNames.indexOf(Theme.accentKey) < 0 ? order.length - 1 : Theme.accentNames.indexOf(Theme.accentKey)
            // A on Any color (a code is in use) asks for another code instead of going round to gold.
            if (dir === 0 && order[at] === "custom") { pickColor(); return }
            const i = dir === 0 ? (at + 1) % order.length : Math.max(0, Math.min(order.length - 1, at + dir))
            if (i === at && dir !== 0) return
            if (order[i] === "custom") pickColor()
            else Settings.accent = order[i]
        } else if (r.kind === "swatches") {
            const at = Math.max(0, swatches.indexOf(String(Profile.myColour).toUpperCase()))
            const i = dir === 0 ? (at + 1) % swatches.length : Math.max(0, Math.min(swatches.length - 1, at + dir))
            Profile.setMyColour(swatches[i])
        } else if (r.kind === "size") { if (dir === 0) press("sizeReset"); else Ui.main.stepSize(dir) }
        else if (r.kind === "slider") stepFall(r.key, dir)
        else if (r.kind === "button" && dir === 0 && r.enabled !== false) press(r.key)
        else if (r.kind === "raven" && dir === 0) tapRaven()
    }

    // Returns false for what this page doesn't use, so Main.qml can take it (Left or B to the rail).
    function handleButton(b) {
        if (!inSection) {
            switch (b) {
            case "up": pick = Math.max(0, pick - 1); return true
            case "down": pick = Math.min(items.length - 1, pick + 1); return true
            case "a": case "right": openMain(); return true
            }
            return false
        }
        const r = rows[row]
        const sideways = r && (r.kind === "segmented" || r.kind === "accent" || r.kind === "size" || r.kind === "slider" || r.kind === "swatches" || r.kind === "grounds")
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(rows.length - 1, row + 1); return true
        case "a": change(0); return true
        case "left": if (sideways) change(-1); else back(); return true
        case "right": change(1); return true
        case "b": back(); return true
        }
        return false
    }

    // Look › Seasonal: a plain slider in house style. A line, a knob, Left and Right or a drag to move it.
    // A slider from 0 to 100 (stepSize a notch), with the names of its two ends under the track.
    // moved: a press or drag at a position; nudged: one notch left (-1) or right (1).
    component LookSlider: Item {
        id: sl
        property real from: 0
        property real to: 100
        property real stepSize: 1
        property real value: 0
        property string label: ""
        property string low: ""
        property string high: ""
        property bool focused: false
        // A level bar behind the track, 0 to 1 (-1: none); lit while the sound goes out.
        property real meter: -1
        property bool meterLit: false
        signal moved(real value)
        signal nudged(int dir)
        readonly property bool ends: low.length > 0 || high.length > 0
        readonly property real trackY: Theme.px(14)
        implicitHeight: Theme.px(28) + (ends ? lowText.implicitHeight : 0)
        readonly property real frac: to > from ? Math.max(0, Math.min(1, (value - from) / (to - from))) : 0
        Accessible.role: Accessible.Slider
        Accessible.name: label
        Accessible.onIncreaseAction: sl.nudged(1)
        Accessible.onDecreaseAction: sl.nudged(-1)
        function at(px) {
            const f = Math.max(0, Math.min(1, (px - knob.width / 2) / (width - knob.width)))
            let v = from + f * (to - from)
            v = Math.round(v / stepSize) * stepSize
            moved(Math.max(from, Math.min(to, v)))
        }
        Rectangle {
            visible: sl.meter >= 0
            y: sl.trackY - height / 2; x: knob.width / 2
            width: (parent.width - knob.width) * Math.max(0, sl.meter); height: Theme.px(10)
            radius: Theme.radiusSmall
            color: sl.meterLit ? Theme.success : Theme.textFaint
            opacity: 0.6
        }
        Rectangle { y: sl.trackY - height / 2; x: knob.width / 2; width: parent.width - knob.width; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.lineMuted }
        Rectangle { y: sl.trackY - height / 2; x: knob.width / 2; width: (parent.width - knob.width) * sl.frac; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.accent }
        Rectangle {
            id: knob
            y: sl.trackY - height / 2
            x: (sl.width - width) * sl.frac
            width: Theme.px(18); height: width; radius: Theme.seasonal ? width / 2 : Theme.radiusSmall
            color: slHover.hovered || sl.focused ? Theme.accent : Theme.textSoft
            border.width: sl.focused ? Theme.focusWidth : 0
            border.color: Theme.text
        }
        Text {
            textFormat: Text.PlainText
            id: lowText
            visible: sl.ends
            x: 0; y: Theme.px(26)
            text: sl.low
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
        Text {
            textFormat: Text.PlainText
            visible: sl.ends
            x: sl.width - width; y: Theme.px(26)
            text: sl.high
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
        HoverHandler { id: slHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: point => sl.at(point.position.x) }
        DragHandler {
            target: null
            onCentroidChanged: if (active) sl.at(centroid.position.x)
        }
    }
}

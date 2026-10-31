import QtQuick
import Huginn

// Settings: one flat list on the left (You, Notifications, Look, Calls, Privacy and lock, Devices,
// Controller, Backup, About, and Erase last in red). A choice opens its rows on the right, related
// ones together in framed sections. Changes save as you go. About starts with the raven: ten presses
// (a click or A) turn Labs mode on or off, which adds the Labs group (update channel, bug reports).
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
    readonly property int fallShown: Theme.fallCount(Ui.main ? Ui.main.width : 1600, Ui.main ? Ui.main.height : 900)
    // Look › Amount and Size: where the slider sits (0 to 100) and what a position sets.
    function fallPos(key) { return key === "fallCount" ? Theme.fallCountPos(fallShown) : Theme.fallSizePos(Settings.fallSize) }
    function setFall(key, v) {
        if (key === "fallCount") Settings.fallCount = Theme.fallCountAt(v)
        else Settings.fallSize = Theme.fallSizeAt(v)
    }
    // One notch left or right: on to the next number the slider gives (near 1 several positions give
    // the same one). A puts it back: Amount follows the window again, Size goes to 60%.
    function stepFall(key, dir) {
        if (dir === 0) {
            if (key === "fallCount") Settings.fallCount = 0
            else Settings.fallSize = 60
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
    readonly property var swatches: ["#C2A25E", "#6E9CC8", "#6FAE8C", "#CF6F54", "#A58BC6", "#B07A5A"]

    // Each row: { kind: "segmented"|"toggle"|"accent"|"swatches"|"grounds"|"button"|"size"|"slider"|"info", group, title, note, ... }.
    // A segmented row's value is Settings[key] (or Chat's), unless the row gives its own value.
    // Rows with the same group sit in one framed section; notes are a few words at most.
    readonly property var rows: {
        switch (section) {
        case "you": return [
            { kind: "button", key: "chatName", title: "Name", note: Chat.username, label: "Change" },
            { kind: "button", key: "picture", title: "Picture", label: Profile.hasMyPicture ? "Change" : "Choose" },
            { kind: "swatches", key: "myColour", title: "Color" },
            { kind: "button", key: "myCode", title: "My code", label: "Show" }
        ]
        case "notify": return [
            { kind: "toggle", key: "showPreview", chat: true, title: "Show message text", note: "Off: only “New message”" }
        ]
        case "look": return [
            // Seasonal names its season; pressing it again steps to the next season.
            { group: "Theme", kind: "segmented", key: "theme", title: "Theme", value: Theme.choice,
              options: [{ value: "dark", label: "Dark" }, { value: "black", label: "True black" }, { value: "light", label: "Light" }, { value: "seasonal", label: "Seasonal: " + Theme.seasonName }] },
            // Seasonal only: the season, its background, what falls and how much.
            { group: "Theme", kind: "segmented", key: "season", hide: Theme.choice !== "seasonal", title: "Season", note: "Auto follows the date",
              value: Theme.seasonPick, options: Theme.seasonChoices },
            { group: "Theme", kind: "grounds", key: "seasonGround", hide: Theme.choice !== "seasonal", title: "Background" },
            { group: "Theme", kind: "segmented", key: "seasonAnimation", hide: Theme.choice !== "seasonal", title: "Animation", note: "Less motion holds it still",
              options: [{ value: "off", label: "Off" }, { value: "full", label: "On" }] },
            { group: "Theme", kind: "slider", key: "fallCount", hide: !Theme.seasonal || Settings.seasonAnimation === "off",
              title: "Amount: " + page.fallShown + (Theme.snow ? (page.fallShown === 1 ? " flake" : " flakes") : (page.fallShown === 1 ? " leaf" : " leaves")),
              low: "1", high: "400" },
            { group: "Theme", kind: "slider", key: "fallSize", hide: !Theme.seasonal || Settings.seasonAnimation === "off",
              title: "Size: " + Settings.fallSize + "%", low: "Tiny", high: "Huge" },
            { group: "Theme", kind: "accent", key: "accent", title: "Accent color" },
            { group: "Theme", kind: "segmented", key: "density", title: "Messages",
              options: [{ value: "compact", label: "Compact" }, { value: "cozy", label: "Default" }, { value: "spacious", label: "Spacious" }] },
            { group: "Size", kind: "size", key: "uiSize", title: "Size: " + Settings.uiSize + "%", note: "Ctrl+= and Ctrl+- too" },
            { group: "Size", kind: "segmented", key: "textSize", title: "Text size",
              options: [{ value: "100", label: "Normal" }, { value: "115", label: "Large" }, { value: "130", label: "Larger" }, { value: "150", label: "Largest" }] },
            { group: "Accessibility", kind: "toggle", key: "highContrast", title: "High contrast" },
            { group: "Accessibility", kind: "toggle", key: "colorBlind", title: "Color-blind friendly" },
            { group: "Accessibility", kind: "toggle", key: "reduceMotion", title: "Less motion" },
            { group: "Window", kind: "toggle", key: "fullScreen", title: "Open full screen", note: "F11 too", hide: System.windowed || System.gameMode },
            { group: "Panels", kind: "toggle", key: "showList", title: "Show the chat list", note: "Ctrl+Shift+L" },
            { group: "Panels", kind: "toggle", key: "showMembers", title: "Show group members", note: "Ctrl+Shift+U" },
            { group: "Panels", kind: "button", key: "resetPanels", title: "Panel sizes", label: "Reset" }
        ].filter(r => !r.hide)
        case "calls": return [
            { kind: "toggle", key: "noiseRemoval", title: "Noise removal" },
            { kind: "toggle", key: "internetCalls", title: "Internet calls", note: "Call people anywhere" },
            { kind: "toggle", key: "pushToTalk", title: "Push to talk", note: "Voice rooms: hold a key" },
            { kind: "button", key: "talkKey", title: "Talk key: " + TalkKey.keyName, note: TalkKey.capturing ? "Press the new key" : "", label: TalkKey.capturing ? "Press a key" : "Change" }
        ]
        case "privacy": return [
            { group: "Lock", kind: "button", key: "changeLock", title: Vault.exists && !Vault.hasLock ? "Lock: off" : "Lock",
              note: "Locks your chats. Can't be recovered.", label: Vault.exists && !Vault.hasLock ? "Set a lock" : "Change", enabled: Vault.exists },
            { group: "Lock", kind: "segmented", key: "lockPosition", title: "Pattern position",
              options: [{ value: "top", label: "Top" }, { value: "middle", label: "Middle" }, { value: "lower", label: "Lower" }] },
            { group: "Lock", kind: "toggle", key: "patternTrail", invert: true, title: "Hide the pattern as I draw" },
            { group: "Lock", kind: "toggle", key: "lockOnLeave", vault: true, hide: Vault.exists && !Vault.hasLock, title: "Lock when I leave" },
            { group: "Lock", kind: "toggle", key: "panicLock", vault: true, hide: Vault.exists && !Vault.hasLock, title: "Panic lock", note: "Ctrl+Shift+Q, or hold L3+R3" },
            { group: "Privacy", kind: "toggle", key: "readReceipts", chat: true, title: "Send read receipts" },
            { group: "Privacy", kind: "toggle", key: "activeStatus", chat: true, title: "Active status", note: "Shows when Huginn is open" },
            { group: "Privacy", kind: "toggle", key: "linkPreviews", chat: true, title: "Link previews" },
            { group: "Privacy", kind: "segmented", key: "deleteAfter", chat: true, title: "Keep messages",
              options: [{ value: "0", label: "Each chat's limit" }, { value: "86400000", label: "One day" }] },
            { group: "Nearby", kind: "toggle", key: "visibleNearby", chat: true, title: "Let people nearby find you" },
            { group: "Nearby", kind: "toggle", key: "nearbyOn", chat: true, hide: !Chat.visibleNearby, title: "Nearby room" },
            { group: "Nearby", kind: "toggle", key: "keepNearby", chat: true, hide: !Chat.visibleNearby, title: "Keep Nearby messages" },
            { group: " ", kind: "button", key: "lockOff", hide: !Vault.hasLock, title: "Turn off the lock", label: "Turn off" }
        ].filter(r => !r.hide)
        case "devices": return [
            { kind: "button", key: "linked", title: "Linked devices", note: Linked.phone.key ? "Linked to " + Linked.phone.name : "", label: "Open" },
            { kind: "toggle", key: "nearbySharing", title: "Share files nearby", note: "Works with LocalSend" }
        ]
        case "controller": return [
            { kind: "info", title: "Show the buttons", note: "Press View (Select)" },
            { kind: "info", title: "Pointer", note: "Right stick moves, RB clicks" },
            { kind: "button", key: "shortcuts", title: "Keyboard shortcuts", note: "Ctrl+/", label: "Open" }
        ]
        case "backup": return [
            { kind: "button", key: "backup", title: "Back up chats", note: "Saved in Documents", label: Vault.busy ? "Working" : "Back up", enabled: Vault.exists && !Vault.busy },
            { kind: "button", key: "restore", title: "Restore from backup", label: "Restore", enabled: Vault.exists && !Vault.busy }
        ]
        case "about": return [
            { kind: "raven", key: "raven", title: "Huginn " + System.version, note: Settings.labs ? "Labs mode on" : "" },
            { kind: "button", key: "check", title: Updater.status,
              label: Updater.available ? "Install " + Updater.latest : "Check now", enabled: !Updater.busy },
            { kind: "toggle", key: "autoUpdate", title: "Update automatically" },
            { kind: "info", title: "Made by Ravenor907", note: "GPL-3.0. Fonts: SIL Open Font License" },
            { group: "Labs", kind: "segmented", key: "updateChannel", hide: !Settings.labs, title: "Update channel",
              options: [{ value: "stable", label: "Stable" }, { value: "beta", label: "Pre-release" }] },
            { group: "Labs", kind: "button", key: "goStable", hide: !Settings.labs, title: "Go back to stable", note: "Installs the latest stable",
              label: "Go back", enabled: !Updater.busy },
            { group: "Labs", kind: "button", key: "report", hide: !Settings.labs, title: "Report a bug", note: "Screenshot optional", label: "Report" }
        ].filter(r => !r.hide)
        case "erase": return [
            { kind: "button", key: "deleteMessages", title: "Delete all messages", note: "Contacts stay", label: "Delete" },
            { kind: "button", key: "eraseChat", title: "Erase everything", note: "Deletes everything on this PC.", label: "Erase" }
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
        // Wide choices go under the words when there isn't room beside them.
        readonly property bool stacked: control.width > width * 0.5
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
            Text { width: parent.width; wrapMode: Text.WordWrap; text: rowItem.r.title || ""; color: rowItem.danger ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
            Text { visible: !!rowItem.r.note; width: parent.width; wrapMode: Text.WordWrap; text: rowItem.r.note || ""; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
        }
        Item {
            id: control
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(16)
            y: rowItem.stacked ? texts.y + texts.implicitHeight + Theme.px(10) : (parent.height - height) / 2
            // Never wider than the row: on a narrow window the choices squeeze to fit.
            width: Math.min(rowItem.width - Theme.px(32),
                   rowItem.r.kind === "segmented" ? segCtl.implicitWidth
                 : rowItem.r.kind === "toggle" ? toggleCtl.implicitWidth
                 : rowItem.r.kind === "accent" ? accentCtl.naturalWidth
                 : rowItem.r.kind === "swatches" ? swatchCtl.implicitWidth
                 : rowItem.r.kind === "grounds" ? groundCtl.naturalWidth
                 : rowItem.r.kind === "size" ? sizeCtl.naturalWidth
                 : rowItem.r.kind === "slider" ? Theme.px(220)
                 : rowItem.r.kind === "raven" ? ravenCtl.width
                 : rowItem.r.kind === "button" ? buttonCtl.implicitWidth : 0)
            height: Math.max(Theme.px(36), segCtl.visible ? segCtl.implicitHeight : 0, buttonCtl.visible ? buttonCtl.implicitHeight : 0,
                             ravenCtl.visible ? ravenCtl.height : 0, sliderCtl.visible ? sliderCtl.implicitHeight : 0)
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
                checked: rowItem.r.kind === "toggle" ? (page.source(rowItem.r)[rowItem.r.key] !== !!rowItem.r.invert) : false
                onToggled: { const o = page.source(rowItem.r); o[rowItem.r.key] = !o[rowItem.r.key] }
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
                // Seven named colors and Any color; smaller swatches on a narrow window.
                readonly property real naturalWidth: Theme.px(28) * 8 + spacing * 7
                readonly property int swatch: Math.max(Theme.px(20), Math.min(Theme.px(28), Math.floor((control.width - spacing * 7) / 8)))
                Repeater {
                    model: rowItem.r.kind === "accent" ? Theme.accentNames : []
                    Rectangle {
                        width: accentCtl.swatch; height: width; radius: Theme.radius
                        color: Theme.light ? Qt.darker(Theme.accents[modelData], 1.7) : Theme.accents[modelData]
                        border.width: Settings.accent === modelData ? Theme.px(2) : 1
                        border.color: Settings.accent === modelData ? Theme.text : Theme.lineMuted
                        Accessible.role: Accessible.RadioButton
                        Accessible.name: modelData.charAt(0).toUpperCase() + modelData.slice(1)
                        Accessible.checked: Settings.accent === modelData
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: Settings.accent = modelData }
                    }
                }
                // Any color: a code like #6E9CC8.
                Rectangle {
                    readonly property bool custom: Theme.accentNames.indexOf(Settings.accent) < 0
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
                        readonly property bool mine: String(Profile.myColour).toUpperCase() === modelData
                        width: Theme.px(28); height: width; radius: width / 2
                        color: modelData
                        border.width: mine ? Theme.px(2) : 1
                        border.color: mine ? Theme.text : Theme.lineMuted
                        Accessible.role: Accessible.RadioButton
                        Accessible.name: "Color " + (index + 1)
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
                    readonly property bool on: Theme.groundPick === ""
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
                        readonly property bool on: Theme.groundPick === modelData
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
    onSectionChanged: rowsView.contentY = 0

    function openMain() {
        const m = items[pick]
        if (!m) return
        section = m.id; row = 0
        inSection = true
    }
    function back() { inSection = false }

    // Anything that closes or restarts Huginn checks first that it won't cut off a job.
    function running() {
        if (LocalSend.busy) return LocalSend.status + " Closing now stops it partway."
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
    // A choice in a row of choices. Seasonal picked again (a click, or A on it) steps to the next season.
    function pickSegment(r, v) {
        if (r.chat) Chat[r.key] = Number(v)
        else if (r.key === "theme" && v === "seasonal" && Theme.choice === "seasonal") Settings.season = Theme.nextSeason()
        else Settings[r.key] = v
    }

    // ------------------------------------------------------------- chat backup
    function startBackup() {
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
        Ui.ask("Backup password", "12 or more characters. Can't be recovered.", "", "Next", t => {
            if (t.length < 12) { Ui.say("At least 12 characters.", true); return }
            Ui.ask("Type it again", "To be sure", "", "Back up", t2 => {
                if (t2 !== t) { Ui.say("They didn't match.", true); return }
                Ui.say("Backing up your chats...")
                Vault.backup(t)
            }, true)
        }, true)
    }
    function startRestore() {
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
        const list = Vault.backups()
        if (!list.length) { Ui.say("No backups in Documents.", true); return }
        const apps = {}
        list.forEach(e => apps[e.url] = e.app)
        Ui.pickFrom("Restore which backup?", [{ header: "", items: list }], url => {
            if (apps[url] === "muninn") { Ui.say("That's a Muninn backup. Restore it in Muninn.", true); return }
            Ui.ask("Backup password", "", "", "Open", pw => {
                Ui.say("Opening the backup...")
                Vault.openBackup(url, pw)
            }, true)
        })
    }
    Connections {
        target: Vault
        function onDone(what, ok, message) {
            if (what === "open" && ok)
                Ui.sure("Replace everything?",
                        message + " Everything here is replaced. Can't be undone.",
                        "Hold to restore", () => Vault.restoreOpened(), true, true, () => Vault.forgetOpened())
            else
                Ui.say(message, !ok)
        }
    }


    function press(key) {
        const job = running()
        if (key === "chatName") Ui.ask("Your name", "Not your real one", Chat.username, "Save", t => Chat.username = t)
        else if (key === "picture") { if (Ui.profile) Ui.profile.pickPicture() }
        else if (key === "myCode") {
            if (!Chat.ready) { Ui.say("Unlock Huginn first.", true); return }
            Ui.openPage("dms", {})
            Qt.callLater(() => { const p = Ui.main.page(); if (p && p.showCode !== undefined) { Chat.newLink(); p.showCode = true } })
        }
        else if (key === "sizeDown") Ui.main.stepSize(-1)
        else if (key === "sizeUp") Ui.main.stepSize(1)
        else if (key === "sizeReset") Ui.main.stepSize(0)
        else if (key === "linked") PeopleActions.linkedDevices()
        else if (key === "talkKey") { if (TalkKey.capturing) TalkKey.stopCapture(); else TalkKey.capture() }
        else if (key === "resetPanels") { Ui.resetPanels(); Ui.say("Panels reset.") }
        else if (key === "shortcuts") Ui.main.showShortcuts()
        else if (key === "backup") startBackup()
        else if (key === "restore") startRestore()
        else if (key === "changeLock") Ui.openPage("chat", { changeLock: true })
        else if (key === "lockOff") Ui.sure("Turn off the lock?", "Anyone here could read your chats.",
                                            "Turn off", () => Ui.openPage("chat", { lockOff: true }), false, true)
        else if (key === "deleteMessages" && !Chat.ready) Ui.say("Unlock Huginn first.", true)
        else if (key === "deleteMessages") Ui.sure("Delete all messages?", "Can't be undone.", "Hold to delete", () => { Chat.deleteAll(); Ui.say("All messages deleted.") }, true, true)
        else if (key === "eraseChat") Ui.sure("Erase everything?", "Deletes everything on this PC. Can't be undone.",
                                              "Hold to erase", () => { Vault.eraseAll(); Qt.quit() }, true, true)
        else if (key === "raven") tapRaven()
        else if (key === "report") Ui.main.openReport()
        else if (key === "goStable") {
            if (Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
            Ui.sure("Go back to stable?", "Installs the latest stable Huginn, then restarts. Labs mode turns off." + (job ? " " + job : ""),
                    "Go back", () => Updater.goBackToStable(), false, true)
        }
        else if (key === "check") {
            if (!Updater.available) Updater.check()
            else if (job) Ui.sure("Update now?", job, "Update anyway", () => Updater.install(), true, true)
            else Updater.install()
        }
    }

    function change(dir) {
        const r = rows[row]
        if (!r) return
        if (r.kind === "toggle") { const o = source(r); o[r.key] = !o[r.key] }
        else if (r.kind === "segmented") {
            const opts = r.options
            const now = r.value !== undefined ? r.value : r.chat ? String(Chat[r.key]) : Settings[r.key]
            // A on Seasonal does what a click does: the next season.
            if (dir === 0 && r.key === "theme" && now === "seasonal") { pickSegment(r, "seasonal"); return }
            let i = opts.findIndex(o => o.value === now)
            i = dir === 0 ? (i + 1) % opts.length : Math.max(0, Math.min(opts.length - 1, i + dir))
            pickSegment(r, opts[i].value)
        } else if (r.kind === "grounds") {
            // Default, then each background.
            const order = [""].concat(Theme.groundSwatches)
            const at = Math.max(0, order.indexOf(Theme.groundPick))
            const i = dir === 0 ? (at + 1) % order.length : Math.max(0, Math.min(order.length - 1, at + dir))
            Settings.seasonGround = order[i]
        } else if (r.kind === "accent") {
            // The named colors, then Any color (A on it asks for a code).
            const order = Theme.accentNames.concat(["custom"])
            const at = Theme.accentNames.indexOf(Settings.accent) < 0 ? order.length - 1 : Theme.accentNames.indexOf(Settings.accent)
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
            id: lowText
            visible: sl.ends
            x: 0; y: Theme.px(26)
            text: sl.low
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
        Text {
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

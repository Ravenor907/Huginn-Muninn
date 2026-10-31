import QtQuick
import QtQuick.Shapes
import Huginn

// The rail down the left edge, built from a list:
// items = [{ id, kind: "raven" | "group" | "add" | "icon", label, icon, letter, badge, unread, bottom }].
// Chats (the raven) sits on top, then the groups (round initials; the one on screen is a
// gold square with a gold pill beside it, one with something unread a short pill, and its
// @mentions of me a number) and + for a new group. Contacts, Files, Settings (the valknut)
// and the power button sit at the foot, always on show; the power button opens a small menu: Lock
// Huginn (when a lock is set) and Close Huginn.
// Pointing at one names it. LB steps through them (Main.qml); the D-pad moves the rail focus.
Rectangle {
    id: rail
    property var items: []
    property string current: ""       // the section on screen
    property int focusIndex: 0
    property bool focused: false      // the controller focus is on the rail
    signal activated(int index)
    signal lockRequested()
    signal exitRequested()
    property string tipText: ""
    property real tipY: 0

    // The power menu. Up and Down move along it, A chooses, B closes.
    property bool menuOpen: false
    property int menuIndex: 0
    readonly property bool canLock: Vault.hasLock && !Vault.screenLocked   // with the lock off there's nothing to lock
    readonly property var menuItems: (canLock ? [{ id: "lock", label: "Lock Huginn", icon: "lock" }] : [])
                                     .concat([{ id: "exit", label: "Close Huginn", icon: "power", danger: true }])
    function openPowerMenu() { menuIndex = 0; menuOpen = true; tipText = "" }
    function closeMenu() { menuOpen = false }
    function runMenu(id) {
        closeMenu()
        if (id === "lock") lockRequested()
        else if (id === "exit") exitRequested()
    }
    function handleButton(b) {
        if (!menuOpen) return false
        switch (b) {
        case "up": menuIndex = Math.max(0, menuIndex - 1); break
        case "down": menuIndex = Math.min(menuItems.length - 1, menuIndex + 1); break
        case "a": { const m = menuItems[menuIndex]; if (m) runMenu(m.id); break }
        case "b": case "left": closeMenu(); break
        }
        return true
    }

    readonly property var withIndex: items.map((e, i) => Object.assign({ idx: i }, e))
    readonly property var ravenItems: withIndex.filter(e => !e.bottom && e.kind === "raven")
    readonly property var groupItems: withIndex.filter(e => !e.bottom && e.kind !== "raven")
    readonly property var bottomItems: withIndex.filter(e => !!e.bottom && !e.hidden)

    implicitWidth: Theme.railWidth
    color: Theme.rail
    Accessible.role: Accessible.PageTabList
    Accessible.name: "Sections"

    // Keep the focused group on screen when there are more than fit.
    onFocusIndexChanged: Qt.callLater(showFocused)
    function showFocused() {
        const at = groupItems.findIndex(e => e.idx === focusIndex)
        if (at < 0) return
        const y = at * (Theme.px(48) + groupCol.spacing)
        if (y < groupView.contentY) groupView.contentY = y
        else if (y + Theme.px(48) > groupView.contentY + groupView.height)
            groupView.contentY = Math.min(Math.max(0, groupView.contentHeight - groupView.height), y + Theme.px(48) - groupView.height)
    }

    Column {
        id: top
        anchors.top: parent.top
        anchors.topMargin: Theme.px(4)
        width: parent.width
        spacing: Theme.px(8)
        Repeater {
            model: rail.ravenItems
            RailButton { entry: modelData }
        }
        Rectangle {
            visible: rail.groupItems.length > 0
            anchors.horizontalCenter: parent.horizontalCenter
            width: Theme.px(32); height: 2
            color: Theme.seam
        }
    }

    // Groups and + scroll when there are many.
    Flickable {
        id: groupView
        anchors.top: top.bottom
        anchors.topMargin: Theme.px(8)
        anchors.bottom: bottomCol.top
        anchors.bottomMargin: Theme.px(10)
        width: parent.width
        contentWidth: width
        contentHeight: groupCol.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: groupCol
            width: groupView.width
            spacing: Theme.px(8)
            Repeater {
                model: rail.groupItems
                RailButton { entry: modelData }
            }
        }
    }
    Column {
        id: bottomCol
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.px(8)
        width: parent.width
        spacing: Theme.px(8)
        Rectangle { anchors.horizontalCenter: parent.horizontalCenter; width: Theme.px(30); height: 2; color: Theme.seam }
        Repeater {
            model: rail.bottomItems
            RailButton { entry: modelData }
        }
    }

    // The power menu, beside the power button.
    Rectangle {
        id: powerMenu
        visible: rail.menuOpen
        z: 60
        x: rail.width - Theme.px(4)
        y: Math.max(Theme.px(8), bottomCol.y + bottomCol.height - height - Theme.px(4))
        width: Theme.px(180)
        height: menuCol.implicitHeight + Theme.px(12)
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        Accessible.role: Accessible.PopupMenu
        Accessible.name: "Power"
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        Column {
            id: menuCol
            x: Theme.px(6); y: Theme.px(6)
            width: parent.width - Theme.px(12)
            Repeater {
                model: rail.menuItems
                Rectangle {
                    id: mi
                    readonly property bool on: rail.menuIndex === index
                    width: menuCol.width
                    height: Theme.px(36)
                    radius: Theme.radius
                    color: mi.on || miHover.hovered ? Theme.raised : "transparent"
                    border.width: mi.on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.MenuItem
                    Accessible.name: modelData.label
                    Accessible.onPressAction: rail.runMenu(modelData.id)
                    Icon { id: miIcon; anchors.left: parent.left; anchors.leftMargin: Theme.px(10); anchors.verticalCenter: parent.verticalCenter
                           name: modelData.icon; size: Theme.px(16); color: modelData.danger ? Theme.danger : Theme.textSoft }
                    Text {
                        anchors.left: miIcon.right; anchors.leftMargin: Theme.px(10)
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight
                        text: modelData.label
                        color: modelData.danger ? Theme.danger : Theme.text
                        font.family: Theme.body; font.pixelSize: Theme.fs(14)
                    }
                    HoverHandler { id: miHover; cursorShape: Qt.PointingHandCursor; onHoveredChanged: if (hovered) rail.menuIndex = index }
                    TapHandler { onTapped: rail.runMenu(modelData.id) }
                }
            }
        }
    }

    Rectangle {
        visible: rail.tipText !== "" && !rail.menuOpen
        z: 50
        x: rail.width - Theme.px(4)
        y: rail.tipY - height / 2
        width: tipLabel.implicitWidth + Theme.px(16); height: tipLabel.implicitHeight + Theme.px(10)
        radius: Theme.radius
        color: Theme.groundDeep
        border.color: Theme.line
        Text {
            id: tipLabel
            anchors.centerIn: parent
            text: rail.tipText
            color: Theme.text
            font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
        }
    }

    component RailButton: Item {
        id: rb
        property var entry: ({})
        readonly property bool here: entry.id === rail.current
        readonly property bool on: rail.focused && rail.focusIndex === entry.idx
        readonly property bool big: entry.kind === "raven" || entry.kind === "group" || entry.kind === "add"
        readonly property var tone: Theme.avatar(entry.label || "")
        width: rail.width
        height: Theme.px(big ? 48 : 40)
        Accessible.role: Accessible.PageTab
        Accessible.name: (entry.label || "") + (entry.kind === "group" ? (entry.badge > 0 ? ", " + entry.badge + (entry.badge === 1 ? " mention" : " mentions") : "")
                                                                         + (unread ? ", " + entry.unread + " unread" : "")
                                                 : entry.badge > 0 ? ", " + entry.badge + " new" : "")
        Accessible.selected: here
        Accessible.onPressAction: rail.activated(entry.idx)

        // The section on screen: a gold pill on the left edge. A group with something unread gets a
        // short one, and pointing at a group a middling one.
        readonly property bool unread: (entry.unread || 0) > 0
        Rectangle {
            visible: rb.here || (rb.entry.kind === "group" && (rb.unread || hover.hovered))
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(4)
            height: rb.here ? Theme.px(rb.big ? 32 : 24) : hover.hovered ? Theme.px(18) : Theme.px(8)
            radius: Theme.radius
            color: rb.here || rb.unread ? Theme.accent : Theme.textMuted
            Behavior on height { enabled: Theme.motion; NumberAnimation { duration: 120 } }
        }
        Rectangle {
            id: face
            anchors.centerIn: parent
            width: Theme.px(rb.big ? 40 : 36)
            height: width
            // Groups are round; the one on screen becomes a square, like the raven.
            radius: rb.entry.kind === "raven" || (rb.entry.kind === "group" && rb.here) || rb.entry.kind === "icon" ? Theme.radius : width / 2
            color: rb.entry.kind === "raven" ? Theme.raised
                 : rb.entry.kind === "group" ? (rb.here ? Theme.accent : rb.tone.bg)
                 : rb.entry.kind === "add" ? (rb.on || hover.hovered ? Theme.raised : "transparent")
                 : rb.entry.id === "settings" ? "transparent"
                 : rb.on || rb.here || hover.hovered ? Theme.raised : "transparent"
            border.width: rb.on ? Theme.focusWidth : 0
            border.color: rb.entry.kind === "group" && rb.here ? Theme.text : Theme.accent
            Behavior on radius { enabled: Theme.motion; NumberAnimation { duration: 120 } }
            Image {
                visible: rb.entry.kind === "raven"
                anchors.centerIn: parent
                source: "assets/raven-gold.png"
                width: Theme.px(26); height: width
                sourceSize.width: 128; sourceSize.height: 128
                smooth: true
            }
            Text {
                visible: rb.entry.kind === "group"
                anchors.centerIn: parent
                text: rb.entry.letter || ""
                color: rb.here ? Theme.ground : rb.tone.fg
                font.family: Theme.body
                font.pixelSize: Theme.fs(16)
                font.weight: Font.DemiBold
            }
            // New group: a dashed ring with a plus.
            Shape {
                visible: rb.entry.kind === "add"
                anchors.fill: parent
                preferredRendererType: Shape.CurveRenderer
                ShapePath {
                    strokeColor: Theme.lineMuted
                    strokeWidth: 1
                    strokeStyle: ShapePath.DashLine
                    dashPattern: [3, 3]
                    fillColor: "transparent"
                    PathAngleArc {
                        centerX: face.width / 2; centerY: face.height / 2
                        radiusX: face.width / 2 - 1; radiusY: face.height / 2 - 1
                        startAngle: 0; sweepAngle: 360
                    }
                }
            }
            // Settings: no tile behind the knot. Open, a small soft glow comes from its centre.
            Shape {
                visible: rb.entry.id === "settings" && (rb.here || hover.hovered)
                opacity: rb.here ? 1 : 0.6
                anchors.centerIn: parent
                width: Theme.px(36); height: width
                preferredRendererType: Shape.CurveRenderer
                ShapePath {
                    strokeColor: "transparent"
                    fillGradient: RadialGradient {
                        centerX: Theme.px(18); centerY: centerX; centerRadius: centerX; focalX: centerX; focalY: centerX
                        GradientStop { position: 0.0; color: Qt.rgba(Theme.accent.r, Theme.accent.g, Theme.accent.b, 0.28) }
                        GradientStop { position: 0.5; color: Qt.rgba(Theme.accent.r, Theme.accent.g, Theme.accent.b, 0.08) }
                        GradientStop { position: 1.0; color: "transparent" }
                    }
                    PathAngleArc { centerX: Theme.px(18); centerY: centerX; radiusX: centerX; radiusY: centerX; startAngle: 0; sweepAngle: 360 }
                }
            }
            // Settings: the valknut, black and grey, lifted a little so it looks centred.
            Image {
                visible: rb.entry.id === "settings"
                anchors.centerIn: parent
                anchors.verticalCenterOffset: -Theme.px(2.5)
                source: "assets/valknut.png"
                width: Theme.px(26); height: width
                sourceSize.width: 128; sourceSize.height: 128
                smooth: true; mipmap: true
                opacity: rb.here || rb.on ? 1 : 0.85
            }
            Icon {
                visible: (rb.entry.kind === "icon" && rb.entry.id !== "settings") || rb.entry.kind === "add"
                anchors.centerIn: parent
                name: rb.entry.kind === "add" ? "plus" : (rb.entry.icon || "file")
                size: Theme.px(rb.entry.kind === "add" ? 20 : 20)
                color: rb.here || rb.on ? Theme.accent : rb.entry.id === "exit" ? Theme.danger : Theme.textMuted
            }
            // Something new: unread messages or people asking to chat.
            Rectangle {
                visible: rb.entry.badge > 0
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: -Theme.px(3)
                height: Math.max(Theme.px(18), badgeText.implicitHeight + Theme.px(2))
                width: Math.max(height, badgeText.implicitWidth + Theme.px(8))
                radius: height / 2
                // A group's number is @mentions of me: the warning color, like Discord's red.
                color: rb.entry.kind === "group" ? Theme.danger : Theme.accent
                border.width: 2
                border.color: Theme.rail
                Text {
                    id: badgeText
                    anchors.centerIn: parent
                    text: rb.entry.badge > 99 ? "99+" : String(rb.entry.badge || 0)
                    color: Theme.ground
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(11)
                    font.weight: Font.Bold
                }
            }
            HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: rail.activated(rb.entry.idx) }
        }
        // Its name, beside the rail while the pointer is on it (drawn by the rail, over the page).
        Connections {
            target: hover
            function onHoveredChanged() {
                if (hover.hovered) { rail.tipText = (rb.entry.label || "") + (rb.entry.kind === "group" && rb.entry.badge > 0 ? " · " + rb.entry.badge + " @" : ""); rail.tipY = rb.mapToItem(rail, 0, rb.height / 2).y }
                else if (rail.tipText === (rb.entry.label || "") || rail.tipText.indexOf((rb.entry.label || "") + " · ") === 0) rail.tipText = ""
            }
        }
    }
}

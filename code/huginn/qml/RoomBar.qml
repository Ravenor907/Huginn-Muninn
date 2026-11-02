import QtQuick
import QtMultimedia
import Huginn

// In a voice room or a group call: along the bottom of every page while it lasts. Who's in it (a gold
// ring while someone talks, dimmed while they're muted), how long, and Mute, Output, Open and Leave.
// On a controller the same things are in the voice room's options on its group page (A on the room).
// Share screen sends this computer's screen to everyone in the room; when someone else shares theirs it
// shows above the bar (shared/docs/protocol.md 5.8.7), encrypted to each person like the sound.
Rectangle {
    id: bar
    // Docked: the desktop's voice panel (VoicePanel.qml) has the controls, so only a shared screen shows.
    property bool docked: false
    visible: Rooms.inRoom
    height: visible && !docked ? Theme.px(Theme.desktop ? 64 : 76) : 0
    color: docked ? "transparent" : Theme.column
    border.width: docked ? 0 : 1
    border.color: Theme.line

    property bool screenShown: true
    // Someone else's screen made big: it fills the window above the bar (the button, a double-click, Esc to return).
    property bool screenBig: false
    // Someone new starts sharing: show it again, at the usual size.
    readonly property string sharer: Rooms.sharerName
    onSharerChanged: { screenShown = true; screenBig = false }
    onScreenShownChanged: if (!screenShown) screenBig = false
    Shortcut { sequence: "Esc"; enabled: bar.screenBig && screenPanel.visible; onActivated: bar.screenBig = false }

    // Sharing this computer: always on show while it lasts, with Stop.
    Rectangle {
        id: sharingStrip
        visible: Rooms.inRoom && Rooms.sharing
        anchors.bottom: parent.top
        anchors.left: parent.left; anchors.right: parent.right
        height: visible ? Theme.px(44) : 0
        color: Theme.accentFill
        border.width: 1; border.color: Theme.accent
        Accessible.role: Accessible.StatusBar
        Accessible.name: "You're sharing: " + Rooms.shareTitle
        Icon {
            id: sharingIcon
            anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
            anchors.verticalCenter: parent.verticalCenter
            name: "screen"; size: Theme.px(18); color: Theme.accent
        }
        Text {
            textFormat: Text.PlainText
            anchors.left: sharingIcon.right; anchors.leftMargin: Theme.px(10)
            anchors.right: shareQuality.left; anchors.rightMargin: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideRight
            text: "You're sharing: " + Rooms.shareTitle + " (no sound)"
            color: Theme.text
            font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
        }
        // Smooth (30 frames a second, smaller) or Sharp (full size, for text): changes the share within a second.
        HButton {
            id: shareQuality
            anchors.right: stopShare.left; anchors.rightMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            // Says what it is now; a click switches to the other.
            text: "Quality: " + (Rooms.shareSmooth ? "Smooth" : "Sharp"); fontSize: 13; implicitHeight: Theme.px(32)
            onClicked: Rooms.shareSmooth = !Rooms.shareSmooth
        }
        HButton {
            id: stopShare
            anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
            anchors.verticalCenter: parent.verticalCenter
            text: "Stop"; kind: "danger"; fontSize: 13; implicitHeight: Theme.px(32)
            onClicked: Rooms.toggleShare()
        }
    }

    // Share pressed: what to share, an app window or a full screen, each with a live picture (Wayland and the Flatpak
    // skip this: the desktop's own picker asks). Over the whole window; Main.qml's overlay() hands it the controller.
    property alias sharePicker: picker
    Connections {
        target: Rooms
        function onPickShare() { picker.open() }
    }
    Item {
        id: picker
        parent: bar.parent
        anchors.fill: parent
        z: 60
        visible: false
        property string mode: "window"          // "window" or "screen"
        property var windows: []
        property var screens: []
        property int at: 0
        readonly property var list: mode === "window" ? windows : screens
        function open() {
            windows = Rooms.shareWindows()
            screens = Rooms.shareScreens()
            pickMode(windows.length ? "window" : "screen")
            visible = true
            forceActiveFocus()
        }
        function close() { visible = false }
        // A game is usually a window: windows start Smooth, full screens Sharp (Y or the buttons change it).
        function pickMode(m) { mode = m; at = 0; Rooms.shareSmooth = m === "window" }
        function choose(i) {
            const why = mode === "window" ? Rooms.shareWindow(list[i].i) : Rooms.shareScreen(list[i].i)
            if (why !== "") { Ui.say(why, true); return }
            close()
        }
        function handleButton(b) {
            const cols = Math.max(1, Math.floor(grid.width / grid.cellWidth))
            switch (b) {
            case "a": case "start": if (list.length) choose(at); return true
            case "b": close(); return true
            case "lb": case "rb": case "x":
                if (windows.length) pickMode(mode === "window" ? "screen" : "window")
                return true
            case "y": Rooms.shareSmooth = !Rooms.shareSmooth; return true
            case "left": at = Math.max(0, at - 1); return true
            case "right": at = Math.min(list.length - 1, at + 1); return true
            case "up": at = Math.max(0, at - cols); return true
            case "down": at = Math.min(list.length - 1, at + cols); return true
            }
            return true
        }
        Keys.onPressed: event => {
            const map = { [Qt.Key_Escape]: "b", [Qt.Key_Return]: "a", [Qt.Key_Enter]: "a", [Qt.Key_Left]: "left", [Qt.Key_Right]: "right",
                          [Qt.Key_Up]: "up", [Qt.Key_Down]: "down", [Qt.Key_Tab]: "x" }
            if (map[event.key] !== undefined) { picker.handleButton(map[event.key]); event.accepted = true }
        }
        Rectangle { anchors.fill: parent; color: "#000000"; opacity: 0.55 }
        MouseArea { anchors.fill: parent; onClicked: picker.close() }
        Rectangle {
            id: pickBox
            anchors.centerIn: parent
            width: Math.min(parent.width - Theme.px(40), Theme.px(760))
            height: Math.min(parent.height - Theme.px(40), Theme.px(560))
            radius: Theme.radius
            color: Theme.panel
            border.color: Theme.line
            clip: true
            MouseArea { anchors.fill: parent }   // a click inside doesn't close it
            Text {
                id: pickTitle
                textFormat: Text.PlainText
                x: Theme.px(20); y: Theme.px(18)
                text: "SHARE WHAT?"
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
            }
            Row {
                id: modes
                x: Theme.px(20); anchors.top: pickTitle.bottom; anchors.topMargin: Theme.px(12)
                spacing: Theme.px(6)
                HButton {
                    visible: picker.windows.length > 0
                    text: "An app window"; kind: picker.mode === "window" ? "accent" : "normal"; implicitHeight: Theme.px(36)
                    onClicked: picker.pickMode("window")
                }
                HButton {
                    text: "A full screen"; kind: picker.mode === "screen" ? "accent" : "normal"; implicitHeight: Theme.px(36)
                    onClicked: picker.pickMode("screen")
                }
            }
            Row {
                anchors.right: parent.right; anchors.rightMargin: Theme.px(20)
                anchors.verticalCenter: modes.verticalCenter
                spacing: Theme.px(6)
                HButton { text: "Smooth"; kind: Rooms.shareSmooth ? "accent" : "normal"; implicitHeight: Theme.px(36); onClicked: Rooms.shareSmooth = true }
                HButton { text: "Sharp"; kind: Rooms.shareSmooth ? "normal" : "accent"; implicitHeight: Theme.px(36); onClicked: Rooms.shareSmooth = false }
            }
            // Two lists, one per kind, so each picture's source always matches its row.
            GridView {
                id: grid
                visible: picker.mode === "window"
                anchors.top: modes.bottom; anchors.topMargin: Theme.px(14)
                anchors.left: parent.left; anchors.leftMargin: Theme.px(20)
                anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                anchors.bottom: pickFoot.top; anchors.bottomMargin: Theme.px(8)
                clip: true
                cellWidth: Theme.px(176); cellHeight: Theme.px(136)
                boundsBehavior: Flickable.StopAtBounds
                model: picker.windows
                currentIndex: picker.mode === "window" ? picker.at : -1
                delegate: Item {
                    id: wtile
                    required property var modelData
                    required property int index
                    width: grid.cellWidth; height: grid.cellHeight
                    readonly property bool on: picker.mode === "window" && picker.at === index
                    Rectangle {
                        anchors.fill: parent; anchors.rightMargin: Theme.px(8); anchors.bottomMargin: Theme.px(8)
                        radius: Theme.radius
                        color: wtile.on ? Theme.tile : wHover.hovered ? Theme.raised : "transparent"
                        border.width: wtile.on ? Theme.focusWidth : 1
                        border.color: wtile.on ? Theme.accent : Theme.line
                        Accessible.role: Accessible.Button
                        Accessible.name: wtile.modelData.title
                        Rectangle {
                            x: Theme.px(6); y: Theme.px(6)
                            width: parent.width - Theme.px(12); height: parent.height - Theme.px(40)
                            color: "#000000"
                            // That window, live, while the picker shows windows.
                            CaptureSession {
                                windowCapture: WindowCapture { window: wtile.modelData.window; active: picker.visible && picker.mode === "window" }
                                videoOutput: wOut
                            }
                            VideoOutput { id: wOut; anchors.fill: parent; fillMode: VideoOutput.PreserveAspectFit }
                        }
                        Text {
                            textFormat: Text.PlainText
                            anchors.left: parent.left; anchors.right: parent.right; anchors.margins: Theme.px(8)
                            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(8)
                            elide: Text.ElideRight
                            text: wtile.modelData.title
                            color: Theme.text
                            font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                        HoverHandler { id: wHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: { picker.at = wtile.index; picker.choose(wtile.index) } }
                    }
                }
            }
            GridView {
                id: screenGrid
                visible: picker.mode === "screen"
                anchors.fill: grid
                clip: true
                cellWidth: grid.cellWidth; cellHeight: grid.cellHeight
                boundsBehavior: Flickable.StopAtBounds
                model: picker.screens
                currentIndex: picker.mode === "screen" ? picker.at : -1
                delegate: Item {
                    id: stile
                    required property var modelData
                    required property int index
                    width: screenGrid.cellWidth; height: screenGrid.cellHeight
                    readonly property bool on: picker.mode === "screen" && picker.at === index
                    Rectangle {
                        anchors.fill: parent; anchors.rightMargin: Theme.px(8); anchors.bottomMargin: Theme.px(8)
                        radius: Theme.radius
                        color: stile.on ? Theme.tile : sHover.hovered ? Theme.raised : "transparent"
                        border.width: stile.on ? Theme.focusWidth : 1
                        border.color: stile.on ? Theme.accent : Theme.line
                        Accessible.role: Accessible.Button
                        Accessible.name: stile.modelData.name
                        Rectangle {
                            x: Theme.px(6); y: Theme.px(6)
                            width: parent.width - Theme.px(12); height: parent.height - Theme.px(40)
                            color: "#000000"
                            // That screen, live, while the picker shows screens.
                            CaptureSession {
                                screenCapture: ScreenCapture { screen: Qt.application.screens[stile.modelData.i] || null; active: picker.visible && picker.mode === "screen" }
                                videoOutput: sOut
                            }
                            VideoOutput { id: sOut; anchors.fill: parent; fillMode: VideoOutput.PreserveAspectFit }
                        }
                        Text {
                            textFormat: Text.PlainText
                            anchors.left: parent.left; anchors.right: parent.right; anchors.margins: Theme.px(8)
                            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(8)
                            elide: Text.ElideRight
                            text: stile.modelData.name + (stile.modelData.primary ? " (main)" : "") + " · " + stile.modelData.size
                            color: Theme.text
                            font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                        HoverHandler { id: sHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: { picker.at = stile.index; picker.choose(stile.index) } }
                    }
                }
            }
            Text {
                textFormat: Text.PlainText
                visible: picker.list.length === 0
                anchors.centerIn: grid
                text: "Nothing to share here."
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(14)
            }
            Rectangle {
                id: pickFoot
                anchors.bottom: parent.bottom
                width: parent.width
                height: Theme.px(64)
                color: Theme.panelLow
                Rectangle { width: parent.width; height: 1; color: Theme.line }
                Text {
                    textFormat: Text.PlainText
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(20)
                    anchors.right: pickButtons.left; anchors.rightMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    elide: Text.ElideRight
                    wrapMode: Text.WordWrap
                    maximumLineCount: 3
                    // "No game sound" on a line of its own, so a narrow window cuts the rest first, never that.
                    text: "No game sound.\n" + (picker.mode === "window" ? "Only that window is shared (a game: Borderless or Windowed mode). " : "Everything on that screen is shared. ")
                          + (Rooms.shareSmooth ? "Smooth: up to 30 frames a second, smaller." : "Sharp: full size, up to 10 frames a second.")
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(12)
                }
                Row {
                    id: pickButtons
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(20)
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(8)
                    HButton { text: "Cancel"; kind: "plain"; onClicked: picker.close() }
                    HButton { text: "Share"; kind: "primary"; enabled: picker.list.length > 0; onClicked: picker.choose(picker.at) }
                }
            }
        }
    }

    // Someone else's shared screen, above the bar (above the sharing strip while this computer shares too).
    Rectangle {
        id: screenPanel
        visible: Rooms.inRoom && Rooms.sharerName !== "" && bar.screenShown
        anchors.bottom: sharingStrip.visible ? sharingStrip.top : parent.top
        anchors.left: parent.left; anchors.right: parent.right
        height: bar.screenBig ? Math.max(Theme.px(120), bar.y - (sharingStrip.visible ? sharingStrip.height : 0))
                              : Math.min(Theme.px(560), (bar.parent ? bar.parent.height : 800) * 0.6)
        color: "#000000"
        border.width: 1; border.color: Theme.line
        VideoOutput {
            id: screenOut
            anchors.fill: parent
            anchors.margins: 1
            fillMode: VideoOutput.PreserveAspectFit
            Component.onCompleted: Rooms.setScreenSink(screenOut.videoSink)
            Accessible.role: Accessible.Graphic
            Accessible.name: Rooms.sharerName + "'s screen"
            TapHandler { onDoubleTapped: bar.screenBig = !bar.screenBig }
        }
        HButton {
            id: hideScreen
            visible: bar.docked
            anchors.right: parent.right; anchors.top: parent.top; anchors.margins: Theme.px(10)
            text: "Hide screen"; fontSize: 13; implicitHeight: Theme.px(32)
            onClicked: bar.screenShown = false
        }
        HButton {
            id: bigScreen
            anchors.right: hideScreen.visible ? hideScreen.left : parent.right; anchors.top: parent.top
            anchors.rightMargin: Theme.px(hideScreen.visible ? 6 : 10); anchors.topMargin: Theme.px(10)
            text: bar.screenBig ? "Smaller (Esc)" : "Full screen"; fontSize: 13; implicitHeight: Theme.px(32)
            onClicked: bar.screenBig = !bar.screenBig
        }
        Text {
            textFormat: Text.PlainText
            anchors.left: parent.left; anchors.top: parent.top; anchors.margins: Theme.px(10)
            width: Math.max(0, Math.min(implicitWidth, bigScreen.x - Theme.px(20)))
            elide: Text.ElideRight
            text: Rooms.sharerName + " is sharing their screen"
            color: Theme.text
            style: Text.Outline; styleColor: "#000000"
            font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
        }
    }

    Row {
        id: who
        visible: !bar.docked
        anchors.left: parent.left; anchors.leftMargin: Theme.px(16)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(12)
        Icon { anchors.verticalCenter: parent.verticalCenter; name: "speaker"; size: Theme.px(22); color: Theme.accent }
        Column {
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(Theme.px(260), bar.width * 0.25)
            Text {
                textFormat: Text.PlainText
                width: parent.width
                elide: Text.ElideRight
                text: Rooms.title + (Rooms.groupName ? " · " + Rooms.groupName : "")
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width
                elide: Text.ElideRight
                text: Rooms.note !== "" ? Rooms.note : (Rooms.statusText + (Rooms.muted ? " · you're muted" : ""))
                color: Rooms.note !== "" ? Theme.danger : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
        }
    }

    // Everyone in the room, you first.
    Row {
        visible: !bar.docked
        anchors.left: who.right; anchors.leftMargin: Theme.px(16)
        anchors.right: buttons.left; anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
        clip: true
        Repeater {
            model: Rooms.people
            Item {
                width: Theme.px(40); height: width
                Accessible.role: Accessible.StaticText
                Accessible.name: modelData.name + (modelData.speaking ? ", speaking" : "") + (modelData.muted ? ", muted" : "")
                    + (modelData.state === "connecting" ? ", connecting" : modelData.state === "reconnecting" ? ", reconnecting" : "")
                Rectangle {
                    anchors.fill: parent
                    radius: width / 2
                    color: "transparent"
                    border.width: Theme.px(3)
                    border.color: Theme.accent
                    opacity: modelData.speaking ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: Theme.motion ? 120 : 0 } }
                }
                Avatar {
                    anchors.centerIn: parent
                    size: Theme.px(32)
                    key: modelData.me ? "me" : modelData.key || ""
                    name: modelData.name
                    opacity: modelData.state === "live" ? (modelData.muted ? 0.55 : 1) : 0.4
                }
                // A click or right click: Mute for me, Volume, and for admins Timeout and Ban (cap g2). A finger: a tap shows
                // their profile and a long press opens that menu, as in the voice panel.
                HoverHandler { cursorShape: modelData.me ? Qt.ArrowCursor : Qt.PointingHandCursor }
                TapHandler {
                    id: personTap
                    acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad | PointerDevice.Stylus
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onTapped: (point, button) => { if (!modelData.me) Ui.menuAt(personTap.parent, point.position.x, point.position.y,
                        () => PeopleActions.memberMenu({ key: modelData.key, name: modelData.name, contact: Chat.isContact(modelData.key) }, { group: Rooms.group })) }
                }
                TapHandler {
                    id: personTouch
                    acceptedDevices: PointerDevice.TouchScreen
                    onTapped: if (!modelData.me) Ui.showProfile(modelData.key, modelData.name, {})
                    onLongPressed: { if (!modelData.me) Ui.menuAt(personTouch.parent, point.position.x, point.position.y,
                        () => PeopleActions.memberMenu({ key: modelData.key, name: modelData.name, contact: Chat.isContact(modelData.key) }, { group: Rooms.group })) }
                }
                Rectangle {
                    visible: modelData.muted
                    anchors.right: parent.right; anchors.bottom: parent.bottom
                    width: Theme.px(16); height: width; radius: width / 2
                    color: Theme.dangerFill
                    border.width: 1; border.color: Theme.dangerLine
                    Icon { anchors.centerIn: parent; name: "mic"; size: Theme.px(11); color: Theme.text }
                }
            }
        }
    }

    Row {
        id: buttons
        visible: !bar.docked
        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
        HButton { text: Rooms.muted ? "Unmute mic" : "Mute mic"; kind: Rooms.muted ? "accent" : "normal"; implicitHeight: Theme.px(44); onClicked: Ui.toggleMic() }
        HButton { text: Rooms.deafened ? "Undeafen" : "Deafen"; kind: Rooms.deafened ? "accent" : "normal"; implicitHeight: Theme.px(44); onClicked: Ui.toggleDeaf() }
        HButton { visible: Rooms.canShare; text: Rooms.sharing ? "Stop sharing" : "Share screen"; kind: Rooms.sharing ? "accent" : "normal"; implicitHeight: Theme.px(44); onClicked: Rooms.toggleShare() }
        HButton { visible: Rooms.sharerName !== ""; text: bar.screenShown ? "Hide screen" : "Show screen"; implicitHeight: Theme.px(44); onClicked: bar.screenShown = !bar.screenShown }
        HButton { visible: bar.width > Theme.px(900); text: "Output: " + Rooms.outputName; implicitHeight: Theme.px(44); onClicked: Rooms.nextOutput() }
        HButton { text: "Open"; implicitHeight: Theme.px(44); onClicked: Ui.openPage("g:" + Rooms.group, { voice: true }) }
        HButton { text: "Leave"; kind: "danger"; implicitHeight: Theme.px(44); onClicked: Rooms.leave() }
    }
}

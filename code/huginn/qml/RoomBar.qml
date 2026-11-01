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
    // Someone new starts sharing: show it again.
    readonly property string sharer: Rooms.sharerName
    onSharerChanged: screenShown = true

    // Someone else's shared screen, above the bar.
    Rectangle {
        id: screenPanel
        visible: Rooms.inRoom && Rooms.sharerName !== "" && bar.screenShown
        anchors.bottom: parent.top
        anchors.left: parent.left; anchors.right: parent.right
        height: Math.min(Theme.px(560), (bar.parent ? bar.parent.height : 800) * 0.6)
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
        }
        HButton {
            visible: bar.docked
            anchors.right: parent.right; anchors.top: parent.top; anchors.margins: Theme.px(10)
            text: "Hide screen"; fontSize: 13; implicitHeight: Theme.px(32)
            onClicked: bar.screenShown = false
        }
        Text {
            textFormat: Text.PlainText
            anchors.left: parent.left; anchors.top: parent.top; anchors.margins: Theme.px(10)
            width: Math.min(implicitWidth, parent.width - Theme.px(20) - (bar.docked ? Theme.px(140) : 0))
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
        HButton { text: Rooms.muted ? "Unmute" : "Mute"; kind: Rooms.muted ? "accent" : "normal"; implicitHeight: Theme.px(44); onClicked: Ui.toggleMic() }
        HButton { text: Rooms.deafened ? "Undeafen" : "Deafen"; kind: Rooms.deafened ? "accent" : "normal"; implicitHeight: Theme.px(44); onClicked: Ui.toggleDeaf() }
        HButton { visible: Rooms.canShare; text: Rooms.sharing ? "Stop sharing" : "Share screen"; kind: Rooms.sharing ? "accent" : "normal"; implicitHeight: Theme.px(44); onClicked: Rooms.toggleShare() }
        HButton { visible: Rooms.sharerName !== ""; text: bar.screenShown ? "Hide screen" : "Show screen"; implicitHeight: Theme.px(44); onClicked: bar.screenShown = !bar.screenShown }
        HButton { visible: bar.width > Theme.px(900); text: "Output: " + Rooms.outputName; implicitHeight: Theme.px(44); onClicked: Rooms.nextOutput() }
        HButton { text: "Open"; implicitHeight: Theme.px(44); onClicked: Ui.openPage("g:" + Rooms.group, { voice: true }) }
        HButton { text: "Leave"; kind: "danger"; implicitHeight: Theme.px(44); onClicked: Rooms.leave() }
    }
}

import QtQuick
import Huginn

// Files going straight to or from one person over the internet (cap f1): what each is doing, with Stop
// while it runs and a close button once it's over. A big file from them (50 MB or more) waits with
// Accept and Decline. Empty (and no height) when there are none. The D-pad reaches Accept and Decline:
// Down past the last message (ChatPage), Left and Right between them, A to choose, Up or B to leave.
Column {
    id: strip
    property string peer: ""
    readonly property var items: peer === "" ? [] : FileLink.transfers.filter(t => t.peer === peer)
    property int focusIndex: -1       // the waiting file the controller is on (-1: none)
    property int focusButton: 0       // 0 Accept, 1 Decline
    visible: items.length > 0
    spacing: Theme.px(6)
    onPeerChanged: focusIndex = -1

    function isAsking(t) { return !!t && t.state === "asking" && !t.mine }
    // The first file waiting for Accept or Decline, or -1.
    function firstAsking() { return items.findIndex(t => isAsking(t)) }
    function enter() {
        const i = firstAsking()
        if (i < 0) return false
        focusIndex = i; focusButton = 0
        return true
    }
    // Up and B leave (false: the page takes Up to its messages).
    function handleButton(b) {
        if (!isAsking(items[focusIndex])) { focusIndex = -1; return false }
        const t = items[focusIndex]
        if (b === "left") { focusButton = 0; return true }
        if (b === "right") { focusButton = 1; return true }
        if (b === "down") {
            for (let i = focusIndex + 1; i < items.length; i++) if (isAsking(items[i])) { focusIndex = i; return true }
            return true
        }
        if (b === "a") { focusIndex = -1; if (focusButton === 0) FileLink.accept(t.id); else FileLink.decline(t.id); return true }
        if (b === "b") { focusIndex = -1; return true }
        if (b === "up") {
            for (let i = focusIndex - 1; i >= 0; i--) if (isAsking(items[i])) { focusIndex = i; return true }
            focusIndex = -1
            return false
        }
        return true
    }

    Repeater {
        model: strip.items
        delegate: Rectangle {
            readonly property bool running: modelData.state !== "done" && modelData.state !== "failed"
            readonly property bool asking: modelData.state === "asking" && !modelData.mine
            width: strip.width
            height: Theme.px(52)
            radius: Theme.radius
            color: Theme.panel
            border.color: modelData.state === "failed" ? Theme.dangerLine : Theme.line
            border.width: 1
            Accessible.role: Accessible.StaticText
            Accessible.name: modelData.name + ", " + PeopleActions.fileText(modelData)
            // How far it got, as a bar along the bottom edge.
            Rectangle {
                anchors.left: parent.left; anchors.bottom: parent.bottom
                height: Theme.px(3); radius: height / 2
                width: modelData.size > 0 ? parent.width * Math.min(1, modelData.done / modelData.size) : 0
                color: Theme.accent
                visible: parent.running
            }
            Icon {
                id: fileIcon
                anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                anchors.verticalCenter: parent.verticalCenter
                name: "file"; size: Theme.px(18)
                color: modelData.state === "failed" ? Theme.danger : Theme.accent
            }
            Column {
                anchors.left: fileIcon.right; anchors.leftMargin: Theme.px(10)
                anchors.right: take.visible ? take.left : stop.left; anchors.rightMargin: Theme.px(10)
                anchors.verticalCenter: parent.verticalCenter
                Text {
                    width: parent.width; elide: Text.ElideMiddle
                    text: (modelData.mine ? "To them: " : "From them: ") + modelData.name
                    color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                }
                Text {
                    width: parent.width; elide: Text.ElideRight
                    text: PeopleActions.fileText(modelData)
                    color: modelData.state === "failed" ? Theme.danger : Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
            }
            HButton {
                id: take
                visible: parent.asking
                anchors.right: stop.left; anchors.rightMargin: Theme.px(6)
                anchors.verticalCenter: parent.verticalCenter
                height: Theme.px(36); fontSize: 14
                text: "Accept"
                kind: "primary"
                focused: strip.focusIndex === index && strip.focusButton === 0
                onClicked: FileLink.accept(modelData.id)
            }
            HButton {
                id: stop
                anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
                anchors.verticalCenter: parent.verticalCenter
                height: Theme.px(36); fontSize: 14
                text: parent.asking ? "Decline" : parent.running ? "Stop" : "Close"
                kind: parent.running ? "danger" : "normal"
                focused: parent.asking && strip.focusIndex === index && strip.focusButton === 1
                onClicked: parent.asking ? FileLink.decline(modelData.id) : parent.running ? FileLink.cancel(modelData.id) : FileLink.dismiss(modelData.id)
            }
        }
    }
}

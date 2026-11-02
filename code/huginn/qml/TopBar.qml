import QtQuick
import Huginn

// The slim bar along the top, like Discord's: HUGINN, a search box in the middle (it
// opens Go to a chat, Ctrl+K) and the keyboard shortcuts (Ctrl+/) on the right.
Rectangle {
    id: bar
    signal search()
    signal shortcuts()
    signal transfers()
    // Files on their way or waiting for an answer here (Transfers' count).
    readonly property int transferCount: FileLink.activeCount + (LocalSend.busy ? 1 : 0)
    // Grows with Text size so the search box's words always fit.
    height: Math.max(Theme.px(36), box.height + Theme.px(12))
    color: Theme.rail
    // A right click anywhere on the bar (or a long press): its small menu where it was clicked.
    function menuAt(p) {
        Ui.menuAt(bar, p.x, p.y, () => Ui.pickFrom("", [{ header: "", items: [
            { name: "Go to a chat", icon: "search", detail: "Ctrl+K", url: "find" },
            { name: "Transfers", icon: "file", detail: bar.transferCount > 0 ? String(bar.transferCount) : "", url: "transfers" },
            { name: "Keyboard shortcuts", icon: "keyboard", detail: "Ctrl+/", url: "keys" },
            { name: "Settings", icon: "gear", detail: "Ctrl+,", url: "settings" }] }], a => {
                if (a === "find") bar.search()
                else if (a === "transfers") bar.transfers()
                else if (a === "keys") bar.shortcuts()
                else if (a === "settings") Ui.openPage("settings", {})
            }))
    }
    TapHandler { acceptedButtons: Qt.RightButton; onTapped: (point, button) => bar.menuAt(point.position) }
    TapHandler { acceptedDevices: PointerDevice.TouchScreen; onLongPressed: bar.menuAt(point.position) }

    Row {
        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(10)
        Text {
            textFormat: Text.PlainText
            anchors.verticalCenter: parent.verticalCenter
            text: "HUGINN"
            color: Theme.textMuted
            font.family: Theme.display; font.pixelSize: Theme.fs(12); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
        }
    }
    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(Theme.px(440), parent.width - Theme.px(320))
        visible: width > Theme.px(120)
        height: Math.max(Theme.px(24), boxText.implicitHeight + Theme.px(6))
        radius: Theme.radius
        color: boxHover.hovered ? Theme.tile : Theme.raised
        Accessible.role: Accessible.Button
        Accessible.name: "Go to a chat (Ctrl+K)"
        Accessible.onPressAction: bar.search()
        Icon { id: lens; anchors.left: parent.left; anchors.leftMargin: Theme.px(8); anchors.verticalCenter: parent.verticalCenter; name: "search"; size: Theme.px(14); color: Theme.textMuted }
        Text {
            textFormat: Text.PlainText
            id: boxText
            anchors.left: lens.right; anchors.leftMargin: Theme.px(6)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideRight
            text: "Go to a chat"
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        HoverHandler { id: boxHover; cursorShape: Qt.IBeamCursor }
        TapHandler { onTapped: bar.search() }
    }
    // Transfers: every file coming or going, with a count while any are.
    Rectangle {
        id: transfersButton
        anchors.right: keysLabel.visible ? keysLabel.left : parent.right
        anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        height: box.height
        width: transfersRow.implicitWidth + Theme.px(14)
        radius: Theme.radius
        color: transfersHover.hovered ? Theme.tile : "transparent"
        Accessible.role: Accessible.Button
        Accessible.name: bar.transferCount > 0 ? "Transfers, " + bar.transferCount + " going" : "Transfers"
        Accessible.onPressAction: bar.transfers()
        Row {
            id: transfersRow
            anchors.centerIn: parent
            spacing: Theme.px(6)
            Icon { anchors.verticalCenter: parent.verticalCenter; name: "down"; size: Theme.px(14); color: bar.transferCount > 0 ? Theme.accent : Theme.textMuted }
            Text {
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                text: "Transfers"
                color: transfersHover.hovered ? Theme.text : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                visible: bar.transferCount > 0
                width: Math.max(height, countText.implicitWidth + Theme.px(8)); height: countText.implicitHeight + Theme.px(2)
                radius: Theme.radius
                color: Theme.accent
                Text {
                    id: countText
                    textFormat: Text.PlainText
                    anchors.centerIn: parent
                    text: bar.transferCount > 99 ? "99+" : String(bar.transferCount)
                    color: Theme.onAccent
                    font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold
                }
            }
        }
        HoverHandler { id: transfersHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: bar.transfers() }
    }
    Text {
        id: keysLabel
        textFormat: Text.PlainText
        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        visible: parent.width > Theme.px(520)
        text: "Ctrl+/ shortcuts"
        color: keysHover.hovered ? Theme.text : Theme.textMuted
        font.family: Theme.body; font.pixelSize: Theme.fs(12)
        Accessible.role: Accessible.Button
        Accessible.name: "Keyboard shortcuts"
        HoverHandler { id: keysHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: bar.shortcuts() }
    }
}

import QtQuick
import Huginn

// The slim bar along the top, like Discord's: the raven and HUGINN, a search box in the middle (it
// opens Find or start a chat, Ctrl+K) and the keyboard shortcuts (Ctrl+/) on the right.
Rectangle {
    id: bar
    signal search()
    signal shortcuts()
    // Grows with Text size so the search box's words always fit.
    height: Math.max(Theme.px(36), box.height + Theme.px(12))
    color: Theme.rail

    Row {
        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(10)
        Image {
            anchors.verticalCenter: parent.verticalCenter
            source: "assets/raven-gold.png"
            width: Theme.px(18); height: width
            sourceSize.width: 64; sourceSize.height: 64
            smooth: true; mipmap: true
        }
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
        Accessible.name: "Find or start a chat (Ctrl+K)"
        Accessible.onPressAction: bar.search()
        Icon { id: lens; anchors.left: parent.left; anchors.leftMargin: Theme.px(8); anchors.verticalCenter: parent.verticalCenter; name: "search"; size: Theme.px(14); color: Theme.textMuted }
        Text {
            textFormat: Text.PlainText
            id: boxText
            anchors.left: lens.right; anchors.leftMargin: Theme.px(6)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideRight
            text: "Find or start a chat"
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        HoverHandler { id: boxHover; cursorShape: Qt.IBeamCursor }
        TapHandler { onTapped: bar.search() }
    }
    Text {
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

import QtQuick
import Huginn

// A column header, 48 px like Discord's: the section or conversation name (carved capitals for
// sections), an optional plain-language subtitle after a thin divider, room for things on the
// right, and a thin seam underneath. In a portrait window it can show a back arrow to the list.
Item {
    id: header
    property string title
    property string subtitle
    property string glyph: ""        // "#" for a channel
    property bool carved: true        // Cinzel for section names; conversations use the body font
    property bool backShown: false
    property bool warn: false         // the subtitle is a warning (a changed security key)
    signal back()
    default property alias trailing: right.data

    // Taller only when a large Text size needs it.
    implicitHeight: Math.max(Theme.headerHeight, titleText.implicitHeight + Theme.px(16), right.implicitHeight + Theme.px(8))

    Rectangle {
        id: backBtn
        visible: header.backShown
        anchors.left: parent.left; anchors.leftMargin: Theme.px(8)
        anchors.verticalCenter: parent.verticalCenter
        width: visible ? Theme.px(32) : 0; height: Theme.px(32)
        radius: Theme.radius
        color: backHover.hovered ? Theme.raised : "transparent"
        Accessible.role: Accessible.Button
        Accessible.name: "Back"
        Accessible.onPressAction: header.back()
        Icon { anchors.centerIn: parent; name: "back"; size: Theme.px(20); color: Theme.textSoft }
        HoverHandler { id: backHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: header.back() }
    }

    Row {
        anchors.left: backBtn.visible ? backBtn.right : parent.left
        anchors.leftMargin: Theme.px(backBtn.visible ? 6 : 16)
        anchors.right: right.left
        anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
        clip: true
        Text {
            id: glyphText
            visible: header.glyph !== ""
            anchors.verticalCenter: parent.verticalCenter
            text: header.glyph
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(20)
        }
        Text {
            id: titleText
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(implicitWidth, parent.width - (glyphText.visible ? glyphText.width + parent.spacing : 0))
            elide: Text.ElideRight
            text: header.carved ? header.title.toUpperCase() : header.title
            color: Theme.text
            font.family: header.carved ? Theme.display : Theme.body
            font.pixelSize: Theme.fs(15)
            font.weight: header.carved ? Font.Bold : Font.DemiBold
            font.letterSpacing: header.carved ? Theme.px(1.5) : 0
        }
        Rectangle {
            visible: header.subtitle.length > 0
            anchors.verticalCenter: parent.verticalCenter
            width: 1; height: Theme.px(20)
            color: Theme.line
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, parent.width - titleText.width - (glyphText.visible ? glyphText.width : 0) - Theme.px(30))
            elide: Text.ElideRight
            visible: header.subtitle.length > 0
            text: header.subtitle
            color: header.warn ? Theme.danger : Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(13)
        }
    }
    Row {
        id: right
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
    }
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.seam }
}

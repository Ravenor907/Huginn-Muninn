import QtQuick
import Huginn

// One controller prompt: a button glyph in the face button's own color, and what it does.
// Tapping it does the same. High contrast: plain outlined glyphs instead of colors.
Item {
    id: root
    property string key
    property string label
    signal activated()

    readonly property var tone: {
        if (Theme.hc) return { bg: "transparent", fg: Theme.text }
        switch (key) {
        case "a": return { bg: "#2F3D30", fg: "#B6D6B6" }
        case "b": return { bg: "#3A2724", fg: "#E6A596" }
        case "x": return { bg: "#23304A", fg: "#B9C6E0" }
        case "y": return { bg: "#3D3622", fg: "#E0C27F" }
        }
        return { bg: "#23272C", fg: Theme.textSoft }
    }

    implicitWidth: row.implicitWidth
    implicitHeight: Theme.px(40)

    Row {
        id: row
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            height: Theme.px(27)
            width: Math.max(height, glyph.implicitWidth + Theme.px(14))
            radius: height / 2
            color: root.tone.bg
            border.color: Theme.textMuted
            border.width: Theme.hc ? 1 : 0
            Text {
                textFormat: Text.PlainText
                id: glyph
                anchors.centerIn: parent
                // A held pair, like "lb+up": LB ▲. Read with Gamepad.style, so it follows the pad in hand.
                text: root.key.split("+").map(k => k === "up" ? "▲" : k === "down" ? "▼" : /^p[1-4]$/.test(k) ? k.toUpperCase() : (Gamepad.style, Gamepad.glyph(k))).join(" ")
                color: root.tone.fg
                font.family: Theme.body
                font.pixelSize: Theme.fs(13)
                font.weight: Font.DemiBold
            }
        }
        Text {
            textFormat: Text.PlainText
            anchors.verticalCenter: parent.verticalCenter
            text: root.label
            color: Theme.textSoft
            font.family: Theme.body
            font.pixelSize: Theme.fs(15)
        }
    }
    TapHandler { onTapped: root.activated() }
    Accessible.role: Accessible.Button
    Accessible.name: root.label
    Accessible.onPressAction: root.activated()
}

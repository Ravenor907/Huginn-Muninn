import QtQuick
import Huginn

// A flat, squared button. kind: "normal", "accent", "primary" (filled with the accent, for a dialog's
// main answer), "plain" (no box) or "danger". `focused` draws the controller focus.
Rectangle {
    id: btn
    property string text
    property string kind: "normal"
    property bool focused: false
    property bool enabled: true
    property int fontSize: 14
    property color accent: Theme.accent   // a chat's own color on its page
    signal clicked()

    implicitWidth: label.implicitWidth + Theme.px(32)
    implicitHeight: Math.max(Theme.tapMin, label.implicitHeight + Theme.px(12))
    radius: Theme.radius
    opacity: enabled ? 1 : 0.45
    color: kind === "primary" ? (hover.hovered ? Qt.lighter(btn.accent, 1.12) : btn.accent)
         : kind === "plain" ? (hover.hovered ? Theme.raised : "transparent")
         : kind === "accent" ? Theme.accentFill : kind === "danger" ? Theme.dangerFill : hover.hovered ? Theme.tile : Theme.raised
    border.width: focused ? Theme.focusWidth : kind === "primary" || kind === "plain" ? 0 : 1
    Accessible.role: Accessible.Button
    Accessible.name: text
    Accessible.onPressAction: clicked()
    border.color: focused ? Theme.text
                : kind === "accent" ? btn.accent
                : kind === "danger" ? Theme.dangerLine : Theme.lineMuted

    Text {
        textFormat: Text.PlainText
        id: label
        anchors.centerIn: parent
        // Never wider than the button: a squeezed button cuts its words short instead of spilling.
        width: Math.min(implicitWidth, Math.max(0, btn.width - Theme.px(12)))
        elide: Text.ElideRight
        text: btn.text
        color: btn.kind === "primary" ? Theme.onAccent : btn.kind === "accent" ? btn.accent : btn.kind === "danger" ? Theme.danger : Theme.text
        font.family: Theme.body
        font.pixelSize: Theme.fs(btn.fontSize)
        font.weight: Font.DemiBold
    }
    HoverHandler { id: hover; cursorShape: btn.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor }
    TapHandler { enabled: btn.enabled; onTapped: btn.clicked() }
}

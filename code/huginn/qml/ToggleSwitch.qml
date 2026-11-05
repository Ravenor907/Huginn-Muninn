import QtQuick
import Huginn

Rectangle {
    id: sw
    property bool checked: false
    signal toggled()

    implicitWidth: Theme.px(40)
    implicitHeight: Theme.px(22)
    radius: Theme.radius
    color: checked ? Theme.accentFill : Theme.raised
    border.color: checked ? Theme.accent : Theme.lineMuted
    border.width: 1

    Rectangle {
        width: parent.height - Theme.px(6)
        height: width
        radius: 2
        y: Theme.px(3)
        x: sw.checked ? parent.width - width - Theme.px(3) : Theme.px(3)
        color: sw.checked ? Theme.accent : Theme.textFaint
        Behavior on x { NumberAnimation { duration: Theme.motion ? 120 : 0 } }
    }
    HoverHandler { cursorShape: Qt.PointingHandCursor }
    TapHandler { enabled: !Theme.touch; onTapped: sw.toggled() }
    // Handheld mode: a finger-sized area around the switch takes the tap too.
    Item {
        anchors.centerIn: parent
        width: Math.max(parent.width, Theme.tapMin)
        height: Math.max(parent.height, Theme.tapMin)
        enabled: Theme.touch
        TapHandler { onTapped: sw.toggled() }
    }
    Accessible.role: Accessible.CheckBox
    Accessible.checked: checked
    Accessible.onToggleAction: toggled()
}

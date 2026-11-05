import QtQuick
import Huginn

// The three dots on every item: its one menu, the same one a right click, LT or X opens.
// A MouseArea, so a click here never reaches the row underneath.
Rectangle {
    id: btn
    property string label: "More"
    property bool lit: false
    signal clicked()

    implicitWidth: Theme.px(28)
    implicitHeight: Theme.px(28)
    radius: Theme.radius
    color: area.containsMouse || lit ? Theme.raised : "transparent"
    border.width: lit ? 1 : 0
    border.color: Theme.lineMuted
    Accessible.role: Accessible.Button
    Accessible.name: label
    Accessible.onPressAction: Ui.menuAt(btn, 0, btn.height, () => btn.clicked())

    Icon {
        anchors.centerIn: parent
        name: "more"
        size: Theme.px(18)
        lineWidth: 2.4
        color: area.containsMouse || btn.lit ? Theme.text : Theme.textMuted
    }
    MouseArea {
        id: area
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        // The menu opens as the small right-click menu, under the dots (above them near the bottom of the window).
        onClicked: Ui.menuAt(btn, 0, btn.height, () => btn.clicked())
    }
}

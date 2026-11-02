import QtQuick
import Huginn

// A text box's right-click menu: Cut, Copy, Paste and Select all, the same small menu as everywhere else (RightClickMenu).
// Put one inside a TextInput, TextEdit, TextField or TextArea. It takes only the right button and a long press on a
// touch screen, so typing, selecting and scrolling work as before. (The Menu key and Shift+F10 open it from Main.)
MouseArea {
    id: area
    anchors.fill: parent
    acceptedButtons: Qt.RightButton
    cursorShape: Qt.IBeamCursor
    onClicked: mouse => Ui.fieldMenu(area.parent, mouse.x, mouse.y)
    TapHandler {
        acceptedDevices: PointerDevice.TouchScreen
        onLongPressed: Ui.fieldMenu(area.parent, point.position.x, point.position.y)
    }
}

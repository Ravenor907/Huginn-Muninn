import QtQuick
import Huginn

// An album's photos full size, one at a time: the arrows (on screen, the keyboard or the D-pad) go between
// them, B, Esc or a click on the picture closes it.
Rectangle {
    id: viewer
    property var photos: []           // stored photo ids, in the album's order
    property int at: 0
    property bool tour: false
    signal closed()
    color: "black"
    visible: photos.length > 0

    function step(by) { if (photos.length) at = (at + by + photos.length) % photos.length }
    function handleButton(b) {
        switch (b) {
        case "left": step(-1); return true
        case "right": step(1); return true
        case "a": case "b": closed(); return true
        }
        return true
    }

    Image {
        anchors.fill: parent; anchors.margins: Theme.px(10)
        fillMode: Image.PreserveAspectFit
        asynchronous: true
        source: viewer.visible && !viewer.tour && viewer.photos[viewer.at] ? Chat.photoUrl(viewer.photos[viewer.at]) : ""
        TapHandler { onTapped: viewer.closed() }
    }
    Text {
        textFormat: Text.PlainText
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top; anchors.topMargin: Theme.px(14)
        text: (viewer.at + 1) + " of " + viewer.photos.length + "   ·   ← → to move · B or Esc to close"
        color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        style: Text.Outline; styleColor: "black"
    }
    Repeater {
        model: viewer.photos.length > 1 ? [-1, 1] : []
        delegate: Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            x: modelData < 0 ? Theme.px(18) : viewer.width - width - Theme.px(18)
            width: Theme.px(56); height: width; radius: width / 2
            color: arrowArea.containsMouse ? Qt.rgba(1, 1, 1, 0.28) : Qt.rgba(1, 1, 1, 0.14)
            Icon { anchors.centerIn: parent; name: modelData < 0 ? "back" : "forward"; color: "white"; size: Theme.px(28) }
            Accessible.role: Accessible.Button
            Accessible.name: modelData < 0 ? "Previous photo" : "Next photo"
            // A MouseArea, so the click stays here and doesn't also close the picture under it.
            MouseArea { id: arrowArea; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: viewer.step(modelData) }
        }
    }
}

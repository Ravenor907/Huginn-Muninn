import QtQuick
import Huginn

// An album's photos full size, one at a time: the arrows (on screen, the keyboard or the D-pad) go between
// them, Save to Pictures (or X) keeps a copy in Pictures › Huginn, and B, Esc or a click on the picture closes it.
Rectangle {
    id: viewer
    property var photos: []           // stored photo ids, in the album's order
    property int at: 0
    property bool tour: false
    signal closed()
    color: "black"
    visible: photos.length > 0

    function step(by) { if (photos.length) at = (at + by + photos.length) % photos.length }
    function save() {
        if (tour || !photos[at]) return
        const ok = Chat.savePhoto(photos[at])
        Ui.say(ok ? "Saved in Pictures › Huginn." : "Couldn't save it.", !ok)
    }
    function handleButton(b) {
        switch (b) {
        case "left": step(-1); return true
        case "right": step(1); return true
        case "x": save(); return true
        case "a": case "b": closed(); return true
        }
        return true
    }

    // Cap a1: a GIF moves here too (held still with Less motion or Battery saver until played); "" for a photo.
    readonly property string gif: visible && !tour && photos[at] ? Chat.gifUrl(photos[at]) : ""
    property bool asked: false
    onAtChanged: asked = false
    Image {
        anchors.fill: parent; anchors.margins: Theme.px(10)
        fillMode: Image.PreserveAspectFit
        asynchronous: true
        visible: viewer.gif === ""
        source: viewer.visible && !viewer.tour && viewer.gif === "" && viewer.photos[viewer.at] ? Chat.photoUrl(viewer.photos[viewer.at]) : ""
        TapHandler { onTapped: viewer.closed() }
    }
    AnimatedImage {
        anchors.fill: parent; anchors.margins: Theme.px(10)
        fillMode: Image.PreserveAspectFit
        cache: false
        visible: viewer.gif !== ""
        source: viewer.gif
        playing: visible && (Theme.motion || viewer.asked) && Qt.application.state === Qt.ApplicationActive
        TapHandler { onTapped: viewer.closed() }
    }
    Rectangle {
        visible: viewer.gif !== "" && !Theme.motion
        anchors.centerIn: parent
        width: Theme.px(64); height: width; radius: width / 2
        color: Qt.rgba(0, 0, 0, 0.55)
        Icon { anchors.centerIn: parent; name: viewer.asked ? "stop" : "play"; color: "white"; size: Theme.px(30) }
        Accessible.role: Accessible.Button
        Accessible.name: viewer.asked ? "Stop the GIF" : "Play the GIF"
        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: viewer.asked = !viewer.asked }
    }
    Text {
        textFormat: Text.PlainText
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top; anchors.topMargin: Theme.px(14)
        text: (viewer.photos.length > 1 ? (viewer.at + 1) + " of " + viewer.photos.length + "   ·   ← → to move · " : "") + "B or Esc to close"
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
    // Save to Pictures: a MouseArea, so the click stays here and doesn't also close the picture under it.
    Rectangle {
        visible: !viewer.tour
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(18)
        width: saveWord.implicitWidth + Theme.px(32); height: Math.max(Theme.tapMin, saveWord.implicitHeight + Theme.px(12))
        radius: Theme.radius
        color: saveArea.containsMouse ? Qt.rgba(1, 1, 1, 0.28) : Qt.rgba(1, 1, 1, 0.14)
        Accessible.role: Accessible.Button
        Accessible.name: "Save to Pictures"
        Accessible.onPressAction: viewer.save()
        Text {
            id: saveWord
            textFormat: Text.PlainText
            anchors.centerIn: parent
            text: (Ui.main && Ui.main.padInUse ? "X: " : "") + "Save to Pictures"
            color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
        }
        MouseArea { id: saveArea; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: viewer.save() }
    }
}

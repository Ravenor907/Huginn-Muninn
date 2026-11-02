import QtQuick
import Huginn

// The button for a hard-to-undo action, the same as Muninn's HoldToConfirm: it fills over 5 seconds while it's held
// (the mouse button or a touch on it, Enter or Space while it has the focus, or a controller's A through press() and
// release() from the dialog it sits in), then confirmed() runs. Letting go early does nothing and says "Keep holding
// for 5 seconds" under it. label: "Hold to block"; spoken: what a screen reader says ("Hold for 5 seconds to block
// Mom"); prefix: the controller's word in front of the label ("A: "); danger: red, otherwise the accent.
Column {
    id: hold
    property string label: "Hold to confirm"
    property string spoken: ""
    property string prefix: ""
    property bool danger: true
    property bool focused: false
    property int fontSize: 14
    readonly property int duration: 5000
    property real progress: 0
    property bool down: false
    property bool early: false
    property double startedAt: 0
    signal confirmed()

    spacing: Theme.px(4)

    // Pressed: a second press while it's already held (a key's repeats) changes nothing.
    function press() {
        if (down) return
        down = true
        early = false
        progress = 0
        startedAt = Date.now()
        ticker.start()
    }
    // Let go: before the 5 seconds were up, nothing happens and the note says to keep holding.
    function release() {
        if (!down) return
        down = false
        ticker.stop()
        if (Date.now() - startedAt >= duration) { progress = 1; hold.confirmed(); return }
        progress = 0
        early = true
    }
    // The hold was lost without a release (the key's release went to another window, or the focus moved away): it
    // stops as a too-early release does, never confirming.
    property bool byKey: false
    function letGo() {
        if (!down) return
        down = false
        byKey = false
        ticker.stop()
        progress = 0
        early = true
    }
    readonly property bool appActive: Qt.application.state === Qt.ApplicationActive
    onAppActiveChanged: if (!appActive) letGo()
    // Back to how it started (its dialog opened again, or closed).
    function reset() { down = false; byKey = false; ticker.stop(); progress = 0; early = false }

    Timer {
        id: ticker
        interval: 30
        repeat: true
        onTriggered: {
            hold.progress = Math.min(1, (Date.now() - hold.startedAt) / hold.duration)
            if (hold.progress < 1) return
            stop()
            hold.down = false
            hold.byKey = false
            hold.confirmed()
        }
    }

    Rectangle {
        id: face
        anchors.right: parent.right
        // As wide as the note too, so nothing moves when it shows.
        width: Math.max(word.implicitWidth, note.implicitWidth) + Theme.px(32)
        height: Math.max(Theme.tapMin, word.implicitHeight + Theme.px(12))
        radius: Theme.radius
        color: hold.danger ? Theme.dangerFill : Theme.accentFill
        border.width: hold.focused || face.activeFocus ? Theme.focusWidth : 1
        border.color: hold.focused || face.activeFocus ? Theme.text : hold.danger ? Theme.dangerLine : Theme.accent
        clip: true
        activeFocusOnTab: true
        onActiveFocusChanged: if (!activeFocus && hold.byKey) hold.letGo()
        Accessible.role: Accessible.Button
        Accessible.name: hold.spoken !== "" ? hold.spoken : hold.label
        Accessible.description: hold.early ? "Keep holding for 5 seconds" : ""
        // The fill grows across the whole button while it's held.
        Rectangle {
            width: parent.width * hold.progress
            height: parent.height
            color: hold.danger ? Theme.danger : Theme.accent
            opacity: 0.45
        }
        Text {
            id: word
            textFormat: Text.PlainText
            anchors.centerIn: parent
            width: Math.min(implicitWidth, Math.max(0, face.width - Theme.px(12)))
            elide: Text.ElideRight
            text: hold.prefix + hold.label
            color: hold.danger ? Theme.danger : Theme.accent
            font.family: Theme.body
            font.pixelSize: Theme.fs(hold.fontSize)
            font.weight: Font.DemiBold
        }
        HoverHandler { cursorShape: Qt.PointingHandCursor }
        TapHandler {
            gesturePolicy: TapHandler.WithinBounds
            onPressedChanged: pressed ? hold.press() : hold.release()
        }
        Keys.onPressed: event => {
            if (event.key !== Qt.Key_Return && event.key !== Qt.Key_Enter && event.key !== Qt.Key_Space) return
            if (!event.isAutoRepeat && !hold.down) { hold.byKey = true; hold.press() }
            event.accepted = true
        }
        Keys.onReleased: event => {
            if (event.key !== Qt.Key_Return && event.key !== Qt.Key_Enter && event.key !== Qt.Key_Space) return
            if (!event.isAutoRepeat) { hold.byKey = false; hold.release() }
            event.accepted = true
        }
    }
    Text {
        id: note
        textFormat: Text.PlainText
        anchors.right: parent.right
        visible: hold.early
        text: "Keep holding for 5 seconds"
        color: Theme.textSoft
        font.family: Theme.body
        font.pixelSize: Theme.fs(12)
    }
}

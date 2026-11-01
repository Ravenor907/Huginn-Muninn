import QtQuick
import Huginn

// Asks before something that can't be undone: the title on its own line, the message wrapping under
// it, and Cancel and the answer at the foot, sized to the content. With `hold` set the answer has to
// be held for 2 seconds (the mouse button, Enter or A), so a stray click can't erase anything.
Item {
    id: dlg
    property string title
    property string message
    property string confirmText: "Continue"
    property bool hold: false
    property bool danger: false
    property var callback: null
    property var cancelCallback: null
    property string tag: ""
    property string cancelText: "Cancel"
    // Only the answer button (a note to read, like a copied code): B closes it too.
    property bool single: false
    property real held: 0

    visible: false
    anchors.fill: parent

    function open(t, msg, confirm, cb, needsHold, isDanger, onCancel, openTag) {
        title = t; message = msg; confirmText = confirm || "Continue"
        callback = cb || null; hold = !!needsHold; danger = !!isDanger
        cancelCallback = onCancel || null; tag = openTag || ""; cancelText = "Cancel"; single = false
        held = 0; visible = true
    }
    function confirm() {
        cancelCallback = null
        visible = false
        holdTimer.stop()
        if (callback) callback()
    }
    function cancel() {
        visible = false; holdTimer.stop(); held = 0
        const c = cancelCallback
        cancelCallback = null
        if (c) c()
    }
    // Closes without running either callback (the question went away on its own).
    function dismiss() { visible = false; holdTimer.stop(); held = 0; cancelCallback = null }

    Timer {
        id: holdTimer
        interval: 40; repeat: true
        onTriggered: {
            dlg.held = Math.min(1, dlg.held + interval / 2000)
            if (dlg.held >= 1) dlg.confirm()
        }
    }
    function startHold() { if (hold) { held = 0; holdTimer.start() } else confirm() }
    function stopHold() { if (hold && held < 1) { holdTimer.stop(); held = 0 } }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(440))
        // Never taller than the window: a long message scrolls above the buttons.
        height: Math.min(parent.height - Theme.px(32), col.implicitHeight + Theme.px(40) + foot.height)
        radius: Theme.radius
        color: Theme.panel
        border.color: dlg.danger ? Theme.dangerLine : Theme.line
        clip: true
        Flickable {
            id: scroller
            x: Theme.px(20); y: Theme.px(20)
            width: parent.width - Theme.px(40)
            height: Math.max(0, parent.height - foot.height - Theme.px(40))
            contentWidth: width
            contentHeight: col.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: col
                width: scroller.width
                spacing: Theme.px(8)
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: dlg.title.toUpperCase()
                    color: dlg.danger ? Theme.danger : Theme.text
                    font.family: Theme.display
                    font.pixelSize: Theme.fs(18)
                    font.weight: Font.Bold
                    font.letterSpacing: Theme.px(2)
                }
                // Wraps at spaces, and inside a long code that has none.
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: dlg.message
                    color: Theme.textMuted
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(14)
                    lineHeight: 1.15
                }
            }
        }
        Rectangle {
            visible: scroller.interactive
            anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
            y: scroller.y + scroller.visibleArea.yPosition * scroller.height
            width: Theme.px(3); height: Math.max(Theme.px(16), scroller.visibleArea.heightRatio * scroller.height)
            radius: width / 2
            color: Theme.lineMuted
        }
        Rectangle {
            id: foot
            anchors.bottom: parent.bottom
            width: parent.width
            height: Theme.px(64)
            color: Theme.panelLow
            Rectangle { width: parent.width; height: 1; color: Theme.line }
            Row {
                anchors.right: parent.right; anchors.rightMargin: Theme.px(20)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(8)
                HButton { visible: !dlg.single; text: dlg.cancelText; kind: "plain"; onClicked: dlg.cancel() }
                HButton {
                    id: go
                    text: dlg.hold ? (dlg.held > 0 ? "Keep holding…" : (Ui.main && Ui.main.padInUse ? "Hold A: " : "") + dlg.confirmText) : dlg.confirmText
                    kind: dlg.danger ? "danger" : "primary"
                    TapHandler {
                        onPressedChanged: pressed ? dlg.startHold() : dlg.stopHold()
                    }
                    Rectangle {
                        visible: dlg.hold
                        anchors.left: parent.left
                        anchors.bottom: parent.bottom
                        height: Math.max(3, Theme.px(3))
                        width: parent.width * dlg.held
                        color: dlg.danger ? Theme.danger : Theme.ground
                    }
                }
            }
        }
    }

    function handleButton(b) {
        if (b === "a") startHold()
        else if (b === "b") cancel()
        else if ((b === "up" || b === "down") && scroller.interactive)
            scroller.contentY = Math.max(0, Math.min(scroller.contentHeight - scroller.height, scroller.contentY + (b === "up" ? -1 : 1) * Theme.px(60)))
        return true
    }
    function handleRelease(b) { if (b === "a") stopHold() }
}

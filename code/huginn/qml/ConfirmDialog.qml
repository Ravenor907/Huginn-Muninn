import QtQuick
import Huginn

// Asks before something that can't be undone: the title on its own line, the message wrapping under
// it, and Cancel and the answer at the foot, sized to the content. With `hold` set (every hard-to-undo action) the
// answer is a HoldToConfirm: held for 5 seconds (the mouse button, Enter, Space or A), so a stray click can't erase
// anything. Its label says Hold ("Block" shows as "Hold to block"); a screen reader hears the title with it ("Hold
// for 5 seconds to block Mom").
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
    // A third answer between Cancel and the main one (X on a controller), set after open().
    property string otherText: ""
    property var otherCallback: null
    property bool otherDanger: false   // red, for one that loses something (Close anyway while a file is moving)
    // A fourth answer, next to the main one (Y on a controller, when there's no "Don't ask again"), set after open().
    property string extraText: ""
    property var extraCallback: null
    // Only the answer button (a note to read, like a copied code): B closes it too.
    property bool single: false
    // A "Don't ask again" switch under the message (Y on a controller), set after open(); remember is what it says.
    property string rememberText: ""
    property bool remember: false
    // What the hold button says and reads out.
    readonly property string holdLabel: confirmText.toLowerCase().indexOf("hold ") === 0 ? confirmText : "Hold to " + lowerFirst(confirmText)
    // The title when it names the same action with whom or what ("Block Mom?" for Block), else the label's own words.
    readonly property string holdWhat: holdLabel.toLowerCase().indexOf("hold to ") === 0 ? holdLabel.slice(8) : holdLabel
    readonly property bool titleSays: title.slice(-1) === "?" && title.split(" ")[0].toLowerCase() === holdWhat.split(" ")[0].toLowerCase()
    readonly property string holdSpoken: "Hold for 5 seconds to " + lowerFirst(titleSays ? title.slice(0, -1) : holdWhat)
    function lowerFirst(t) { return t.length ? t.charAt(0).toLowerCase() + t.slice(1) : t }

    visible: false
    anchors.fill: parent

    function open(t, msg, confirm, cb, needsHold, isDanger, onCancel, openTag) {
        title = t; message = msg; confirmText = confirm || "Continue"
        callback = cb || null; hold = !!needsHold; danger = !!isDanger
        cancelCallback = onCancel || null; tag = openTag || ""; cancelText = "Cancel"; single = false
        otherText = ""; otherCallback = null; otherDanger = false
        extraText = ""; extraCallback = null
        rememberText = ""; remember = false
        holdButton.reset(); visible = true
    }
    function confirm() {
        cancelCallback = null
        visible = false
        holdButton.reset()
        if (callback) callback()
    }
    function other() {
        const o = otherCallback
        cancelCallback = null; otherCallback = null
        visible = false; holdButton.reset()
        if (o) o()
    }
    function extra() {
        const e = extraCallback
        cancelCallback = null; extraCallback = null
        visible = false; holdButton.reset()
        if (e) e()
    }
    function cancel() {
        visible = false; holdButton.reset()
        const c = cancelCallback
        cancelCallback = null
        if (c) c()
    }
    // Closes without running either callback (the question went away on its own).
    function dismiss() { visible = false; holdButton.reset(); cancelCallback = null }

    function startHold() { if (hold) holdButton.press(); else confirm() }
    function stopHold() { if (hold) holdButton.release() }

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
                Row {
                    visible: dlg.rememberText !== ""
                    spacing: Theme.px(10)
                    topPadding: Theme.px(6)
                    ToggleSwitch {
                        anchors.verticalCenter: parent.verticalCenter
                        checked: dlg.remember
                        Accessible.name: dlg.rememberText
                        onToggled: dlg.remember = !dlg.remember
                    }
                    Text {
                        textFormat: Text.PlainText
                        anchors.verticalCenter: parent.verticalCenter
                        text: (Ui.main && Ui.main.padInUse && dlg.extraText === "" ? "Y: " : "") + dlg.rememberText
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: Theme.fs(14)
                        TapHandler { onTapped: dlg.remember = !dlg.remember }
                    }
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
            height: Math.max(Theme.px(64), buttons.implicitHeight + Theme.px(24))
            color: Theme.panelLow
            Rectangle { width: parent.width; height: 1; color: Theme.line }
            // Right to left: the main answer at the right, then the others; a second line when four don't fit.
            Flow {
                id: buttons
                x: Theme.px(20)
                width: parent.width - Theme.px(40)
                anchors.verticalCenter: parent.verticalCenter
                layoutDirection: Qt.RightToLeft
                spacing: Theme.px(8)
                HButton {
                    id: go
                    visible: !dlg.hold
                    text: (Ui.main && Ui.main.padInUse ? "A: " : "") + dlg.confirmText
                    kind: dlg.danger ? "danger" : "primary"
                    onClicked: dlg.confirm()
                }
                HoldToConfirm {
                    id: holdButton
                    visible: dlg.hold
                    label: dlg.holdLabel
                    spoken: dlg.holdSpoken
                    prefix: Ui.main && Ui.main.padInUse ? "A: " : ""
                    danger: dlg.danger
                    onConfirmed: dlg.confirm()
                }
                HButton {
                    visible: !dlg.single && dlg.extraText !== ""
                    text: (Ui.main && Ui.main.padInUse ? "Y: " : "") + dlg.extraText
                    onClicked: dlg.extra()
                }
                HButton {
                    visible: !dlg.single && dlg.otherText !== ""
                    text: (Ui.main && Ui.main.padInUse ? "X: " : "") + dlg.otherText
                    kind: dlg.otherDanger ? "danger" : "normal"
                    onClicked: dlg.other()
                }
                HButton { visible: !dlg.single; text: dlg.cancelText; kind: "plain"; onClicked: dlg.cancel() }
            }
        }
    }

    function handleButton(b) {
        if (b === "a") startHold()
        else if (b === "b") cancel()
        // Space on the keyboard holds too (it comes as X; a controller's X stays the third answer).
        else if (b === "x" && hold && otherText === "" && !(Ui.main && Ui.main.padInUse)) startHold()
        else if (b === "x" && !single && otherText !== "") other()
        else if (b === "y" && !single && extraText !== "") extra()
        else if (b === "y" && rememberText !== "") remember = !remember
        else if ((b === "up" || b === "down") && scroller.interactive)
            scroller.contentY = Math.max(0, Math.min(scroller.contentHeight - scroller.height, scroller.contentY + (b === "up" ? -1 : 1) * Theme.px(60)))
        return true
    }
    function handleRelease(b) { if (b === "a" || b === "x") stopHold() }
}

import QtQuick
import Huginn

// Labs › Report a bug: the screenshot of Huginn taken just before this opened, Include screenshot (on unless
// switched off), what went wrong (up to 2000 characters), Include performance check (when Labs › Performance
// check opened it; on unless switched off), Include crash log (off unless ticked; only when there is one), then Send. The D-pad moves between them, A uses one, B or Esc closes. Main.qml takes the screenshot and
// opens it (openReport).
Item {
    id: report
    visible: false
    anchors.fill: parent
    property string text: ""
    property bool include: true
    property bool includeLog: false
    property bool hasLog: false
    property bool tour: false
    // Labs › Performance check's short report, sent after what was written.
    property string perf: ""
    property bool includePerf: true
    // The focus with a controller: 0 Include screenshot, 1 the text, 5 Include performance check, 4 Include crash
    // log, 2 Send, 3 Cancel.
    property int focusAt: 1
    readonly property bool padFocus: !!Ui.main && Ui.main.padInUse
    // Top to bottom (Cancel sits beside Send).
    readonly property var order: (Labs.hasShot ? [0] : []).concat([1]).concat(perf !== "" ? [5] : []).concat(hasLog ? [4] : []).concat([2])

    function open(asTour) {
        tour = !!asTour
        text = tour ? "The chat list jumps to the top when a photo arrives." : ""
        include = true
        includeLog = false
        hasLog = !tour && Labs.hasCrashLog()
        perf = ""
        includePerf = true
        focusAt = 1
        visible = true
    }
    // From Labs › Performance check: its report goes along.
    function openWith(perfText) {
        open(false)
        perf = perfText
        text = "Performance check"
    }
    function close() {
        visible = false
        Labs.clearShot()
        if (Ui.main) Ui.main.restoreFocus()
    }
    function dismiss() {
        if (tour || report.text.trim() === "") { close(); return }
        Ui.sure("Discard this report?", "What you wrote is lost.", "Discard", () => report.close(), false, true)
    }
    // The controller's focus stays in view.
    onFocusAtChanged: Qt.callLater(reveal)
    function reveal() {
        const it = focusAt === 0 ? shotRow : focusAt === 1 ? textBox : focusAt === 4 ? logRow : focusAt === 5 ? perfRow : buttons
        if (!it || !it.visible) return
        const y = it.mapToItem(col, 0, 0).y
        if (y < view.contentY) view.contentY = Math.max(0, y)
        else if (y + it.height > view.contentY + view.height) view.contentY = Math.min(Math.max(0, view.contentHeight - view.height), y + it.height - view.height)
    }
    function edit() {
        Ui.ask("What went wrong?", "Up to 2000 characters", report.text, "Done", t => report.text = t.slice(0, 2000), false, true)
    }
    function send() {
        if (tour) return
        const err = Labs.send(report.text, report.include && Labs.hasShot, report.includeLog && report.hasLog, report.includePerf ? report.perf : "")
        if (err !== "") { Ui.say(err, true); return }
        Ui.say("Report sent. Thank you.")
        close()
    }
    function activate() {
        if (focusAt === 0) include = !include
        else if (focusAt === 1) edit()
        else if (focusAt === 4) includeLog = !includeLog
        else if (focusAt === 5) includePerf = !includePerf
        else if (focusAt === 2) send()
        else close()
    }
    function handleButton(b) {
        switch (b) {
        // Top to bottom as in order, with 2 and 3 side by side at the end.
        case "up": {
            const i = order.indexOf(focusAt === 3 ? 2 : focusAt)
            focusAt = order[Math.max(0, i - 1)]
            break
        }
        case "down": {
            if (focusAt === 2 || focusAt === 3) break
            const i = order.indexOf(focusAt)
            focusAt = order[Math.min(order.length - 1, i + 1)]
            break
        }
        case "left": if (focusAt === 2) focusAt = 3; else if (focusAt === 3) focusAt = 2; break
        case "right": if (focusAt === 3) focusAt = 2; else if (focusAt === 2) focusAt = 3; break
        case "a": activate(); break
        case "b": dismiss(); break
        }
        return true
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    // A click beside it closes it, but never throws away what was typed without asking.
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: report.dismiss() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(620))
        height: Math.min(parent.height - Theme.px(32), col.implicitHeight + Theme.px(40))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        Flickable {
            id: view
            anchors.fill: parent
            anchors.margins: Theme.px(20)
            contentHeight: col.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: col
                width: view.width
                spacing: Theme.px(14)
                Text {
                    textFormat: Text.PlainText
                    text: "REPORT A BUG"
                    color: Theme.text
                    font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
                }
                // The screenshot, as it will go (smaller here).
                Rectangle {
                    visible: Labs.hasShot
                    width: parent.width
                    height: Math.round(width * 9 / 16)
                    radius: Theme.radius
                    color: Theme.groundDeep
                    border.color: Theme.line
                    opacity: report.include ? 1 : 0.35
                    Image {
                        anchors.fill: parent
                        anchors.margins: 1
                        fillMode: Image.PreserveAspectFit
                        source: Labs.shotUrl
                        asynchronous: true
                    }
                }
                Rectangle {
                    id: shotRow
                    visible: Labs.hasShot
                    width: parent.width
                    height: Theme.px(48)
                    radius: Theme.radius
                    color: report.focusAt === 0 && report.padFocus ? Theme.focusFill : "transparent"
                    border.width: report.focusAt === 0 && report.padFocus ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Text {
                        textFormat: Text.PlainText
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        text: "Include screenshot"
                        color: Theme.text
                        font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium
                    }
                    ToggleSwitch {
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        checked: report.include
                        onToggled: report.include = !report.include
                    }
                }
                // What went wrong: a click or A opens the keyboard prompt.
                Rectangle {
                    id: textBox
                    width: parent.width
                    height: Math.max(Theme.px(96), body.implicitHeight + Theme.px(24))
                    radius: Theme.radius
                    color: Theme.groundDeep
                    border.width: report.focusAt === 1 && report.padFocus ? Theme.focusWidth : 1
                    border.color: report.focusAt === 1 && report.padFocus ? Theme.accent : Theme.lineMuted
                    Accessible.role: Accessible.EditableText
                    Accessible.name: "What went wrong?"
                    Text {
                        textFormat: Text.PlainText
                        id: body
                        x: Theme.px(12); y: Theme.px(12)
                        width: parent.width - Theme.px(24)
                        wrapMode: Text.Wrap
                        text: report.text !== "" ? report.text : "What went wrong?"
                        color: report.text !== "" ? Theme.text : Theme.textFaint
                        font.family: Theme.body; font.pixelSize: Theme.fs(15)
                    }
                    HoverHandler { cursorShape: Qt.IBeamCursor }
                    TapHandler { onTapped: { report.focusAt = 1; report.edit() } }
                }
                // Labs › Performance check's report (no names, messages, files or addresses). On unless switched off.
                Rectangle {
                    id: perfRow
                    visible: report.perf !== ""
                    width: parent.width
                    height: Theme.px(48)
                    radius: Theme.radius
                    color: report.focusAt === 5 && report.padFocus ? Theme.focusFill : "transparent"
                    border.width: report.focusAt === 5 && report.padFocus ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Text {
                        textFormat: Text.PlainText
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.right: perfSwitch.left; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight
                        text: "Include performance check"
                        color: Theme.text
                        font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium
                    }
                    ToggleSwitch {
                        id: perfSwitch
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        checked: report.includePerf
                        onToggled: report.includePerf = !report.includePerf
                    }
                }
                // The crash log's last lines (crashes and errors only; no messages, names or addresses). Off unless ticked.
                Rectangle {
                    id: logRow
                    visible: report.hasLog
                    width: parent.width
                    height: Theme.px(48)
                    radius: Theme.radius
                    color: report.focusAt === 4 && report.padFocus ? Theme.focusFill : "transparent"
                    border.width: report.focusAt === 4 && report.padFocus ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Text {
                        textFormat: Text.PlainText
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.right: logSwitch.left; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight
                        text: "Include crash log"
                        color: Theme.text
                        font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium
                    }
                    ToggleSwitch {
                        id: logSwitch
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        checked: report.includeLog
                        onToggled: report.includeLog = !report.includeLog
                    }
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: "Also sends Huginn " + Labs.label + ", " + Labs.os
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
                Row {
                    id: buttons
                    anchors.right: parent.right
                    spacing: Theme.px(10)
                    HButton { text: "Cancel"; kind: "plain"; focused: report.focusAt === 3 && report.padFocus; onClicked: report.close() }
                    HButton { text: "Send"; kind: "primary"; enabled: report.text.trim() !== ""; focused: report.focusAt === 2 && report.padFocus; onClicked: report.send() }
                }
            }
        }
    }
}

import QtQuick
import QtQuick.Controls.Basic
import Huginn

// A new poll (cap m2), the same as Muninn's PollDialog: a question, 2 to 10 answers, and whether several may
// be picked. With the controller: up and down pick a line, A types in it (Steam's keyboard), X adds an answer,
// Y switches "Several answers", Menu sends, B cancels.
Item {
    id: dlg
    visible: false
    anchors.fill: parent
    property string title: "New poll"
    property var answers: ["", ""]
    property bool multi: false
    property int row: 0                // 0 the question, then the answers, then Add, Several, Send
    signal send(string question, var answers, bool multi)

    readonly property var filled: answers.map(a => a.trim()).filter(a => a.length > 0)
    readonly property bool same: filled.map(a => a.toLowerCase()).filter((a, i, l) => l.indexOf(a) !== i).length > 0
    readonly property bool ready: question.text.trim().length > 0 && filled.length >= 2 && !same
    readonly property int rows: answers.length + 4

    function open(t) {
        title = t || "New poll"
        question.text = ""
        answers = ["", ""]
        multi = false
        row = 0
        visible = true
        Qt.callLater(() => focusRow(0, false))
    }
    function close() {
        visible = false
        System.hideSteamKeyboard()
        if (Ui.main) Ui.main.restoreFocus()
    }
    function setAnswer(i, t) { const a = answers.slice(); a[i] = t.replace(/\n/g, " "); answers = a }
    function addAnswer() { if (answers.length < 10) { answers = answers.concat([""]); Qt.callLater(() => focusRow(answers.length, false)) } }
    function removeAnswer(i) { if (answers.length > 2) { const a = answers.slice(); a.splice(i, 1); answers = a } }
    function submit() {
        if (!ready) { Ui.say(same ? "Two answers are the same." : "A poll needs a question and at least 2 answers.", true); return }
        const q = question.text.trim(), a = filled, m = multi
        close()
        send(q, a, m)
    }
    // A line picked with the controller; typing puts the cursor in it.
    function focusRow(r, type) {
        row = Math.max(0, Math.min(rows - 1, r))
        const f = row === 0 ? question : row <= answers.length ? fields.itemAt(row - 1) : null
        if (f && (type || !(Ui.main && Ui.main.padInUse))) { f.forceActiveFocus(); if (type) System.showSteamKeyboard() }
        else if (Ui.main) Ui.main.restoreFocus()
    }
    function handleButton(b) {
        switch (b) {
        case "up": focusRow(row - 1, false); return true
        case "down": focusRow(row + 1, false); return true
        case "a":
            if (row <= answers.length) focusRow(row, true)
            else if (row === answers.length + 1) addAnswer()
            else if (row === answers.length + 2) multi = !multi
            else submit()
            return true
        case "x": addAnswer(); return true
        case "y": multi = !multi; return true
        case "start": submit(); return true
        case "b": close(); return true
        }
        return true
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.85) }
    MouseArea { anchors.fill: parent }

    Rectangle {
        id: box
        anchors.horizontalCenter: parent.horizontalCenter
        y: Theme.px(30)
        width: Math.min(parent.width - Theme.px(60), Theme.px(720))
        height: Math.min(parent.height - Theme.px(60), col.implicitHeight + Theme.px(40))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.lineStrong
        Flickable {
            anchors.fill: parent; anchors.margins: Theme.px(20)
            contentHeight: col.implicitHeight
            clip: true
            Column {
                id: col
                width: parent.width
                spacing: Theme.px(10)
                Text { textFormat: Text.PlainText; text: dlg.title.toUpperCase(); color: Theme.textMuted; font.family: Theme.display; font.pixelSize: Theme.fs(17); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(2) }
                TextField {
                    id: question
                    FieldMenu {}   // right click: Cut, Copy, Paste, Select all
                    width: parent.width; height: Theme.px(52)
                    placeholderText: "Question"
                    maximumLength: 200
                    color: Theme.text; placeholderTextColor: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(19)
                    background: Rectangle { radius: Theme.radius; color: Theme.ground; border.color: dlg.row === 0 || question.activeFocus ? Theme.accent : Theme.lineMuted; border.width: dlg.row === 0 ? Theme.focusWidth : 1 }
                    onActiveFocusChanged: if (activeFocus) dlg.row = 0
                    Keys.onReturnPressed: dlg.focusRow(1, false)
                    Keys.onEnterPressed: dlg.focusRow(1, false)
                    Keys.onEscapePressed: dlg.close()
                }
                Repeater {
                    id: fields
                    model: dlg.answers.length
                    delegate: TextField {
                        required property int index
                        FieldMenu {}   // right click: Cut, Copy, Paste, Select all
                        width: col.width - (dlg.answers.length > 2 ? Theme.px(52) : 0); height: Theme.px(48)
                        placeholderText: "Answer " + (index + 1)
                        maximumLength: 100
                        text: dlg.answers[index]
                        onTextEdited: dlg.setAnswer(index, text)
                        color: Theme.text; placeholderTextColor: Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(17)
                        background: Rectangle { radius: Theme.radius; color: Theme.ground; border.color: dlg.row === index + 1 ? Theme.accent : Theme.lineMuted; border.width: dlg.row === index + 1 ? Theme.focusWidth : 1 }
                        onActiveFocusChanged: if (activeFocus) dlg.row = index + 1
                        Keys.onReturnPressed: index + 1 < dlg.answers.length ? dlg.focusRow(index + 2, false) : dlg.submit()
                        Keys.onEnterPressed: index + 1 < dlg.answers.length ? dlg.focusRow(index + 2, false) : dlg.submit()
                        Keys.onEscapePressed: dlg.close()
                        // Remove this answer (there are always at least 2).
                        Rectangle {
                            visible: dlg.answers.length > 2
                            anchors.left: parent.right; anchors.leftMargin: Theme.px(8); anchors.verticalCenter: parent.verticalCenter
                            width: Theme.px(44); height: width; radius: Theme.radius; color: Theme.raised; border.color: Theme.lineMuted
                            Icon { anchors.centerIn: parent; name: "close"; size: Theme.px(18) }
                            Accessible.role: Accessible.Button
                            Accessible.name: "Remove answer " + (index + 1)
                            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: dlg.removeAnswer(index) }
                        }
                    }
                }
                HButton {
                    visible: dlg.answers.length < 10
                    text: "Add an answer"
                    focused: dlg.row === dlg.answers.length + 1
                    onClicked: dlg.addAnswer()
                }
                Rectangle {
                    width: parent.width; height: Theme.px(56)
                    radius: Theme.radius
                    color: "transparent"
                    border.color: dlg.row === dlg.answers.length + 2 ? Theme.accent : "transparent"; border.width: Theme.focusWidth
                    Column {
                        anchors.verticalCenter: parent.verticalCenter; x: Theme.px(8)
                        Text { textFormat: Text.PlainText; text: "Several answers"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17) }
                        Text { textFormat: Text.PlainText; text: dlg.multi ? "People can pick more than one" : "People pick one"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                    }
                    ToggleSwitch { anchors.right: parent.right; anchors.rightMargin: Theme.px(8); anchors.verticalCenter: parent.verticalCenter; checked: dlg.multi; onToggled: dlg.multi = !dlg.multi }
                }
                Text {
                    textFormat: Text.PlainText
                    visible: dlg.same
                    text: "Two answers are the same."
                    color: Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
                Row {
                    spacing: Theme.px(14)
                    anchors.right: parent.right
                    Text {
                        textFormat: Text.PlainText
                        anchors.verticalCenter: parent.verticalCenter
                        text: Ui.main && Ui.main.padInUse ? "X: add an answer · Y: several · Menu: send" : ""
                        color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                    }
                    HButton { text: "Cancel"; implicitHeight: Theme.px(44); fontSize: 16; onClicked: dlg.close() }
                    HButton { text: "Send"; kind: "accent"; enabled: dlg.ready; focused: dlg.row === dlg.answers.length + 3; implicitHeight: Theme.px(44); fontSize: 16; onClicked: dlg.submit() }
                }
            }
        }
    }
}

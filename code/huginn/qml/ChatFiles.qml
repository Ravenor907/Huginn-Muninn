import QtQuick
import QtQuick.Controls.Basic
import Huginn

// A chat's or group's Files (the header's file icon, or its menu › Files): everything sent and received there, newest
// first, with where it is. Typing in the box at the top shows only the files whose name has it; a right click on a
// file opens the same choices as a menu.
// Open uses this computer's app for that kind of file (only when asked), Show in folder opens its folder, and Delete
// takes a received file off the disk (a sent one only leaves the list; the original stays). Esc, B or a click outside
// closes it; Up and Down pick a file, Left and Right a button, A presses it.
Item {
    id: panel
    visible: false
    anchors.fill: parent
    property string where: ""
    property string title: ""
    property bool tour: false
    property int sel: -1
    property int button: 0
    property int revision: 0

    function open(w, t, isTour) {
        where = w; title = t; tour = !!isTour; sel = -1; button = 0; revision++; filterField.text = ""; visible = true; view.contentY = 0
        if (!tour) Qt.callLater(() => filterField.forceActiveFocus())
    }
    function close() { visible = false; if (Ui.main) Ui.main.restoreFocus() }

    readonly property var samples: [
        { name: "Raid plan.pdf", size: 2400000, mine: true, there: true, folder: "Documents", path: "", icon: "file", time: Date.now() - 3600000 },
        { name: "Recipe.docx", size: 48000, mine: false, there: true, folder: "Downloads/Huginn/Gaming Crew", path: "", icon: "file", time: Date.now() - 86400000 },
        { name: "Screenshot.png", size: 820000, mine: false, there: true, folder: "Downloads/Huginn/Gaming Crew", path: "", icon: "image", time: Date.now() - 2 * 86400000 },
        { name: "Old notes.txt", size: 1200, mine: false, there: false, folder: "Downloads/Huginn/Gaming Crew", path: "", icon: "file", time: Date.now() - 5 * 86400000 }
    ]
    readonly property var files: !visible ? [] : tour ? samples : (revision, FileLink.transfers, FileLink.filesIn(where))
    // The files whose name has what's typed in the box (all of them when it's empty).
    readonly property string filter: filterField.text.trim().toLowerCase()
    readonly property var shown: filter === "" ? files : files.filter(f => String(f.name).toLowerCase().indexOf(filter) >= 0)
    readonly property string saveText: tour ? "Downloads/Huginn/" + title : where === "" ? "" : FileLink.folderText(where)

    function acts(f) {
        if (!f) return []
        const out = []
        if (f.there && !f.mine) out.push({ label: "Open", act: "open", kind: "accent" })
        if (f.there) out.push({ label: "Show in folder", act: "folder", kind: "normal" })
        out.push({ label: f.mine ? "Remove" : "Delete", act: "delete", kind: "danger" })
        return out
    }
    function run(f, a) {
        if (tour) return
        if (a === "open") { if (!FileLink.openFile(f.path)) Ui.say("Nothing on this computer opens this kind of file.", true) }
        else if (a === "folder") { if (!FileLink.showInFolder(f.path)) Ui.say("It isn't there any more.", true) }
        else if (a === "delete") {
            const w = where, p = f.path
            if (f.mine) { FileLink.deleteFile(w, p); revision++; Ui.say("Removed from the list. The file itself stays where it was."); return }
            Ui.sure("Delete " + f.name + "?", f.there ? "It's deleted from this computer." : "It's already gone; this takes it off the list.", "Delete", () => {
                if (FileLink.deleteFile(w, p)) Ui.say("Deleted.")
                else Ui.say("It couldn't be deleted.", true)
                panel.revision++
            }, false, true)
        }
    }
    // A right click on a file: its choices as a menu.
    function rowMenu(f) {
        const list = acts(f)
        if (!list.length) return
        Ui.pickFrom(f.name, [{ header: "", items: list.map(a => ({ name: a.label, url: a.act })) }], a => panel.run(f, a))
    }
    function when(ms) {
        const d = new Date(ms)
        return d.toLocaleDateString(Qt.locale(), Locale.ShortFormat) + " " + d.toLocaleTimeString(Qt.locale(), Locale.ShortFormat)
    }

    function handleButton(b) {
        const n = shown.length
        if (b === "b") { close(); return true }
        if (b === "up") { sel = n ? Math.max(0, sel - 1) : -1; button = 0; return true }
        if (b === "down") { sel = n ? Math.min(n - 1, sel + 1) : -1; button = 0; return true }
        const list = sel >= 0 ? acts(shown[sel]) : []
        if (b === "left") { button = Math.max(0, button - 1); return true }
        if (b === "right") { button = Math.min(Math.max(0, list.length - 1), button + 1); return true }
        if (b === "a" && list.length) { run(shown[sel], list[Math.min(button, list.length - 1)].act); return true }
        return true
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(820))
        height: Math.min(parent.height - Theme.px(32), head.height + Math.max(Theme.px(90), column.implicitHeight) + Theme.px(48))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        Column {
            id: head
            x: Theme.px(24); y: Theme.px(20)
            width: parent.width - Theme.px(48)
            spacing: Theme.px(4)
            Item {
                width: parent.width; height: Theme.px(28)
                Text {
                    textFormat: Text.PlainText
                    anchors.left: parent.left; anchors.right: closeHint.left; anchors.rightMargin: Theme.px(12)
                    anchors.verticalCenter: parent.verticalCenter
                    elide: Text.ElideRight
                    text: "FILES · " + panel.title.toUpperCase()
                    color: Theme.text
                    font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
                }
                Text {
                    id: closeHint
                    textFormat: Text.PlainText
                    anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                    text: "Esc closes"
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
                }
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width; elide: Text.ElideMiddle
                visible: panel.saveText !== ""
                text: "Received files are saved in " + panel.saveText + ". Settings › Devices › Save files to changes it."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
            TextField {
                id: filterField
                FieldMenu {}   // right click: Cut, Copy, Paste, Select all
                width: parent.width
                height: Math.max(Theme.px(38), implicitHeight)
                leftPadding: Theme.px(12)
                color: Theme.text
                placeholderText: "Find a file by name"
                placeholderTextColor: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.fs(15)
                background: Rectangle { radius: Theme.radius; color: Theme.ground; border.color: filterField.activeFocus ? Theme.accent : Theme.line }
                onTextChanged: { panel.sel = -1; panel.button = 0 }
                Keys.onPressed: event => {
                    if (event.key === Qt.Key_Down) { panel.handleButton("down"); event.accepted = true }
                    else if (event.key === Qt.Key_Up) { panel.handleButton("up"); event.accepted = true }
                    else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) { panel.handleButton("a"); event.accepted = true }
                    else if (event.key === Qt.Key_Escape) { panel.close(); event.accepted = true }
                }
            }
        }
        Flickable {
            id: view
            anchors.top: head.bottom; anchors.topMargin: Theme.px(14)
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(16); anchors.rightMargin: Theme.px(16); anchors.bottomMargin: Theme.px(14)
            contentHeight: column.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: column
                width: view.width
                spacing: Theme.px(8)
                Text {
                    textFormat: Text.PlainText
                    visible: panel.shown.length === 0
                    width: parent.width; wrapMode: Text.Wrap
                    topPadding: Theme.px(12)
                    text: panel.files.length ? "No file names have that." : "No files here yet. Send one with the clip beside the message box."
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
                Repeater {
                    model: panel.shown
                    delegate: Rectangle {
                        id: row
                        required property var modelData
                        required property int index
                        readonly property var f: modelData
                        readonly property var buttons: panel.acts(f)
                        width: column.width
                        height: Math.max(Theme.px(64), info.implicitHeight + Theme.px(20))
                        radius: Theme.radius
                        color: Theme.raised
                        border.color: panel.sel === index ? Theme.text : Theme.line
                        border.width: panel.sel === index ? Theme.focusWidth : 1
                        Accessible.role: Accessible.StaticText
                        Accessible.name: f.name + ", " + (f.mine ? "sent" : "received")
                        TapHandler {
                            acceptedButtons: Qt.RightButton
                            onTapped: (point, button) => { panel.sel = row.index; Ui.menuAt(row, point.position.x, point.position.y, () => panel.rowMenu(row.f)) }
                        }
                        Rectangle {
                            id: badge
                            anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                            anchors.verticalCenter: parent.verticalCenter
                            width: Theme.px(40); height: width
                            radius: Theme.radius
                            color: Theme.panel
                            Icon { anchors.centerIn: parent; name: row.f.icon || "file"; size: Theme.px(20); color: row.f.there ? Theme.accent : Theme.textMuted }
                        }
                        Column {
                            id: info
                            anchors.left: badge.right; anchors.leftMargin: Theme.px(12)
                            anchors.right: buttonRow.left; anchors.rightMargin: Theme.px(10)
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: Theme.px(2)
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width; elide: Text.ElideMiddle
                                text: row.f.name
                                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                            }
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width; elide: Text.ElideRight
                                text: FileLink.size(row.f.size) + " · " + (row.f.mine ? "Sent" : "Received") + " " + panel.when(row.f.time)
                                      + " · " + (row.f.there ? row.f.folder : "Moved or deleted")
                                color: row.f.there ? Theme.textMuted : Theme.danger
                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                        }
                        Row {
                            id: buttonRow
                            anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: Theme.px(6)
                            Repeater {
                                model: row.buttons
                                HButton {
                                    required property var modelData
                                    required property int index
                                    height: Theme.px(34); fontSize: 13
                                    text: modelData.label
                                    kind: modelData.kind
                                    focused: panel.sel === row.index && panel.button === index
                                    onClicked: panel.run(row.f, modelData.act)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

import QtQuick
import Huginn

// Transfers (the top bar's button): every file coming or going, newest first. Each row says what it is (name, size,
// kind), who it's from or to and which chat, how it's going with a bar, speed and time left, its route ("Direct" on the
// same Wi-Fi, "Over the internet" otherwise), and why when it didn't work. Buttons: Accept, Always from this person and
// Decline when a file asks first, Pause or Resume and Cancel while it runs, Retry after a failure, Open and Folder once
// saved. LocalSend devices nearby show here too. Esc, B or a click outside closes it; Up and Down pick a row, Left and
// Right a button, A presses it.
Item {
    id: panel
    visible: false
    anchors.fill: parent
    property bool tour: false
    property int sel: -1
    property int button: 0

    function open(isTour) { tour = !!isTour; sel = -1; button = 0; visible = true; view.contentY = 0 }
    function close() { visible = false }

    // The screenshot tour's sample rows (nothing real is shown or sent).
    readonly property var samples: [
        { id: "s1", name: "Raid plan.pdf", size: 2400000, done: 1100000, mine: true, state: "sending", kind: "PDF document", chat: "Gaming Crew",
          peer: "", route: "Direct", pace: "4.2 MB/s · 1 s left", where: "g:tour", folder: "", note: "", path: "", paused: false, held: false },
        { id: "s2", name: "Holiday photos.zip", size: 380000000, done: 0, mine: false, state: "asking", kind: "Archive", chat: "Mira", peer: "tour",
          route: "", pace: "", where: "tour", folder: "Downloads/Huginn/Mira", note: "", path: "", paused: false, held: false },
        { id: "s3", name: "Map pack.7z", size: 52000000, done: 31000000, mine: true, state: "sending", kind: "Archive", chat: "Jake", peer: "",
          route: "Over the internet", pace: "", where: "j", folder: "", note: "", path: "", paused: true, held: false },
        { id: "s4", name: "Recipe.docx", size: 48000, done: 48000, mine: false, state: "done", kind: "Document", chat: "Family", peer: "tour",
          route: "Direct", pace: "", where: "g:fam", folder: "Downloads/Huginn/Family", note: "", path: "", paused: false, held: false },
        { id: "s5", name: "Season 2.mkv", size: 1900000000, done: 0, mine: true, state: "failed", kind: "Video", chat: "Folk", peer: "",
          route: "", pace: "", where: "g:folk", folder: "", note: "Refused by the group's file rules.", path: "", paused: false, held: false }
    ]

    // LocalSend's own transfer (a device nearby) as rows like the others.
    readonly property var nearbyRows: {
        const out = []
        if (tour) return out
        if (LocalSend.sending) {
            LocalSend.outgoing.forEach((f, i) => out.push({ id: "ls-out-" + i, local: true, name: f.name, size: f.size, done: f.done, mine: true,
                state: f.status === "Sent" ? "done" : "sending", kind: FileLink.kind(f.name), chat: "LocalSend · " + f.to, peer: "", route: "Direct",
                pace: LocalSend.pace, where: "", folder: "", note: f.status, path: "", paused: false, held: false }))
        }
        LocalSend.items.forEach((f, i) => { if (f.from !== undefined) out.push({ id: "ls-in-" + i, local: true, name: f.name, size: f.size || 0,
            done: f.done || 0, mine: false, state: f.failed ? "failed" : f.running ? "sending" : "done", kind: FileLink.kind(f.name),
            chat: "LocalSend · " + f.from, peer: "", route: "Direct", pace: f.running ? LocalSend.pace : "", where: "", folder: "", note: f.status,
            path: "", paused: false, held: false }) })
        return out
    }
    readonly property var rows: tour ? samples : nearbyRows.concat(FileLink.transfers.slice().reverse())

    function acts(t) {
        if (!t) return []
        const out = []
        if (tour) {
            if (t.state === "asking") out.push({ label: "Accept", act: "", kind: "primary" }, { label: "Always from this person", act: "", kind: "normal" }, { label: "Decline", act: "", kind: "normal" })
            else if (t.paused) out.push({ label: "Resume", act: "", kind: "accent" }, { label: "Cancel", act: "", kind: "danger" })
            else if (t.state === "sending") out.push({ label: "Pause", act: "", kind: "normal" }, { label: "Cancel", act: "", kind: "danger" })
            else if (t.state === "done") out.push({ label: "Open", act: "", kind: "accent" }, { label: "Folder", act: "", kind: "normal" })
            else out.push({ label: "Retry", act: "", kind: "accent" }, { label: "Close", act: "", kind: "normal" })
            return out
        }
        if (t.local) {
            if (t.state === "sending") out.push({ label: "Cancel", act: t.mine ? "lsout" : "lsin", kind: "danger" })
            return out
        }
        if (t.state === "asking" && !t.mine) {
            out.push({ label: "Accept", act: "accept", kind: "primary" })
            if (Chat.isContact(t.peer)) out.push({ label: "Always from this person", act: "always", kind: "normal" })
            out.push({ label: "Decline", act: "decline", kind: "normal" })
        } else if (FileLink.canResume(t.id)) {
            out.push({ label: "Resume", act: "resume", kind: "accent" }, { label: "Cancel", act: "cancel", kind: "danger" })
        } else if (FileLink.canPause(t.id)) {
            out.push({ label: "Pause", act: "pause", kind: "normal" }, { label: "Cancel", act: "cancel", kind: "danger" })
        } else if (FileLink.canCancel(t.id)) {
            out.push({ label: "Cancel", act: "cancel", kind: "danger" })
        } else if (t.state === "done" && t.path) {
            if (!t.mine) out.push({ label: "Open", act: "open", kind: "accent" })
            out.push({ label: "Folder", act: "folder", kind: "normal" }, { label: "Close", act: "close", kind: "normal" })
        } else if (t.state === "failed" && FileLink.canRetry(t.id)) {
            // Too big to go without Direct: one tap turns it on here and asks them to do the same.
            if (FileLink.needsDirect(t.id) && Chat.isContact(t.peer))
                out.push({ label: Calls.trusted(t.peer) ? "Ask " + Chat.nameOf(t.peer) : "Allow direct", act: "direct", kind: "primary" })
            out.push({ label: "Retry", act: "retry", kind: "accent" }, { label: "Close", act: "close", kind: "normal" })
        } else if (t.state === "done" || t.state === "failed") {
            out.push({ label: "Close", act: "close", kind: "normal" })
        }
        return out
    }
    function run(t, a) {
        if (tour || a === "") return
        if (a === "accept") FileLink.accept(t.id)
        else if (a === "always") FileLink.acceptAlways(t.id)
        else if (a === "decline") FileLink.decline(t.id)
        else if (a === "pause") FileLink.pause(t.id)
        else if (a === "resume") FileLink.resume(t.id)
        else if (a === "cancel") FileLink.cancel(t.id)
        else if (a === "retry") FileLink.retry(t.id)
        else if (a === "close") FileLink.dismiss(t.id)
        else if (a === "open") { if (!FileLink.openFile(t.path)) Ui.say("Nothing on this computer opens this kind of file, or it was moved.", true) }
        else if (a === "folder") { if (!FileLink.showInFolder(t.path)) Ui.say("It isn't there any more.", true) }
        else if (a === "direct") PeopleActions.allowDirect(t.peer)
        else if (a === "lsout") LocalSend.cancelSend()
        else if (a === "lsin") LocalSend.cancelReceive()
    }
    // Size, kind, what it's doing, route and pace.
    function line(t) {
        const parts = [FileLink.size(t.size), t.kind]
        parts.push(t.local ? (t.note || "") : tour && t.paused ? "Paused" : tour ? statusOf(t) : FileLink.text(t))
        if (t.route !== "" && t.state !== "failed") parts.push(t.route)
        if (t.pace !== "") parts.push(t.pace)
        return parts.filter(p => p !== "").join(" · ")
    }
    function statusOf(t) {
        if (t.state === "asking") return "Wants to send you this"
        if (t.state === "sending") return (t.mine ? "Sending " : "Receiving ") + Math.floor(100 * t.done / Math.max(1, t.size)) + "%"
        if (t.state === "done") return t.mine ? "Sent" : "Saved"
        return t.note
    }
    // Who it's from or to, the chat, and where it goes.
    function whoLine(t) {
        if (t.mine) return "To " + t.chat
        const from = t.local || tour ? t.chat : Chat.nameOf(t.peer)
        const via = !t.local && t.where.indexOf("g:") === 0 ? " in " + t.chat : ""
        return "From " + from + via + (t.folder !== "" ? " · Saves to " + t.folder : "")
    }

    function handleButton(b) {
        const n = rows.length
        if (b === "b") { close(); return true }
        if (b === "up") { sel = n ? Math.max(0, sel - 1) : -1; button = 0; reveal(); return true }
        if (b === "down") { sel = n ? Math.min(n - 1, sel + 1) : -1; button = 0; reveal(); return true }
        const list = sel >= 0 ? acts(rows[sel]) : []
        if (b === "left") { button = Math.max(0, button - 1); return true }
        if (b === "right") { button = Math.min(Math.max(0, list.length - 1), button + 1); return true }
        if (b === "a" && list.length) { run(rows[sel], list[Math.min(button, list.length - 1)].act); return true }
        return true
    }
    function reveal() {
        const item = rowsRepeater.itemAt(sel)
        if (!item) return
        if (item.y < view.contentY) view.contentY = item.y
        else if (item.y + item.height > view.contentY + view.height) view.contentY = item.y + item.height - view.height
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(860))
        height: Math.min(parent.height - Theme.px(32), head.height + Math.max(Theme.px(90), column.implicitHeight) + Theme.px(48))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        Item {
            id: head
            x: Theme.px(24); y: Theme.px(20)
            width: parent.width - Theme.px(48); height: Theme.px(28)
            Text {
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                text: "TRANSFERS"
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
            }
            Text {
                textFormat: Text.PlainText
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                text: "Esc closes"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
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
                    visible: panel.rows.length === 0
                    width: parent.width; wrapMode: Text.Wrap
                    topPadding: Theme.px(12)
                    text: "Nothing on its way. Send a file with the clip in any chat or group, or drop it on the chat."
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
                Repeater {
                    id: rowsRepeater
                    model: panel.rows
                    delegate: Rectangle {
                        id: row
                        required property var modelData
                        required property int index
                        readonly property var t: modelData
                        readonly property bool failed: t.state === "failed"
                        readonly property bool moving: t.state === "sending"
                        readonly property var buttons: panel.acts(t)
                        readonly property real fraction: t.size > 0 ? Math.min(1, t.done / t.size) : 0
                        width: column.width
                        height: Math.max(Theme.px(70), info.implicitHeight + Theme.px(22))
                        radius: Theme.radius
                        color: Theme.raised
                        border.color: panel.sel === index ? Theme.text : failed ? Theme.dangerLine : Theme.line
                        border.width: panel.sel === index ? Theme.focusWidth : 1
                        Accessible.role: Accessible.StaticText
                        Accessible.name: t.name + ", " + panel.line(t) + ", " + panel.whoLine(t)
                        // A right click: the row's buttons as a menu.
                        TapHandler {
                            acceptedButtons: Qt.RightButton
                            onTapped: (point, button) => {
                                panel.sel = row.index
                                const t = row.t, list = row.buttons
                                if (list.length) Ui.menuAt(row, point.position.x, point.position.y, () =>
                                    Ui.pickFrom(t.name, [{ header: "", items: list.map(a => ({ name: a.label, url: a.act })) }], a => panel.run(t, a)))
                            }
                        }
                        Rectangle {
                            anchors.left: parent.left; anchors.bottom: parent.bottom
                            anchors.leftMargin: 1; anchors.bottomMargin: 1
                            height: Theme.px(4)
                            width: (parent.width - 2) * row.fraction
                            color: row.t.paused || row.t.held ? Theme.textMuted : Theme.accent
                            visible: row.moving
                        }
                        Rectangle {
                            id: badge
                            anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                            anchors.verticalCenter: parent.verticalCenter
                            width: Theme.px(40); height: width
                            radius: Theme.radius
                            color: Theme.panel
                            Icon {
                                anchors.centerIn: parent
                                name: FileLink.icon(row.t.name); size: Theme.px(20)
                                color: row.failed ? Theme.danger : Theme.accent
                            }
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
                                text: row.t.name
                                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                            }
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width; wrapMode: Text.Wrap; maximumLineCount: 3; elide: Text.ElideRight
                                text: panel.line(row.t)
                                color: row.failed ? Theme.danger : Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                            Text {
                                textFormat: Text.PlainText
                                visible: row.failed && !panel.tour && !row.t.local && FileLink.needsDirect(row.t.id)
                                width: parent.width; wrapMode: Text.Wrap
                                text: "Big files go straight between your devices when you both turn on Direct calls and files."
                                color: Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(12)
                            }
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width; elide: Text.ElideRight
                                text: panel.whoLine(row.t)
                                color: Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(12)
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
                                    onClicked: panel.run(row.t, modelData.act)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

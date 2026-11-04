import QtQuick
import Huginn

// Files on their way in one chat or group (caps f1 and f2), each as a card at the bottom of the chat: type icon,
// name, size and kind, what it's doing ("Waiting for Ana to accept", "Sending 42%") with its route ("Direct" or
// "Over the internet") and speed, and a bar while it moves. The same cards as Transfers. Buttons: Decline, Accept and
// (Trusted or Family only) Always accept from them when a file asks first, Pause or Resume and Cancel while it runs, Open, Folder and Close once
// saved, Retry and Close after a failure. A file of mine going as a message shows on the message itself. Empty (and no
// height) when there are none. The D-pad reaches the buttons: Down past the last message, Left and Right between them,
// Up and Down between cards, A to choose, B to leave.
Column {
    id: strip
    property string peer: ""
    // The chat these cards belong to: their key, or "g:<group>".
    property string where: peer
    readonly property var items: where === "" ? [] : FileLink.transfers.filter(t => t.where === where && !t.relay)
    property int focusIndex: -1       // the card the controller is on (-1: none)
    property int focusButton: 0
    visible: items.length > 0
    spacing: Theme.px(6)
    onWhereChanged: focusIndex = -1

    // A card's buttons: [{ label, act, kind }]. The same as Transfers (TransfersPanel.acts).
    function acts(t) {
        if (!t) return []
        const out = []
        if (t.state === "asking" && !t.mine) {
            // The same as Transfers: Decline, Accept, and Always accept from them only for someone Trusted or Family
            // (undone in their Details › Send me files).
            out.push({ label: "Decline", act: "decline", kind: "normal" }, { label: "Accept", act: "accept", kind: "primary" })
            if (t.canAlways === true) out.push({ label: "Always accept from " + Chat.nameOf(t.peer), act: "always", kind: "normal" })
        } else if (FileLink.canResume(t.id)) {
            out.push({ label: "Resume", act: "resume", kind: "accent" }, { label: "Cancel", act: "cancel", kind: "danger" })
        } else if (FileLink.canPause(t.id)) {
            out.push({ label: "Pause", act: "pause", kind: "normal" }, { label: "Cancel", act: "cancel", kind: "danger" })
        } else if (FileLink.canCancel(t.id)) {
            out.push({ label: "Cancel", act: "cancel", kind: "danger" })
        } else if (t.state === "done" && !t.mine && t.path) {
            out.push({ label: "Open", act: "open", kind: "accent" }, { label: "Folder", act: "folder", kind: "normal" }, { label: "Close", act: "close", kind: "normal" })
        } else if (t.state === "failed" && FileLink.canRetry(t.id)) {
            if (FileLink.needsDirect(t.id) && Chat.isContact(t.peer))
                out.push({ label: PeopleActions.directLabel(t.peer), act: "direct", kind: PeopleActions.directLabel(t.peer) === "Asked" ? "normal" : "primary" })
            out.push({ label: "Retry", act: "retry", kind: "accent" }, { label: "Close", act: "close", kind: "normal" })
        } else if (t.state === "failed" && !t.mine && FileLink.keptSize(t.id) > 0) {
            // What came of their file is kept (it goes on from there): Close keeps it, Discard asks, then deletes it.
            out.push({ label: "Close", act: "close", kind: "normal" }, { label: "Discard", act: "discard", kind: "danger" })
        } else if (t.state === "done" || t.state === "failed") {
            out.push({ label: "Close", act: "close", kind: "normal" })
        }
        return out
    }
    function run(t, a) {
        if (a === "accept") FileLink.accept(t.id)
        else if (a === "always") FileLink.acceptAlways(t.id)
        else if (a === "decline") FileLink.decline(t.id)
        else if (a === "pause") FileLink.pause(t.id)
        else if (a === "resume") FileLink.resume(t.id)
        else if (a === "cancel") FileLink.cancel(t.id)
        else if (a === "open") { if (!FileLink.openFile(t.path)) Ui.say("Nothing on this computer opens this kind of file.", true) }
        else if (a === "folder") { if (!FileLink.showInFolder(t.path)) Ui.say("It's in " + t.folder + ".", true) }
        else if (a === "retry") FileLink.retry(t.id)
        else if (a === "direct") PeopleActions.allowDirect(t.peer)
        else if (a === "close") FileLink.dismiss(t.id)
        else if (a === "discard") {
            const pct = t.size > 0 ? Math.floor(t.done * 100 / t.size) : 0
            Ui.sure("Throw away what came?", "The " + pct + "% of " + t.name + " that came (" + FileLink.size(FileLink.keptSize(t.id))
                    + ") is deleted. It starts over if they send it again. Close keeps it for 7 days instead.", "Discard", () => FileLink.discard(t.id), false, true)
        }
    }
    // The line under a card's name: size, kind, what it's doing, its route and pace.
    function line(t) {
        const parts = [FileLink.size(t.size), t.kind, FileLink.text(t)]
        if (t.route !== "" && t.state !== "failed") parts.push(t.route)
        if (t.pace !== "") parts.push(t.pace)
        return parts.join(" · ")
    }
    // Who it's from or to, and where it goes.
    function whereLine(t) {
        if (t.mine) return "To " + t.chat
        return "From " + Chat.nameOf(t.peer) + (t.folder !== "" ? " · Saves to " + t.folder : "")
    }
    // What A does now, for the prompt bar.
    function focusLabel() {
        const b = acts(items[focusIndex])[focusButton]
        return b ? b.label : ""
    }
    function firstWithButtons() { return items.findIndex(t => acts(t).length > 0) }
    function enter() {
        const i = firstWithButtons()
        if (i < 0) return false
        focusIndex = i; focusButton = 0
        return true
    }
    // Up from the first card and B leave (false: the page takes Up to its messages).
    function handleButton(b) {
        const list = acts(items[focusIndex])
        if (!list.length) { focusIndex = -1; return false }
        if (b === "left") { focusButton = Math.max(0, focusButton - 1); return true }
        if (b === "right") { focusButton = Math.min(list.length - 1, focusButton + 1); return true }
        if (b === "down") {
            for (let i = focusIndex + 1; i < items.length; i++) if (acts(items[i]).length) { focusIndex = i; focusButton = 0; return true }
            return true
        }
        if (b === "a") {
            const t = items[focusIndex], act = list[Math.min(focusButton, list.length - 1)].act
            focusIndex = -1
            run(t, act)
            return true
        }
        if (b === "b") { focusIndex = -1; return true }
        if (b === "up") {
            for (let i = focusIndex - 1; i >= 0; i--) if (acts(items[i]).length) { focusIndex = i; focusButton = 0; return true }
            focusIndex = -1
            return false
        }
        return true
    }

    Repeater {
        model: strip.items
        delegate: Rectangle {
            id: card
            required property var modelData
            required property int index
            readonly property var t: modelData
            readonly property int at: index
            readonly property bool moving: t.state === "sending"
            readonly property bool failed: t.state === "failed"
            readonly property var buttons: strip.acts(t)
            readonly property int pct: t.size > 0 ? Math.floor(100 * Math.min(1, t.done / t.size)) : 0
            width: strip.width
            height: Math.max(Theme.px(64), info.implicitHeight + Theme.px(20))
            radius: Theme.radius
            color: Theme.panel
            border.color: strip.focusIndex === at ? Theme.text : failed ? Theme.dangerLine : Theme.line
            border.width: strip.focusIndex === at ? Theme.focusWidth : 1
            Accessible.role: Accessible.StaticText
            Accessible.name: t.name + ", " + strip.line(t)
            // How far it got, as a bar along the bottom edge.
            Rectangle {
                anchors.left: parent.left; anchors.bottom: parent.bottom
                anchors.leftMargin: 1; anchors.bottomMargin: 1
                height: Theme.px(4)
                width: (parent.width - 2) * card.pct / 100
                color: card.t.paused || card.t.held ? Theme.textMuted : Theme.accent
                visible: card.moving
            }
            Rectangle {
                id: badge
                anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(38); height: width
                radius: Theme.radius
                color: Theme.raised
                Icon {
                    anchors.centerIn: parent
                    name: FileLink.icon(card.t.name); size: Theme.px(20)
                    color: card.failed ? Theme.danger : Theme.accent
                }
            }
            Column {
                id: info
                anchors.left: badge.right; anchors.leftMargin: Theme.px(10)
                anchors.right: buttonRow.left; anchors.rightMargin: Theme.px(10)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(2)
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; elide: Text.ElideMiddle
                    text: card.t.name
                    color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; wrapMode: Text.Wrap; maximumLineCount: 3; elide: Text.ElideRight
                    text: strip.line(card.t)
                    color: card.failed ? Theme.danger : Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; elide: Text.ElideRight
                    visible: !card.t.mine
                    text: strip.whereLine(card.t)
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(12)
                }
            }
            Row {
                id: buttonRow
                anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(6)
                Repeater {
                    model: card.buttons
                    HButton {
                        required property var modelData
                        required property int index
                        height: Theme.px(34); fontSize: 13
                        text: modelData.label
                        kind: modelData.kind
                        focused: strip.focusIndex === card.at && strip.focusButton === index
                        onClicked: strip.run(card.t, modelData.act)
                    }
                }
            }
        }
    }
}

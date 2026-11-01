import QtQuick
import Huginn

// Files on their way to or from one person (cap f1), each as a card at the bottom of the chat: type icon,
// name, size and what it's doing ("Waiting for Ana to accept", "Sending 42%", "Receiving 42%"), with a bar
// while it moves. Buttons: Accept and Decline when a file asks first, Cancel while it runs, Open, Folder and
// Close once saved, Retry (or Send on Wi-Fi when it's too big for the internet route) and Close after a
// failure. Empty (and no height) when there are none. The D-pad reaches the buttons: Down past the last
// message (ChatPage), Left and Right between them, Up and Down between cards, A to choose, B to leave.
Column {
    id: strip
    property string peer: ""
    readonly property var items: peer === "" ? [] : FileLink.transfers.filter(t => t.peer === peer)
    property int focusIndex: -1       // the card the controller is on (-1: none)
    property int focusButton: 0
    visible: items.length > 0
    spacing: Theme.px(6)
    onPeerChanged: focusIndex = -1

    // A card's buttons: [{ label, act, kind }].
    function acts(t) {
        if (!t) return []
        const out = []
        if (t.state === "asking" && !t.mine) {
            out.push({ label: "Accept", act: "accept", kind: "primary" }, { label: "Decline", act: "decline", kind: "normal" })
        } else if (FileLink.canCancel(t.id)) {
            out.push({ label: "Cancel", act: "cancel", kind: "danger" })
        } else if (t.state === "done" && !t.mine && t.path) {
            out.push({ label: "Open", act: "open", kind: "accent" }, { label: "Folder", act: "folder", kind: "normal" }, { label: "Close", act: "close", kind: "normal" })
        } else if (t.state === "failed" && t.mine && t.wifi && t.path) {
            out.push({ label: "Send on Wi-Fi", act: "wifi", kind: "accent" }, { label: "Close", act: "close", kind: "normal" })
        } else if (t.state === "failed" && FileLink.canRetry(t.id)) {
            out.push({ label: "Retry", act: "retry", kind: "accent" }, { label: "Close", act: "close", kind: "normal" })
        } else if (t.state === "done" || t.state === "failed") {
            out.push({ label: "Close", act: "close", kind: "normal" })
        }
        return out
    }
    function run(t, a) {
        if (a === "accept") FileLink.accept(t.id)
        else if (a === "decline") FileLink.decline(t.id)
        else if (a === "cancel") FileLink.cancel(t.id)
        else if (a === "open") { if (!FileLink.openFile(t.path)) Ui.say("Nothing on this computer opens this kind of file.", true) }
        else if (a === "folder") { if (!FileLink.showInFolder(t.path)) Ui.say("It's in Downloads.", true) }
        else if (a === "retry") FileLink.retry(t.id)
        else if (a === "wifi") {
            Transfer.share([t.path])
            FileLink.dismiss(t.id)
            Ui.say("Ready in Files: pick their device on the same Wi-Fi.")
            Ui.openPage("files", {})
        }
        else if (a === "close") FileLink.dismiss(t.id)
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
            readonly property var t: modelData
            readonly property int at: index
            readonly property bool moving: t.state === "sending"
            readonly property bool failed: t.state === "failed"
            readonly property var buttons: strip.acts(t)
            readonly property int pct: t.size > 0 ? Math.floor(100 * Math.min(1, t.done / t.size)) : 0
            width: strip.width
            height: Math.max(Theme.px(60), info.implicitHeight + Theme.px(20))
            radius: Theme.radius
            color: Theme.panel
            border.color: strip.focusIndex === at ? Theme.text : failed ? Theme.dangerLine : Theme.line
            border.width: strip.focusIndex === at ? Theme.focusWidth : 1
            Accessible.role: Accessible.StaticText
            Accessible.name: t.name + ", " + FileLink.text(t)
            // How far it got, as a bar along the bottom edge.
            Rectangle {
                anchors.left: parent.left; anchors.bottom: parent.bottom
                anchors.leftMargin: 1; anchors.bottomMargin: 1
                height: Theme.px(4)
                width: (parent.width - 2) * card.pct / 100
                color: Theme.accent
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
                    text: FileLink.size(card.t.size) + " · " + FileLink.text(card.t)
                    color: card.failed ? Theme.danger : Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
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

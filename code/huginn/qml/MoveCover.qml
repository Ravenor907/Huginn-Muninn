import QtQuick
import Huginn

// Moving to another device (cap x1): over the pages and panels while it's under way (questions, prompts and toasts
// show above it). On the new computer: the 4 emoji to check and the parts coming in. On the old one: the emoji, then
// locked while it moves, then "Moved to [name]". A controller's D-pad moves between its buttons and A presses one.
Rectangle {
    id: cover
    readonly property var inc: Mover.incoming
    readonly property var out: Mover.outgoing
    readonly property bool here: inc.state === "hello" || inc.state === "receiving" || inc.state === "done"
    readonly property bool away: out.state === "checking" || out.state === "moving" || out.state === "moved"
    visible: here || away
    color: Theme.ground
    z: 29

    // The buttons on show, in order, and the one the controller is on.
    readonly property var buttons: [matchBtn, linkBtn, stopBtn, freshBtn, cancelBtn].filter(b => b.visible)
    property int focusIndex: 0
    readonly property int focusAt: Math.min(focusIndex, Math.max(0, buttons.length - 1))
    onVisibleChanged: focusIndex = 0
    function handleButton(b) {
        if (b === "left" || b === "up") focusIndex = Math.max(0, focusAt - 1)
        else if (b === "right" || b === "down") focusIndex = Math.min(buttons.length - 1, focusAt + 1)
        else if (b === "a" && buttons.length > 0) buttons[focusAt].clicked()
        return true
    }
    readonly property var prompts: buttons.length > 0 ? [{ key: "dpad", label: "Choose" }, { key: "a", label: buttons[focusAt].text }] : []

    MouseArea { anchors.fill: parent }   // nothing behind it can be used

    Column {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(64), Theme.px(560))
        spacing: Theme.px(14)

        Text {
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            color: Theme.text
            font.pixelSize: Theme.fs(26)
            font.bold: true
            text: cover.away ? (out.state === "moved" ? "Moved to " + out.to : "Moving to " + out.to)
                             : (inc.state === "done" ? "Moved here" : "Moving from " + inc.from)
        }
        Text {
            textFormat: Text.PlainText
            width: parent.width
            visible: (cover.away && out.state === "checking") || (cover.here && inc.state !== "done")
            horizontalAlignment: Text.AlignHCenter
            color: Theme.text
            font.pixelSize: Theme.fs(40)
            text: cover.away ? out.emoji : inc.emoji
        }
        Text {
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            color: Theme.textSoft
            font.pixelSize: Theme.fs(17)
            text: cover.away
                ? (out.state === "checking" ? "Check these are the same on both screens, then choose They match on both. Only continue if the other device is yours and in front of you. This computer is then locked out: it can't send, read or receive as you."
                   : out.state === "moving" ? (out.of > 0 ? out.sent + " of " + out.of + " parts there. " : "") + (out.note || "Keep both on the same Wi-Fi. This computer stays locked until the move is done, or an hour without the other device.")
                   : "You, your contacts and chats are on " + out.to + " now. Nothing of yours is left here.")
                : (inc.state === "done" ? "Everything is here. Huginn opens again in a moment as you."
                   : "Check these are the same on both screens. " + (inc.of > 0 ? inc.have + " of " + inc.of + " parts here." : "")
                     + (inc.confirmed ? " Taking it in as soon as everything is here." : ""))
        }
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Theme.px(12)
            HButton {
                id: matchBtn
                focused: Ui.main && Ui.main.padInUse && cover.buttons[cover.focusAt] === matchBtn
                visible: (cover.away && out.state === "checking") || (cover.here && inc.state !== "done" && !inc.confirmed)
                text: "They match"; kind: "accent"
                onClicked: cover.away ? Mover.go() : Mover.confirmHere()
            }
            HButton {
                id: linkBtn
                focused: Ui.main && Ui.main.padInUse && cover.buttons[cover.focusAt] === linkBtn
                visible: cover.away && out.state === "moved" && out.canLink
                text: "Use as a linked device"; kind: "accent"
                onClicked: Mover.becomeLinked()
            }
            HButton {
                id: stopBtn
                focused: Ui.main && Ui.main.padInUse && cover.buttons[cover.focusAt] === stopBtn
                visible: cover.away && out.state === "moving"
                text: "Stop moving"
                // Needs the person's own password.
                onClicked: Ui.ask("Stop moving", "Type your Huginn password. This computer is then yours again, as it was.", "", "Stop",
                                  t => { if (!Mover.abort(t)) Ui.say("That isn't your password.", true) }, true)
            }
            HButton {
                id: freshBtn
                focused: Ui.main && Ui.main.padInUse && cover.buttons[cover.focusAt] === freshBtn
                visible: cover.away && out.state === "moved"
                text: "Start fresh"
                onClicked: Mover.startFresh()
            }
            HButton {
                id: cancelBtn
                focused: Ui.main && Ui.main.padInUse && cover.buttons[cover.focusAt] === cancelBtn
                visible: (cover.away && out.state === "checking") || (cover.here && inc.state !== "done")
                text: "Cancel"
                onClicked: cover.away ? Mover.cancelOut() : Mover.cancelHere()
            }
        }
    }
    Connections {
        target: Mover
        function onNotice(text, bad) { Ui.say(text, bad) }
        function onChanged() {
            if (Mover.linkAsk !== "" && cover.asked !== Mover.linkAsk) {
                cover.asked = Mover.linkAsk
                Ui.sure("Link " + Mover.linkAsk + "?", Mover.linkAsk + ", the device you just moved from, asks to show your chats as a linked device. Only say yes if it's in front of you.",
                        "Link", () => { cover.asked = ""; Mover.answerLink(true) }, false, false, () => { cover.asked = ""; Mover.answerLink(false) })
            }
        }
    }
    property string asked: ""
    property string checking: ""
    // Linked devices: the 4 emoji are checked on both screens before anything is mirrored.
    Connections {
        target: Linked
        function onChanged() {
            const u = Linked.unchecked()
            if (!u.length || u[0].key === cover.checking) return
            const d = u[0]
            cover.checking = d.key
            // The phone this device mirrors: Link your phone asks, with the emoji big.
            if (d.key === Linked.phone.key) { if (Ui.main) Ui.main.showLinkPhone(false); return }
            Ui.sure("Check the link with " + d.name, "These must be the same on both screens:\n\n" + d.emoji + "\n\nNothing is mirrored until you choose They match.",
                    "They match", () => { cover.checking = ""; Linked.confirm(d.key) }, false, false, () => { cover.checking = ""; Linked.unlink(d.key) })
        }
    }
}

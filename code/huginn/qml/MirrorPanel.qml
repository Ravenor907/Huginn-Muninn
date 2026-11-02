import QtQuick
import Huginn

// On a companion (linked devices, section 15): the phone's 1:1 chats, over the conversation. Up and Down pick a
// chat, Right goes into its messages (Up and Down pick one, A plays or saves its photo, voice, video or file),
// A answers through the phone, X asks it for everything again (Refresh, at most once in 10 minutes), B goes back.
// Opening never asks the phone for anything. History, photos, voice, video and files come over the home Wi-Fi; until then
// media shows as words with "On home Wi-Fi". A message sent from here shows at once as Sending, and as "Didn't send ·
// Retry" (or why) when the phone doesn't take it. Cap l1: group chats too (who wrote each message), with my role and the
// folders each chat is in; the call button says calls ring on the phone. A copy kept after unlinking is read only.
Rectangle {
    id: panel
    visible: false
    color: Theme.ground
    property int row: 0
    property string zone: "chats"     // "chats" or "talk"
    property int msgSel: -1
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    readonly property var chats: visible ? Linked.mirror : []
    readonly property var current: chats[row] || null
    readonly property var msgs: current ? (Linked.mirror, Linked.messages(current.peer)) : []
    readonly property var picked: zone === "talk" ? msgs[msgSel] || null : null
    readonly property var prompts: zone === "talk"
        ? [{ key: "a", label: picked && picked.failed ? "Retry" : picked && picked.media ? actionWord(picked) : "Write" }, { key: "x", label: "Refresh" }, { key: "b", label: "Back" }]
        : [{ key: "a", label: "Write" }, { key: "x", label: "Refresh" }, { key: "b", label: "Close" }]

    function open(peer) {
        row = 0; zone = "chats"; msgSel = -1
        visible = true
        if (peer) { const i = Linked.mirror.findIndex(c => c.peer === peer); if (i >= 0) row = i }
    }
    function close() { visible = false; zone = "chats"; msgSel = -1; if (Ui.main) Ui.main.restoreFocus() }
    // Unlinked with its copy kept: read only.
    readonly property bool kept: !!Linked.phone.gone
    function reply() {
        if (!current) return
        if (kept) { Ui.say("Unlinked. Link your phone again to send.", true); return }
        const c = current
        Ui.ask("Message " + c.name, "Sent by " + (Linked.phone.name || "your phone") + " as its own message, while it's online", "", "Send", t => {
            const r = Linked.send(c.peer, t)
            if (r !== "") Ui.say(r, true)
        }, false, true)
    }
    function refresh() { Ui.say(Linked.refresh()) }
    function stateWord(m) { return m.failed ? (m.err || "Didn't send") + " · Retry" : "Sending" }
    function actionWord(m) { return m.kind === "voice" ? (Voice.playing === m.media ? "Stop" : "Play") : "Save" }
    // A photo, voice, video or file that's here: voice plays, the rest is saved in Downloads.
    function act(m) {
        if (!m || !m.media || !current) return
        if (m.kind === "voice") { if (Voice.playing === m.media) Voice.stop(); else Voice.play(m.media); return }
        const r = Linked.saveMedia(current.peer, m.id)
        Ui.say(r === "" ? "Saved in Downloads." : r, r !== "")
    }
    function handleButton(b) {
        if (zone === "talk") {
            switch (b) {
            case "up": msgSel = Math.max(0, msgSel - 1); talk.positionViewAtIndex(msgSel, ListView.Contain); return true
            case "down": msgSel = Math.min(msgs.length - 1, msgSel + 1); talk.positionViewAtIndex(msgSel, ListView.Contain); return true
            case "a": if (picked && picked.failed) Linked.retrySend(picked.id); else if (picked && picked.media) act(picked); else reply(); return true
            case "x": refresh(); return true
            case "left": case "b": zone = "chats"; msgSel = -1; return true
            }
            return true
        }
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(chats.length - 1, row + 1); return true
        case "right": if (msgs.length) { zone = "talk"; msgSel = msgs.length - 1; talk.positionViewAtEnd() } return true
        case "a": reply(); return true
        case "x": refresh(); return true
        case "b": close(); return true
        }
        return true
    }
    onRowChanged: { msgSel = -1; if (zone === "talk") zone = "chats" }

    PageHeader {
        id: head
        width: parent.width
        z: 2   // the call button's tip shows over the lists
        title: (Linked.phone.name || "Your phone") + "'s chats" + (panel.kept ? " (kept copy)" : "")
        // Calls stay on the phone: the button only says so.
        Rectangle {
            id: callBtn
            visible: !!panel.current && !panel.kept
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(34); height: width
            radius: Theme.radius
            color: callHover.hovered ? Theme.raised : "transparent"
            Accessible.role: Accessible.Button
            Accessible.name: "Call"
            Accessible.description: "Calls ring on your phone"
            Accessible.onPressAction: Ui.say("Calls ring on your phone")
            Icon { anchors.centerIn: parent; name: "call"; size: Theme.px(18); color: callHover.hovered ? Theme.text : Theme.textMuted }
            HoverHandler { id: callHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: Ui.say("Calls ring on your phone") }
            Rectangle {
                visible: callHover.hovered
                z: 30
                anchors.top: parent.bottom; anchors.topMargin: Theme.px(6)
                anchors.right: parent.right
                width: tip.implicitWidth + Theme.px(16); height: tip.implicitHeight + Theme.px(10)
                radius: Theme.radius
                color: Theme.panel
                border.color: Theme.line
                Text { id: tip; anchors.centerIn: parent; textFormat: Text.PlainText; text: "Calls ring on your phone"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
            }
        }
        HButton { visible: !panel.kept; anchors.verticalCenter: parent.verticalCenter; height: Theme.px(34); fontSize: 14; text: "Refresh"; onClicked: panel.refresh() }
        HButton { anchors.verticalCenter: parent.verticalCenter; height: Theme.px(34); fontSize: 14; text: "Close"; onClicked: panel.close() }
    }
    ListView {
        id: side
        anchors.top: head.bottom; anchors.bottom: parent.bottom; anchors.left: parent.left
        width: Math.min(Theme.px(260), parent.width * 0.4)
        clip: true
        model: panel.chats
        delegate: Rectangle {
            width: side.width; height: Theme.px(56)
            color: index === panel.row ? Theme.tile : "transparent"
            border.width: index === panel.row && panel.pad && panel.zone === "chats" ? Theme.focusWidth : 0
            border.color: Theme.accent
            Column {
                anchors.fill: parent; anchors.margins: Theme.px(10)
                Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                       text: [modelData.role || "", modelData.folders || "", modelData.last || ""].filter(x => x !== "").join(" · ") }
            }
            TapHandler { onTapped: { panel.row = index; panel.zone = "chats" } }
        }
        Text {
            textFormat: Text.PlainText
            visible: panel.chats.length === 0
            anchors.centerIn: parent; width: parent.width - Theme.px(20); wrapMode: Text.WordWrap
            text: "Nothing yet. History comes over on home Wi-Fi, while the phone is on."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }
    ListView {
        id: talk
        anchors.top: head.bottom; anchors.bottom: send.top; anchors.left: side.right; anchors.right: parent.right
        anchors.margins: Theme.px(14)
        clip: true
        spacing: Theme.px(6)
        model: panel.msgs
        onCountChanged: if (panel.zone !== "talk") positionViewAtEnd()
        delegate: Rectangle {
            readonly property bool sel: panel.zone === "talk" && index === panel.msgSel
            width: talk.width
            height: body.implicitHeight + Theme.px(8)
            radius: Theme.radius
            color: sel ? Theme.tile : "transparent"
            border.width: sel && panel.pad ? Theme.focusWidth : 0
            border.color: Theme.accent
            Column {
                id: body
                x: Theme.px(6); y: Theme.px(4)
                width: parent.width - Theme.px(12)
                spacing: Theme.px(4)
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    elide: Text.ElideRight
                    text: (modelData.mine ? "You" : modelData.from ? modelData.from : panel.current ? panel.current.name : "") + " · " + Qt.formatTime(new Date(modelData.time), "h:mm AP")
                          + (modelData.pending ? " · " + panel.stateWord(modelData) : "")
                    color: modelData.failed ? Theme.danger : modelData.mine ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                    // Didn't send: a click sends it again (the phone never sends one twice).
                    TapHandler { enabled: !!modelData.failed; onTapped: Linked.retrySend(modelData.id) }
                }
                // A photo that's here.
                Image {
                    visible: modelData.kind === "photo" && modelData.media !== ""
                    width: Math.min(parent.width, Theme.px(260)); height: visible ? Math.max(Theme.px(40), width * (implicitWidth > 0 ? implicitHeight / implicitWidth : 0.75)) : 0
                    fillMode: Image.PreserveAspectFit
                    asynchronous: true
                    source: visible ? "image://thumb/" + modelData.media : ""
                    TapHandler { onTapped: panel.act(modelData) }
                }
                Text {
                    width: parent.width; wrapMode: Text.Wrap; textFormat: Text.PlainText
                    visible: !(modelData.kind === "photo" && modelData.media !== "")
                    text: modelData.text; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16)
                }
                // Voice, video and files: play or save once here; until then, where they come from.
                Row {
                    visible: modelData.kind !== ""
                    spacing: Theme.px(8)
                    HButton {
                        visible: modelData.media !== "" && modelData.kind !== "photo"
                        implicitHeight: Theme.px(28); fontSize: 12; kind: "accent"
                        text: panel.actionWord(modelData)
                        onClicked: panel.act(modelData)
                    }
                    Text {
                        textFormat: Text.PlainText
                        visible: modelData.media === ""
                        text: "On home Wi-Fi"
                        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.italic: true
                    }
                }
            }
        }
    }
    HButton {
        id: send
        anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: Theme.px(14)
        visible: !!panel.current && !panel.kept
        kind: "accent"
        text: panel.current ? "Message " + panel.current.name : ""
        onClicked: panel.reply()
    }
}

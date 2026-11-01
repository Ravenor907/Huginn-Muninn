import QtQuick
import Huginn

// Labs reports (the Huginn with the Labs key, from the Chats list's three dots): each bug report with who
// sent it, the app's version, system and device type, the text and the screenshot. Reply makes the sender a
// contact and opens the chat; Delete removes the report. Up and Down pick one, A replies, X deletes, B closes.
Item {
    id: inbox
    visible: false
    anchors.fill: parent
    property bool tour: false
    property int at: 0
    readonly property var tourReports: [
        { id: "t1", key: "", name: "Raven 4821", app: "huginn", label: "0.99 beta 3", os: "Linux", model: "linux x86_64",
          text: "The chat list jumps to the top when a photo arrives.", hasShot: false, time: Date.now() - 3600000 },
        { id: "t2", key: "", name: "Mira", app: "muninn", label: "0.99 beta 3", os: "Android 15", model: "android arm64",
          text: "Voice messages stop after a minute.", hasShot: false, time: Date.now() - 86400000 }
    ]
    readonly property var list: tour ? tourReports : Labs.reports
    readonly property bool padFocus: !!Ui.main && Ui.main.padInUse

    function open(asTour) { tour = !!asTour; at = 0; view.contentY = 0; visible = true }
    function close() { visible = false; if (Ui.main) Ui.main.restoreFocus() }
    function reply(r) {
        if (tour || !r) return
        const k = Labs.reply(r.id)
        if (k === "") { Ui.say("Can't reply to this one.", true); return }
        close()
        Ui.openPage("dms", { open: k })
    }
    function remove(r) {
        if (tour || !r) return
        Ui.sure("Delete this report?", "Can't be undone.", "Delete", () => Labs.remove(r.id), false, true)
    }
    function reveal() {
        const item = cards.itemAt(at)
        if (!item) return
        const y = item.mapToItem(col, 0, 0).y
        if (y < view.contentY) view.contentY = y
        else if (y + item.height > view.contentY + view.height) view.contentY = Math.min(Math.max(0, view.contentHeight - view.height), y + item.height - view.height)
    }
    function handleButton(b) {
        switch (b) {
        case "up": at = Math.max(0, at - 1); reveal(); break
        case "down": at = Math.max(0, Math.min(list.length - 1, at + 1)); reveal(); break
        case "a": reply(list[at]); break
        case "x": remove(list[at]); break
        case "b": close(); break
        }
        return true
    }
    onListChanged: if (at >= list.length) at = Math.max(0, list.length - 1)

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: inbox.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(760))
        height: parent.height - Theme.px(64)
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
                text: "LABS REPORTS"
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
            }
            Text {
                textFormat: Text.PlainText
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                text: "A reply, X delete, B close"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }
        Text {
            textFormat: Text.PlainText
            visible: inbox.list.length === 0
            anchors.centerIn: parent
            text: "No reports yet."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        Flickable {
            id: view
            anchors.top: head.bottom; anchors.topMargin: Theme.px(16)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(16)
            contentHeight: col.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: col
                width: view.width
                spacing: Theme.px(12)
                Repeater {
                    id: cards
                    model: inbox.list
                    Rectangle {
                        id: card
                        readonly property var r: modelData
                        readonly property bool lit: inbox.at === index && inbox.padFocus
                        width: col.width
                        height: cardCol.implicitHeight + Theme.px(24)
                        radius: Theme.radius
                        color: lit ? Theme.focusFill : Theme.raised
                        border.width: lit ? Theme.focusWidth : 1
                        border.color: lit ? Theme.accent : Theme.line
                        TapHandler { onTapped: inbox.at = index }
                        Column {
                            id: cardCol
                            x: Theme.px(12); y: Theme.px(12)
                            width: parent.width - Theme.px(24)
                            spacing: Theme.px(6)
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width
                                elide: Text.ElideRight
                                text: card.r.name + "  ·  " + (card.r.app === "muninn" ? "Muninn " : "Huginn ") + card.r.label
                                color: Theme.text
                                font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                            }
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width
                                wrapMode: Text.Wrap
                                text: [card.r.os, card.r.model, Qt.formatDateTime(new Date(card.r.time), "d MMM, HH:mm")].filter(t => !!t).join("  ·  ")
                                color: Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width
                                wrapMode: Text.Wrap
                                text: card.r.text || "(no text)"
                                color: Theme.textSoft
                                font.family: Theme.body; font.pixelSize: Theme.fs(15)
                            }
                            Image {
                                visible: card.r.hasShot
                                width: parent.width
                                height: visible ? Math.round(width * 9 / 16) : 0
                                fillMode: Image.PreserveAspectFit
                                asynchronous: true
                                source: card.r.hasShot && !inbox.tour ? Labs.shotOf(card.r.id) : ""
                            }
                            Row {
                                spacing: Theme.px(10)
                                HButton { text: "Reply"; onClicked: inbox.reply(card.r) }
                                HButton { text: "Delete"; kind: "danger"; onClicked: inbox.remove(card.r) }
                            }
                        }
                    }
                }
            }
        }
    }
}

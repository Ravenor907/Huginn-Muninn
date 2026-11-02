import QtQuick
import Huginn

// Labs › App storage: files Huginn keeps in its own folders that can go (update downloads, caches, the crash log).
// Only AppStorage's allowlist is ever listed; the vault, keys, chats and settings never are. The total, Delete all
// received files and Clear caches (each asks first), then each file with Delete. Up and Down pick one, A deletes it,
// X clears caches, B closes.
Item {
    id: panel
    visible: false
    anchors.fill: parent
    property bool tour: false
    property int at: 0
    readonly property var tourItems: [
        { id: "t1", name: "Huginn-v1.1.flatpak", size: 4200000, at: Date.now() - 86400000, kind: "update", word: "Update", folder: "updates" },
        { id: "t2", name: "crash.log", size: 2048, at: Date.now() - 3 * 86400000, kind: "crash", word: "Crash log", folder: "crash log" },
        { id: "t3", name: "a1b2c3.qmlc", size: 18000, at: Date.now() - 9 * 86400000, kind: "cache", word: "Cache", folder: "qmlcache" }
    ]
    readonly property var list: tour ? tourItems : AppStorage.items
    readonly property double total: tour ? tourItems.reduce((n, i) => n + i.size, 0) : AppStorage.total
    readonly property bool hasReceived: list.some(i => i.kind === "received")
    readonly property bool hasCaches: list.some(i => i.kind === "cache")
    readonly property bool padFocus: !!Ui.main && Ui.main.padInUse

    function open(asTour) {
        tour = !!asTour
        if (!tour) AppStorage.refresh()
        at = 0; view.contentY = 0; visible = true
    }
    function close() { visible = false; if (Ui.main) Ui.main.restoreFocus() }
    function remove(i) {
        if (tour || !i) return
        Ui.sure("Delete " + i.name + "?", "Can't be undone.", "Delete", () => Ui.say(AppStorage.remove(i.id) ? "Deleted." : "Couldn't delete.", false), true, true)
    }
    function removeReceived() {
        if (tour || !hasReceived) return
        Ui.sure("Delete all received files?", "Only files kept inside Huginn.", "Delete",
                () => Ui.say(AppStorage.removeKind("received") + " deleted."), true, true)
    }
    function clearCaches() {
        if (tour || !hasCaches) return
        Ui.sure("Clear caches?", "Huginn makes them again when needed.", "Clear", () => { AppStorage.removeKind("cache"); Ui.say("Caches cleared.") }, false, true)
    }
    function reveal() {
        const item = rows.itemAt(at)
        if (!item) return
        const y = item.mapToItem(col, 0, 0).y
        if (y < view.contentY) view.contentY = y
        else if (y + item.height > view.contentY + view.height) view.contentY = Math.min(Math.max(0, view.contentHeight - view.height), y + item.height - view.height)
    }
    function handleButton(b) {
        switch (b) {
        case "up": at = Math.max(0, at - 1); reveal(); break
        case "down": at = Math.max(0, Math.min(list.length - 1, at + 1)); reveal(); break
        case "a": remove(list[at]); break
        case "x": clearCaches(); break
        case "b": close(); break
        }
        return true
    }
    onListChanged: if (at >= list.length) at = Math.max(0, list.length - 1)

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

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
                text: "APP STORAGE"
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
            }
            Text {
                textFormat: Text.PlainText
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                text: "A delete, X clear caches, B close"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }
        Column {
            id: summary
            anchors.top: head.bottom; anchors.topMargin: Theme.px(12)
            x: Theme.px(24)
            width: parent.width - Theme.px(48)
            spacing: Theme.px(10)
            Text {
                textFormat: Text.PlainText
                text: "Total " + System.sizeText(panel.total) + "  ·  " + (panel.list.length === 1 ? "1 file" : panel.list.length + " files")
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
            }
            Row {
                spacing: Theme.px(10)
                HButton { text: "Delete all received files"; kind: "danger"; enabled: panel.hasReceived && !AppStorage.busy; onClicked: panel.removeReceived() }
                HButton { text: "Clear caches"; enabled: panel.hasCaches && !AppStorage.busy; onClicked: panel.clearCaches() }
            }
        }
        Text {
            textFormat: Text.PlainText
            visible: panel.list.length === 0
            anchors.centerIn: parent
            text: "Nothing to clear."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        Flickable {
            id: view
            anchors.top: summary.bottom; anchors.topMargin: Theme.px(16)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(16)
            contentHeight: col.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: col
                width: view.width
                spacing: Theme.px(8)
                Repeater {
                    id: rows
                    model: panel.list
                    Rectangle {
                        id: card
                        readonly property var f: modelData
                        readonly property bool lit: panel.at === index && panel.padFocus
                        width: col.width
                        height: Math.max(cardCol.implicitHeight, del.height) + Theme.px(20)
                        radius: Theme.radius
                        color: lit ? Theme.focusFill : Theme.raised
                        border.width: lit ? Theme.focusWidth : 1
                        border.color: lit ? Theme.accent : Theme.line
                        TapHandler { onTapped: panel.at = index }
                        Column {
                            id: cardCol
                            x: Theme.px(12); y: Theme.px(10)
                            width: parent.width - del.width - Theme.px(36)
                            spacing: Theme.px(4)
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width
                                elide: Text.ElideMiddle
                                text: card.f.name
                                color: Theme.text
                                font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                            }
                            Text {
                                textFormat: Text.PlainText
                                width: parent.width
                                elide: Text.ElideRight
                                text: [card.f.word, System.sizeText(card.f.size), Qt.formatDateTime(new Date(card.f.at), "d MMM yyyy"), card.f.folder].join("  ·  ")
                                color: Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                        }
                        HButton {
                            id: del
                            anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                            anchors.verticalCenter: parent.verticalCenter
                            text: "Delete"; kind: "danger"
                            enabled: !AppStorage.busy
                            onClicked: panel.remove(card.f)
                        }
                    }
                }
            }
        }
    }
}

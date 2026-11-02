import QtQuick
import QtQuick.Controls.Basic
import Huginn

// Ctrl+K: type part of a name, Up and Down to choose, Enter to go. Chats and every text
// channel of every group, the ones with something unread first.
Item {
    id: sw
    visible: false
    anchors.fill: parent

    function open() {
        if (Vault.screenLocked || !Vault.exists) { Ui.say("Unlock Huginn first.", true); return }
        field.text = ""
        row = 0
        visible = true
        field.forceActiveFocus()
    }
    function close() {
        visible = false
        if (Ui.main) Ui.main.restoreFocus()
    }
    property int row: 0
    readonly property var everything: {
        if (!visible) return []
        const out = []
        Chat.chats.forEach(c => out.push({ kind: "dm", key: c.key, name: c.name, detail: "Direct message", unread: !!c.unread }))
        Chat.groups.forEach(g => {
            const chans = Chat.groupInfo(g.id).channels || []
            chans.filter(c => c.type === "text").forEach(c => out.push({ kind: "ch", g: g.id, ch: c.id, name: "#" + c.name,
                detail: g.name, unread: c.unread > 0 }))
        })
        return out
    }
    readonly property var shown: {
        const q = field.text.trim().toLowerCase()
        const hit = everything.filter(e => q === "" || e.name.toLowerCase().indexOf(q) >= 0 || e.detail.toLowerCase().indexOf(q) >= 0)
        hit.sort((a, b) => (b.unread ? 1 : 0) - (a.unread ? 1 : 0))
        // Nobody by that name here: add them.
        if (!hit.length && visible) return [{ kind: "add", name: "Add someone", detail: "Their code or link" }]
        return hit.slice(0, 12)
    }
    function go(e) {
        if (!e) return
        close()
        if (e.kind === "add") Ui.openPage("dms", { add: true })
        else if (e.kind === "dm") Ui.openPage("dms", { open: e.key })
        else Ui.openPage("g:" + e.g, { channel: e.ch, zone: "messages" })
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.7) }
    MouseArea { anchors.fill: parent; onClicked: sw.close() }

    Rectangle {
        anchors.horizontalCenter: parent.horizontalCenter
        // Near the top, and never past the foot of the window: the hits scroll when they don't fit.
        y: Math.max(Theme.px(16), Math.min(Theme.px(90), parent.height - height - Theme.px(16)))
        width: Math.min(parent.width - Theme.px(32), Theme.px(620))
        height: col.implicitHeight + Theme.px(28)
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.lineStrong
        MouseArea { anchors.fill: parent }
        Column {
            id: col
            x: Theme.px(14); y: Theme.px(14)
            width: parent.width - Theme.px(28)
            spacing: Theme.px(8)
            Text { textFormat: Text.PlainText; id: askText; width: parent.width; elide: Text.ElideRight; text: "Go to a chat or channel"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            TextField {
                id: field
                FieldMenu {}   // right click: Cut, Copy, Paste, Select all
                width: parent.width
                height: Math.max(Theme.px(46), implicitHeight)
                leftPadding: Theme.px(14)
                color: Theme.text
                placeholderText: "Who or where?"
                placeholderTextColor: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.fs(18)
                background: Rectangle { radius: Theme.radius; color: Theme.ground; border.color: Theme.accent }
                onTextChanged: sw.row = 0
                Keys.onPressed: event => {
                    if (event.key === Qt.Key_Down) { sw.row = Math.min(sw.shown.length - 1, sw.row + 1); event.accepted = true }
                    else if (event.key === Qt.Key_Up) { sw.row = Math.max(0, sw.row - 1); event.accepted = true }
                    else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) { sw.go(sw.shown[sw.row]); event.accepted = true }
                    else if (event.key === Qt.Key_Escape) { sw.close(); event.accepted = true }
                }
            }
            ListView {
                id: hits
                width: parent.width
                visible: sw.shown.length > 0
                // Counted from the rows (each the same height), not read back from contentHeight, which
                // a ListView estimates from the rows it has made so far.
                readonly property real rowsHeight: sw.shown.length * Theme.px(44) + Math.max(0, sw.shown.length - 1) * spacing
                height: Math.max(0, Math.min(rowsHeight, sw.height - Theme.px(32) - Theme.px(28) - askText.height - field.height - col.spacing * 2))
                clip: true
                boundsBehavior: Flickable.StopAtBounds
                interactive: contentHeight > height
                spacing: col.spacing
                model: sw.shown
                currentIndex: sw.row
                highlightFollowsCurrentItem: false
                onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
                delegate: Rectangle {
                    width: hits.width
                    height: Theme.px(44)
                    radius: Theme.radius
                    color: index === sw.row ? Theme.tile : hitHover.hovered ? Theme.raised : "transparent"
                    border.width: index === sw.row ? 1 : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.ListItem
                    Accessible.name: modelData.name + ", " + modelData.detail
                    // A person's picture, or a hash for a channel.
                    Item {
                        id: hitIcon
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(26); height: width
                        Avatar { visible: modelData.kind === "dm"; anchors.fill: parent; size: parent.width; key: modelData.key || ""; name: modelData.name }
                        Icon { visible: modelData.kind !== "dm"; anchors.centerIn: parent; name: "hash"; size: Theme.px(18); color: Theme.textMuted }
                    }
                    Text {
                        textFormat: Text.PlainText
                        anchors.left: hitIcon.right; anchors.leftMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        width: parent.width * 0.5
                        elide: Text.ElideRight
                        text: modelData.name
                        color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: modelData.unread ? Font.Bold : Font.Normal
                    }
                    Text {
                        textFormat: Text.PlainText
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        width: parent.width * 0.35
                        horizontalAlignment: Text.AlignRight
                        elide: Text.ElideRight
                        text: modelData.detail
                        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
                    }
                    HoverHandler { id: hitHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: sw.go(modelData) }
                }
            }
            Text { textFormat: Text.PlainText; visible: sw.shown.length === 0; text: "Nothing matches."; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
        }
    }
    function handleButton(b) {
        if (b === "b") close()
        else if (b === "a") go(shown[row])
        else if (b === "up") row = Math.max(0, row - 1)
        else if (b === "down") row = Math.min(shown.length - 1, row + 1)
        return true
    }
}

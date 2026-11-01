import QtQuick
import QtQuick.Controls.Basic
import Huginn

// Find in this chat (the search icon in a chat's header, or Ctrl+F): the messages kept here that
// have the words typed, newest first. Up or Enter goes to an older one, Down or Shift+Enter to a newer
// one, Esc closes. The page lights up the matches (hits) and the one shown (current), and scrolls to it.
// With a controller: Up and Down step, A types, B closes.
Rectangle {
    id: bar
    property var messages: []
    property color accent: Theme.accent
    // The message index to scroll to.
    signal jump(int index)
    signal closed()
    // Open (it shows while its page does).
    property bool shown: false
    property alias text: field.text

    readonly property string query: field.text.trim().toLowerCase()
    // The matching messages' indexes, oldest first.
    readonly property var hits: {
        if (!shown || query === "") return []
        const out = []
        for (let i = 0; i < messages.length; i++) {
            const m = messages[i]
            if (!m || m.system || m.unsent) continue
            if (String(m.text || "").toLowerCase().indexOf(query) >= 0) out.push(i)
        }
        return out
    }
    // Which match is shown, counted from the newest (0).
    property int at: 0
    readonly property int current: hits.length ? hits[hits.length - 1 - Math.min(at, hits.length - 1)] : -1

    function open() {
        shown = true
        field.forceActiveFocus()
        field.selectAll()
    }
    function close() {
        if (!shown) return
        shown = false
        field.text = ""
        System.hideSteamKeyboard()
        bar.closed()
    }
    function older() { if (hits.length) { at = Math.min(hits.length - 1, at + 1); bar.jump(current) } }
    function newer() { if (hits.length) { at = Math.max(0, at - 1); bar.jump(current) } }
    // Typing starts again from the newest match.
    onQueryChanged: { at = 0; if (hits.length) bar.jump(current) }
    // The controller while the bar is open.
    function handleButton(b) {
        switch (b) {
        case "up": older(); return true
        case "down": newer(); return true
        case "a": field.forceActiveFocus(); System.showSteamKeyboard(); return true
        case "b": close(); return true
        }
        return false
    }

    visible: shown
    height: Theme.px(44)
    radius: Theme.radius
    color: Theme.panel
    border.width: 1
    border.color: field.activeFocus ? bar.accent : Theme.line

    Icon {
        id: lens
        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        name: "search"; size: Theme.px(18); color: Theme.textMuted
    }
    TextField {
        id: field
        anchors.left: lens.right; anchors.leftMargin: Theme.px(4)
        anchors.right: countText.left; anchors.rightMargin: Theme.px(8)
        anchors.verticalCenter: parent.verticalCenter
        placeholderText: "Find in this chat"
        placeholderTextColor: Theme.textMuted
        color: Theme.text
        selectionColor: Theme.accentFill
        selectedTextColor: Theme.text
        font.family: Theme.body
        font.pixelSize: Theme.fs(15)
        background: null
        Accessible.name: "Find in this chat"
        Keys.onPressed: event => {
            if (event.key === Qt.Key_Escape) { bar.close(); event.accepted = true }
            else if (event.key === Qt.Key_Up) { bar.older(); event.accepted = true }
            else if (event.key === Qt.Key_Down) { bar.newer(); event.accepted = true }
            else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                if (event.modifiers & Qt.ShiftModifier) bar.newer(); else bar.older()
                event.accepted = true
            }
        }
    }
    Text {
        id: countText
        textFormat: Text.PlainText
        anchors.right: upBtn.left; anchors.rightMargin: Theme.px(6)
        anchors.verticalCenter: parent.verticalCenter
        visible: bar.query !== ""
        text: bar.hits.length ? (Math.min(bar.at, bar.hits.length - 1) + 1) + " of " + bar.hits.length : "No matches"
        color: bar.hits.length ? Theme.textSoft : Theme.textMuted
        font.family: Theme.body; font.pixelSize: Theme.fs(13)
    }
    component BarButton: Rectangle {
        id: bb
        property string icon: "down"
        property real turn: 0
        property string label: ""
        property bool on: true
        signal clicked()
        width: Theme.px(30); height: width
        radius: Theme.radius
        opacity: on ? 1 : 0.4
        color: bbHover.hovered && on ? Theme.raised : "transparent"
        Accessible.role: Accessible.Button
        Accessible.name: label
        Accessible.onPressAction: if (on) bb.clicked()
        Icon { anchors.centerIn: parent; name: bb.icon; rotation: bb.turn; size: Theme.px(16); color: bbHover.hovered && bb.on ? Theme.text : Theme.textMuted }
        HoverHandler { id: bbHover; cursorShape: bb.on ? Qt.PointingHandCursor : Qt.ArrowCursor }
        TapHandler { onTapped: if (bb.on) bb.clicked() }
    }
    BarButton {
        id: upBtn
        anchors.right: downBtn.left
        anchors.verticalCenter: parent.verticalCenter
        icon: "down"; turn: 180; label: "Older match"
        on: bar.hits.length > 0 && bar.at < bar.hits.length - 1
        onClicked: bar.older()
    }
    BarButton {
        id: downBtn
        anchors.right: closeBtn.left
        anchors.verticalCenter: parent.verticalCenter
        icon: "down"; label: "Newer match"
        on: bar.hits.length > 0 && bar.at > 0
        onClicked: bar.newer()
    }
    BarButton {
        id: closeBtn
        anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
        anchors.verticalCenter: parent.verticalCenter
        icon: "close"; label: "Close find"
        onClicked: bar.close()
    }
}

import QtQuick
import Huginn

// The reactions under a message: each emoji once with how many chose it; mine is outlined. A tap on
// one gives that reaction too (or takes mine back); [add] shows a + to pick another.
Flow {
    id: row
    property var list: []                 // [{e, n, mine}]
    property bool add: false
    signal toggled(string e, bool mine)
    signal more()
    spacing: Theme.px(6)
    visible: list.length > 0
    Repeater {
        model: row.list
        Rectangle {
            height: Math.max(Theme.px(28), chip.implicitHeight + Theme.px(6))
            width: chip.implicitWidth + Theme.px(16)
            radius: height / 2
            color: modelData.mine ? Theme.accentFill : chipHover.hovered ? Theme.raised : Theme.tile
            border.width: modelData.mine ? 1 : 0
            border.color: Theme.accent
            Accessible.role: Accessible.Button
            Accessible.name: modelData.e + " " + modelData.n + (modelData.mine ? ", yours: take it back" : "")
            Row {
                id: chip
                anchors.centerIn: parent
                spacing: Theme.px(4)
                Text { textFormat: Text.PlainText; text: modelData.e; font.pixelSize: Theme.fs(14); anchors.verticalCenter: parent.verticalCenter }
                Text { textFormat: Text.PlainText; text: modelData.n; color: modelData.mine ? Theme.accent : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13); anchors.verticalCenter: parent.verticalCenter }
            }
            HoverHandler { id: chipHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: row.toggled(modelData.e, !!modelData.mine) }
        }
    }
    Rectangle {
        visible: row.add
        height: Theme.px(28); width: Theme.px(36)
        radius: height / 2
        color: addHover.hovered ? Theme.raised : Theme.tile
        Accessible.role: Accessible.Button
        Accessible.name: "Add a reaction"
        Icon { anchors.centerIn: parent; name: "smile"; size: Theme.px(15); color: Theme.textMuted }
        HoverHandler { id: addHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: row.more() }
    }
}

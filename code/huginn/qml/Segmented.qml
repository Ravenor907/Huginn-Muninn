import QtQuick
import QtQuick.Controls.Basic
import Huginn

// A row of exclusive choices. `options` is a list of { value, label }.
// Every choice gets the same width: the widest label at the current text size, never cut short. Given less than
// that (a narrow window, a big text size), it becomes a dropdown showing the current choice. A row that places it
// beside a label (Settings) moves it under the label first, at full width, when it needs more than half the row.
Rectangle {
    id: seg
    property var options: []
    property string value
    property real cell: 0      // a fixed width for every choice (a dialog fills its width with them)
    signal picked(string value)

    // The widest label, read from the same Text the choices draw (so a font that loads late still counts).
    Column {
        id: probe
        opacity: 0
        enabled: false
        Accessible.ignored: true
        Repeater {
            model: seg.options
            Text { textFormat: Text.PlainText; text: modelData.label || ""; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold }
        }
    }
    readonly property int count: Math.max(1, options.length)
    readonly property real cellNeed: Math.max(cell, Math.ceil(probe.implicitWidth) + Theme.px(28))
    // The width every choice needs at its full label, side by side.
    readonly property real naturalWidth: cellNeed * count + 2
    // Too narrow for that: a dropdown.
    readonly property bool dropdown: width < naturalWidth - 0.5
    readonly property bool squeezed: dropdown
    readonly property real cellWidth: Math.max(cellNeed, (width - 2) / count)
    FontMetrics { id: metrics; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold }
    implicitWidth: naturalWidth
    implicitHeight: Math.max(Theme.tapMin, Math.ceil(metrics.height) + Theme.px(12))
    radius: Theme.radius
    color: "transparent"
    border.color: Theme.lineStrong
    // The inside corners, one pixel in from the border.
    readonly property real inner: Math.max(0, radius - 1)
    readonly property int current: options.findIndex(o => o.value === value)

    Row {
        id: row
        visible: !seg.dropdown
        x: 1; y: 1
        Repeater {
            model: seg.options
            Item {
                id: cellItem
                readonly property bool on: modelData.value === seg.value
                readonly property bool first: index === 0
                readonly property bool last: index === seg.options.length - 1
                width: seg.cellWidth
                height: Math.max(0, seg.height - 2)
                // The highlight follows the pill: rounded on the outer ends of the first and last choice, square
                // where it meets a neighbor.
                Rectangle {
                    visible: cellItem.on
                    anchors.fill: parent
                    radius: cellItem.first || cellItem.last ? seg.inner : 0
                    color: Theme.accentFill
                }
                Rectangle {
                    visible: cellItem.on && seg.inner > 0 && (cellItem.first !== cellItem.last)
                    width: parent.width / 2; height: parent.height
                    x: cellItem.first ? parent.width / 2 : 0
                    color: Theme.accentFill
                }
                Text {
                    textFormat: Text.PlainText
                    anchors.centerIn: parent
                    text: modelData.label
                    color: cellItem.on ? Theme.accent : Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(14)
                    font.weight: Font.DemiBold
                }
                Accessible.role: Accessible.RadioButton
                Accessible.name: modelData.label || ""
                Accessible.checked: cellItem.on
                TapHandler { onTapped: seg.picked(modelData.value) }
            }
        }
    }

    // The dropdown: the current choice and an arrow; a tap lists every choice.
    Item {
        anchors.fill: parent
        visible: seg.dropdown
        Text {
            textFormat: Text.PlainText
            anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
            anchors.right: arrow.left; anchors.rightMargin: Theme.px(8)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideRight
            text: seg.current >= 0 ? seg.options[seg.current].label : ""
            color: Theme.accent
            font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
        }
        Icon { id: arrow; anchors.right: parent.right; anchors.rightMargin: Theme.px(10); anchors.verticalCenter: parent.verticalCenter; name: "down"; size: Theme.px(16); color: Theme.textSoft }
        Accessible.role: Accessible.ComboBox
        Accessible.name: seg.current >= 0 ? seg.options[seg.current].label : ""
        Accessible.onPressAction: list.open()
        TapHandler { onTapped: list.open() }
    }
    Popup {
        id: list
        y: seg.height + Theme.px(4)
        width: seg.width
        padding: 1
        background: Rectangle { color: Theme.raised; border.color: Theme.lineStrong; radius: Theme.radius }
        contentItem: Column {
            Repeater {
                model: seg.options
                Rectangle {
                    width: list.width - 2
                    height: Math.max(Theme.tapMin, label.implicitHeight + Theme.px(12))
                    radius: seg.inner
                    color: modelData.value === seg.value ? Theme.accentFill : "transparent"
                    Text {
                        id: label
                        textFormat: Text.PlainText
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        wrapMode: Text.WordWrap
                        text: modelData.label
                        color: modelData.value === seg.value ? Theme.accent : Theme.text
                        font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                    }
                    TapHandler { onTapped: { list.close(); seg.picked(modelData.value) } }
                }
            }
        }
    }

    function step(dir) {
        let i = options.findIndex(o => o.value === value)
        i = Math.max(0, Math.min(options.length - 1, i + dir))
        picked(options[i].value)
    }
}

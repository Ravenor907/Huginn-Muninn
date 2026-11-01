import QtQuick
import Huginn

// A row of exclusive choices. `options` is a list of { value, label }.
Rectangle {
    id: seg
    property var options: []
    property string value
    property real cell: 0      // a fixed width for every choice (a dialog fills its width with them)
    signal picked(string value)

    // The width every choice needs at its full label. Given less, the choices share it evenly and
    // their labels are cut short.
    readonly property real naturalWidth: options.reduce((w, o) => w + Math.max(Math.ceil(metrics.advanceWidth(o.label || "")) + Theme.px(28), cell), 0) + 2
    readonly property bool squeezed: width < naturalWidth - 0.5
    FontMetrics { id: metrics; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold }
    implicitWidth: naturalWidth
    implicitHeight: Math.max(Theme.px(36), Math.ceil(metrics.height) + Theme.px(12))
    radius: Theme.radius
    color: "transparent"
    border.color: Theme.lineStrong
    clip: true

    Row {
        id: row
        anchors.centerIn: parent
        Repeater {
            model: seg.options
            Rectangle {
                readonly property bool on: modelData.value === seg.value
                width: seg.squeezed ? Math.max(0, Math.floor((seg.width - 2) / Math.max(1, seg.options.length))) : Math.max(txt.implicitWidth + Theme.px(28), seg.cell)
                height: Math.max(0, seg.height - 2)
                color: on ? Theme.accentFill : "transparent"
                Text {
                    textFormat: Text.PlainText
                    id: txt
                    anchors.centerIn: parent
                    width: Math.max(0, Math.min(implicitWidth, parent.width - Theme.px(8)))
                    elide: Text.ElideRight
                    text: modelData.label
                    color: parent.on ? Theme.accent : Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(14)
                    font.weight: Font.DemiBold
                }
                TapHandler { onTapped: seg.picked(modelData.value) }
            }
        }
    }

    function step(dir) {
        let i = options.findIndex(o => o.value === value)
        i = Math.max(0, Math.min(options.length - 1, i + dir))
        picked(options[i].value)
    }
}

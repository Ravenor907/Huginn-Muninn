import QtQuick
import QtQuick.Shapes
import Huginn

// A handle on the edge between two panels (the desktop only): two small spikes, one at the top and
// one at the bottom of the edge. Drag either to make the panel wider or narrower; Huginn keeps the
// width once it's let go. While the pointer is on a spike or dragging, a thin guide line shows along
// the edge. A double click on a spike puts the panel back as it was at first.
Item {
    id: split
    property int value: 0          // the panel's width now, in Normal-size pixels
    property int minimum: 0
    property int maximum: 1000
    property int fallback: 0       // what a double click goes back to
    property int direction: 1      // 1: dragging right widens the panel; -1: dragging right narrows it
    property string label: "panel"
    signal moved(int v)            // while it's dragged
    signal done(int v)             // let go: keep it
    property int startValue: 0
    readonly property bool dragging: topSpike.dragging || bottomSpike.dragging
    readonly property bool hot: dragging || topSpike.hovered || bottomSpike.hovered

    width: Theme.px(12)
    visible: Theme.desktop
    Accessible.role: Accessible.Separator
    Accessible.name: "Drag to resize the " + label

    // The guide: 1 px along the edge, dim, only while a spike is pointed at or dragged.
    Rectangle {
        anchors.horizontalCenter: parent.horizontalCenter
        width: 1
        height: parent.height
        visible: split.hot
        color: Theme.accent
        opacity: split.dragging ? 0.55 : 0.35
    }
    Spike { id: topSpike; anchors.top: parent.top; down: true }
    Spike { id: bottomSpike; anchors.bottom: parent.bottom; down: false }

    function dragTo(dx) {
        const v = Math.round(split.startValue + split.direction * dx / Theme.s)
        split.moved(Math.max(split.minimum, Math.min(split.maximum, v)))
    }

    // A small triangle, pointing in along the edge: grey, gold while pointed at or dragged.
    component Spike: Item {
        id: spike
        property bool down: true
        readonly property bool hovered: spikeHover.hovered
        readonly property bool dragging: grab.active
        readonly property bool lit: hovered || dragging
        // The grab is a little bigger than the triangle it shows.
        width: split.width; height: Theme.px(14)
        Accessible.role: Accessible.Button
        Accessible.name: "Drag to resize the " + split.label + "; double click puts it back"
        Accessible.onPressAction: { split.moved(split.fallback); split.done(split.fallback) }
        Shape {
            id: tri
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: spike.down ? parent.top : undefined
            anchors.bottom: spike.down ? undefined : parent.bottom
            width: Theme.px(10); height: Theme.px(10)
            preferredRendererType: Shape.CurveRenderer
            ShapePath {
                strokeColor: "transparent"
                fillColor: spike.lit ? Theme.accent : Theme.lineMuted
                startX: 0; startY: spike.down ? 0 : tri.height
                PathLine { x: tri.width; y: spike.down ? 0 : tri.height }
                PathLine { x: tri.width / 2; y: spike.down ? tri.height : 0 }
                PathLine { x: 0; y: spike.down ? 0 : tri.height }
            }
        }
        HoverHandler { id: spikeHover; cursorShape: Qt.SplitHCursor }
        DragHandler {
            id: grab
            target: null
            yAxis.enabled: false
            cursorShape: Qt.SplitHCursor
            onActiveChanged: {
                if (active) split.startValue = split.value
                else split.done(split.value)
            }
            onActiveTranslationChanged: if (active) split.dragTo(activeTranslation.x)
        }
        TapHandler { onDoubleTapped: { split.moved(split.fallback); split.done(split.fallback) } }
    }
}

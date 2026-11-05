import QtQuick
import Huginn

// Someone's status dot, cut out of what it sits on (backdrop). Each status has its own shape, so it never depends on
// color alone: online is filled, away is a hollow ring, do not disturb is a minus, offline is a plain gray dot.
Rectangle {
    id: dot
    property string status
    property color backdrop: Theme.ground
    readonly property color fill: status === "online" ? Theme.online : status === "away" ? Theme.away : status === "dnd" ? Theme.dnd : Theme.offline
    width: Theme.px(12); height: width
    radius: width / 2
    color: backdrop
    Rectangle {
        id: core
        anchors.centerIn: parent
        width: Math.max(4, Math.round(dot.width * 0.6)); height: width
        radius: width / 2
        color: dot.status === "away" ? dot.backdrop : dot.fill
        border.width: dot.status === "away" ? Math.max(2, Math.round(width * 0.26)) : 0
        border.color: dot.fill
    }
    Rectangle {
        visible: dot.status === "dnd"
        anchors.centerIn: parent
        width: Math.max(3, Math.round(core.width * 0.64)); height: Math.max(2, Math.round(core.width * 0.2))
        color: dot.backdrop
    }
}

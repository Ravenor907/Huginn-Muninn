import QtQuick
import Huginn

// What can be done with a message, over its top right corner while the pointer (a mouse or the
// right stick's) is on it, or while the controller has it picked, Discord style: react, reply, a
// thread, edit (mine), pin, and everything else.
Rectangle {
    id: bar
    property bool canEdit: false
    property bool canThread: false
    property bool canPin: false
    property bool pinnedNow: false
    signal react()
    signal reply()
    signal thread()
    signal edit()
    signal pin()
    signal more()
    height: Theme.px(32)
    width: icons.implicitWidth + Theme.px(8)
    radius: Theme.radius
    color: Theme.panel
    border.color: Theme.line
    readonly property var actions: [{ id: "react", icon: "smile", tip: "React" }, { id: "reply", icon: "reply", tip: "Reply" }]
        .concat(canThread ? [{ id: "thread", icon: "thread", tip: "Open thread" }] : [])
        .concat(canEdit ? [{ id: "edit", icon: "edit", tip: "Edit" }] : [])
        .concat(canPin ? [{ id: "pin", icon: "pin", tip: pinnedNow ? "Unpin" : "Pin" }] : [])
        .concat([{ id: "more", icon: "more", tip: "More" }])
    Row {
        id: icons
        anchors.centerIn: parent
        spacing: Theme.px(2)
        Repeater {
            model: bar.actions
            Rectangle {
                width: Theme.px(28); height: width; radius: Theme.radius
                color: aHover.hovered ? Theme.raised : "transparent"
                Accessible.role: Accessible.Button
                Accessible.name: modelData.tip
                Icon { anchors.centerIn: parent; name: modelData.icon; size: Theme.px(16); color: aHover.hovered || (modelData.id === "pin" && bar.pinnedNow) ? Theme.accent : Theme.textMuted }
                HoverHandler { id: aHover; cursorShape: Qt.PointingHandCursor }
                TapHandler {
                    onTapped: {
                        switch (modelData.id) {
                        case "react": bar.react(); break
                        case "reply": bar.reply(); break
                        case "thread": bar.thread(); break
                        case "edit": bar.edit(); break
                        case "pin": bar.pin(); break
                        default: bar.more()
                        }
                    }
                }
            }
        }
    }
}

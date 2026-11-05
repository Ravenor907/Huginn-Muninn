import QtQuick
import Huginn

// Every keyboard shortcut in one place (Ctrl+/), in two columns: getting around and
// messages, then calls and voice and the look. Esc, B or a click outside closes it; Up and Down scroll.
Item {
    id: panel
    visible: false
    anchors.fill: parent
    function open() { visible = true; view.contentY = 0 }
    function close() { visible = false }
    function handleButton(b) {
        if (b === "b" || b === "a") close()
        else if (b === "up") view.contentY = Math.max(0, view.contentY - Theme.px(60))
        else if (b === "down") view.contentY = Math.min(Math.max(0, view.contentHeight - view.height), view.contentY + Theme.px(60))
        return true
    }

    readonly property var columns: [
        [{ title: "GETTING AROUND", keys: [
            ["Go to a chat", "Ctrl+K"],
            ["Chat above or below", "Alt+Up/Down"],
            ["Next or previous section", "Tab"],
            ["Hide the list", "Ctrl+Shift+L"],
            ["Hide members", "Ctrl+Shift+U"],
            ["Settings", "Ctrl+,"],
            ["Scroll a screenful", "Page Up"],
            ["Right-click menu", "Shift+F10"],
            ["Hard-to-undo actions", "hold Enter 5 s"],
            ["Back", "Esc"] ] },
         { title: "MESSAGES", keys: [
            ["Send", "Enter"],
            ["New line", "Shift+Enter"],
            ["Edit your last message", "Up"],
            ["Stop replying or editing", "Esc"],
            ["Mark the chat as read", "Esc"],
            ["Paste a picture to send it", "Ctrl+V"],
            ["Mention someone in a group", "@name"],
            ["Find in this chat", "Ctrl+F"] ] }],
        [{ title: "CALLS AND VOICE", keys: [
            // Which work while a game is in front: only Mute and Deafen (GlobalKeys); the rest need Huginn in front.
            [GlobalKeys.everywhere ? "Mute mic (also in a game)" : "Mute mic", GlobalKeys.muteName],
            [GlobalKeys.everywhere ? "Deafen (also in a game)" : "Deafen", GlobalKeys.deafenName],
            ["Leave (Huginn in front)", "Ctrl+Shift+H"],
            [Settings.pushToTalk ? "Push to talk (Huginn in front)" : "Push to talk (Settings › Calls › Voice rooms)", TalkKey.key !== 0 ? "hold " + TalkKey.keyName : TalkKey.hasKey ? "hold " + Gamepad.glyph(TalkKey.padButton) : "pick a key first"],
            ["In a call: mute", "Space"] ] },
         { title: "LOOK AND LOCK", keys: [
            ["Bigger", "Ctrl+="],
            ["Smaller", "Ctrl+-"],
            ["Normal size", "Ctrl+0"],
            ["Full screen", "F11"],
            ["Lock Huginn now", "Ctrl+Shift+Q"],
            ["This list", "Ctrl+/"] ] }]
    ]

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        id: box
        readonly property bool narrow: width < Theme.px(620)
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(760))
        height: Math.min(parent.height - Theme.px(32), head.height + grid.implicitHeight + Theme.px(44))
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
                text: "KEYBOARD SHORTCUTS"
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
            }
            Text {
                textFormat: Text.PlainText
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                text: "Esc closes"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }
        Flickable {
            id: view
            anchors.top: head.bottom; anchors.topMargin: Theme.px(16)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(8)
            contentHeight: grid.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Grid {
                id: grid
                width: view.width
                columns: box.narrow ? 1 : 2
                columnSpacing: Theme.px(32)
                rowSpacing: Theme.px(20)
                Repeater {
                    model: panel.columns
                    Column {
                        readonly property var groups: modelData
                        width: box.narrow ? grid.width : (grid.width - grid.columnSpacing) / 2
                        spacing: Theme.px(20)
                        Repeater {
                            model: parent.groups
                            Column {
                                width: parent.width
                                spacing: Theme.px(2)
                                Text {
                                    textFormat: Text.PlainText
                                    bottomPadding: Theme.px(6)
                                    text: modelData.title
                                    color: Theme.textMuted
                                    font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1)
                                }
                                Repeater {
                                    model: modelData.keys
                                    Item {
                                        width: parent.width
                                        height: Theme.px(34)
                                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.seam }
                                        Text {
                                            textFormat: Text.PlainText
                                            anchors.left: parent.left
                                            anchors.right: cap.left; anchors.rightMargin: Theme.px(10)
                                            anchors.verticalCenter: parent.verticalCenter
                                            elide: Text.ElideRight
                                            text: modelData[0]
                                            color: Theme.textSoft
                                            font.family: Theme.body; font.pixelSize: Theme.fs(14)
                                        }
                                        Rectangle {
                                            id: cap
                                            anchors.right: parent.right
                                            anchors.verticalCenter: parent.verticalCenter
                                            width: capText.implicitWidth + Theme.px(14); height: Math.max(Theme.px(24), capText.implicitHeight + Theme.px(6))
                                            radius: Theme.radius
                                            color: Theme.raised
                                            border.color: Theme.lineStrong
                                            Text {
                                                textFormat: Text.PlainText
                                                id: capText
                                                anchors.centerIn: parent
                                                text: modelData[1]
                                                color: Theme.text
                                                font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

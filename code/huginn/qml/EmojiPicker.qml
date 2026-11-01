import QtQuick
import QtQuick.Window
import Huginn

// A panel of emoji to put in a message or react with. [quick]: the five quick reactions on top (for
// reacting); tap one to pick it. Closes on Esc or a tap outside it (the page decides: picked hides it).
// A controller moves a highlight with the D-pad (the page hands it the buttons), A picks, B closes.
Rectangle {
    id: picker
    property bool quick: false
    property string current: ""          // my reaction now, outlined (tap it again to take it back)
    signal picked(string e)

    visible: false
    // Never bigger than the window it opens in.
    width: Math.min(Theme.px(360), Window.width > 0 ? Window.width - Theme.px(16) : Theme.px(360))
    height: Math.min(Theme.px(320), grid.contentHeight + quickRow.height + Theme.px(24), Window.height > 0 ? Window.height * 0.6 : Theme.px(320))
    radius: Theme.radius
    color: Theme.panel
    border.color: Theme.lineStrong
    z: 20
    Accessible.role: Accessible.Pane
    Accessible.name: "Emoji"

    readonly property var all: Chat.emojiList()
    readonly property var quickOnes: Chat.quickReactions()

    // The controller's highlight: the quick row first, then the grid.
    property int cur: 0
    readonly property int quickCount: quick ? quickOnes.length : 0
    readonly property int cols: Math.max(1, Math.floor(grid.width / grid.cellWidth))
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    onVisibleChanged: if (visible) { cur = 0; grid.positionViewAtBeginning() }
    function moveTo(i) {
        cur = i
        if (i >= quickCount) grid.positionViewAtIndex(i - quickCount, GridView.Contain)
    }
    function handleButton(b) {
        const q = quickCount, n = q + all.length
        switch (b) {
        case "left": if (cur > 0) moveTo(cur - 1); break
        case "right": if (cur < n - 1) moveTo(cur + 1); break
        case "up":
            if (cur >= q + cols) moveTo(cur - cols)
            else if (cur >= q && q > 0) moveTo(Math.min(q - 1, cur - q))
            break
        case "down":
            if (cur < q) moveTo(Math.min(n - 1, q + cur))
            else if (cur + cols < n) moveTo(cur + cols)
            break
        case "a": if (n > 0) picker.picked(cur < q ? quickOnes[cur] : all[cur - q]); break
        case "b": visible = false; break
        }
        return true
    }

    Row {
        id: quickRow
        visible: picker.quick
        height: visible ? Theme.px(46) : 0
        x: Theme.px(10); y: Theme.px(8)
        spacing: Theme.px(6)
        Repeater {
            model: picker.quick ? picker.quickOnes : []
            Rectangle {
                readonly property bool on: picker.pad && picker.cur === index
                width: Theme.px(40); height: width; radius: width / 2
                color: modelData === picker.current ? Theme.accentFill : on ? Theme.focusFill : qHover.hovered ? Theme.raised : Theme.tile
                border.width: on ? Theme.focusWidth : modelData === picker.current ? 2 : 0
                border.color: Theme.accent
                Accessible.role: Accessible.Button
                Accessible.name: modelData === picker.current ? "Take back " + modelData : "React " + modelData
                Text { textFormat: Text.PlainText; anchors.centerIn: parent; text: modelData; font.pixelSize: Theme.fs(20) }
                HoverHandler { id: qHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: picker.picked(modelData) }
            }
        }
    }

    GridView {
        id: grid
        anchors.top: quickRow.bottom
        anchors.topMargin: Theme.px(8)
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.px(8)
        x: Theme.px(8)
        width: parent.width - Theme.px(16)
        cellWidth: Theme.px(38)
        cellHeight: Theme.px(38)
        clip: true
        model: picker.all
        boundsBehavior: Flickable.StopAtBounds
        delegate: Rectangle {
            readonly property bool on: picker.pad && picker.cur === picker.quickCount + index
            width: grid.cellWidth - Theme.px(2); height: width; radius: Theme.radius
            color: on ? Theme.focusFill : eHover.hovered ? Theme.raised : "transparent"
            border.width: on ? Theme.focusWidth : 0
            border.color: Theme.accent
            Accessible.role: Accessible.Button
            Accessible.name: modelData
            Text { textFormat: Text.PlainText; anchors.centerIn: parent; text: modelData; font.pixelSize: Theme.fs(20) }
            HoverHandler { id: eHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: picker.picked(modelData) }
        }
    }
    Keys.onEscapePressed: picker.visible = false
}

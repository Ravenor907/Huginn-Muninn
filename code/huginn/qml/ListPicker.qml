import QtQuick
import Huginn

// A list to choose from, in sections: every item's menu (the three dots), message options,
// backups. A row can be ticked (check), red and last (danger), or have a short detail. A sub-page
// passes back: B and the arrow beside the title return to the menu it came from.
Item {
    id: picker
    property string title
    property var entries: []          // flat list: { header } or { name, detail, url, icon, check, danger }
    property int current: 0
    property var callback: null
    property var backFn: null

    visible: false
    anchors.fill: parent

    // sections = [{ header, items: [{ name, detail, url, icon, check, danger }] }]; back: the menu this came from.
    function openList(t, sections, cb, back) {
        title = t
        callback = cb
        backFn = back || null
        const out = []
        sections.forEach(sec => {
            if (!sec.items.length) return
            if (sec.header) out.push({ header: sec.header })   // an untitled section is just its rows
            sec.items.forEach(e => out.push(e))
        })
        entries = out
        current = out.findIndex(e => !e.header)
        visible = true
        list.positionViewAtIndex(Math.max(0, current), ListView.Beginning)
    }
    function pick() {
        const e = entries[current]
        if (!e || e.header) return
        visible = false
        backFn = null
        const cb = callback
        if (cb) cb(e.url)
    }
    function close() { visible = false; backFn = null }
    // B: back to the menu this sub-page came from, or closed.
    function goBack() {
        const f = backFn
        close()
        if (f) f()
    }
    function step(dir) {
        let i = current
        do { i += dir } while (i >= 0 && i < entries.length && entries[i].header)
        if (i >= 0 && i < entries.length) current = i
    }
    function jumpSection(dir, wrap) {
        // Headers mark sections; land on the first entry of the next or previous one.
        const headers = []
        entries.forEach((e, i) => { if (e.header) headers.push(i) })
        if (!headers.length) return
        let here = 0
        headers.forEach((h, k) => { if (h < current) here = k })
        const target = wrap ? (here + dir + headers.length) % headers.length : Math.max(0, Math.min(headers.length - 1, here + dir))
        current = headers[target] + 1
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.85) }
    MouseArea { anchors.fill: parent; onClicked: picker.close() }

    // Sized to its rows (no taller than the window) and about 480 px wide; a detail wraps under its name.
    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(480))
        height: Math.min(parent.height - Theme.px(48), head.height + Theme.px(28) + list.contentHeight + (keys.visible ? keys.height : Theme.px(8)))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        MouseArea { anchors.fill: parent }   // a click between rows doesn't close it

        Rectangle {
            id: backBtn
            visible: !!picker.backFn
            x: Theme.px(10); y: Theme.px(12)
            width: visible ? Theme.px(30) : 0; height: Theme.px(30)
            radius: Theme.radius
            color: backHover.hovered ? Theme.raised : "transparent"
            Accessible.role: Accessible.Button
            Accessible.name: "Back"
            Accessible.onPressAction: picker.goBack()
            Icon { anchors.centerIn: parent; name: "back"; size: Theme.px(18); color: Theme.textSoft }
            HoverHandler { id: backHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: picker.goBack() }
        }
        Text {
            textFormat: Text.PlainText
            id: head
            x: backBtn.visible ? backBtn.x + backBtn.width + Theme.px(6) : Theme.px(20); y: Theme.px(18)
            width: parent.width - x - Theme.px(20)
            wrapMode: Text.WordWrap
            text: picker.title.toUpperCase()
            color: Theme.text
            font.family: Theme.display
            font.pixelSize: Theme.fs(16)
            font.weight: Font.Bold
            font.letterSpacing: Theme.px(2)
        }
        ListView {
            id: list
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(8)
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: keys.visible ? keys.top : parent.bottom
            anchors.margins: Theme.px(8)
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            model: picker.entries
            currentIndex: picker.current
            highlightMoveDuration: 80
            preferredHighlightBegin: height / 3
            preferredHighlightEnd: height * 2 / 3
            highlightRangeMode: ListView.ApplyRange
            delegate: Item {
                id: rowItem
                // The first red row gets a line above it, so what can't be undone sits apart.
                readonly property bool firstDanger: !!modelData.danger && index > 0 && !!picker.entries[index - 1] && !picker.entries[index - 1].danger
                readonly property int gap: firstDanger ? Theme.px(11) : 0
                width: list.width
                height: modelData.header ? headText.implicitHeight + Theme.px(14) : gap + Math.max(Theme.px(40), rowCol.implicitHeight + Theme.px(14))
                Rectangle {
                    visible: rowItem.firstDanger
                    y: Theme.px(5)
                    x: Theme.px(8); width: parent.width - Theme.px(16); height: 1
                    color: Theme.line
                }
                Text {
                    textFormat: Text.PlainText
                    id: headText
                    visible: !!modelData.header
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: Theme.px(4)
                    wrapMode: Text.WordWrap
                    text: modelData.header ? modelData.header.toUpperCase() : ""
                    color: Theme.textMuted
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(11)
                    font.weight: Font.DemiBold
                    font.letterSpacing: Theme.px(0.5)
                }
                Rectangle {
                    visible: !modelData.header
                    anchors.fill: parent
                    anchors.topMargin: rowItem.gap
                    radius: Theme.radius
                    readonly property bool on: index === picker.current
                    color: on ? (Ui.main && Ui.main.padInUse ? Theme.focusFill : Theme.tile) : rowHover.hovered ? Theme.raised : "transparent"
                    border.width: on && Ui.main && Ui.main.padInUse ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.ListItem
                    Accessible.name: (modelData.name || "") + (modelData.detail ? ", " + modelData.detail : "") + (modelData.check ? ", on" : "")
                    Icon {
                        id: rowIcon
                        visible: !!modelData.icon
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        name: modelData.icon || "file"; size: Theme.px(18); color: modelData.danger ? Theme.danger : Theme.textMuted
                    }
                    Icon {
                        id: checkIcon
                        visible: !!modelData.check
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        name: "check"; size: Theme.px(18); color: Theme.accent
                    }
                    Column {
                        id: rowCol
                        anchors.left: rowIcon.visible ? rowIcon.right : parent.left
                        anchors.leftMargin: Theme.px(12)
                        anchors.right: checkIcon.visible ? checkIcon.left : parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.px(1)
                        Text {
                            textFormat: Text.PlainText
                            width: parent.width
                            wrapMode: Text.Wrap
                            text: modelData.name || ""
                            color: modelData.danger ? Theme.danger : Theme.text
                            font.family: Theme.body
                            font.pixelSize: Theme.fs(15)
                            font.weight: Font.Medium
                        }
                        Text {
                            textFormat: Text.PlainText
                            visible: !!modelData.detail
                            width: parent.width
                            wrapMode: Text.WordWrap
                            text: modelData.detail || ""
                            color: Theme.textMuted
                            font.family: Theme.body
                            font.pixelSize: Theme.fs(13)
                        }
                    }
                    HoverHandler { id: rowHover; onHoveredChanged: if (hovered && !(Ui.main && Ui.main.padInUse)) picker.current = index }
                    TapHandler { onTapped: { picker.current = index; picker.pick() } }
                }
            }
        }
        // The buttons only while a controller is in use; a mouse clicks a row or outside.
        PromptBar {
            id: keys
            visible: !!Ui.main && Ui.main.padInUse
            anchors.bottom: parent.bottom
            width: parent.width
            color: "transparent"
            prompts: [{ key: "a", label: "Choose" }, { key: "b", label: picker.backFn ? "Back" : "Close" }]
            onPrompt: k => picker.handleButton(k)
        }
    }

    function handleButton(b) {
        switch (b) {
        case "up": step(-1); break
        case "down": step(1); break
        case "lb": jumpSection(1, true); break   // LB goes round the sections
        case "next": jumpSection(1, false); break
        case "prev": jumpSection(-1, false); break
        case "a": pick(); break
        case "b": goBack(); break
        }
        return true
    }
}

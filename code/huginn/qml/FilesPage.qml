import QtQuick
import Huginn

// Files: share with phones and PCs. Middle column: Send a file (pick it, then a person, a group or a LocalSend
// device nearby, the same picker everywhere), then the devices nearby with Muninn or the free LocalSend app (A
// picks a file for one). The three dots (or LT) hold Look again and Direct connect (for when there's no shared
// Wi-Fi). Main column: what came in. Transfers (the top bar) shows everything on its way.
Rectangle {
    id: page
    color: Theme.ground
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int current: 0
    property bool tour: false
    readonly property var devices: LocalSend.devices
    readonly property var incoming: LocalSend.items
    // Rows in the middle column: Send files, then one per device.
    readonly property int actionCount: 1
    readonly property var rows: [{ kind: "pick", name: "Send a file", detail: "To a person, a group or a device nearby" }]
        .concat(devices.map(d => ({ kind: "device", id: d.id, name: d.alias, type: d.type,
                                    detail: d.model ? d.model : d.app === "muninn" ? "Muninn" : d.app === "huginn" ? "Huginn" : d.type === "mobile" ? "Phone" : "Computer" })))

    function setArgs(a) { tour = !!a.tour }
    Component.onCompleted: LocalSend.refresh()

    readonly property var prompts: LocalSend.busy ? [{ key: "b", label: cancelArmed ? "Cancel now" : "Cancel" }]
        : [{ key: "a", label: page.rows[page.current] && page.rows[page.current].kind === "device" ? "Send to it" : "Pick a file" },
           { key: "y", label: "Look again" }, { key: "lt", label: "Menu" }, { key: "b", label: "Back" }]
    // B while files go: the first press says to press again, a second one within 3 s cancels.
    property bool cancelArmed: false
    Timer { id: cancelTimer; interval: 3000; onTriggered: page.cancelArmed = false }
    Connections {
        target: LocalSend
        function onProgressChanged() { if (!LocalSend.busy && page.cancelArmed) { page.cancelArmed = false; cancelTimer.stop() } }
    }
    readonly property string status: ""

    // ============================================================= middle column
    Rectangle {
        id: middle
        // Portrait: the list on top, the rest under it.
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.portrait ? parent.height * 0.45 : Ui.dock
        width: Theme.portrait ? parent.width : Theme.listWidth
        color: Theme.column

        PageHeader {
            id: head; width: parent.width; title: "Files"
            MoreButton { anchors.verticalCenter: parent.verticalCenter; label: "Files menu"; onClicked: page.filesMenu() }
        }

        // Scrolls when there are more devices than fit.
        Flickable {
            id: rowView
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(10)
            x: Theme.px(10)
            width: parent.width - Theme.px(20)
            height: Math.min(contentHeight, Math.max(0, parent.height - head.height - Theme.px(10)))
            contentWidth: width
            contentHeight: rowList.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: rowList
                width: rowView.width
                spacing: Theme.px(3)

                Repeater {
                    model: page.rows
                    Item {
                        width: rowList.width
                        // "Nearby" label above the first device.
                        height: (index === page.actionCount ? label.height : 0) + tile.height
                        Text {
                            textFormat: Text.PlainText
                            id: label
                            visible: index === page.actionCount
                            height: visible ? Theme.px(34) : 0
                            x: Theme.px(10)
                            verticalAlignment: Text.AlignBottom
                            bottomPadding: Theme.px(6)
                            text: "NEARBY"
                            color: Theme.textMuted
                            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
                        }
                        Rectangle {
                            id: tile
                            readonly property bool on: page.active && index === page.current
                            y: label.height
                            onOnChanged: if (on) { const y = tile.mapToItem(rowList, 0, 0).y; if (y < rowView.contentY) rowView.contentY = y; else if (y + tile.height > rowView.contentY + rowView.height) rowView.contentY = Math.min(rowView.contentHeight - rowView.height, y + tile.height - rowView.height) }
                            width: parent.width
                            height: Theme.px(56)
                            radius: Theme.radius
                            // Send files is the one button: framed in the accent.
                            readonly property bool button: modelData.kind === "pick"
                            color: on ? Theme.tile : button ? Theme.accentFill : tileHover.hovered ? Theme.raised : "transparent"
                            border.width: on ? Theme.focusWidth : button ? 1 : 0
                            border.color: Theme.accent
                            Accessible.role: Accessible.Button
                            Accessible.name: (modelData.kind === "device" ? "Send to " : "") + modelData.name
                            Rectangle {
                                id: ava
                                anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                                anchors.verticalCenter: parent.verticalCenter
                                width: Theme.px(34); height: width; radius: width / 2
                                color: Theme.raised
                                Icon {
                                    anchors.centerIn: parent
                                    size: Theme.px(18)
                                    name: modelData.kind === "pick" ? "send" : modelData.type === "mobile" ? "phone" : "network"
                                    color: tile.on ? Theme.accent : Theme.textMuted
                                }
                            }
                            Column {
                                anchors.left: ava.right; anchors.leftMargin: Theme.px(10)
                                anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                                anchors.verticalCenter: parent.verticalCenter
                                Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; text: modelData.name; color: tile.on ? Theme.text : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                                Text { textFormat: Text.PlainText; visible: text !== ""; width: parent.width; elide: Text.ElideRight; text: modelData.detail; color: modelData.kind === "pick" ? Theme.accent : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                            }
                            HoverHandler { id: tileHover }
                            TapHandler { onTapped: { page.current = index; page.choose() } }
                        }
                    }
                }
            }
        }
        // One line when nobody is found; "Look again" is a link.
        Text {
            visible: page.devices.length === 0
            anchors.top: rowView.bottom
            anchors.topMargin: Theme.px(14)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            // Markup only for our own "Look again" line; anything else (an error can name a device) is plain text.
            readonly property bool linkLine: Settings.nearbySharing && LocalSend.running && !LocalSend.scanning
            textFormat: linkLine ? Text.StyledText : Text.PlainText
            linkColor: Theme.accent
            text: !Settings.nearbySharing ? "Nearby sharing is off."
                : !LocalSend.running ? (LocalSend.error || "Starting…")
                : LocalSend.scanning ? "Looking…"
                : "No devices found. <a href=\"again\">Look again</a>"
            onLinkActivated: LocalSend.refresh()
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(14)
            HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
        }
    }

    // ============================================================= main column
    Item {
        id: main
        anchors.left: Theme.portrait ? parent.left : middle.right
        anchors.right: parent.right
        anchors.top: Theme.portrait ? middle.bottom : parent.top
        anchors.bottom: parent.bottom

        PageHeader {
            id: mainHead
            width: parent.width
            carved: false
            title: "Sharing"
            subtitle: LocalSend.running ? "Shown as " + LocalSend.alias : ""
        }

        // Scrolls when the window is short or the Size is large.
        Flickable {
            id: listsView
            anchors.top: mainHead.bottom
            anchors.topMargin: Theme.px(16)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            anchors.bottom: backup.visible ? backup.top : parent.bottom
            anchors.bottomMargin: Theme.px(12)
            contentWidth: width
            contentHeight: lists.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: lists
                width: listsView.width
                spacing: Theme.px(8)

                // The transfer under way: overall (percent, speed, time left), each file going out, and Cancel.
                SectionLabel { visible: LocalSend.busy; text: LocalSend.sending ? "SENDING" : "RECEIVING"; detail: LocalSend.pace }
                Rectangle {
                    visible: LocalSend.busy
                    width: lists.width
                    height: visible ? busyCol.implicitHeight + Theme.px(24) : 0
                    radius: Theme.radius
                    color: Theme.panel
                    border.color: Theme.accent
                    Column {
                        id: busyCol
                        x: Theme.px(12); y: Theme.px(12)
                        width: parent.width - Theme.px(24)
                        spacing: Theme.px(8)
                        Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; text: LocalSend.status; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                        Rectangle {
                            width: parent.width; height: Theme.px(6); color: Theme.line
                            Rectangle { width: parent.width * Math.max(0, Math.min(1, LocalSend.progress)); height: parent.height; color: Theme.accent }
                        }
                        Text {
                            textFormat: Text.PlainText
                            text: Math.round(LocalSend.progress * 100) + "%" + (LocalSend.pace !== "" ? " · " + LocalSend.pace : "")
                            color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                        }
                        Repeater {
                            model: LocalSend.sending ? LocalSend.outgoing.slice(0, 8) : []
                            Item {
                                width: busyCol.width; height: Theme.px(20)
                                Text { textFormat: Text.PlainText; width: parent.width - outSt.width - Theme.px(12); elide: Text.ElideMiddle; text: modelData.name; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                                Text { textFormat: Text.PlainText; id: outSt; anchors.right: parent.right; text: modelData.status; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                            }
                        }
                        HButton {
                            text: "Cancel"
                            kind: "danger"; height: Theme.px(34); fontSize: 13
                            onClicked: LocalSend.sending ? LocalSend.cancelSend() : LocalSend.cancelReceive()
                        }
                    }
                }
                // How the last one ended, until the next starts.
                Rectangle {
                    visible: !LocalSend.busy && LocalSend.lastResult !== ""
                    width: lists.width
                    height: visible ? Math.max(Theme.px(52), lastText.implicitHeight + Theme.px(24)) : 0
                    radius: Theme.radius
                    color: Theme.panel
                    border.color: LocalSend.lastOk ? Theme.line : Theme.dangerLine
                    Icon { id: lastIcon; x: Theme.px(12); anchors.verticalCenter: parent.verticalCenter; name: LocalSend.lastOk ? "check" : "info"; size: Theme.px(20); color: LocalSend.lastOk ? Theme.accent : Theme.danger }
                    Text {
                        id: lastText
                        textFormat: Text.PlainText
                        anchors.left: lastIcon.right; anchors.leftMargin: Theme.px(10)
                        anchors.right: lastDone.left; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        wrapMode: Text.Wrap
                        text: LocalSend.lastResult
                        color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                    }
                    HButton { id: lastDone; anchors.right: parent.right; anchors.rightMargin: Theme.px(8); anchors.verticalCenter: parent.verticalCenter; text: "Done"; height: Theme.px(34); fontSize: 13; onClicked: LocalSend.clearResult() }
                }

                SectionLabel { text: "RECEIVED"; detail: "Saved in " + Transfer.saveName + "/Huginn" }
                Repeater {
                    model: page.incoming.slice(0, 4)
                    Rectangle {
                        width: lists.width
                        height: Theme.px(52)
                        radius: Theme.radius
                        color: Theme.panel
                        border.color: Theme.line
                        Column {
                            anchors.fill: parent
                            anchors.margins: Theme.px(11)
                            spacing: Theme.px(7)
                            Item {
                                width: parent.width; height: Theme.px(19)
                                Text { textFormat: Text.PlainText; width: parent.width - st.width - Theme.px(12); elide: Text.ElideMiddle; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
                                Text { textFormat: Text.PlainText; id: st; anchors.right: parent.right; text: modelData.status; color: modelData.failed ? Theme.danger : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                            }
                            Rectangle {
                                width: parent.width; height: Theme.px(4); radius: 1; color: Theme.line
                                Rectangle { width: parent.width * modelData.fraction; height: parent.height; color: modelData.fraction >= 1 ? Theme.textFaint : Theme.accent }
                            }
                        }
                    }
                }
                Text {
                    textFormat: Text.PlainText
                    visible: page.incoming.length === 0
                    width: parent.width
                    wrapMode: Text.WordWrap
                    text: "Nothing yet."
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
            }
        }

        // Direct connect: a code for the phone's camera to join this computer's own Wi-Fi.
        Rectangle {
            id: backup
            visible: Direct.on || Direct.error.length > 0
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(18)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            height: Math.max(Theme.px(176), directWords.implicitHeight + Theme.px(28))
            radius: Theme.radius
            color: Theme.panelLow
            border.color: Theme.line
            Rectangle {
                id: qr
                visible: Direct.on
                anchors.left: parent.left
                anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                width: Direct.on ? Theme.px(148) : 0; height: width
                radius: Theme.radius
                color: "#E4E1DA"   // the light paper the code is drawn on, in every look
                Image {
                    anchors.fill: parent
                    anchors.margins: Theme.px(10)
                    source: Direct.on ? "image://qr/" + encodeURIComponent(Direct.qr) : ""
                    smooth: false
                    cache: false
                }
            }
            Column {
                id: directWords
                anchors.left: qr.right
                anchors.leftMargin: Theme.px(16)
                anchors.right: parent.right
                anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(6)
                Text { textFormat: Text.PlainText; visible: Direct.on; width: parent.width; wrapMode: Text.WordWrap; text: "Scan with the phone's camera"; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                Text { textFormat: Text.PlainText; visible: Direct.on; width: parent.width; wrapMode: Text.Wrap; text: "Wi-Fi " + Direct.ssid + "  ·  password " + Direct.password; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                Text { textFormat: Text.PlainText; visible: Direct.error.length > 0 && !Direct.on; width: parent.width; wrapMode: Text.WordWrap; text: Direct.error; color: Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            }
        }
    }

    component SectionLabel: Item {
        property alias text: labelText.text
        property string detail
        width: parent ? parent.width : 0
        height: Theme.px(22)
        Text {
            textFormat: Text.PlainText
            id: labelText
            anchors.verticalCenter: parent.verticalCenter
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
        }
        Text {
            textFormat: Text.PlainText
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            text: parent.detail
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
    }

    // ============================================================= actions
    // Send a file: pick it, then who gets it (PeopleActions.sendPicker: people, groups, LocalSend devices nearby).
    function pickFiles() {
        if (page.tour) return
        Ui.pickFiles(paths => PeopleActions.sendPicker(paths))
    }
    // A device nearby: pick a file for it. A PIN, when it asks for one, sends the same files again.
    function sendTo(d) {
        if (page.tour) return
        Ui.pickFiles(paths => {
            Transfer.clearShared()
            Transfer.share(paths)
            LocalSend.send(d.id, Transfer.shared)
        })
    }
    function choose() {
        const r = rows[current]
        if (!r) return
        if (r.kind === "pick") pickFiles()
        else sendTo(r)
    }
    // The three dots: Look again and Direct connect.
    function filesMenu() {
        const items = [{ name: "Look again", icon: "search", url: "look" },
                       { name: "Direct connect", icon: "qr", url: "direct", check: Direct.on }]
        Ui.pickFrom("Files", [{ header: "", items: items }], a => {
            if (a === "look") LocalSend.refresh()
            else if (a === "direct") toggleDirect()
        })
    }

    // No shared Wi-Fi: this computer makes its own for the phone to join.
    function toggleDirect() {
        if (Direct.on) { Direct.stop(); Ui.say("Direct connect is off."); return }
        Ui.sure("Start Direct connect?", "Leaves your Wi-Fi until stopped.", "Start", () => Direct.start(), false, false)
    }
    function handleButton(b) {
        if (LocalSend.busy) {
            if (b === "b") {
                if (cancelArmed) { cancelArmed = false; cancelTimer.stop(); if (LocalSend.sending) LocalSend.cancelSend(); else LocalSend.cancelReceive() }
                else { cancelArmed = true; cancelTimer.restart(); Ui.say((Ui.main && Ui.main.padInUse ? "Press " + Gamepad.glyph("b") : "Press Esc") + " again to cancel") }
                return true
            }
            return b !== "left"
        }
        switch (b) {
        case "up": current = Math.max(0, current - 1); return true
        case "down": current = Math.min(rows.length - 1, current + 1); return true
        case "a": choose(); return true
        case "x": filesMenu(); return true
        case "y": LocalSend.refresh(); return true
        }
        return false   // Left and B: back to the rail
    }
}

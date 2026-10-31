import QtQuick
import Huginn

// The bigger sub-pages of a contact's menu (PeopleActions.contactMenu): "edit" (nickname, note,
// chat color) and "can" (what they can do: the four switches, Direct calls and Message days).
// One framed section of rows. The D-pad moves, A changes, Left and Right pick a color, B goes back
// to the menu.
//
// HOOKS (chatpeople.cpp), each through has() so this still works on a build without them:
//   Chat.callsAllowed / setCallsAllowed, Chat.filesAllowed / setFilesAllowed, Chat.showsActive / setShowsActive,
//   Chat.isRightAway / setRightAway, Chat.chatColor / setChatColor
Item {
    id: panel
    property string key: ""
    property string name: ""
    property var extra: ({})          // { page: "edit" | "can", back (the menu to go back to), tour }
    property int focusIndex: 0
    property int revision: 0          // bumps after a change so the bindings below read again
    visible: false
    anchors.fill: parent

    readonly property bool tour: !!extra.tour
    readonly property string page: extra.page === "can" ? "can" : "edit"
    readonly property string shownName: tour ? name : (revision, Chat.chats, Chat.nameOf(key)) || name
    readonly property string nick: tour ? "" : (revision, Chat.chats, Chat.nicknameOf(key))
    readonly property string note: tour ? "" : (revision, Chat.people, Chat.noteOf(key))

    function has(fn) { return typeof Chat[fn] === "function" }
    function hook(fn, args, fallback) { return has(fn) ? Chat[fn].apply(Chat, args || []) : fallback }
    readonly property var getters: ({ always: "isRightAway", calls: "callsAllowed", files: "filesAllowed", active: "showsActive" })
    readonly property var setters: ({ always: "setRightAway", calls: "setCallsAllowed", files: "setFilesAllowed", active: "setShowsActive" })
    function isOn(id) {
        revision; Chat.chats
        if (tour) return id !== "direct"
        if (id === "direct") return Calls.trusted(key)
        return !!hook(getters[id], [key], id !== "always")
    }
    readonly property string tint: tour ? "" : (revision, Chat.chats, hook("chatColor", [key], ""))
    readonly property var colors: ["", "#C2A25E", "#6E9CC8", "#6FAE8C", "#CF6F54", "#A58BC6"]

    readonly property var rows: page === "can" ? [
        { id: "always", label: "Always right away", toggle: true },
        { id: "calls", label: "Calls and video", toggle: true },
        { id: "files", label: "Send me files", toggle: true },
        { id: "active", label: "See when I'm active", toggle: true },
        { id: "direct", label: "Direct calls (shares your address)", toggle: true },
        { id: "days", label: "Message days", value: tour ? "7 days" : (revision, Chat.chats, Chat.chatDays(key)) + " days" }
    ] : [
        { id: "nick", label: "Nickname", value: nick !== "" ? nick : "None" },
        { id: "note", label: "Note", value: note !== "" ? note : "None" },
        { id: "color", label: "Chat color", swatches: true }
    ]

    function show(k, n, x) {
        key = k; name = n; extra = x || {}; focusIndex = 0; revision++
        visible = true
    }
    function close() { visible = false; if (Ui.main) Ui.main.restoreFocus() }
    // B and the arrow: back to the menu this came from.
    function back() {
        const f = extra.back
        close()
        if (f) f()
    }
    // Comes back here after a prompt or a list opened from a row.
    function reopen() { const k = key, n = name, x = extra, at = focusIndex; show(k, n, x); focusIndex = at }

    function act(id) {
        if (tour) return
        const k = key
        if (id === "nick")
            Ui.ask("Nickname for " + Chat.realNameOf(k), "Only you see it. Empty: their name", Chat.nicknameOf(k), "Save", t => { Chat.setNickname(k, t); panel.revision++ }, false, true)
        else if (id === "note")
            Ui.ask("Note about " + shownName, "Only you see it", Chat.noteOf(k), "Save", t => { Chat.setNote(k, t); panel.revision++ }, false, true)
        else if (id === "color") pickColor(1)
        // The list opens over this panel (it stays open on the same row); back here after a pick or B.
        else if (id === "days") PeopleActions.daysMenu(k, () => { panel.revision++; if (Ui.main) Ui.main.restoreFocus() })
        else if (id === "direct") { Calls.setTrusted(k, !Calls.trusted(k)); revision++ }
        else if (setters[id] && has(setters[id])) { Chat[setters[id]](k, !isOn(id)); revision++ }
    }
    function setColor(c) {
        if (tour || !has("setChatColor")) return
        Chat.setChatColor(key, c)
        revision++
    }
    function pickColor(dir) {
        const at = Math.max(0, colors.indexOf(tint))
        setColor(colors[(at + dir + colors.length) % colors.length])
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.7) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(480))
        height: Math.min(parent.height - Theme.px(32), head.height + section.height + foot.height + Theme.px(56))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        Accessible.role: Accessible.Dialog
        Accessible.name: (panel.page === "can" ? "What they can do" : "Edit") + ", " + panel.shownName
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

        Item {
            id: head
            x: Theme.px(12); y: Theme.px(12)
            width: parent.width - Theme.px(24)
            height: Theme.px(36)
            Rectangle {
                id: backBtn
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(30); height: width
                radius: Theme.radius
                color: backHover.hovered ? Theme.raised : "transparent"
                Accessible.role: Accessible.Button
                Accessible.name: "Back"
                Accessible.onPressAction: panel.back()
                Icon { anchors.centerIn: parent; name: "back"; size: Theme.px(18); color: Theme.textSoft }
                HoverHandler { id: backHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: panel.back() }
            }
            Avatar {
                id: face
                anchors.left: backBtn.right; anchors.leftMargin: Theme.px(8)
                anchors.verticalCenter: parent.verticalCenter
                key: panel.tour ? "" : panel.key
                name: panel.shownName
                size: Theme.px(30)
                backdrop: Theme.panel
            }
            Text {
                anchors.left: face.right; anchors.leftMargin: Theme.px(10)
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                elide: Text.ElideRight
                text: (panel.page === "can" ? "What they can do" : "Edit").toUpperCase()
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(15); font.weight: Font.Bold; font.letterSpacing: Theme.px(1.5)
            }
        }

        // One framed section: the rows, a seam between each.
        Flickable {
            id: view
            anchors.top: head.bottom; anchors.topMargin: Theme.px(12)
            anchors.bottom: foot.top; anchors.bottomMargin: Theme.px(12)
            x: Theme.px(16)
            width: parent.width - Theme.px(32)
            contentHeight: section.height
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Rectangle {
                id: section
                width: view.width
                height: rowCol.implicitHeight + 2
                radius: Theme.radius
                color: Theme.panelLow
                border.color: Theme.line
                Column {
                    id: rowCol
                    x: 1; y: 1
                    width: parent.width - 2
                    Repeater {
                        model: panel.rows
                        Rectangle {
                            id: row
                            readonly property bool on: !!Ui.main && Ui.main.padInUse && panel.focusIndex === index
                            width: rowCol.width
                            height: Math.max(Theme.px(48), label.implicitHeight + Theme.px(16))
                            color: on ? Theme.focusFill : rowHover.hovered ? Theme.raised : "transparent"
                            border.width: on ? Theme.focusWidth : 0
                            border.color: Theme.accent
                            Accessible.role: modelData.toggle ? Accessible.CheckBox : Accessible.Button
                            Accessible.name: modelData.label + (modelData.value ? ", " + modelData.value : "")
                            Accessible.checked: !!modelData.toggle && panel.isOn(modelData.id)
                            Accessible.onPressAction: panel.act(modelData.id)
                            Rectangle { visible: index > 0; width: parent.width; height: 1; color: Theme.seam }
                            Text {
                                id: label
                                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                                anchors.right: right.left; anchors.rightMargin: Theme.px(10)
                                anchors.verticalCenter: parent.verticalCenter
                                wrapMode: Text.WordWrap
                                text: modelData.label
                                color: Theme.text
                                font.family: Theme.body; font.pixelSize: Theme.fs(15)
                            }
                            Item {
                                id: right
                                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                                anchors.verticalCenter: parent.verticalCenter
                                width: modelData.toggle ? sw.width : modelData.swatches ? swatchRow.width : valueRow.width
                                height: Math.max(sw.height, swatchRow.height, valueRow.height)
                                ToggleSwitch {
                                    id: sw
                                    visible: !!modelData.toggle
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    // The whole row is the switch: a click anywhere on it (the row's tap) flips it once.
                                    enabled: false
                                    checked: !!modelData.toggle && panel.isOn(modelData.id)
                                }
                                Row {
                                    id: swatchRow
                                    visible: !!modelData.swatches
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    spacing: Theme.px(6)
                                    Repeater {
                                        model: modelData.swatches ? panel.colors : []
                                        Rectangle {
                                            readonly property bool picked: (panel.tint || "") === modelData
                                            width: Theme.px(24); height: width; radius: Theme.radius
                                            color: modelData === "" ? Theme.raised : modelData
                                            border.width: picked ? Theme.px(2) : 1
                                            border.color: picked ? Theme.text : Theme.line
                                            Accessible.role: Accessible.RadioButton
                                            Accessible.name: modelData === "" ? "No color" : "Color " + index
                                            Accessible.checked: picked
                                            Icon { visible: modelData === ""; anchors.centerIn: parent; name: "close"; size: Theme.px(12); color: Theme.textMuted }
                                            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: panel.setColor(modelData) }
                                        }
                                    }
                                }
                                Row {
                                    id: valueRow
                                    visible: !modelData.toggle && !modelData.swatches
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    spacing: Theme.px(6)
                                    Text {
                                        anchors.verticalCenter: parent.verticalCenter
                                        width: Math.min(implicitWidth, row.width * 0.45)
                                        elide: Text.ElideRight
                                        text: modelData.value || ""
                                        color: Theme.textMuted
                                        font.family: Theme.body; font.pixelSize: Theme.fs(14)
                                    }
                                    Icon { anchors.verticalCenter: parent.verticalCenter; name: "forward"; size: Theme.px(14); color: Theme.textMuted }
                                }
                            }
                            HoverHandler { id: rowHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: { panel.focusIndex = index; if (!modelData.swatches) panel.act(modelData.id) } }
                        }
                    }
                }
            }
        }
        Row {
            id: foot
            anchors.right: parent.right; anchors.rightMargin: Theme.px(16)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(16)
            HButton { text: "Done"; kind: "primary"; onClicked: panel.back() }
        }
    }

    function handleButton(b) {
        const r = rows[focusIndex]
        switch (b) {
        case "up": focusIndex = Math.max(0, focusIndex - 1); return true
        case "down": focusIndex = Math.min(rows.length - 1, focusIndex + 1); return true
        case "left": case "right": if (r && r.swatches) pickColor(b === "left" ? -1 : 1); return true
        case "a": if (r) act(r.id); return true
        case "b": back(); return true
        }
        return true
    }
}

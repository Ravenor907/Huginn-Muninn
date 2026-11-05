import QtQuick
import Huginn

// A person's Details (PeopleActions.personMenu › Details): one page, the same as Muninn's.
//   Name and note: Nickname, Note, Refresh picture
//   This chat: How long messages stay, Pin to top, Archive, Chat color, Folders
//   They can: Call me, Send me files (Ask, Always or Off; Always only for Trusted and Family), See when I'm active
//   Safety: Compare emoji, Trusted direct, Reaching them by, Introduce
// Framed sections of rows. The D-pad moves, A changes, Left and Right pick a color or a Send me files choice, B goes
// back to the menu. Always right away is in the chat's Notifications (PeopleActions.notifyMenu).
//
// HOOKS, each through has() so this still works on a build without them:
//   Chat.callsAllowed / setCallsAllowed, Chat.showsActive / setShowsActive, Chat.chatColor / setChatColor,
//   Chat.pinChat / archiveChat, Profile.askPicture (Refresh their picture, cap p2),
//   Chat.filesAllowed / setFilesAllowed and Chat.filesAlways / setFilesAlways (Send me files), Chat.inFamilyGroup and
//   Calls.trusted (who may be set to Always; FileLink.mayAlwaysFrom instead once filelink has it)
Item {
    id: panel
    property string key: ""
    property string name: ""
    property var extra: ({})          // { back (the menu to go back to), tour }
    property int focusIndex: 0
    property int revision: 0          // bumps after a change so the bindings below read again
    visible: false
    anchors.fill: parent

    readonly property bool tour: !!extra.tour
    readonly property string shownName: tour ? name : (revision, Chat.chats, Chat.nameOf(key)) || name
    readonly property string nick: tour ? "" : (revision, Chat.chats, Chat.nicknameOf(key))
    readonly property string note: tour ? "" : (revision, Chat.people, Chat.noteOf(key))
    // Interop 2: a contact saved by an older Huginn has no pinned certificate, so it isn't asked on this Wi-Fi until it
    // updates or Nearby is on once (src/localsend.cpp waitsForPin).
    readonly property bool pinWait: !tour && key !== "" && (revision, LocalSend.waitsForPin(key))

    function has(fn) { return typeof Chat[fn] === "function" }
    function hook(fn, args, fallback) { return has(fn) ? Chat[fn].apply(Chat, args || []) : fallback }
    function fileHas(fn) { return typeof FileLink[fn] === "function" }
    readonly property var getters: ({ calls: "callsAllowed", active: "showsActive" })
    readonly property var setters: ({ calls: "setCallsAllowed", active: "setShowsActive" })
    function isOn(id) {
        revision; Chat.chats
        if (tour) return id !== "direct" && id !== "pin" && id !== "archive"
        if (id === "direct") return Calls.trusted(key) && !UltraPrivacy.active
        if (id === "pin") return has("isPinnedChat") && Chat.isPinnedChat(key)
        if (id === "archive") return has("isArchived") && Chat.isArchived(key)
        return !!hook(getters[id], [key], true)
    }
    // Send me files: "ask" (a card each time), "always" (only for Trusted and Family) or "off". Off is Chat.filesAllowed,
    // Always is Chat.filesAlways (FileLink skips the card only while their badge is Trusted or Family).
    function filesRule() {
        revision; Chat.chats
        if (tour) return "ask"
        if (!hook("filesAllowed", [key], true)) return "off"
        return hook("filesAlways", [key], false) && canAlways() ? "always" : "ask"
    }
    // Their trust badge is Trusted (Trusted direct on) or Family (in one of my Family groups).
    function canAlways() {
        if (tour || key === "") return false
        if (fileHas("mayAlwaysFrom")) return !!FileLink.mayAlwaysFrom(key)
        return (has("inFamilyGroup") && !!Chat.inFamilyGroup(key)) || !!Calls.trusted(key)
    }
    function setFilesRule(v) {
        if (tour) return
        if (v === "always" && !canAlways()) { Ui.say("Always is only for Trusted or Family.", true); return }
        if (has("setFilesAllowed")) Chat.setFilesAllowed(key, v !== "off")
        if (has("setFilesAlways")) Chat.setFilesAlways(key, v === "always")
        revision++
    }
    function filesWord(r) { return r === "always" ? "Always" : r === "off" ? "Off" : "Ask" }
    readonly property var fileChoices: canAlways() ? ["ask", "always", "off"] : ["ask", "off"]
    readonly property string tint: tour ? "" : (revision, Chat.chats, hook("chatColor", [key], ""))
    // Color vision: only the ones that look different (Theme.distinctSwatches).
    readonly property var colors: Theme.distinctSwatches(["", "#C2A25E", "#6E9CC8", "#6FAE8C", "#CF6F54", "#A58BC6"])
    // The folders they're in, by name.
    readonly property string folderLine: {
        if (tour || key === "" || !has("foldersOf")) return "None"
        revision; Chat.chats
        const mine = Chat.foldersOf(key) || []
        const names = (Chat.folders || []).filter(f => f && mine.indexOf(f.id) >= 0).map(f => f.name)
        return names.length ? names.join(", ") : "None"
    }
    readonly property string routeLine: tour ? "Wi-Fi" : (revision, Chat.chats, PeopleActions.routeWord(Chat.route(key)))

    // Each row: { id, sec (its section), label, value, note, toggle, swatches }.
    readonly property var rows: [
        { id: "nick", sec: "Name and note", label: "Nickname", value: nick !== "" ? nick : "None" },
        { id: "note", sec: "Name and note", label: "Note", value: note !== "" ? note : "None" },
        { id: "picture", sec: "Name and note", label: "Refresh picture", value: "Ask again" },
        { id: "days", sec: "This chat", label: "How long messages stay", value: tour ? "7 days" : (revision, Chat.chats, Chat.chatDays(key)) + " days" },
        { id: "pin", sec: "This chat", label: "Pin to top", toggle: true },
        { id: "archive", sec: "This chat", label: "Archive", toggle: true },
        { id: "color", sec: "This chat", label: "Chat color", swatches: true },
        { id: "folders", sec: "This chat", label: "Folders", value: folderLine },
        { id: "calls", sec: "They can", label: "Call me", toggle: true },
        { id: "files", sec: "They can", label: "Send me files", value: filesWord(filesRule()), choice: true,
          note: canAlways() ? "" : "Always is for Trusted or Family" },
        { id: "active", sec: "They can", label: "See when I'm active", toggle: true },
        { id: "check", sec: "Safety", label: "Compare emoji", value: tour ? "" : (revision, Chat.chats, Chat.isVerified(key)) ? "Verified" : "Not verified" },
        // Ultra privacy: nothing goes straight while it's on (the saved choice stays), and the row says so.
        { id: "direct", sec: "Safety", label: "Trusted direct", toggle: true,
          note: UltraPrivacy.active ? UltraPrivacy.offNote : "Faster. Shares your address" },
        { id: "route", sec: "Safety", label: "Reaching them by", value: routeLine, info: true },
        { id: "introduce", sec: "Safety", label: "Introduce", value: "" }
    ].concat(appRows)
    // 1.45: their app's version and platform, what it's missing, and a polite ask to update (contacts only).
    readonly property var appInfo: tour || key === "" || !has("appInfo") ? ({}) : (revision, Chat.chats, Chat.appInfo(key))
    readonly property var appRows: {
        const a = appInfo || {}
        if (!a.app) return []
        const out = [{ id: "app", sec: "Their app", label: "App", value: a.app + (a.older ? " (Older version)" : ""), info: true }]
        if (a.more) out.push({ id: "more", sec: "Their app", label: "More", value: a.more, info: true })
        if (a.older) out.push({ id: "askupdate", sec: "Their app", label: "Ask to update", value: "" })
        return out
    }
    // The rows in their framed sections: [{ title, start (the first row's index), rows }].
    readonly property var groups: {
        const out = []
        rows.forEach((r, i) => {
            if (!out.length || out[out.length - 1].title !== r.sec) out.push({ title: r.sec, start: i, rows: [] })
            out[out.length - 1].rows.push(r)
        })
        return out
    }

    function show(k, n, x) {
        key = k; name = n; extra = x || {}; focusIndex = 0; revision++
        view.contentY = 0
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
    function reopen() { const k = key, n = name, x = extra, at = focusIndex, y = view.contentY; show(k, n, x); focusIndex = at; view.contentY = y }
    function again() { panel.reopen(); if (Ui.main) Ui.main.restoreFocus() }

    function act(id) {
        if (tour) return
        const k = key
        if (id === "nick")
            Ui.ask("Nickname for " + Chat.realNameOf(k), "Only you see it. Empty: their name", Chat.nicknameOf(k), "Save", t => { Chat.setNickname(k, t); panel.revision++ }, false, true)
        else if (id === "note")
            Ui.ask("Note about " + shownName, "Only you see it", Chat.noteOf(k), "Save", t => { Chat.setNote(k, t); panel.revision++ }, false, true)
        else if (id === "color") pickColor(1)
        // Cap p2: asks them for their current picture, in case one never arrived.
        else if (id === "picture") { if (typeof Profile.askPicture === "function") Ui.say(Profile.askPicture(k)) }
        // The list opens over this panel (it stays open on the same row); back here after a pick or B.
        else if (id === "days") PeopleActions.daysMenu(k, () => panel.again())
        else if (id === "folders") PeopleActions.folderMenu(k, () => panel.again())
        else if (id === "pin") { if (has("pinChat")) Chat.pinChat(k, !isOn("pin")); revision++ }
        else if (id === "archive") { if (has("archiveChat")) Chat.archiveChat(k, !isOn("archive")); revision++ }
        else if (id === "files") pickFiles()
        else if (id === "check") PeopleActions.checkCode(k)
        else if (id === "route") PeopleActions.sayRoute(k)
        else if (id === "introduce") PeopleActions.introduce(k)
        else if (id === "askupdate") { Chat.sendPrivate(k, appInfo.ask); Ui.say("Asked " + shownName + " to update.") }
        else if (id === "more") Ui.say(appInfo.more + ".")
        else if (id === "direct" && UltraPrivacy.active) Ui.say(UltraPrivacy.offNote + ".")
        else if (id === "direct") { Calls.setTrusted(k, !Calls.trusted(k)); revision++ }
        else if (setters[id] && has(setters[id])) { Chat[setters[id]](k, !isOn(id)); revision++ }
    }
    // Send me files: Ask, Always (Trusted and Family only) or Off, in a list (Left and Right step through them too).
    function pickFiles() {
        const now = filesRule()
        const items = [{ name: "Ask", detail: "A card for each file", url: "ask", check: now === "ask" }]
        if (canAlways()) items.push({ name: "Always", detail: "From " + shownName + ", no card", url: "always", check: now === "always" })
        items.push({ name: "Off", detail: "Their files are refused", url: "off", check: now === "off" })
        Ui.pickFrom("Send me files", [{ header: canAlways() ? "" : "Always is only for Trusted or Family", items: items }],
                    v => { panel.setFilesRule(v); panel.again() }, () => panel.again())
    }
    function stepFiles(dir) {
        const list = fileChoices, at = Math.max(0, list.indexOf(filesRule()))
        const i = Math.max(0, Math.min(list.length - 1, at + dir))
        if (i !== at) setFilesRule(list[i])
    }
    function setColor(c) {
        if (tour || !has("setChatColor")) return
        Chat.setChatColor(key, c)
        revision++
    }
    function pickColor(dir) {
        const at = Math.max(0, colors.findIndex(c => Theme.sameSwatch(c, tint)))
        setColor(colors[(at + dir + colors.length) % colors.length])
    }
    // Keeps the focused row in view.
    function showRow(item) {
        if (!item) return
        const y = item.mapToItem(sectionsCol, 0, 0).y
        if (y - Theme.px(30) < view.contentY) view.contentY = Math.max(0, y - Theme.px(30))
        else if (y + item.height > view.contentY + view.height) view.contentY = Math.min(y + item.height - view.height, Math.max(0, view.contentHeight - view.height))
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.7) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(480))
        height: Math.min(parent.height - Theme.px(32), head.height + view.contentHeight + foot.height + Theme.px(56))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        Accessible.role: Accessible.Dialog
        Accessible.name: "Details, " + panel.shownName
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
                textFormat: Text.PlainText
                anchors.left: face.right; anchors.leftMargin: Theme.px(10)
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                elide: Text.ElideRight
                text: panel.shownName
                color: Theme.text
                font.family: Theme.display; font.pixelSize: Theme.fs(15); font.weight: Font.Bold; font.letterSpacing: Theme.px(1)
            }
        }

        // Framed sections, a heading over each, a seam between rows.
        Flickable {
            id: view
            anchors.top: head.bottom; anchors.topMargin: Theme.px(12)
            anchors.bottom: foot.top; anchors.bottomMargin: Theme.px(12)
            x: Theme.px(16)
            width: parent.width - Theme.px(32)
            contentHeight: sectionsCol.implicitHeight + (pinText.visible ? pinText.height + Theme.px(10) : 0)
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: sectionsCol
                width: view.width
                spacing: Theme.px(12)
                Repeater {
                    model: panel.groups
                    Column {
                        id: groupCol
                        readonly property var groupData: modelData
                        width: sectionsCol.width
                        spacing: Theme.px(6)
                        Text {
                            textFormat: Text.PlainText
                            leftPadding: Theme.px(4)
                            text: String(groupCol.groupData.title || "").toUpperCase()
                            color: Theme.section
                            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
                        }
                        Rectangle {
                            width: parent.width
                            height: rowCol.implicitHeight + 2
                            radius: Theme.radius
                            color: Theme.panelLow
                            border.color: Theme.line
                            Column {
                                id: rowCol
                                x: 1; y: 1
                                width: parent.width - 2
                                Repeater {
                                    model: groupCol.groupData.rows
                                    Rectangle {
                                        id: row
                                        readonly property int at: groupCol.groupData.start + index
                                        readonly property bool on: !!Ui.main && Ui.main.padInUse && panel.focusIndex === at
                                        onOnChanged: if (on) Qt.callLater(panel.showRow, row)
                                        width: rowCol.width
                                        height: Math.max(Theme.px(48), texts.implicitHeight + Theme.px(16))
                                        color: on ? Theme.focusFill : rowHover.hovered ? Theme.raised : "transparent"
                                        border.width: on ? Theme.focusWidth : 0
                                        border.color: Theme.accent
                                        Accessible.role: modelData.toggle ? Accessible.CheckBox : Accessible.Button
                                        Accessible.name: modelData.label + (modelData.value ? ", " + modelData.value : "") + (modelData.note ? ", " + modelData.note : "")
                                        Accessible.checked: !!modelData.toggle && panel.isOn(modelData.id)
                                        Accessible.onPressAction: panel.act(modelData.id)
                                        Rectangle { visible: index > 0; width: parent.width; height: 1; color: Theme.seam }
                                        Column {
                                            id: texts
                                            anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                                            anchors.right: right.left; anchors.rightMargin: Theme.px(10)
                                            anchors.verticalCenter: parent.verticalCenter
                                            spacing: Theme.px(2)
                                            Text {
                                                textFormat: Text.PlainText
                                                width: parent.width
                                                wrapMode: Text.WordWrap
                                                text: modelData.label || ""
                                                color: Theme.text
                                                font.family: Theme.body; font.pixelSize: Theme.fs(15)
                                            }
                                            Text {
                                                textFormat: Text.PlainText
                                                visible: !!modelData.note
                                                width: parent.width
                                                wrapMode: Text.WordWrap
                                                text: modelData.note || ""
                                                color: Theme.textMuted
                                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                                            }
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
                                                        readonly property bool picked: Theme.sameSwatch(panel.tint, modelData)
                                                        width: Theme.px(24); height: width; radius: Theme.radius
                                                        color: modelData === "" ? Theme.raised : modelData
                                                        border.width: picked ? Theme.px(2) : 1
                                                        border.color: picked ? Theme.text : Theme.line
                                                        Accessible.role: Accessible.RadioButton
                                                        Accessible.name: Theme.swatchName(modelData)
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
                                                    textFormat: Text.PlainText
                                                    anchors.verticalCenter: parent.verticalCenter
                                                    width: Math.min(implicitWidth, row.width * 0.45)
                                                    elide: Text.ElideRight
                                                    text: modelData.value || ""
                                                    color: Theme.textMuted
                                                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                                                }
                                                Icon { visible: !modelData.info; anchors.verticalCenter: parent.verticalCenter; name: "forward"; size: Theme.px(14); color: Theme.textMuted }
                                            }
                                        }
                                        HoverHandler { id: rowHover; cursorShape: Qt.PointingHandCursor }
                                        TapHandler { onTapped: { panel.focusIndex = row.at; if (!modelData.swatches) panel.act(modelData.id) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text {
                id: pinText
                textFormat: Text.PlainText
                visible: panel.pinWait
                y: sectionsCol.implicitHeight + Theme.px(10)
                width: view.width
                wrapMode: Text.WordWrap
                text: panel.shownName + " will be reached on this Wi-Fi again once they update or Nearby is on."
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(13)
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
        case "left": case "right":
            if (r && r.swatches) pickColor(b === "left" ? -1 : 1)
            else if (r && r.choice) stepFiles(b === "left" ? -1 : 1)
            return true
        case "a": if (r) act(r.id); return true
        case "b": back(); return true
        }
        return true
    }
}

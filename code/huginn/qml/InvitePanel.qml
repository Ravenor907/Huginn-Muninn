import QtQuick
import Huginn

// Group invite links, like Discord's. "create": the link with Copy, its QR code, and Edit invite link
// (Expire after, Max uses) to make another. "list": the group menu › Add people › Active links, every link with who made
// it, how many used it and the time left, and Revoke. Who can invite is under Group settings.
//
// HOOKS (cap j1, grouplinks.cpp), each checked with has() so this still works without them:
//   Chat.createGroupLink(g, ttlMs, maxUses) -> { link, id } or { error } (ttlMs 0: never; maxUses 0: no limit)
//   Chat.groupLinks(g) -> [{ id, byName, x (ms, 0 never), max, uses, link (only my own), mine }]
//   Chat.revokeGroupLink(g, id) -> "" when done, or what to tell the person
//   Chat.membersInvite(g) -> bool, Chat.setMembersInvite(g, on) -> "" or what to tell the person
// Joining by a link is Chat.joinWithCode (ChatPage's Add someone).
Item {
    id: panel
    property string gid: ""
    property string groupName: ""
    property bool amAdmin: false
    property string mode: ""          // "", "create" or "list"
    property string link: ""
    property bool editing: false
    property int expireAt: 5          // index into expireChoices (7 days)
    property int usesAt: 0            // index into useChoices (no limit)
    property int revision: 0
    visible: mode !== ""

    readonly property var expireChoices: [{ label: "30 minutes", s: 1800 }, { label: "1 hour", s: 3600 }, { label: "6 hours", s: 21600 },
                                          { label: "12 hours", s: 43200 }, { label: "1 day", s: 86400 }, { label: "7 days", s: 604800 }, { label: "Never", s: 0 }]
    readonly property var useChoices: [{ label: "No limit", n: 0 }, { label: "1 use", n: 1 }, { label: "5 uses", n: 5 }, { label: "10 uses", n: 10 },
                                       { label: "25 uses", n: 25 }, { label: "50 uses", n: 50 }, { label: "100 uses", n: 100 }]

    function has(fn) { return typeof Chat[fn] === "function" }
    readonly property bool ready: has("createGroupLink")
    readonly property bool membersCanInvite: gid !== "" && (revision, Chat.groups, has("membersInvite") ? !!Chat.membersInvite(gid) : false)
    readonly property var links: mode === "list" && has("groupLinks") ? (revision, Chat.groups, Chat.groupLinks(gid)) || [] : []

    function make() {
        if (!ready) { link = ""; return }
        const r = Chat.createGroupLink(gid, expireChoices[expireAt].s * 1000, useChoices[usesAt].n) || {}
        if (r.error) { link = ""; Ui.say(r.error, true) }
        else link = r.link || ""
        revision++
    }
    function openCreate() { mode = "create"; editing = false; make() }
    function openList() { mode = "list"; revision++ }
    function close() { mode = ""; if (Ui.main) Ui.main.restoreFocus() }
    function timeLeft(ms) {
        if (!ms) return "Never expires"
        const left = ms - Date.now()
        if (left <= 0) return "Expired"
        const h = Math.floor(left / 3600000)
        return h >= 48 ? Math.floor(h / 24) + " days" : h >= 1 ? h + (h === 1 ? " hour" : " hours") : Math.max(1, Math.floor(left / 60000)) + " minutes"
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; onClicked: panel.close() }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(panel.mode === "list" ? 640 : 440))
        height: Math.min(parent.height - Theme.px(32), col.implicitHeight + Theme.px(40))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent }
        Flickable {
            anchors.fill: parent
            anchors.margins: Theme.px(20)
            contentHeight: col.implicitHeight
            boundsBehavior: Flickable.StopAtBounds
            clip: true
            Column {
                id: col
                width: parent.width
                spacing: Theme.px(10)
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; wrapMode: Text.WordWrap
                    text: (panel.mode === "list" ? "Active links" : "Invite link").toUpperCase()
                    color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; wrapMode: Text.WordWrap
                    visible: text !== ""
                    text: !panel.ready ? "" : panel.mode === "list" ? "Revoke stops a link at once." : "Anyone with this link can join."
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }

                // ------------------------------------------------- create
                Item {
                    visible: panel.mode === "create" && panel.ready
                    width: parent.width; height: Theme.px(40)
                    Rectangle {
                        anchors.left: parent.left; anchors.right: copyBtn.left; anchors.rightMargin: Theme.px(6)
                        height: parent.height
                        radius: Theme.radius
                        color: Theme.groundDeep
                        border.color: Theme.line
                        Text {
                            textFormat: Text.PlainText
                            anchors.fill: parent; anchors.leftMargin: Theme.px(10); anchors.rightMargin: Theme.px(10)
                            verticalAlignment: Text.AlignVCenter
                            elide: Text.ElideMiddle
                            text: panel.link || "No link yet"
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                        }
                    }
                    HButton {
                        id: copyBtn
                        anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                        kind: "primary"; text: "Copy"
                        onClicked: { if (panel.link !== "") { Chat.copyText(panel.link); Ui.say("Invite link copied.") } }
                    }
                }
                Text {
                    textFormat: Text.PlainText
                    visible: panel.mode === "create" && panel.ready
                    text: "Expires after " + panel.expireChoices[panel.expireAt].label.toLowerCase() + " · " + panel.useChoices[panel.usesAt].label.toLowerCase()
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
                }
                Rectangle {
                    visible: panel.mode === "create" && panel.ready && panel.link !== ""
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: Math.min(parent.width, Theme.px(200)); height: width; color: "#E4E1DA"   // the light paper the code is drawn on, in every look
                    Image { anchors.fill: parent; anchors.margins: Theme.px(10); smooth: false; source: parent.visible ? "image://qr/" + encodeURIComponent(panel.link) : "" }
                }
                Text {
                    textFormat: Text.PlainText
                    visible: panel.mode === "create" && panel.ready
                    text: panel.editing ? "Edit invite link ▴" : "Edit invite link ▾"
                    color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                    Accessible.role: Accessible.Button
                    Accessible.name: "Edit invite link"
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: panel.editing = !panel.editing }
                }
                Column {
                    visible: panel.mode === "create" && panel.ready && panel.editing
                    width: parent.width
                    spacing: Theme.px(6)
                    Text { textFormat: Text.PlainText; text: "EXPIRE AFTER"; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold }
                    Choices { width: parent.width; options: panel.expireChoices; at: panel.expireAt; onPicked: i => panel.expireAt = i }
                    Text { textFormat: Text.PlainText; topPadding: Theme.px(6); text: "MAX USES"; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold }
                    Choices { width: parent.width; options: panel.useChoices; at: panel.usesAt; onPicked: i => panel.usesAt = i }
                    HButton { text: "Make a new link"; onClicked: panel.make() }
                }

                // ------------------------------------------------- list
                Column {
                    visible: panel.mode === "list"
                    width: parent.width
                    // The table's heading.
                    Row {
                        visible: panel.links.length > 0
                        width: parent.width; height: Theme.px(28)
                        Cell { w: 0.34; text: "LINK"; head: true }
                        Cell { w: 0.24; text: "MADE BY"; head: true }
                        Cell { w: 0.12; text: "USES"; head: true }
                        Cell { w: 0.16; text: "TIME LEFT"; head: true }
                    }
                    Repeater {
                        model: panel.links
                        Rectangle {
                            width: parent.width; height: Theme.px(40)
                            color: index % 2 ? "transparent" : Theme.panelLow
                            Row {
                                anchors.fill: parent
                                Cell { w: 0.34; text: modelData.link ? String(modelData.link).slice(-12) : String(modelData.id || "").slice(0, 8) }
                                Cell { w: 0.24; text: modelData.mine ? "You" : modelData.byName || "" }
                                Cell { w: 0.12; text: (modelData.uses || 0) + (modelData.max ? " / " + modelData.max : "") }
                                Cell { w: 0.16; text: panel.timeLeft(modelData.x || 0) }
                                Item {
                                    width: parent.width * 0.14; height: parent.height
                                    HButton {
                                        anchors.centerIn: parent
                                        kind: "danger"; text: "Revoke"; fontSize: 12; implicitHeight: Theme.px(28)
                                        onClicked: { const r = Chat.revokeGroupLink(panel.gid, modelData.id); if (r) Ui.say(r, true); else Ui.say("Revoked."); panel.revision++ }
                                    }
                                }
                            }
                        }
                    }
                    Text {
                        textFormat: Text.PlainText
                        visible: panel.ready && panel.links.length === 0
                        topPadding: Theme.px(6)
                        text: "No invite links yet."
                        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                    }
                }
                Row {
                    anchors.right: parent.right
                    spacing: Theme.px(8)
                    HButton { visible: panel.mode === "list" && panel.ready; text: "Make a link"; onClicked: panel.openCreate() }
                    HButton { text: "Done"; kind: "primary"; onClicked: panel.close() }
                }
            }
        }
    }

    component Cell: Text {
        textFormat: Text.PlainText
        property real w: 0.2
        property bool head: false
        width: parent ? parent.width * w : 0
        height: parent ? parent.height : 0
        leftPadding: Theme.px(8)
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
        color: head ? Theme.textMuted : Theme.text
        font.family: Theme.body; font.pixelSize: Theme.fs(head ? 11 : 13); font.weight: head ? Font.DemiBold : Font.Normal
    }
    component Choices: Flow {
        id: flow
        property var options: []
        property int at: 0
        signal picked(int i)
        spacing: Theme.px(6)
        Repeater {
            model: flow.options
            Rectangle {
                readonly property bool on: index === flow.at
                width: optText.implicitWidth + Theme.px(20); height: Theme.px(30)
                radius: Theme.radius
                color: on ? Theme.accentFill : optHover.hovered ? Theme.raised : "transparent"
                border.color: on ? Theme.accent : Theme.line
                Accessible.role: Accessible.RadioButton
                Accessible.name: modelData.label
                Accessible.checked: on
                Text { textFormat: Text.PlainText; id: optText; anchors.centerIn: parent; text: modelData.label; color: parent.on ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                HoverHandler { id: optHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: flow.picked(index) }
            }
        }
    }

    function handleButton(b) { if (b === "b" || b === "a") close(); return true }
}

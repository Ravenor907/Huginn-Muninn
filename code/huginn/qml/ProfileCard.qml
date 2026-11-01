import QtQuick
import Huginn

// Someone's profile card (a click on a name or picture): their avatar and name, the note you gave
// them (only on this computer), how they are right now, and Message, Call and the three dots, their
// one menu (from a group, the member menu). D-pad moves along the buttons, A chooses, B closes.
Item {
    id: card
    property string key: ""
    property string name: ""
    property var extra: ({})
    property int focusIndex: 0
    visible: false
    anchors.fill: parent

    readonly property bool me: key === "me"
    // Opened straight into Your picture (Settings › You): only the picker shows, and closing it closes this.
    property bool pickingOnly: false
    readonly property bool tour: !!extra.tour
    // Chat.chats and Chat.people make these follow renames, notes and folder changes.
    readonly property bool contact: !me && !tour && (Chat.people, Chat.isContact(key))
    readonly property string nick: me || tour ? "" : (Chat.chats, Chat.nicknameOf(key))
    readonly property string realName: me ? Chat.username : tour ? name : (Chat.chats, Chat.realNameOf(key)) || name
    readonly property string shownName: me ? Chat.username : nick !== "" ? nick : realName
    readonly property string note: me ? "" : tour ? (extra.note || "") : (Chat.people, Chat.noteOf(key))
    readonly property string status: {
        if (extra.room) return "In " + extra.room
        if (me) return "Online (you)"
        if (tour) return extra.status === "online" ? "Online" : ""
        const p = Ui.main ? Ui.main.presenceOf(key) : "offline"
        return p === "online" ? "Online" : p === "idle" ? "Idle" : ""
    }
    readonly property bool canCall: !me && !tour && Calls.canCall(key)

    // Every button, in the order the controller steps through them. Yours has none: Settings › You changes you.
    readonly property var actions: me ? [] : [
        { id: "message", label: "Message", icon: "thread", on: contact || tour, main: true },
        { id: "call", label: "Call", icon: "call", on: canCall || tour, main: true },
        { id: "more", label: "More", icon: "more", on: true, main: true }
    ]
    readonly property var mainActions: actions

    function show(k, n, x) {
        key = k; name = n; extra = x || {}
        pickingOnly = false
        focusIndex = Math.max(0, actions.findIndex(a => a.on))
        visible = true
    }
    function close() { visible = false; pickingOnly = false; if (picker.visible) picker.visible = false }
    // Your picture on its own, from Settings.
    function pickPicture() {
        if (!visible) { key = "me"; name = Chat.username; extra = {}; pickingOnly = true; visible = true }
        picker.open()
    }
    function run(id) {
        const k = key, n = shownName
        const a = actions.find(x => x.id === id)
        if (!a || !a.on) {
            if (id === "message") Ui.say(n + " isn't a contact yet.", true)
            else if (id === "call") Ui.say(n + " can't be called yet.", true)
            return
        }
        if (tour && id !== "more") return
        if (id === "message") { close(); Ui.openPage("dms", { open: k }) }
        else if (id === "call") { const w = Calls.call(k); if (w !== "") Ui.say(w, true); else close() }
        // The three dots: a group member's menu from a group, otherwise the contact menu.
        else if (id === "more") {
            const f = extra.more, t = tour
            close()
            if (f) f()
            else PeopleActions.contactMenu(k, n, { tour: t })
        }
    }
    function handleButton(b) {
        if (picker.visible) return picker.handleButton(b)
        const n = actions.length
        if (b === "b") close()
        else if (b === "a") { const a = actions[focusIndex]; if (a) run(a.id) }
        else if (b === "left" || b === "up") focusIndex = Math.max(0, focusIndex - 1)
        else if (b === "right" || b === "down") focusIndex = Math.min(n - 1, focusIndex + 1)
        return true
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.6); visible: !card.pickingOnly }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: card.close() }
    // Your picture: choose, crop, use an initial and a color, or remove it.
    PicturePicker {
        id: picker
        z: 2
        onClosed: if (card.pickingOnly) card.close()
    }

    Rectangle {
        id: box
        visible: !card.pickingOnly
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(60), Theme.px(420))
        // Never taller than the window: what doesn't fit under the picture scrolls.
        height: Math.min(parent.height - Theme.px(32), face.y + face.height + Theme.px(10) + body.implicitHeight + Theme.px(14))
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        clip: true
        Accessible.role: Accessible.Dialog
        Accessible.name: card.shownName
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

        Rectangle {
            id: banner
            width: parent.width
            height: Theme.px(74)
            color: Theme.accentFill
            Image {
                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                source: "assets/raven-gold.png"
                width: Theme.px(44); height: width
                sourceSize.width: 128; sourceSize.height: 128
                opacity: 0.35
            }
        }
        // The avatar sits over the banner's edge.
        Rectangle {
            id: face
            x: Theme.px(20)
            y: banner.height - height / 2
            width: Theme.px(84); height: width; radius: width / 2
            color: Theme.panel
            Avatar {
                anchors.centerIn: parent
                key: card.tour ? "" : card.key
                name: card.shownName
                size: parent.width - Theme.px(10)
                backdrop: Theme.panel
            }
            Rectangle {
                visible: card.status !== ""
                anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.rightMargin: Theme.px(4); anchors.bottomMargin: Theme.px(4)
                width: Theme.px(20); height: width; radius: width / 2
                color: card.status.startsWith("In ") ? Theme.accent : card.status === "Idle" ? Theme.idle : Theme.online
                border.width: Theme.px(4); border.color: Theme.panel
            }
        }
        HButton {
            anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
            y: banner.height + Theme.px(10)
            text: "Close"; fontSize: 13
            implicitHeight: Theme.px(32)
            onClicked: card.close()
        }

        Flickable {
            id: bodyView
            anchors.top: face.bottom; anchors.topMargin: Theme.px(10)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(14)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            contentWidth: width
            contentHeight: body.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
        Column {
            id: body
            width: bodyView.width
            spacing: Theme.px(10)

            Column {
                width: parent.width
                spacing: Theme.px(2)
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    elide: Text.ElideRight
                    text: card.shownName
                    color: Theme.text
                    font.family: Theme.display; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold
                }
                Text {
                    textFormat: Text.PlainText
                    visible: text !== ""
                    width: parent.width
                    elide: Text.ElideRight
                    text: card.nick !== "" ? "Calls themselves " + card.realName : ""
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
                Text {
                    textFormat: Text.PlainText
                    visible: text !== ""
                    width: parent.width
                    elide: Text.ElideRight
                    text: [card.status, card.extra.group ? (card.extra.owner ? "Owner of " : card.extra.admin ? "Admin in " : "In ") + card.extra.group : ""].filter(t => t !== "").join(" · ")
                    color: card.status.startsWith("In ") ? Theme.accent : Theme.textSoft
                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
            }
            Rectangle { width: parent.width; height: 1; color: Theme.line; visible: card.note !== "" }
            Column {
                visible: card.note !== ""
                width: parent.width
                spacing: Theme.px(3)
                Label { text: "NOTE" }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    wrapMode: Text.WordWrap
                    maximumLineCount: 5
                    elide: Text.ElideRight
                    text: card.note
                    color: Theme.text
                    font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
            }
            // Message, Call and the three dots.
            Row {
                visible: card.mainActions.length > 0
                width: parent.width
                spacing: Theme.px(8)
                Repeater {
                    model: card.mainActions
                    ActionButton {
                        width: (parent.width - Theme.px(8) * (card.mainActions.length - 1)) / card.mainActions.length
                        action: modelData || ({})
                        primary: true
                    }
                }
            }
        }
        }
    }
    // Scrolls the card so the button the controller is on is in view.
    function reveal(item) {
        const y = item.mapToItem(body, 0, 0).y
        if (y < bodyView.contentY) bodyView.contentY = y
        else if (y + item.height > bodyView.contentY + bodyView.height) bodyView.contentY = Math.min(bodyView.contentHeight - bodyView.height, y + item.height - bodyView.height)
    }

    component Label: Text {
        textFormat: Text.PlainText
        color: Theme.textMuted
        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
    }
    component ActionButton: Rectangle {
        id: ab
        property var action: ({})
        property bool primary: false
        readonly property bool focused: !!card.actions[card.focusIndex] && card.actions[card.focusIndex].id === action.id
        width: Math.min((ab.action.icon ? Theme.px(24) : 0) + abText.implicitWidth + Theme.px(24), body.width)
        height: Math.max(Theme.px(primary ? 46 : 36), abText.implicitHeight + Theme.px(12))
        onFocusedChanged: if (focused) Qt.callLater(card.reveal, ab)
        radius: Theme.radius
        opacity: action.on ? 1 : 0.45
        color: primary && action.on ? (abHover.hovered ? Theme.accentFill : Theme.raised) : abHover.hovered ? Theme.tile : Theme.raised
        border.width: focused ? Theme.focusWidth : 1
        border.color: focused ? Theme.accent : primary && action.on ? Theme.accent : Theme.lineMuted
        Accessible.role: Accessible.Button
        Accessible.name: action.label || ""
        Accessible.onPressAction: card.run(action.id)
        Row {
            id: abRow
            anchors.centerIn: parent
            spacing: Theme.px(6)
            Icon { visible: !!ab.action.icon; anchors.verticalCenter: parent.verticalCenter; name: ab.action.icon || "file"; size: Theme.px(18); color: ab.primary && ab.action.on ? Theme.accent : Theme.textSoft }
            Text { textFormat: Text.PlainText; id: abText; anchors.verticalCenter: parent.verticalCenter; width: Math.min(implicitWidth, ab.width - Theme.px(24) - (ab.action.icon ? Theme.px(24) : 0)); elide: Text.ElideRight; text: ab.action.label || ""; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(ab.primary ? 15 : 13); font.weight: Font.DemiBold }
        }
        HoverHandler { id: abHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: { card.focusIndex = card.actions.findIndex(a => a.id === ab.action.id); card.run(ab.action.id) } }
    }
}

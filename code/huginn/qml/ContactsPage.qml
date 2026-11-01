import QtQuick
import Huginn

// Contacts (on the rail): everyone you're in touch with or met close by, who's blocked, and who's
// in the Trash (kept 30 days). Each person's one menu (PeopleActions.contactMenu) opens from their
// three dots, a click, a right click, A or LT.
Rectangle {
    id: page
    // Autumn: a frosted pane the leaves fall behind; the other looks are solid.
    color: Theme.sheet
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int row: 0
    function setArgs(a) {}

    readonly property var people: Chat.people.concat(Chat.trash.map(t => ({ key: t.key, name: t.name, trashed: true, daysLeft: t.daysLeft, contact: false, verified: false, blocked: false })))
    readonly property var person: people[row] || null
    readonly property var prompts: [{ key: "a", label: "Menu" }, { key: "lt", label: "Menu" }, { key: "b", label: "Back" }]
    readonly property string status: ""

    function stateText(p) {
        if (p.trashed) return "In the Trash"
        return p.blocked ? "Blocked" : (p.contact ? "Contact" : "Met close by") + (p.verified ? " · Verified" : "")
    }

    // ------------------------------------------------------------- middle column: people
    Rectangle {
        id: middle
        // Portrait: the list on top, the rest under it.
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.portrait ? parent.height * 0.45 : Ui.dock
        width: Theme.portrait ? parent.width : Theme.listWidth
        color: Theme.column
        PageHeader {
            id: header
            width: parent.width
            title: "Contacts"
            // Add someone: their code or link (on the Chats page, where they then show).
            Rectangle {
                visible: Vault.exists && !Vault.screenLocked
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(28); height: width
                radius: Theme.radius
                color: addHover.hovered ? Theme.raised : "transparent"
                Accessible.role: Accessible.Button
                Accessible.name: "Add someone"
                Accessible.onPressAction: Ui.openPage("dms", { add: true })
                Icon { anchors.centerIn: parent; name: "plus"; size: Theme.px(18); lineWidth: 2; color: addHover.hovered ? Theme.text : Theme.textMuted }
                HoverHandler { id: addHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: Ui.openPage("dms", { add: true }) }
            }
        }

        ListView {
            id: list
            anchors.top: header.bottom; anchors.topMargin: Theme.px(10)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(10)
            x: Theme.px(10); width: parent.width - Theme.px(20)
            spacing: Theme.px(3)
            clip: true
            model: page.people
            currentIndex: page.row
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            delegate: Rectangle {
                readonly property bool on: page.active && page.row === index
                width: list.width
                height: Theme.px(44)
                radius: Theme.radius
                color: on ? Theme.tile : hover.hovered ? Theme.raised : "transparent"
                border.width: on ? Theme.focusWidth : 0
                border.color: Theme.accent
                Accessible.role: Accessible.ListItem
                Accessible.name: modelData.name + ", " + page.stateText(modelData)
                Avatar {
                    id: ava
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    size: Theme.px(34)
                    key: modelData.blocked || modelData.trashed ? "" : modelData.key || ""
                    name: modelData.name
                    dim: !!modelData.blocked || !!modelData.trashed
                    showDot: !modelData.blocked && !modelData.trashed && Chat.activeStatus
                    backdrop: parent.on ? Theme.tile : Theme.column
                }
                Column {
                    anchors.left: ava.right; anchors.leftMargin: Theme.px(10); anchors.right: rowMore.left; anchors.rightMargin: Theme.px(6)
                    anchors.verticalCenter: parent.verticalCenter
                    Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                    Text { textFormat: Text.PlainText; width: parent.width; elide: Text.ElideRight; text: page.stateText(modelData); color: modelData.blocked ? Theme.danger : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
                HoverHandler { id: hover }
                TapHandler {
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onTapped: { page.row = index; page.choose() }
                    onLongPressed: { page.row = index; page.choose() }
                }
                MoreButton {
                    id: rowMore
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
                    anchors.verticalCenter: parent.verticalCenter
                    label: "More for " + modelData.name
                    onClicked: { page.row = index; page.choose() }
                }
            }
        }
        Text {
            textFormat: Text.PlainText
            anchors.top: header.bottom; anchors.topMargin: Theme.px(16)
            x: Theme.px(20); width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            visible: list.count === 0
            text: "Nobody yet. Add someone with +"
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
    }

    // ------------------------------------------------------------- main column: the person
    Item {
        anchors.left: Theme.portrait ? parent.left : middle.right
        anchors.right: parent.right
        anchors.top: Theme.portrait ? middle.bottom : parent.top
        anchors.bottom: parent.bottom
        PageHeader { id: mainHead; width: parent.width; carved: false; title: page.person ? page.person.name : "" }
        Column {
            visible: !!page.person
            anchors.top: mainHead.bottom
            anchors.topMargin: Theme.px(28)
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width - Theme.px(48), Theme.px(520))
            spacing: Theme.px(12)
            Avatar {
                anchors.horizontalCenter: parent.horizontalCenter
                size: Theme.px(84)
                key: page.person && !page.person.blocked ? page.person.key || "" : ""
                name: page.person ? page.person.name : ""
                dim: !!page.person && !!page.person.blocked
                showDot: !!page.person && !page.person.blocked && Chat.activeStatus
                backdrop: Theme.ground
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width; horizontalAlignment: Text.AlignHCenter
                text: page.person ? page.stateText(page.person) : ""
                color: page.person && page.person.blocked ? Theme.danger : Theme.textSoft
                font.family: Theme.body; font.pixelSize: Theme.fs(17)
            }
            HButton {
                anchors.horizontalCenter: parent.horizontalCenter
                text: page.person && page.person.trashed ? "Restore or delete" : "Menu"
                onClicked: page.choose()
            }
        }
    }

    // A person's one menu (the same as in Chats); someone in the Trash gets Restore or Delete for good.
    function choose() {
        const p = people[row]
        if (!p) return
        if (p.trashed) {
            Ui.pickFrom(p.name, [{ header: p.daysLeft + (p.daysLeft === 1 ? " day left" : " days left"), items: [
                { name: "Restore", url: "restore" },
                { name: "Delete for good", url: "delete", danger: true }] }], a => {
                if (a === "restore") { Chat.restore(p.key); Ui.say(p.name + " is back.") }
                else if (a === "delete") Ui.sure("Delete " + p.name + " for good?", "Can't be undone.", "Delete for good",
                                                 () => { Chat.deleteForGood(p.key); Ui.say(p.name + " deleted for good.") }, false, true)
            })
            return
        }
        PeopleActions.contactMenu(p.key, p.name, {})
    }
    function handleButton(b) {
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(people.length - 1, row + 1); return true
        case "a": case "x": choose(); return true
        case "b": Ui.closePage(); return true
        }
        return false
    }
}

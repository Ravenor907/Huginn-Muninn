import QtQuick
import Huginn

// A group's member list, on the right like Discord: who's in a voice room, who's online (Active
// status, cap m1), who's idle (was online in the last half hour) and everyone else, each with a dot
// that says it and a line that says it in words. A click, a right click, the three dots, A or LT
// open the member's one menu (GroupPage.memberMenu). The controller moves through `ordered`
// (GroupPage's members zone).
Rectangle {
    id: list
    property var host: null                 // GroupPage
    readonly property var members: host ? host.members : []
    readonly property string gid: host ? host.gid : ""
    readonly property bool tour: !!host && host.isTour
    readonly property bool statusOn: tour || Chat.activeStatus
    property alias flick: memFlick
    color: Theme.column

    // Who is in which voice room or group call of this group: key -> its name.
    readonly property var voiceIn: {
        const out = {}
        if (!host) return out
        if (tour) { out["j"] = "Lounge"; out["s"] = "Lounge"; return out }
        if (host.locked || gid === "") return out
        Rooms.presence; Rooms.people
        host.channels.filter(c => c.type === "voice").forEach(c => Rooms.whoIn(gid, c.id).forEach(p => out[p.me ? "me" : p.key] = c.name))
        Rooms.groupCalls(gid).forEach(c => Rooms.whoIn(gid, c.ch).forEach(p => out[p.me ? "me" : p.key] = "Group call"))
        return out
    }
    // Someone in a room who went quiet (their app stopped saying it's there): "Reconnecting" until they're back or gone.
    readonly property var voiceQuiet: {
        const out = {}
        if (!host || tour || host.locked || gid === "") return out
        Rooms.presence; Rooms.people
        host.channels.filter(c => c.type === "voice").forEach(c => Rooms.whoIn(gid, c.id).forEach(p => { if (p.state === "reconnecting") out[p.key] = true }))
        Rooms.groupCalls(gid).forEach(c => Rooms.whoIn(gid, c.ch).forEach(p => { if (p.state === "reconnecting") out[p.key] = true }))
        return out
    }
    readonly property var tourStatus: ({ "t": "idle", "m": "online" })
    function statusOf(m) {
        const k = m.me ? "me" : m.key
        if (voiceIn[k] !== undefined) return "voice"
        if (m.me) return "online"
        if (tour) return tourStatus[m.key] || "offline"
        if (!Ui.main) return "offline"
        return Ui.main.presenceOf(m.key)
    }
    function statusText(m, st) {
        if (st === "voice") return !m.me && voiceQuiet[m.key] ? "Reconnecting" : "In " + voiceIn[m.me ? "me" : m.key]
        if (st === "online") return m.me ? "Online (you)" : "Online"
        if (st === "idle") return "Idle"
        return ""
    }
    // Sections in the order they're drawn. With Active status off nobody's shows, so the rest are
    // simply "Members".
    readonly property var sections: {
        const withStatus = members.map(m => Object.assign({ status: statusOf(m) }, m))
        const by = s => withStatus.filter(m => m.status === s)
        if (!statusOn)
            return [{ title: "IN A VOICE ROOM", people: by("voice") },
                    { title: "MEMBERS", people: withStatus.filter(m => m.status !== "voice") }].filter(s => s.people.length)
        return [{ title: "IN A VOICE ROOM", people: by("voice") }, { title: "ONLINE", people: by("online") },
                { title: "IDLE", people: by("idle") }, { title: "OFFLINE", people: by("offline") }].filter(s => s.people.length)
    }
    readonly property var ordered: sections.reduce((all, s) => all.concat(s.people), [])

    Flickable {
        id: memFlick
        anchors.fill: parent
        anchors.topMargin: Theme.px(8)
        anchors.leftMargin: Theme.px(8)
        anchors.rightMargin: Theme.px(8)
        contentHeight: memCol.implicitHeight + Theme.px(12)
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: memCol
            width: parent.width
            spacing: Theme.px(2)
            Text {
                textFormat: Text.PlainText
                visible: !list.statusOn
                width: parent.width
                wrapMode: Text.WordWrap
                bottomPadding: Theme.px(8)
                text: "Active status is off."
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
            Repeater {
                model: list.sections
                Column {
                    id: sec
                    readonly property int first: list.sections.slice(0, index).reduce((n, s) => n + s.people.length, 0)
                    width: memCol.width
                    spacing: Theme.px(2)
                    Text {
                        textFormat: Text.PlainText
                        width: parent.width
                        elide: Text.ElideRight
                        leftPadding: Theme.px(8)
                        topPadding: Theme.px(index === 0 ? 12 : 20)
                        bottomPadding: Theme.px(4)
                        text: modelData.title + " - " + modelData.people.length
                        color: Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(0.5)
                    }
                    Repeater {
                        model: modelData.people
                        MemberRow { person: modelData || ({}); at: sec.first + index }
                    }
                }
            }
        }
    }

    component MemberRow: Rectangle {
        id: mr
        property var person: ({})
        property int at: 0
        readonly property bool on: !!list.host && list.host.active && list.host.zone === "members" && list.host.memRow === at
        readonly property string status: person.status || "offline"
        readonly property string line: list.statusText(person, status)
        width: memCol.width
        height: Theme.px(42)
        radius: Theme.radius
        color: on ? Theme.tile : memHover.hovered ? Theme.raised : "transparent"
        border.width: on ? Theme.focusWidth : 0
        border.color: Theme.accent
        Accessible.role: Accessible.ListItem
        Accessible.name: (person.name || "") + (person.owner ? ", owner" : person.admin ? ", admin" : "") + (person.checked && !!list.host && list.host.family ? ", checked" : "")
                         + (line !== "" ? ", " + line : list.statusOn ? ", offline" : "")
        Avatar {
            id: mface
            anchors.left: parent.left; anchors.leftMargin: Theme.px(8)
            anchors.verticalCenter: parent.verticalCenter
            size: Theme.px(32)
            key: list.tour ? "" : (mr.person.me ? "me" : mr.person.key || "")
            name: mr.person.name
            dim: mr.status === "offline" && list.statusOn
            // The status dot (or a speaker for a voice room), cut out of the avatar's corner.
            Rectangle {
                visible: mr.status !== "offline"
                anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.rightMargin: -Theme.px(3); anchors.bottomMargin: -Theme.px(3)
                width: Theme.px(mr.status === "voice" ? 17 : 13); height: width; radius: width / 2
                color: mr.status === "voice" ? Theme.accentFill : mr.status === "online" ? Theme.online : Theme.column
                border.width: Theme.px(2.5)
                border.color: Theme.column
                Icon {
                    visible: mr.status === "voice"
                    anchors.centerIn: parent
                    name: "speaker"; size: Theme.px(11); color: Theme.accent
                }
                Icon {
                    visible: mr.status === "idle"
                    anchors.centerIn: parent
                    name: "moon"; size: Theme.px(12); lineWidth: 2.4; color: Theme.idle
                }
            }
        }
        Column {
            anchors.left: mface.right; anchors.leftMargin: Theme.px(12)
            anchors.right: checkedMark.visible ? checkedMark.left : crown.visible ? crown.left : memMore.left; anchors.rightMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            spacing: 0
            Text {
                textFormat: Text.PlainText
                width: parent.width
                elide: Text.ElideRight
                text: (mr.person.name || "") + (mr.person.me ? " (you)" : "")
                color: mr.status === "offline" && list.statusOn ? Theme.textMuted : Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium
            }
            Text {
                textFormat: Text.PlainText
                visible: text !== ""
                width: parent.width
                elide: Text.ElideRight
                text: mr.line !== "" ? mr.line : mr.person.owner ? "Owner" : mr.person.admin ? "Admin" : ""
                color: mr.status === "voice" ? Theme.accent : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }
        // A Family group: this member's safety code was matched.
        Icon {
            id: checkedMark
            visible: !!list.host && !!list.host.family && !!mr.person.checked
            anchors.right: crown.visible ? crown.left : memMore.left; anchors.rightMargin: Theme.px(4)
            anchors.verticalCenter: parent.verticalCenter
            name: "check"; size: Theme.px(15)
            color: Theme.online
        }
        Icon {
            id: crown
            visible: !!mr.person.owner || !!mr.person.admin
            anchors.right: memMore.left; anchors.rightMargin: Theme.px(4)
            anchors.verticalCenter: parent.verticalCenter
            name: mr.person.owner ? "crown" : "shield"; size: Theme.px(15)
            color: mr.person.owner ? Theme.accent : Theme.textMuted
        }
        HoverHandler { id: memHover; cursorShape: Qt.PointingHandCursor }
        // A click, a right click or a hold: the member's menu, the same as the three dots and LT.
        // A right click or a long press: the small right-click menu where it was (Profile, Message, Mute for me, Volume...).
        TapHandler {
            acceptedButtons: Qt.LeftButton | Qt.RightButton
            onTapped: (point, button) => { if (button === Qt.RightButton) mr.contextAt(point.position); else mr.openMenu() }
            onLongPressed: mr.contextAt(point.position)
        }
        function contextAt(p) {
            if (!list.host) return
            list.host.zone = "members"; list.host.memRow = mr.at
            if (typeof list.host.memberMenuAt === "function") list.host.memberMenuAt(mr, p, mr.person)
            else list.host.memberMenu(mr.person)
        }
        function openMenu() {
            if (!list.host) return
            list.host.zone = "members"; list.host.memRow = mr.at
            list.host.memberMenu(mr.person)
        }
        MoreButton {
            id: memMore
            anchors.right: parent.right; anchors.rightMargin: Theme.px(4)
            anchors.verticalCenter: parent.verticalCenter
            label: "More for " + (mr.person.name || "")
            onClicked: mr.openMenu()
        }
    }
}

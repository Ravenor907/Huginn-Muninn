import QtQuick
import Huginn

// Shared by groups and 1:1 chats (threads and pins).
// One message: name, time (and "(edited)"), for mine a check mark with how many have read it in a
// circle; the quoted message of a reply, the text or picture or voice, reactions, "N replies" (a
// thread), and the tools over its corner (pointed at, or picked with the controller). An @mention
// of me is lit up. Only what a message has is made: the quote, picture, GIF, voice, file card, album or poll,
// reactions, replies and the tools over its corner are each loaded when present (or pointed at) and gone otherwise,
// so a plain message is a few texts. Nothing in a row runs a timer or an animation while it sits there.
Rectangle {
    id: msg
    // The page it sits on (GroupPage or ChatPage): its clock, actions, pins and photo viewer.
    property var host: null
    property var m: ({})
    property int at: 0
    property bool picked: false
    property int lineH: 0
    property bool hoverTools: false
    property bool inThread: false
    property color accent: Theme.accent   // a chat's own color on its page
    // Under the one before from the same person within 7 minutes: no name or picture again (Discord).
    property bool grouped: false
    signal options()
    signal callBack()        // a call's line was clicked
    signal chosen()          // clicked: the page picks it (its tools show)
    // A click on the name or picture: their profile card (in a group, with their member menu).
    function showWho() {
        const k = whoText.key
        if (k === "" || (host && host.isTour && !host.showMember)) return
        if (host && host.showMember) host.showMember(k, who)
        else Ui.showProfile(k, who, {})
    }
    property string who: m.name || "Someone"
    // Muted for me with their messages folded (cap g2's menus): "Muted, tap to show" until it's tapped.
    property string shownId: ""
    readonly property bool folded: !!m && !!m.folded && shownId !== (m.id || "")
    function unfold() { shownId = m.id || "" }
    // Safe while a list takes the row away (its message is gone by then) and the theme changes.
    readonly property bool mine: !!m && !!m.mine
    // Settings › Look: Compact drops the pictures and the room between messages; Spacious adds room.
    readonly property int pad: grouped ? Theme.px(1) : Theme.px(Theme.compact ? 2 : Theme.spacious ? 8 : 5)
    height: m.system ? note.height + Theme.px(12) : folded ? lineH + foldBox.height + Theme.px(12) : lineH + Math.max(face.visible ? face.height : 0, col.implicitHeight) + pad * 2
    // What this message has, worked out once for the parts below.
    readonly property bool live: !!m && !m.system && !m.unsent
    readonly property bool hasPhoto: !!m && !!m.photo && !m.voice && !m.file && !m.unsent && !m.video && !m.album
    radius: 0
    // Find in this chat: 1 a match, 2 the match shown (the page's find bar).
    property int found: 0
    color: picked ? Theme.focusFill : found === 2 ? Theme.accentFill : m.mention ? Theme.accentFill : msgHover.hovered ? Theme.panelLow : found === 1 ? Theme.panel : "transparent"
    border.width: picked ? Theme.focusWidth : 0
    border.color: msg.accent
    Rectangle { visible: (!!msg.m.mention || msg.found > 0) && !msg.picked; x: 0; y: msg.lineH; width: Theme.px(3); height: parent.height - msg.lineH
                color: msg.found === 1 && !msg.m.mention ? Theme.lineMuted : msg.accent }

    Loader {
        active: msg.lineH > 0
        visible: active
        width: msg.width; height: msg.lineH
        sourceComponent: Item {
            Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width; height: 1; color: Theme.danger }
            Rectangle {
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                width: newText.implicitWidth + Theme.px(14); height: Math.max(Theme.px(20), newText.implicitHeight + Theme.px(4)); radius: Theme.radius; color: Theme.danger
                Text { textFormat: Text.PlainText; id: newText; anchors.centerIn: parent; text: "NEW MESSAGES"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold }
            }
        }
    }
    // A note from Huginn itself (someone joined, the group was renamed).
    Loader {
        id: note
        active: !!msg.m.system
        visible: active
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        sourceComponent: noteBox
    }
    Component {
        id: noteBox
    Rectangle {
        // As wide as the words up to 90% of the row; measured apart from noteText, whose width follows this one.
        width: Math.min(msg.width * 0.9, Math.ceil(noteMetrics.advanceWidth) + Theme.px(30))
        height: noteText.implicitHeight + Theme.px(14)
        TextMetrics { id: noteMetrics; font: noteText.font; text: noteText.text }
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: msg.m.call && noteHover.hovered ? msg.accent : Theme.line
        Text {
            textFormat: Text.PlainText
            id: noteText
            anchors.centerIn: parent
            width: Math.max(0, parent.width - Theme.px(28))
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            text: msg.m.system ? (msg.m.text || "") + (msg.m.call ? "  ·  Call back" : "") : ""
            color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
        // A call's line: a click calls back.
        HoverHandler { id: noteHover; enabled: !!msg.m.call; cursorShape: Qt.PointingHandCursor }
        TapHandler { enabled: !!msg.m.call; onTapped: msg.callBack() }
    }
    }
    Loader {
        id: foldBox
        active: msg.folded && !msg.m.system
        visible: active
        x: Theme.px(Theme.compact ? 16 : 72); y: msg.lineH + Theme.px(6)
        sourceComponent: Rectangle {
        width: foldText.implicitWidth + Theme.px(24)
        height: foldText.implicitHeight + Theme.px(10)
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: foldHover.hovered ? msg.accent : Theme.line
        Accessible.role: Accessible.Button
        Accessible.name: "Muted, tap to show"
        Accessible.onPressAction: msg.unfold()
        Text {
            textFormat: Text.PlainText
            id: foldText
            anchors.centerIn: parent
            text: "Muted, tap to show"
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        HoverHandler { id: foldHover; cursorShape: Qt.PointingHandCursor }
    }
    }
    Avatar {
        id: face
        visible: !msg.m.system && !msg.folded && !Theme.compact && !msg.grouped
        x: Theme.px(16); y: msg.lineH + msg.pad
        size: visible ? Theme.px(40) : 0
        key: msg.m.mine ? "me" : (msg.host && msg.host.isTour ? "" : (msg.m.from || (msg.host && msg.host.openKey) || ""))
        name: msg.who
        // A click on the picture opens their profile card, like the name.
        HoverHandler { cursorShape: whoText.key !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
        TapHandler {
            gesturePolicy: TapHandler.ReleaseWithinBounds
            onTapped: msg.showWho()
        }
    }
    Column {
        id: col
        visible: !msg.m.system && !msg.folded
        x: Theme.px(Theme.compact ? 16 : 72)
        width: parent.width - x - Theme.px(16)
        y: msg.lineH + msg.pad
        spacing: Theme.px(2)
        Row {
            visible: !msg.grouped
            spacing: Theme.px(8)
            Text {
                textFormat: Text.PlainText
                id: whoText
                width: Math.min(implicitWidth, Math.round(col.width * 0.6))
                elide: Text.ElideRight
                text: msg.who
                color: Theme.avatar(msg.who, msg.mine).fg
                font.family: Theme.body; font.pixelSize: Theme.ms(16); font.weight: Font.Medium
                // A click on the name opens their profile card.
                readonly property string key: msg.m.mine ? "me" : (msg.m.from || (msg.host && msg.host.openKey) || "")
                HoverHandler { cursorShape: whoText.key !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                TapHandler {
                    gesturePolicy: TapHandler.ReleaseWithinBounds
                    onTapped: msg.showWho()
                }
            }
            Text {
                textFormat: Text.PlainText
                anchors.baseline: whoText.baseline
                text: host.clock(msg.m.time) + (msg.m.isEdited ? " · (edited)" : "") + host.stateLabel(msg.m)
                color: msg.m.failed ? Theme.danger : msg.m.mine && msg.m.state === "read" ? msg.accent : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
            // Mine and read: a check mark and how many have read it, in a circle.
            Loader {
                active: !!msg.m.mine && !msg.m.unsent && (msg.m.readCount || 0) > 0
                visible: active
                anchors.verticalCenter: whoText.verticalCenter
                sourceComponent: Row {
                spacing: Theme.px(3)
                Accessible.role: Accessible.StaticText
                Accessible.name: "Read by " + (msg.m.readCount || 0)
                Icon { anchors.verticalCenter: parent.verticalCenter; name: "check"; size: Theme.px(15); color: msg.accent }
                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: Math.max(Theme.px(18), readN.implicitWidth + Theme.px(8)); height: Theme.px(18); radius: height / 2
                    color: "transparent"; border.color: msg.accent; border.width: 1
                    Text { textFormat: Text.PlainText; id: readN; anchors.centerIn: parent; text: msg.m.readCount || 0; color: msg.accent; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold }
                }
                }
            }
        }
        Loader {
            active: !!msg.m.reName && !msg.m.unsent
            visible: active
            sourceComponent: QuoteLine {
                // Up to the column's width (its parent here is this Loader, which takes its size from it).
                limit: col.width
                who: msg.m.reName || ""
                what: msg.m.reText || ""
                onJump: msg.host.jumpTo(msg.m.re)
            }
        }
        Loader {
            active: msg.hasPhoto && !msg.m.gif
            visible: active
            sourceComponent: Image {
                width: Math.min(Theme.px(300), col.width); height: Math.round(width * 170 / 300)
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                // Read, shrunk to the bubble (sharp up to twice its size) and kept in memory on the loading thread (ThumbProvider).
                sourceSize.width: Math.ceil(width * 2)
                sourceSize.height: Math.ceil(height * 2)
                source: msg.host && !msg.host.isTour ? "image://thumb/" + msg.m.photo : ""
            }
        }
        // A GIF (cap a1): it moves while Huginn is in front; with Less motion or Battery saver it holds its first frame until played.
        Loader {
            active: msg.hasPhoto && !!msg.m.gif
            visible: active
            sourceComponent: Item {
            id: gifBox
            property bool asked: false
            width: Math.min(Theme.px(300), col.width); height: Math.round(width * 170 / 300)
            clip: true
            AnimatedImage {
                anchors.fill: parent
                fillMode: Image.PreserveAspectCrop
                cache: false
                // Chat.gifUrl checks the GIF limits again before anything decodes it.
                source: msg.host && !msg.host.isTour ? Chat.gifUrl(msg.m.photo) : ""
                playing: (Theme.motion || gifBox.asked) && Qt.application.state === Qt.ApplicationActive
            }
            Rectangle {
                visible: !Theme.motion
                anchors.centerIn: parent
                width: Theme.px(48); height: width; radius: width / 2
                color: Qt.rgba(0, 0, 0, 0.55)
                Icon { anchors.centerIn: parent; name: gifBox.asked ? "stop" : "play"; color: "white"; size: Theme.px(24) }
                Accessible.role: Accessible.Button
                Accessible.name: gifBox.asked ? "Stop the GIF" : "Play the GIF"
                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: gifBox.asked = !gifBox.asked }
            }
            Rectangle {
                anchors.left: parent.left; anchors.bottom: parent.bottom; anchors.margins: Theme.px(6)
                width: gifTag.implicitWidth + Theme.px(8); height: gifTag.implicitHeight + Theme.px(2); radius: Theme.px(4)
                color: Qt.rgba(0, 0, 0, 0.6)
                Text { id: gifTag; textFormat: Text.PlainText; anchors.centerIn: parent; text: "GIF"; color: "white"; font.family: Theme.body; font.pixelSize: Theme.fs(11) }
            }
            }
        }
        Loader {
            active: !!msg.m.voice && !msg.m.unsent
            visible: active
            sourceComponent: Row {
            spacing: Theme.px(10)
            Icon { name: Voice.playing === msg.m.id ? "stop" : "play"; color: msg.accent }
            Column {
                anchors.verticalCenter: parent.verticalCenter
                Rectangle {
                    width: Theme.px(180); height: Theme.px(4); color: Theme.line
                    Rectangle { width: parent.width * (Voice.playing === msg.m.id ? Voice.progress : 0); height: parent.height; color: msg.accent }
                }
                Text { textFormat: Text.PlainText; text: "Voice message · " + Voice.length(msg.m.voice || 0); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
            }
            }
        }
        // A file that went as a message (the internet route): type icon, name, size and how it's going ("Sending 42%"
        // with a bar, "Sent", "Sent · Saved in their Downloads"). Theirs was saved to Downloads when it came: Open and
        // Folder (Save if it couldn't be then). Nothing received opens by itself.
        Loader {
            active: !!msg.m.file && !msg.m.unsent
            visible: active
            sourceComponent: Rectangle {
            id: fileCard
            readonly property real up: msg.m.mine && Chat.uploads[msg.m.id] !== undefined ? Chat.uploads[msg.m.id] : -1
            readonly property bool sending: !!msg.m.mine && !msg.m.failed && msg.m.state === "sending"
            readonly property string savedPath: msg.m.saved || ""
            width: Math.min(Theme.px(380), col.width)
            height: fileHead.height + Theme.px(20) + (fileButtons.visible ? fileButtons.height + Theme.px(8) : 0) + (fileBar.visible ? Theme.px(10) : 0)
            radius: Theme.radius
            color: Theme.panel
            border.width: 1
            border.color: msg.m.failed ? Theme.dangerLine : Theme.line
            Accessible.role: Accessible.StaticText
            Accessible.name: (msg.m.file || "") + ", " + fileLine.text
            Item {
                id: fileHead
                x: Theme.px(10); y: Theme.px(10)
                width: parent.width - Theme.px(20)
                height: Math.max(fileBadge.height, fileWords.implicitHeight)
                Rectangle {
                    id: fileBadge
                    width: Theme.px(38); height: width; radius: Theme.radius
                    anchors.verticalCenter: parent.verticalCenter
                    color: Theme.raised
                    Icon { anchors.centerIn: parent; name: FileLink.icon(msg.m.file || ""); size: Theme.px(20); color: msg.m.failed ? Theme.danger : msg.accent }
                }
                Column {
                    id: fileWords
                    anchors.left: fileBadge.right; anchors.leftMargin: Theme.px(10)
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(2)
                    Text {
                        textFormat: Text.PlainText
                        width: parent.width; elide: Text.ElideMiddle
                        text: msg.m.file || ""
                        color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.ms(15); font.weight: Font.DemiBold
                    }
                    Text {
                        id: fileLine
                        textFormat: Text.PlainText
                        width: parent.width; wrapMode: Text.Wrap
                        text: (msg.m.size ? FileLink.size(msg.m.size) + " · " : "")
                              + (msg.m.mine ? FileLink.relayLine(msg.m.state || "", !!msg.m.failed, fileCard.up)
                                            : fileCard.savedPath !== "" ? "Saved to Downloads" : "Not saved yet")
                        color: msg.m.failed ? Theme.danger : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(13)
                    }
                }
            }
            Rectangle {
                id: fileBar
                visible: fileCard.sending
                x: Theme.px(10); y: fileHead.y + fileHead.height + Theme.px(6)
                width: parent.width - Theme.px(20); height: Theme.px(4)
                color: Theme.line
                Rectangle { width: parent.width * Math.max(0, fileCard.up); height: parent.height; color: msg.accent }
            }
            Row {
                id: fileButtons
                visible: !msg.m.mine && !(msg.host && msg.host.isTour)
                x: Theme.px(10); y: fileHead.y + fileHead.height + Theme.px(8)
                spacing: Theme.px(6)
                HButton {
                    visible: fileCard.savedPath === ""
                    text: "Save"; kind: "accent"; height: Theme.px(32); fontSize: 13
                    onClicked: if (msg.host && msg.host.openKey) PeopleActions.saveFile(msg.host.openKey, msg.m)
                }
                HButton {
                    visible: fileCard.savedPath !== ""
                    text: "Open"; kind: "accent"; height: Theme.px(32); fontSize: 13
                    onClicked: if (!FileLink.openFile(fileCard.savedPath)) Ui.say("Nothing on this computer opens this kind of file, or it was moved.", true)
                }
                HButton {
                    visible: fileCard.savedPath !== ""
                    text: "Folder"; height: Theme.px(32); fontSize: 13
                    onClicked: if (!FileLink.showInFolder(fileCard.savedPath)) Ui.say("It's not in Downloads any more.", true)
                }
            }
            }
        }
        Text {
            visible: (!msg.m.photo || !!msg.m.unsent) && !(msg.m.pollView && !msg.m.unsent)
            width: parent.width; wrapMode: Text.Wrap
            textFormat: Text.PlainText
            text: msg.m.removed ? "Removed by an admin" : msg.m.unsent ? (msg.m.mine ? "Message deleted by you" : "Message deleted by " + msg.m.unsent) : (msg.m.text || "")
            font.italic: !!msg.m.unsent
            lineHeight: 1.2
            color: msg.m.unsent ? Theme.textMuted : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.ms(16)
        }
        // Cap m2: an album, a video message, a poll or a link preview.
        Loader {
            active: msg.live && (!!msg.m.album || msg.m.video > 0 || !!msg.m.pollView || !!msg.m.lp)
            visible: active
            width: parent.width
            sourceComponent: MoreParts { host: msg.host; m: msg.m; where: msg.host && msg.host.more ? msg.host.more.where : "" }
        }
        Loader {
            active: (msg.m.rxList || []).length > 0
            visible: active
            width: parent.width
            sourceComponent: Reactions {
                list: msg.m.rxList || []
                add: msg.hoverTools
                onToggled: (e, mine) => msg.host.reactTo(msg.m.id, mine ? "" : e)
                onMore: msg.host.openReact(msg.m.id, msg.m.myReaction || "")
            }
        }
        // A thread: how many replies, opening them beside the channel.
        Loader {
            active: !msg.inThread && (msg.m.replies || 0) > 0 && !msg.m.unsent
            visible: active
            sourceComponent: Text {
                textFormat: Text.PlainText
                text: (msg.m.replies === 1 ? "1 reply" : msg.m.replies + " replies") + " ›"
                color: msg.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                Accessible.role: Accessible.Button
                Accessible.name: "Open the thread"
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: msg.host.openThread(msg.m.id) }
            }
        }
    }
    // Made only while pointed at or picked.
    Loader {
        id: tools
        active: msg.hoverTools && (msgHover.hovered || msg.picked) && msg.live
        visible: active
        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
        y: msg.lineH - Theme.px(6)
        z: 2
        sourceComponent: HoverBar {
            id: bar
            canEdit: !!msg.m.mine && !msg.m.photo && !msg.m.failed && !msg.m.file
            canThread: !msg.inThread
            canPin: !msg.inThread
            pinnedNow: msg.host.pins.indexOf(msg.m.id) >= 0
            onReact: msg.host.openReact(msg.m.id, msg.m.myReaction || "")
            // In a thread its own box answers; from the pins, the chat's box does.
            onReply: msg.inThread && msg.host.threadCompose.visible ? msg.host.threadCompose.focusField() : msg.host.startReply(msg.m.id)
            onThread: msg.host.openThread(msg.m.id)
            onEdit: msg.host.startEdit(msg.m)
            onPin: msg.host.togglePin(msg.m.id)
            onMore: Ui.menuAt(bar, bar.width, bar.height, () => msg.options())
        }
    }
    HoverHandler { id: msgHover }
    // A click (a mouse or the right stick's pointer) picks it and opens a photo; a right click opens
    // the options. A finger: a tap does the same as a click, a long press opens the options.
    function clickOpen() {
        if (msg.m.mine && msg.m.failed && msg.host && msg.host.openKey) Chat.retry(msg.host.openKey, msg.m.id)
        else if (msg.m.photo && !msg.m.voice && !msg.m.file && !msg.m.unsent && !msg.m.video && !msg.m.album) msg.host.viewing = msg.m.photo
    }
    TapHandler {
        acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad | PointerDevice.Stylus
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        onTapped: (point, button) => {
            // The tools over the corner answer their own clicks.
            if (tools.visible && tools.contains(tools.mapFromItem(msg, point.position.x, point.position.y))) return
            msg.chosen()
            if (msg.folded && button !== Qt.RightButton) { msg.unfold(); return }
            if (button === Qt.RightButton) Ui.menuAt(msg, point.position.x, point.position.y, () => msg.options())
            else msg.clickOpen()
        }
    }
    TapHandler {
        acceptedDevices: PointerDevice.TouchScreen
        onTapped: { msg.chosen(); if (msg.folded) msg.unfold(); else msg.clickOpen() }
        onLongPressed: { msg.chosen(); Ui.menuAt(msg, point.position.x, point.position.y, () => msg.options()) }
    }
}

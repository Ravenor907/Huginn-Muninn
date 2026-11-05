import QtQuick
import QtQuick.Shapes
import Huginn

// You, at the bottom left under the rail and the list, like Discord: your picture, name and status
// (a click opens your card, MeCard.qml) with Mute and Deafen (Settings is on the rail). In a voice room or group
// call it grows a top part: which room, who you're talking with (a gold ring while they talk,
// dimmed while muted), push to talk, Share and Leave.
Rectangle {
    id: panel
    property bool tour: false              // the screenshot tour shows how it looks
    property var roomBar: null             // shows someone's shared screen above the conversation
    property bool compact: false           // a short window (a handheld): the room is one line, so the panel stays small
    // Its buttons: at least 44 px to touch in Handheld mode, and never smaller than their words.
    readonly property int smallButton: Math.max(Theme.touch ? Theme.tapMin : Theme.px(28), Math.round(Theme.fs(12) * 1.3) + Theme.px(8))
    property bool cardOpen: false          // your card is up (Main.qml), so the name stays lit
    signal meClicked()
    // What the small panel says under your name.
    // Locked only when there is a vault and it is locked: without a passphrase there's no key to hold.
    readonly property bool vaultLocked: Vault.exists && Vault.screenLocked
    readonly property string statusText: {
        if (vaultLocked) return "Locked"
        if (Settings.pushToTalk && inVoice) return TalkKey.canTalk ? "Push to talk: " + keyFull
                                            : TalkKey.padGone ? "Controller gone: mic muted"
                                            : TalkKey.padAway ? "Voice activity: no controller" : "Voice activity: no talk key yet"
        if (Ui.myCustomStatus !== "") return Ui.myCustomStatus
        return statusName(Ui.myStatus)
    }
    function statusName(s) { return s === "away" ? "Away" : s === "dnd" ? "Do not disturb" : s === "invisible" ? "Invisible" : "Online" }
    readonly property bool inVoice: Rooms.inRoom || tour
    readonly property var people: tour ? [{ key: "me", name: Ui.myName, me: true, speaking: false, muted: false, state: "live" },
                                          { key: "j", name: "Jake", speaking: true, state: "live" },
                                          { key: "s", name: "Sam", muted: true, state: "live" }]
                                       : Rooms.people
    readonly property var others: people.filter(p => !p.me)
    readonly property bool muted: !tour && Ui.micMuted
    readonly property bool deafened: !tour && Ui.deafened
    readonly property bool talking: tour || Rooms.inRoom || Calls.inCall
    // The talk key (or, with no key, the controller's talk button), and short: "`" for Backtick.
    readonly property string keyFull: TalkKey.key !== 0 ? TalkKey.keyName : Gamepad.glyph(TalkKey.padButton)
    readonly property string keyShort: TalkKey.key === Qt.Key_QuoteLeft ? "`" : keyFull
    // What push to talk does right now: no key yet (voice activity until one is picked), the key goes into the focused
    // text box, or hold it. Short for the compact line; holdTip has the whole of it.
    readonly property string holdWords: !TalkKey.hasKey ? "Pick a key"
                                      : TalkKey.padGone ? "Controller gone"
                                      : TalkKey.padAway ? "No controller"
                                      : TalkKey.keyTypes && TalkKey.inBox ? "Typing: " + keyShort + " goes to the box"
                                      : "Hold " + keyShort
    readonly property string holdTip: !TalkKey.hasKey ? "No talk key yet: voice activity. Pick one in Settings › Calls › Voice rooms"
                                    : TalkKey.padGone ? "Controller gone: mic muted. Connect it again to talk"
                                    : TalkKey.padAway ? "No controller connected: voice activity until one is" : holdWords
    height: (voice.visible ? voice.implicitHeight : 0) + me.height
    radius: Theme.radius
    color: Theme.raised
    border.color: Theme.line
    // Clicks here never reach the page underneath.
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }


    Column {
        id: voice
        visible: panel.inVoice
        x: Theme.px(10)
        width: parent.width - Theme.px(20)
        spacing: panel.compact ? Theme.px(4) : Theme.px(6)
        topPadding: panel.compact ? Theme.px(4) : Theme.px(8)
        bottomPadding: panel.compact ? Theme.px(4) : Theme.px(8)

        // Compact: the room, the faces of whoever is talking (with push to talk and nobody talking, the key to hold),
        // More (Share, Watch) and Leave, all on one line. A problem (Rooms.note) takes the room's place, beside More and
        // Leave.
        Row {
            id: oneLine
            visible: panel.compact
            width: parent.width
            spacing: Theme.px(6)
            readonly property var talkers: panel.others.filter(p => p.speaking).slice(0, 3)
            readonly property bool noted: Rooms.note !== "" && !panel.tour
            readonly property bool facesShown: !noted && talkers.length > 0
            // Who is talking comes first: the key shows while nobody is (its words are in the room name's tooltip too).
            readonly property bool holdShown: !noted && !facesShown && !panel.tour && Settings.pushToTalk
            readonly property bool hasMore: Rooms.canShare || panel.tour || (Rooms.sharerName !== "" && !!panel.roomBar)
            Text {
                id: roomWords
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                width: Math.max(0, parent.width - compactLeave.width - parent.spacing
                                - (oneLine.hasMore ? compactMore.width + parent.spacing : 0)
                                - (oneLine.facesShown ? talkerFaces.width + parent.spacing : 0)
                                - (oneLine.holdShown ? holdKey.width + parent.spacing : 0))
                elide: Text.ElideRight
                text: oneLine.noted ? Rooms.note
                    : !panel.tour && Settings.pushToTalk && Rooms.talking ? "Talking"
                    : panel.tour ? "Voice / Gaming Crew" : Rooms.title + (Rooms.groupName ? " / " + Rooms.groupName : "")
                color: oneLine.noted ? Theme.danger : !panel.tour && Settings.pushToTalk && Rooms.talking ? Theme.accent : Theme.online
                font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                Accessible.name: "Voice connected: " + text + (!panel.tour && Settings.pushToTalk ? ". " + panel.holdTip : "")
                HoverHandler { id: roomHover }
                TapHandler { onTapped: if (!panel.tour) Ui.openPage("g:" + Rooms.group, { voice: true }) }
                Tip { shown: roomHover.hovered && !panel.tour && Settings.pushToTalk && !oneLine.holdShown; text: panel.holdTip }
            }
            // Push to talk: the key to hold, where the faces would be.
            Text {
                id: holdKey
                visible: oneLine.holdShown
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                // Never wider than a third of the line, so the room name, More and Leave keep their room.
                width: Math.min(implicitWidth, Math.round(oneLine.width / 3))
                elide: Text.ElideRight
                text: panel.holdWords
                color: Rooms.talking ? Theme.accent : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
                Accessible.name: panel.holdTip
                HoverHandler { id: holdHover }
                Tip { shown: holdHover.hovered && panel.holdTip !== holdKey.text; text: panel.holdTip }
            }
            Row {
                id: talkerFaces
                visible: oneLine.facesShown
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(2)
                Repeater {
                    model: oneLine.talkers
                    Avatar {
                        id: talker
                        required property var modelData
                        size: Theme.px(24)
                        key: panel.tour ? "" : (modelData.key || "")
                        name: modelData.name || ""
                        backdrop: Theme.raised
                        Accessible.role: Accessible.Button
                        Accessible.name: (modelData.name || "") + ", speaking"
                        // The same as the full panel's faces: a tap, their profile; a long press or right click, Mute
                        // for me, Volume, and for admins Timeout and Ban.
                        function menuAt(x, y) {
                            if (panel.tour) return
                            Ui.menuAt(talker, x, y, () => PeopleActions.memberMenu({ key: talker.modelData.key, name: talker.modelData.name,
                                                                                     contact: Chat.isContact(talker.modelData.key) }, { group: Rooms.group }))
                        }
                        TapHandler {
                            onTapped: if (!panel.tour) Ui.showProfile(talker.modelData.key, talker.modelData.name, {})
                            onLongPressed: talker.menuAt(point.position.x, point.position.y)
                        }
                        TapHandler {
                            acceptedButtons: Qt.RightButton
                            onTapped: (point, button) => talker.menuAt(point.position.x, point.position.y)
                        }
                    }
                }
            }
            HButton {
                id: compactMore
                visible: oneLine.hasMore
                anchors.verticalCenter: parent.verticalCenter
                implicitHeight: panel.smallButton
                fontSize: 12
                text: "More"
                onClicked: if (!panel.tour) panel.moreMenu(compactMore)
            }
            HButton {
                id: compactLeave
                anchors.verticalCenter: parent.verticalCenter
                implicitHeight: panel.smallButton
                fontSize: 12
                kind: "danger"
                text: "Leave"
                onClicked: if (!panel.tour) Rooms.leave()
            }
        }

        Row {
            visible: !panel.compact
            width: parent.width
            spacing: Theme.px(8)
            Column {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width - leaveBtn.width - Theme.px(8)
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    elide: Text.ElideRight
                    text: Rooms.note !== "" && !panel.tour ? Rooms.note : "Voice connected"
                    color: Rooms.note !== "" && !panel.tour ? Theme.danger : Theme.online
                    font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width
                    elide: Text.ElideRight
                    text: panel.tour ? "Voice / Gaming Crew" : Rooms.title + (Rooms.groupName ? " / " + Rooms.groupName : "")
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(12)
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: if (!panel.tour) Ui.openPage("g:" + Rooms.group, { voice: true }) }
                }
            }
            HButton {
                id: leaveBtn
                anchors.verticalCenter: parent.verticalCenter
                implicitHeight: panel.smallButton
                fontSize: 12
                kind: "danger"
                text: "Leave"
                onClicked: if (!panel.tour) Rooms.leave()
            }
        }

        // Who you're talking with.
        Flow {
            width: parent.width
            spacing: Theme.px(6)
            visible: !panel.compact && panel.people.length > 0
            Repeater {
                model: panel.people
                Item {
                    id: who
                    // Safe while the list takes the face away.
                    readonly property var p: modelData || ({})
                    width: Theme.px(34); height: width
                    Accessible.role: Accessible.StaticText
                    Accessible.name: who.p.name + (who.p.speaking ? ", speaking" : "") + (who.p.muted ? ", muted" : "") + (who.p.state === "reconnecting" ? ", reconnecting" : "")
                    Rectangle {
                        anchors.fill: parent
                        radius: width / 2
                        color: "transparent"
                        border.width: Theme.px(2.5)
                        border.color: Theme.accent
                        opacity: who.p.speaking ? 1 : 0
                        Behavior on opacity { NumberAnimation { duration: Theme.motion ? 120 : 0 } }
                    }
                    Avatar {
                        anchors.centerIn: parent
                        size: Theme.px(28)
                        key: panel.tour ? "" : who.p.me ? "me" : (who.p.key || "")
                        name: who.p.name || ""
                        backdrop: Theme.raised
                        opacity: who.p.state === "live" ? (who.p.muted ? 0.55 : 1) : 0.4
                    }
                    HoverHandler { cursorShape: who.p.me ? Qt.ArrowCursor : Qt.PointingHandCursor }
                    function menuAt(x, y) {
                        if (!who.p.me && !panel.tour) Ui.menuAt(who, x, y,
                            () => PeopleActions.memberMenu({ key: who.p.key, name: who.p.name, contact: Chat.isContact(who.p.key) }, { group: Rooms.group }))
                    }
                    // A tap: their profile. A right click or a long press: Mute for me, Volume, and for admins Timeout and Ban (cap g2).
                    TapHandler {
                        onTapped: if (!who.p.me && !panel.tour) Ui.showProfile(who.p.key, who.p.name, {})
                        onLongPressed: who.menuAt(point.position.x, point.position.y)
                    }
                    TapHandler {
                        acceptedButtons: Qt.RightButton
                        onTapped: (point, button) => who.menuAt(point.position.x, point.position.y)
                    }
                }
            }
        }
        Text {
            textFormat: Text.PlainText
            visible: !panel.compact
            width: parent.width
            elide: Text.ElideRight
            text: panel.others.length === 0 ? "Nobody else here yet"
                : "With " + panel.others.map(p => p.name + (p.state === "reconnecting" ? " (reconnecting)" : "")).join(", ")
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        // Push to talk: the key to hold, lit while it's held.
        Rectangle {
            visible: Settings.pushToTalk && !panel.compact
            width: parent.width
            height: Math.max(Theme.px(24), pttText.implicitHeight + Theme.px(6))
            radius: Theme.radius
            color: !panel.tour && Rooms.talking ? Theme.accentFill : "transparent"
            border.width: 1
            border.color: !panel.tour && Rooms.talking ? Theme.accent : Theme.line
            Text {
                textFormat: Text.PlainText
                id: pttText
                anchors.centerIn: parent
                width: parent.width - Theme.px(12)
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                // Windows, or Linux without a shortcut portal: push to talk only hears the key while Huginn is in front,
                // so with another app in front it says so.
                text: !panel.tour && Rooms.talking ? "Talking"
                    : !panel.tour && (Qt.platform.os === "windows" || !GlobalKeys.everywhere) && Qt.application.state !== Qt.ApplicationActive ? "Push to talk pauses while another app is in front"
                    : !panel.tour && !TalkKey.hasKey ? "No talk key yet: voice activity. Pick one in Settings › Calls › Voice rooms"
                    : !panel.tour && TalkKey.padGone ? "Controller gone: mic muted. Connect it again to talk"
                    : !panel.tour && TalkKey.padAway ? "No controller connected: voice activity until one is"
                    : !panel.tour && TalkKey.keyTypes && TalkKey.inBox ? "Typing: " + TalkKey.keyName + " goes to the box"
                    : "Push to talk: hold " + panel.keyFull
                color: !panel.tour && Rooms.talking ? Theme.accent : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
            }
        }
        Row {
            id: buttons
            visible: !panel.compact && (Rooms.canShare || panel.tour || (Rooms.sharerName !== "" && !!panel.roomBar))
            spacing: Theme.px(6)
            HButton {
                visible: Rooms.canShare || panel.tour
                implicitHeight: panel.smallButton; fontSize: 12
                text: Rooms.sharing ? "Stop sharing" : "Share screen"
                onClicked: if (!panel.tour) Rooms.toggleShare()
            }
            HButton {
                visible: !panel.tour && Rooms.sharerName !== "" && !!panel.roomBar
                implicitHeight: panel.smallButton; fontSize: 12
                text: panel.roomBar && panel.roomBar.screenShown ? "Hide their screen" : "Watch their screen"
                onClicked: panel.roomBar.screenShown = !panel.roomBar.screenShown
            }
        }
        Rectangle { width: parent.width; height: 1; color: Theme.line }
    }

    // Compact's More: Share screen and Watch their screen.
    function moreMenu(item) {
        Ui.menuAt(item, item.width / 2, 0, () => {
            const items = []
            if (Rooms.canShare) items.push({ name: Rooms.sharing ? "Stop sharing" : "Share screen", icon: "screen", url: "share" })
            if (Rooms.sharerName !== "" && panel.roomBar)
                items.push({ name: panel.roomBar.screenShown ? "Hide their screen" : "Watch their screen", icon: "screen", url: "watch" })
            if (panel.roomBar && panel.roomBar.screenOn)
                items.push({ name: panel.roomBar.screenBig ? "Smaller (B)" : "Bigger", icon: "screen", url: "big" })
            Ui.pickFrom("Voice", [{ header: "", items: items }], a => {
                if (a === "share") Rooms.toggleShare()
                else if (a === "watch" && panel.roomBar) panel.roomBar.screenShown = !panel.roomBar.screenShown
                else if (a === "big" && panel.roomBar) panel.roomBar.screenBig = !panel.roomBar.screenBig
            })
        })
    }

    // You: picture, name and status (a click opens your card), then Mute and Deafen.
    Item {
        id: me
        anchors.bottom: parent.bottom
        width: parent.width
        height: Math.max(Theme.px(52), meWords.implicitHeight + Theme.px(16), meButtons.implicitHeight + Theme.px(8))
        Rectangle {
            id: meTile
            anchors.left: parent.left; anchors.leftMargin: Theme.px(4)
            anchors.right: meButtons.left; anchors.rightMargin: Theme.px(4)
            anchors.verticalCenter: parent.verticalCenter
            height: parent.height - Theme.px(8)
            radius: Theme.radius
            color: panel.cardOpen || meHover.hovered ? Theme.tile : "transparent"
            Accessible.role: Accessible.Button
            Accessible.name: Ui.myName + ", " + panel.statusText + ". Your status and profile"
            Accessible.onPressAction: if (!panel.tour) panel.meClicked()
            Avatar {
                id: meFace
                x: Theme.px(6)
                anchors.verticalCenter: parent.verticalCenter
                key: "me"
                name: Ui.myName
                size: Theme.px(32)
                showDot: !panel.vaultLocked
            }
            Column {
                id: meWords
                anchors.left: meFace.right; anchors.leftMargin: Theme.px(8)
                anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
                anchors.verticalCenter: parent.verticalCenter
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; elide: Text.ElideRight
                    text: Ui.myName
                    color: Theme.text
                    font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                }
                Text {
                    textFormat: Text.PlainText
                    width: parent.width; elide: Text.ElideRight
                    text: panel.statusText
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(12)
                }
            }
            HoverHandler { id: meHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: if (!panel.tour) panel.meClicked() }
        }
        Row {
            id: meButtons
            anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(2)
            PanelButton {
                icon: panel.muted ? "micoff" : "mic"; label: (panel.muted ? "Unmute mic" : "Mute mic") + " (" + GlobalKeys.muteName + ")"; lit: panel.muted
                onClicked: if (!panel.tour) Ui.toggleMic()
            }
            PanelButton {
                icon: panel.deafened ? "deafened" : "headphones"; label: (panel.deafened ? "Undeafen" : "Deafen") + " (" + GlobalKeys.deafenName + ")"; lit: panel.deafened
                onClicked: if (!panel.tour) Ui.toggleDeaf()
            }
        }
    }

    component PanelButton: Rectangle {
        id: pb
        property string icon
        property string label
        property bool lit: false
        property bool enabled: true
        signal clicked()
        width: Theme.touch ? Theme.tapMin : Theme.px(32); height: width
        radius: Theme.radius
        opacity: enabled ? 1 : 0.4
        color: lit ? Theme.dangerFill : pbHover.hovered && enabled ? Theme.tile : "transparent"
        Accessible.role: Accessible.Button
        Accessible.name: label
        Accessible.onPressAction: if (!panel.tour && enabled) clicked()
        Icon { anchors.centerIn: parent; name: pb.icon; size: Theme.px(18); color: pb.lit ? Theme.danger : Theme.textSoft }
        HoverHandler { id: pbHover; cursorShape: pb.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor }
        TapHandler { enabled: pb.enabled; onTapped: if (!panel.tour) pb.clicked() }
        Tip { shown: pbHover.hovered; text: pb.label }
    }
    // A small label over a button while the pointer is on it.
    component Tip: Rectangle {
        property bool shown: false
        property string text
        visible: shown
        z: 10
        anchors.bottom: parent.top; anchors.bottomMargin: Theme.px(4)
        anchors.horizontalCenter: parent.horizontalCenter
        width: tipText.implicitWidth + Theme.px(12); height: tipText.implicitHeight + Theme.px(8)
        radius: Theme.radius
        color: Theme.groundDeep
        border.color: Theme.line
        Text { textFormat: Text.PlainText; id: tipText; anchors.centerIn: parent; text: parent.text; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
    }
}

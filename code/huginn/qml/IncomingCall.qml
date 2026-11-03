import QtQuick
import Huginn

// Someone is calling: over whatever is on screen, wherever you are in Huginn. The raven call rings
// (Calls, or Rooms for a group call, plays it) until you answer, decline, or they give up.
// A answers (joins a group call), B declines.
Item {
    id: dlg
    anchors.fill: parent
    readonly property bool group: Calls.state !== "incoming" && Rooms.ringing
    visible: Calls.state === "incoming" || Rooms.ringing

    // A video call (shared/docs/protocol.md 5.8.5): X answers with the camera on, A as a voice call.
    readonly property bool video: !group && Calls.incomingVideo
    readonly property var prompts: video ? [{ key: "x", label: "Answer with video" }, { key: "a", label: "Voice only" }, { key: "b", label: "Decline" }]
                                         : [{ key: "a", label: group ? "Join" : "Answer" }, { key: "b", label: "Decline" }]
    readonly property string status: group ? "Group call" : video ? "Incoming video call" : "Incoming call"
    // A locked Chat doesn't say who it is on screen.
    readonly property string who: Vault.screenLocked ? "Someone" : group ? Rooms.ringName : Calls.peerName
    function answer() {
        if (!group) { Calls.answer(); return }
        const why = Rooms.answerRing()
        if (why !== "") Ui.say(why, true)
    }
    function decline() { if (group) Rooms.declineRing(); else Calls.decline() }
    // Round 17: for its first half second (Main.guardOverlay) a tap on Answer or Decline is ignored too, so a tap
    // meant for what was on screen never answers or declines.
    function tapGuarded() { return !!Ui.main && Ui.main.guardedNow(dlg) }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(560))
        height: Math.min(parent.height - Theme.px(16), col.implicitHeight + Theme.px(56))
        clip: true
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.accent
        Column {
            id: col
            anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
            anchors.margins: Theme.px(28)
            spacing: Theme.px(16)
            Text {
                textFormat: Text.PlainText
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                text: dlg.group ? "GROUP CALL" : dlg.video ? "VIDEO CALL" : "INCOMING CALL"
                color: Theme.accent
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
            }
            Avatar {
                id: avatar
                anchors.horizontalCenter: parent.horizontalCenter
                // A locked Chat shows no picture either.
                key: Vault.screenLocked || dlg.group ? "" : Calls.peerKey
                name: dlg.who
                // Smaller on a short window, so the buttons always show.
                size: Math.max(Theme.px(48), Math.min(Theme.px(96), Math.round(dlg.height * 0.16)))
                // A slow pulse while it rings.
                SequentialAnimation on scale {
                    running: dlg.visible && Theme.motion
                    loops: Animation.Infinite
                    NumberAnimation { from: 1; to: 1.06; duration: 700; easing.type: Easing.InOutQuad }
                    NumberAnimation { from: 1.06; to: 1; duration: 700; easing.type: Easing.InOutQuad }
                }
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                text: dlg.who + (dlg.group && !Vault.screenLocked && Rooms.ringGroup ? " is calling " + Rooms.ringGroup : " is calling")
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold
            }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.px(16)
                HButton { text: "Decline"; kind: "danger"; onClicked: if (!dlg.tapGuarded()) dlg.decline() }
                HButton { text: dlg.group ? "Join" : dlg.video ? "Voice only" : "Answer"; kind: dlg.video ? "normal" : "accent"; onClicked: if (!dlg.tapGuarded()) dlg.answer() }
                HButton { visible: dlg.video; text: "Answer with video"; kind: "accent"; onClicked: if (!dlg.tapGuarded()) Calls.answerVideo() }
            }
        }
    }

    function handleButton(b) {
        if (b === "a") answer()
        else if (b === "x" && video) Calls.answerVideo()
        else if (b === "b") decline()
        return true
    }
    function handleRelease(b) {}
}

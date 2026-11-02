import QtQuick
import QtMultimedia
import Huginn

// The call on screen, over everything else while it lasts: who, how long, which ways the sound is
// travelling (never an address), and mute, output and end. Controller: A mute, Y output, B ends (press it
// twice: the first press says "Press B again to hang up" for 3 seconds), X camera on or off in a video
// call (shared/docs/protocol.md 5.8.5), otherwise X ends like B. With a camera on, the
// picture fills the top (yours small in its corner), a strip under it shows the name, the ways and the
// time, and the buttons sit below that. Main.qml shows it while Calls.inCall.
Rectangle {
    id: page
    anchors.fill: parent
    visible: Calls.inCall
    color: Theme.ground

    readonly property bool ended: Calls.state === "ended"
    // Locked: a call still goes on, but who it is stays behind the lock (as on the ring, IncomingCall.qml).
    readonly property bool nameHidden: Vault.exists && Vault.screenLocked && System.tourDir.length === 0
    readonly property string who: nameHidden ? "Someone" : Calls.peerName
    readonly property bool videoUp: Calls.peerCamera && !ended
    readonly property var prompts: ended ? [] : [{ key: "a", label: Calls.muted ? "Unmute mic" : "Mute mic" }, { key: "y", label: "Output" }]
                                               .concat(Calls.videoPossible ? [{ key: "x", label: Calls.cameraOn ? "Camera off" : "Camera on" }] : [])
                                               .concat([{ key: "b", label: hangArmed ? "Hang up now" : "End call" }])
    readonly property string status: ended ? "" : Calls.muted ? "You're muted" : ""

    // The End call button ends at once, as on Muninn.
    function endCall() { hangArmed = false; hangTimer.stop(); Calls.hangup() }
    // B (and Esc or Backspace, and X on a voice call): the first press only says to press again;
    // a second press within 3 seconds hangs up.
    property bool hangArmed: false
    Timer { id: hangTimer; interval: 3000; onTriggered: page.hangArmed = false }
    function pressHangup() {
        if (hangArmed) { endCall(); return }
        hangArmed = true
        hangTimer.restart()
        Ui.say((Ui.main && Ui.main.padInUse ? "Press B" : "Press Esc") + " again to hang up")
    }
    onVisibleChanged: if (!visible) { hangArmed = false; hangTimer.stop() }

    MouseArea { anchors.fill: parent }   // nothing underneath takes clicks during a call

    // Once either camera is on: the picture on top, a solid strip with who, which ways and how long under it,
    // then the buttons. Nothing covers the picture but your own small one in its corner.
    readonly property bool videoLayout: !ended && (Calls.peerCamera || Calls.cameraOn)

    // Which ways the sound goes right now.
    Component {
        id: pathChips
        Row {
            id: chipRow
            spacing: Theme.px(10)
            // On a narrow window the chips share the width evenly and their words are cut short.
            readonly property var chips: (Calls.directTried ? [{ label: "Internet, straight to them", on: Calls.directLive }] : [])
                       .concat([{ label: "Internet", on: Calls.relayLive && !(Calls.directLive || Calls.wifiLive) }, { label: "Wi-Fi", on: Calls.wifiLive },
                                { label: "Bluetooth", on: Calls.bleLive }])
            readonly property real avail: page.width - Theme.px(60)
            readonly property real natural: chips.reduce((w, c) => w + Math.ceil(chipMetrics.advanceWidth(c.label)) + Theme.px(34), 0) + spacing * Math.max(0, chips.length - 1)
            readonly property bool squeezed: natural > avail
            readonly property real share: Math.max(Theme.px(60), (avail - spacing * Math.max(0, chips.length - 1)) / Math.max(1, chips.length))
            FontMetrics { id: chipMetrics; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold }
            Repeater {
                model: chipRow.chips
                Rectangle {
                    height: Math.max(Theme.px(32), chip.implicitHeight + Theme.px(8))
                    width: chipRow.squeezed ? chipRow.share : chip.implicitWidth + Theme.px(34)
                    radius: height / 2
                    color: modelData.on ? Theme.accentFill : Theme.tile
                    border.width: 1
                    border.color: modelData.on ? Theme.accent : Theme.line
                    Accessible.role: Accessible.StaticText
                    Accessible.name: modelData.label + (modelData.on ? ", in use" : ", not in use")
                    Rectangle {
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(7); height: width; radius: width / 2
                        color: modelData.on ? (Theme.hc ? Theme.text : Theme.online) : Theme.textFaint
                    }
                    Text {
                        textFormat: Text.PlainText
                        id: chip
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(25)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Math.max(0, Math.min(implicitWidth, parent.width - Theme.px(34)))
                        elide: Text.ElideRight
                        text: modelData.label
                        color: modelData.on ? Theme.text : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                    }
                }
            }
        }
    }

    Component {
        id: callButtons
        Row {
            id: buttonRow
            spacing: Theme.px(16)
            // On a narrow window the buttons share the width evenly and their words are cut short.
            readonly property real avail: page.width - Theme.px(48)
            readonly property var shown: [muteBtn, outputBtn, cameraBtn, switchBtn, endBtn].filter(b => b.visible)
            readonly property real natural: shown.reduce((w, b) => w + b.implicitWidth, 0) + spacing * Math.max(0, shown.length - 1)
            readonly property bool squeezed: natural > avail
            readonly property real share: Math.max(Theme.px(40), (avail - spacing * Math.max(0, shown.length - 1)) / Math.max(1, shown.length))
            HButton {
                id: muteBtn
                width: buttonRow.squeezed ? buttonRow.share : implicitWidth
                text: Calls.muted ? "Unmute mic" : "Mute mic"
                kind: Calls.muted ? "accent" : "normal"
                onClicked: Ui.toggleMic()
            }
            HButton {
                id: outputBtn
                width: buttonRow.squeezed ? buttonRow.share : implicitWidth
                text: "Output: " + Calls.outputName
                onClicked: Calls.nextOutput()
            }
            HButton {
                id: cameraBtn
                width: buttonRow.squeezed ? buttonRow.share : implicitWidth
                visible: Calls.videoPossible
                text: Calls.cameraOn ? "Camera off" : "Camera on"
                kind: Calls.cameraOn ? "accent" : "normal"
                onClicked: Calls.toggleCamera()
            }
            HButton {
                id: switchBtn
                width: buttonRow.squeezed ? buttonRow.share : implicitWidth
                visible: Calls.cameraOn
                text: "Switch camera"
                onClicked: Calls.nextCamera()
            }
            HButton {
                id: endBtn
                width: buttonRow.squeezed ? buttonRow.share : implicitWidth
                text: "End call"
                kind: "danger"
                onClicked: page.endCall()
            }
        }
    }

    // Avatar, with a ring while they talk.
    Component {
        id: avatar
        Item {
            // Smaller on a short window, so the name and buttons still fit under it.
            width: Math.max(Theme.px(64), Math.min(Theme.px(132), Math.round(page.height * 0.22))); height: width
            Rectangle {
                anchors.centerIn: parent
                width: parent.width; height: width; radius: width / 2
                color: "transparent"
                border.width: Theme.px(4)
                border.color: Theme.accent
                opacity: Calls.peerSpeaking && !page.ended ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: Theme.motion ? 160 : 0 } }
            }
            Avatar {
                anchors.centerIn: parent
                key: page.nameHidden ? "" : Calls.peerKey
                name: page.who
                size: parent.width - Theme.px(16)
            }
        }
    }

    // The picture: their camera, turned upright (or just who it is while theirs is off), yours small in the corner.
    Rectangle {
        id: picture
        visible: page.videoLayout
        anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
        anchors.bottom: strip.top
        color: Theme.groundDeep
        clip: true

        VideoOutput {
            id: remote
            anchors.fill: parent
            visible: page.videoUp
            fillMode: VideoOutput.PreserveAspectFit
            // The sender says how far to turn it clockwise (5.8.5); VideoOutput's orientation turns clockwise too
            // (Qt 6 docs: "the angle, in degrees, at which the displayed video is rotated clockwise").
            orientation: Calls.peerRotation
            Component.onCompleted: Calls.setRemoteSink(remote.videoSink)
            Accessible.role: Accessible.Graphic
            Accessible.name: "Their camera"
        }
        Loader {
            anchors.centerIn: parent
            active: !page.videoUp
            sourceComponent: avatar
        }
        // Yours, like a mirror.
        Rectangle {
            visible: Calls.cameraOn && !page.ended
            anchors.right: parent.right; anchors.top: parent.top
            anchors.margins: Theme.px(24)
            width: Math.min(Theme.px(220), Math.round(parent.width * 0.35)); height: Math.round(width * 0.75)
            radius: Theme.radius
            color: Theme.tile
            border.width: 1; border.color: Theme.line
            clip: true
            VideoOutput {
                id: self
                anchors.fill: parent
                anchors.margins: 1
                fillMode: VideoOutput.PreserveAspectCrop
                transform: Scale { origin.x: self.width / 2; xScale: -1 }
                Component.onCompleted: Calls.setLocalSink(self.videoSink)
            }
            Accessible.role: Accessible.Graphic
            Accessible.name: "Your camera"
        }
    }

    // Under the picture: who, which ways the sound goes, and how long.
    Rectangle {
        id: strip
        visible: page.videoLayout
        anchors.left: parent.left; anchors.right: parent.right
        anchors.bottom: videoButtons.top; anchors.bottomMargin: Theme.px(18)
        height: stripText.implicitHeight + Theme.px(28)
        color: Theme.panel
        Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top; height: 1; color: Theme.line }
        Column {
            id: stripText
            anchors.left: parent.left; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: Theme.px(24); anchors.rightMargin: Theme.px(24)
            spacing: Theme.px(10)
            Item {
                width: parent.width; height: Math.max(stripName.implicitHeight, stripStatus.implicitHeight)
                Text {
                    textFormat: Text.PlainText
                    id: stripName
                    anchors.left: parent.left; anchors.right: stripStatus.left; anchors.rightMargin: Theme.px(16)
                    anchors.verticalCenter: parent.verticalCenter
                    elide: Text.ElideRight
                    text: page.who
                    color: Theme.text
                    font.family: Theme.display; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1)
                }
                Text {
                    textFormat: Text.PlainText
                    id: stripStatus
                    anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                    text: Calls.statusText + (Calls.peerMuted ? "  ·  they're muted" : "")
                    color: Calls.state === "reconnecting" ? Theme.danger : Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(17)
                }
            }
            Loader { active: page.videoLayout; sourceComponent: pathChips }
        }
    }

    Loader {
        id: videoButtons
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(24)
        active: page.videoLayout
        visible: active
        sourceComponent: callButtons
    }

    // A voice call (or one whose cameras are both off): the avatar in the middle, everything under it.
    Column {
        visible: !page.videoLayout
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        width: Math.min(parent.width - Theme.px(60), Theme.px(620))
        spacing: Theme.px(18)

        Loader {
            anchors.horizontalCenter: parent.horizontalCenter
            active: !page.videoLayout
            sourceComponent: avatar
        }
        Text {
            textFormat: Text.PlainText
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
            text: page.who
            color: Theme.text
            font.family: Theme.display; font.pixelSize: Theme.fs(28); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1)
        }
        Text {
            textFormat: Text.PlainText
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            text: Calls.statusText + (Calls.peerMuted && !page.ended ? "  ·  they're muted" : "")
            color: page.ended || Calls.state === "reconnecting" ? Theme.danger : Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(19)
        }
        Loader {
            anchors.horizontalCenter: parent.horizontalCenter
            active: !page.ended && !page.videoLayout
            visible: active
            sourceComponent: pathChips
        }

        Item { width: 1; height: Theme.px(8) }

        Loader {
            anchors.horizontalCenter: parent.horizontalCenter
            active: !page.ended && !page.videoLayout
            visible: active
            sourceComponent: callButtons
        }
    }

    function handleButton(b) {
        if (ended) return true
        switch (b) {
        case "a": Ui.toggleMic(); break
        case "y": Calls.nextOutput(); break
        case "x": if (Calls.videoPossible) Calls.toggleCamera(); else pressHangup(); break
        case "b": pressHangup(); break
        }
        return true
    }
    function handleRelease(b) {}
}

import QtQuick
import Huginn

// Link your phone (linked devices, protocol.md section 15), this device as the companion. One screen over the
// whole window, on the season's own ground: the raven, a big QR code of the link code (a new one comes a minute
// before the old one runs out) and where to scan it in Muninn. When the phone takes it the same screen asks
// "Do these match?" with the 4 emoji, then says "Linked". Shown once on first open (after the lock is set,
// when nothing is linked) and from Settings › Linked devices › Link this PC to my phone. The D-pad moves between the
// buttons, A presses one, B goes back (Skip on first open, Later while the emoji wait, Done once linked).
// It never shows over the lock: it closes when Huginn locks.
Item {
    id: screen
    visible: false
    anchors.fill: parent

    property string stage: "code"     // "code", "match" or "linked"
    property bool first: false        // first open: Skip instead of Back
    property bool tour: false
    property int focusIndex: 0
    readonly property bool pad: !!Ui.main && Ui.main.padInUse

    readonly property var phoneCheck: tour || stage !== "match" ? null : (Linked.phone, Linked.unchecked().find(u => u.key === Linked.phone.key) || null)
    readonly property string emoji: tour ? "🐺 🌲 🔥 🛡" : phoneCheck ? phoneCheck.emoji : ""
    readonly property string phoneName: tour ? "Pixel" : (Linked.phone.name || "your phone")
    readonly property string shownCode: tour ? "MUNINNL1:eyJrIjoidG91ciJ9" : Linked.code

    // The buttons on show, in order, and the one the controller is on.
    readonly property var buttons: stage === "code" ? [skipBtn, copyBtn] : stage === "match" ? [matchBtn, noBtn, laterBtn] : [doneBtn]
    readonly property int focusAt: Math.min(focusIndex, Math.max(0, buttons.length - 1))
    readonly property string backLabel: stage === "linked" ? "Done" : stage === "match" ? "Later" : first ? "Skip" : "Back"
    readonly property var prompts: [{ key: "dpad", label: "Choose" }, { key: "a", label: buttons[focusAt] ? buttons[focusAt].text : "" },
                                    { key: "b", label: backLabel }]

    // isFirst: the first open. tourStage: the screenshot tour's stage (nothing real is linked or shown).
    function open(isFirst, tourStage) {
        if (!tourStage && Vault.screenLocked) return   // never over the lock
        tour = !!tourStage
        first = !!isFirst
        stage = tourStage ? tourStage : (Linked.phone.key && !Linked.phone.ok) ? "match" : "code"
        focusIndex = 0
        visible = true
        if (stage === "code" && !tour) Linked.freshCode()
    }
    function close() {
        visible = false
        tour = false
        if (Ui.main) Ui.main.restoreFocus()
    }
    function goBack() { close() }
    function match() {
        if (!tour && Linked.phone.key) Linked.confirm(Linked.phone.key)
        stage = "linked"; focusIndex = 0
    }
    function notMatching() {
        if (!tour && Linked.phone.key) Linked.unlink(Linked.phone.key)
        stage = "code"; focusIndex = 0
        if (!tour) { Linked.freshCode(); Ui.say("Not linked. Scan the new code to try again.", true) }
    }
    // For a phone without a camera: the code as text, to paste in Muninn.
    function copyInstead() {
        if (tour || shownCode === "") return
        Chat.copyText(shownCode, true)
        Ui.note("Link code copied", "Paste it in Muninn: Settings › Linked devices › Scan to link. Works once, for 10 minutes. It's cleared from the clipboard in a minute.")
    }
    function handleButton(b) {
        if (b === "left" || b === "up") focusIndex = Math.max(0, focusAt - 1)
        else if (b === "right" || b === "down") focusIndex = Math.min(buttons.length - 1, focusAt + 1)
        else if (b === "a" && buttons[focusAt]) buttons[focusAt].clicked()
        else if (b === "b") goBack()
        return true
    }

    // A new code a minute before the one on show runs out, without a word.
    Timer {
        interval: 5000; repeat: true
        running: screen.visible && screen.stage === "code" && !screen.tour
        onTriggered: Linked.freshCode()
    }
    // Huginn locked: this screen closes, so nothing of it shows over the lock.
    Connections {
        target: Vault
        enabled: screen.visible && !screen.tour
        function onChanged() { if (Vault.screenLocked) screen.close() }
    }
    // The phone took the code, the emoji were checked here or there, or it went away.
    Connections {
        target: Linked
        enabled: screen.visible && !screen.tour
        function onChanged() {
            if (screen.stage === "code" && Linked.phone.key && !Linked.phone.ok) { screen.stage = "match"; screen.focusIndex = 0 }
            else if (screen.stage === "match" && Linked.phone.key && Linked.phone.ok) { screen.stage = "linked"; screen.focusIndex = 0 }
            else if (screen.stage === "match" && !Linked.phone.key) { screen.stage = "code"; screen.focusIndex = 0; Linked.freshCode() }
        }
    }

    // The season's ground (and what falls) behind it, as everywhere else.
    Rectangle { anchors.fill: parent; color: Theme.ground }
    Leaves { anchors.fill: parent }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

    Flickable {
        id: view
        anchors.fill: parent
        contentWidth: width
        contentHeight: Math.max(height, col.implicitHeight + Theme.px(48))
        boundsBehavior: Flickable.StopAtBounds
        clip: true
        Column {
            id: col
            width: Math.min(view.width - Theme.px(48), Theme.px(520))
            x: (view.width - width) / 2
            y: Math.max(Theme.px(24), (view.contentHeight - implicitHeight) / 2)
            spacing: Theme.px(16)

            Image {
                anchors.horizontalCenter: parent.horizontalCenter
                source: "assets/raven-gold.png"
                width: Theme.px(64); height: width
                sourceSize.width: 256; sourceSize.height: 256
            }
            Text {
                textFormat: Text.PlainText
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: screen.stage === "code" ? "LINK YOUR PHONE" : screen.stage === "match" ? "DO THESE MATCH?" : "LINKED"
                color: Theme.textBright
                font.family: Theme.display; font.pixelSize: Theme.fs(26); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
            }

            // ------------------------------------------------- the code
            Rectangle {
                visible: screen.stage === "code"
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.max(Theme.px(140), Math.min(parent.width, Theme.px(300), screen.height * 0.4)); height: width
                radius: Theme.radius
                color: "#E4E1DA"   // the light paper the code is drawn on, in every look
                border.width: Theme.px(2)
                border.color: Theme.accent
                Image {
                    anchors.fill: parent; anchors.margins: Theme.px(14)
                    smooth: false
                    fillMode: Image.PreserveAspectFit
                    source: screen.visible && screen.stage === "code" && screen.shownCode !== "" ? "image://qr/" + encodeURIComponent(screen.shownCode) : ""
                }
                Text {
                    textFormat: Text.PlainText
                    visible: screen.shownCode === ""
                    anchors.centerIn: parent
                    text: "Making a code…"
                    color: "#3B362E"; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
            }
            Text {
                textFormat: Text.PlainText
                visible: screen.stage === "code"
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: "In Muninn: Settings › Linked devices › Scan to link"
                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17)
            }

            // ------------------------------------------------- the 4 emoji
            Text {
                textFormat: Text.PlainText
                visible: screen.stage === "match"
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: "The same 4 on " + screen.phoneName + "?"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16)
            }
            Rectangle {
                visible: screen.stage === "match"
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(parent.width, emojiText.implicitWidth + Theme.px(48)); height: emojiText.implicitHeight + Theme.px(28)
                radius: Theme.radius
                color: Theme.panel
                border.color: Theme.accent
                Text {
                    id: emojiText
                    textFormat: Text.PlainText
                    anchors.centerIn: parent
                    width: Math.min(implicitWidth, parent.width - Theme.px(24))
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    text: screen.emoji
                    color: Theme.text; font.pixelSize: Theme.fs(44)
                }
            }

            // ------------------------------------------------- linked
            Rectangle {
                visible: screen.stage === "linked"
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.px(72); height: width
                radius: Theme.radius
                color: Theme.accentFill
                border.color: Theme.accent; border.width: Theme.px(2)
                Icon { anchors.centerIn: parent; name: "check"; size: Theme.px(40); color: Theme.accent; lineWidth: 2.4 }
            }
            Text {
                textFormat: Text.PlainText
                visible: screen.stage === "linked"
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: "Your chats, contacts, groups and folders are coming over. Photos follow on home Wi-Fi."
                color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17)
            }

            // ------------------------------------------------- buttons
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.px(10)
                topPadding: Theme.px(4)
                HButton {
                    id: matchBtn
                    visible: screen.stage === "match"
                    text: "They match"; kind: "primary"
                    focused: screen.pad && screen.buttons[screen.focusAt] === matchBtn
                    onClicked: screen.match()
                }
                HButton {
                    id: noBtn
                    visible: screen.stage === "match"
                    text: "No"
                    focused: screen.pad && screen.buttons[screen.focusAt] === noBtn
                    onClicked: screen.notMatching()
                }
                // Later: the emoji wait; Settings › Linked devices asks again.
                HButton {
                    id: laterBtn
                    visible: screen.stage === "match"
                    text: "Later"; kind: "plain"
                    focused: screen.pad && screen.buttons[screen.focusAt] === laterBtn
                    onClicked: screen.close()
                }
                HButton {
                    id: doneBtn
                    visible: screen.stage === "linked"
                    text: "Done"; kind: "primary"
                    focused: screen.pad && screen.buttons[screen.focusAt] === doneBtn
                    onClicked: screen.close()
                }
                HButton {
                    id: skipBtn
                    visible: screen.stage === "code"
                    text: screen.first ? "Skip" : "Back"; kind: "plain"
                    focused: screen.pad && screen.buttons[screen.focusAt] === skipBtn
                    onClicked: screen.close()
                }
            }
            HButton {
                id: copyBtn
                visible: screen.stage === "code"
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Copy the code instead"; kind: "plain"; fontSize: 13
                implicitHeight: Theme.px(30)
                focused: screen.pad && screen.buttons[screen.focusAt] === copyBtn
                onClicked: screen.copyInstead()
            }
        }
    }
}

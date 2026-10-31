import QtQuick
import Huginn

// Huginn's lock, required like Muninn's. "setup": first time, pick a 9-dot pattern or a password.
// "unlock": draw or type it. "change": Settings › Privacy and lock › Lock (already unlocked).
// "off": Settings › Turn off the lock: the current one is drawn or typed again first.
// Everything Huginn keeps is locked with it; there's no way to recover it. The pattern sits in the
// lower half (Settings › Privacy and lock › Pattern position: Top, Middle or Lower).
Item {
    id: panel
    property string mode: Vault.exists ? "unlock" : "setup"
    // Unlocking, or turning the lock off: the current pattern or password is asked for.
    readonly property bool asks: mode === "unlock" || mode === "off"
    property bool usePattern: asks ? Vault.usesPattern : true
    property var first: null
    property string note: ""
    property bool bad: false
    property int wait: Vault.waitSeconds
    signal finished(string how)   // "setup", "unlock", "change" or "off": what just finished
    signal cancelled()

    implicitWidth: Theme.px(520)
    // How the free room splits above and below the pattern.
    readonly property var grow: Settings.lockPosition === "top" ? [0.15, 1] : Settings.lockPosition === "middle" ? [1, 1] : [1, 0.12]

    Timer { interval: 500; repeat: true; running: panel.wait > 0; onTriggered: panel.wait = Vault.waitSeconds }

    function saveSecret(secret, pattern) {
        if (Vault.setSecret(secret, pattern)) {
            Ui.say(pattern ? "Pattern set." : "Password set.")
            first = null; note = ""; bad = false
            finished(mode === "setup" ? "setup" : "change")
        } else { note = "That didn't save. Try again."; bad = true }
    }
    function tryUnlock(secret) {
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        if (Vault.unlock(secret)) { note = ""; bad = false; finished("unlock") }
        else { note = "That's not it. Try again."; bad = true; wait = Vault.waitSeconds; pad.shake() }
    }
    function tryOff(secret) {
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        if (Vault.turnOffLock(secret)) { note = ""; bad = false; Ui.say("The lock is off."); finished("off") }
        else { note = "That's not it. Try again."; bad = true; wait = Vault.waitSeconds; pad.shake() }
    }
    function onPattern(dots) {
        const secret = "pattern:" + dots.join(",")
        if (mode === "unlock") { tryUnlock(secret); return }
        if (mode === "off") { tryOff(secret); return }
        if (dots.length < 6) { note = "Connect at least 6 dots."; bad = true; pad.shake(); return }
        if (first === null) { first = dots; note = "Draw it again to confirm."; bad = false; return }
        if (first.join(",") !== dots.join(",")) { first = null; note = "That didn't match. Start over."; bad = true; pad.shake(); return }
        saveSecret(secret, true)
    }
    // The password typed in the field on the lock screen.
    function submitPassword() {
        const t = pw.text
        if (mode === "unlock") { tryUnlock(t); pw.text = ""; return }
        if (mode === "off") { tryOff(t); pw.text = ""; return }
        if (t.length < 6) { note = "At least 6 characters."; bad = true; return }
        if (pw2.text !== t) { note = "They didn't match. Try again."; bad = true; pw2.text = ""; return }
        pw.text = ""; pw2.text = ""
        saveSecret(t, false)
    }
    function focusField() { if (!usePattern) pw.forceActiveFocus() }
    onUsePatternChanged: if (visible) Qt.callLater(focusField)
    onVisibleChanged: if (visible) Qt.callLater(focusField)

    Column {
        id: intro
        width: parent.width
        spacing: Theme.px(10)
        topPadding: Theme.px(28)
        Image {
            anchors.horizontalCenter: parent.horizontalCenter
            source: "assets/raven-gold.png"
            width: Theme.px(56); height: width
            sourceSize.width: 256; sourceSize.height: 256
        }
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: panel.mode === "setup" ? "PROTECT YOUR MESSAGES" : panel.mode === "change" ? "CHANGE THE LOCK"
                : panel.mode === "off" ? "TURN OFF THE LOCK" : "HUGINN"
            color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(22); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
        }
        Text {
            width: parent.width; wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            text: panel.asks
                  ? (panel.usePattern ? "Draw your pattern" : "Type your password")
                  : "No way to recover it."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
        Row {
            visible: !panel.asks
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Theme.px(8)
            HButton { text: "Pattern"; kind: panel.usePattern ? "accent" : "normal"; onClicked: { panel.usePattern = true; panel.first = null; panel.note = "" } }
            HButton { text: "Password"; kind: !panel.usePattern ? "accent" : "normal"; onClicked: { panel.usePattern = false; panel.first = null; panel.note = "" } }
        }
        Text {
            width: parent.width; wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            visible: text !== ""
            text: panel.wait > 0 ? "Too many tries. Wait " + panel.wait + " s."
                : panel.note !== "" ? panel.note
                : ""
            color: panel.bad || panel.wait > 0 ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }

    // The pattern (or the password) sits where Settings says: the free room is split above and below.
    Item {
        id: room
        anchors.top: intro.bottom
        anchors.bottom: foot.top
        width: parent.width
        readonly property real free: Math.max(0, height - entry.height)
        Item {
            id: entry
            y: room.free * panel.grow[0] / (panel.grow[0] + panel.grow[1])
            width: parent.width
            height: panel.usePattern ? pad.height : pwCol.implicitHeight
            PatternPad {
                id: pad
                visible: panel.usePattern
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(Theme.px(260), room.height, parent.width); height: width
                error: panel.bad
                enabled: panel.wait === 0
                onDone: d => panel.onPattern(d)
            }
            Column {
                id: pwCol
                visible: !panel.usePattern
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(parent.width, Theme.px(320))
                spacing: Theme.px(8)
                PwField { id: pw; placeholder: panel.asks ? "Password" : "New password (6 or more)"; onEnter: panel.asks ? panel.submitPassword() : pw2.forceActiveFocus() }
                PwField { id: pw2; visible: !panel.asks; placeholder: "Type it again"; onEnter: panel.submitPassword() }
                HButton { anchors.horizontalCenter: parent.horizontalCenter; kind: "primary"; text: panel.mode === "unlock" ? "Unlock" : panel.mode === "off" ? "Turn off" : "Save"; enabled: panel.wait === 0; onClicked: panel.submitPassword() }
            }
        }
    }

    Column {
        id: foot
        anchors.bottom: parent.bottom
        width: parent.width
        spacing: Theme.px(8)
        bottomPadding: Theme.px(16)
        Text {
            width: parent.width; wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            visible: Gamepad.connected && panel.usePattern
            text: "D-pad moves. A starts and ends. B clears."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
        HButton {
            visible: panel.mode === "change" || panel.mode === "off"
            anchors.horizontalCenter: parent.horizontalCenter
            kind: "plain"; text: "Cancel"
            onClicked: panel.cancelled()
        }
    }

    component PwField: Rectangle {
        id: pf
        property alias text: input.text
        property string placeholder
        signal enter()
        function forceActiveFocus() { input.forceActiveFocus() }
        width: parent ? parent.width : 0
        height: Theme.px(40)
        radius: Theme.radius
        color: Theme.groundDeep
        border.color: input.activeFocus ? Theme.accent : Theme.line
        TextInput {
            id: input
            anchors.fill: parent; anchors.leftMargin: Theme.px(10); anchors.rightMargin: Theme.px(10)
            verticalAlignment: TextInput.AlignVCenter
            echoMode: TextInput.Password
            color: Theme.text
            selectionColor: Theme.accentFill
            font.family: Theme.body; font.pixelSize: Theme.fs(15)
            clip: true
            Keys.onReturnPressed: pf.enter()
            Keys.onEnterPressed: pf.enter()
            Keys.onEscapePressed: { input.text = ""; if (Ui.main) Ui.main.restoreFocus() }
        }
        Text {
            visible: input.text.length === 0
            anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            text: pf.placeholder
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        TapHandler { onTapped: input.forceActiveFocus() }
    }

    function handleButton(b) {
        if (usePattern && pad.handleButton(b)) return true
        if (b === "a" && !usePattern) { if (pw.text.length) submitPassword(); else { pw.forceActiveFocus(); System.showSteamKeyboard() } return true }
        if ((b === "lb" || b === "next" || b === "prev") && !asks) { usePattern = !usePattern; first = null; note = ""; return true }
        if (b === "b") { cancelled(); return true }
        return false
    }
}

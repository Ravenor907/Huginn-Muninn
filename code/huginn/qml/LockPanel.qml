import QtQuick
import Huginn

// Huginn's lock, required like Muninn's. "setup": first time, pick a 9-dot pattern or a password.
// "unlock": draw or type it. "change": Settings › Privacy and lock › Lock (already unlocked).
// "off": Settings › Turn off the lock: the current one is drawn or typed again first.
// "own": Settings › Linked devices › This device's own chats: the current one opens them while a phone is linked.
// Everything Huginn keeps is locked with it; there's no way to recover it. The pattern sits in the
// lower half (Settings › Privacy and lock › Pattern position: Top, Middle or Lower).
Item {
    id: panel
    property string mode: Vault.exists ? "unlock" : "setup"
    // Unlocking, or turning the lock off: the current pattern or password is asked for.
    readonly property bool asks: mode === "unlock" || mode === "off" || mode === "own"
    // Setting one: a password (a passphrase) with a mouse and keyboard; the pattern with a controller or on a handheld.
    property bool usePattern: asks ? Vault.usesPattern : (System.handheld || System.gameMode || (!!Ui.main && Ui.main.padInUse))
    property var first: null
    // A controller pattern is under way (B clears it), for the button legend.
    readonly property bool drawing: usePattern && pad.drawing
    property string note: ""
    property bool bad: false
    property int wait: Vault.waitSeconds
    // Asking from somewhere else (Report a bug): a Cancel button with this text, and a line saying why.
    property bool cancelable: false
    property string cancelText: "Cancel"
    property string lead: ""
    // First setup's practice (Labs › Replay first setup): a pattern or password is checked as usual but never saved.
    property bool practice: false
    // A short window: no raven, so the pattern keeps its room.
    property bool compact: false
    // The least height that still gives the pattern 200 px (a page that scrolls uses it).
    readonly property real minHeight: intro.implicitHeight + Theme.px(200) + foot.implicitHeight
    signal finished(string how)   // "setup", "unlock", "change", "off" or "own": what just finished
    signal cancelled()

    implicitWidth: Theme.px(520)
    // How the free room splits above and below the pattern.
    readonly property var grow: Settings.lockPosition === "top" ? [0.15, 1] : Settings.lockPosition === "middle" ? [1, 1] : [1, 0.12]

    Timer { interval: 500; repeat: true; running: panel.wait > 0; onTriggered: panel.wait = Vault.waitSeconds }

    function saveSecret(secret, pattern) {
        if (practice) {
            Ui.say("Practice: no lock was set.")
            first = null; note = ""; bad = false
            finished("practice")
            return
        }
        if (Vault.setSecret(secret, pattern)) {
            Ui.say(pattern ? "Pattern set." : "Password set.")
            first = null; note = ""; bad = false
            finished(mode === "setup" ? "setup" : "change")
        } else { note = "That didn't save. Try again."; bad = true }
    }
    // Unlocking works out the key (and reads the chats) off the window's thread: a ring turns until it's done.
    property bool unlockAsked: false
    function tryUnlock(secret) {
        if (Vault.unlocking) return
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        unlockAsked = true
        note = ""; bad = false
        Vault.unlockInBackground(secret)
    }
    Connections {
        target: Vault
        function onUnlockFinished(ok) {
            if (!panel.unlockAsked) return
            panel.unlockAsked = false
            if (ok) { panel.note = ""; panel.bad = false; panel.finished("unlock") }
            else { panel.note = "That's not it. Try again."; panel.bad = true; panel.wait = Vault.waitSeconds; pad.shake() }
        }
    }
    function tryOff(secret) {
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        if (Vault.turnOffLock(secret)) { note = ""; bad = false; Ui.say("The lock is off."); finished("off") }
        else { note = "That's not it. Try again."; bad = true; wait = Vault.waitSeconds; pad.shake() }
    }
    function tryOwn(secret) {
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        if (Linked.openOwn(secret)) { note = ""; bad = false; finished("own") }
        else { note = "That's not it. Try again."; bad = true; wait = Vault.waitSeconds; pad.shake() }
    }
    function onPattern(dots) {
        const secret = "pattern:" + dots.join(",")
        if (mode === "unlock") { tryUnlock(secret); return }
        if (mode === "off") { tryOff(secret); return }
        if (mode === "own") { tryOwn(secret); return }
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
        if (mode === "own") { tryOwn(t); pw.text = ""; return }
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
        topPadding: Theme.px(panel.compact ? 8 : 28)
        Image {
            visible: !panel.compact
            anchors.horizontalCenter: parent.horizontalCenter
            source: "assets/raven-gold.png"
            width: Theme.px(56); height: width
            sourceSize.width: 256; sourceSize.height: 256
        }
        Text {
            textFormat: Text.PlainText
            anchors.horizontalCenter: parent.horizontalCenter
            text: panel.mode === "setup" ? "SET A LOCK" : panel.mode === "change" ? "CHANGE THE LOCK"
                : panel.mode === "off" ? "TURN OFF THE LOCK" : panel.mode === "own" ? "THIS DEVICE'S OWN CHATS" : "HUGINN"
            color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(22); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
        }
        Text {
            textFormat: Text.PlainText
            width: parent.width; wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            text: panel.asks
                  ? (panel.lead !== "" ? panel.lead + "\n" : "") + (panel.usePattern ? "Draw your pattern" : "Type your password")
                  : panel.mode === "setup" ? (panel.usePattern ? "Keeps your chats private on this computer. Connect at least 6 dots. Your chats are locked with it, so nobody can reset it: pick one you'll remember, and make a backup after setup (Settings › Backup)."
                                                                : "Keeps your chats private on this computer. Your chats are locked with it, so nobody can reset it: pick one you'll remember, and make a backup after setup (Settings › Backup). A few words make a good password.")
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
            textFormat: Text.PlainText
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
                enabled: panel.wait === 0 && !Vault.unlocking
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
                HButton { anchors.horizontalCenter: parent.horizontalCenter; kind: "primary"; text: panel.mode === "unlock" ? "Unlock" : panel.mode === "off" ? "Turn off" : panel.mode === "own" ? "Open" : "Save"; enabled: panel.wait === 0 && !Vault.unlocking; onClicked: panel.submitPassword() }
            }
            // The progress ring while Huginn unlocks: an arc drawn once, turned (still when motion is off).
            Item {
                id: ring
                visible: Vault.unlocking && panel.unlockAsked
                anchors.centerIn: parent
                width: Theme.px(56); height: width
                Rectangle { anchors.fill: parent; radius: width / 2; color: Theme.groundDeep; opacity: 0.85 }
                Canvas {
                    id: arc
                    anchors.fill: parent
                    anchors.margins: Theme.px(8)
                    property color tint: Theme.accent
                    onTintChanged: requestPaint()
                    onPaint: {
                        const c = getContext("2d")
                        c.reset()
                        const w = Theme.px(4)
                        c.lineWidth = w
                        c.lineCap = "round"
                        c.strokeStyle = tint
                        c.beginPath()
                        c.arc(width / 2, height / 2, width / 2 - w, 0, Math.PI * 1.5)
                        c.stroke()
                    }
                    RotationAnimator on rotation {
                        from: 0; to: 360; duration: 900
                        loops: Animation.Infinite
                        running: ring.visible && Theme.motion
                    }
                }
                Accessible.role: Accessible.ProgressBar
                Accessible.name: "Unlocking"
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
            textFormat: Text.PlainText
            width: parent.width; wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            visible: Gamepad.connected && panel.usePattern && !!Ui.main && Ui.main.padInUse
            text: "D-pad moves. A starts and ends. B clears."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
        HButton {
            visible: panel.cancelable || panel.mode === "change" || panel.mode === "off" || panel.mode === "own"
            anchors.horizontalCenter: parent.horizontalCenter
            kind: "plain"; text: panel.cancelText
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
            FieldMenu {}   // right click: Cut, Copy, Paste, Select all
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
            textFormat: Text.PlainText
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
        // Setting one: A in the first field goes on to the second.
        if (b === "a" && !usePattern && !asks && pw.text.length && pw2.text.length === 0) { pw2.forceActiveFocus(); System.showSteamKeyboard(); return true }
        if (b === "a" && !usePattern) { if (pw.text.length) submitPassword(); else { pw.forceActiveFocus(); System.showSteamKeyboard() } return true }
        if ((b === "lb" || b === "next" || b === "prev") && !asks) { usePattern = !usePattern; first = null; note = ""; return true }
        if (b === "b") { cancelled(); return true }
        return false
    }
}

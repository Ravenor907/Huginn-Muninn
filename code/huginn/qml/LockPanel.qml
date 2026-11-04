import QtQuick
import Huginn

// Huginn's lock, required like Muninn's. "setup": first time, pick a 9-dot pattern or a password.
// "unlock": draw or type it. "change": Settings › Privacy and lock › Lock (already unlocked).
// "off": Settings › Turn off the lock: the current one is drawn or typed again first.
// "own": Settings › Linked devices › This device's own chats: the current one opens them while a phone is linked.
// "renew": restored after "Forgot it?" (Vault.lockNeeded): a new lock, like "setup", with no way to skip it.
// "confirm" (round 17, LockCheck.qml): the current one, before Back up, Restore, Move or linking another device.
// "spare" (1.42, Vault.spareStep): a pattern's unlock password, typed twice ("set": a pattern set before 1.42, or
// Settings › Unlock password), then once more from memory ("recall"). A new pattern asks for its unlock password right
// after it's drawn twice. Either one opens Huginn; wrong tries of both share one count (Vault.triesWords says what's next).
// Everything Huginn keeps is locked with it; nobody can reset it. "Forgot it?" under the unlock puts a backup back
// (BackupPanel's restore, erasing what's here, held to confirm), then a new lock is set. The pattern sits in the
// lower half (Settings › Privacy and lock › Pattern position: Top, Middle or Lower).
Item {
    id: panel
    property string mode: Vault.exists ? "unlock" : "setup"
    // Unlocking, or turning the lock off: the current pattern or password is asked for.
    readonly property bool asks: mode === "unlock" || mode === "off" || mode === "own" || mode === "confirm"
    // 1.42: a pattern lock asked for: its unlock password typed instead (either one opens Huginn).
    property bool spareTyped: false
    readonly property bool canSpare: asks && Vault.usesPattern && Vault.hasUnlockPassword
    // A new pattern drawn twice, waiting for its unlock password (typed twice) before it's saved.
    property string pendingPattern: ""
    // "spare": setting the unlock password ("set", or a new one after a wrong one from memory), or typing it from memory.
    property bool respare: false
    readonly property bool spareSet: mode === "spare" && (Vault.spareStep === "set" || respare)
    readonly property bool spareRecall: mode === "spare" && !spareSet
    // Setting one: a password (a passphrase) with a mouse and keyboard; the pattern with a controller or on a handheld.
    function patternDefault() {
        return mode === "spare" ? false : asks ? Vault.usesPattern && !spareTyped : (System.handheld || System.gameMode || (!!Ui.main && Ui.main.padInUse))
    }
    property bool usePattern: patternDefault()
    // Another mode (the same panel goes from unlock to change to spare): back to its own default, nothing half done.
    onModeChanged: {
        spareTyped = false; pendingPattern = ""; respare = false; first = null
        usePattern = Qt.binding(() => panel.patternDefault())
    }
    // The steps after wrong tries, in plain words.
    readonly property string rules: "If someone keeps guessing, Huginn makes them wait: 5 wrong tries, 5 minutes. 5 more, 1 hour. 5 more, 12 hours. Then 24 hours after every 5 more."
    // The screenshot tour: the words after this many wrong tries (shown only, nothing is counted).
    property int sampleFails: -1
    property bool sampleErase: false
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
    signal finished(string how)   // "setup", "unlock", "change", "off", "own", "confirm" or "spare": what just finished
    // "confirm": what the lock is given for (Vault.confirmLock).
    property string grantFor: ""
    // Round 17: setting a lock after it was off seals everything again on a thread of its own (Vault.sealing).
    property bool sealAsked: false
    property bool sealPattern: false
    readonly property bool busy: Vault.unlocking || Vault.sealing
    signal cancelled()

    implicitWidth: Theme.px(520)
    // How the free room splits above and below the pattern.
    readonly property var grow: Settings.lockPosition === "top" ? [0.15, 1] : Settings.lockPosition === "middle" ? [1, 1] : [1, 0.12]

    Timer { interval: 500; repeat: true; running: panel.wait > 0; onTriggered: panel.wait = Vault.waitSeconds }
    // Round 19 (m3 handheld): wait loses its binding once set, so a wait begun elsewhere (Confirm it's you) is read again
    // whenever the vault changes or the panel shows.
    Connections { target: Vault; function onChanged() { panel.wait = Vault.waitSeconds } }
    // Round 19 (M3 handheld): a wait that just ended leaves no "Wait 5 minutes" behind: the live line says what's next.
    onWaitChanged: if (wait === 0 && bad && asks) { note = ""; bad = false }
    // Both fields emptied, so A starts again from the first prompt (round 19, B1 handheld).
    function clearFields() { pw.text = ""; pw2.text = "" }

    function saveSecret(secret, pattern, spare) {
        if (practice) {
            Ui.say("Practice: no lock was set.")
            first = null; pendingPattern = ""; note = ""; bad = false
            finished("practice")
            return
        }
        if (Vault.setSecret(secret, pattern, spare || "")) {
            Ui.say(pattern ? "Pattern and unlock password set." : "Password set.")
            first = null; pendingPattern = ""; note = ""; bad = false
            finished(mode === "setup" ? "setup" : mode === "renew" ? "renew" : "change")
        } else if (Vault.sealing) {
            // Sealing every file again with the new lock: the progress shows here, and secretFinished says how it ended.
            sealAsked = true; sealPattern = pattern
            first = null; pendingPattern = ""; note = ""; bad = false
        } else { note = Vault.secretError() || "That didn't save. Try again."; bad = true }
    }
    // A wrong pattern or password: said, with what the next wrong tries bring. Round 19 (FIX 15): a try that couldn't
    // be counted wasn't checked at all, and says why.
    function wrongNote() { return Vault.tryError() || ("That's not it. " + Vault.triesWords()).trim() }
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
            if (ok) { panel.note = ""; panel.bad = false; panel.spareTyped = false; panel.finished("unlock") }
            else { panel.note = panel.wrongNote(); panel.bad = true; panel.wait = Vault.waitSeconds; pad.shake() }
        }
        function onSecretFinished(ok, error) {
            if (!panel.sealAsked) return
            panel.sealAsked = false
            if (ok) {
                Ui.say(panel.sealPattern ? "Pattern and unlock password set. Your chats are sealed with them." : "Password set. Your chats are sealed with it.")
                panel.note = ""; panel.bad = false
                panel.finished("change")
            } else { panel.note = error || "That didn't save. Try again."; panel.bad = true }
        }
    }
    function tryConfirm(secret) {
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        if (Vault.confirmLock(secret, grantFor)) { note = ""; bad = false; spareTyped = false; finished("confirm") }
        else { note = wrongNote(); bad = true; wait = Vault.waitSeconds; pad.shake() }
    }
    function tryOff(secret) {
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        if (Vault.turnOffLock(secret)) { note = ""; bad = false; Ui.say("The lock is off."); finished("off") }
        else { note = wrongNote(); bad = true; wait = Vault.waitSeconds; pad.shake() }
    }
    // Round 19 (owner decision): inside the open app, never counted (no wait, no erase), so no tries are spoken of.
    function tryOwn(secret) {
        if (Linked.openOwn(secret)) { note = ""; bad = false; spareTyped = false; finished("own") }
        else { note = "That's not it."; bad = true; pad.shake() }
    }
    function onPattern(dots) {
        const secret = "pattern:" + dots.join(",")
        if (mode === "unlock") { tryUnlock(secret); return }
        if (mode === "off") { tryOff(secret); return }
        if (mode === "own") { tryOwn(secret); return }
        if (mode === "confirm") { tryConfirm(secret); return }
        if (dots.length < 6) { note = "Connect at least 6 dots."; bad = true; pad.shake(); return }   // the rule since 1.0, not a new one
        if (first === null) { first = dots; note = "Draw it again to confirm."; bad = false; return }
        if (first.join(",") !== dots.join(",")) { first = null; note = "That didn't match. Start over."; bad = true; pad.shake(); return }
        // 1.42: a pattern needs its unlock password, typed twice, before both are saved together.
        pendingPattern = secret; first = null; usePattern = false
        note = "Now an unlock password, typed twice."; bad = false
        Qt.callLater(focusField)
    }
    // The password typed in the field on the lock screen.
    function submitPassword() {
        const t = pw.text
        if (mode === "spare") { submitSpare(t); return }
        // Round 19 (m2 handheld): nothing typed is no try at all.
        if (asks && t === "") return
        if (mode === "unlock") { tryUnlock(t); pw.text = ""; return }
        if (mode === "off") { tryOff(t); pw.text = ""; return }
        if (mode === "own") { tryOwn(t); pw.text = ""; return }
        if (mode === "confirm") { tryConfirm(t); pw.text = ""; return }
        // Round 19 (B1 handheld): every failure empties both fields, so the next A asks for the first one again.
        if (t.length < 6) { note = "At least 6 characters."; bad = true; clearFields(); return }
        if (pw2.text !== t) { note = "They didn't match. Type both again."; bad = true; clearFields(); return }
        if (pendingPattern !== "" && t.startsWith("pattern:")) { note = "Pick other words for your unlock password."; bad = true; clearFields(); return }
        clearFields()
        if (pendingPattern !== "") saveSecret(pendingPattern, true, t)
        else saveSecret(t, false)
    }
    // "spare": set it (typed twice; then it's asked from memory), or type it from memory.
    function submitSpare(t) {
        if (spareRecall) {
            clearFields()
            if (t === "") return
            if (Vault.recallUnlockPassword(t)) { note = ""; bad = false; Ui.say("That's it. Keep it somewhere safe."); finished("spare") }
            else { note = "That's not it. Try again, or set a new one."; bad = true }
            return
        }
        if (t.length < 6) { note = "At least 6 characters."; bad = true; clearFields(); return }
        if (pw2.text !== t) { note = "They didn't match. Type both again."; bad = true; clearFields(); return }
        if (t.startsWith("pattern:")) { note = "Pick other words for your unlock password."; bad = true; clearFields(); return }
        clearFields()
        if (Vault.setUnlockPassword(t)) { respare = false; note = "Saved. Now type it once more, from memory."; bad = false; Qt.callLater(focusField) }
        else { note = "That didn't save. Try again."; bad = true }
    }
    // From memory went wrong: set a new one (Huginn is open, so nothing is lost). Y with a controller.
    function newSpare() { respare = true; note = ""; bad = false; clearFields(); Qt.callLater(focusField) }
    // A new pattern waiting for its unlock password: draw another instead (B with a controller).
    function redraw() { pendingPattern = ""; usePattern = true; note = ""; bad = false; clearFields() }
    // Round 19 (B1 and M5 handheld): the required unlock password screen always has a way out with a controller.
    // Settings › Change: Cancel keeps the one there is. A new one after a wrong one from memory: back to typing it.
    // Otherwise Lock: the lock screen comes back, and the pattern brings this screen back.
    function leaveSpare() {
        clearFields(); note = ""; bad = false
        if (Vault.spareStep === "") { cancelled(); return }   // the screenshot tour's sample
        if (respare && Vault.spareStep === "recall") { respare = false; Qt.callLater(focusField); return }
        if (Vault.spareStep === "set" && Vault.hasUnlockPassword) { Vault.cancelUnlockPasswordChange(); return }
        respare = false
        Vault.lockScreen()
    }
    function focusField() { if (!usePattern) pw.forceActiveFocus() }
    // The main button's words.
    readonly property string goText: mode === "unlock" ? "Unlock" : mode === "off" ? "Turn off" : mode === "own" ? "Open" : mode === "confirm" ? "Confirm"
        : spareRecall ? "Check" : "Save"
    // A pattern lock: type the unlock password instead of drawing, or back.
    function toggleSpare() {
        if (!canSpare) return
        spareTyped = !spareTyped
        note = ""; bad = false
        Qt.callLater(focusField)
    }
    // "Forgot it?": nobody can reset the lock, so the way back is a backup (the same steps as Muninn's).
    readonly property bool canForget: mode === "unlock" && !cancelable && !practice
    // On a PC linked to a phone the easier way back is to erase it and link the phone again (the chats come back from
    // the phone): a backup here holds this PC's own account, not the phone's chats (as the Backup screen says), so a
    // restore comes second. The next link tells the phone it's a relink, and the phone asks whether to replace its old
    // entry for this PC (protocol.md 15).
    function forgot() {
        const restore = () => { if (Ui.main) Ui.main.openBackup("restore", false, false, true) }
        if (Linked.linkedWhileLocked()) {
            Ui.sure("Forgot your lock?", "Your chats are encrypted with it, so nobody can open or reset it.\n\nThis PC is linked to your phone. "
                    + "A backup here holds this PC's own account, not your phone's chats, and restoring it can break the link.\n\nThe easier way: "
                    + "erase this PC and link your phone again. Your chats come back from your phone, and it asks to replace its old entry for this PC (if it still lists the old one, remove it in Linked devices). "
                    + "Anything only on this PC is erased. Huginn closes; open it again to link.",
                    "Erase and link my phone again", () => { Vault.eraseForRelink(); if (Ui.main) Ui.main.quitNow() }, true, true)
            Ui.confirm.otherText = "Restore from a backup"
            Ui.confirm.otherCallback = restore
            return
        }
        Ui.sure("Forgot your lock?", "Your chats are encrypted with it, so nobody can open or reset it. If you made a backup, you can erase what's on this computer "
                + "and put the backup back. Messages that came after the backup are lost. Then you set a new lock.\n\nNo backup? Settings › Devices and backup › Erase everything "
                + "starts again. Contacts will need to add you again.",
                "Restore from a backup", restore)
    }
    onUsePatternChanged: if (visible) Qt.callLater(focusField)
    onVisibleChanged: if (visible) { wait = Vault.waitSeconds; Qt.callLater(focusField) }

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
            text: panel.pendingPattern !== "" || panel.mode === "spare" ? "UNLOCK PASSWORD"
                : panel.mode === "setup" ? "SET A LOCK" : panel.mode === "renew" ? "SET A NEW LOCK" : panel.mode === "change" ? "CHANGE THE LOCK"
                : panel.mode === "off" ? "TURN OFF THE LOCK" : panel.mode === "own" ? "THIS DEVICE'S OWN CHATS"
                : panel.mode === "confirm" ? "CONFIRM IT'S YOU" : "HUGINN"
            color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(22); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
        }
        Text {
            textFormat: Text.PlainText
            width: parent.width; wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            text: panel.asks
                  ? (panel.lead !== "" ? panel.lead + "\n" : "") + (panel.usePattern ? "Draw your pattern" : Vault.usesPattern ? "Type your unlock password" : "Type your password")
                  : panel.spareRecall ? "Type your unlock password from memory, so you know you'll have it if you forget your pattern."
                  : panel.mode === "spare" ? (Vault.hasUnlockPassword ? "Type a new unlock password twice. It opens Huginn too, if you forget your pattern."
                                              : "Huginn now asks for an unlock password with every pattern. Either one opens Huginn, so if you forget the pattern, the unlock password still gets you in. It isn't your Backup password. " + panel.rules)
                  : panel.pendingPattern !== "" ? "A pattern also needs an unlock password. Either one opens Huginn, so if you forget the pattern, the unlock password still gets you in. It isn't your Backup password: that one is only for backup files."
                  : panel.mode === "renew" ? "Set a new lock. Your old one isn't in the backup. Nobody can reset it, so pick one you'll remember, and make a new backup once you're in (Settings › Devices and backup)."
                  : panel.mode === "setup" ? (panel.usePattern ? "Keeps your chats private on this computer. Connect at least 6 dots; next you set an unlock password too. Your chats are locked with it, so nobody can reset it: pick one you'll remember, and make a backup after setup (Settings › Devices and backup). " + panel.rules
                                                                : "Keeps your chats private on this computer. Your chats are locked with it, so nobody can reset it: pick one you'll remember, and make a backup after setup (Settings › Devices and backup). A few words make a good password. " + panel.rules)
                  : "Nobody can reset it."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
        Row {
            visible: !panel.asks && panel.mode !== "spare" && panel.pendingPattern === ""
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
            // 1.42: each step in plain words: "Wait 1 hour. Next: 12 hours after 5 more wrong tries. Try again in 59
            // minutes.", and in the last stage "Last 3 tries. If all 3 are wrong, everything is erased for good."
            text: panel.sampleFails >= 0 ? Vault.triesWordsFor(panel.sampleFails, panel.sampleErase)
                : panel.wait > 0 ? (panel.wait, Vault.triesWords())
                : panel.sealAsked && Vault.sealing ? Vault.sealProgress
                : panel.note !== "" ? panel.note
                : panel.asks && Vault.wrongTries > 0 ? Vault.triesWords()
                : ""
            color: panel.bad || panel.wait > 0 || panel.sampleFails >= 0 || (panel.asks && Vault.wrongTries > 0) ? Theme.danger : Theme.text
            font.family: Theme.body; font.pixelSize: Theme.fs(14)
            Accessible.role: Accessible.AlertMessage
            Accessible.name: text
        }
        // A pattern lock: its unlock password opens Huginn too (after 5 wrong patterns it's the way back in).
        Text {
            id: spareLink
            textFormat: Text.PlainText
            visible: panel.canSpare
            anchors.horizontalCenter: parent.horizontalCenter
            text: panel.spareTyped ? "Draw your pattern instead" : "Use your unlock password"
            color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.underline: true
            font.weight: !panel.spareTyped && Vault.wrongTries >= 5 ? Font.Bold : Font.Normal
            Accessible.role: Accessible.Button
            Accessible.name: text
            Accessible.onPressAction: panel.toggleSpare()
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: panel.toggleSpare() }
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
                enabled: panel.wait === 0 && !panel.busy
                onDone: d => panel.onPattern(d)
            }
            Column {
                id: pwCol
                visible: !panel.usePattern
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(parent.width, Theme.px(320))
                spacing: Theme.px(8)
                PwField {
                    id: pw
                    placeholder: panel.asks ? (Vault.usesPattern ? "Unlock password" : "Password") : panel.spareRecall ? "Unlock password"
                        : panel.pendingPattern !== "" || panel.mode === "spare" ? "Unlock password (6 or more)" : "New password (6 or more)"
                    onEnter: panel.asks || panel.spareRecall ? panel.submitPassword() : pw2.forceActiveFocus()
                }
                PwField { id: pw2; visible: !panel.asks && !panel.spareRecall; placeholder: "Type it again"; onEnter: panel.submitPassword() }
                HButton { anchors.horizontalCenter: parent.horizontalCenter; kind: "primary"; text: panel.goText; enabled: panel.wait === 0 && !panel.busy; onClicked: panel.submitPassword() }
                // A new pattern waiting for its unlock password: draw another instead.
                HButton {
                    visible: panel.pendingPattern !== ""
                    anchors.horizontalCenter: parent.horizontalCenter
                    kind: "plain"; text: "Draw a different pattern"
                    onClicked: panel.redraw()
                }
                // From memory went wrong: set a new one (Huginn is open, so nothing is lost).
                HButton {
                    visible: panel.spareRecall && panel.bad
                    anchors.horizontalCenter: parent.horizontalCenter
                    kind: "plain"; text: "Set a new unlock password"
                    onClicked: panel.newSpare()
                }
            }
            // The progress ring while Huginn unlocks: an arc drawn once, turned (still when motion is off).
            Item {
                id: ring
                visible: (Vault.unlocking && panel.unlockAsked) || (Vault.sealing && panel.sealAsked)
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
                Accessible.name: panel.sealAsked ? "Sealing your chats" : "Unlocking"
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
            // Round 19 (m1 handheld): the controller's own button names (a PlayStation pad's cross is its A), and a way
            // back to the pattern once the unlock password is being typed.
            visible: Gamepad.connected && !!Ui.main && Ui.main.padInUse && text !== ""
            text: {
                const g = k => (Gamepad.style, Gamepad.glyph(k))
                if (panel.usePattern)
                    return "D-pad moves. " + g("a") + " starts and ends. " + g("b") + " clears." + (panel.canSpare ? " " + g("x") + ": unlock password." : "")
                        + (panel.canForget ? " " + g("y") + ": Forgot it?" : "")
                return panel.canSpare ? g("x") + ": draw your pattern instead." : ""
            }
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
        Text {
            id: forgotLink
            textFormat: Text.PlainText
            visible: panel.canForget
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Forgot it?"
            color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.underline: true
            Accessible.role: Accessible.Button
            Accessible.name: "Forgot it? Restore from a backup"
            Accessible.onPressAction: panel.forgot()
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: panel.forgot() }
        }
        HButton {
            visible: (panel.cancelable || panel.mode === "change" || panel.mode === "off" || panel.mode === "own") && !panel.sealAsked
            anchors.horizontalCenter: parent.horizontalCenter
            kind: "plain"; text: panel.cancelText
            onClicked: panel.cancelled()
        }
        // Settings › Unlock password › Change: Cancel keeps the one there is. Round 19: the required screen has Lock,
        // a new one after a wrong one from memory has Back (leaveSpare).
        HButton {
            visible: panel.mode === "spare" && Vault.spareStep !== ""
            anchors.horizontalCenter: parent.horizontalCenter
            kind: "plain"; text: panel.spareLeaveText
            onClicked: panel.leaveSpare()
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

    // Round 17: where Steam's keyboard would cover the fields (and Unlock or Save), the password is typed in the prompt
    // at the top instead, which sits above it (as a message is). Setting one asks twice, then saves.
    function typeIn(second) {
        if (!System.keyboardCovers) { const f = second ? pw2 : pw; f.forceActiveFocus(); System.showSteamKeyboard(); return }
        const go = goText
        if (asks || spareRecall) Ui.ask(asks && !Vault.usesPattern ? "Password" : "Unlock password", "", "", go, t => { pw.text = t; panel.submitPassword() }, true, true)
        else if (!second) Ui.ask(pendingPattern !== "" || mode === "spare" ? "Unlock password (6 or more)" : "New password (6 or more)", "", "", "Next", t => { pw.text = t; panel.typeIn(true) }, true, true)
        else Ui.ask("Type it again", "", "", go, t => { pw2.text = t; panel.submitPassword() }, true, true)
    }
    // What B does on the unlock password screen (leaveSpare), in words.
    readonly property string spareLeaveText: respare && Vault.spareStep === "recall" ? "Back"
        : Vault.spareStep === "set" && Vault.hasUnlockPassword ? "Cancel" : "Lock"
    // Round 19 (M5 handheld): the buttons that do something on the current step, for the hint bar (ChatPage's lock
    // screen and LockCheck use these, so no dead button is ever shown).
    readonly property var prompts: {
        const p = []
        const held = asks && (wait > 0 || busy)
        if (usePattern) { if (!held) p.push({ key: "dpad", label: "Move" }, { key: "a", label: drawing ? "Done" : "Draw" }) }
        else if (!held) p.push({ key: "a", label: pw.text.length && (asks || spareRecall || pw2.text.length) ? goText : "Type" })
        if (!asks && mode !== "spare" && pendingPattern === "" && !drawing) p.push({ key: "lb", label: usePattern ? "Password" : "Pattern" })
        if (canSpare) p.push({ key: "x", label: usePattern ? "Unlock password" : "Draw pattern" })
        if (canForget) p.push({ key: "y", label: "Forgot it?" })
        else if (spareRecall && bad) p.push({ key: "y", label: "Set a new one" })
        if (drawing) p.push({ key: "b", label: "Clear" })
        else if (pendingPattern !== "") p.push({ key: "b", label: "Draw again" })
        else if (mode === "spare") p.push({ key: "b", label: Vault.spareStep !== "" ? spareLeaveText : "Cancel" })
        else if (cancelable || mode === "change" || mode === "off" || mode === "own") p.push({ key: "b", label: cancelText })
        else if (mode === "setup") p.push({ key: "b", label: "Back" })
        return p
    }
    function handleButton(b) {
        // Round 19 (m3 handheld): during a wait or while it works, A does nothing (the Unlock button is off too).
        if (b === "a" && asks && (wait > 0 || busy)) return true
        if (usePattern && pad.handleButton(b)) return true
        // Setting one: A in the first field goes on to the second.
        if (b === "a" && !usePattern && !asks && !spareRecall && pw.text.length && pw2.text.length === 0) { typeIn(true); return true }
        if (b === "a" && !usePattern) { if (pw.text.length) submitPassword(); else typeIn(false); return true }
        if (b === "x" && canSpare) { toggleSpare(); return true }
        if ((b === "lb" || b === "next" || b === "prev") && !asks && mode !== "spare" && pendingPattern === "") { usePattern = !usePattern; first = null; note = ""; return true }
        if (b === "y" && canForget) { forgot(); return true }
        if (b === "y" && spareRecall && bad) { newSpare(); return true }
        // A new lock after "Forgot it?" can't be skipped.
        // The unlock password after a new pattern: B goes back to drawing. "spare": B leaves (leaveSpare).
        if (b === "b" && pendingPattern !== "") { redraw(); return true }
        if (b === "b" && mode === "spare") { leaveSpare(); return true }
        if (b === "b") { if (mode !== "renew") cancelled(); return true }
        return false
    }
}

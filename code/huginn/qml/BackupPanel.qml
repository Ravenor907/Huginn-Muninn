import QtQuick
import Huginn

// Settings › Backup and first setup's Restore from a backup, one step at a time (shared/docs/backup.md).
// Back up: what's inside (files and pictures on or off, with their size), the password twice with how strong it looks,
// then Qt's Save picker (Documents, "Huginn backup 2026-11-02.hmbak"), then where it went, that it's encrypted, its
// size and Show in folder. Restore: Choose file (any file, any name), its password (a wrong one says so, try again),
// then what's in it and Restore (replaces what's here), held to confirm; a copy of what's here is kept first.
// sample: the screenshot tour and first setup's practice (nothing is read, written or opened).
// The D-pad moves between the buttons, A presses one, B goes back.
Item {
    id: panel
    visible: false
    anchors.fill: parent

    property string mode: ""          // "backup" or "restore"
    property string step: ""          // backup: what, password, saving, saved. restore: pick, password, opening, summary, restoring, done
    property bool sample: false
    property bool fromSetup: false
    // The lock screen's "Forgot it?": what's here can't be opened, so it's erased, not kept (Vault.restoreErasing).
    property bool erases: false
    property bool withFiles: true
    property var info: ({})
    property string file: ""
    property bool wrong: false
    property string error: ""
    property var opened: ({})
    property var saved: ({})
    property string doneText: ""
    property int focusIndex: 0
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    readonly property string sep: Qt.platform.os === "windows" ? "\\" : "/"

    // How strong a backup password looks: 0 too short (can't be used), 1 weak, 2 fair, 3 good, 4 strong.
    // The same rules as Muninn's (BackupFormat.strength).
    function strength(pw) {
        if (pw.length < 12) return 0
        if (new Set(pw.split("")).size < 5) return 1
        let kinds = 0
        if (/[a-z]/.test(pw)) kinds++
        if (/[A-Z]/.test(pw)) kinds++
        if (/[0-9]/.test(pw)) kinds++
        if (/[^a-zA-Z0-9]/.test(pw)) kinds++
        let s = 2
        if (pw.length >= 16) s++
        if (pw.length >= 20 || kinds >= 3) s++
        return Math.min(s, 4)
    }
    function strengthText(pw) {
        return ["Too short: 12 or more characters.", "Weak: add more words or different characters.", "Fair. Longer is stronger.", "Good.", "Strong."][strength(pw)]
    }
    function count(n, word) { return n + " " + (n === 1 ? word : word + "s") }
    function made(o) {
        const d = new Date(o.created || "")
        const when = isNaN(d.getTime()) ? "on an unknown date" : Qt.formatDateTime(d, "d MMM yyyy, h:mm AP")
        return "Made " + when + (o.device ? " on " + o.device : "") + " in " + (o.app === "muninn" ? "Muninn" : "Huginn")
    }

    // The buttons on show, in the order the controller steps through them.
    readonly property var order: {
        if (mode === "backup") {
            if (step === "what") return ["files", "next", "cancel"]
            if (step === "password") return ["pw1", "pw2", "save", "back"]
            if (step === "saved") return ["show", "done"]
            return []
        }
        if (step === "pick") return ["choose", "cancel"]
        if (step === "password") return ["pw", "open", "other", "cancel"]
        if (step === "summary") return ["restore", "cancel"]
        return []
    }
    readonly property string focusId: order.length ? order[Math.max(0, Math.min(focusIndex, order.length - 1))] : ""
    function lit(id) { return pad && focusId === id }
    readonly property var prompts: [{ key: "dpad", label: "Choose" }, { key: "a", label: "Press" }, { key: "b", label: "Back" }]

    // m: "backup" or "restore". setupFlag: from first setup. isSample: nothing real happens. shotStep: the tour's step.
    // erasing: the lock screen's "Forgot it?" (restore only).
    function open(m, setupFlag, isSample, shotStep, erasing) {
        mode = m
        fromSetup = !!setupFlag
        erases = m === "restore" && !!erasing
        sample = !!isSample
        withFiles = true
        file = ""; wrong = false; error = ""; opened = ({}); saved = ({}); doneText = ""
        pw1.text = ""; pw2.text = ""; pwOpen.text = ""
        info = sample ? { name: "Huginn backup 2026-11-02.hmbak", folder: Vault.backupFolder, all: 48500000, media: 41200000 } : Vault.backupInfo()
        step = shotStep || (m === "backup" ? "what" : "pick")
        if (sample && m === "backup" && step === "password") { pw1.text = "correct-horse-42"; pw2.text = pw1.text }
        if (sample && step === "saved")
            saved = { path: info.folder + sep + info.name, folder: info.folder, size: 7340032, files: true }
        if (sample && m === "restore" && step !== "pick") file = info.folder + sep + "Muninn backup 2026-11-01.hmbak"
        if (sample && m === "restore" && step === "password") wrong = true
        if (sample && step === "summary")
            opened = { app: "muninn", device: "Pixel 8", created: "2026-11-01T18:20:00Z", v: 3, files: "yes", contacts: 12, chats: 9, groups: 3, folders: 2, items: 240 }
        focusIndex = 0
        visible = true
    }
    function close() {
        if (step === "saving" || step === "opening" || step === "restoring" || step === "done") return
        if (!sample && mode === "restore") Vault.forgetOpened()
        visible = false
        if (Ui.main) Ui.main.restoreFocus()
    }
    function back() {
        if (mode === "backup" && step === "password") { step = "what"; focusIndex = 0 }
        else if (mode === "restore" && step === "summary" && !sample) { Vault.forgetOpened(); step = "password"; focusIndex = 0 }
        else close()
    }

    // ---- back up
    function save() {
        if (strength(pw1.text) === 0) { error = "At least 12 characters."; return }
        if (pw1.text !== pw2.text) { error = "The two don't match."; return }
        if (sample) { Ui.say("Practice: this asks where to save."); return }
        const path = Vault.pickBackupSave(info.name || "")
        if (path === "") return
        error = ""
        step = "saving"
        Vault.backupTo(path, pw1.text, withFiles)
    }
    function showFolder() {
        if (sample) { Ui.say("Practice: this opens the folder."); return }
        if (!FileLink.showInFolder(saved.path)) Ui.say("Couldn't open the folder.", true)
    }

    // ---- restore
    function chooseFile() {
        if (sample) { Ui.say("Practice: this opens the file picker."); return }
        const f = Vault.pickBackupOpen()
        if (f === "") return
        file = f; wrong = false; error = ""; pwOpen.text = ""
        step = "password"; focusIndex = 0
    }
    function openIt() {
        if (pwOpen.text === "") { pwOpen.forceActiveFocus(); return }
        if (sample) { Ui.say("Practice: this checks the password."); return }
        wrong = false; error = ""
        step = "opening"
        Vault.openBackup(file, pwOpen.text)
    }
    // A backup made on a phone (or another computer) makes this computer the same person as that device. Said first,
    // with its own confirm, and Link offered instead: both in use at once breaks messages on one or both.
    function restoreNow(copyOk) {
        const where = panel.opened.device || (panel.opened.app === "muninn" ? "your phone" : "that device")
        const copy = panel.opened.app === "muninn" || (!!panel.opened.device && panel.opened.device !== Settings.deviceName)
        // Already linked to a phone: restoring here doesn't stop the link, it breaks both (R12).
        const linked = !panel.erases && !!Linked.phone.key && !Linked.phone.gone
        if (linked && !copyOk) {
            Ui.sure("This PC is linked to " + (Linked.phone.name || "your phone"),
                    "This PC is already linked to " + (Linked.phone.name || "your phone") + ". Restoring a backup here breaks both: unlink first (Settings › Devices and backup › Linked devices), then restore.",
                    "Restore anyway", () => panel.restoreNow(true), false, true)
            return
        }
        if (copy && !copyOk) {
            // A phone's backup: linking is the way to use both. Another computer's (a new PC replacing an old one):
            // no phone in it, so only the warning.
            const fromPhone = panel.opened.app === "muninn"
            Ui.sure("This makes a copy of you",
                    "This computer becomes the same person as " + where + ". If both are used, messages stop working on one or both, and friends see errors. "
                    + (fromPhone ? "To use both, link this PC to your phone instead. Restore only if " + where + " won't chat again."
                                 : "Restore only if " + where + " won't chat again (a new PC replacing an old one)."),
                    "Restore anyway", () => panel.restoreNow(true), false, true)
            if (fromPhone) {
                Ui.confirm.otherText = "Link instead"
                Ui.confirm.otherCallback = () => { panel.close(); if (Ui.main) Ui.main.showLinkPhone(panel.fromSetup, panel.sample) }
            }
            return
        }
        if (panel.erases) {
            Ui.sure("Erase what's here and restore?", "Huginn's chats, contacts and keys on this computer are erased for good, and the backup goes in. "
                    + "Messages since the backup are lost. Huginn then opens without a lock. You'll set a new one next.",
                    "Hold to erase and restore", () => {
                if (panel.sample) { Ui.say("Practice: nothing was restored."); return }
                panel.error = ""
                panel.step = "restoring"
                Vault.restoreErasing()
            }, true, true)
            return
        }
        Ui.sure("Replace what's here?", "Everything in Huginn is replaced by the backup. A copy of what's here now is kept first.",
                "Hold to restore", () => {
            if (panel.sample) { Ui.say("Practice: nothing was restored."); return }
            if (panel.fromSetup) Settings.setupState = "done"
            panel.error = ""
            panel.step = "restoring"
            Vault.restoreOpened()
        }, true, true)
    }

    Connections {
        target: Vault
        function onDone(what, ok, message) {
            if (!panel.visible || panel.sample) return
            if (what === "backup" && panel.mode === "backup") {
                if (ok) { panel.saved = Vault.lastBackup(); pw1.text = ""; pw2.text = ""; panel.step = "saved" }
                else { panel.error = message; panel.step = "password" }
                panel.focusIndex = 0
            } else if (what === "open" && panel.mode === "restore") {
                pwOpen.text = ""
                if (ok) { panel.opened = Vault.openedInfo(); panel.step = "summary" }
                else { panel.wrong = Vault.openedInfo().error === "password"; panel.error = panel.wrong ? "" : message; panel.step = "password" }
                panel.focusIndex = 0
            } else if (what === "restore" && panel.mode === "restore") {
                if (ok) { panel.doneText = message; panel.step = "done" }
                else { if (panel.fromSetup) Settings.setupState = "started"; panel.error = message; panel.step = "pick"; panel.focusIndex = 0 }
            }
        }
    }

    function run(id) {
        switch (id) {
        case "files": withFiles = !withFiles; break
        case "next": error = ""; step = "password"; focusIndex = 0; if (!pad) pw1.forceActiveFocus(); break
        case "cancel": close(); break
        case "pw1": pw1.forceActiveFocus(); System.showSteamKeyboard(); break
        case "pw2": pw2.forceActiveFocus(); System.showSteamKeyboard(); break
        case "save": save(); break
        case "back": back(); break
        case "show": showFolder(); break
        case "done": close(); break
        case "choose": chooseFile(); break
        case "pw": pwOpen.forceActiveFocus(); System.showSteamKeyboard(); break
        case "open": openIt(); break
        case "other": chooseFile(); break
        case "restore": restoreNow(); break
        }
    }
    function handleButton(b) {
        if (b === "up" || b === "left") focusIndex = Math.max(0, focusIndex - 1)
        else if (b === "down" || b === "right") focusIndex = Math.min(Math.max(0, order.length - 1), focusIndex + 1)
        else if (b === "a") run(focusId)
        else if (b === "b") back()
        return true
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(600))
        height: Math.min(parent.height - Theme.px(48), col.implicitHeight + Theme.px(48))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        Accessible.role: Accessible.Dialog
        Accessible.name: panel.mode === "backup" ? "Back up" : "Restore from a backup"
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

        Flickable {
            id: view
            x: Theme.px(24); y: Theme.px(24)
            width: parent.width - Theme.px(48)
            height: parent.height - Theme.px(48)
            contentWidth: width
            contentHeight: col.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds

            Column {
                id: col
                width: view.width
                spacing: Theme.px(10)

                Title {
                    text: panel.mode === "backup" ? (panel.step === "saved" ? "BACKUP SAVED" : panel.step === "what" ? "BACK UP" : "BACKUP PASSWORD")
                        : panel.step === "summary" ? "WHAT'S IN IT" : panel.step === "pick" ? "RESTORE FROM A BACKUP"
                        : panel.step === "done" || panel.step === "restoring" ? "RESTORE" : "BACKUP PASSWORD"
                }

                // ---- back up: what's inside
                Column {
                    visible: panel.mode === "backup" && panel.step === "what"
                    width: parent.width
                    spacing: Theme.px(10)
                    Line { text: "What's inside: your identity, contacts, chats, groups, folders and settings." }
                    // Linked to a phone: this saves the PC's own account, not the phone's (R2).
                    Line {
                        visible: !!Linked.phone.key && !Linked.phone.gone
                        color: Theme.danger
                        text: "This PC's own account, not your phone's. To keep your account safe, back up on your phone: in Muninn, Settings › Devices and backup › Backup."
                    }
                    Rectangle {
                        width: parent.width
                        height: filesRow.implicitHeight + Theme.px(20)
                        radius: Theme.radius
                        color: panel.lit("files") ? Theme.focusFill : Theme.raised
                        border.width: panel.lit("files") ? Theme.focusWidth : 1
                        border.color: panel.lit("files") ? Theme.accent : Theme.line
                        Accessible.role: Accessible.CheckBox
                        Accessible.name: "Files and pictures"
                        Accessible.checked: panel.withFiles
                        Accessible.onPressAction: panel.run("files")
                        Row {
                            id: filesRow
                            x: Theme.px(12); y: Theme.px(10)
                            width: parent.width - Theme.px(24)
                            spacing: Theme.px(12)
                            Rectangle {
                                anchors.verticalCenter: parent.verticalCenter
                                width: Theme.px(22); height: width
                                color: panel.withFiles ? Theme.accentFill : Theme.groundDeep
                                border.width: 1; border.color: panel.withFiles ? Theme.accent : Theme.lineMuted
                                Icon { visible: panel.withFiles; anchors.centerIn: parent; name: "check"; size: Theme.px(18); lineWidth: 2.2; color: Theme.accent }
                            }
                            Column {
                                width: parent.width - Theme.px(34)
                                Text {
                                    textFormat: Text.PlainText
                                    text: "Files and pictures"
                                    color: Theme.text
                                    font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                                }
                                Text {
                                    textFormat: Text.PlainText
                                    width: parent.width
                                    wrapMode: Text.Wrap
                                    text: "Photos, voice and video messages: " + System.sizeText(panel.info.media || 0) + ". Longer videos stay on this computer; their pictures go."
                                    color: Theme.textMuted
                                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                                }
                            }
                        }
                        TapHandler { onTapped: panel.run("files") }
                    }
                    Line { text: "Encrypted with a password you choose. Only that password opens it."; color: Theme.textMuted }
                }

                // ---- back up: the password twice
                Column {
                    visible: panel.mode === "backup" && (panel.step === "password" || panel.step === "saving")
                    width: parent.width
                    spacing: Theme.px(8)
                    PwField { id: pw1; focusId: "pw1"; placeholder: "Password (12 or more characters)"; onEnter: pw2.forceActiveFocus() }
                    Line {
                        visible: pw1.text.length > 0
                        text: panel.strengthText(pw1.text)
                        color: panel.strength(pw1.text) < 2 ? Theme.danger : Theme.textMuted
                    }
                    PwField { id: pw2; focusId: "pw2"; placeholder: "Type it again"; onEnter: panel.run("save") }
                    Line { visible: pw2.text.length > 0 && pw1.text !== pw2.text; text: "They don't match yet."; color: Theme.danger }
                    Line { text: "No one can reset it. Write it down. Lose it, lose the backup."; color: Theme.textMuted }
                    Line { text: "Next you pick where to save. Documents is a good place."; color: Theme.textMuted }
                    Line { visible: panel.step === "saving"; text: "Saving..." }
                }

                // ---- back up: where it went
                Column {
                    visible: panel.mode === "backup" && panel.step === "saved"
                    width: parent.width
                    spacing: Theme.px(6)
                    Line { text: "Saved to " + (panel.saved.path || "") }
                    Line { text: "Encrypted with your password" }
                    Line { text: "Size: " + System.sizeText(panel.saved.size || 0) }
                    Line { text: panel.saved.files ? "Files and pictures are in it." : "Files and pictures are not in it."; color: Theme.textMuted }
                }

                // ---- restore: choose the file
                Column {
                    visible: panel.mode === "restore" && panel.step === "pick"
                    width: parent.width
                    spacing: Theme.px(8)
                    Line { text: "Choose the backup file. Any name works, from Huginn or Muninn." }
                    Line { text: "Look in Documents or Downloads, or wherever you saved it."; color: Theme.textMuted }
                }

                // ---- restore: its password
                Column {
                    visible: panel.mode === "restore" && (panel.step === "password" || panel.step === "opening")
                    width: parent.width
                    spacing: Theme.px(8)
                    Line { text: "File: " + panel.file; color: Theme.textMuted }
                    PwField { id: pwOpen; focusId: "pw"; placeholder: "Password"; onEnter: panel.run("open") }
                    Line { visible: panel.wrong; text: "Wrong password. Try again."; color: Theme.danger; font.weight: Font.DemiBold }
                    Line { visible: panel.step === "opening"; text: "Checking the password..." }
                }

                // ---- restore: what's in it
                Column {
                    visible: panel.mode === "restore" && panel.step === "summary"
                    width: parent.width
                    spacing: Theme.px(6)
                    Line { text: panel.made(panel.opened) }
                    Line { text: (panel.opened.v || 0) < 3 ? "An older backup. Encrypted with its password." : "Encrypted with its password."; color: Theme.textMuted }
                    Line {
                        text: (panel.opened.contacts === undefined || panel.opened.contacts < 0)
                              ? "Your account (who you are to your contacts), contacts, chats, groups, folders and settings: " + panel.count(panel.opened.items || 0, "item") + "."
                              : "Your account, " + panel.count(panel.opened.contacts, "contact") + ", " + panel.count(panel.opened.chats, "chat") + ", "
                                + panel.count(panel.opened.groups, "group") + ", " + panel.count(panel.opened.folders, "folder") + ", settings."
                    }
                    Line {
                        visible: text !== ""
                        text: panel.opened.app === "muninn" ? "Made in Muninn: messages come across, pictures and files stay behind."
                            : panel.opened.files === "no" ? "Files and pictures: not in it." : panel.opened.files === "yes" ? "Files and pictures: in it." : ""
                        color: Theme.textMuted
                    }
                    Line { text: panel.erases ? "What's on this computer now is erased first." : "A copy of what's here now is kept first."; color: Theme.textMuted }
                }

                // ---- restore: under way, then done (Huginn restarts)
                Line { visible: panel.mode === "restore" && panel.step === "restoring"; text: "Restoring. Huginn restarts when it's done." }
                Line { visible: panel.mode === "restore" && panel.step === "done"; text: panel.doneText }

                Line { visible: panel.error !== ""; text: panel.error; color: Theme.danger }

                // The step's buttons.
                Flow {
                    width: parent.width
                    spacing: Theme.px(8)
                    topPadding: Theme.px(6)
                    Repeater {
                        model: panel.order.filter(id => ["files", "pw1", "pw2", "pw"].indexOf(id) < 0)
                        HButton {
                            required property string modelData
                            text: ({ next: "Next", cancel: "Cancel", save: "Choose where to save", back: "Back", show: "Show in folder", done: "Done",
                                     choose: "Choose file", open: "Open", other: "Choose another file", restore: panel.erases ? "Erase and restore" : "Restore (replaces what's here)" })[modelData] || ""
                            kind: modelData === "restore" ? "danger"
                                : ["next", "save", "choose", "open", "done"].indexOf(modelData) >= 0 ? "primary" : "normal"
                            enabled: !Vault.busy && (modelData !== "save" || (panel.strength(pw1.text) > 0 && pw1.text === pw2.text))
                                     && (modelData !== "open" || pwOpen.text !== "")
                            focused: panel.lit(modelData)
                            onClicked: panel.run(modelData)
                        }
                    }
                }
            }
        }
    }

    component Title: Text {
        textFormat: Text.PlainText
        width: parent ? parent.width : 0
        wrapMode: Text.Wrap
        color: Theme.text
        font.family: Theme.display; font.pixelSize: Theme.fs(20); font.weight: Font.Bold; font.letterSpacing: Theme.px(2)
    }
    component Line: Text {
        textFormat: Text.PlainText
        width: parent ? parent.width : 0
        wrapMode: Text.Wrap
        color: Theme.text
        font.family: Theme.body; font.pixelSize: Theme.fs(15)
    }
    // A password box: Enter does the step's job, Esc leaves it.
    component PwField: Rectangle {
        id: field
        property alias text: input.text
        property string placeholder
        property string focusId
        signal enter()
        function forceActiveFocus() { input.forceActiveFocus() }
        width: parent ? parent.width : 0
        height: Math.max(Theme.px(40), input.implicitHeight + Theme.px(16))
        radius: Theme.radius
        color: Theme.groundDeep
        border.width: panel.lit(field.focusId) || input.activeFocus ? Theme.focusWidth : 1
        border.color: input.activeFocus ? Theme.accent : panel.lit(field.focusId) ? Theme.text : Theme.line
        Accessible.role: Accessible.EditableText
        Accessible.name: field.placeholder
        TextInput {
            id: input
            anchors.fill: parent; anchors.leftMargin: Theme.px(10); anchors.rightMargin: Theme.px(10)
            verticalAlignment: TextInput.AlignVCenter
            echoMode: TextInput.Password
            color: Theme.text
            selectionColor: Theme.accentFill
            font.family: Theme.body; font.pixelSize: Theme.fs(15)
            maximumLength: 400
            clip: true
            Keys.onReturnPressed: field.enter()
            Keys.onEnterPressed: field.enter()
            Keys.onEscapePressed: { if (Ui.main) Ui.main.restoreFocus() }
        }
        Text {
            textFormat: Text.PlainText
            visible: input.text.length === 0
            anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            text: field.placeholder
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        TapHandler { onTapped: input.forceActiveFocus() }
    }
}

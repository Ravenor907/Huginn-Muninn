import QtQuick
import Huginn

// Round 17 (B1): Back up, Restore, Move here, Move from this device and showing this PC's chats on another device ask
// for the current lock first, so someone holding an unlocked Huginn can't take the account. The lock is checked by
// Vault.confirmLock (a wrong one counts toward the waits; inside the open app it never erases), which grants that one thing once; the C++
// that does it takes the grant, so nothing can skip this. Without a lock there is nothing to ask: it goes straight on.
// Over the pages and panels, under questions, calls and toasts. B or Cancel closes it.
Item {
    id: lockCheckRoot
    visible: false
    anchors.fill: parent
    property var after: null

    // title: what it is for ("Back up"). why: a line under it. what: the grant ("backup", "restore", "movehere",
    // "moveaway", "link"). callback: runs once the lock was given (or at once when no lock is set).
    function open(title, why, what, callback) {
        if (!Vault.hasLock || Vault.lockConfirmed(what)) { callback(); return }
        if (Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
        after = callback
        lockAsk.mode = "confirm"
        lockAsk.sampleFails = -1
        lockAsk.grantFor = what
        lockAsk.lead = title + (why !== "" ? ". " + why : ".")
        lockAsk.note = ""
        lockAsk.bad = false
        visible = true
        Qt.callLater(lockAsk.focusField)
    }
    // The screenshot tour: shown as it looks, with nothing to do once the lock is given. fails and erase (1.42): the
    // words after that many wrong tries (shown only, nothing is counted).
    function sample(title, why, fails, erase) {
        after = null
        lockAsk.mode = "confirm"
        lockAsk.sampleFails = fails === undefined ? -1 : fails
        lockAsk.sampleErase = !!erase
        lockAsk.grantFor = ""
        lockAsk.lead = title + ". " + why + "."
        lockAsk.note = ""
        lockAsk.bad = false
        visible = true
    }
    // The screenshot tour (1.42): setting a pattern's unlock password, as it looks (nothing is saved: the tour has no pattern).
    function sampleSpare() {
        after = null
        lockAsk.mode = "spare"
        lockAsk.respare = true
        lockAsk.sampleFails = -1
        lockAsk.lead = ""
        lockAsk.note = ""
        lockAsk.bad = false
        visible = true
    }
    function close() {
        if (!visible) return
        visible = false
        after = null
        if (Ui.main) Ui.main.restoreFocus()
    }
    function handleButton(b) { return lockAsk.handleButton(b) }
    // Round 19 (m1 handheld): the buttons that do something right now (the pattern draws with A, X switches to the
    // unlock password), for Main.qml's hint bar instead of a fixed "A: Confirm, B: Cancel".
    readonly property var prompts: lockAsk.prompts

    Rectangle { anchors.fill: parent; color: Theme.groundDeep; opacity: 0.94 }
    // Nothing under it takes a click or the wheel while it shows.
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: wheel => wheel.accepted = true }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(540))
        height: Math.min(parent.height - Theme.px(32), Math.max(lockAsk.minHeight + Theme.px(24), Theme.px(520)))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.lineMuted
        LockPanel {
            id: lockAsk
            anchors.fill: parent
            anchors.margins: Theme.px(12)
            mode: "confirm"
            cancelable: true
            compact: true
            onFinished: how => {
                const go = lockCheckRoot.after
                lockCheckRoot.visible = false
                lockCheckRoot.after = null
                if (Ui.main) Ui.main.restoreFocus()
                if (how === "confirm" && go) go()
            }
            onCancelled: lockCheckRoot.close()
        }
    }
}

pragma Singleton
import QtQuick
import Huginn

// Shared handles to the window-wide overlays, set by Main.qml, so any page can use them.
QtObject {
    property var main: null
    property var prompt: null
    property var confirm: null
    property var toast: null
    property var picker: null
    property var profile: null

    // Panel widths while they're dragged (Settings keeps them once let go), and how much room the
    // voice panel takes at the foot of the list column (0 when it isn't there).
    property int listW: Settings.listWidth
    property int memberW: Settings.memberWidth
    property int dock: 0
    // The window: a portrait one gets the one-column layout (set by Main.qml).
    property bool portrait: false
    property int windowWidth: 0
    function resetPanels() {
        Settings.listWidth = 240; Settings.memberWidth = 240
        listW = 240; memberW = 240
        Settings.showList = true; Settings.showMembers = true
    }

    function openPage(name, args) { main.openPage(name, args || {}) }
    // Back out of a page: to the rail, or from Contacts back to Settings.
    function closePage() { main.closePage() }
    // back: the menu a sub-page came from (B and the arrow go back to it).
    function pickFrom(title, sections, cb, back) { picker.openList(title, sections, cb, back) }
    function say(text, bad) { if (toast) toast.show(text, bad) }
    function ask(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) { prompt.open(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) }
    // The same with a row of choices under the field: cb(text, choice).
    function askWith(title, ctx, initial, confirmText, choices, choice, choicesLabel, cb) { prompt.openWith(title, ctx, initial, confirmText, cb, false, true, choices, choice, choicesLabel) }
    // Someone's profile card: avatar, nickname, note, folders, and Message, Call and Video.
    // extra: { group, admin, owner, member } when opened from a group's member list.
    function showProfile(key, name, extra) { if (profile && key) profile.show(key, name || "", extra || {}) }
    // The person panel (right click or hold on someone): role, what they can do, where their chat sits.
    property var person: null
    function showPerson(key, name, extra) { if (person && key) person.show(key, name || "", extra || {}) }
    function sure(title, message, confirmText, cb, hold, danger, onCancel, tag) { confirm.open(title, message, confirmText, cb, hold, danger, onCancel, tag) }
    // A note with one Done button (a code that was just copied, and the like).
    function note(title, message) { confirm.open(title, message, "Done", null, false, false); confirm.single = true }
}

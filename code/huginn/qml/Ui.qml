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
    // The small right-click menu (RightClickMenu.qml).
    property var menu: null

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

    // You: while a phone is linked and checked here, its name and status are yours on this screen and a change goes to
    // the phone; otherwise this device's own.
    readonly property bool phoneIsMe: Linked.me.name !== undefined
    readonly property string myName: phoneIsMe ? Linked.me.name : Chat.username
    readonly property string myStatus: phoneIsMe ? (Linked.me.status || "online") : Profile.myStatus
    readonly property string myCustomStatus: phoneIsMe ? (Linked.me.custom || "") : Profile.myCustomStatus
    function setMyName(t) {
        if (!phoneIsMe) { Chat.username = t; return }
        const r = Linked.setMe(t, myStatus, myCustomStatus)
        if (r !== "") say(r, true)
    }
    function setMyStatus(s) {
        if (!phoneIsMe) { Profile.setStatus(s); return }
        const r = Linked.setMe(myName, s, myCustomStatus)
        if (r !== "") say(r, true)
    }
    function setMyCustomStatus(c) {
        if (!phoneIsMe) { Profile.setCustomStatus(c); return }
        const r = Linked.setMe(myName, myStatus, c)
        if (r !== "") say(r, true)
    }

    // Mute and Deafen, like Discord: they work any time. In a call or voice room they change it now; either way the
    // choice is kept (Settings), and the next call or room starts with it. Deafen mutes too; undeafening gives the mic
    // back only if Deafen was what muted it.
    readonly property bool micMuted: Rooms.inRoom ? Rooms.muted : Calls.inCall ? Calls.muted : (Settings.micMuted || Settings.deafened)
    readonly property bool deafened: Rooms.inRoom ? Rooms.deafened : Settings.deafened
    function keepVoiceState() {
        if (Rooms.inRoom) { Settings.deafened = Rooms.deafened; Settings.micMuted = Rooms.muted && !(Rooms.deafened && Settings.mutedByDeaf) }
        else if (Calls.inCall) Settings.micMuted = Calls.muted && !(Settings.deafened && Settings.mutedByDeaf)
        if (!Settings.deafened) Settings.mutedByDeaf = false
    }
    function toggleMic() {
        if (Rooms.inRoom) { Rooms.toggleMute(); keepVoiceState(); return }
        // Unmuting while deafened turns both off, like taking the headphones back off.
        if (Settings.deafened && micMuted) { Settings.deafened = false; Settings.mutedByDeaf = false; Settings.micMuted = false }
        else Settings.micMuted = !micMuted
        if (Calls.inCall && Calls.muted !== Settings.micMuted) Calls.toggleMute()
    }
    function toggleDeaf() {
        if (Rooms.inRoom) {
            const wasMuted = Rooms.muted
            Rooms.toggleDeafen()
            if (Rooms.deafened) Settings.mutedByDeaf = !wasMuted
            keepVoiceState()
            return
        }
        if (!Settings.deafened) {
            Settings.mutedByDeaf = !micMuted
            Settings.deafened = true
        } else {
            Settings.deafened = false
            if (Settings.mutedByDeaf) Settings.micMuted = false
            else Settings.micMuted = true
            Settings.mutedByDeaf = false
        }
        const want = Settings.micMuted || Settings.deafened
        if (Calls.inCall && Calls.muted !== want) Calls.toggleMute()
    }

    function openPage(name, args) { main.openPage(name, args || {}) }
    // Back out of a page: to the rail, or from Contacts back to Settings.
    function closePage() { main.closePage() }
    // back: the menu a sub-page came from (B and the arrow go back to it).
    // While a right-click menu is being built (menuAt), the list shows there instead, as a small menu.
    function pickFrom(title, sections, cb, back) {
        if (menu && menu.wants()) { menu.take(title, sections, cb, back); return }
        picker.openList(title, sections, cb, back)
    }
    // Right click, a long press, the Menu key or Shift+F10: what build() opens with pickFrom shows as a small menu at (x, y)
    // in item (null: the window), inside the window and flipped at its edges. A row may have sub: a function that opens
    // its side menu with pickFrom (the big list ignores sub and answers its url as usual).
    function menuAt(item, x, y, build) { if (menu) menu.openAt(item, x, y, build, false); else if (build) build() }
    // A text box's menu: Cut, Copy, Paste and Select all (FieldMenu.qml).
    function fieldMenu(field, x, y) { if (menu) menu.forField(field, x, y, false) }
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

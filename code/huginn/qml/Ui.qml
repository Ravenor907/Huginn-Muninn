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
    // back only if Deafen was what muted it. toggleMic() and toggleDeaf() are the one-press toggles for every input
    // (the voice card, Ctrl+Shift+M and D, GlobalKeys, a controller button): call them, not Rooms or Calls directly.
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
    // mark (Huginn's own file list): { toggle, total }, Y marks rows to send several (ListPicker.qml).
    function pickFrom(title, sections, cb, back, mark) {
        if (menu && menu.wants() && !mark) { menu.take(title, sections, cb, back); return }
        picker.openList(title, sections, cb, back, mark)
    }
    // Right click, a long press, the Menu key or Shift+F10: what build() opens with pickFrom shows as a small menu at (x, y)
    // in item (null: the window), inside the window and flipped at its edges. A row may have sub: a function that opens
    // its side menu with pickFrom (the big list ignores sub and answers its url as usual).
    function menuAt(item, x, y, build) { if (menu) menu.openAt(item, x, y, build, false); else if (build) build() }
    // A text box's menu: Cut, Copy, Paste and Select all (FieldMenu.qml).
    function fieldMenu(field, x, y) { if (menu) menu.forField(field, x, y, false) }
    function say(text, bad) { if (toast) toast.show(text, bad) }
    function ask(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) { prompt.open(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) }
    // Text that carries on what's there (a message draft, an edit): the cursor at the end and nothing selected, so the
    // first key typed never replaces it. limit: the most characters it may hold (0 none); over it, Done says so and
    // the prompt stays open with the text.
    function askAppend(title, ctx, initial, confirmText, cb, limit) {
        prompt.open(title, ctx, initial, confirmText, cb, false, true)
        prompt.cursorToEnd()
        prompt.limit = limit || 0
    }
    // A message: at most this many characters (Chat::kMax; counted as characters, not UTF-16 units).
    readonly property int messageMax: 2000
    function textLength(t) { return t.length <= messageMax ? t.length : Array.from(t).length }
    function tooLongText(n) { return "2,000 characters at most (this has " + String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "). Split it, or send it as a file." }
    function askMessage(title, ctx, initial, confirmText, cb) { askAppend(title, ctx, initial, confirmText, cb, messageMax) }
    // Several lines of text (Enter starts a new line, Ctrl+Enter saves): a group's about text and rules.
    function askLong(title, ctx, initial, confirmText, cb) { prompt.openLong(title, ctx, initial, confirmText, cb) }
    // The same with a row of choices under the field: cb(text, choice).
    function askWith(title, ctx, initial, confirmText, choices, choice, choicesLabel, cb) { prompt.openWith(title, ctx, initial, confirmText, cb, false, true, choices, choice, choicesLabel) }
    // Someone's profile card: avatar, nickname, note, folders, and Message, Call and Video.
    // extra: { group, admin, owner, member } when opened from a group's member list.
    // Never over the lock (Main.lockedOut): nobody's card or details show while Huginn is locked.
    function showProfile(key, name, extra) { if (profile && key && !(main && main.lockedOut)) profile.show(key, name || "", extra || {}) }
    // The person panel (right click or hold on someone): role, what they can do, where their chat sits.
    property var person: null
    // A drop's files. From a file manager outside the Flatpak sandbox, the document portal's key (the files themselves,
    // from any folder); otherwise the drop's own URLs.
    function dropUrls(drop) {
        const kinds = ["application/vnd.portal.filetransfer", "application/vnd.portal.files"]
        for (let i = 0; i < kinds.length; i++) {
            if (!drop.formats || drop.formats.indexOf(kinds[i]) < 0) continue
            const got = FileLink.portalUrls(drop.getDataAsString(kinds[i]))
            if (got.length) return got
        }
        return drop.hasUrls ? drop.urls : []
    }
    function showPerson(key, name, extra) { if (person && key && !(main && main.lockedOut)) person.show(key, name || "", extra || {}) }
    function sure(title, message, confirmText, cb, hold, danger, onCancel, tag) { confirm.open(title, message, confirmText, cb, hold, danger, onCancel, tag) }
    // Files to send, then cb(paths) (nothing when none were picked). In game mode (or Handheld mode with the controller)
    // the system's file window can't be driven with the controller and may not even show, so Huginn's own list opens:
    // the usual folders (and Steam screenshots), then folders and files (newest first), D-pad and A to pick, Y to mark
    // several, B back up a folder. kind: "pictures" or "videos" lists only those; one: a single file (no marking).
    function ownPicker() { return System.gameMode || (System.handheld && !!main && main.padInUse) }
    function pickFiles(cb, kind, one) {
        if (!ownPicker()) {
            const paths = System.pickFiles()
            if (paths.length) cb(paths)
            return
        }
        const places = System.filePlaces()
        if (!places.length) { say("No folders to pick from here.", true); return }
        pickPlace({ kind: kind || "", many: !one, marks: [] }, places, cb)
    }
    function pickPlace(st, places, cb) {
        const title = st.kind === "pictures" ? "Pick pictures" : st.kind === "videos" ? "Pick a video" : st.many ? "Pick files" : "Pick a file"
        pickFrom(title, [{ header: "", items: places.map(p => ({ name: p.name, icon: "folder", url: p.path })) }],
                 path => browseFiles(path, places.find(p => p.path === path).name, cb, () => pickPlace(st, places, cb), st))
    }
    function browseFiles(dir, title, cb, back, st) {
        const state = st || { kind: "", many: true, marks: [] }
        const only = state.kind === "pictures" ? /\.(png|jpe?g|webp|bmp|gif|heic)$/i : state.kind === "videos" ? /\.(mp4|m4v|mov)$/i : null
        const list = System.listFolder(dir)
        const folders = list.filter(e => e.dir).map(e => ({ name: e.name, icon: "folder", url: "d" + e.path }))
        const files = list.filter(e => !e.dir && (!only || only.test(e.name)))
                          .map(e => ({ name: e.name, icon: "attach", detail: FileLink.size(e.size), url: "f" + e.path,
                                       markable: state.many, check: state.marks.indexOf(e.path) >= 0 }))
        if (!folders.length && !files.length) {
            say((only ? (state.kind === "pictures" ? "No pictures in " : "No videos in ") : "Nothing in ") + title + ".", true)
            if (back) back()
            return
        }
        const mark = state.many ? { total: state.marks.length, toggle: (u, on) => {
            const p = u.substring(1), i = state.marks.indexOf(p)
            if (on && i < 0) state.marks.push(p)
            else if (!on && i >= 0) state.marks.splice(i, 1)
            return state.marks.length
        } } : null
        pickFrom(title, [{ header: folders.length && files.length ? "Folders" : "", items: folders },
                         { header: folders.length && files.length ? "Files, newest first" : "", items: files }], u => {
            const path = u.substring(1)
            if (u.charAt(0) === "d") browseFiles(path, path.substring(path.lastIndexOf("/") + 1), cb, () => browseFiles(dir, title, cb, back, state), state)
            else if (state.marks.length) cb(state.marks.indexOf(path) >= 0 ? state.marks.slice() : state.marks.concat([path]))
            else cb([path])
        }, back, mark)
    }
    // A note with one Done button (a code that was just copied, and the like).
    function note(title, message) { confirm.open(title, message, "Done", null, false, false); confirm.single = true }
}

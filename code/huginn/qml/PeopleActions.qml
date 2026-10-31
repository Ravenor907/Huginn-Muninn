pragma Singleton
import QtQuick
import Huginn

// A contact's one menu (the three dots) and its sub-pages, adding people you trust (cap i1), files
// over the internet (cap f1) and linked devices, the same as Muninn's. Shared by the chat, group,
// contacts and settings pages so each only calls in here.
QtObject {
    // ------------------------------------------------------------- a contact's one menu
    // The same everywhere: a chat row, a chat's header, Contacts and a profile card, with the mouse
    // (the three dots or a right click) or a controller (LT or X). ctx: { section, keys (that
    // section's order as shown, for Move up and down), removed (called before Remove), tour }.
    function has(fn) { return typeof Chat[fn] === "function" }
    function contactMenu(k, n, ctx) {
        const c = ctx || {}
        if (!k) return
        const name = c.tour ? n : (Chat.nameOf(k) || n)
        const contact = !!c.tour || Chat.isContact(k)
        const blocked = !c.tour && Chat.isBlocked(k)
        const items = [{ name: "Message", icon: "thread", url: "message" }]
        if (contact) items.push({ name: "Call", icon: "call", url: "call" })
        if (contact && (c.tour || Calls.canVideo(k))) items.push({ name: "Video", icon: "video", url: "video" })
        items.push({ name: "Edit", icon: "edit", url: "edit" })
        if (contact) items.push({ name: "Folders and role", icon: "folder", url: "folders" }, { name: "What they can do", icon: "shield", url: "can" })
        items.push({ name: "Chat", icon: "pin", url: "chat" })
        if (contact) items.push({ name: "Check safety code", icon: "check", url: "check" }, { name: "Introduce", icon: "people", url: "introduce" })
        items.push({ name: blocked ? "Unblock" : "Block", url: "block", danger: !blocked })
        if (contact) items.push({ name: "Remove", url: "remove", danger: true })
        const back = () => contactMenu(k, n, c)
        Ui.pickFrom(name, [{ header: "", items: items }], a => {
            if (c.tour) return
            if (a === "message") Ui.openPage("dms", { open: k })
            else if (a === "call") { const w = Calls.call(k); if (w !== "") Ui.say(w, true) }
            else if (a === "video") { const w = Calls.callVideo(k); if (w !== "") Ui.say(w, true) }
            else if (a === "edit") Ui.showPerson(k, name, { page: "edit", back: back })
            else if (a === "can") Ui.showPerson(k, name, { page: "can", back: back })
            else if (a === "folders") folderMenu(k, back)
            else if (a === "chat") chatMenu(k, c, back)
            else if (a === "check") checkCode(k)
            else if (a === "introduce") introduce(k)
            else if (a === "block") {
                if (blocked) { Chat.block(k, false); Ui.say(name + " unblocked.") }
                else Ui.sure("Block " + name + "?", "They aren't told.", "Block", () => { Chat.block(k, true); Ui.say(name + " blocked.") }, false, true)
            } else if (a === "remove")
                Ui.sure("Remove " + name + "?", "Restore within 30 days.", "Remove", () => { if (c.removed) c.removed(); Chat.removeContact(k); Ui.say(name + " is in the Trash.") }, false, true)
        })
    }
    // Folders and role: Family, Work and Folk are the roles (one at a time); the rest are folders.
    function folderMenu(k, back) {
        const roles = ["Family", "Work", "Folk"]
        const named = r => Chat.folders.find(f => String(f.name).toLowerCase() === r.toLowerCase())
        const mine = Chat.foldersOf(k)
        const items = roles.map(r => { const f = named(r); return { name: r, url: "role:" + r, check: !!f && mine.indexOf(f.id) >= 0 } })
        Chat.folders.filter(f => roles.every(r => r.toLowerCase() !== String(f.name).toLowerCase()))
            .forEach(f => items.push({ name: f.name, icon: "folder", url: f.id, check: mine.indexOf(f.id) >= 0 }))
        items.push({ name: "New folder", icon: "plus", url: "+" })
        const again = () => folderMenu(k, back)
        Ui.pickFrom("Folders and role", [{ header: "", items: items }], a => {
            if (a === "+") {
                Ui.ask("New folder", "Only you see it", "", "Make it", t => { const f = Chat.addFolder(t); if (f !== "") Chat.setInFolder(f, k, true); Qt.callLater(again) }, false, true)
                return
            }
            if (a.indexOf("role:") === 0) {
                const pick = a.substring(5), now = Chat.foldersOf(k)
                roles.forEach(r => {
                    let f = named(r)
                    const on = r === pick && !(f && now.indexOf(f.id) >= 0)
                    if (!f && on) { const id = Chat.addFolder(r); if (id !== "") Chat.setInFolder(id, k, true); return }
                    if (f) Chat.setInFolder(f.id, k, on)
                })
            } else Chat.setInFolder(a, k, Chat.foldersOf(k).indexOf(a) < 0)
            Qt.callLater(again)
        }, back)
    }
    // Chat: where it sits and how it behaves, its pins and what was shared in it.
    function chatMenu(k, c, back) {
        const items = []
        if (has("pinChat")) items.push({ name: "Pin to the top", url: "pin", check: Chat.isPinnedChat(k) })
        if (has("muteChat")) items.push({ name: "Mute", url: "mute", check: Chat.isMuted(k) })
        if (has("archiveChat")) items.push({ name: "Archive", url: "archive", check: Chat.isArchived(k) })
        if (has("markUnread")) items.push({ name: "Mark unread", url: "unread", check: Chat.isMarkedUnread(k) })
        const keys = c.keys || [], at = keys.indexOf(k)
        if (at > 0) items.push({ name: "Move up", url: "up" })
        if (at >= 0 && at < keys.length - 1) items.push({ name: "Move down", url: "down" })
        items.push({ name: "Pinned messages", icon: "pin", url: "pins" }, { name: "Shared", icon: "image", url: "shared" })
        Ui.pickFrom("Chat", [{ header: "", items: items }], a => {
            let next = c
            if (a === "pin") Chat.pinChat(k, !Chat.isPinnedChat(k))
            else if (a === "mute") Chat.muteChat(k, Chat.isMuted(k) ? 0 : 8640000000000000)   // until unmuted
            else if (a === "archive") Chat.archiveChat(k, !Chat.isArchived(k))
            else if (a === "unread") Chat.markUnread(k, !Chat.isMarkedUnread(k))
            else if (a === "up" || a === "down") {
                const order = keys.slice()
                order.splice(at, 1)
                order.splice(a === "up" ? at - 1 : at + 1, 0, k)
                Chat.placeIn(k, c.section || "", c.section || "", order)
                next = Object.assign({}, c, { keys: order })
            }
            else if (a === "pins" || a === "shared") { Ui.openPage("dms", { open: k, show: a }); return }
            Qt.callLater(() => chatMenu(k, next, back))
        }, back)
    }
    // How long messages stay with them: only for someone in Family or Work (the shorter side counts).
    function daysMenu(k, back) {
        const l = Chat.limitInfo(k)
        if (!l.close) { Ui.say(l.days + " days. Family or Work can choose."); return }
        const note = l.older ? "Their app keeps all until updated" : l.d1 ? "They keep " + l.theirs + ". Shorter counts" : ""
        Ui.pickFrom("Message days", [{ header: note, items: l.choices.map(d => ({ name: d + " days", url: String(d), check: d === l.mine })) }],
                    d => { Chat.setKeep(k, Number(d)); if (back) Qt.callLater(back) }, back)
    }


    // ------------------------------------------------------------- i1: checking and introducing

    // The 4 pictures (and the number apps without i1 show) for a contact; "It matches" marks them checked.
    function checkCode(k) {
        const n = Chat.nameOf(k), e = Trust.emojiWith(k)
        Ui.sure("Safety code with " + n, (e !== "" ? e + "\n\n" : "") + Chat.codeWith(k)
                + "\n\nSame on their screen? Then it's really them.",
                "It matches", () => Chat.verify(k))
    }
    // Introduce a contact to another one: both say yes, then they're in touch.
    function introduce(k) {
        const n = Chat.nameOf(k)
        const items = Chat.chats.filter(c => c.contact && c.key !== k).map(c => ({ name: c.name, url: c.key,
            detail: Trust.hasI1(c.key) ? "" : "Needs an update" }))
        if (!items.length) { Ui.say("Add another contact first.", true); return }
        Ui.pickFrom("Introduce " + n + " to", [{ header: "Both are asked first", items: items }], other => {
            const r = Trust.introduce(k, other)
            if (r === "") Ui.say("Introduced " + n + " and " + Chat.nameOf(other) + ".")
            else Ui.say(r, true)
        })
    }
    // Rows for the Chats list: introductions, group members asking, Family offers.
    function pendingEntries() {
        return Trust.pending.map(p => ({ kind: "intro", id: p.id, key: p.key, name: p.name, pendingKind: p.kind, detail: p.detail }))
    }
    function answer(e) {
        const title = e.pendingKind === "intro" ? "Meet " + e.name + "?" : e.pendingKind === "offer" ? "Add " + e.name + "?" : e.name + " wants to be in touch"
        const why = e.pendingKind === "offer" ? e.detail.replace(": add as a contact?", "") + "." : e.detail + "."
        Ui.sure(title, why, e.pendingKind === "offer" ? "Ask them" : "Yes", () => { Trust.answer(e.id, true); Ui.say(e.pendingKind === "offer" ? "Asked " + e.name + "." : "Said yes to " + e.name + ".") },
                false, false, () => Trust.answer(e.id, false))
        Ui.confirm.cancelText = e.pendingKind === "offer" ? "Not now" : "No"
    }
    // Someone in a group who isn't a contact yet (GroupPage's member options).
    function askToAdd(g, m) {
        const r = Trust.askToAdd(g, m.key)
        if (r === "") Ui.say("Asked " + m.name + ".")
        else Ui.say(r, true)
    }
    // A link that works for one person: copied, for any app or email.
    function inviteLink() {
        const link = Trust.inviteLink(24)
        if (link === "") { Ui.say("Unlock Huginn first.", true); return }
        Chat.copyText(link)
        Ui.say("One-time link copied. Works for a day.")
    }
    // After adding a code: a one-time invite says hi at once, so they know it worked.
    function added(code, key) {
        if (Trust.isOnceCode(code)) Chat.sendPrivate(key, "Hi, it's " + Chat.username + ". I added you with your invite link.")
    }

    // ------------------------------------------------------------- f1: files over the internet

    function sendFile(k) {
        if (Ui.main && Ui.main.touring) return
        System.pickFiles().forEach(p => sendUrl(k, p))
    }
    function sendUrl(k, url) {
        const path = Chat.localPath(url)
        const r = FileLink.send(k, path === "" ? String(url) : path)
        if (r !== "") Ui.say(r, true)
    }
    // A dropped file that isn't a picture: straight to them when possible, otherwise on the same Wi-Fi.
    function dropFiles(k, urls) {
        if (Chat.route(k) !== "wifi" && FileLink.hasF1(k)) { urls.forEach(u => sendUrl(k, u)); return true }
        return false
    }
    // A file that came through the relays.
    function saveFile(k, m) {
        const r = FileLink.saveReceived(k, m.id)
        Ui.say(r === "" ? m.file + " is in Downloads." : r, r !== "")
    }
    function fileText(t) {
        const pct = t.size > 0 ? Math.floor(100 * t.done / t.size) : 0
        return t.state === "done" ? (t.mine ? "Sent" : t.note || "Saved")
             : t.state === "failed" ? t.note
             : t.state === "sending" ? (t.note !== "" ? t.note : (t.mine ? "Sending " : "Getting ") + pct + "% of " + System.sizeText(t.size))
             : t.note
    }

    // ------------------------------------------------------------- linked devices (section 15)

    function linkedDevices() {
        if (!Chat.ready) { Ui.say("Unlock Huginn first.", true); return }
        const items = []
        const ph = Linked.phone
        if (ph.key) items.push({ name: "Unlink " + ph.name, url: "ph", danger: true })
        else items.push({ name: "Show my phone's chats here", detail: "Shows a code for your phone", url: "code" })
        items.push({ name: "Link a device", detail: "It mirrors this PC's chats", url: "link" })
        Linked.companions.forEach(c => items.push({ name: "Unlink " + c.name, url: "u:" + c.key, danger: true }))
        // Cap x1: everything moves here instead, and the old device is locked out.
        const move = [{ name: "Move here", detail: "The old device is locked out", url: "movehere" },
                      { name: "Move from this PC", detail: "Paste the new device's code", url: "moveaway" }]
        Ui.pickFrom("Linked devices", [{ header: "Use on both", items: items },
                                       { header: "Move to another device", items: move }], a => {
            if (a === "movehere") {
                const code = Mover.showCode()
                if (code === "") { Ui.say("Join your home Wi-Fi first.", true); return }
                Chat.copyText(code)
                Ui.sure("Move code copied", "Paste it on the old device, on the same Wi-Fi.\n\n" + code, "Done", () => {}, false, false)
            } else if (a === "moveaway") {
                Ui.ask("Move from this PC", "Only to a device you hold", "", "Next", t => Mover.take(t))
            } else if (a === "code") {
                const code = Linked.newCode()
                Chat.copyText(code)
                Ui.sure("Link code copied", "Paste it on your phone. Works once, for 10 minutes.\n\n" + code, "Done", () => {}, false, false)
            } else if (a === "link") {
                Ui.ask("Link a device", "Paste the code it shows", "", "Link", t => {
                    const r = Linked.link(t)
                    Ui.say(r === "" ? "Check the 4 emoji match." : r, r !== "")
                })
            } else if (a === "ph") {
                Ui.sure("Unlink " + ph.name + "?", "Its chats leave this PC.", "Unlink", () => Linked.unlink(ph.key), false, true)
            } else if (a.indexOf("u:") === 0) {
                Linked.unlink(a.substring(2))
                Ui.say("Unlinked.")
            }
        })
    }
}

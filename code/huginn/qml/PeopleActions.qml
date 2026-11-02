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
    // section's order as shown, for Move up and down), removed (called before Remove), tour, inChat
    // (from the open chat, whose header has Call and Video) }.
    // Short, like Muninn's: Find, Media and files, Mute and Details first; then Notifications, Mark unread
    // and Add to group; everything else under More (contactMore).
    function has(fn) { return typeof Chat[fn] === "function" }
    function contactMenu(k, n, ctx) {
        const c = ctx || {}
        if (!k) return
        const name = c.tour ? n : (Chat.nameOf(k) || n)
        const contact = !!c.tour || Chat.isContact(k)
        const top = []
        if (!c.inChat) {
            top.push({ name: "Message", icon: "thread", url: "message" })
            if (contact) top.push({ name: "Call", icon: "call", url: "call" })
            if (contact && (c.tour || Calls.canVideo(k))) top.push({ name: "Video", icon: "video", url: "video" })
        }
        top.push({ name: "Find in chat", icon: "search", url: "find" },
                 { name: "Media and files", icon: "image", url: "media", sub: () => mediaMenu(k, name, c) })
        if (!c.tour) top.push(Chat.isMuted(k) ? { name: "Unmute", icon: "bell", url: "mute" }
                                              : { name: "Mute", icon: "belloff", url: "mute", sub: () => muteMenu(k, name) })
        top.push({ name: "Details", icon: "edit", url: "edit" })
        const mid = []
        if (has("notifyMode")) mid.push({ name: "Notifications", icon: "bell", url: "notify", sub: () => notifyMenu(k) })
        if (has("markUnread")) mid.push({ name: "Mark unread", icon: "check", url: "unread", check: !c.tour && Chat.isMarkedUnread(k) })
        if (contact) mid.push({ name: "Add to group", icon: "people", url: "group", sub: () => addToGroupMenu(k, name) })
        mid.push({ name: "More", icon: "more", url: "more", sub: () => contactMore(k, n, c) })
        const back = () => contactMenu(k, n, c)
        Ui.pickFrom(name, [{ header: "", items: top }, { header: "", items: mid }], a => {
            if (c.tour) return
            if (a === "message") Ui.openPage("dms", { open: k })
            else if (a === "call") { const w = Calls.call(k); if (w !== "") Ui.say(w, true) }
            else if (a === "video") { const w = Calls.callVideo(k); if (w !== "") Ui.say(w, true) }
            else if (a === "find") Ui.openPage("dms", { open: k, show: "find" })
            else if (a === "media") mediaMenu(k, name, c, back)
            else if (a === "mute") muteMenu(k, name)
            else if (a === "edit") Ui.showPerson(k, name, { page: "edit", back: back })
            else if (a === "notify") notifyMenu(k, back)
            else if (a === "unread") Chat.markUnread(k, !Chat.isMarkedUnread(k))
            else if (a === "group") addToGroupMenu(k, name)
            else if (a === "more") contactMore(k, n, c, back)
        })
    }
    // Media and files: what was shared in the chat (photos, links, voice), or its Files.
    function mediaMenu(k, name, c, back) {
        Ui.pickFrom("Media and files", [{ header: "", items: [{ name: "Media", detail: "Photos, links and voice", icon: "image", url: "shared" },
                                                             { name: "Files", icon: "file", url: "files" }] }], a => {
            if (c && c.tour) return
            if (a === "files") openFiles(k, name)
            else Ui.openPage("dms", { open: k, show: "shared" })
        }, back)
    }
    // How messages reach someone right now, in a word (Chat.route).
    function routeWord(r) { return r === "bluetooth" ? "Bluetooth" : r === "wifi" ? "Wi-Fi" : r === "internet" ? "Internet" : "Not reachable" }
    // The rest of a contact's menu: where the chat sits and how it behaves, then safety, then the red ones last.
    function contactMore(k, n, ctx, back) {
        const c = ctx || {}
        const name = c.tour ? n : (Chat.nameOf(k) || n)
        const contact = !!c.tour || Chat.isContact(k)
        const blocked = !c.tour && Chat.isBlocked(k)
        const items = []
        if (contact) items.push({ name: "Folders and role", icon: "folder", url: "folders" }, { name: "What they can do", icon: "shield", url: "can" })
        if (has("pinChat")) items.push({ name: "Pin to the top", icon: "pin", url: "pin", check: !c.tour && Chat.isPinnedChat(k) })
        if (has("archiveChat")) items.push({ name: "Archive", url: "archive", check: !c.tour && Chat.isArchived(k) })
        const keys = c.keys || [], at = keys.indexOf(k)
        if (at > 0) items.push({ name: "Move up", url: "up" })
        if (at >= 0 && at < keys.length - 1) items.push({ name: "Move down", url: "down" })
        items.push({ name: "Pinned messages", icon: "pin", url: "pins" })
        if (contact) items.push({ name: "Compare emoji", icon: "check", detail: c.tour ? "" : Chat.isVerified(k) ? "Verified" : "Not verified", url: "check" },
                                { name: "Introduce", icon: "people", url: "introduce" })
        // How messages reach them right now (never an address): out of the chat's header, as on Muninn.
        if (!c.tour) items.push({ name: "How messages travel", detail: routeWord(Chat.route(k)), url: "route" })
        // Someone nearby who isn't a contact: Hide takes them off the Nearby list for good.
        if (!contact && !c.tour) items.push({ name: "Hide", icon: "close", url: "hide" })
        const end = [{ name: blocked ? "Unblock" : "Block", url: "block", danger: !blocked }]
        if (!c.tour) end.push({ name: "Delete chat", url: "deletechat", danger: true })
        if (contact) end.push({ name: "Delete contact", url: "remove", danger: true })
        const again = () => contactMore(k, n, c, back)
        Ui.pickFrom("More for " + name, [{ header: "", items: items }, { header: "", items: end }], a => {
            if (c.tour) return
            if (a === "can") Ui.showPerson(k, name, { page: "can", back: again })
            else if (a === "folders") folderMenu(k, again)
            else if (a === "pin") { Chat.pinChat(k, !Chat.isPinnedChat(k)); Qt.callLater(again) }
            else if (a === "archive") { Chat.archiveChat(k, !Chat.isArchived(k)); Qt.callLater(again) }
            else if (a === "up" || a === "down") {
                const order = keys.slice()
                order.splice(at, 1)
                order.splice(a === "up" ? at - 1 : at + 1, 0, k)
                Chat.placeIn(k, c.section || "", c.section || "", order)
                const next = Object.assign({}, c, { keys: order })
                Qt.callLater(() => contactMore(k, n, next, back))
            }
            else if (a === "pins") Ui.openPage("dms", { open: k, show: "pins" })
            else if (a === "check") checkCode(k)
            else if (a === "route") {
                const w = routeWord(Chat.route(k))
                Ui.say(w === "Not reachable" ? name + " can't be reached right now." : "Messages to " + name + " go by " + w + " right now, end-to-end encrypted.")
            }
            else if (a === "introduce") introduce(k)
            else if (a === "hide") { if (c.removed) c.removed(); Chat.hideNearby(k); Ui.say(name + " is hidden.") }
            else if (a === "deletechat") deleteChat(k, name, c.removed)
            else if (a === "block") {
                if (blocked) { Chat.block(k, false); Ui.say(name + " unblocked.") }
                else Ui.sure("Block " + name + "?", "They aren't told.", "Block", () => { Chat.block(k, true); Ui.say(name + " blocked.") }, false, true)
            } else if (a === "remove")
                Ui.sure("Delete contact " + name + "?", "Restore within 30 days.", "Delete contact", () => { if (c.removed) c.removed(); Chat.removeContact(k); Ui.say(name + " is in the Trash.") }, false, true)
        }, back)
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
                Ui.ask("New folder", "Only you see it", "", "Make it", t => {
                    const f = Chat.addFolder(t)
                    if (f !== "") Chat.setInFolder(f, k, true)
                    else if (Chat.saveFailed()) Ui.say("Couldn't save. Try again.", true)
                    Qt.callLater(again)
                }, false, true)
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
    // Mute a chat (a contact, someone nearby, or "g:" and a group's id): 15 minutes, 1 hour, 8 hours or until turned back
    // on. A muted one unmutes at once. No sound or notification while muted.
    function muteMenu(k, name, back) {
        if (Chat.isMuted(k)) {
            Chat.muteChat(k, 0)
            Ui.say(name + " isn't muted.")
            if (back) Qt.callLater(back)
            return
        }
        const items = [{ name: "For 15 minutes", url: "900000" }, { name: "For 1 hour", url: "3600000" },
                       { name: "For 8 hours", url: "28800000" }, { name: "Until I turn it back on", url: "on" }]
        const said = { "900000": " for 15 minutes", "3600000": " for an hour", "28800000": " for 8 hours", "on": "" }
        Ui.pickFrom("Mute " + name, [{ header: "Calls show as missed, without ringing.", items: items }], a => {
            Chat.muteChat(k, a === "on" ? 8640000000000000 : Date.now() + Number(a))   // the huge one: until unmuted
            Ui.say(name + " is muted" + said[a] + ".")
            if (back) Qt.callLater(back)
        }, back)
    }
    // A chat's notifications: every message, one until it's opened (Reduce notifications), or for a group only
    // @mentions and replies to me.
    function notifyMenu(k, back) {
        const group = k.indexOf("g:") === 0, mode = Chat.notifyMode(k)
        const items = [{ name: "Every message", url: "every", check: mode === "every" },
                       { name: "One until I open it", url: "one", check: mode === "one" }]
        if (group) items.push({ name: "Only mentions and replies to me", url: "mentions", check: mode === "mentions" })
        Ui.pickFrom("Notifications", [{ header: "", items: items }], a => {
            Chat.setNotifyMode(k, a)
            if (back) Qt.callLater(back)
        }, back)
    }
    // Delete chat: its messages go from this computer (asked first). The person stays; after: before it goes (closes it).
    function deleteChat(k, name, after) {
        Ui.sure("Delete chat with " + name + "?", "Its messages go from this computer.", "Delete chat",
                () => { if (after) after(); Chat.deleteChat(k); Ui.say("Chat deleted.") }, false, true)
    }
    // Someone nearby who isn't a contact (the Nearby section's rows): Message, Mute, Hide, Block and Delete chat.
    // ctx: { hasChat, removed (called before Hide, Block or Delete chat) }.
    function nearbyMenu(k, n, ctx) {
        const c = ctx || {}
        if (!k) return
        const items = [{ name: "Message", icon: "thread", url: "message" },
                       { name: Chat.isMuted(k) ? "Unmute" : "Mute", icon: "bell", url: "mute" },
                       { name: "Hide", icon: "close", url: "hide" },
                       { name: "Block", url: "block", danger: true }]
        if (c.hasChat) items.push({ name: "Delete chat", url: "deletechat", danger: true })
        Ui.pickFrom(n, [{ header: "Not a contact", items: items }], a => {
            if (a === "message") { Chat.meet(k, c.alt || n); Ui.openPage("dms", { open: k }) }
            else if (a === "mute") muteMenu(k, n)
            else if (a === "hide") { if (c.removed) c.removed(); Chat.hideNearby(k); Ui.say(n + " is hidden.") }
            else if (a === "block")
                Ui.sure("Block " + n + "?", "They aren't told.", "Block", () => { if (c.removed) c.removed(); Chat.block(k, true); Ui.say(n + " blocked.") }, false, true)
            else if (a === "deletechat") deleteChat(k, n, c.removed)
        })
    }
    // How long messages stay with them: only for someone in Family or Work (the shorter side counts).
    function daysMenu(k, back) {
        const l = Chat.limitInfo(k)
        if (!l.close) { Ui.note("How long messages stay", "Messages with them delete after " + l.days + " days, so old chats can't leak. To keep them longer, put them in Family or Work (Folders and role), then pick 14 or 30 days. Only people in those two get longer. They pick too, and the shorter time wins."); return }
        const note = l.older ? "Their app keeps all until updated" : l.d1 ? "They keep " + l.theirs + ". Shorter counts" : ""
        Ui.pickFrom("How long messages stay", [{ header: note, items: l.choices.map(d => ({ name: d + " days", url: String(d), check: d === l.mine })) }],
                    d => { Chat.setKeep(k, Number(d)); if (back) Qt.callLater(back) }, back)
    }

    // ------------------------------------------------------------- right-click menus (Ui.menuAt, RightClickMenu.qml)
    // Each opens with Ui.pickFrom, so it shows as the small menu at the pointer (a row's sub opens its side menu) or as
    // the big list; the big list answers a sub row's url, so every row works there too.

    // A person's right-click menu, like Discord's: Open, Profile, Call, Mute, Notifications, Add to folder, Add to group,
    // Mark as read, Copy name, Hide, Block, Delete chat, and More (their whole menu). ctx: as contactMenu's, plus
    // near (someone nearby who isn't a contact; then also hasChat and alt) and open (opens their chat, else Chats does).
    function personMenu(k, n, ctx) {
        const c = ctx || {}
        if (!k) return
        const name = c.tour ? n : (Chat.nameOf(k) || n)
        const contact = !!c.tour || (!c.near && Chat.isContact(k))
        const blocked = !c.tour && Chat.isBlocked(k)
        const top = [{ name: "Open", icon: "thread", url: "open" }]
        if (contact) top.push({ name: "Profile", icon: "person", url: "profile" }, { name: "Call", icon: "call", url: "call" })
        const mid = [!c.tour && Chat.isMuted(k) ? { name: "Unmute", icon: "bell", url: "mute" }
                                                : { name: "Mute", icon: "belloff", url: "mute", sub: () => muteMenu(k, name) }]
        if (has("notifyMode")) mid.push({ name: "Notifications", icon: "bell", url: "notify", sub: () => notifyMenu(k) })
        if (contact) mid.push({ name: "Add to folder", icon: "folder", url: "folder", sub: () => addToFolderMenu(k, name) },
                              { name: "Add to group", icon: "people", url: "group", sub: () => addToGroupMenu(k, name) })
        mid.push({ name: "Mark read", icon: "check", url: "read" }, { name: "Copy name", url: "copy" },
                 { name: "Hide", icon: "close", url: "hide" },
                 { name: "More", icon: "more", url: "more", sub: () => c.near ? nearbyMenu(k, n, c) : contactMenu(k, n, c) })
        const end = [{ name: blocked ? "Unblock" : "Block", url: "block", danger: !blocked }]
        if (!c.tour && (!c.near || c.hasChat)) end.push({ name: "Delete chat", url: "deletechat", danger: true })
        Ui.pickFrom(name, [{ header: "", items: top }, { header: "", items: mid }, { header: "", items: end }], a => {
            if (c.tour) return
            if (a === "open") { if (c.open) c.open(); else Ui.openPage("dms", { open: k }) }
            else if (a === "profile") Ui.showProfile(k, name)
            else if (a === "call") { const w = Calls.call(k); if (w !== "") Ui.say(w, true) }
            else if (a === "mute") muteMenu(k, name)
            else if (a === "notify") notifyMenu(k)
            else if (a === "folder") addToFolderMenu(k, name)
            else if (a === "group") addToGroupMenu(k, name)
            else if (a === "read") Chat.markRead(k)
            else if (a === "copy") { Chat.copyText(name); Ui.say("Name copied.") }
            else if (a === "hide") {
                // A contact's chat goes to Archived (a new message brings it back); someone nearby leaves the list for good.
                if (c.removed) c.removed()
                if (contact) { if (has("archiveChat")) Chat.archiveChat(k, true); Ui.say(name + " is hidden. A new message brings the chat back.") }
                else { Chat.hideNearby(k); Ui.say(name + " is hidden.") }
            } else if (a === "block") {
                if (blocked) { Chat.block(k, false); Ui.say(name + " unblocked.") }
                else Ui.sure("Block " + name + "?", "They aren't told.", "Block", () => { if (c.near && c.removed) c.removed(); Chat.block(k, true); Ui.say(name + " blocked.") }, false, true)
            }
            else if (a === "deletechat") deleteChat(k, name, c.removed)
            else if (a === "more") { if (c.near) nearbyMenu(k, n, c); else contactMenu(k, n, c) }
        })
    }
    // Add to folder: the folders (ticked where they are) and New folder. The roles (Family, Work, Folk) stay in Folders and role.
    function addToFolderMenu(k, name) {
        const roles = ["family", "work", "folk"], mine = Chat.foldersOf(k)
        const items = Chat.folders.filter(f => roles.indexOf(String(f.name).toLowerCase()) < 0)
            .map(f => ({ name: f.name, icon: "folder", url: f.id, check: mine.indexOf(f.id) >= 0 }))
        items.push({ name: "New folder", icon: "plus", url: "+" })
        Ui.pickFrom("Add to folder", [{ header: "", items: items }], a => {
            if (a === "+") {
                Ui.ask("New folder", "Only you see it", "", "Make it", t => {
                    const f = Chat.addFolder(t)
                    if (f !== "") { Chat.setInFolder(f, k, true); Ui.say(name + " is in " + t + ".") }
                    else Ui.say(Chat.saveFailed() ? "Couldn't save. Try again." : "Give the folder a name.", true)
                }, false, true)
                return
            }
            const on = Chat.foldersOf(k).indexOf(a) < 0, f = Chat.folders.find(x => x.id === a)
            Chat.setInFolder(a, k, on)
            Ui.say(name + (on ? " is in " : " left ") + (f ? f.name : "the folder") + ".")
        })
    }
    // Add to group: an invite to one of my groups (they're asked first), or a new group.
    function addToGroupMenu(k, name) {
        const items = Chat.groups.filter(g => !g.readOnly).map(g => ({ name: g.name, icon: "hash", url: g.id }))
        items.push({ name: "New group", icon: "plus", url: "+" })
        Ui.pickFrom("Add to group", [{ header: "They're asked first", items: items }], g => {
            if (g === "+") { if (Ui.main) Ui.main.newGroup(); return }
            const r = Chat.inviteToGroup(g, k)
            Ui.say(r === "" ? "Invite sent to " + name + "." : r, r !== "")
        })
    }
    // A group's right-click menu (its rail button): Open, Invite people and Copy invite link, Mute notifications,
    // Notifications, Mark as read, Channels, Group settings, More (the group's own menu), Leave. ctx: { tour }.
    function groupRowMenu(g, n, ctx) {
        const c = ctx || {}
        if (!g) return
        const k = "g:" + g
        const info = c.tour ? { amAdmin: true } : Chat.groupInfo(g)
        const row = c.tour ? {} : (Chat.groups.find(x => x.id === g) || {})
        const name = n || info.name || "Group"
        const lim = c.tour ? { links: true } : Chat.groupLimit(g)
        const top = [{ name: "Open", icon: "hash", url: "open" }]
        // Only for someone who can invite: an admin, or anyone in a group where members can invite.
        const canInvite = !row.readOnly && (c.tour || !!info.amAdmin || (lim.links && typeof Chat.membersInvite === "function" && !!Chat.membersInvite(g)))
        if (canInvite) top.push({ name: "Invite people", icon: "plus", url: "people" })
        if (canInvite && lim.links) top.push({ name: "Copy invite link", icon: "attach", url: "invite" })
        const mid = [!c.tour && Chat.isMuted(k) ? { name: "Unmute", icon: "bell", url: "mute" }
                                                : { name: "Mute", icon: "belloff", url: "mute", sub: () => muteMenu(k, name) }]
        if (has("notifyMode")) mid.push({ name: "Notifications", icon: "bell", url: "notify", sub: () => notifyMenu(k) })
        mid.push({ name: "Mark read", icon: "check", url: "read" })
        if (!row.readOnly) mid.push({ name: "Channels", icon: "hash", url: "channels" })
        mid.push({ name: "Group settings", icon: "gear", url: "settings" },
                 { name: "More", icon: "more", url: "more" })
        const end = [row.readOnly ? { name: "Delete from this device", url: "forget", danger: true } : { name: "Leave", url: "leave", danger: true }]
        const onPage = f => { Ui.openPage(k, {}); Qt.callLater(() => { const p = Ui.main ? Ui.main.page() : null; if (p && typeof p[f] === "function") p[f]() }) }
        Ui.pickFrom(name, [{ header: "", items: top }, { header: "", items: mid }, { header: "", items: end }], a => {
            if (c.tour) return
            if (a === "open") Ui.openPage(k, {})
            else if (a === "mute") muteMenu(k, name)
            else if (a === "notify") notifyMenu(k)
            else if (a === "read") (info.channels || []).forEach(ch => Chat.markGroupRead(g, ch.id))
            else if (a === "invite") copyInvite(g)
            else if (a === "people") onPage("inviteMenu")
            else if (a === "channels") onPage("channelsMenu")
            else if (a === "settings") onPage("settingsMenu")
            else if (a === "more") onPage("groupMenu")
            else if (a === "leave") {
                if (info.amOwner && (row.count || 0) > 1) { Ui.say("Make someone else owner first.", true); return }
                Ui.sure("Leave " + name + "?", "Only an invite brings you back.", "Leave", () => { const r = Chat.leaveGroup(g); Ui.say(r === "" ? "You left the group." : r, r !== "") }, false, true)
            } else if (a === "forget")
                Ui.sure("Delete " + name + " from this device?", "Can't be undone.", "Hold to delete", () => { Chat.forgetGroup(g); Ui.openPage("dms", {}) }, true, true)
        })
    }
    // My newest invite link for g nobody used yet, with no use limit and more than an hour left: shown and copied again
    // instead of making another. { link, x (ms, 0 never), max } or null.
    function spareLink(g) {
        if (typeof Chat.groupLinks !== "function") return null
        const spare = (Chat.groupLinks(g) || []).filter(l => l.mine && l.link && !(l.uses > 0) && !(l.max > 0) && (!l.x || l.x - Date.now() > 3600000))
        return spare.length ? spare.sort((a, b) => (b.x || Infinity) - (a.x || Infinity))[0] : null
    }
    // "7 days", "5 hours", "20 minutes": how long a link still works.
    function worksFor(x) {
        if (!x) return "Never expires."
        const left = x - Date.now(), h = Math.floor(left / 3600000)
        return "Works for " + (h >= 48 ? Math.floor(h / 24) + " days" : h >= 1 ? h + (h === 1 ? " hour" : " hours") : Math.max(1, Math.floor(left / 60000)) + " minutes") + "."
    }
    // An invite to copy: my spare link again, else a new one good for 7 days (as the invite panel makes), or the
    // group's code (good for an hour) where links don't work.
    function copyInvite(g) {
        const name = (Chat.groupInfo(g) || {}).name || ""
        const spare = spareLink(g)
        if (spare) { Chat.copyText(inviteText(name, spare.link)); Ui.say("Invite link copied, with how to join. " + worksFor(spare.x || 0)); return }
        const r = Chat.createGroupLink(g, 7 * 86400000, 0) || {}
        if (r.link) { Chat.copyText(inviteText(name, r.link)); Ui.say("Invite link copied, with how to join. Works for 7 days."); return }
        const code = Chat.groupCode(g)
        if (code !== "") { Chat.copyText(inviteText(name, code)); Ui.say("Group code copied, with how to join. Works for an hour."); return }
        Ui.say(r.error ? r.error : "Couldn't make an invite.", true)
    }
    // A group member's right-click menu. In a group page it is the member's one menu (GroupPage.memberMenu, the same as
    // a click). Elsewhere: Profile, Message and the people controls (ModActions: Mute for me, Volume, and for admins
    // Timeout and Ban; Volume and Timeout open beside it).
    // who: { key, name, me, contact }; ctx: { group (its id, "" outside one), host (GroupPage), member (their entry there), tour }.
    function memberMenu(who, ctx) {
        const c = ctx || {}
        if (!who || !who.key) return
        const me = !!who.me, g = c.group || ""
        if (!me && c.member && c.host && typeof c.host.memberMenu === "function") { c.host.memberMenu(c.member); return }
        const name = who.name || (c.tour ? "" : Chat.nameOf(who.key)) || "Someone"
        const items = [{ name: "Profile", icon: "person", url: "profile" }]
        if (!me && (who.contact || c.tour)) items.push({ name: "Message", icon: "thread", url: "message" })
        if (!me && !who.contact && !c.tour && g !== "") items.push({ name: "Add as contact", icon: "plus", url: "add" })
        const controls = me ? [] : ModActions.items(g, who.key, !!c.tour, name).map(x =>
            x.url === "mod:volume" ? Object.assign({}, x, { sub: () => ModActions.volumeMenu(who.key, name, null, !!c.tour) })
            : x.url === "mod:timeout" && !c.tour ? Object.assign({}, x, { sub: () => ModActions.timeoutMenu(g, who.key, name, null) }) : x)
        const more = !me && c.host && typeof c.host.memberMenu === "function"
            ? [{ name: "More", icon: "more", url: "more", sub: () => c.host.memberMenu(c.member || who) }] : []
        Ui.pickFrom(name, [{ header: "", items: items }, { header: "", items: controls.filter(x => !x.person) },
                           { header: controls.some(x => !!x.person) ? name : "", items: more.concat(controls.filter(x => !!x.person)) }], a => {
            if (a === "profile") {
                if (c.host && typeof c.host.showMember === "function") c.host.showMember(me ? "me" : who.key, name)
                else Ui.showProfile(me ? "me" : who.key, name, c.tour ? { tour: true } : {})
            } else if (c.tour) return
            else if (a === "message") Ui.openPage("dms", { open: who.key })
            else if (a === "add") askToAdd(g, { key: who.key, name: name })
            else if (a === "more" && c.host) c.host.memberMenu(c.member || who)
            else ModActions.handle(a, g, who.key, name, null)
        })
    }
    // A message's link (the first one in its text), for Copy link.
    function firstLink(t) { const r = String(t || "").match(/https?:\/\/[^\s<>"]+/); return r ? r[0] : "" }
    // Forward a message's text to a contact or a group (its first channel).
    function forwardMenu(text) {
        const people = Chat.chats.filter(c => c.contact && !c.archived).map(c => ({ name: c.name, icon: "person", url: c.key }))
        const groups = Chat.groups.filter(g => !g.readOnly).map(g => ({ name: g.name, icon: "hash", url: "g:" + g.id }))
        Ui.pickFrom("Forward to", [{ header: "People", items: people }, { header: "Groups", items: groups }], to => {
            if (to.indexOf("g:") === 0) Chat.sendGroup(to.substring(2), "main", text)
            else Chat.sendPrivate(to, text)
            Ui.say("Sent.")
        })
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
    // A group's code or invite link (cap j1 links work the same way): its name and size, then Join or Not now. False
    // when t isn't a group invite (then it may be a person's code). done(name) after asking to join.
    function joinGroup(t, done) {
        const gc = Chat.parseGroupCode(t) || {}
        if (!gc.name && t.indexOf("MUNINNG1:") < 0) return false
        if (gc.expired) { Ui.say("That invite expired. Ask for a new one.", true); return true }
        if (gc.mine || gc.member) { Ui.say("You're already in " + (gc.name || "that group") + ".", true); return true }
        const go = () => {
            const r = Chat.joinWithCode(t)
            if (r !== "") { Ui.say(r, true); return }
            Ui.say(joinWaitText(gc.name || "the group", gc.adminName || ""))
            if (done) done(gc.name || "")
        }
        if (!gc.name) { go(); return true }
        const n = gc.count || gc.members || 0
        // While a phone is linked, this joins as this PC's own account, not the phone's: said plainly.
        const own = !!Linked.phone.key && !Linked.phone.gone
        Ui.sure(own ? "Join " + gc.name + " as this PC's own account?" : "Join " + gc.name + "?",
                (n ? n + (n === 1 ? " member. " : " members. ") : "") + (own ? "They'd see a second " + Ui.myName + ": this PC's own account, with the same name and picture but separate from your phone. To join as you, open the invite on your phone." : "They see your name."),
                "Join", go, false, false)
        Ui.confirm.cancelText = "Not now"
        return true
    }
    // What an invite to a group is copied as: the link or code with a one-line how-to, the same as Muninn's.
    function inviteText(group, link) {
        return "Join " + (group || "my group") + " on Huginn or Muninn. Open the app, choose + then Join a group, and paste this: " + link
    }
    // Joining waits for the person who invited you: their app lets you in when it's open.
    function joinWaitText(group, inviter) {
        const who = inviter || "the person who invited you"
        return "Asked to join " + group + ". You're in as soon as " + who + " has the app open. If it takes a while, ask them to open it."
    }
    // A link that works for one person: copied, for any app or email.
    function inviteLink() {
        const link = Trust.inviteLink(24)
        if (link === "") { Ui.say("Unlock Huginn first.", true); return }
        Chat.copyText(link)
        Ui.say("One-time link copied. Works for a day.")
    }
    // After adding a code: a code with "1" (every code Huginn and Muninn show, and one-time links) says hi at once, which
    // makes this device a contact on theirs too, with no request to accept (13.6).
    function added(code, key) {
        if (Trust.isOnceCode(code)) Chat.sendPrivate(key, "Hi, it's " + Chat.username + ". I added you.")
    }

    // ------------------------------------------------------------- files (caps f1 and f2)

    // The one way to send a file (the clip in every chat and group, drag and drop): to a chat, their key or
    // "g:<group>[:<channel>]". The route is picked by itself; its card shows in the chat and in Transfers.
    function sendFile(where) {
        if (Ui.main && Ui.main.touring) return
        Ui.pickFiles(paths => sendFiles(where, paths))
    }
    function sendFiles(where, urls) {
        const r = FileLink.sendTo(where, urls)
        if (r !== "") Ui.say(r, true)
        else Ui.say(urls.length === 1 ? "Sending the file. Transfers shows how it goes." : "Sending " + urls.length + " files. Transfers shows how they go.")
    }
    // A file too big to go without Direct (Transfers, a chat's file card): Direct calls and files with them on here (it
    // shares this device's internet address with them), and a message asks them to turn it on too, once per person this
    // run (the button then says Asked). Retry once they have.
    property var askedDirect: ({})
    function directLabel(k) { return !Calls.trusted(k) ? "Allow direct" : askedDirect[k] ? "Asked" : "Ask " + Chat.nameOf(k) }
    function allowDirect(k) {
        const name = Chat.nameOf(k)
        if (Calls.trusted(k) && askedDirect[k]) { Ui.say(name + " was already asked. Retry once they turn on Direct calls and files."); return }
        const ask = () => {
            if (!Calls.trusted(k)) Calls.setTrusted(k, true)
            Chat.sendPrivate(k, "Can you turn on Direct calls and files for me? It's in the menu for my name. Then big files go straight between us.")
            const a = Object.assign({}, askedDirect)
            a[k] = true
            askedDirect = a
            Ui.say("Direct is on here, and " + name + " was asked to turn it on too. Retry once they have.")
        }
        if (Calls.trusted(k)) { ask(); return }
        Ui.sure("Allow direct with " + name + "?", "Big files go straight between your devices. Both of you turn this on. It shares your internet address with " + name + ".",
                "Allow and ask", ask)
    }
    // Send to someone: files picked first (Files), then who gets them: people, groups, and LocalSend devices nearby.
    function sendPicker(urls) {
        if (!urls.length) return
        const people = Chat.people.filter(c => c.contact && !c.blocked).map(c => ({ name: c.name, icon: "person", url: "p:" + c.key }))
        const groups = Chat.groups.filter(g => !g.readOnly).map(g => ({ name: g.name, icon: "people", url: "g:" + g.id }))
        const nearby = LocalSend.devices.filter(d => !d.chatKey).map(d => ({ name: "Nearby: LocalSend · " + d.alias, icon: "network", url: "l:" + d.id }))
        const sections = []
        if (people.length) sections.push({ header: "PEOPLE", items: people })
        if (groups.length) sections.push({ header: "GROUPS", items: groups })
        sections.push({ header: "NEARBY", items: nearby.length ? nearby : [{ name: LocalSend.running ? "No LocalSend devices on this Wi-Fi" : "Nearby sharing is off", url: "" }] })
        Ui.pickFrom(urls.length === 1 ? "Send the file to" : "Send " + urls.length + " files to", sections, a => {
            if (a.indexOf("p:") === 0) sendFiles(a.substring(2), urls)
            else if (a.indexOf("g:") === 0) sendFiles(a, urls)
            else if (a.indexOf("l:") === 0) {
                // LocalSend: its own transfer (the app on their side asks first); a PIN, when asked, resends these.
                Transfer.clearShared()
                Transfer.share(urls)
                LocalSend.send(a.substring(2), Transfer.shared)
                Ui.say("Sending. Transfers shows how it goes.")
            }
        })
    }
    // A chat's Files: everything sent and received there (where: their key or "g:<group>").
    function openFiles(where, title) { if (Ui.main && Ui.main.openFiles) Ui.main.openFiles(where, title) }
    // A group's file rules (cap f2): who can send files and the size limit. Every member's app enforces them.
    function groupFilesMenu(g, back) {
        const r = Chat.groupFiles(g)
        if (!r.who) return
        if (!r.canChange) { Ui.say(r.line + "."); if (back) Qt.callLater(back); return }
        const again = () => groupFilesMenu(g, back)
        Ui.pickFrom("Who can send files", [{ header: "", items: [
            { name: "Everyone", url: "everyone", check: r.who === "everyone" },
            { name: "Admins only", url: "admins", check: r.who === "admins" },
            { name: "Nobody", url: "nobody", check: r.who === "nobody" },
            { name: "Size limit", detail: r.mb > 0 ? "Up to " + r.mb + " MB" : "No limit", url: "limit" }] }], a => {
            if (a === "limit") { sizeLimitMenu(g, r, again); return }
            const w = Chat.setGroupFiles(g, a, r.mb)
            Ui.say(w !== "" ? w : "Saved. Every member's app follows it.", w !== "")
            Qt.callLater(again)
        }, back)
    }
    function sizeLimitMenu(g, r, back) {
        const sizes = [0, 10, 25, 100, 500, 2048]
        Ui.pickFrom("Size limit", [{ header: "Bigger files are refused", items: sizes.map(mb => ({
            name: mb === 0 ? "No limit" : mb >= 1024 ? (mb / 1024) + " GB" : mb + " MB", url: String(mb), check: r.mb === mb })) }], a => {
            const w = Chat.setGroupFiles(g, r.who, parseInt(a))
            Ui.say(w !== "" ? w : "Saved.", w !== "")
            Qt.callLater(back)
        }, back)
    }
    // A file that came through the relays and couldn't be saved then.
    function saveFile(k, m) {
        const r = FileLink.saveReceived(k, m.id)
        Ui.say(r === "" ? m.file + " is in " + FileLink.folderText(k) + "." : r, r !== "")
    }

    // ------------------------------------------------------------- linked devices (section 15)

    // Unlinks the phone and takes its copy off. This PC's own name and picture come back; when they aren't known
    // (it was linked before they were kept, or it never had a name), it asks for one instead of keeping the phone's.
    // wasKept: only a copy kept earlier is removed (it was unlinked already).
    function unlinkRemove(wasKept) {
        const done = wasKept ? "Copy removed." : "Unlinked."
        if (Linked.unlinkPhone(false)) { Ui.say(wasKept ? "Copy removed. This computer has its own name again." : "Unlinked. This computer has its own name again."); return }
        Qt.callLater(() => askOwnName(done))
    }
    // This computer's own account needs a name of its own again (the phone's was used here).
    function askOwnName(done) {
        Ui.ask("Name this computer's account", "Your phone's name was used here. Pick the name this computer shows in its own chats.", "", "Save",
               t => { Chat.username = t; Linked.nameGiven(); Ui.say(done || "Saved.") })
    }

    function linkedDevices() {
        if (!Chat.ready) { Ui.say("Unlock Huginn first.", true); return }
        const ph = Linked.phone
        const phone = []
        if (!ph.key) phone.push({ name: "Link this PC to my phone", detail: "Shows a code to scan in Muninn", url: "code" })
        else if (ph.gone) {
            // Unlinked with a copy kept: link again, or take the copy off.
            phone.push({ name: "Link this PC to my phone", detail: "Shows a code to scan in Muninn", url: "code" },
                       { name: "Remove " + ph.name + "'s copy", detail: "Its chats, contacts, groups and folders leave this device", url: "wipe", danger: true })
        } else {
            if (!ph.ok) phone.push({ name: "Compare emoji", detail: "Nothing comes over until they match", url: "check" })
            phone.push({ name: "Unlink " + ph.name, detail: "Keep a read-only copy here, or remove it", url: "ph", danger: true })
        }
        // Huginn as the phone: another device mirrors this one.
        // The other way round, rarely wanted: another device shows this PC's chats.
        const others = [{ name: "Show this PC's chats on another device", detail: "Paste the code that device shows", url: "link" }]
        Linked.companions.forEach(c => others.push({ name: "Unlink " + c.name, url: "u:" + c.key, danger: true }))
        // Cap x1: everything moves here instead, and the old device is locked out.
        const move = [{ name: "Move here", detail: "The old device is locked out", url: "movehere" },
                      { name: "Move from this device", detail: "Paste the new device's code", url: "moveaway" }]
        Ui.pickFrom("Linked devices", [{ header: "Your phone", items: phone },
                                       { header: "Other ways", items: others },
                                       { header: "Move to another device", items: move }], a => {
            if (a === "movehere") {
                const code = Mover.showCode()
                if (code === "") { Ui.say("Join your home Wi-Fi first.", true); return }
                Chat.copyText(code, true)
                Ui.note("Move code copied", "Paste it on the old device, on the same Wi-Fi. It's cleared from the clipboard in a minute.\n\n" + code)
            } else if (a === "moveaway") {
                Ui.ask("Move from this device", "Only to a device you hold", "", "Next", t => Mover.take(t))
            } else if (a === "code" || a === "check") {
                if (Ui.main) Ui.main.showLinkPhone(false)
            } else if (a === "link") {
                Ui.ask("Show this PC's chats on another device", "Paste the code that device shows. To link to your phone, use Link this PC to my phone.", "", "Link", t => {
                    const r = Linked.link(t)
                    Ui.say(r === "" ? "Check the 4 emoji match." : r, r !== "")
                })
            } else if (a === "ph") {
                // Keep a read-only copy here, or take everything that came from the phone off this device.
                Ui.pickFrom("Unlink " + ph.name + "?", [{ header: "", items: [
                    { name: "Unlink, keep a copy", detail: "Chats, contacts, groups and folders stay here, read only", url: "keep" },
                    { name: "Unlink and remove", detail: "Its chats, contacts, groups and folders leave this device", url: "wipe", danger: true }] }],
                    b => {
                        if (b === "keep") { Linked.unlinkPhone(true); Ui.say("Unlinked. A read-only copy stays here. This PC's own account keeps your name until you remove the copy."); return }
                        Ui.sure("Unlink and remove?", "Its chats, contacts, groups and folders leave this device. Your phone keeps them all.",
                                "Unlink and remove", () => unlinkRemove(false), false, true)
                    })
            } else if (a === "wipe") {
                Ui.sure("Remove " + ph.name + "'s copy?", "Its chats, contacts, groups and folders leave this device. Your phone keeps them all.", "Remove", () => unlinkRemove(true), false, true)
            } else if (a.indexOf("u:") === 0) {
                const key = a.substring(2), c = Linked.companions.find(x => x.key === key)
                const name = c ? c.name : "this device"
                Ui.sure("Unlink " + name + "?", "It stops showing this device's chats.", "Unlink", () => { Linked.unlink(key); Ui.say("Unlinked.") }, false, true)
            }
        })
    }
}

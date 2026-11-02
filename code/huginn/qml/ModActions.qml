pragma Singleton
import QtQuick
import Huginn

// Mute for me, Volume, and for admins Timeout and Ban (cap g2, moderation.cpp), the same in every place a person
// shows: the member list, a voice room and a group message (a right click). items() gives the rows to add to a
// menu, handle() acts on one of them (false when the url isn't one of these), personMenu() is the menu on its own.
// The same as Muninn's.
QtObject {
    function isTimeout(g, k) { return g !== "" && Mod.timedOutUntil(g, k) > 0 }
    // g: the group ("" outside one), k: their key. tour: how it looks, without asking anything. name: theirs, for
    // "Timeout Alex…" and "Ban Alex…" (rows about the person carry person: true, for their own section).
    function items(g, k, tour, name) {
        const out = []
        if (!k) return out
        const muted = !tour && Mod.mutedForMe(k)
        out.push({ name: muted ? "Unmute for me" : "Mute for me", icon: muted ? "mic" : "micoff", url: "mod:mute",
                   detail: muted ? "Their voice is off here" : "Only you stop hearing them" })
        if (muted) out.push({ name: "Fold their messages", url: "mod:fold", check: Mod.hidesMessages(k) })
        out.push({ name: "Volume (just for you)", icon: "speaker", detail: (tour ? 100 : Mod.volume(k)) + "%", url: "mod:volume" })
        if (g !== "" && (tour || Mod.canModerate(g, k))) {
            const until = tour ? 0 : Mod.timedOutUntil(g, k)
            const who = name ? " " + name : ""
            if (until > 0) out.push({ name: "Cancel timeout" + (name ? " for " + name : ""), url: "mod:untimeout", person: true })
            else out.push({ name: "Timeout" + who + "…", detail: "They can read but not post", url: "mod:timeout", person: true })
            out.push({ name: "Ban" + who + "…", detail: "Removes them, and they can't rejoin", url: "mod:ban", danger: true, person: true })
        }
        return out
    }
    // True when a was one of items()'s rows; back reopens the menu it came from.
    function handle(a, g, k, name, back) {
        if (typeof a !== "string" || a.indexOf("mod:") !== 0) return false
        const after = () => { if (back) Qt.callLater(back) }
        if (a === "mod:mute") {
            const on = !Mod.mutedForMe(k)
            Mod.setMutedForMe(k, on)
            Ui.say(on ? name + " is muted for you." : name + " isn't muted for you.")
            after()
        } else if (a === "mod:fold") {
            Mod.setHidesMessages(k, !Mod.hidesMessages(k))
            after()
        } else if (a === "mod:volume") volumeMenu(k, name, back)
        else if (a === "mod:timeout") timeoutMenu(g, k, name, back)
        else if (a === "mod:untimeout") tell(Mod.cancelTimeout(g, k), name + "'s timeout ended.")
        else if (a === "mod:ban") banMenu(g, k, name, back)
        return true
    }
    // Ban, or Ban and also remove what they posted in the last day (one remove each, like Delete for everyone).
    function banMenu(g, k, name, back) {
        Ui.pickFrom("Ban " + name + "?", [{ header: "They leave the group and can't rejoin by a link, code or invite until you unban them.", items: [
            { name: "Ban " + name, url: "ban", danger: true },
            { name: "Ban and remove their messages", detail: "From the last day, for everyone", url: "clear", danger: true }] }], a => {
            const clear = a === "clear"
            Ui.sure("Ban " + name + "?", clear ? "Their messages from the last day show as Removed by an admin, and they can't rejoin until you unban them."
                                               : "They leave the group and can't rejoin by a link, code or invite until you unban them.", "Ban", () => {
                const n = clear && typeof Mod.removeRecent === "function" ? Mod.removeRecent(g, k, 86400000) : 0
                // One removal list holds at most 50 (ModWire::kMaxList), and apps without g2 keep showing them.
                const behind = n > 0 && typeof Mod.withoutRemove === "function" ? Mod.withoutRemove(g) : 0
                tell(Mod.ban(g, k), name + " is banned." + (n > 0 ? " " + n + (n === 1 ? " message removed." : " messages removed.") : "")
                     + (n >= 50 ? " Some older ones may still be up: remove them one at a time." : "")
                     + (behind > 0 ? " " + behind + (behind === 1 ? " member still sees them until they update." : " members still see them until they update.") : ""))
            }, false, true)
        }, back)
    }
    function tell(err, done) { if (err) Ui.say(err, true); else Ui.say(done) }
    function personMenu(g, k, name, back, tour) {
        if (!k) return
        const again = () => personMenu(g, k, name, back, tour)
        Ui.pickFrom(name, [{ header: "", items: items(g, k, tour) }], a => { if (!tour) handle(a, g, k, name, again) }, back)
    }
    // A slider from 0 to 200%, saved as it moves; their sound changes at once.
    function volumeMenu(k, name, back, tour) {
        const v = tour ? 150 : Mod.volume(k)
        Ui.pickFrom("Volume for " + name, [{ header: "Only on this computer", items: [
            { name: "Volume", slider: true, value: v, from: 0, to: 200, step: 5, unit: "%", moved: n => { if (!tour) Mod.setVolume(k, n) } },
            { name: "Back to 100%", url: "reset" }] }], a => {
            if (a === "reset" && !tour) { Mod.setVolume(k, 100); Ui.say(name + " is at 100%.") }
            if (back) Qt.callLater(back)
        }, back)
    }
    // A group's row (the rail): Mute or Unmute, and Notifications, as in the group's own menu.
    function groupRowMenu(g, name) {
        if (!g || g.indexOf("tour-") === 0) return
        const k = "g:" + g, again = () => groupRowMenu(g, name)
        Ui.pickFrom(name, [{ header: "", items: [{ name: Chat.isMuted(k) ? "Unmute" : "Mute", icon: "bell", url: "mute" },
                                                  { name: "Notifications", icon: "bell", url: "notify" }] }], a => {
            if (a === "mute") PeopleActions.muteMenu(k, name, again)
            else if (a === "notify") PeopleActions.notifyMenu(k, again)
        })
    }
    function timeoutMenu(g, k, name, back) {
        const items = Mod.timeoutChoices().map(c => ({ name: c.label, url: String(c.ms) }))
        Ui.pickFrom("Timeout " + name, [{ header: "They can read but not post, send files or join voice", items: items }], a => {
            tell(Mod.timeout(g, k, Number(a)), name + " is timed out.")
        }, back)
    }
}

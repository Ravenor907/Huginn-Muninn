package io.github.ravenor907.muninn.net

import org.json.JSONObject

/**
 * Cap i1 (shared/docs/protocol.md, section 13): becoming contacts through someone you already trust.
 *
 *  - Room "intro" t "intro" {id, k, n, p}: a contact introduces me to [k] (their name and mailbox). Nothing happens
 *    until I say yes.
 *  - Room "intro" t "yes" {id}: an introduced person says yes, straight to the other one. Taken from a non-contact only
 *    when I hold an introduction with that id naming them. Both said yes: contacts on both phones.
 *  - Room "intro" t "add" {g}: someone in a group with me (a Family group) asks to be contacts. Taken only from a member
 *    of a group we share; both said yes: contacts.
 */
object Trust {
    const val CAP = "i1"

    /** An introduction or a group member asking ([by] is the introducer's key, or "g:" + the group). */
    data class Intro(val id: String, val by: String, val k: String, val n: String, val p: String,
                     val iSaid: Boolean = false, val theySaid: Boolean = false, val declined: Boolean = false, val at: Long = 0)

    fun ok(p: JSONObject): Boolean = when (p.opt("t")) {
        "intro" -> Proto06.isLogicalId(p.opt("id") as? String ?: "") && keyOk(p.opt("k")) &&
            (p.opt("n") as? String)?.let { it.isNotBlank() && it.codePointCount(0, it.length) <= 40 && it.none { c -> c.code < 0x20 } } == true &&
            (p.opt("p") as? String)?.let { it.isEmpty() || Proto06.isMailbox(it) } == true
        "yes" -> Proto06.isLogicalId(p.opt("id") as? String ?: "")
        "add" -> (p.opt("g") as? String)?.length in 1..40
        else -> false
    }

    private fun keyOk(k: Any?): Boolean = k is String && k.length in 40..200 && try { ChatCrypto.publicFrom(k); true } catch (e: Exception) { false }

    fun toJson(i: Intro): JSONObject = JSONObject().put("id", i.id).put("by", i.by).put("k", i.k).put("n", i.n).put("p", i.p)
        .put("i", i.iSaid).put("t", i.theySaid).put("d", i.declined).put("at", i.at)

    fun fromJson(o: JSONObject) = Intro(o.getString("id"), o.getString("by"), o.getString("k"), o.optString("n"), o.optString("p"),
        o.optBoolean("i"), o.optBoolean("t"), o.optBoolean("d"), o.optLong("at"))

    /** The id that names a group-member request: one per pair of keys and group, the same on both phones. */
    fun groupId(g: String, a: String, b: String): String {
        val keys = listOf(a, b).sorted()
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(("add|$g|${keys[0]}|${keys[1]}").toByteArray())
        return d.take(16).joinToString("") { "%02x".format(it) }
    }
}

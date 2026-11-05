package io.github.ravenor907.muninn.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "Always right away" list (local only): whose messages a low battery, quiet hours and checking less
 * never hold back. It starts as the Family folder and follows it; [added] are chats put on it by hand and
 * [removed] are Family chats taken off it (see [PowerLogic.rightAway]). Stored through [write] (the vault).
 */
class RightAway(private val read: () -> String?, private val write: (String) -> Unit) {
    data class State(val added: Set<String> = emptySet(), val removed: Set<String> = emptySet())

    private val _all = MutableStateFlow(load())
    val all: StateFlow<State> = _all.asStateFlow()

    private fun load(): State = try {
        val o = JSONObject(read() ?: return State())
        fun set(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        State(set("added"), set("removed"))
    } catch (e: Exception) { State() }

    /** The chats on the list, given the Family folder's [family]. */
    fun keys(family: Collection<String>): Set<String> = PowerLogic.rightAway(family, _all.value.added, _all.value.removed)

    /** Puts [key] on the list or takes it off; [inFamily]: it's in the Family folder. */
    @Synchronized
    fun set(key: String, on: Boolean, inFamily: Boolean) {
        val (a, r) = PowerLogic.setRightAway(key, on, inFamily, _all.value.added, _all.value.removed)
        val s = State(a, r)
        _all.value = s
        try { write(JSONObject().put("added", JSONArray(s.added.toList())).put("removed", JSONArray(s.removed.toList())).toString()) } catch (_: Exception) {}
    }
}

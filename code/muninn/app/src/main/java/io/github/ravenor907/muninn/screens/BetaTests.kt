package io.github.ravenor907.muninn.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.ui.Framed
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import org.json.JSONObject

/**
 * Labs › Things to test: the beta's checklist (code/shared/beta-tests.json, the same file as Huginn's, copied into
 * the APK's assets by the build). Ticks are kept on this device only and start again when the file's "beta" changes.
 */
object BetaTests {
    const val ASSET = "beta-tests.json"

    data class Check(val id: String, val text: String)
    data class Group(val id: String, val title: String, val note: String, val checks: List<Check>)
    data class Tests(val beta: String, val about: String, val groups: List<Group>) {
        val total: Int get() = groups.sumOf { it.checks.size }
    }

    /** The file, read; null when it can't be. */
    fun parse(json: String): Tests? = try {
        val o = JSONObject(json)
        val gs = o.getJSONArray("groups")
        Tests(o.optString("beta"), o.optString("about"), (0 until gs.length()).map { i ->
            val g = gs.getJSONObject(i)
            val cs = g.optJSONArray("checks")
            Group(g.optString("id"), g.optString("title"), g.optString("note"),
                (0 until (cs?.length() ?: 0)).map { j -> cs!!.getJSONObject(j).let { Check(it.optString("id"), it.optString("text")) } })
        })
    } catch (_: Exception) { null }

    /** The ticks still counted: only ones saved for this [beta] and for checks the file still has. */
    fun kept(savedBeta: String, saved: Set<String>, list: Tests): Set<String> {
        if (savedBeta != list.beta) return emptySet()
        val ids = list.groups.flatMap { g -> g.checks.map { it.id } }.toSet()
        return saved.filter { it in ids }.toSet()
    }

    /** What Report a problem starts with: "Beta test t1 Natural voices: ". */
    fun reportStart(g: Group) = "Beta test ${g.id} ${g.title}: "

    fun done(n: Int, total: Int) = "$n of $total done"

    @Volatile private var cached: Tests? = null
    fun load(ctx: Context): Tests? = cached ?: try {
        ctx.assets.open(ASSET).use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (_: Exception) { null }.also { cached = it }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("beta-tests", Context.MODE_PRIVATE)
    fun ticks(ctx: Context, list: Tests): Set<String> =
        prefs(ctx).let { kept(it.getString("beta", "") ?: "", it.getStringSet("done", emptySet()) ?: emptySet(), list) }
    fun save(ctx: Context, list: Tests, ticks: Set<String>) {
        prefs(ctx).edit().putString("beta", list.beta).putStringSet("done", HashSet(ticks)).apply()
    }
}

/** The "Things to test" section of the Labs page: shown only on the Pre-release channel. */
@Composable
internal fun ThingsToTest() {
    val ctx = LocalContext.current
    val list = remember { BetaTests.load(ctx) } ?: return
    var ticks by remember { mutableStateOf(BetaTests.ticks(ctx, list)) }
    var open by remember { mutableStateOf(setOf<String>()) }
    Framed("Things to test") {
        Text(BetaTests.done(ticks.size, list.total), style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 8.dp))
        if (list.about.isNotBlank()) Text(list.about, style = Type.small, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        list.groups.forEachIndexed { i, g ->
            val shown = g.id in open
            val n = g.checks.count { it.id in ticks }
            // One group per item, folded until it's opened (a D-pad moves onto it like any row).
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClickLabel = if (shown) "Hide" else "Show") { open = if (shown) open - g.id else open + g.id }
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. ${g.title}", style = Type.normal.copy(color = Palette.text))
                    Text("$n of ${g.checks.size}", style = Type.small)
                }
                MIcon("forward", Palette.textMuted, 18.dp, Modifier.rotate(if (shown) 90f else 0f))
            }
            if (shown) {
                if (g.note.isNotBlank()) Text(g.note, style = Type.small, modifier = Modifier.padding(bottom = 4.dp))
                g.checks.forEach { c ->
                    val on = c.id in ticks
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(on, role = Role.Checkbox) { ticks = if (it) ticks + c.id else ticks - c.id; BetaTests.save(ctx, list, ticks) }
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(on, onCheckedChange = null)
                        Text(c.text, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
                MButton("Report a problem", Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) { Labs.report(ctx, BetaTests.reportStart(g)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

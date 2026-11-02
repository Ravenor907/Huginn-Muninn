package io.github.ravenor907.muninn.tv

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A parent's phone's side of parent-approved installs on a Kids' TV (cap k9, 7.15): the TV's "Install app.apk?"
 * shows on the Kids' TV page (Apps) and as a notification; Install or Not now goes back to the TV,
 * and the TV's answer ("Installed", "Needs Install updates permission", "Failed: …") shows under it.
 */
object TvAppAsks {
    /** One app waiting on TV [tv]; [result] is the TV's last word on it ("" until Install was pressed). */
    data class Item(val tv: String, val ask: TvInstalls.Ask, val answered: Boolean = false, val result: String = "", val why: String = "")

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private const val FILE = "tv-app-asks.json"
    @Volatile private var loaded = false

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    private fun load(app: Muninn) {
        if (loaded) return
        loaded = true
        _items.value = try {
            val a = JSONArray(app.vault.read(file(app)) ?: "[]")
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val ao = o.optJSONObject("ask") ?: return@mapNotNull null
                val ask = TvInstalls.parseAsk(ao) ?: return@mapNotNull null
                Item(o.optString("tv"), ask.copy(at = ao.optLong("at")), o.optBoolean("answered"), o.optString("result"), o.optString("why"))
            }.filter { System.currentTimeMillis() - it.ask.at < TvInstalls.KEEP_MS }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(app: Muninn, l: List<Item>) {
        _items.value = l
        try {
            app.vault.write(file(app), JSONArray().apply {
                l.forEach { put(JSONObject().put("tv", it.tv).put("answered", it.answered).put("result", it.result).put("why", it.why)
                    .put("ask", JSONObject(TvInstalls.askMessage(it.ask)).put("at", it.ask.at))) }
            }.toString())
        } catch (_: Exception) {}
    }

    /** The apps waiting on [tv], newest first. */
    fun of(app: Muninn, tv: String): List<Item> { load(app); return _items.value.filter { it.tv == tv }.reversed() }

    /** Room tvconfig from a TV this phone looks after: "appask" or "appres". True when it was one of those. */
    fun handle(app: Muninn, tv: String, o: JSONObject): Boolean {
        load(app)
        when (o.optString("t")) {
            "appask" -> {
                val a = TvInstalls.parseAsk(o) ?: return true
                val now = System.currentTimeMillis()
                val rest = _items.value.filter { !(it.tv == tv && it.ask.h == a.h) && now - it.ask.at < TvInstalls.KEEP_MS }
                save(app, (rest + Item(tv, a.copy(at = now))).takeLast(40))
                notify(app, tv, a)
            }
            "appres" -> {
                val (h, r, why) = TvInstalls.parseResult(o) ?: return true
                val it = _items.value.firstOrNull { it.tv == tv && it.ask.h == h } ?: return true
                save(app, _items.value.map { x -> if (x === it) x.copy(answered = true, result = r, why = why) else x })
                Ui.say("${it.ask.name}: ${TvInstalls.resultText(r, why)}", r == "failed" || r == "perm")
            }
            else -> return false
        }
        return true
    }

    /** Install ([yes]) or Not now, from the Kids' TV menu. */
    fun answer(app: Muninn, item: Item, yes: Boolean) {
        load(app)
        try { Tv.bridge(app)?.sendTvRoom(item.tv, TvInstalls.answerMessage(item.ask.h, yes)) } catch (_: Exception) {}
        save(app, if (yes) _items.value.map { if (it === item) it.copy(answered = true, result = "") else it } else _items.value.filter { it !== item })
        try { NotificationManagerCompat.from(app).cancel(("app" + item.ask.h).hashCode()) } catch (_: Exception) {}
    }

    /** "Install app.apk on Kids' TV?": a tap opens Muninn, where Install and Not now are. No name without previews on. */
    private fun notify(app: Muninn, tv: String, a: TvInstalls.Ask) {
        val preview = app.identity.showPreview
        val open = PendingIntent.getActivity(app, ("app" + a.h).hashCode(), Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, Muninn.MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (preview) "Install ${a.name} on ${app.chat.nameOf(tv)}?" else "A Kids' TV asks to install an app")
            .setContentText("Install or Not now: Kids' TV settings, Apps")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(app).notify(("app" + a.h).hashCode(), n) } catch (_: SecurityException) {}
    }
}

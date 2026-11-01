package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.net.Proto06
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** shared/vectors/d1-vectors.json (cap d1: message limits, shared/docs/protocol.md section 16). */
class LimitsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("d1-vectors.json")!!.readText())

    @Test
    fun groupStatesWithTypeAndLimit() {
        val all = v.getJSONObject("states")
        for (k in all.keys()) {
            val x = all.getJSONObject(k)
            val s = Proto06.parseState(x.getJSONObject("state"))
            Proto06.check(s)
            assertEquals(k, x.getString("signed"), s.signedText())
            assertEquals(k, x.getString("hash"), s.hash)
            assertTrue(k, s.verifies())
            assertEquals(k, x.getInt("days"), Limits.groupDays(s))
        }
    }

    @Test
    fun badTypesAndLimitsAreRefused() {
        val all = v.getJSONObject("refuse")
        for (k in all.keys()) {
            try { Proto06.check(Proto06.parseState(all.getJSONObject(k))) } catch (e: IllegalArgumentException) { continue }
            fail("should refuse $k")
        }
    }

    @Test
    fun acceptingLimitChanges() {
        val st = v.getJSONObject("states")
        val a = v.getJSONArray("accept")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val cur = if (c.isNull("current")) null else Proto06.parseState(st.getJSONObject(c.getString("current")).getJSONObject("state"))
            val new = Proto06.parseState(st.getJSONObject(c.getString("new")).getJSONObject("state"))
            assertEquals(c.toString(), c.getBoolean("accept"), Proto06.accept(cur, new).first)
        }
    }

    @Test
    fun envelopeDays() {
        val a = v.getJSONArray("envelope")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val want = if (c.isNull("days")) null else c.getInt("days")
            assertEquals(c.toString(), want, Limits.envelopeDays(c.getJSONObject("o")))
        }
    }

    @Test
    fun myLimitAndTheShorterOfTwo() {
        val m = v.getJSONArray("mine")
        for (i in 0 until m.length()) {
            val c = m.getJSONObject(i)
            assertEquals(c.toString(), c.getInt("days"), Limits.myDays(if (c.isNull("chosen")) null else c.getInt("chosen"), c.getBoolean("close")))
        }
        val p = v.getJSONArray("pair")
        for (i in 0 until p.length()) {
            val c = p.getJSONObject(i)
            assertEquals(c.toString(), c.getInt("days"), Limits.pairDays(c.getInt("mine"), c.getBoolean("d1"), if (c.isNull("theirs")) null else c.getInt("theirs")))
        }
    }

    @Test
    fun sweepKeepsWhatIsYoungEnough() {
        val a = v.getJSONArray("sweep")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val cut = Limits.cutoff(c.getLong("now"), c.getInt("days"))
            val t = c.getJSONArray("times")
            val k = c.getJSONArray("kept")
            for (j in 0 until t.length()) assertEquals(c.toString(), k.getBoolean(j), t.getLong(j) >= cut)
        }
    }

    @Test
    fun capsSayD1() = assertTrue(Limits.CAP in Proto06.caps(Proto06.CAPS))
}

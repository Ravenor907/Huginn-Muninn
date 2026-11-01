package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.screens.SetupFlow
import io.github.ravenor907.muninn.screens.SetupFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** First setup: which pages show, moving through them, and practice never saving anything. */
class SetupFlowTest {
    private fun facts(practice: Boolean = false, lock: Boolean = false, name: Boolean = false, allow: Boolean = true, tablet: Boolean = false) =
        SetupFlow.Facts(practice, lock, name, allow, tablet)

    @Test
    fun freshPhoneShowsEveryPage() {
        assertEquals(listOf(Step.WELCOME, Step.LOCK, Step.NAME, Step.ALLOW, Step.PEOPLE, Step.TOUR, Step.DONE), SetupFlow.steps(facts()))
    }

    @Test
    fun whatsSetIsNotAskedAgain() {
        val s = SetupFlow.steps(facts(lock = true, name = true, allow = false))
        assertEquals(listOf(Step.WELCOME, Step.PEOPLE, Step.TOUR, Step.DONE), s)
    }

    @Test
    fun tabletWithNothingLinkedGetsLinkPage() {
        val s = SetupFlow.steps(facts(tablet = true))
        assertEquals(s.indexOf(Step.PEOPLE) + 1, s.indexOf(Step.LINK))
        assertFalse(Step.LINK in SetupFlow.steps(facts(tablet = false)))
    }

    @Test
    fun practiceShowsEveryPageEvenWhenSet() {
        val s = SetupFlow.steps(facts(practice = true, lock = true, name = true, allow = false))
        assertTrue(s.containsAll(listOf(Step.LOCK, Step.NAME, Step.ALLOW)))
    }

    @Test
    fun optionalPagesHaveSkip() {
        assertFalse(Step.WELCOME.optional); assertFalse(Step.DONE.optional)
        listOf(Step.LOCK, Step.NAME, Step.ALLOW, Step.PEOPLE, Step.LINK, Step.TOUR).forEach { assertTrue(it.name, it.optional) }
    }

    @Test
    fun startsOnlyOnFreshInstallsOrToFinish() {
        fun start(tv: Boolean = false, ci: Boolean = false, started: Boolean = false, done: Boolean = false, lock: Boolean = false,
                  name: Boolean = false, notice: Boolean = false, contacts: Boolean = false) =
            SetupFlow.shouldStart(tv, ci, started, done, lock, name, notice, contacts)
        assertTrue(start())
        assertTrue(start(started = true, lock = true, name = true, notice = true))   // a restart part way through
        assertFalse(start(done = true, started = true))
        assertFalse(start(tv = true))
        assertFalse(start(ci = true))
        // Existing users never see it.
        assertFalse(start(lock = true))
        assertFalse(start(name = true))
        assertFalse(start(notice = true))
        assertFalse(start(contacts = true))
    }

    @Test
    fun runMovesAndCountsDots() {
        val r = SetupFlow.Run(false, SetupFlow.steps(facts()))
        assertTrue(r.first); assertFalse(r.back())
        assertEquals(6, r.dots)   // 7 pages, the tour has no dot
        while (r.step != Step.TOUR) assertTrue(r.next())
        val dotAtTour = r.dot
        assertTrue(r.next()); assertEquals(Step.DONE, r.step)
        assertEquals(dotAtTour + 1, r.dot); assertEquals(r.dots - 1, r.dot)
        assertTrue(r.last); assertFalse(r.next())
        assertTrue(r.back()); assertEquals(Step.TOUR, r.step)
    }

    @Test
    fun tourHasFiveShortStops() {
        assertEquals(listOf("plus", "menu", "row", "settings", "files"), SetupFlow.TOUR.map { it.spot })
        SetupFlow.TOUR.forEach { assertTrue(it.text.length <= 70); assertFalse(it.text.contains('\u2014')) }
    }

    private class Recording : SetupFlow.Store {
        val calls = mutableListOf<String>()
        override fun markStarted() { calls += "started" }
        override fun markDone() { calls += "done" }
        override fun noticeSeen() { calls += "notice" }
        override fun saveName(name: String) { calls += "name:$name" }
        override fun nameAsked() { calls += "asked" }
        override fun skipLock() { calls += "skipLock" }
        override fun myCode(): String { calls += "code"; return "real" }
        override fun inviteLink(): String { calls += "link"; return "real-link" }
    }

    private fun everything(w: SetupFlow.Writes): Pair<String, String> {
        w.markStarted(); w.noticeSeen(); w.skipLock(); w.saveName("Ravenor"); w.nameAsked(); w.markDone()
        return w.myCode() to w.inviteLink()
    }

    @Test
    fun practiceNeverWrites() {
        val store = Recording()
        val w = SetupFlow.Writes(true, store)
        val (code, link) = everything(w)
        assertTrue(store.calls.isEmpty())
        assertFalse(w.live)
        assertEquals(SetupFlow.SAMPLE, code); assertEquals(SetupFlow.SAMPLE, link)
    }

    @Test
    fun realRunWritesThrough() {
        val store = Recording()
        val w = SetupFlow.Writes(false, store)
        val (code, link) = everything(w)
        assertTrue(w.live)
        assertEquals(listOf("started", "notice", "skipLock", "name:Ravenor", "asked", "done", "code", "link"), store.calls)
        assertEquals("real", code); assertEquals("real-link", link)
    }
}

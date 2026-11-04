package io.github.ravenor907.muninn.screens

/**
 * First setup on a fresh phone or tablet (never a Kids' TV): which pages show, in what order, and everything the
 * pages may save. Labs › Replay first setup runs the same pages as practice: [Writes] then saves nothing.
 * No Android here, so the tests can run it.
 */
object SetupFlow {
    /** One page. [optional]: it has Skip. */
    enum class Step(val optional: Boolean) {
        WELCOME(false), PRIVACY(false), LOCK(true), NAME(true), ALLOW(true), PEOPLE(true), LINK(true), TOUR(true), DONE(false)
    }

    /**
     * What's already true on this device. [lockChosen]: a lock is set or was skipped; [nameAsked]: the name was asked;
     * [needsAllow]: a permission on the Allow page isn't allowed yet; [tabletUnlinked]: a tablet with nothing linked
     * (offered "Link your phone" once).
     */
    class Facts(val practice: Boolean, val lockChosen: Boolean, val nameAsked: Boolean, val needsAllow: Boolean, val tabletUnlinked: Boolean)

    /** The pages, in order. Practice shows every page; a real run leaves out what's already set, so nothing is asked twice. */
    fun steps(f: Facts): List<Step> = buildList {
        add(Step.WELCOME)
        // 1.42: "Your privacy, built in", what protects every chat and that Ultra privacy is there (off).
        add(Step.PRIVACY)
        if (f.practice || !f.lockChosen) add(Step.LOCK)
        if (f.practice || !f.nameAsked) add(Step.NAME)
        if (f.practice || f.needsAllow) add(Step.ALLOW)
        add(Step.PEOPLE)
        if (f.tabletUnlinked) add(Step.LINK)
        add(Step.TOUR)
        add(Step.DONE)
    }

    /**
     * Whether first setup runs now: started and not finished (it picks up again after a restart), or a fresh install
     * (no lock chosen, no name asked, no notice seen, no contacts). Never on a Kids' TV or in a CI screenshot run.
     */
    fun shouldStart(kidsTv: Boolean, ci: Boolean, started: Boolean, done: Boolean, lockChosen: Boolean, nameAsked: Boolean,
                    noticeSeen: Boolean, hasContacts: Boolean): Boolean =
        !kidsTv && !ci && !done && (started || (!lockChosen && !nameAsked && !noticeSeen && !hasContacts))

    /** One run: the pages and where it is. */
    class Run(val practice: Boolean, val steps: List<Step>, start: Int = 0) {
        var index = start.coerceIn(0, steps.lastIndex)
            private set
        val step: Step get() = steps[index]
        val first: Boolean get() = index == 0
        val last: Boolean get() = index == steps.lastIndex
        /** The progress dots: the tour is shown on the real screen, so it has no dot of its own. */
        val dots: Int get() = steps.count { it != Step.TOUR }
        val dot: Int get() = steps.take(index + 1).count { it != Step.TOUR } - 1
        /** Next page (Next, Skip, Got it on the last tour stop). False on the last page. */
        fun next(): Boolean { if (last) return false; index++; return true }
        /** Back a page. False on the first. */
        fun back(): Boolean { if (first) return false; index--; return true }
    }

    /** One stop on the tour: [spot] names what it points at on the main screen. */
    class Stop(val spot: String, val text: String, val missing: String = text)

    val TOUR = listOf(
        Stop("plus", "Tap + to message someone, add someone or make a group."),
        Stop("menu", "The ⋮ next to search has more for your chat list."),
        Stop("row", "Press and hold a chat for its options.", "Once you have chats, press and hold one for its options."),
        Stop("settings", "This symbol, top right, opens Settings."),
        Stop("files", "Share nearby sends files to phones close by. Chats have + for photos."),
    )

    /** Everything first setup can change. */
    interface Store {
        fun markStarted()
        fun markDone()
        fun noticeSeen()
        fun saveName(name: String)
        fun nameAsked()
        fun skipLock()
        /** A code for my QR, good for an hour (adds an invite token). */
        fun myCode(): String
        /** A one-time link to share (adds an invite token). */
        fun inviteLink(): String
    }

    /** The sample code practice shows instead of a real one: it adds no one. */
    const val SAMPLE = "PRACTICE-SAMPLE-CODE"

    /** What the pages call. In practice nothing reaches [store]: the codes are the sample and every save is dropped. */
    class Writes(val practice: Boolean, private val store: Store) : Store {
        override fun markStarted() { if (!practice) store.markStarted() }
        override fun markDone() { if (!practice) store.markDone() }
        override fun noticeSeen() { if (!practice) store.noticeSeen() }
        override fun saveName(name: String) { if (!practice) store.saveName(name) }
        override fun nameAsked() { if (!practice) store.nameAsked() }
        override fun skipLock() { if (!practice) store.skipLock() }
        override fun myCode(): String = if (practice) SAMPLE else store.myCode()
        override fun inviteLink(): String = if (practice) SAMPLE else store.inviteLink()
        /** Android's permission questions, the lock, the picture, scanning and linking: real runs only. */
        val live: Boolean get() = !practice
    }
}

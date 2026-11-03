package io.github.ravenor907.muninn.tv

/**
 * Round 17: two parents changing a Kids' Device at the same moment. Both phones sign the same version; the device runs
 * the one with the lower hash (7.2). The phone whose config lost doesn't tell its person to make the change again: it puts
 * its own change ([mine] against [base], what the device ran before it) on top of the winner ([theirs]) and sends that as
 * the next version, so both changes end up on the device. Free of Android for the unit tests.
 *
 * Also the parent side of "removals win" (7.18): the people and parents a parent app with k11 took off, which an older
 * parent app's config can't bring back.
 */
object KidMerge {
    /** [mine]'s value where this phone changed it from [base], else [theirs]. */
    private fun <T> pick(base: T, mine: T, theirs: T): T = if (mine != base) mine else theirs

    /** A set as a list: [theirs], plus what this phone added, minus what it took off (order kept). */
    fun <T> setMerge(base: List<T>, mine: List<T>, theirs: List<T>): List<T> {
        val added = mine.filter { it !in base }
        val removed = base.filter { it !in mine }.toSet()
        return (theirs + added).distinct().filter { it !in removed }
    }

    /** A map: [theirs], with what this phone set or changed, minus what it took out. */
    fun <K, V> mapMerge(base: Map<K, V>, mine: Map<K, V>, theirs: Map<K, V>): Map<K, V> {
        val out = theirs.toMutableMap()
        for ((k, v) in mine) if (base[k] != v) out[k] = v
        for (k in base.keys) if (k !in mine) out.remove(k)
        return out
    }

    /** Both changes: [theirs] (what the device runs now) with this phone's own change from [base] to [mine] on top. */
    fun merge(base: ManagedTv, mine: ManagedTv, theirs: ManagedTv): ManagedTv {
        val reach = setMerge(base.reach, mine.reach, theirs.reach).take(TvRules.MAX_CONTACTS - 1)
        val micMine = mine.mic != base.mic
        return theirs.copy(
            quick = pick(base.quick, mine.quick, theirs.quick),
            reach = reach,
            others = (theirs.others + mine.others.filter { m -> theirs.others.none { it.k == m.k } }).filter { it.k in reach },
            dictation = pick(base.dictation, mine.dictation, theirs.dictation),
            parents = setMerge(base.parents, mine.parents, theirs.parents).ifEmpty { theirs.parents },
            calls = pick(base.calls, mine.calls, theirs.calls),
            mic = if (micMine) mine.mic else theirs.mic, micAt = if (micMine) mine.micAt else theirs.micAt,
            micOnTv = if (micMine) mine.micOnTv else theirs.micOnTv,
            nr = pick(base.nr, mine.nr, theirs.nr), upd = pick(base.upd, mine.upd, theirs.upd), keep = pick(base.keep, mine.keep, theirs.keep),
            chat = pick(base.chat, mine.chat, theirs.chat), quiet = pick(base.quiet, mine.quiet, theirs.quiet), pin = pick(base.pin, mine.pin, theirs.pin),
            hav = pick(base.hav, mine.hav, theirs.hav), havw = pick(base.havw, mine.havw, theirs.havw), havx = pick(base.havx, mine.havx, theirs.havx),
            names = mapMerge(base.names, mine.names, theirs.names), tz = pick(base.tz, mine.tz, theirs.tz),
            look = pick(base.look, mine.look, theirs.look), season = pick(base.season, mine.season, theirs.season),
            accent = pick(base.accent, mine.accent, theirs.accent), lookPick = pick(base.lookPick, mine.lookPick, theirs.lookPick),
            child = pick(base.child, mine.child, theirs.child), access = pick(base.access, mine.access, theirs.access),
            gok = setMerge(base.gok, mine.gok, theirs.gok), gno = setMerge(base.gno, mine.gno, theirs.gno),
            waitKid = mapMerge(base.waitKid, mine.waitKid, theirs.waitKid),
            myName = pick(base.myName, mine.myName, theirs.myName),
            goneC = (theirs.goneC + mine.goneC).distinct().takeLast(KidRules.MAX_GONE),
            goneP = (theirs.goneP + mine.goneP).distinct().takeLast(KidRules.MAX_GONE),
        )
    }

    /**
     * Whose config the device keeps when two parents sign the same version (7.2): the lower hash. True when the one this
     * phone signed ([mine]) loses to [theirs].
     */
    fun lost(mine: String, theirs: String): Boolean = theirs < mine

    /** Keys taken off ([old] to [new]) join [gone]; keys listed again leave it. */
    fun gone(old: List<String>, new: List<String>, gone: List<String>): List<String> =
        (gone + old.filter { it !in new }).distinct().filter { it !in new }.takeLast(KidRules.MAX_GONE)
}

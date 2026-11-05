package io.github.ravenor907.muninn.update

/**
 * Where a version sorts (shared/docs/releases.md, "Pre-releases"). A stable version has one dot and reads as a decimal:
 * 0.3 < 0.31 < 0.4 < 1.0. A pre-release is labelled "<version> beta <n>" and sits below that version and above every
 * version before it; betas of one version sort by n. So 0.98 < 0.99 beta 3 < 0.99 beta 7 < 0.99.
 * A test build ("0.3 Test 1") sorts like a beta. Anything else sorts below everything.
 */
object Versions {
    private val FORM = Regex("""^(\d+)\.(\d+)(?: (?:beta|Test) (\d+))?$""")
    private val BETA = Regex("""^\d+\.\d+ beta \d+$""")

    /** A sortable key: (number, 1 for stable or 0 for a beta, beta number). */
    data class Key(val num: Long, val stable: Int, val n: Long) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, { it.num }, { it.stable }, { it.n })
    }

    private val LOWEST = Key(-1, 0, 0)

    fun key(v: String): Key {
        val m = FORM.find(v.trim()) ?: return LOWEST
        val (a, b, n) = m.destructured
        val num = try { a.toLong() * 1_000_000 + (b + "000000").take(6).toLong() } catch (e: NumberFormatException) { return LOWEST }
        return if (n.isEmpty()) Key(num, 1, 0) else Key(num, 0, n.toLongOrNull() ?: return LOWEST)
    }

    /** Below zero when [a] is older than [b], zero when they sort the same, above zero when newer. */
    fun compare(a: String, b: String): Int = key(a).compareTo(key(b))

    fun newer(a: String, than: String) = compare(a, than) > 0

    /** A pre-release label, "0.99 beta 12". */
    fun isBeta(v: String) = BETA.matches(v.trim())

    /** A stable version, "0.98". */
    fun isStable(v: String) = Regex("""^\d+\.\d+$""").matches(v.trim())
}

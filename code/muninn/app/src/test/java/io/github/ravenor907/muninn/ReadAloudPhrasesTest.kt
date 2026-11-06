package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ReadAloudText
import io.github.ravenor907.muninn.net.ReadAloudText.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read aloud 1.43: the text before the voice (quotes, dots, abbreviations, shorthand, times, numbers, a lone emoji), the
 * phrases and our own pauses, Cadence, the natural voices' respellings and tuning presets. Huginn's readaloud.cpp
 * self-test checks the same cases.
 */
class ReadAloudPhrasesTest {
    private fun texts(p: List<Phrase>) = p.map { it.text }
    private fun pauses(p: List<Phrase>) = p.map { it.pause }

    @Test fun quotesAndDots() {
        assertEquals("It's \"fine\"", ReadAloudText.normalize("It’s “fine”"))
        assertEquals("'yes'", ReadAloudText.normalize("‘yes’"))
        // ".." is one full stop, never read as "dot".
        assertEquals("Okay. See you", ReadAloudText.normalize("Okay.. See you"))
        // "..." and "…" are a pause, never words.
        val p = ReadAloudText.phrases("Well... maybe… yes", natural = false)
        assertEquals(listOf("Well", "maybe", "yes"), texts(p))
        assertEquals(listOf(ReadAloudText.PAUSE_ELLIPSIS, ReadAloudText.PAUSE_ELLIPSIS, 0), pauses(p))
        assertTrue(p.none { it.text.contains('.') || it.text.contains('\u0001') })
    }

    @Test fun ourOwnPauses() {
        val p = ReadAloudText.phrases("Hello there, friend. How are you?\nFine!", natural = false)
        assertEquals(listOf("Hello there,", "friend.", "How are you?", "Fine!"), texts(p))
        assertEquals(listOf(250, 500, 500, 0), pauses(p))
        // A line break without a full stop: 400.
        val q = ReadAloudText.phrases("one line\nanother line", natural = false)
        assertEquals(listOf(400, 0), pauses(q))
        // A full stop at a line's end keeps its longer pause.
        assertEquals(listOf(500, 0), pauses(ReadAloudText.phrases("Done.\nNext", natural = false)))
        assertEquals(250, ReadAloudText.PAUSE_COMMA)
        assertEquals(500, ReadAloudText.PAUSE_STOP)
        assertEquals(400, ReadAloudText.PAUSE_LINE)
        assertEquals(900, ReadAloudText.PAUSE_MEANS)
        assertEquals(700, ReadAloudText.PAUSE_MESSAGE)
        // Marks alone never make a phrase.
        assertEquals(listOf("Wait!"), texts(ReadAloudText.phrases("Wait! !!", natural = false)))
        assertTrue(ReadAloudText.phrases("", natural = false).isEmpty())
    }

    @Test fun cadenceScalesPausesOnly() {
        assertEquals(100f, ReadAloudText.cadenceOf(null), 0f)
        assertEquals(50f, ReadAloudText.cadenceOf(10), 0f)
        assertEquals(200f, ReadAloudText.cadenceOf(500f), 0f)
        assertEquals(100f, ReadAloudText.cadenceOf(Float.NaN), 0f)
        assertEquals("150%", ReadAloudText.cadenceText(150f))
        assertEquals(500, ReadAloudText.pauseMs(500, 100f))
        assertEquals(250, ReadAloudText.pauseMs(500, 50f))
        assertEquals(1000, ReadAloudText.pauseMs(500, 200f))
        assertEquals(1000, ReadAloudText.pauseMs(500, 900f))
        // The silence is added after the words, never stretching them.
        val words = floatArrayOf(0.5f, -0.5f)
        val out = ReadAloudText.withPause(words, 1000, ReadAloudText.pauseMs(250, 200f))
        assertEquals(502, out.size)
        assertEquals(0.5f, out[0], 0f)
        assertTrue(out.drop(2).all { it == 0f })
        assertEquals(2, ReadAloudText.withPause(words, 1000, 0).size)
    }

    @Test fun silenceAtTheEndsIsTrimmed() {
        val rate = 1000
        val s = FloatArray(200) + floatArrayOf(0.3f, 0.2f) + FloatArray(200)
        val t = ReadAloudText.trimSilence(s, rate)
        // 30 ms kept each side.
        assertEquals(62, t.size)
        assertEquals(0, ReadAloudText.trimSilence(FloatArray(100), rate).size)
    }

    @Test fun longSentencesSplitAtNaturalBreaks() {
        val long = "We walked all the way down to the old river in the morning light because the boats were coming in from the sea that day"
        val p = ReadAloudText.phrases(long, natural = false)
        assertEquals(2, p.size)
        assertTrue(p[1].text.startsWith("because"))
        assertEquals(ReadAloudText.PAUSE_COMMA, p[0].pause)
        val semi = "We walked all the way down to the old river in the morning light; the boats were coming in from the sea that day"
        assertTrue(ReadAloudText.phrases(semi, natural = false).size >= 2)
        // A short one stays whole.
        assertEquals(1, ReadAloudText.phrases("I stayed home because it rained", natural = false).size)
    }

    @Test fun numbersTimesAndShorthand() {
        assertEquals("three thirty", ReadAloudText.normalize("3:30"))
        assertEquals("Meet at three oh five", ReadAloudText.normalize("Meet at 3:05"))
        assertEquals("three o'clock", ReadAloudText.normalize("3:00"))
        assertEquals("seven pee em", ReadAloudText.normalize("7:00 pm"))
        assertEquals("ten fifteen ay em", ReadAloudText.normalize("10:15am"))
        assertEquals("fifteen hundred", ReadAloudText.normalize("15:00"))
        assertEquals("I have twenty three apples", ReadAloudText.normalize("I have 23 apples"))
        assertEquals("one thousand two hundred fifty", ReadAloudText.normalize("1,250"))
        assertEquals("three point five", ReadAloudText.normalize("3.5"))
        assertEquals("nineteen twenty three", ReadAloudText.normalize("1923"))
        assertEquals("two thousand seven", ReadAloudText.normalize("2007"))
        assertEquals("the first and the twenty second", ReadAloudText.normalize("the 1st and the 22nd"))
        assertEquals("zero zero seven", ReadAloudText.normalize("007"))
        assertEquals("It's fifteen dollars", ReadAloudText.normalize("It's $15"))
        assertEquals("one pound fifty", ReadAloudText.normalize("\u00A31.50"))
        assertEquals("one hundred", ReadAloudText.numberWords(100))
        assertEquals("two million five", ReadAloudText.numberWords(2_000_005))
        assertEquals("ninetieth", ReadAloudText.ordinalWords(90))
        assertEquals("laughing out loud, be right back", ReadAloudText.normalize("lol, brb"))
        assertEquals("I don't know. on my way", ReadAloudText.normalize("IDK. omw"))
        assertEquals("to be honest in my opinion thank you no problem", ReadAloudText.normalize("tbh imo ty np"))
        // Inside a word, nothing changes.
        assertEquals("lollipop typing", ReadAloudText.normalize("lollipop typing"))
    }

    @Test fun abbreviations() {
        assertEquals("Doctor Lee and Mister Smith met Missus Jones", ReadAloudText.normalize("Dr. Lee and Mr. Smith met Mrs. Jones"))
        assertEquals("Saint Olaf lived on Main Street.", ReadAloudText.normalize("St. Olaf lived on Main St."))
        // A title's dot never ends a sentence; "etc." at the end keeps one.
        assertEquals(listOf("Doctor Lee is here."), texts(ReadAloudText.phrases("Dr. Lee is here.", natural = false)))
        assertEquals("Apples, pears et cetera. Then more", ReadAloudText.normalize("Apples, pears etc. Then more"))
        assertEquals("for example this, that is that", ReadAloudText.normalize("e.g. this, i.e. that"))
    }

    @Test fun aLoneEmojiIsNamed() {
        assertEquals("smiling face", ReadAloudText.loneEmoji("🙂"))
        assertEquals("thumbs up", ReadAloudText.loneEmoji(" 👍🏽 "))
        assertEquals("red heart", ReadAloudText.loneEmoji("❤️"))
        assertEquals(listOf("smiling face"), texts(ReadAloudText.phrases("😊", natural = false)))
        // Two emoji, or one beside words, are dropped as before.
        assertNull(ReadAloudText.loneEmoji("😀😀"))
        assertNull(ReadAloudText.loneEmoji("Hi 😀"))
        assertEquals(listOf("Hi"), texts(ReadAloudText.phrases("Hi 😀", natural = false)))
    }

    @Test fun respellingsOnlyForTheNaturalVoices() {
        val text = "Muninn and Huginn read the Hávamál ere Othin spoke to Loddfafnir."
        val natural = texts(ReadAloudText.phrases(text, natural = true)).joinToString(" ")
        assertEquals("Moo-nin and Hoo-gin read the Hah-vah-mawl air Oh-thin spoke to Lodd-fahv-neer.", natural)
        val device = texts(ReadAloudText.phrases(text, natural = false)).joinToString(" ")
        assertEquals(text, device)
        assertEquals("Moo-nin's", ReadAloudText.respell("Muninn's"))
        // Only whole words: "here" and "where" keep their "ere".
        assertEquals("here and where", ReadAloudText.respell("here and where"))
        assertEquals("Air long", ReadAloudText.respell("Ere long"))
        // The engine still gets only its safe letters.
        assertEquals("Hah-vah-mawl", ReadAloudText.forEngine(ReadAloudText.respell("Hávamál")))
    }

    @Test fun aVerseThenWhatItMeans() {
        val verse = "Fire he needs who with frozen knees,\nHas come from the cold without."
        val means = "Someone who comes in from the cold needs a warm fire.\nHelp them get warm and fed."
        val p = ReadAloudText.verse(verse, means, natural = false)
        val i = p.indexOfFirst { it.text == ReadAloudText.MEANS }
        assertTrue(i > 0)
        // The verse's last phrase, then 900 ms, then "What it means".
        assertEquals(ReadAloudText.PAUSE_MEANS, p[i - 1].pause)
        assertEquals("Has come from the cold without.", p[i - 1].text)
        // Each line of the meaning a pause; no doubled dots anywhere.
        assertEquals(listOf("Someone who comes in from the cold needs a warm fire.", "Help them get warm and fed."), texts(p.drop(i + 1)))
        assertEquals(ReadAloudText.PAUSE_STOP, p[i + 1].pause)
        assertEquals(0, p.last().pause)
        assertFalse(p.any { it.text.contains("..") })
        // Two messages: 700 ms between.
        val m = ReadAloudText.messages(listOf("Hi", "Bye"), natural = false)
        assertEquals(listOf(Phrase("Hi", 700), Phrase("Bye", 0)), m)
    }

    @Test fun tuningPresets() {
        assertEquals(listOf("A", "B", "C", "D", "E"), ReadAloudText.TUNES.map { it.name })
        val a = ReadAloudText.tuneOf(null)
        assertEquals("A", a.name)
        assertEquals(0.667f, a.noise, 0f)
        assertEquals(0.8f, a.width, 0f)
        assertEquals(ReadAloudText.Tune("D", 0.5f, 1.2f), ReadAloudText.tuneOf("D"))
        assertEquals("A", ReadAloudText.tuneOf("Z").name)
        assertEquals("B: 0.33 / 0.4", ReadAloudText.tuneText(ReadAloudText.tuneOf("B")))
    }
}

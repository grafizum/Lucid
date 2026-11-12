package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the lens reads when a message holds more than one script.
 *
 * The rule under test: the script holding most of the message owns the whole
 * message, so a small token of another script in the middle (`8pm`) travels
 * with it — while a genuine minority under the lens (one foreign word in
 * someone else's sentence) is still narrowed to that word alone.
 */
class TextRunTest {

    /** Reads [text] with the lens sitting on its character at [index]. */
    private fun hover(text: String, index: Int): TextRun.Focus =
        TextRun.at(text, index, text.indices)

    /* ── the message is in one language: it is read whole ────────────── */

    @Test
    fun `japanese message with 8pm inside is read whole`() {
        val text = "明日8pmに来る"
        // The lens on the first character: the Latin token must not split it.
        val focus = hover(text, 0)
        assertEquals(text, focus.text)
        assertFalse(focus.narrowed)
    }

    @Test
    fun `japanese message with 8pm inside is read whole when hovering after it`() {
        val text = "明日8pmに来る"
        // On a character after the token, the message is still the unit.
        val focus = hover(text, text.indexOf('来'))
        assertEquals(text, focus.text)
    }

    @Test
    fun `latin token in the middle does not cut the japanese run`() {
        val text = "これを8pmまでやる"
        assertEquals(text, hover(text, 0).text)
        assertEquals(text, hover(text, text.length - 1).text)
    }

    @Test
    fun `question mark travels with a majority message`() {
        val text = "彼は8pmに来ます？"
        val focus = hover(text, 0)
        assertEquals(text, focus.text)
        assertTrue(focus.text.endsWith("？"))
    }

    /* ── the lens is on a minority: only that word is read ────────────── */

    @Test
    fun `english sentence reads whole however much japanese sits inside`() {
        val text = "I go to 日本語 every day"
        // Anywhere on the English: the whole sentence, Japanese word and all.
        assertEquals(text, hover(text, 0).text)
        assertEquals(text, hover(text, text.length - 1).text)
    }

    @Test
    fun `hovering the lone japanese word reads only that word`() {
        val text = "I go to 日本語 every day"
        val focus = hover(text, text.indexOf('日'))
        assertEquals("日本語", focus.text)
        assertTrue(focus.narrowed)
        assertEquals(text.indexOf('日'), focus.start)
        assertEquals(text.indexOf('日') + 3, focus.end)
    }

    @Test
    fun `hovering a lone english word in a japanese message reads only that word`() {
        // 5 Japanese letters vs 2 Latin: Japanese owns the message, so the
        // English word under the lens is narrowed to itself.
        val text = "これはhiの話"
        val focus = hover(text, text.indexOf('h'))
        assertEquals("hi", focus.text)
        assertTrue(focus.narrowed)
    }

    @Test
    fun `whichever script out-votes the other owns the message`() {
        // 8 Latin letters vs 5 Japanese: Latin owns it, so the lens on the
        // Latin word asks for the whole message…
        val text = "これはtomorrowの話"
        assertEquals(text, hover(text, text.indexOf('t')).text)
        // …and the lens on the out-voted Japanese narrows to its own stretch.
        assertEquals("これは", hover(text, text.indexOf('は')).text)
    }

    @Test
    fun `a dead-even split narrows on both sides rather than swallowing both`() {
        // 5 Japanese letters, 5 Latin: neither side out-votes the other, so
        // each is read as its own run instead of one mixed message.
        val text = "東京とTokyoの間"
        assertEquals("東京と", hover(text, text.indexOf('東')).text)
        assertEquals("Tokyo", hover(text, text.indexOf('T')).text)
    }

    /* ── one script only: unchanged behaviour ─────────────────────────── */

    @Test
    fun `a single-language message is read whole`() {
        val text = "日本語を勉強しています。"
        assertEquals(text, hover(text, 0).text)
        assertFalse(hover(text, 0).narrowed)
    }

    @Test
    fun `digits and punctuation never pick the language on their own`() {
        // Lens on `8`: the nearest script decides, and Japanese holds the vote.
        val text = "明日8時に来る"
        assertEquals(text, hover(text, text.indexOf('8')).text)
    }

    @Test
    fun `empty text returns the whole thing untouched`() {
        val focus = TextRun.at("", 0, IntRange.EMPTY)
        assertEquals("", focus.text)
        assertFalse(focus.narrowed)
    }
}

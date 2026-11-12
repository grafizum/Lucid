package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where each kanji block's reading comes from, and over which characters it is
 * drawn.
 *
 * The engine supplies one reading for a whole line, so the alignment is what
 * decides the kana above the card's source text. Anything it cannot place is
 * dropped rather than guessed at.
 */
class FuriganaTest {

    /** The kana the card would draw, from an engine reading in romaji. */
    private fun rubies(text: String, reading: String): List<Furigana.Ruby> {
        val kana = Furigana.kana(reading) ?: return emptyList()
        return Furigana.of(text, kana)
    }

    private fun draw(text: String, reading: String): List<String> =
        rubies(text, reading).map { "${text.substring(it.start, it.end)}=${it.reading}" }

    @Test
    fun `one kanji block carries its own reading`() {
        assertEquals(
            listOf("東京=とうきょう", "行=い"),
            draw("東京に行きます", "Tōkyō ni ikimasu"),
        )
    }

    @Test
    fun `neighbouring kanji share one reading instead of being sliced by size`() {
        // The engine says how 日本語 sounds, not how much of にほんご belongs to
        // 日本 — so the whole block is pronounced at once.
        assertEquals(
            listOf("日本語=にほんご", "勉強=べんきょう"),
            draw("日本語を勉強しています", "Nihongo o benkyō shite imasu"),
        )
    }

    @Test
    fun `particles are matched as they are spoken`() {
        assertEquals(
            listOf("私=わたし", "学生=がくせい"),
            draw("私は学生です", "Watashi wa gakusei desu"),
        )
    }

    @Test
    fun `katakana in the source does not stop the reading`() {
        // コーヒー is written in katakana and the reading spells it in hiragana;
        // the alignment has to survive that, or every kanji after it is lost.
        assertEquals(
            listOf("飲=の"),
            draw("コーヒーを飲む", "Kōhī o nomu"),
        )
    }

    @Test
    fun `a reading the engine sends as kana is used as it is`() {
        assertEquals(
            listOf("今日=きょう", "晴=は"),
            draw("今日は晴れです", "きょうははれです"),
        )
    }

    @Test
    fun `a line with no kanji gets no ruby`() {
        assertTrue(rubies("こんにちは", "konnichiwa").isEmpty())
    }

    @Test
    fun `the reading never covers a character the line does not have`() {
        val text = "今日は晴れです"
        for (ruby in rubies(text, "kyō wa hare desu")) {
            assertTrue("${ruby.start}..${ruby.end} inside the line", ruby.end <= text.length)
            assertTrue(ruby.start < ruby.end)
            assertTrue("reading is not empty", ruby.reading.isNotEmpty())
        }
    }
}

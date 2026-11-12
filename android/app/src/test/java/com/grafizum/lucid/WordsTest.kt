package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word-by-word rules: where a Japanese line is cut into words, and when a
 * set of meanings may be shown at all.
 */
class WordsTest {

    @Test
    fun `cuts a japanese line at its words, in reading order`() {
        assertEquals(
            listOf("日本語", "勉強しています"),
            Words.terms("日本語を勉強しています。", "ja"),
        )
    }

    @Test
    fun `keeps katakana words whole`() {
        assertEquals(
            listOf("コーヒー", "飲みます"),
            Words.terms("コーヒーを飲みます", "ja"),
        )
    }

    @Test
    fun `a particle after a noun does not join the noun`() {
        // 東京から is 東京 + から; showing a meaning for 東京から would be wrong.
        assertEquals(
            listOf("東京", "来ました"),
            Words.terms("東京から来ました", "ja"),
        )
    }

    @Test
    fun `latin words and digits are left out`() {
        assertEquals(
            listOf("日本語"),
            Words.terms("I am learning 日本語 2026", "ja"),
        )
    }

    @Test
    fun `a language with spaces has nothing to split`() {
        assertTrue(Words.terms("how long have you been studying", "en").isEmpty())
        assertTrue(!Words.crowded("en"))
        assertTrue(Words.crowded("ja"))
    }

    @Test
    fun `one word is not a word list`() {
        assertEquals(listOf("勉強"), Words.terms("勉強", "ja"))
    }

    @Test
    fun `meanings are taken only when they line up one for one`() {
        // The engine keeps the line breaks it was given, inside the segments.
        val body = """[[["Japanese\n","日本語\n",null,null,10],["studying","勉強している",null,null,10]],null,"ja"]"""
        assertEquals(listOf("Japanese", "studying"), Words.parse(body, 2))

        // One segment per word works just as well.
        val perLine = """[[["Japanese","日本語",null,null,10],["studying","勉強している",null,null,10]],null,"ja"]"""
        assertEquals(listOf("Japanese", "studying"), Words.parse(perLine, 2))

        // Fewer answers than words: nothing is guessed at.
        assertNull(Words.parse(body, 3))
        assertNull(Words.parse("<html>429</html>", 2))
    }

    @Test
    fun `a meaning that says nothing is dropped rather than shown`() {
        val pairs = Words.pairs(
            listOf("日本語", "を", "勉強"),
            listOf("Japanese", "", "study"),
        )
        assertEquals(listOf(Words.Pair("日本語", "Japanese"), Words.Pair("勉強", "study")), pairs)

        // Echoing the word back, or answering with punctuation, is not a meaning.
        assertTrue(Words.pairs(listOf("日本語"), listOf("日本語")).isEmpty())
        assertTrue(Words.pairs(listOf("日本語"), listOf("。")).isEmpty())
    }

    @Test
    fun `the request carries every word on its own line`() {
        val url = Words.request(listOf("日本語", "勉強"), "en", "ja")
        assertTrue(url.contains("sl=ja"))
        assertTrue(url.contains("tl=en"))
        assertTrue(url.contains("%E6%97%A5%E6%9C%AC%E8%AA%9E%0A%E5%8B%89%E5%BC%B7"))
    }
}

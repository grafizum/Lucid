package com.grafizum.lucid

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs

/**
 * Mixed-language narrowing: a sentence can hold two languages at once
 * ("I'm learning 日本語を勉強しています"), and the accessibility tree hands that
 * over as one string. The lens, though, is physically over *one* of them — so
 * this cuts the string down to what the lens is actually asking for.
 *
 * Which cut that is depends on how much of the message the script under the
 * lens holds. Most messages are in one language with the odd token from
 * another in the middle (`明日8pmに来る`, `I go to 日本語 today`): those tokens
 * belong to the message and travel with it, so the whole message is read.
 * When the lens is instead resting on a genuine minority — one foreign word in
 * someone else's sentence — only that word's stretch is taken, so hovering it
 * translates it alone.
 *
 * The character under the lens comes from mapping the lens x onto the type
 * size TextLines recovered (or the app's own character boxes when it publishes
 * them); punctuation, spaces and digits never decide the language, they only
 * bridge the letters either side of them.
 */
object TextRun {

    /** [start] and [end] are character offsets into the node's string. */
    data class Focus(val text: String, val start: Int, val end: Int, val narrowed: Boolean)

    private const val NEUTRAL = 0
    private const val LATIN = 1
    private const val JAPANESE = 2
    private const val KOREAN = 3
    private const val CYRILLIC = 4
    private const val GREEK = 5
    private const val ARABIC = 6
    private const val HEBREW = 7
    private const val THAI = 8
    private const val DEVANAGARI = 9
    private const val OTHER = 10

    fun at(
        text: String,
        bounds: Rect,
        x: Int,
        y: Int,
        density: Float,
        chars: Array<RectF?>? = null,
        roomySpacing: Float = TextLines.LINE_HEIGHT,
    ): Focus {
        val clean = text.replace('\u00A0', ' ')
        val whole = Focus(clean, 0, clean.length, narrowed = false)
        if (clean.isBlank()) return whole

        // Which character the lens is over: read straight off the app's own
        // character boxes when it reported them, otherwise off the fitted
        // layout. The boxes make the hover exact — no glyph is guessed.
        val index: Int
        val search: IntRange
        if (chars != null && chars.size == clean.length) {
            index = indexFromBoxes(chars, x, y) ?: return whole
            search = lineAround(chars, index)
        } else {
            if (bounds.width() <= 0 || bounds.height() <= 0) return whole
            val layout = TextLines.layout(clean, bounds, density, roomySpacing)
            val line = layout.lines.minByOrNull { verticalDistance(it, y) } ?: return whole
            index = indexAt(clean, line, x)
            search = line.start until line.end
        }

        return at(clean, index, search)
    }

    /**
     * The narrowing itself: which character the lens sits on, and what the read
     * becomes. Pure string logic — no geometry — so the rule can be checked on
     * the JVM without a device.
     */
    fun at(clean: String, index: Int, search: IntRange): Focus {
        val whole = Focus(clean, 0, clean.length, narrowed = false)
        if (clean.isBlank() || index !in clean.indices) return whole

        val group = groupAt(clean, index, search)
        if (group == NEUTRAL) return whole

        var start = index
        var end = index + 1

        if (majorityOf(clean, group)) {
            // This really is a message in one language, so the whole message is
            // the run: a small intrusion in the middle rides along instead of
            // cutting it in two (`明日8pmに来る`, `I go to 日本語 today`).
            start = 0
            end = clean.length
        } else {
            // The lens is on a minority of the text — a lone foreign word in
            // someone else's sentence — so only this script's own stretch is
            // taken. A short neutral stretch (space, comma, dash) is part of
            // the same run; a letter of another script ends it.
            var i = index - 1
            while (i >= 0) {
                val g = groupOf(clean[i])
                if (g == group) {
                    start = i
                } else if (g != NEUTRAL) {
                    break
                }
                i--
            }

            i = index + 1
            while (i < clean.length) {
                val g = groupOf(clean[i])
                if (g == group) {
                    end = i + 1
                } else if (g != NEUTRAL) {
                    break
                }
                i++
            }
        }

        if (end <= start) return whole

        // The run stops at the last letter, but a sentence is not finished
        // there: ？ is what makes a Japanese line a question, and a read that
        // dropped it handed the engine a statement (本当？ came back as
        // "Really", not "Really?"). The marks touching the run belong to it.
        var tail = end
        while (tail < clean.length && tail - end < 3 && isMark(clean[tail])) tail++
        end = tail

        var head = start
        while (head > 0 && start - head < 2 && isMark(clean[head - 1])) head--
        start = head

        val cut = clean.substring(start, end)
        val trimmed = cut.trim()
        if (trimmed.length < 2) return whole

        val shift = cut.indexOf(trimmed)
        val from = start + shift
        val narrowed = !(from == 0 && from + trimmed.length == clean.length)
        return Focus(trimmed, from, from + trimmed.length, narrowed)
    }

    /** Character offset of the glyph under [x] on [line]. */
    private fun indexAt(clean: String, line: TextLines.Line, x: Int): Int {
        val target = (x - line.left).toFloat()
        if (target <= 0f) return line.start

        for (i in line.start until line.end) {
            val width = TextLines.measure(clean.substring(line.start, i + 1))
            if (width >= target) return i
        }
        return line.end - 1
    }

    /**
     * The character whose own box is under the lens. A point in the gap between
     * boxes — between words, or between two lines — takes the nearest one.
     */
    private fun indexFromBoxes(chars: Array<RectF?>, x: Int, y: Int): Int? {
        var best = -1
        var bestScore = Float.MAX_VALUE

        for (i in chars.indices) {
            val box = chars[i] ?: continue
            if (box.contains(x.toFloat(), y.toFloat())) return i

            val dx = maxOf(box.left - x, 0f, x - box.right)
            val dy = maxOf(box.top - y, 0f, y - box.bottom)
            val score = dx * dx + dy * dy
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }

        return best.takeIf { it >= 0 }
    }

    /** The stretch of characters sitting on the same visual line as [index]. */
    private fun lineAround(chars: Array<RectF?>, index: Int): IntRange {
        val anchor = chars[index] ?: return 0 until chars.size
        val reach = anchor.height().coerceAtLeast(1f) * 0.7f

        fun onLine(i: Int): Boolean =
            chars[i]?.let { abs(it.centerY() - anchor.centerY()) < reach } == true

        var start = index
        while (start > 0 && onLine(start - 1)) start--
        var end = index + 1
        while (end < chars.size && onLine(end)) end++
        return start until end
    }

    /**
     * The script under the lens. Landing on punctuation or a space looks either
     * side for the nearest letter on the same line first, so a cursor in the gap
     * between words still picks the language it is sitting in.
     */
    private fun groupAt(clean: String, index: Int, search: IntRange): Int {
        groupOf(clean[index]).let { if (it != NEUTRAL) return it }

        for (step in 1..24) {
            val left = index - step
            if (left >= search.first) {
                groupOf(clean[left]).let { if (it != NEUTRAL) return it }
            }
            val right = index + step
            if (right <= search.last) {
                groupOf(clean[right]).let { if (it != NEUTRAL) return it }
            }
            if (left < search.first && right > search.last) break
        }
        return NEUTRAL
    }

    /**
     * Does the script under the lens hold most of the message?
     *
     * Counted in letters, so digits and punctuation never tip it either way:
     * `明日8pmに来る` is 4 Japanese letters to 2 Latin ones, and the message is
     * Japanese. A tie goes to the strict majority check — the run narrows
     * rather than swallowing a sentence that is half of another language.
     */
    private fun majorityOf(clean: String, group: Int): Boolean {
        if (group == NEUTRAL) return false
        var own = 0
        var other = 0
        for (ch in clean) {
            val g = groupOf(ch)
            if (g == group) own++
            else if (g != NEUTRAL) other++
        }
        return own > other
    }

    private fun verticalDistance(line: TextLines.Line, y: Int): Int = when {
        y < line.top -> line.top - y
        y > line.bottom -> y - line.bottom
        else -> 0
    }

    /** Punctuation and symbols: they belong to the run they touch, not to a language. */
    private fun isMark(ch: Char): Boolean = !ch.isLetter() && !ch.isDigit() && !ch.isWhitespace()

    private fun groupOf(ch: Char): Int {
        if (ch.isWhitespace()) return NEUTRAL
        if (!ch.isLetter()) return NEUTRAL

        val block = runCatching { Character.UnicodeBlock.of(ch) }.getOrNull()
            ?: return if (ch.code < 0x250) LATIN else OTHER

        return when (block) {
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            -> JAPANESE

            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
            -> KOREAN

            Character.UnicodeBlock.CYRILLIC, Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> CYRILLIC
            Character.UnicodeBlock.GREEK, Character.UnicodeBlock.GREEK_EXTENDED -> GREEK
            Character.UnicodeBlock.ARABIC -> ARABIC
            Character.UnicodeBlock.HEBREW -> HEBREW
            Character.UnicodeBlock.THAI -> THAI
            Character.UnicodeBlock.DEVANAGARI -> DEVANAGARI

            Character.UnicodeBlock.BASIC_LATIN,
            Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A,
            Character.UnicodeBlock.LATIN_EXTENDED_B,
            -> LATIN

            else -> OTHER
        }
    }
}

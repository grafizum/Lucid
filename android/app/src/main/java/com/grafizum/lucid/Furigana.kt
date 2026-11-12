package com.grafizum.lucid

/**
 * Lines a reading up with the kanji it belongs to.
 *
 * The engine gives one reading for a whole sentence ("にほんごをべんきょうしています")
 * and no hint of where each kanji's part starts. Japanese comes to the rescue:
 * every kana written in the sentence appears in the reading, in the same order.
 * So the two are walked side by side — a kana in the sentence must be the next
 * kana in the reading, and a kanji swallows everything in the reading up to
 * where the next kana of the sentence begins.
 *
 * The moment the two stop agreeing, alignment stops: a missing reading is fine,
 * a wrong one is not.
 */
object Furigana {

    /** A stretch of the original text and the kana to draw above it. */
    data class Ruby(val start: Int, val end: Int, val reading: String)

    /**
     * The reading as kana, whichever way the engine spelled it: an engine may
     * send romaji ("Nihongo o benkyō shite imasu") or kana already.
     */
    fun kana(reading: String): String? {
        val letters = reading.count { it.isLetter() }
        if (letters == 0) return null
        val kana = reading.count { isKana(it) }
        return if (kana * 2 >= letters) reading else Romaji.hiragana(reading)
    }

    fun of(text: String, reading: String): List<Ruby> {
        if (text.isEmpty() || reading.isEmpty()) return emptyList()

        val kana = reading.filter { isKana(it) }.map { toHiragana(it) }
        if (kana.isEmpty()) return emptyList()
        val spoken = String(kana.toCharArray())

        val rubies = ArrayList<Ruby>()
        var at = 0
        var ri = 0

        while (at < text.length && ri < spoken.length) {
            val c = text[at]

            if (isKanji(c)) {
                // Kanji with nothing pronounceable between them share one
                // stretch of reading (来週、東京 is らいしゅうとうきょう with no
                // kana to cut at), so the block is measured as a whole.
                val runs = ArrayList<IntArray>()
                var end = at
                while (end < text.length) {
                    if (isKanji(text[end])) {
                        val start = end
                        while (end < text.length && isKanji(text[end])) end++
                        runs.add(intArrayOf(start, end))
                        continue
                    }
                    // Only punctuation, digits or Latin may hide between runs.
                    var look = end
                    while (look < text.length && !isKanji(text[look]) && !isKana(text[look])) look++
                    if (look < text.length && isKanji(text[look])) {
                        end = look
                        continue
                    }
                    break
                }

                // A kanji always sounds like something, so its reading is at
                // least one kana long: the next kana of the sentence is looked
                // for from there, and a block with no following kana takes
                // whatever reading is left.
                val boundary = nextKana(text, end)
                    ?.let { find(spoken, it, ri + 1) }
                    ?: spoken.length

                if (boundary > ri) {
                    split(spoken, ri, boundary, runs, rubies)
                    ri = boundary
                }
                at = end
                continue
            }

            if (isKana(c)) {
                if (!same(spoken[ri], c)) break
                ri++
                at++
                continue
            }

            at++ // Punctuation, digits, Latin: it anchors nothing either way.
        }

        return rubies
    }

    /**
     * Puts the whole reading of a block over the block.
     *
     * The engine says how a block sounds, never how much of that belongs to
     * each kanji inside it, and guessing by character count is how 日本語 ended
     * up printed as にほ over 日本 and んご over 語. So neighbouring kanji share
     * one reading, drawn across them: 日本語 reads にほんご, 勉強 reads べんきょう.
     * Nothing is split where the truth is not known.
     */
    private fun split(
        spoken: String,
        from: Int,
        to: Int,
        runs: List<IntArray>,
        out: MutableList<Ruby>,
    ) {
        val chunk = spoken.substring(from, to)
        if (chunk.isEmpty() || runs.isEmpty()) return
        out.add(Ruby(runs.first()[0], runs.last()[1], chunk))
    }

    /**
     * The first kana of the sentence at or after [from], as hiragana so it can
     * be compared with a reading — a katakana word reads in hiragana.
     */
    private fun nextKana(text: String, from: Int): Char? {
        for (i in from until text.length) {
            if (isKana(text[i])) return toHiragana(text[i])
        }
        return null
    }

    /** Where [target] (spoken kana) sits in [reading] at or after [from]. */
    private fun find(reading: String, target: Char, from: Int): Int? {
        for (i in from until reading.length) {
            if (same(reading[i], target)) return i
        }
        return null
    }

    /**
     * Whether the reading's kana and the sentence's kana say the same sound.
     *
     * The sentence may spell a sound in katakana (コーヒー, スマホ) while the
     * reading spells it in hiragana, so both sides are compared as hiragana.
     * は, へ and を are particles spoken わ, え and お, and a long vowel written
     * ー is spelled out as a vowel in a reading. Everything else has to match
     * exactly.
     */
    private fun same(reading: Char, written: Char): Boolean {
        val sound = toHiragana(written)
        if (reading == sound) return true
        return when (sound) {
            'は' -> reading == 'わ'
            'へ' -> reading == 'え'
            'を' -> reading == 'お'
            'ー' -> reading in "あいうえお"
            else -> reading == 'ー' && sound in "あいうえお"
        }
    }

    private fun isKanji(c: Char): Boolean =
        c in '\u4E00'..'\u9FFF' ||
            c in '\u3400'..'\u4DBF' ||
            c in '\uF900'..'\uFAFF' ||
            c == '々' || c == '〆'

    private fun isKana(c: Char): Boolean =
        c in '\u3041'..'\u309F' || c in '\u30A1'..'\u30FA' || c == 'ー'

    private fun toHiragana(c: Char): Char =
        if (c in '\u30A1'..'\u30F6') (c.code - 0x60).toChar() else c
}

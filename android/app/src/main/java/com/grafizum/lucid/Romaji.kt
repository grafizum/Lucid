package com.grafizum.lucid

/**
 * Turns romaji into hiragana.
 *
 * The online engine hands back the reading of a Japanese sentence as romaji
 * ("Nihongo o benkyō shite imasu"), but what belongs over kanji is kana
 * ("にほんごをべんきょうしています"). This is a conversion, not a dictionary:
 * it knows that "nihon" is にほん, never that 日本 is read that way — the
 * engine supplies the reading, this only respells it.
 *
 * Anything it cannot read cleanly returns null, so a reading we are not sure
 * of is never drawn.
 */
object Romaji {

    /** Longest syllables first, so "kyo" is never read as "ki" + "yo". */
    private val syllables: Map<String, String> = buildMap {
        fun put(vararg pairs: Pair<String, String>) = pairs.forEach { (k, v) -> this[k] = v }

        put(
            "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
            "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
            "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
            "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
            "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
            "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ",
            "te" to "て", "to" to "と",
            "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
            "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
            "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
            "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
            "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
            "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
            "ya" to "や", "yu" to "ゆ", "yo" to "よ",
            "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
            "wa" to "わ", "wi" to "うぃ", "we" to "うぇ", "wo" to "を",
        )

        // Contractions: two kana, written with a small ゃゅょ.
        put(
            "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
            "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
            "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
            "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ", "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
            "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ", "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
            "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
            "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
            "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
            "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
            "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
            "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
            "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
            "ye" to "いぇ",
        )
    }

    /** Longest key first: the parser tries these in order at each position. */
    private val keys: List<String> = syllables.keys.sortedByDescending { it.length }

    private const val SOKUON = "っ"

    /**
     * A macron is a long vowel, and it sits on the vowel of a syllable — so
     * "kō" can never be read where it stands, only as "kou". Expanding every
     * macron into that spelling before parsing is what makes 学校 ("Gakkō")
     * come out がっこう instead of nothing at all.
     */
    private val longVowels = mapOf(
        'ā' to "aa", 'ī' to "ii", 'ū' to "uu", 'ē' to "ei", 'ō' to "ou",
        'Ā' to "aa", 'Ī' to "ii", 'Ū' to "uu", 'Ē' to "ei", 'Ō' to "ou",
        'â' to "aa", 'î' to "ii", 'û' to "uu", 'ê' to "ei", 'ô' to "ou",
    )

    /** How a marked vowel is spelled out, matching the macron table above. */
    private val marks = mapOf('a' to "aa", 'i' to "ii", 'u' to "uu", 'e' to "ei", 'o' to "ou")

    /** U+0304, the mark the engine sometimes places on the vowel instead. */
    private const val COMBINING_MACRON = '\u0304'

    private val vowels = "aiueo"

    /**
     * The hiragana for [romaji], or null if some syllable is not Japanese we
     * can read (a stray Latin word, an up-tempo phrase we would only guess at).
     *
     * Spaces and punctuation pass through so the reading keeps its shape; they
     * carry no kana and are dropped when the reading is aligned onto kanji.
     */
    fun hiragana(romaji: String): String? {
        val spelled = StringBuilder(romaji.length)
        var at = 0
        while (at < romaji.length) {
            val c = romaji[at]
            // The engine sends a macron either as its own letter (ō) or as a
            // mark stuck onto the vowel (o + ̄), and the mark alone is not a
            // letter — folding both to the same spelling keeps 学校 as がっこう.
            if (at + 1 < romaji.length && romaji[at + 1] == COMBINING_MACRON) {
                spelled.append(marks[c.lowercaseChar()] ?: c.toString())
                at += 2
                continue
            }
            spelled.append(longVowels[c] ?: c)
            at++
        }
        val text = spelled.toString()

        val out = StringBuilder()
        var i = 0
        var readSomething = false

        while (i < text.length) {
            val c = text[i]

            if (!c.isLetter()) {
                out.append(if (c == ' ') ' ' else c)
                i++
                continue
            }

            val lower = c.lowercaseChar()

            // Doubled consonant: the small っ, then the syllable runs again.
            // "tch" is the one that hides two consonants behind one letter.
            if (i + 1 < text.length) {
                val next = text[i + 1].lowercaseChar()
                val doubled = lower == next && lower !in vowels && lower != 'n'
                if (doubled) {
                    out.append(SOKUON)
                    i++
                    continue
                }
                if (text.startsWith("tch", i, ignoreCase = true)) {
                    out.append(SOKUON)
                    i++
                    continue
                }
            }

            val syllable = keys.firstOrNull { text.startsWith(it, i, ignoreCase = true) }
            if (syllable != null) {
                out.append(syllables.getValue(syllable))
                i += syllable.length
                readSomething = true
                continue
            }

            // A bare n is the only consonant that stands alone; "nya" and the
            // rest already matched above.
            if (lower == 'n') {
                out.append('ん')
                i++
                readSomething = true
                continue
            }

            return null
        }

        return if (readSomething) out.toString() else null
    }
}

package com.grafizum.lucid

import org.json.JSONArray
import java.net.URLEncoder

/**
 * Word by word: which word means what.
 *
 * Japanese (and Chinese, Thai) is written without spaces, so a translation of
 * a whole line leaves no way to tell where one word ends and the next begins.
 * The split is taken from the writing itself — a run of kanji, the kana run
 * welded onto it, a katakana word — and then every word is asked for its own
 * meaning in one request. If the answers cannot be lined up with the words
 * exactly, nothing is shown: a wrong pairing under the eye is worse than none.
 */
object Words {

    /** One word of the source, and what it means. */
    data class Pair(val term: String, val meaning: String)

    /** Languages that run their words together, so the split has to be guessed. */
    fun crowded(lang: String?): Boolean = when (lang?.substringBefore('-')?.lowercase()) {
        "ja", "zh", "th", "lo", "my", "km" -> true
        else -> false
    }

    /**
     * A kana run this short is a particle or an ending, and carries no meaning
     * of its own worth a line in the card.
     */
    private val PARTICLES = setOf(
        "を", "は", "が", "に", "で", "と", "も", "の", "へ", "や", "ね", "よ", "か",
        "だ", "さ", "な",
    )

    /**
     * A particle glued to the end of a word — コーヒーを is the word コーヒー and
     * the marker を, and only the word is worth a meaning.
     */
    private const val TRAILING_PARTICLES = "をはがにでともへやかねよ"

    /**
     * Kana that start a particle rather than the tail of the word in front of
     * it. 東京から is 東京 + から, while 食べます is one word — both are kana
     * runs of two or more, so the beginning of the run is what tells them apart.
     */
    private val PARTICLE_HEADS = listOf(
        "から", "まで", "より", "など", "だけ", "しか", "でも", "には", "では", "との",
        "への", "って", "ばかり", "ながら", "ので", "のに", "けど",
    )

    /** How many words are worth asking about; a long line is not a dictionary. */
    const val LIMIT = 10

    /**
     * The words of [text], in reading order. Latin words and digits are left
     * out on purpose — they are already readable and would only take a slot.
     */
    fun terms(text: String, lang: String?): List<String> {
        if (!crowded(lang)) return emptyList()

        val out = ArrayList<String>()
        var i = 0

        while (i < text.length) {
            val c = text[i]

            if (isKanji(c)) {
                var end = i
                while (end < text.length && isKanji(text[end])) end++

                // The tail of an inflected word is kana (食べる, 行きます) and
                // belongs with the kanji; a particle does not.
                if (end < text.length && isKana(text[end])) {
                    var tail = end
                    while (tail < text.length && isKana(text[tail])) tail++
                    val run = text.substring(end, tail)
                    if (run.length >= 2 && run !in PARTICLES && PARTICLE_HEADS.none { run.startsWith(it) }) {
                        end = tail
                    }
                }

                out += text.substring(i, end)
                i = end
                continue
            }

            if (isKana(c)) {
                var end = i
                while (end < text.length && isKana(text[end])) end++
                var run = text.substring(i, end)
                // コーヒーを is one kana run but two things: the word and the
                // marker that follows it. The marker is cut off, not shown.
                while (run.length >= 2 && run.last() in TRAILING_PARTICLES) {
                    run = run.dropLast(1)
                }
                // A bare particle is skipped; a katakana word or a written-out
                // verb stands on its own.
                if (run.length >= 2 && run !in PARTICLES && PARTICLE_HEADS.none { run == it }) {
                    out += run
                }
                i = end
                continue
            }

            if (c.isLetter()) {
                // Latin, Cyrillic and the rest: already spaced, nothing to split.
                var end = i
                while (end < text.length && text[end].isLetter()) end++
                i = end
                continue
            }

            i++
        }

        return out.take(LIMIT)
    }

    /**
     * One request for every word at once: the words are sent one per line, and
     * the engine hands back one line each.
     */
    fun request(terms: List<String>, target: String, source: String? = null): String {
        val query = terms.joinToString("\n")
        val from = source?.takeIf { crowded(it) } ?: "auto"
        return "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=" + URLEncoder.encode(from, "UTF-8") +
            "&dt=t&tl=" + URLEncoder.encode(target, "UTF-8") +
            "&q=" + URLEncoder.encode(query, "UTF-8")
    }

    /**
     * The meanings, one per word, or null when the engine did not answer with
     * exactly that many lines — an unaligned answer is thrown away, never
     * guessed at.
     */
    fun parse(body: String, count: Int): List<String>? {
        if (count <= 0) return null

        return runCatching {
            val segments = JSONArray(body).optJSONArray(0) ?: return null
            val pieces = ArrayList<String>()

            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                // The reading row has no translation of its own; it is not
                // requested here, but a segment without index 0 is skipped.
                if (segment.isNull(0)) continue
                pieces += segment.optString(0)
            }

            // The engine keeps the line breaks it was given, so the joined
            // answer splits back into one line per word. Some responses come
            // back as one segment per line instead, which is just as good.
            val lines = pieces.joinToString("").split("\n").map { it.trim() }
            when {
                lines.size == count -> lines
                pieces.size == count -> pieces.map { it.trim() }
                else -> null
            }
        }.getOrNull()
    }

    /** The words that came back with a meaning worth showing. */
    fun pairs(terms: List<String>, meanings: List<String>): List<Pair> {
        if (terms.size != meanings.size) return emptyList()

        return terms.indices.mapNotNull { index ->
            val term = terms[index]
            val meaning = meanings[index].trim()
            if (meaning.isEmpty()) return@mapNotNull null
            if (meaning.none { it.isLetterOrDigit() }) return@mapNotNull null
            if (meaning.equals(term, ignoreCase = true)) return@mapNotNull null
            Pair(term, meaning)
        }
    }

    private fun isKanji(c: Char): Boolean =
        c in '\u4E00'..'\u9FFF' ||
            c in '\u3400'..'\u4DBF' ||
            c in '\uF900'..'\uFAFF' ||
            c == '々' || c == '〆'

    private fun isKana(c: Char): Boolean =
        c in '\u3041'..'\u309F' || c in '\u30A1'..'\u30FA' || c == 'ー'
}

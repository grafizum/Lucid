package com.grafizum.lucid

/**
 * The rest of the screen, read silently.
 *
 * A line under the lens rarely stands alone — the messages around it say who
 * "he" is, what "it" refers to, which language a chat is really in. Those
 * neighbours are therefore gathered from the accessibility tree alongside the
 * read, never drawn, and handed to the engine together with the line itself:
 * the line goes first, the screen behind it after, so the engine's own language
 * detection sees the text it is actually being asked about.
 *
 * Only the line under the lens is ever shown. This is context for the engine,
 * not content for the card.
 */
object Contexts {

    /** How many neighbouring lines are worth sending. */
    const val MAX_LINES = 12

    /** One line capped, so a whole paragraph cannot eat the budget. */
    const val MAX_LINE = 200

    /** Total context in characters — a hint, not a document. */
    const val MAX_CHARS = 700

    /**
     * What is sent: the line under the lens, then the screen behind it.
     *
     * The line itself is dropped from the neighbours (it is already on screen),
     * repeats collapse, and the caps are applied in reading order — the
     * messages above the lens matter more than the footer below it.
     */
    fun query(context: String, target: String): List<String> {
        val core = target.trim()
        val out = ArrayList<String>()
        if (core.isNotEmpty()) out += core

        if (context.isBlank() || out.isEmpty()) return out

        val seen = HashSet<String>()
        if (core.isNotEmpty()) seen += core
        var budget = MAX_CHARS - core.length

        for (raw in context.lines()) {
            if (out.size > MAX_LINES) break
            if (budget <= 0) break

            val line = raw.replace(WHITESPACE, " ").trim().take(MAX_LINE)
            if (line.length < 2) continue
            if (!seen.add(line)) continue

            out += line
            budget -= line.length
        }

        return out
    }

    /**
     * The line's own answer out of the engine's lines, or null when they did
     * not come back one-for-one. A mismatch is refused, never guessed at: the
     * wrong line translated is worse than the line translated alone.
     */
    fun resolve(sent: List<String>, returned: String): String? {
        if (sent.size <= 1) return returned.trim().ifBlank { null }
        val lines = returned.split('\n').map { it.trim() }
        if (lines.size != sent.size) return null
        return lines.first().ifBlank { null }
    }

    private val WHITESPACE = Regex("""[\s\u00A0]+""")
}

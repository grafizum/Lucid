package com.grafizum.lucid

/**
 * The one rule the lens follows: it reads what it is on, and nothing else.
 *
 * Both paths end up with a box that is generous — an accessibility node's
 * bounds, or an OCR line's bounding box — and a lens inside that box can still
 * be nowhere near the words: a padded row, a chat bubble, a paragraph
 * stretched to a page's column all hold space their glyphs do not. So neither
 * path trusts the box alone, and both ask this.
 *
 * Plain integers on purpose: the rule is checked on the JVM, where the
 * graphics classes are stubs.
 */
object UnderLens {

    /** A box in screen pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Long get() = (right - left).toLong() * (bottom - top).toLong()
    }

    /**
     * True when the point is on [box], give or take [slack] pixels.
     *
     * The slack exists because boxes are cut to the glyphs: a lens aimed at the
     * gap between two lines of a paragraph is still asking about that
     * paragraph, while one aimed a thumb's width away is not.
     */
    fun holds(box: Box, x: Int, y: Int, slack: Int = 0): Boolean =
        x >= box.left - slack && x <= box.right + slack &&
            y >= box.top - slack && y <= box.bottom + slack

    /**
     * The innermost box the point is on, or null when it is on none — the
     * "nothing under the lens" answer, which is an answer and not a miss.
     */
    fun innermost(boxes: List<Box>, x: Int, y: Int, slack: Int = 0): Box? =
        boxes.filter { holds(it, x, y, slack) }.minByOrNull { it.area }
}

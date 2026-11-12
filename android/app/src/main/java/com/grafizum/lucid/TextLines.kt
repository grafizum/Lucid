package com.grafizum.lucid

import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs

/**
 * Turns "this node holds that string, in this box" into one tight rectangle per
 * visible line — and, because the same fit knows the type size, into where any
 * given character sits on screen.
 *
 * Geometry is taken from the best source the node offers, in this order:
 *
 * 1. The app's own per-character boxes, when it publishes them — a real
 *    layout, and the wash is cut from it ([fromChars]).
 * 2. The box the text fills: a box that is exactly as wide as its own text
 *    states the type size, so the fit is corrected by that ratio.
 * 3. The box the text barely fills: a padded control or a block stretched to a
 *    page's column, where the height is the app's line box and not the type
 *    size, and [roomySpacing] stands in for what nothing publishes.
 *
 * What is left is the wrap: the type size is the one at which wrapping the
 * string to the box width produces exactly as many lines as the box height
 * allows, and a candidate that does not wrap self-consistently is discarded.
 */
object TextLines {

    /** Line box height as a multiple of the type size — Android's default. */
    const val LINE_HEIGHT = 1.18f

    /** Height of the selection band, relative to the type size (cap + a hair). */
    private const val BAND_HEIGHT = 1.15f

    /**
     * Below this much of its box, a run of text is not what the box was sized
     * for — a padded control, or a block the page stretched to its column — and
     * the box height then leads the type size astray.
     */
    private const val ROOMY_FILL = 0.95f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** One laid-out line: a character range and the box it occupies. */
    data class Line(
        val start: Int,
        val end: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /**
     * [fitted] is false when the box explained nothing about its own text — an
     * app that hands over one clipped piece of a paragraph, say — and the line
     * structure below is a stand-in rather than a reading.
     */
    data class Layout(val text: String, val size: Float, val lines: List<Line>, val fitted: Boolean = true)

    /**
     * One band per visible line for the characters in [from, to) — the app's
     * own character boxes when it published them, the fitted box otherwise.
     */
    fun bands(
        text: String,
        bounds: Rect,
        density: Float,
        from: Int,
        to: Int,
        chars: Array<RectF?>?,
        roomySpacing: Float = LINE_HEIGHT,
        anchor: Point? = null,
    ): List<Rect> {
        val estimated = ofRange(text, bounds, density, from, to, roomySpacing, anchor)
        if (chars == null || chars.size != text.length) return estimated
        return fromChars(chars, from, to, estimated)
    }

    /**
     * Bands cut from the app's own per-character boxes. Characters are gathered
     * into visual lines by vertical overlap, and each line's band runs from the
     * first glyph to the last — a native selection, not a fitted guess.
     */
    private fun fromChars(
        chars: Array<RectF?>,
        from: Int,
        to: Int,
        estimated: List<Rect>,
    ): List<Rect> {
        val lines = ArrayList<MutableList<RectF>>(2)
        for (i in from until minOf(to, chars.size)) {
            val box = chars[i] ?: continue
            val current = lines.lastOrNull()
            val anchor = current?.firstOrNull()
            if (anchor != null && sameLine(anchor, box)) current.add(box) else lines.add(mutableListOf(box))
        }

        return lines.map { boxes ->
            val band = Rect(
                boxes.minOf { it.left }.toInt(),
                boxes.minOf { it.top }.toInt(),
                boxes.maxOf { it.right }.toInt(),
                boxes.maxOf { it.bottom }.toInt(),
            )
            // Some apps report a character's horizontal extent only; those
            // boxes would draw nothing, so they take the estimated height.
            if (band.height() >= 2) band else withHeight(band, estimated)
        }
    }

    private fun sameLine(anchor: RectF, box: RectF): Boolean {
        val reach = minOf(anchor.height(), box.height()).coerceAtLeast(1f)
        return abs(anchor.centerY() - box.centerY()) < reach * 0.7f
    }

    /** Keeps [band]'s own left and right, borrowing the vertical from the
     *  estimated band closest to it. */
    private fun withHeight(band: Rect, estimated: List<Rect>): Rect {
        val near = estimated.minByOrNull { abs(it.centerY() - band.centerY()) } ?: return band
        return Rect(band.left, near.top, band.right, near.bottom)
    }

    /** Bands for the characters in [start, end) only. */
    fun ofRange(
        text: String,
        bounds: Rect,
        density: Float,
        start: Int,
        end: Int,
        roomySpacing: Float = LINE_HEIGHT,
        anchor: Point? = null,
    ): List<Rect> {
        val clean = text.replace('\u00A0', ' ')
        if (clean.isBlank() || bounds.width() <= 0 || bounds.height() <= 0) {
            return listOf(Rect(bounds))
        }

        val layout = layout(clean, bounds, density, roomySpacing)

        // The box could not say where its own text sits inside it, so the lens
        // itself is the only place we know text to be: draw the run around it.
        if (!layout.fitted && anchor != null && end > start) {
            return listOf(around(run(clean, layout.size, start, end), layout.size, anchor))
        }

        val out = ArrayList<Rect>(layout.lines.size)

        for (line in layout.lines) {
            val from = maxOf(start, line.start)
            val to = minOf(end, line.end)
            if (to <= from) continue

            val prefix = clean.substring(line.start, from)
            val body = clean.substring(from, to)
            val offset = measure(prefix)
            val width = measure(body).coerceAtLeast(layout.size * 0.5f)

            val left = (line.left + offset).toInt()
            val right = (left + width).toInt().coerceAtMost(line.right.coerceAtLeast(left + 2))
            out.add(Rect(left, line.top, right.coerceAtLeast(left + 2), line.bottom))
        }

        return if (out.isEmpty()) listOf(Rect(bounds)) else out
    }

    /** The width the characters in [start, end) take at [size]. */
    private fun run(clean: String, size: Float, start: Int, end: Int): Float {
        paint.textSize = size
        return measure(clean.substring(start.coerceIn(0, clean.length), end.coerceIn(0, clean.length)))
    }

    /** A band centred on [anchor] with the run's own width and one type size's height. */
    private fun around(width: Float, size: Float, anchor: Point): Rect {
        val half = (width / 2f).coerceAtLeast(size * 0.5f)
        val height = size * BAND_HEIGHT
        return Rect(
            (anchor.x - half).toInt(),
            (anchor.y - height / 2f).toInt(),
            (anchor.x + half).toInt(),
            (anchor.y + height / 2f).toInt(),
        )
    }

    /**
     * [roomySpacing] is only consulted when the box turns out to be too roomy to
     * measure the type from — the app's own idea of how tall one line of its
     * text stands, which is what the box height is then telling us.
     */
    fun layout(
        clean: String,
        bounds: Rect,
        density: Float,
        roomySpacing: Float = LINE_HEIGHT,
    ): Layout {
        val width = bounds.width().toFloat()

        var size = 0f
        var ranges: List<IntRange> = emptyList()
        var fitted = true

        // Prefer the largest type that still explains the box height.
        for (count in 1..8) {
            val candidate = bounds.height() / (count * LINE_HEIGHT)
            if (candidate < 5f) break
            paint.textSize = candidate
            val wrapped = wrap(clean, width)
            if (wrapped.size == count) {
                size = candidate
                ranges = wrapped
                break
            }
        }

        if (ranges.isEmpty()) {
            // Nothing about this box explains its own text — a paragraph the app
            // reported as one clipped piece, for instance. The platform's body
            // size is the best stand-in when the box says nothing at all.
            fitted = false
            // Nothing fit cleanly (padding, unusual glyph widths, mixed scripts):
            // fall back to a normal body size and let the wrap decide.
            size = (15f * density).coerceIn(9f, 64f)
            paint.textSize = size
            ranges = wrap(clean, width)
        }

        val widest = ranges.maxOf { measure(clean.substring(it.first, it.last + 1)) }
        val fill = if (width > 0f) widest / width else 1f

        if (fill > 1.02f) {
            // The text is wider than the box it sits in, so the box is the
            // text's own: the width says exactly how big the type is, and the
            // fit above was oversize. Shrink by that ratio and re-wrap.
            size /= fill
            paint.textSize = size
            ranges = wrap(clean, width)
        } else if (fitted && fill < ROOMY_FILL) {
            // The text fills far less than its box: a padded control, or a block
            // stretched to the page's column, where the height is the app's line
            // box rather than the type size. Take the app's own idea of a line
            // instead of the platform default.
            size = bounds.height() / (ranges.size * roomySpacing)
            paint.textSize = size
            ranges = wrap(clean, width)
        }

        return place(clean, bounds, size, ranges, density, fitted)
    }

    fun measure(text: String): Float = paint.measureText(text)

    fun typeSize(size: Float) {
        paint.textSize = size
    }

    private fun place(
        clean: String,
        bounds: Rect,
        size: Float,
        ranges: List<IntRange>,
        density: Float,
        fitted: Boolean,
    ): Layout {
        // Each line owns an equal slot of the box; the band sits inside its slot
        // at roughly cap height, so a roomy line-height does not stretch the wash
        // over the gaps between lines either.
        val slot = bounds.height() / ranges.size.toFloat()
        val band = minOf(slot, size * BAND_HEIGHT)

        // A TextView's box includes its padding, so a paragraph that does not
        // fill the width leaves the wash hanging in space on both sides. Split
        // that slack evenly — for equal padding this lands the band on the
        // first glyph — but only for short shifts, so a genuinely left-aligned
        // block in a wide container is not dragged sideways.
        val widest = ranges.maxOf { measure(clean.substring(it.first, it.last + 1)) }
        val slack = (bounds.width() - widest) / 2f
        val inset = if (ranges.size > 1) slack.coerceIn(0f, 20f * density) else 0f

        val left = (bounds.left + inset).toInt()
        val lines = ArrayList<Line>(ranges.size)
        for ((index, range) in ranges.withIndex()) {
            val top = bounds.top + index * slot + (slot - band) / 2f
            lines.add(
                Line(
                    start = range.first,
                    end = range.last + 1,
                    left = left,
                    top = top.toInt(),
                    right = bounds.right,
                    bottom = (top + band).toInt().coerceAtMost(bounds.bottom),
                ),
            )
        }
        return Layout(clean, size, lines, fitted)
    }

    /** Greedy wrap that keeps track of the character range of each line. */
    private fun wrap(text: String, width: Float): List<IntRange> {
        if (measure(text) <= width) return listOf(text.indices)

        val lines = ArrayList<IntRange>(4)
        var start = -1
        var last = -1

        fun flush() {
            if (start >= 0 && last >= start) lines.add(start..last)
            start = -1
            last = -1
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == ' ' && start < 0) {
                i++
                continue
            }

            val candidateStart = if (start < 0) i else start
            if (measure(text.substring(candidateStart, i + 1)) > width && start >= 0) {
                flush()
                continue
            }

            if (start < 0) start = i
            if (ch != ' ') last = i
            i++
        }
        flush()
        return if (lines.isEmpty()) listOf(text.indices) else lines
    }
}

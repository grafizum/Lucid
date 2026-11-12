package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Source text with its kanji pronounced above them.
 *
 * The kana used to be drawn by a span that asked the line for room through its
 * font metrics — and a line ignores that request for its own top, so the
 * reading was painted above the view and cropped by the card, or hidden behind
 * the translation above it. Here the room is real: the view keeps a top padding
 * tall enough for the kana and draws them inside it, so nothing can cut them.
 *
 * Only the source text is the view's text, so copying, selecting and the rest
 * of the card behave exactly as before; the kana are paint, drawn last, one per
 * kanji block, centred on the characters they belong to.
 */
class RubyTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private var rubies: List<Furigana.Ruby> = emptyList()
    private val rubyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The view's own top padding, before the kana's room was added to it. */
    private var baseTop = -1

    /**
     * Sets the line and the kana above it in one call, so a view reused for a
     * language without a reading cannot keep the old one.
     */
    fun setRuby(source: CharSequence, reading: List<Furigana.Ruby>) {
        if (baseTop < 0) baseTop = paddingTop
        rubies = reading
        text = source

        val room = if (reading.isEmpty()) 0 else roomForKana()
        setPadding(paddingLeft, baseTop + room, paddingRight, paddingBottom)
        // Lines below the first have no padding of their own, so the gap
        // between them grows to hold the kana that hang over each line.
        setLineSpacing(if (reading.isEmpty()) 0f else textSize * LINE_GAP, MULTIPLIER)
        requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = layout ?: return
        if (rubies.isEmpty() || layout.lineCount == 0) return

        val base = paint
        rubyPaint.typeface = base.typeface
        rubyPaint.color = faded(base.color)

        val size = base.textSize * RATIO
        // Where the kanji start, in the view's own coordinates: the line's
        // baseline, walked up by the font's ascender and by the padding that
        // was kept for exactly this.
        val glyphTop = base.fontMetricsInt.ascent
        val gap = base.textSize * KANA_GAP
        val top = totalPaddingTop

        for (ruby in rubies) {
            val start = ruby.start
            val end = ruby.end
            if (ruby.reading.isEmpty() || start < 0 || end > layout.text.length || end <= start) continue

            val line = layout.getLineForOffset(start)
            // Text that runs past its line is cut with an ellipsis, and kana
            // for a character that is not on screen would sit on the dots.
            val cut = layout.getEllipsisCount(line)
            if (cut > 0 && end > layout.getLineVisibleEnd(line)) continue

            val from = totalPaddingLeft + layout.getPrimaryHorizontal(start)
            val to = totalPaddingLeft + layout.getPrimaryHorizontal(end)
            val width = to - from
            if (width <= 1f) continue

            // One wide kanji can carry a long reading; condense instead of
            // spilling onto the characters either side.
            rubyPaint.textSize = size
            val measured = rubyPaint.measureText(ruby.reading)
            if (measured > width) rubyPaint.textSize = size * (width / measured)

            val baseline = top + layout.getLineBaseline(line) + glyphTop - gap - rubyPaint.fontMetricsInt.descent
            canvas.drawText(
                ruby.reading,
                from + (width - rubyPaint.measureText(ruby.reading)) / 2f,
                baseline,
                rubyPaint,
            )
        }
    }

    /**
     * Room above the glyphs for one row of kana: their ascender and descender
     * plus the gap that keeps them off the kanji.
     */
    private fun roomForKana(): Int {
        rubyPaint.textSize = textSize * RATIO
        val metrics = rubyPaint.fontMetricsInt
        val kana = -metrics.ascent + metrics.descent + textSize * KANA_GAP
        return (kana + textSize * SLACK).toInt()
    }

    private fun faded(color: Int): Int {
        val alpha = (((color ushr 24) and 0xFF) * FADE).toInt()
        return (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    }

    private companion object {
        /** Ruby is about half the base type in real Japanese typesetting. */
        const val RATIO = 0.62f

        /** Breathing room between the kana and the top of the kanji. */
        const val KANA_GAP = 0.16f

        /** Extra space between lines once a reading hangs over each of them. */
        const val LINE_GAP = 0.55f

        /** A little spare, so a taller font never shaves the top row. */
        const val SLACK = 0.08f

        /** The source line's own leading, kept from the layout it replaced. */
        const val MULTIPLIER = 1.25f

        /** Faint enough to read past, strong enough to read. */
        const val FADE = 0.8f
    }
}

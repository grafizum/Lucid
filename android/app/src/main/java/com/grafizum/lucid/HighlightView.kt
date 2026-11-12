package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The selection wash, in the manner of Google Lens: one rounded band per line
 * of the text that was read, tight to the words, instead of a box around the
 * whole bubble. The bands sit on the glyphs like a text selection, so what was
 * picked is obvious at a glance — and they vanish with the card.
 */
class HighlightView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val bands = ArrayList<RectF>(6)
    private val scratch = RectF()

    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.wash)
    }

    private var originX = 0f
    private var originY = 0f

    init {
        alpha = 0f
    }

    /** [lines] are in screen coordinates; the view sits at ([left], [top]). */
    fun select(lines: List<android.graphics.Rect>, left: Int, top: Int) {
        originX = left.toFloat()
        originY = top.toFloat()

        bands.clear()
        for (line in lines) {
            // A hair of bleed around the glyphs, the way a native selection
            // sits a pixel proud of the text it covers.
            bands.add(
                RectF(
                    line.left - originX - 1.5f * density,
                    line.top - originY - density,
                    line.right - originX + 1.5f * density,
                    line.bottom - originY + density,
                ),
            )
        }
        invalidate()
    }

    fun reveal() {
        animate().cancel()
        animate().alpha(1f).setDuration(130).setInterpolator(DecelerateInterpolator()).start()
    }

    fun conceal() {
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(170)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val radius = 3.5f * density
        for (band in bands) {
            scratch.set(
                band.left.coerceAtLeast(0f),
                band.top.coerceAtLeast(0f),
                band.right.coerceAtMost(width.toFloat()),
                band.bottom.coerceAtMost(height.toFloat()),
            )
            if (scratch.width() <= 0f || scratch.height() <= 0f) continue
            canvas.drawRoundRect(scratch, radius, radius, wash)
        }
    }
}

package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * The "still working" signal: a short piece of a circle that runs around the
 * lens button's own border, like a lit segment of its edge travelling round.
 *
 * Drawn on the bubble's edge while a read is in flight, and inline in the card
 * while the translation is coming back. No track, no full ring — just the moving
 * piece, so it reads as the button breathing rather than as a spinner on top.
 */
class LoadingRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val stroke = 3f * density

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = context.getColor(R.color.clay_300)
        strokeCap = Paint.Cap.ROUND
    }

    /** The short, dimmer tail behind the head, so the piece reads as travelling. */
    private val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = (context.getColor(R.color.clay_500) and 0x00FFFFFF) or 0x5C000000
        strokeCap = Paint.Cap.ROUND
    }

    private var phase = 0f

    var running: Boolean = false
        private set

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            // One revolution every 1.15 s, wrapping so it never drifts.
            phase = (SystemClock.uptimeMillis() % 1150L) / 1150f * 360f
            invalidate()
            postOnAnimation(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        phase = 0f
        animate().cancel()
        animate().alpha(1f).setDuration(150).start()
        postOnAnimation(frame)
    }

    fun stop() {
        if (!running) return
        running = false
        removeCallbacks(frame)
        animate().cancel()
        alpha = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Exactly the button's edge: the stroke's outer half lands on the same
        // circle as the bubble's own border, so there is nothing between them.
        val radius = minOf(width, height) / 2f - stroke / 2f
        if (radius <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val left = cx - radius
        val top = cy - radius
        val right = cx + radius
        val bottom = cy + radius

        canvas.drawArc(left, top, right, bottom, phase - 90f, 78f, false, arc)
        canvas.drawArc(left, top, right, bottom, phase - 90f - 34f, 30f, false, tail)
    }
}

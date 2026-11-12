package com.grafizum.lucid

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * How a press is answered in this app: the control gives under the finger and
 * springs back.
 *
 * Android's own answer is a grey ripple rolling out from the touch point, which
 * is a different visual language entirely — a flat Material stain over soft
 * paper. Here the surface is already pressed *into* the page by shading, so the
 * press is the same idea as motion: for as long as the finger is down the chip
 * sinks a little and loses a little of its light, and on release it comes back
 * with a slight overshoot, the way the slide's well settles.
 *
 * Nothing is drawn and no background is swapped, so a chip keeps the exact
 * shape, rim and colour it was designed with — only its scale and alpha move.
 */
object Press {

    /** How far a chip sinks. Small enough to read as pressure, not as a button. */
    private const val SINK = 0.945f

    /** The light a chip loses while it is held — it is pressed, not lit. */
    private const val DIM = 0.86f

    private const val DOWN_MS = 95L
    private const val UP_MS = 260L

    /**
     * Gives [view] the press. The listener never consumes the touch, so the
     * view's own click handling — and anything else listening — still runs.
     */
    fun attach(view: View) {
        if (view.getTag(R.id.press_tag) != null) return
        view.setTag(R.id.press_tag, true)

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> sink(v, pressed = true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> sink(v, pressed = false)
            }
            false
        }
    }

    /** Gives [views] the press, for a row of controls set up together. */
    fun attachAll(vararg views: View) {
        views.forEach { attach(it) }
    }

    /**
     * Used where the press is not a touch at all — a chip that changes state
     * from code gets the same little give, so a tap and a programmatic flip
     * feel like the same control.
     */
    fun nudge(view: View) {
        sink(view, pressed = true)
        view.postDelayed({ sink(view, pressed = false) }, DOWN_MS)
    }

    private fun sink(view: View, pressed: Boolean) {
        view.animate().cancel()
        view.animate()
            .scaleX(if (pressed) SINK else 1f)
            .scaleY(if (pressed) SINK else 1f)
            .alpha(if (pressed) DIM else 1f)
            .setDuration(if (pressed) DOWN_MS else UP_MS)
            .setInterpolator(
                if (pressed) DecelerateInterpolator(1.6f) else OvershootInterpolator(1.4f),
            )
            .start()
    }
}

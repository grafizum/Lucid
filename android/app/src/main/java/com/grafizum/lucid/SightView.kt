package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The frame that shows exactly which pixels the lens will read.
 * Reads on both dark and light screens: mint edge plus a dark keyline.
 */
class SightView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val rect = RectF()

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = (context.getColor(R.color.clay_500) and 0x00FFFFFF) or 0x57000000
    }

    private val keyline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33191814
    }

    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = context.getColor(R.color.clay_400)
        strokeCap = Paint.Cap.ROUND
    }

    var scanning: Boolean = false
        set(value) {
            field = value
            corner.color = context.getColor(if (value) R.color.clay_500 else R.color.clay_400)
            edge.color = (context.getColor(R.color.clay_500) and 0x00FFFFFF) or (if (value) 0x8C000000.toInt() else 0x57000000)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val radius = 16f * density
        val inset = 1.5f * density

        rect.set(inset, inset, width - inset, height - inset)

        canvas.drawRoundRect(rect, radius, radius, keyline)
        canvas.drawRoundRect(rect, radius, radius, edge)

        val len = 14f * density
        val pad = 4f * density
        val l = pad
        val t = pad
        val r = width - pad
        val b = height - pad

        // top-left
        canvas.drawLine(l, t, l + len, t, corner)
        canvas.drawLine(l, t, l, t + len, corner)
        // top-right
        canvas.drawLine(r - len, t, r, t, corner)
        canvas.drawLine(r, t, r, t + len, corner)
        // bottom-left
        canvas.drawLine(l, b - len, l, b, corner)
        canvas.drawLine(l, b, l + len, b, corner)
        // bottom-right
        canvas.drawLine(r - len, b, r, b, corner)
        canvas.drawLine(r, b - len, r, b, corner)
    }
}

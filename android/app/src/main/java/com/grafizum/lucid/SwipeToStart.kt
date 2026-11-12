package com.grafizum.lucid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.BlurMaskFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The one CTA, as a slide with two directions. While the lens is off the well
 * rests at the left seat and a rightward drag starts it; while the lens is on
 * the well rests at the right seat and a leftward drag stops it. The two seats
 * are the same two points, so when the lens changes state the control hands
 * over without travelling — only the word and the glyph cross-fade.
 *
 * The surface is soft UI in 2D, not a glossy ball in a grey box. The track is
 * the same cream as the page and is only *pressed* into it: one inner shade
 * along the top and one inner light along the foot, and nothing else — the box
 * is spelled by its shading rather than by a border. The knob is a flat paper
 * disc, lifted by a soft shadow and two hairlines — light on its upper-left,
 * shade on its lower-right. No gradient fill on either.
 *
 * A drag is answered in colour, not in outline: a pool gathers under the knob
 * and warms from paper beige to clay as the slide commits.
 *
 * Both of those — the whole track and the knob's shadow — are painted once
 * into bitmaps in [onSizeChanged], so a drag or a settle per frame costs one
 * bitmap blit, two solid arcs, the glyph and the word. That is what keeps the
 * motion at frame rate: previously four radial gradients were rebuilt and
 * re-rasterised on every frame of every animation.
 */
class SwipeToStart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Which way the well travels to fire. */
    enum class Mode { START, STOP }

    /** The word in the track while the lens is off. Set once by the activity. */
    var startLabel: CharSequence = ""
        set(value) {
            field = value
            if (mode == Mode.START) contentDescription = value
            invalidate()
        }

    /** The word in the track while the lens is on. */
    var stopLabel: CharSequence = ""
        set(value) {
            field = value
            if (mode == Mode.STOP) contentDescription = value
            invalidate()
        }

    /**
     * Setting the mode only animates when the well is not already sitting on
     * the new seat, which is the normal case after a completed slide — the
     * activity confirms the state a beat later and nothing moves.
     */
    var mode: Mode = Mode.START
        set(value) {
            if (field == value) return
            field = value
            contentDescription = label
            settleToMode()
        }

    /** Fires with the mode that just completed, once the words have crossed. */
    var onComplete: ((Mode) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val cream50 = color(R.color.cream_50)
    private val cream100 = color(R.color.cream_100)
    private val beige300 = color(R.color.beige_300)
    private val stone600 = color(R.color.stone_600)
    private val ink900 = color(R.color.ink_900)
    private val clay400 = color(R.color.clay_400)
    private val clay600 = color(R.color.clay_600)

    private val typeface = ResourcesCompat.getFont(context, R.font.lucid_sans)

    /** Static chrome: the pressed well, painted once per size. */
    private var chrome: Bitmap? = null

    /** Static sprite: the knob's soft shadow, painted once per size. */
    private var shadowSprite: Bitmap? = null

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(cream100, cream50, 0.55f) }

    /**
     * The drag's answer: a pool of colour under the knob, clipped to the well.
     * It is shading, not an outline — the box is never stroked, so nothing on
     * the control ever lights up as a border.
     */
    private val poolPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val poolMatrix = Matrix()
    private var poolCool: Shader? = null
    private var poolWarm: Shader? = null

    /** The well as a path, so the pool can be clipped to it without a second bitmap. */
    private var capsule: Path? = null
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@SwipeToStart.typeface
        textSize = dp(13.5f)
        isSubpixelText = true
        letterSpacing = 0.01f
        fontVariationSettings = "'wght' 500"
    }

    private val glyphPath = Path()

    private var inset = dp(8f)
    private var knobRadius = dp(21f)
    private var travel = 0f
    private var shadowOffset = dp(3f)

    /** 0 at the left seat, 1 at the right seat. The only positional state. */
    private var progress = 0f
    private var knobScale = 1f
    private var labelIn = 1f
    private var checkMix = 0f
    private var armed = false
    private var dragging = false
    private var busy = false

    /** True while a mode change is carrying the well home: no hot state then. */
    private var cooling = false

    private var downAt = 0f
    private var progressAtDown = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingPx = dp(900f)
    private val velocity = VelocityTracker.obtain()

    /**
     * A quick take-off and a short landing. The old curve was a plain
     * decelerate at 2.4, which puts most of the distance in the first frames
     * and then creeps through a long tail — read as lag, not as weight.
     */
    private val land: Interpolator = PathInterpolator(0.12f, 0.72f, 0.14f, 1f)

    /** The words coming back: no overshoot, just an arrival. */
    private val fadeUp: Interpolator = DecelerateInterpolator(1.6f)

    private var animator: ValueAnimator? = null
    private var squashAnimator: ValueAnimator? = null
    private var cueAnimator: ValueAnimator? = null
    private var checkAnimator: ValueAnimator? = null
    private var labelAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setWillNotDraw(false)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    /* ── geometry ──────────────────────────────────────────────────── */

    private val restProgress: Float get() = if (mode == Mode.START) 0f else 1f
    private val endProgress: Float get() = if (mode == Mode.START) 1f else 0f

    /** How far the well is from its seat — 0 resting, 1 at the far end. */
    private val commitment: Float
        get() = if (mode == Mode.START) progress else 1f - progress

    private val label: CharSequence
        get() = if (mode == Mode.STOP) stopLabel else startLabel

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(320f).toInt(), widthMeasureSpec)
        val height = resolveSize(dp(58f).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        inset = dp(8f)
        knobRadius = (h / 2f - inset).coerceAtMost(dp(21f))
        travel = (w - 2 * inset - 2 * knobRadius).coerceAtLeast(1f)
        buildChrome(w, h)
        buildShadow()
    }

    /**
     * The well: no fill, so the page's own paper shows straight through it.
     * The whole capsule is pressed into that paper by one shadow that follows
     * its rim — a blurred copy of the pill, nudged up so the shade gathers
     * under the top edge, drawn through the pill as a clip. A light copy nudged
     * the other way leaves the bright lip along the foot. Both are painted into
     * a bitmap once per size, so a frame never pays for a blur.
     */
    private fun buildChrome(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        chrome?.recycle()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = h / 2f
        val pill = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val clip = Path().apply { addRoundRect(pill, r, r, Path.Direction.CW) }
        val blur = dp(5.5f)

        // everything that is not the pill: blurred, its edge bleeds inward and
        // becomes the shadow of a hole. Nudge the hole up and the bleed runs
        // deeper under the top edge; nudge it down and the light sits on the foot.
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(-blur * 4, -blur * 4, w + blur * 4, h + blur * 4, Path.Direction.CW)
            addRoundRect(pill, r, r, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(clip)

        canvas.save()
        canvas.translate(0f, -dp(2f))
        canvas.drawPath(
            outside,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(ink900, 0.30f)
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            },
        )
        canvas.restore()

        canvas.save()
        canvas.translate(0f, dp(1.8f))
        canvas.drawPath(
            outside,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(0xFFFFFFFF.toInt(), 0.78f)
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            },
        )
        canvas.restore()

        canvas.restore()

        chrome = bitmap

        // The well as a path, and the two pools that answer a drag: paper beige
        // while the slide is still a gesture, clay once it has committed. Both
        // are radial and centred on the knob, so one matrix moves them and no
        // shader is rebuilt during a frame.
        capsule = Path().apply { addRoundRect(pill, r, r, Path.Direction.CW) }
        val reach = h * 1.35f
        poolCool = RadialGradient(
            0f, 0f, reach,
            intArrayOf(withAlpha(beige300, 0.52f), withAlpha(beige300, 0.24f), withAlpha(beige300, 0f)),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        poolWarm = RadialGradient(
            0f, 0f, reach,
            intArrayOf(withAlpha(clay400, 0.55f), withAlpha(clay400, 0.26f), withAlpha(clay400, 0f)),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * The knob's shadow: one soft radial, baked so the frame never pays for it.
     * Its dense part sits under the disc, so only a fringe shows past the edge —
     * a shadow, not a dark ring around a coin.
     */
    private fun buildShadow() {
        // The sprite is the disc's own size plus the soft edge, and it is dropped
        // by exactly that soft edge. So its top lands on the disc's top: the
        // shadow gathers under the knob and all but disappears above it, which
        // is what stops it reading as a halo around a coin.
        val soft = dp(3.2f)
        val size = ((knobRadius + soft) * 2f).toInt().coerceAtLeast(2)
        val core = knobRadius / (knobRadius + soft)
        shadowSprite?.recycle()

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val half = size / 2f
        canvas.drawCircle(
            half, half, half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    half, half, half,
                    intArrayOf(
                        withAlpha(ink900, 0.19f),
                        withAlpha(ink900, 0.165f),
                        withAlpha(ink900, 0.085f),
                        withAlpha(ink900, 0f),
                    ),
                    floatArrayOf(0f, core * 0.92f, core, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        shadowSprite = bitmap
        shadowOffset = soft
    }

    /* ── paint ─────────────────────────────────────────────────────── */

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val c = commitment.coerceIn(0f, 1f)
        val hot = if (cooling) 0f else ((c - 0.58f) / 0.34f).coerceIn(0f, 1f)
        val knobCx = inset + knobRadius + progress.coerceIn(0f, 1f) * travel
        val cy = h / 2f

        chrome?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // No border and no highlight: the drag is answered by colour under the
        // knob. It rises with the first millimetre of travel, and shifts from
        // the paper's beige to clay as the slide passes the point of no return.
        val lift = (c * 4f).coerceAtMost(1f)
        if (lift > 0.01f) {
            poolMatrix.setTranslate(knobCx, cy)
            poolCool?.setLocalMatrix(poolMatrix)
            poolWarm?.setLocalMatrix(poolMatrix)

            canvas.save()
            capsule?.let { canvas.clipPath(it) }
            poolPaint.shader = poolCool
            poolPaint.alpha = ((1f - hot) * 255f * lift).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), h, poolPaint)
            poolPaint.shader = poolWarm
            poolPaint.alpha = (hot * 255f).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), h, poolPaint)
            canvas.restore()
            poolPaint.alpha = 255
        }

        drawLabel(canvas, knobCx, h, c, hot)
        drawKnob(canvas, knobCx, cy, hot)
    }

    /**
     * The word lifts away in the first fifth of the travel so the well sweeps
     * through empty track instead of chopping it in half. In stop mode it is
     * right-aligned and clipped from the other side.
     */
    private fun drawLabel(canvas: Canvas, knobCx: Float, h: Float, c: Float, hot: Float) {
        val text = label
        if (text.isEmpty()) return
        val alpha = (1f - c / 0.22f).coerceIn(0f, 1f) * labelIn
        if (alpha <= 0.02f) return

        labelPaint.color = blend(withAlpha(stone600, alpha), withAlpha(clay600, alpha), hot)
        val baseline = h / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f - dp(2.5f) * c

        canvas.save()
        if (mode == Mode.START) {
            labelPaint.textAlign = Paint.Align.LEFT
            val x = inset + 2 * knobRadius + dp(15f) - dp(7f) * c
            canvas.clipRect(knobCx + knobRadius * knobScale + dp(4f), 0f, width.toFloat(), h)
            canvas.drawText(text, 0, text.length, x, baseline, labelPaint)
        } else {
            labelPaint.textAlign = Paint.Align.RIGHT
            val x = width - inset - 2 * knobRadius - dp(15f) + dp(7f) * c
            canvas.clipRect(0f, 0f, knobCx - knobRadius * knobScale - dp(4f), h)
            canvas.drawText(text, 0, text.length, x, baseline, labelPaint)
        }
        canvas.restore()
    }

    /**
     * A flat disc of paper, lifted by one soft shadow and nothing else: no rim,
     * no highlight, no gradient. The edge stays a clean antialiased circle, so
     * the depth is the shadow's rather than the drawing's.
     */
    private fun drawKnob(canvas: Canvas, cx: Float, cy: Float, hot: Float) {
        val r = knobRadius * knobScale

        shadowSprite?.let { sprite ->
            val half = sprite.width / 2f
            canvas.drawBitmap(sprite, cx - half, cy + shadowOffset - half, null)
        }

        canvas.drawCircle(cx, cy, r, knobPaint)

        drawGlyph(canvas, cx, cy, hot)
    }

    /** The arrow points the way the well will travel; the check marks a landing. */
    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, hot: Float) {
        val s = knobRadius * 0.52f          // glyph half-width, in the well's units
        val mix = checkMix.coerceIn(0f, 1f)
        glyphPaint.strokeWidth = dp(1.7f)
        glyphPaint.color = blend(withAlpha(ink900, 0.62f), withAlpha(clay600, 1f), hot)

        canvas.save()
        canvas.translate(cx, cy)

        if (mix < 1f) {
            canvas.save()
            canvas.rotate(if (mode == Mode.START) 0f else 180f)
            glyphPath.reset()
            glyphPath.moveTo(-s, 0f)
            glyphPath.lineTo(s, 0f)
            glyphPath.moveTo(s - s * 0.42f, -s * 0.40f)
            glyphPath.lineTo(s, 0f)
            glyphPath.lineTo(s - s * 0.42f, s * 0.40f)
            glyphPaint.alpha = ((1f - mix) * 255f).toInt()
            canvas.drawPath(glyphPath, glyphPaint)
            canvas.restore()
        }
        if (mix > 0f) {
            glyphPath.reset()
            glyphPath.moveTo(-s * 0.62f, s * 0.06f)
            glyphPath.lineTo(-s * 0.14f, s * 0.54f)
            glyphPath.lineTo(s * 0.68f, -s * 0.48f)
            glyphPaint.alpha = (mix * 255f).toInt()
            canvas.drawPath(glyphPath, glyphPaint)
        }
        glyphPaint.alpha = 255

        canvas.restore()
    }

    /* ── input ─────────────────────────────────────────────────────── */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (busy) return false
                val cx = inset + knobRadius + progress.coerceAtLeast(0f) * travel
                if (abs(event.x - cx) > knobRadius + dp(10f)) return false
                cancelIdle()
                cancelAnimator()
                checkAnimator?.cancel()
                labelAnimator?.cancel()
                labelIn = 1f
                cooling = false
                dragging = true
                downAt = event.x
                progressAtDown = progress
                velocity.clear()
                velocity.addMovement(event)
                parent?.requestDisallowInterceptTouchEvent(true)
                squash(target = 1.03f)
                if (commitment <= 0.001f) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                velocity.addMovement(event)
                val raw = progressAtDown + (event.x - downAt) / travel
                progress = raw.coerceIn(0f, 1f)
                val nowArmed = commitment >= THRESHOLD
                if (nowArmed != armed) {
                    armed = nowArmed
                    if (nowArmed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                // the check previews in the last stretch of the travel
                checkMix = ((commitment - 0.86f) / 0.14f).coerceIn(0f, 1f)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                velocity.addMovement(event)
                velocity.computeCurrentVelocity(1000)
                // a flick counts even when it never reaches the threshold: the
                // control should answer the gesture, not the distance
                val vx = velocity.xVelocity
                val flick = if (mode == Mode.START) vx else -vx
                if (commitment >= THRESHOLD || (flick > flingPx && abs(event.x - downAt) > touchSlop)) {
                    complete()
                } else if (abs(progress - restProgress) > 0.001f) {
                    settleBack()
                } else {
                    squash(target = 1f)
                }
                velocity.clear()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                velocity.clear()
                settleBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** A click is the accessibility path (and a mouse path); it runs the full slide. */
    override fun performClick(): Boolean {
        super.performClick()
        if (busy || !isEnabled) return true
        autoSlide()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.isClickable = true
        info.text = label
        if (isEnabled && !busy) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
    }

    /* ── motion ────────────────────────────────────────────────────── */

    private fun squash(target: Float) {
        squashAnimator?.cancel()
        squashAnimator = ValueAnimator.ofFloat(knobScale, target).apply {
            duration = 140
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                knobScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun settleBack() {
        animate(progress, restProgress, 250, land) {
            armed = false
            checkMix = 0f
            squash(1f)
            animateLabelIn(1f, 140)
        }
    }

    /** The well has landed: confirm, let the words cross, then fire. */
    private fun complete() {
        val done = mode
        busy = true
        dragging = false
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS,
        )
        checkMix = 0f
        animate(progress, endProgress, 250, land) {
            squash(1f)
            animateCheck(1f, 150)
            // the action fires while the check is standing still, so whatever
            // it starts (the overlay window, a capture session) cannot stutter
            // an animation that is still running
            postDelayed({
                onComplete?.invoke(done)
                postDelayed({ handOver(done) }, 90)
            }, 210)
        }
    }

    /** The accessibility/mouse path: run the slide by itself, then fire. */
    private fun autoSlide() {
        val done = mode
        cancelIdle()
        busy = true
        animate(progress, endProgress, 280, land) {
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.LONG_PRESS,
            )
            animateCheck(1f, 150)
            postDelayed({
                onComplete?.invoke(done)
                postDelayed({ handOver(done) }, 90)
            }, 210)
        }
    }

    /**
     * The well is already parked on the other mode's seat, so handing over is
     * pure words: the check fades into the arrow that points the new way and
     * the new label rises out of the paper. Nothing travels.
     */
    private fun handOver(completed: Mode) {
        // The seat to hand over to is the one the slide just earned. Reading it
        // off the current mode instead would invert the control whenever the
        // activity reported the new lens state first.
        val next = if (completed == Mode.START) Mode.STOP else Mode.START
        if (mode == next) {
            // Already handed over by the activity: only re-affirm the check.
            if (labelAnimator?.isRunning != true) animateLabelIn(1f, 190)
            animateCheck(0f, 170)
            return
        }
        labelIn = 0f
        mode = next
    }

    /**
     * Bring the well to the seat of the current mode. Normally it is already
     * there and only the word fades in; if the activity reports a state the
     * control did not perform itself (the lens stopped from elsewhere, or a
     * start was refused) it springs home instead.
     */
    private fun settleToMode() {
        cancelIdle()
        cancelAnimator()
        checkAnimator?.cancel()
        dragging = false
        busy = false
        armed = false
        checkMix = 0f

        val rest = restProgress
        if (abs(progress - rest) < 0.001f) {
            cooling = false
            animateCheck(0f, 170)
            animateLabelIn(1f, 190)
            scheduleIdle()
            return
        }

        cooling = true
        animate(progress, rest, 260, land) {
            cooling = false
            progress = rest
            labelIn = 1f
            scheduleIdle()
            invalidate()
        }
    }

    private fun animateCheck(target: Float, duration: Long) {
        checkAnimator?.cancel()
        if (abs(checkMix - target) < 0.001f) {
            checkMix = target
            invalidate()
            return
        }
        checkAnimator = ValueAnimator.ofFloat(checkMix, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                checkMix = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateLabelIn(target: Float, duration: Long) {
        labelAnimator?.cancel()
        if (abs(labelIn - target) < 0.001f) {
            labelIn = target
            invalidate()
            return
        }
        labelAnimator = ValueAnimator.ofFloat(labelIn, target).apply {
            this.duration = duration
            interpolator = fadeUp
            addUpdateListener {
                labelIn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animate(from: Float, to: Float, duration: Long, interpolator: Interpolator, end: () -> Unit) {
        cancelAnimator()
        var cancelled = false
        animator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                progress = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // A cancel (detach, a new gesture) must never fire the action.
                    if (!cancelled) end()
                }
            })
            start()
        }
    }

    private fun cancelAnimator() {
        animator?.cancel()
        animator = null
    }

    /* ── the idle affordance ───────────────────────────────────────── */

    private val idleCue: Runnable = object : Runnable {
        override fun run() {
            if (busy || dragging || abs(progress - restProgress) > 0.001f || !isShown) {
                postDelayed(this, 2400)
                return
            }
            cueAnimator?.cancel()
            cueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1150
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    // out and back, like a hand trying the handle
                    val bump = 0.075f * sin((PI * t).toFloat())
                    progress = restProgress + if (mode == Mode.START) bump else -bump
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        progress = restProgress
                        invalidate()
                        if (isAttachedToWindow) postDelayed(idleCue, 5200)
                    }
                })
                start()
            }
        }
    }

    private fun scheduleIdle() {
        removeCallbacks(idleCue)
        postDelayed(idleCue, 5200)
    }

    private fun cancelIdle() {
        removeCallbacks(idleCue)
        cueAnimator?.cancel()
        cueAnimator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleIdle()
    }

    override fun onDetachedFromWindow() {
        cancelIdle()
        cancelAnimator()
        squashAnimator?.cancel()
        checkAnimator?.cancel()
        labelAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /* ── colour helpers ────────────────────────────────────────────── */

    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = (fraction.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = ((from ushr 24) + (((to ushr 24) - (from ushr 24)) * f)).toInt()
        val r = (((from shr 16) and 0xFF) + ((((to shr 16) and 0xFF) - ((from shr 16) and 0xFF)) * f)).toInt()
        val g = (((from shr 8) and 0xFF) + ((((to shr 8) and 0xFF) - ((from shr 8) and 0xFF)) * f)).toInt()
        val b = ((from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * f)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        /** Where the slide counts as "done" — a little under three quarters. */
        const val THRESHOLD = 0.72f
    }
}

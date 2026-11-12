package com.grafizum.lucid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.util.Locale

/**
 * The lens session.
 *
 * Owns the floating bubble, the capture frame and the translation card, and
 * runs the read → OCR → translate pipeline exactly once per tap.
 */
class LensService : Service() {

    companion object {
        const val ACTION_START = "com.grafizum.lucid.action.START"
        const val ACTION_STOP = "com.grafizum.lucid.action.STOP"

        /** Takes the lens off screen and leaves the session warm behind it. */
        const val ACTION_HIDE = "com.grafizum.lucid.action.HIDE"

        /** Puts the lens back on screen without paying for a cold start. */
        const val ACTION_SHOW = "com.grafizum.lucid.action.SHOW"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "lens_session"
        private const val NOTIFICATION_ID = 4711
        private const val TAG = "LucidLens"

        /** How often the session checks that its lens is still on screen. */
        private const val WATCHDOG_MS = 1500L

        /**
         * How long the overlays stay out of the way of a frame grab: long
         * enough for the compositor to draw one without them, short enough
         * that the lens does not visibly blink.
         */
        private const val GRAB_SETTLE_MS = 70L

        /**
         * The session that is actually up, if any.
         *
         * The lens is a window of ours, not of the system, and it can be
         * dropped behind our back. A cached boolean would then keep saying
         * "on" with nothing on screen — so the live service *is* the state.
         */
        @Volatile
        private var live: LensService? = null

        val isRunning: Boolean
            get() = live != null

        /**
         * Running *and* on screen. A lens that was put away is neither off nor
         * on: the session is warm, the engine is loaded, and one tap brings it
         * back. Callers that mean "there is a lens to look through" want this,
         * not [isRunning].
         */
        val isLensVisible: Boolean
            get() = live?.let { !it.hiddenForLater && it.bubble?.isAttachedToWindow == true } ?: false

        /** Put the lens away, or bring it back, without touching the session. */
        fun setLensVisible(context: Context, visible: Boolean) {
            runCatching {
                context.startService(
                    Intent(context, LensService::class.java).apply {
                        action = if (visible) ACTION_SHOW else ACTION_HIDE
                    },
                )
            }
        }

        /** True while a look-mode (screen capture) session is attached. */
        var hasCapture: Boolean = false
            private set

        private var listener: ((Boolean) -> Unit)? = null

        fun observe(callback: ((Boolean) -> Unit)?) {
            listener = callback
            callback?.invoke(isRunning)
        }

        /** Text-access only: no screen capture involved at all. */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LensService::class.java).apply { action = ACTION_START },
            )
        }

        /** Look mode: adds whole-screen capture on top of text access. */
        fun startWithCapture(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, LensService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LensService::class.java).apply { action = ACTION_STOP })
        }

        /**
         * Makes the live session prove its lens is on screen right now, before
         * anything that shows the state to the user draws itself — the shade,
         * above all, must never say "on" for a lens that is gone.
         */
        fun verify() {
            val session = live ?: return
            session.handler.removeCallbacks(session.watchBubble)
            session.watchBubble.run()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Marks the session up or down, and tells every place that shows it: the
     * app, the accessibility service and the quick-settings tile all read one
     * value, so none of them can drift out of step with the screen.
     */
    private fun publish(running: Boolean) {
        live = if (running) this else null
        listener?.invoke(running)
        LensState.publish()
        LensTileService.refresh(this)

        // The lens only counts as on screen once its window is attached, and a
        // window added from a tile click attaches a frame or two after the
        // service starts. Say it once more, so a lens started from the shade
        // does not leave the app reading "Lens off" over a visible lens.
        handler.removeCallbacks(republish)
        if (running) handler.postDelayed(republish, 260)
    }

    private val republish = Runnable {
        if (live === this) {
            LensState.publish()
            LensTileService.refresh(this)
        }
    }

    private lateinit var wm: WindowManager
    private var capturer: ScreenCapturer? = null
    private var translator: VisionTranslator? = null

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleIcon: ImageView? = null

    /**
     * The fixed cross the lens is carried onto to put it away: the same gesture
     * as throwing a floating timer or popup player at the dismiss mark at the
     * bottom of the screen. It exists only while the lens is being dragged, so
     * it is never something a tap for a translation can hit by mistake.
     */
    private var dropTarget: View? = null
    private var dropCx = 0
    private var dropCy = 0
    private var dropReach = 0f
    private var hoveringDrop = false

    private var sight: SightView? = null

    /** The selection wash that sits on the text the lens picked. */
    private var highlight: HighlightView? = null
    private var highlightGen = 0

    /** The mint arc that spins around the bubble while a read is in flight. */
    private var loading: LoadingRing? = null
    private var loadingParams: WindowManager.LayoutParams? = null

    private var card: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var cardStatus: TextView? = null
    private var cardBody: TextView? = null
    private var cardOriginal: RubyTextView? = null
    private var cardWords: LinearLayout? = null
    private var cardLangRow: View? = null
    private var cardSrc: TextView? = null
    private var cardDst: TextView? = null
    private var cardFoot: View? = null
    private var cardMeta: TextView? = null
    private var cardCopy: TextView? = null
    private var cardSpinner: LoadingRing? = null

    /** What the card currently holds, so its languages can be changed live. */
    private var cardSourceText: String = ""
    private var cardSourceLang: String? = null

    /** The source language the user picked for this card, when not left on auto. */
    private var cardSourcePin: String? = null
    private var cardTarget: String = ""

    /** The screen behind the read: sent to the engine, never drawn. */
    private var readContext: String = ""

    /** The language sheet shown over the card when a chip is tapped. */
    private var sheet: View? = null


    /** Long-press parks the lens: it fades back and stops reading. */
    private var dormant = false

    /**
     * The lens has been put away on purpose (from the shade's button, say). The
     * session stays up and the watchdog must not read this as a lost window.
     */
    private var hiddenForLater = false

    /** Guards shutdown() so ACTION_STOP and onDestroy can both call it. */
    private var shut = false

    private var bubbleSize = 0
    private var screenW = 0
    private var screenH = 0
    private var busy = false

    /** Bumped by every read so stale callbacks can be ignored. */
    private var readGen = 0

    /** Same, for the word-by-word meanings that arrive after a translation. */
    private var wordsGen = 0

    /** Same, for the tree lookups that now run off the main thread. */
    private var lookupGen = 0
    private var lastKey: String = ""
    private var lastRect: Rect? = null

    /** Fires when the lens has been still for a moment — the cursor behaviour. */
    private val autoRead = Runnable { readNow(auto = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        screenSize().also { screenW = it.x; screenH = it.y }
        bubbleSize = dp(42)
        translator = VisionTranslator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> {
                hideLens()
                // A show/hide that arrives with no session behind it must not
                // leave a started service that never called startForeground.
                if (!isRunning) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                showLens()
                if (!isRunning) stopSelf()
                return START_NOT_STICKY
            }
        }
        // A restart after a stop re-opens the session on this same instance.
        shut = false
        hiddenForLater = false

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val earlyData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        startForegroundSession(withProjection = resultCode != 0 && earlyData != null)

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && data != null) {
            beginProjection(resultCode, data)
        }

        showBubble()
        // No lens on screen means no session — never claim one.
        if (bubble == null) return START_NOT_STICKY

        // Text-access mode has no projection callback to report from, so the
        // session counts as running as soon as the bubble is on screen.
        publish(true)
        startWatchdog()
        return START_NOT_STICKY
    }

    /* ── session ─────────────────────────────────────────────────────── */

    private fun startForegroundSession(withProjection: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, LensService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_loop)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_stop), stopAction)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (withProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (err: Exception) {
            null
        } ?: run {
            toast("Screen access was denied.")
            return
        }

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    publish(false)
                    shutdown()
                }
            },
            handler,
        )

        val size = screenSize()
        screenW = size.x
        screenH = size.y

        val capture = ScreenCapturer(this)
        val ok = capture.attach(projection, screenW, screenH, resources.displayMetrics.densityDpi)
        capturer = capture

        if (!ok) {
            toast("Couldn't start screen capture.")
            publish(false)
            return
        }

        hasCapture = true
        publish(true)
    }

    /**
     * Rotation and density changes move the whole screen under the lens. The
     * session re-measures and pulls the bubble back into view instead of
     * leaving it off-screen, which looks exactly like a lens that vanished.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        bubbleSize = dp(42)
        val size = screenSize()
        screenW = size.x
        screenH = size.y

        val params = bubbleParams ?: return
        params.x = params.x.coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenH - bubbleSize).coerceAtLeast(0))
        val view = bubble ?: return
        runCatching { wm.updateViewLayout(view, params) }
        hideCard()
    }

    /* ── bubble ──────────────────────────────────────────────────────── */

    private fun toggleDormant() {
        dormant = !dormant
        cancelAutoRead()
        hideCard()
        hideSight()

        bubble?.animate()?.alpha(if (dormant) 0.3f else 1f)?.setDuration(160)?.start()
        toast(if (dormant) "Lens paused \u2014 long-press to wake it" else "Lens on")
    }

    /**
     * Off screen, session still up. Everything the lens had floating is taken
     * down — the bubble, the read frame, the card, the sheet — because all of
     * them are the lens, and the lens is what was put away.
     */
    private fun hideLens() {
        if (!isRunning) return
        hiddenForLater = true
        cancelAutoRead()
        hideCard()
        hideSight()
        hideLoading()
        hideLanguageSheet()

        bubble?.let { view -> runCatching { wm.removeView(view) } }
        bubble = null
        bubbleParams = null
        bubbleIcon = null
        removeDropTarget()
        LensState.publish()
    }

    private fun showLens() {
        if (!isRunning) return
        hiddenForLater = false
        showBubble()
        LensState.publish()
    }

    private fun showBubble() {
        // A bubble we still hold a reference to may already be gone from the
        // screen, so the reference alone is not proof — only an attached view
        // is. A detached one is cleared away and put back.
        val existing = bubble
        if (existing != null && existing.isAttachedToWindow) return
        if (existing != null) {
            runCatching { wm.removeView(existing) }
            bubble = null
            bubbleParams = null
            bubbleIcon = null
            removeDropTarget()
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Lucid needs the draw-over-other-apps permission.")
            shutdown()
            return
        }

        val container = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.iris)
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        }
        container.addView(icon)

        container.background = ContextCompat.getDrawable(this, R.drawable.bubble_bg)
        container.elevation = dp(12).toFloat()
        // Coming back from a dropped window must not wake a paused lens.
        container.alpha = if (dormant) 0.3f else 1f
        bubbleIcon = icon

        val params = overlayParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            val savedX = Prefs.bubbleX(this@LensService)
            val savedY = Prefs.bubbleY(this@LensService)
            x = if (savedX >= 0) savedX else screenW - bubbleSize - dp(14)
            y = if (savedY >= 0) savedY else (screenH / 3)
        }

        attachDrag(container)
        val added = runCatching { wm.addView(container, params) }.isSuccess
        if (!added) {
            Log.i(TAG, "couldn't add the lens window")
            return
        }
        bubble = container
        bubbleParams = params
    }

    /**
     * The lens can be dropped behind the session's back: the draw-over
     * permission is withdrawn, the window token is revoked, a display change
     * moves it off-screen. A session with no lens on screen must never look
     * "on" in the shade, so this puts the lens back — and when it cannot, it
     * ends the session, which turns the tile off too.
     */
    private val watchBubble = object : Runnable {
        override fun run() {
            if (live !== this@LensService) return

            if (!Settings.canDrawOverlays(this@LensService)) {
                Log.i(TAG, "draw-over permission gone \u2014 ending the session")
                shutdown()
                return
            }

            // Put away on purpose is not lost: leave it where it is.
            if (hiddenForLater) {
                handler.postDelayed(this, WATCHDOG_MS)
                return
            }

            val view = bubble
            if (view == null || !view.isAttachedToWindow) {
                Log.i(TAG, "lens bubble lost \u2014 putting it back")
                showBubble()
                if (bubble == null) {
                    Log.i(TAG, "couldn't restore the lens \u2014 ending the session")
                    shutdown()
                    return
                }
            }

            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchBubble)
        handler.postDelayed(watchBubble, WATCHDOG_MS)
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchBubble)
    }

    private fun attachDrag(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false

        val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggleDormant()
        }

        view.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressed = false
                    cancelAutoRead()
                    // Moving the lens clears the last answer so the screen is visible again.
                    hideCard()
                    hideSight()
                    hideLoading()
                    handler.postDelayed(longPress, 460)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        // Carrying the lens is also when the way out shows up:
                        // a fixed cross to drop it on, the way a floating timer
                        // is got rid of. Nothing is hidden under it while it is
                        // up that a read would need.
                        showDropTarget()
                        showSight()
                    }
                    if (moved) {
                        params.x = (startX + dx).toInt().coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (screenH - bubbleSize).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(view, params) }
                        moveSight()
                        moveLoading()
                        markDropHover(onDropTarget(params))
                        // Cursor behaviour: pause over text and it reads by itself.
                        scheduleAutoRead(650)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    val dropped = moved && onDropTarget(params)
                    hideDropTarget()
                    if (dropped) {
                        // Let go over the cross: the lens is put away, wherever
                        // it was, and the session goes with it.
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        closeLens()
                    } else if (moved) {
                        Prefs.saveBubble(this, params.x, params.y)
                        // Moving the lens is the normal trigger. With
                        // auto-translate off, letting go after a move is the read.
                        // Both are reads the lens asked for, not the person:
                        // one at rest, one on letting go. Neither of them
                        // argues with a spot that has nothing on it.
                        if (Prefs.autoTranslate(this)) scheduleAutoRead(260) else readNow(auto = true)
                    } else if (!longPressed) {
                        view.performClick()
                        // A tap is not a read any more: it opens the translation
                        // options, and a press anywhere outside puts them away
                        // again with the pick saved.
                        if (dormant) {
                            toast("Lens is paused \u2014 long-press to wake it")
                        } else {
                            showLanguageSheet(pickTarget = true, save = true)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    hideDropTarget()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * The dismiss mark, raised while the lens is being carried: a ring with a
     * cross in the middle and a small "Delete" under it, near the top edge.
     *
     * It used to be drawn around the front camera itself, which only reads as a
     * bin on a phone whose camera is a punch hole in the top centre — and even
     * there the cross sat inside the hole, where nothing can be seen. A plain
     * circled cross with a label means the same thing on every phone, whatever
     * shape its notch is and wherever its camera sits. It only exists during a
     * drag, so it can never take a tap meant for the lens.
     */
    private fun showDropTarget() {
        // A drag begun again while the last mark is still fading out takes the
        // same window back rather than starting a second one under it.
        dropTarget?.let { existing ->
            existing.animate().cancel()
            existing.alpha = 1f
            existing.scaleX = 1f
            existing.scaleY = 1f
            hoveringDrop = false
            return
        }
        val radius = dp(20)
        val width = dp(78)
        val height = radius * 2 + dp(4) + dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            contentDescription = getString(R.string.lens_close)
            elevation = dp(10).toFloat()
            alpha = 0f
        }
        val disc = FrameLayout(this).apply { background = discBackground() }
        disc.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(tint(R.color.cream_100))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        column.addView(
            disc,
            LinearLayout.LayoutParams(radius * 2, radius * 2),
        )
        column.addView(
            TextView(this).apply {
                text = getString(R.string.lens_delete)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(tint(R.color.cream_100))
                gravity = Gravity.CENTER
                setPadding(dp(9), 0, dp(9), 0)
                // A dark pill under the word, so it holds up over a white page
                // and a dark one alike.
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(0xB3191814.toInt())
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(20),
            ).apply { topMargin = dp(4) },
        )
        val top = dropTop()
        val params = overlayParams(
            width,
            height,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            x = screenW / 2 - width / 2
            y = top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Sit over the cutout instead of being pushed below it.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        if (!runCatching { wm.addView(column, params) }.isSuccess) {
            Log.i(TAG, "couldn't add the dismiss mark")
            return
        }
        dropTarget = column
        dropCx = screenW / 2
        dropCy = top + radius
        dropReach = (radius + dp(14)).toFloat()
        hoveringDrop = false
        column.animate().alpha(1f).setDuration(120).start()
    }

    /**
     * How far down the mark starts: just clear of the cutout, so the cross is
     * always on visible screen instead of behind a camera, and near the top
     * corner of the screen where nothing being read usually sits.
     */
    private fun dropTop(): Int {
        val cutoutBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                wm.currentWindowMetrics.windowInsets.displayCutout
                    ?.boundingRects?.maxByOrNull { it.width() * it.height() }?.bottom
            }.getOrNull()?.takeIf { it > 0 }
        } else {
            null
        }
        return (cutoutBottom ?: dp(20)) + dp(8)
    }

    /**
     * A dark disc with a cream edge. The cross inside it is cream, so the disc
     * has to be dark under it: that is what makes the target readable over a
     * white page as well as a night screen, instead of a ring that vanishes
     * into whichever backdrop it happens to land on.
     */
    private fun discBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xCC191814.toInt())
        setStroke(dp(2), 0xF2FBF3E2.toInt())
    }

    /** Grows the mark while the lens is over it, so the drop reads as aimed. */
    private fun markDropHover(on: Boolean) {
        if (on == hoveringDrop) return
        hoveringDrop = on
        val view = dropTarget ?: return
        view.animate().cancel()
        view.animate()
            .scaleX(if (on) 1.18f else 1f)
            .scaleY(if (on) 1.18f else 1f)
            .setDuration(110)
            .start()
    }

    /** Whether the lens, at this position, is on the delete mark. */
    private fun onDropTarget(lens: WindowManager.LayoutParams): Boolean {
        if (dropReach <= 0f) return false
        val half = bubbleSize / 2f
        val dx = (lens.x + half) - dropCx
        val dy = (lens.y + half) - dropCy
        // Generous on purpose: carrying the lens up towards the mark is enough,
        // without having to land it on the circle to the pixel.
        return dx * dx + dy * dy <= dropReach * dropReach
    }

    /** Takes the mark away once the lens has been let go. */
    private fun hideDropTarget() {
        val view = dropTarget ?: return
        hoveringDrop = false
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(110).withEndAction {
            // Taken back by a new drag in the meantime: leave it standing.
            if (dropTarget !== view) return@withEndAction
            dropTarget = null
            dropReach = 0f
            runCatching { wm.removeView(view) }
        }.start()
    }

    /** Drops the mark at once — used when the whole session is going away. */
    private fun removeDropTarget() {
        val view = dropTarget ?: return
        dropTarget = null
        dropReach = 0f
        hoveringDrop = false
        runCatching { wm.removeView(view) }
    }

    /** The cross: the lens leaves the screen, and the session goes with it. */
    private fun closeLens() {
        toast("Lens off")
        shutdown()
    }

    /* ── capture frame ───────────────────────────────────────────────── */

    /**
     * What a pixel read looks at: the patch of screen the lens itself is on.
     *
     * It used to be a wide strip below the bubble, which was the only way to
     * keep the lens out of its own frame — but then the words under the lens
     * were never in the picture, and the read answered with whatever line
     * happened to be nearest in the strip. The lens now steps out of the frame
     * instead, and the crop follows the lens.
     */
    private fun sightRect(): Rect {
        val params = bubbleParams
        val cx = params?.let { it.x + bubbleSize / 2 } ?: (screenW / 2)
        val cy = params?.let { it.y + bubbleSize / 2 } ?: (screenH / 3)

        val side = (bubbleSize + dp(120)).coerceAtMost(screenW - dp(16))
        val left = (cx - side / 2).coerceIn(dp(8), (screenW - side - dp(8)).coerceAtLeast(dp(8)))
        val top = (cy - side / 2).coerceIn(dp(8), (screenH - side - dp(8)).coerceAtLeast(dp(8)))
        return Rect(left, top, left + side, top + side)
    }

    /**
     * The lens and the card are windows over the screen, so a captured frame
     * would contain them. They are made transparent for the frame and put back
     * after it: the lens at whichever alpha it is meant to have, the card as it
     * was.
     */
    private fun hideForFrame() {
        bubble?.alpha = 0f
        sight?.alpha = 0f
        // The wash from the last answer sits over the very words about to be
        // read, so it goes with them.
        highlight?.alpha = 0f
        card?.alpha = 0f
    }

    private fun backAfterFrame() {
        bubble?.alpha = if (dormant) 0.3f else 1f
        sight?.alpha = 1f
        highlight?.alpha = 1f
        card?.alpha = 1f
    }

    private fun showSight() {
        // The crop frame only means something when pixels are being read.
        if (capturer?.isReady != true) return

        val rect = sightRect()
        if (sight == null) {
            val view = SightView(this)
            val params = overlayParams(
                rect.width(),
                rect.height(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            ).apply {
                x = rect.left
                y = rect.top
            }
            runCatching { wm.addView(view, params) }
            sight = view
        } else {
            moveSight()
        }
    }

    private fun moveSight() {
        val view = sight ?: return
        val rect = sightRect()
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun hideSight() {
        val view = sight ?: return
        runCatching { wm.removeView(view) }
        sight = null
    }

    /* ── selection highlight ────────────────────────────────────────── */

    /**
     * A soft wash exactly over the text the lens picked — like Android's
     * native selection highlight. It shows you *what* was read the instant
     * the node is found, before the translation card appears, and it hides
     * with the card.
     */
    private fun showHighlight(
        bounds: Rect,
        text: String,
        from: Int = 0,
        to: Int = text.length,
        chars: Array<RectF?>? = null,
        roomySpacing: Float = TextLines.LINE_HEIGHT,
        anchor: Point? = null,
    ) {
        highlightGen++
        Log.i(TAG, "highlight $from..$to of ${text.length} in ${bounds.width()}x${bounds.height()}")
        val view: HighlightView
        if (highlight == null) {
            view = HighlightView(this)
            highlight = view
        } else {
            view = highlight!!
        }

        // One band per visible line, tight to the glyphs — the Lens selection
        // look, rather than a slab around the whole bubble. Only the characters
        // that were actually read are covered, cut from the app's own character
        // boxes when it published them.
        val bands = TextLines.bands(
            text,
            bounds,
            resources.displayMetrics.density,
            from,
            to,            chars,
            roomySpacing,
            anchor,
        )

        view.select(bands, bounds.left, bounds.top)

        val params = overlayParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            x = bounds.left
            y = bounds.top
        }

        if (view.parent == null) {
            runCatching { wm.addView(view, params) }
            // A freshly-added overlay window may not run a property animation
            // until it has been laid out, so set alpha directly on first add.
            view.alpha = 1f
        } else {
            runCatching { wm.updateViewLayout(view, params) }
            // Always: reveal() cancels a fade-out that may still be running.
            view.reveal()
        }
    }

    private fun hideHighlight() {
        val view = highlight ?: return
        val gen = highlightGen
        view.conceal()
        // Remove only if it is still hidden and never re-shown when the fade
        // has finished — a re-show bumps the generation (and the alpha), so
        // the view must survive.
        view.postDelayed({
            if (gen == highlightGen && view.alpha <= 0.05f && highlight === view) {
                runCatching { wm.removeView(view) }
                highlight = null
            }
        }, 240)
    }

    private fun setScanning(on: Boolean) {
        (sight as? SightView)?.scanning = on
        if (on) showLoading() else hideLoading()
        // No background swap: the only signal that it is working is the lit
        // piece of the button's own border running around it.
    }

    /* ── loading ─────────────────────────────────────────────────────── */

    /**
     * A ring drawn around the bubble, so "it is working" is visible at the
     * cursor itself and not only down in the card.
     */
    private fun showLoading() {
        bubbleParams ?: return

        val view = loading
        if (view != null) {
            moveLoading()
            view.start()
            return
        }

        val ring = LoadingRing(this)
        // Exactly the button's size, so the lit piece runs around its own border.
        val size = bubbleSize
        val center = lensCenter()
        val params = overlayParams(
            size,
            size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            x = center.x - size / 2
            y = center.y - size / 2
        }

        val added = runCatching { wm.addView(ring, params) }.isSuccess
        if (!added) return

        loading = ring
        loadingParams = params
        ring.start()
    }

    private fun moveLoading() {
        val params = loadingParams ?: return
        val view = loading ?: return
        val size = bubbleSize
        val center = lensCenter()
        params.x = center.x - size / 2
        params.y = center.y - size / 2
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun hideLoading() {
        val view = loading ?: return
        view.stop()
        runCatching { wm.removeView(view) }
        loading = null
        loadingParams = null
    }

    /* ── pipeline ────────────────────────────────────────────────────── */

    private fun lensCenter(): Point {
        val params = bubbleParams ?: return Point(screenW / 2, screenH / 2)
        return Point(params.x + bubbleSize / 2, params.y + bubbleSize / 2)
    }

    private fun scheduleAutoRead(delayMs: Long) {
        handler.removeCallbacks(autoRead)
        if (dormant || !Prefs.autoTranslate(this)) return
        handler.postDelayed(autoRead, delayMs)
    }

    private fun cancelAutoRead() {
        handler.removeCallbacks(autoRead)
    }

    /**
     * Text access first: the accessibility tree gives the exact string and its
     * bounds, with no pixels and no capture permission involved — but only when
     * the lens is on the words. Anything the tree cannot give up at that spot
     * is read as pixels: an app that hides its text, a photo, a video frame, a
     * sheet drawn over the page.
     */
    private fun readNow(auto: Boolean = false, retry: Int = 2) {
        if (busy || dormant) {
            Log.i(TAG, "read skipped (busy=$busy dormant=$dormant)")
            return
        }

        val target = Prefs.targetLanguage(this)
        val center = lensCenter()

        if (LensAccessibilityService.isRunning()) {
            // Walking the tree — and asking a node where each of its characters
            // sits — is a call into the app under the lens, so it happens off
            // the main thread and the answer comes back to it to draw.
            val gen = ++lookupGen
            scope.launch {
                val hit = withContext(Dispatchers.Default) {
                    runCatching {
                        LensAccessibilityService.textAt(center.x, center.y)
                    }.getOrNull()
                }
                if (gen != lookupGen || dormant) return@launch

                val boxes = hit?.chars?.count { it != null } ?: 0
                // The words themselves are the user's: only the shape of the
                // hit is logged, which is all a lookup ever needs to be debugged.
                Log.i(
                    TAG,
                    "read at ${center.x},${center.y} hitLen=${hit?.text?.length ?: 0} boxes=$boxes " +
                        "label=${hit?.isLabel} ctx=${hit?.context?.lines()?.count() ?: 0}",
                )

                // A label — an icon's description, a photo's alt text — is not
                // text you can see. With pixels to hand, read what is actually
                // drawn there instead: the caption inside the photo, the words
                // burned into the video.
                val labelsOnly = hit?.isLabel == true && capturer?.isReady == true

                // Written exactly where the lens is: a node's box is not its
                // text, so a hit alone does not mean there is anything under it.
                if (hit != null && !labelsOnly && readHit(hit, target, center, auto)) return@launch

                if (hit == null && retry > 0) {
                    // Accessibility trees are rebuilt right after a screen
                    // change; a couple of short second looks catch the moment
                    // the lens lands on fresh text.
                    handler.postDelayed({ readNow(auto, retry = retry - 1) }, 140)
                } else {
                    readPixels(auto, center, target, sawText = hit != null)
                }
            }
            return
        }

        readPixels(auto, center, target, sawText = false)
    }

    /**
     * The tree path: a sentence can carry two languages, so only the run the
     * lens is physically over is translated — and the wash covers exactly the
     * characters that run holds.
     *
     * Returns false, having drawn nothing, when the lens is not actually on the
     * text: a node's box is not its glyphs, so a padded row, a chat bubble or a
     * paragraph stretched to a page's column all contain a lot of space its
     * lines do not. Those are the places where a lens that read "whatever node
     * I am inside" would answer for a line it is nowhere near.
     */
    private fun readHit(
        hit: LensAccessibilityService.Hit,
        target: String,
        center: Point,
        auto: Boolean,
    ): Boolean {
        val focus = TextRun.at(
            hit.text,
            hit.bounds,
            center.x,
            center.y,
            resources.displayMetrics.density,
            hit.chars,
            hit.roomySpacing,
        )

        val focusBox = focusBounds(hit.text, hit.bounds, focus, hit.chars, hit.roomySpacing, center)
        // A couple of dp of grace: the band is cut to the glyphs, and a lens
        // aimed at the gap between two lines should still read the line it is in.
        val band = UnderLens.Box(focusBox.left, focusBox.top, focusBox.right, focusBox.bottom)
        if (!UnderLens.holds(band, center.x, center.y, slack = dp(2))) return false

        hideSight()
        // The screen behind this line travels with it to the engine, so a later
        // language switch on this card can ask with the same neighbours.
        readContext = hit.context
        markTarget(focusBox)
        showHighlight(
            hit.bounds,
            hit.text,
            focus.start,
            focus.end,
            hit.chars,
            hit.roomySpacing,
            center,
        )

        val key = "text|$target|${focus.text}"
        if (key == lastKey && card != null) return true
        lastKey = key

        busy = true
        setScanning(true)
        renderScanning()

        // A late status callback from an earlier read must not repaint the
        // card over this one, so every read carries its generation.
        val gen = ++readGen
        scope.launch {
            val outcome = try {
                translator?.translate(
                    focus.text,
                    target,
                    onStatus = { phase ->
                        handler.post { if (gen == readGen) updateScanningPhase(phase) }
                    },
                    screenContext = hit.context,
                ) ?: Outcome(error = "Translator unavailable.")
            } catch (err: Exception) {
                Outcome(error = "Something went wrong while reading.")
            }
            Log.i(TAG, "outcome error=${outcome.error} empty=${outcome.empty} provider=${outcome.provider}")
            // Cancelled or superseded: this answer is stale, and it must not
            // touch the state of the read that replaced it.
            if (gen != readGen) return@launch
            busy = false
            setScanning(false)
            renderOutcome(outcome, target, auto)
        }
        return true
    }

    /**
     * Look mode: the pixels themselves — which is how a photo, a video frame, a
     * PDF or anything else merely drawn on screen gets read, tree or no tree.
     *
     * [sawText] says a node was under the lens but its text was not, which is
     * worth telling apart from a spot with nothing at all.
     */
    private fun readPixels(auto: Boolean, center: Point, target: String, sawText: Boolean) {
        // Nothing in the tree means nothing to read as context either.
        readContext = ""
        val capture = capturer
        if (capture == null || !capture.isReady) {
            if (auto) {
                // Nothing under the lens: don't leave the last answer behind,
                // it would read as if it described this spot.
                hideCard()
            } else {
                renderError(
                    if (sawText) {
                        "Nothing under the lens — move it onto the words."
                    } else {
                        "Nothing here to read. Look mode reads images, video and anything else drawn on screen — turn it on in Lucid."
                    },
                )
            }
            return
        }

        busy = true
        setScanning(true)

        val gen = ++readGen
        scope.launch {
            val rect = sightRect()
            val inset = dp(7)
            val crop = Rect(
                rect.left + inset,
                rect.top + inset,
                rect.right - inset,
                rect.bottom - inset,
            )

            // The lens and the card are windows lying on top of the screen, so
            // a frame taken now would contain them, and the lens would be
            // sitting on its own dark disc instead of on the words. They step
            // out of the frame for the grab and come straight back.
            hideForFrame()
            val bitmap = try {
                withContext(Dispatchers.Default) {
                    // A beat for the compositor to draw the frame without them.
                    SystemClock.sleep(GRAB_SETTLE_MS)
                    capture.grab(crop)
                }
            } finally {
                backAfterFrame()
            }

            if (bitmap == null) {
                if (gen == readGen) {
                    busy = false
                    setScanning(false)
                    if (!auto) renderError("Couldn't grab a frame. Try again.")
                }
                return@launch
            }

            // Cancelled while the frame was being taken: nothing left to show.
            if (gen != readGen) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return@launch
            }

            // The card appears after the frame is taken, so it is never part of it.
            showSight()
            renderScanning()

            val point = Point(center.x - crop.left, center.y - crop.top)
            val outcome = try {
                translator?.read(bitmap, point, target, slack = dp(3)) { phase ->
                    handler.post { if (gen == readGen) updateScanningPhase(phase) }
                } ?: Outcome(error = "Translator unavailable.")
            } catch (err: Exception) {
                Outcome(error = "Something went wrong while reading.")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            // Cancelled or superseded: leave the current read's state alone.
            if (gen != readGen) return@launch
            busy = false
            setScanning(false)

            // Nothing under the lens: drop the old answer rather than leave it
            // hanging where it no longer describes anything.
            if (outcome.empty) {
                hideSight()
                hideCard()
                return@launch
            }
            outcome.box?.let { box ->
                val rect = Rect(
                    crop.left + box.left,
                    crop.top + box.top,
                    crop.left + box.right,
                    crop.top + box.bottom,
                )
                markTarget(rect)
                showHighlight(rect, outcome.source, 0, outcome.source.length)
            }
            renderOutcome(outcome, target, auto)
        }
    }

    /* ── where the card sits ─────────────────────────────────────────── */

    /**
     * What the card hangs from: the run that was read, not the node around it,
     * so hovering one Japanese phrase in a mixed sentence puts the card under
     * that phrase.
     */
    private fun focusBounds(
        text: String,
        bounds: Rect,
        focus: TextRun.Focus,
        chars: Array<RectF?>? = null,
        roomySpacing: Float = TextLines.LINE_HEIGHT,
        anchor: Point? = null,
    ): Rect {
        val bands = TextLines.bands(
            text,
            bounds,
            resources.displayMetrics.density,
            focus.start,
            focus.end,
            chars,
            roomySpacing,
            anchor,
        )
        if (bands.isEmpty()) return bounds
        val union = Rect(bands.first())
        for (band in bands) union.union(band)
        return union
    }

    /** Remember what the lens picked so the card can sit right under it. */
    private fun markTarget(bounds: Rect) {
        val pad = dp(3)
        lastRect = Rect(
            (bounds.left - pad).coerceAtLeast(0),
            (bounds.top - pad).coerceAtLeast(0),
            (bounds.right + pad).coerceAtMost(screenW),
            (bounds.bottom + pad).coerceAtMost(screenH),
        )
    }


    /* ── card ────────────────────────────────────────────────────────── */

    private fun ensureCard(): View {
        card?.let { return it }

        val holder = FrameLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipToPadding = false
        }
        val content = LayoutInflater.from(this).inflate(R.layout.overlay_card, holder, false)
        holder.addView(content)
        holder.elevation = dp(20).toFloat()

        cardStatus = content.findViewById(R.id.statusText)
        cardBody = content.findViewById(R.id.bodyText)
        cardOriginal = content.findViewById(R.id.originalText)
        cardWords = content.findViewById(R.id.wordsBox)
        cardLangRow = content.findViewById(R.id.langRow)
        cardSrc = content.findViewById(R.id.srcChip)
        cardDst = content.findViewById(R.id.dstChip)
        cardFoot = content.findViewById(R.id.footRow)
        cardMeta = content.findViewById(R.id.metaText)
        cardCopy = content.findViewById(R.id.copyBtn)
        cardSpinner = content.findViewById(R.id.statusSpinner)

        val closeBtn = content.findViewById<View>(R.id.closeBtn)
        Press.attach(closeBtn)
        listOfNotNull(cardDst, cardSrc, cardCopy).forEach { Press.attach(it) }
        closeBtn.setOnClickListener { hideCard() }

        // Both language chips are live: tap one to re-translate just this card
        // into another language. Nothing is saved — it reverts on the next read.
        cardDst?.setOnClickListener { showLanguageSheet(pickTarget = true) }
        cardSrc?.setOnClickListener { showLanguageSheet(pickTarget = false) }

        // Copy hands over the words that were scanned, not the translation, so
        // what lands in the clipboard is the original message.
        cardCopy?.setOnClickListener {
            cardSourceText.takeIf { it.isNotBlank() }?.let { copyText(it) }
        }

        // A touch anywhere else on the screen takes the card away with it, the way
        // a popup closes when you press outside it. Presses inside still work.
        holder.setOnTouchListener { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) return@setOnTouchListener false
            // While the language sheet is up it owns the screen; its scrim
            // closes only the sheet, never the translation behind it.
            if (sheet != null) return@setOnTouchListener false
            // A press outside while it is still reading cancels the read and
            // clears the screen, the same as pressing outside a finished card.
            if (busy) {
                cancelRead()
            } else {
                hideCard()
                lastKey = ""
            }
            false
        }

        val width = minOf(dp(330), screenW - dp(20))
        val params = overlayParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            x = dp(10)
            y = screenH / 2
        }

        runCatching { wm.addView(holder, params) }

        holder.post { positionCard() }

        card = holder
        cardParams = params
        return holder
    }

    private fun positionCard() {
        val holder = card ?: return
        val params = cardParams ?: return
        val rect = lastRect ?: sightRect()
        val height = holder.height.takeIf { it > 0 } ?: dp(160)
        val gap = dp(10)

        var top = rect.bottom + gap
        if (top + height > screenH - dp(16)) {
            val above = rect.top - gap - height
            top = if (above >= dp(16)) above else screenH - height - dp(16)
        }

        params.x = dp(10)
        params.y = top.coerceIn(dp(8), (screenH - height - dp(8)).coerceAtLeast(dp(8)))
        runCatching { wm.updateViewLayout(holder, params) }
    }

    private fun hideCard() {
        hideLanguageSheet()
        // A word list still in flight must not be drawn onto the next card.
        wordsGen++
        readContext = ""
        val holder = card ?: return
        cardSpinner?.stop()
        runCatching { wm.removeView(holder) }
        card = null
        cardParams = null
        hideHighlight()
    }

    /**
     * A press outside while the lens is still reading: the work in flight is
     * dropped and the screen goes back to the bare lens, exactly as if the
     * answer had already arrived and been dismissed. Bumping the generations
     * is what makes the cancelled read stay gone — it cannot draw itself back
     * over the screen a moment later.
     */
    private fun cancelRead() {
        readGen++
        lookupGen++
        wordsGen++
        busy = false
        setScanning(false)
        hideCard()
        hideSight()
        lastKey = ""
    }

    /* ── live language change ────────────────────────────────────────── */

    /**
     * A dimmed sheet over the card, so either language of the current
     * translation can be changed on the spot. Opened from a chip, the pick
     * is not persisted: the next thing the lens reads goes back to the
     * language set in the app. Opened from the lens itself ([save]), the pick
     * is kept for every read that follows.
     */
    private fun showLanguageSheet(pickTarget: Boolean, save: Boolean = false) {
        // A chip re-translates what is already on screen, so it needs a card to
        // work on. The lens opens the list on its own.
        if (sheet != null) return
        if (!save && (busy || card == null)) return

        val current = when {
            save -> Prefs.targetLanguage(this)
            pickTarget -> cardTarget.ifBlank { Prefs.targetLanguage(this) }
            // The sheet marks the choice, not the detection: a card left on
            // auto shows Auto detect ticked, whatever the words turned out to be.
            else -> cardSourcePin ?: "auto"
        }

        val panel = LanguagePicker.build(
            this,
            current,
            title = if (pickTarget) "TRANSLATE INTO" else "READ AS",
            includeAuto = !pickTarget,
        ) { lang ->
            hideLanguageSheet()
            if (save) {
                // Picked from the lens: keep it, so every later read goes into
                // this language until it is changed again.
                Prefs.setTargetLanguage(this, lang.code)
                toast("Translating into ${lang.label}")
                return@build
            }
            if (!pickTarget) {
                // Picking pins this card to that language; Auto hands the
                // choice back to the detector.
                cardSourcePin = if (lang.code == "auto") null else lang.code
            }
            val same = if (pickTarget) {
                lang.code == cardTarget
            } else {
                lang.code == (cardSourcePin ?: "auto")
            }
            if (!same) {
                retranslate(
                    target = if (pickTarget) lang.code else cardTarget,
                    sourceHint = when {
                        pickTarget -> cardSourceLang
                        lang.code == "auto" -> null
                        else -> lang.code
                    },
                )
            }
        }

        val panelHolder = FrameLayout(this).apply {
            addView(
                panel,
                FrameLayout.LayoutParams(
                    minOf(dp(320), screenW - dp(24)),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            // Taps on the sheet itself must not reach the scrim behind it.
            setOnClickListener { }
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(tint(R.color.scrim))
            addView(
                panelHolder,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            setOnClickListener { hideLanguageSheet() }
        }

        val params = overlayParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )

        if (runCatching { wm.addView(scrim, params) }.isFailure) return
        sheet = scrim
    }

    private fun hideLanguageSheet() {
        val view = sheet ?: return
        runCatching { wm.removeView(view) }
        sheet = null
    }

    /** Re-run the same source text into [target], optionally pinning the source. */
    private fun retranslate(target: String, sourceHint: String?) {
        val source = cardSourceText
        if (source.isBlank()) return

        ensureCard()
        cardStatus?.text = "TRANSLATING"
        cardStatus?.setTextColor(tint(R.color.clay_300))
        cardBody?.text = "Translating…"
        cardBody?.setTextColor(tint(R.color.sand_500))
        cardDst?.text = "${Languages.label(target)}  ▾"
        cardFoot?.visibility = View.GONE
        showSpinner()
        busy = true
        card?.post { positionCard() }

        val gen = ++readGen
        scope.launch {
            val outcome = try {
                translator?.translate(source, target, sourceHint = sourceHint, screenContext = readContext)
                    ?: Outcome(error = "Translator unavailable.")
            } catch (err: Exception) {
                Outcome(error = "Couldn't switch language just now.")
            }
            // A press outside while the switch was running cancels it too.
            if (gen != readGen) return@launch
            busy = false

            if (outcome.error != null) {
                stopSpinner()
                cardStatus?.text = "COULDN'T SWITCH"
                cardStatus?.setTextColor(tint(R.color.rose_300))
                cardBody?.text = outcome.error
                cardBody?.setTextColor(tint(R.color.sand_500))
                cardFoot?.visibility = View.VISIBLE
                card?.post { positionCard() }
            } else {
                renderOutcome(outcome, target)
            }
        }
    }

    private fun renderScanning() {
        ensureCard()
        // A fresh read detects afresh: whatever was pinned belonged to the
        // card before, not to these words.
        cardSourcePin = null
        cardStatus?.text = "READING"
        cardStatus?.setTextColor(tint(R.color.clay_300))
        cardBody?.text = "Reading…"
        cardBody?.setTextColor(tint(R.color.sand_500))
        cardLangRow?.visibility = View.GONE
        cardOriginal?.visibility = View.GONE
        clearWords()
        cardFoot?.visibility = View.GONE
        showSpinner()
        card?.post { positionCard() }
    }

    private fun updateScanningPhase(phase: String) {
        val translating = phase == "Translating" || phase == "Trying online"
        cardStatus?.text = if (translating) "TRANSLATING" else "READING"
        cardBody?.text = if (translating) "Translating…" else "Reading…"
        showSpinner()
        card?.post { positionCard() }
    }

    private fun showSpinner() {
        val spinner = cardSpinner ?: return
        spinner.visibility = View.VISIBLE
        spinner.start()
    }

    private fun stopSpinner() {
        cardSpinner?.stop()
        cardSpinner?.visibility = View.GONE
    }

    private fun renderOutcome(outcome: Outcome, target: String, auto: Boolean = false) {
        // A read the lens started by itself, because it came to rest over
        // nothing readable, says nothing at all: the person is moving the lens
        // out of the way, and a card about a spot they are leaving is noise.
        // A tap is a question, and that one still gets an answer.
        if (auto && (outcome.error != null || outcome.empty)) {
            hideSight()
            hideCard()
            return
        }

        if (outcome.error != null) {
            renderError(outcome.error)
            return
        }

        if (outcome.empty) {
            ensureCard()
            stopSpinner()
            cardStatus?.text = "NOTHING FOUND"
            cardStatus?.setTextColor(tint(R.color.amber_300))
            cardBody?.text = "No readable text in the frame.\nDrag the lens over a message or caption and tap it again."
            cardBody?.setTextColor(tint(R.color.sand_500))
            cardLangRow?.visibility = View.GONE
            cardOriginal?.visibility = View.GONE
            clearWords()
            cardFoot?.visibility = View.GONE
            card?.post { positionCard() }
            return
        }

        ensureCard()
        stopSpinner()

        cardStatus?.text = if (outcome.sameLanguage) "ALREADY ${Languages.label(target).uppercase(Locale.ROOT)}" else "TRANSLATED"
        cardStatus?.setTextColor(tint(R.color.sand_300))

        cardSourceText = outcome.source
        cardSourceLang = outcome.detected
        cardTarget = target

        val temporary = target != Prefs.targetLanguage(this)

        cardLangRow?.visibility = View.VISIBLE
        // The left chip says how the source was chosen, then what was heard:
        // it never silently becomes the language it guessed.
        val pin = cardSourcePin
        val heard = outcome.detected
        cardSrc?.text = when {
            pin != null -> "${Languages.short(pin)}  ▾"
            heard != null -> "Auto select (${Languages.short(heard)})  ▾"
            else -> "Auto select  ▾"
        }
        cardDst?.text = "${Languages.label(target)}  ▾"
        // A muted chip marks a target that differs from the saved setting.
        cardDst?.background = ContextCompat.getDrawable(
            this,
            if (temporary) R.drawable.chip_dark_bg else R.drawable.chip_accent_dark_bg,
        )
        cardDst?.setTextColor(tint(if (temporary) R.color.cream_50 else R.color.clay_300))

        val shown = outcome.translated ?: outcome.source
        cardBody?.text = shown
        cardBody?.setTextColor(tint(R.color.cream_50))

        cardOriginal?.visibility = if (outcome.source.isNotBlank() && outcome.source != shown) View.VISIBLE else View.GONE
        cardOriginal?.setRuby(outcome.source, rubiesOf(outcome))

        cardFoot?.visibility = View.VISIBLE
        cardMeta?.text = buildList {
            add(if (outcome.provider == "online") "online" else "on-device")
            add(Languages.label(outcome.detected ?: target))
            if (temporary) add("temporary")
        }.joinToString("  ·  ")
        cardCopy?.text = "Copy"
        cardCopy?.setTextColor(tint(R.color.sand_500))

        // A language written without spaces gets its words spelled out one by
        // one underneath, filled in a moment after the translation itself.
        clearWords()
        if (outcome.provider == "online" && !outcome.sameLanguage && Words.crowded(outcome.detected)) {
            showWords(outcome, target)
        }

        card?.post { positionCard() }
    }

    /**
     * Word by word. The meanings arrive after the translation, in a second and
     * smaller request; the card grows a line per word as they land. An answer
     * that does not line up with the words exactly is dropped, so no word is
     * ever shown a meaning that belongs to its neighbour.
     */
    private fun showWords(outcome: Outcome, target: String) {
        val terms = Words.terms(outcome.source, outcome.detected)
        if (terms.size < 2) return

        val gen = ++wordsGen
        scope.launch {
            val pairs = try {
                translator?.gloss(outcome.source, outcome.detected, target) ?: emptyList()
            } catch (err: Exception) {
                emptyList()
            }
            // Cancelled, superseded, or the card is already gone: say nothing.
            if (gen != wordsGen || card == null || busy) return@launch
            if (pairs.isEmpty()) return@launch

            val box = cardWords ?: return@launch
            box.removeAllViews()
            pairs.take(SHOWN_WORDS).forEach { pair -> box.addView(wordRow(pair)) }
            box.visibility = View.VISIBLE
            // The card just grew taller, so it is re-placed both now and once
            // the new rows have been laid out.
            card?.requestLayout()
            positionCard()
            card?.post { positionCard() }
        }
    }

    /** How many words the card shows; the rest would turn it into a glossary. */
    private val SHOWN_WORDS = 6

    /**
     * One word and its meaning: the word in mint on a fixed column, the
     * meaning beside it, so the meanings form a line the eye can read down.
     */
    private fun wordRow(pair: Words.Pair): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val term = TextView(this).apply {
            text = pair.term
            setTextColor(tint(R.color.clay_300))
            textSize = 11.5f
            minWidth = dp(92)
            maxWidth = dp(150)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = ResourcesCompat.getFont(this@LensService, R.font.lucid_sans)
        }

        val meaning = TextView(this).apply {
            text = pair.meaning
            setTextColor(tint(R.color.sand_500))
            textSize = 11.5f
            setPadding(dp(12), 0, 0, 0)
            typeface = ResourcesCompat.getFont(this@LensService, R.font.lucid_sans)
        }

        row.addView(term)
        row.addView(meaning)
        return row
    }

    /** Drops the word list, rows and all, before the next one is built. */
    private fun clearWords() {
        cardWords?.removeAllViews()
        cardWords?.visibility = View.GONE
    }

    /**
     * The reading of the source's kanji, to sit above them.
     *
     * Only the online engine publishes a reading, and only Japanese gets one.
     * Kanji whose kana cannot be placed with certainty are left bare — a wrong
     * reading under the eye is worse than no reading.
     */
    private fun rubiesOf(outcome: Outcome): List<Furigana.Ruby> {
        val reading = outcome.reading
        if (reading.isNullOrBlank()) return emptyList()
        if ((outcome.detected ?: "").substringBefore('-') != "ja") return emptyList()

        val kana = Furigana.kana(reading) ?: return emptyList()
        return Furigana.of(outcome.source, kana)
    }

    private fun renderError(message: String) {
        ensureCard()
        stopSpinner()
        cardStatus?.text = "TROUBLE"
        cardStatus?.setTextColor(tint(R.color.rose_300))
        cardBody?.text = message
        cardBody?.setTextColor(tint(R.color.sand_500))
        cardLangRow?.visibility = View.GONE
        cardOriginal?.visibility = View.GONE
        clearWords()
        cardFoot?.visibility = View.GONE
        card?.post { positionCard() }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Lucid", text))
        cardCopy?.text = "Copied"
        cardCopy?.setTextColor(tint(R.color.clay_300))
        toast("Copied")
    }

    /* ── lifecycle ───────────────────────────────────────────────────── */

    private fun shutdown() {
        if (shut) return
        shut = true
        hiddenForLater = false
        stopWatchdog()
        cancelAutoRead()
        hideCard()
        hideSight()
        hideLoading()

        bubble?.let { view -> runCatching { wm.removeView(view) } }
        bubble = null
        bubbleParams = null
        bubbleIcon = null
        removeDropTarget()

        capturer?.release(keepProjection = false)
        capturer = null
        hasCapture = false

        publish(false)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        // Always tear the session down: a stopService() from the quick-settings
        // tile lands here without an ACTION_STOP, and the overlay windows must
        // not outlive the service. shutdown() is idempotent.
        shutdown()
        super.onDestroy()
        scope.cancel()
        runCatching { translator?.close() }
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Resolve a palette colour for the overlay, which has no theme of its own. */
    private fun tint(id: Int): Int = ContextCompat.getColor(this, id)

    /**
     * Overlay windows must not be inset by the status/navigation bars, otherwise
     * the drawn window and its input region disagree and taps miss the bubble.
     */
    private fun overlayParams(
        width: Int,
        height: Int,
        flags: Int,
    ): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.fitInsetsTypes = 0
        }
        return params
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

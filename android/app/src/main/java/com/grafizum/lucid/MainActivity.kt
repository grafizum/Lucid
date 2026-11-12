package com.grafizum.lucid

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Dialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.exp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var btn1: TextView
    private lateinit var btn2: TextView
    private lateinit var btn3: TextView
    private lateinit var sub1: TextView
    private lateinit var sub2: TextView
    private lateinit var sub3: TextView
    private lateinit var targetName: TextView
    private lateinit var dot4: View
    private lateinit var btn4: TextView
    private lateinit var sub4: TextView
    private lateinit var autoBtn: TextView
    private lateinit var autoHint: TextView
    private lateinit var engBtn: TextView
    private lateinit var engHint: TextView
    private lateinit var startBtn: SwipeToStart
    private lateinit var stopX: FrameLayout
    private lateinit var stopXBtn: FrameLayout
    private lateinit var lensState: TextView
    private lateinit var versionName: TextView


    /** Whether the cross is currently sitting beside the slide. */
    private var stopXShown = false
    private var stopXAnim: ValueAnimator? = null

    /** The one-time setup sheet, so returning from Settings can update it. */
    private var setupDialog: Dialog? = null

    /** Which place in Android the guide sheet is pointing at. */
    private enum class Ask { OVERLAY, TEXT, CAPTURE }

    /** Where the disclosure should lead, when it was reached on the way somewhere. */
    private var pendingAsk: Ask? = null

    /** The breathing ring on the sheet's drawing, stopped when it closes. */
    private var guidePulse: ValueAnimator? = null

    /**
     * The Settings screen the app has just sent the user to, if any. While it is
     * open the switch is polled, so the app can come straight back the moment it
     * is allowed instead of leaving the user to find their own way home.
     */
    private var waitingFor: Ask? = null
    private var waitTicks = 0
    private val waitHandler = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            val ask = waitingFor ?: return
            if (granted(ask)) {
                waitingFor = null
                comeBack(ask)
                return
            }
            // Two minutes of watching; after that the way back is their own.
            if (++waitTicks > 240) {
                waitingFor = null
                return
            }
            waitHandler.postDelayed(this, 500)
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh(); bindSetupDialog() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { startBtn.postDelayed({ refresh(); bindSetupDialog() }, 400) }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // The choice is written down; the grant itself cannot be, so this is
            // what tells an updated app that the user had Look mode on.
            Prefs.setLookMode(this, true)
            LensService.startWithCapture(this, result.resultCode, data)
            say("Look mode is on — the whole screen, every app.")
            startBtn.postDelayed({ refresh() }, 900)
        } else {
            say("Screen access declined. Text access alone still works everywhere it can read.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
        btn1 = findViewById(R.id.btn1)
        btn2 = findViewById(R.id.btn2)
        btn3 = findViewById(R.id.btn3)
        sub1 = findViewById(R.id.sub1)
        sub2 = findViewById(R.id.sub2)
        sub3 = findViewById(R.id.sub3)
        dot4 = findViewById(R.id.dot4)
        btn4 = findViewById(R.id.btn4)
        sub4 = findViewById(R.id.sub4)
        targetName = findViewById(R.id.targetName)
        autoBtn = findViewById(R.id.autoBtn)
        autoHint = findViewById(R.id.autoHint)
        engBtn = findViewById(R.id.engBtn)
        engHint = findViewById(R.id.engHint)
        startBtn = findViewById(R.id.startBtn)
        stopX = findViewById(R.id.stopX)
        stopXBtn = findViewById(R.id.stopXBtn)
        lensState = findViewById(R.id.lensState)
        versionName = findViewById(R.id.versionName)
        versionName.text = "v${BuildConfig.VERSION_NAME}"

        startBtn.startLabel = "Start the lens"
        startBtn.stopLabel = "Stop the lens"

        // Every control answers a press the same way: it gives under the finger
        // and springs back. No ripples, no grey boxes — see Press.kt.
        Press.attachAll(btn1, btn2, btn3, btn4, autoBtn, engBtn, stopXBtn)
        Press.attach(findViewById(R.id.changeTarget))

        // An "Allow" that is already on is not a question any more: it goes
        // straight to the switch. A switch still off gets the drawn screen first.
        btn1.setOnClickListener {
            if (Settings.canDrawOverlays(this)) openOverlaySettings() else askPermission(Ask.OVERLAY)
        }
        btn2.setOnClickListener {
            if (LensAccessibilityService.isEnabled(this)) {
                openAccessibilitySettings()
            } else {
                askPermission(Ask.TEXT)
            }
        }
        btn3.setOnClickListener { enableLookMode() }
        btn4.setOnClickListener { addQuickButton() }
        // The slide reads the way it is facing at the moment it lands, so one
        // control starts the lens and, from its other seat, switches it off.
        // It reports that seat itself: the control has already handed over by
        // the time the action runs.
        startBtn.onComplete = { done ->
            if (done == SwipeToStart.Mode.START) startLens() else stopLens()
        }
        stopXBtn.setOnClickListener {
            stopXBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            stopLens()
        }
        findViewById<View>(R.id.changeTarget).setOnClickListener { showLanguageDialog() }
        autoBtn.setOnClickListener { toggleAuto() }
        engBtn.setOnClickListener { toggleEngine() }

        // Edge to edge, with the bars paid for in padding instead of left to
        // fitsSystemWindows: the bottom line — version and grafizum.com — has to
        // clear the gesture bar on every device, not just most of them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ViewGroup>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPaddingRelative(
                dp(32) + bars.left,
                dp(30) + bars.top,
                dp(32) + bars.right,
                dp(24) + bars.bottom,
            )
            insets
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT

        refresh()
        // Asked as the app opens: the switches are off until they are allowed.
        startBtn.postDelayed({ askForSetup() }, 350)
    }

    override fun onResume() {
        super.onResume()
        // In front again: if a switch was flipped on the way back, carry on with
        // the next one; otherwise there is nothing left to watch.
        val finished = waitingFor
        stopWaiting()
        if (finished != null && granted(finished)) {
            startBtn.postDelayed({ nextStep(finished) }, 450)
        }
        // Coming back from a system screen means its switch may have changed.
        refresh()
        bindSetupDialog()
        LensState.observe { runOnUiThread { refresh() } }
    }

    override fun onPause() {
        super.onPause()
        LensState.observe(null)
    }

    override fun onDestroy() {
        stopWaiting()
        super.onDestroy()
    }

    /* ── actions ─────────────────────────────────────────────────────── */

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        val opened = runCatching { overlaySettingsLauncher.launch(intent) }
        if (opened.isSuccess) {
            startWaiting(Ask.OVERLAY)
        } else {
            say("Couldn't open that screen — go to Settings › Apps › Special access › Display over other apps.")
        }
    }

    /**
     * Text access is never asked for straight from the switch: the sheet that
     * says what the lens reads, and where that text goes, comes first, and
     * Android's own screen follows the agree. That disclosure is the app's,
     * separate from the system prompt and from the other switches.
     */
    private fun openAccessibilitySettings() {
        if (!Prefs.textConsent(this) && !LensAccessibilityService.isEnabled(this)) {
            // The agreement is a step of this same ask, so the way there is kept.
            askForSetup(Ask.TEXT)
            return
        }

        // Android's own accessibility screen. There is no per-service page an app
        // is allowed to open — that one is guarded by a system permission — so
        // the guide has already shown where Lucid sits in this list.
        val opened = runCatching {
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (opened.isSuccess) {
            startWaiting(Ask.TEXT)
        } else {
            say("Couldn't open Accessibility settings — find Lucid in Settings › Accessibility.")
        }
    }

    /**
     * Optional fallback for apps that hide their text. Requests the whole
     * display explicitly, so Android never offers the single-app option.
     */
    private fun enableLookMode() {
        if (!Settings.canDrawOverlays(this)) {
            askPermission(Ask.OVERLAY)
            return
        }

        // The capture dialog is one more place to know what to tap, so it gets
        // the same drawn-screen treatment as a switch in Settings.
        askPermission(Ask.CAPTURE)
    }

    /** Android's own capture dialog, once the sheet has said what to pick. */
    private fun startCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    /**
     * The one switch Android lets an app offer itself. From 13 the system puts
     * up its own "add Lucid?" sheet, so the user answers in their own words;
     * before that a tile can only be dragged in from the panel's edit screen,
     * so say that instead of asking for something the platform won't do.
     */
    private fun addQuickButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            say("Open the shade, tap the pencil, and drag Lucid into your quick buttons.")
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            say("This phone has no quick buttons to add to.")
            return
        }
        manager.requestAddTileService(
            ComponentName(this, LensTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile_mark),
            mainExecutor,
        ) { result ->
            runOnUiThread {
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                        Prefs.setTileAdded(this, true)
                        say("Lucid is in your quick buttons — tap it from the shade.")
                    }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                        Prefs.setTileAdded(this, true)
                        say("Lucid is already in your quick buttons.")
                    }
                    else -> say("Not added — the shade's pencil can still drag it in.")
                }
                refresh()
            }
        }
    }

    private fun startLens() {
        // Neither switch can be worked around: without one the lens has nowhere
        // to sit, without the other it reads nothing. So this is a decision, said
        // once in a sheet, not a line that waits under the slide.
        // The slide has already turned itself round for a lens that will not
        // start, so it is told the truth again a beat later: nothing to stop.
        if (!Settings.canDrawOverlays(this)) {
            askPermission(Ask.OVERLAY)
            startBtn.postDelayed({ refresh() }, 420)
            return
        }

        if (!LensAccessibilityService.isEnabled(this)) {
            askPermission(Ask.TEXT)
            startBtn.postDelayed({ refresh() }, 420)
            return
        }

        LensService.start(this)
        startBtn.postDelayed({ refresh() }, 500)
    }

    /**
     * What the app says when a switch is still off, or when Android is about to
     * ask to share the screen: why it matters, and a drawing of the screen the
     * user is about to meet with the one control ringed and captioned. Nothing
     * is left to be found by reading through Settings.
     */
    private fun askPermission(ask: Ask) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_permission)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)

        dialog.findViewById<TextView>(R.id.permTitle).text = when (ask) {
            Ask.OVERLAY -> "The lens needs to sit on top"
            Ask.TEXT -> "The lens needs Text access"
            Ask.CAPTURE -> "One screen to pick"
        }
        dialog.findViewById<TextView>(R.id.permBody).text = when (ask) {
            Ask.OVERLAY ->
                "Android keeps \u201CDisplay over other apps\u201D off. Without it the lens cannot float over a chat or a browser, so it does nothing at all."
            Ask.TEXT ->
                "Android keeps Text access off. Without it the lens still opens, but it reads nothing \u2014 every read comes back empty."
            Ask.CAPTURE ->
                "Android asks which part of the screen to share. The lens needs the whole screen to read anything sitting on top of other apps."
        }

        // The drawing follows the real screen: its title, the one row that
        // matters, and the way there.
        dialog.findViewById<TextView>(R.id.guidePageLabel).text = when (ask) {
            Ask.OVERLAY -> "Display over other apps"
            Ask.TEXT -> "Accessibility"
            Ask.CAPTURE -> "Start recording or casting"
        }
        dialog.findViewById<TextView>(R.id.guideRowTitle).text = when (ask) {
            Ask.OVERLAY -> "Lucid"
            Ask.TEXT -> getString(R.string.a11y_label)
            Ask.CAPTURE -> "Entire screen"
        }
        dialog.findViewById<TextView>(R.id.guideRowSub).text = when (ask) {
            Ask.OVERLAY -> "Tap this to reach its switch"
            Ask.TEXT -> "Downloaded apps \u00B7 Off"
            Ask.CAPTURE -> "So the lens sees every app, not one"
        }
        dialog.findViewById<TextView>(R.id.guideCaption).text = when (ask) {
            Ask.OVERLAY -> "\u2191 tap Lucid, then switch it on"
            Ask.TEXT -> "\u2191 tap it, then turn it on"
            Ask.CAPTURE -> "then this one \u2191"
        }
        dialog.findViewById<TextView>(R.id.guidePath).text = when (ask) {
            Ask.OVERLAY -> "Settings \u203A Apps \u203A Special access \u203A Display over other apps"
            Ask.TEXT -> "Settings \u203A Accessibility \u203A Downloaded apps \u203A ${getString(R.string.a11y_label)}"
            Ask.CAPTURE -> "Pick Entire screen, then Start now \u2014 a single app will not do."
        }
        val note = dialog.findViewById<TextView>(R.id.guideNote)
        // Sideloaded builds are greyed out until Android's own restriction is
        // lifted, which is the one trap this screen has.
        note.visibility = if (ask == Ask.TEXT) View.VISIBLE else View.GONE

        // Both Settings screens are lists, so the ringed row is the target; only
        // Android's own dialog has a button instead.
        dialog.findViewById<View>(R.id.guidePillWrap).visibility =
            if (ask == Ask.CAPTURE) View.VISIBLE else View.GONE

        val open = dialog.findViewById<TextView>(R.id.permOpen)
        val later = dialog.findViewById<TextView>(R.id.permLater)
        // Text access is disclosed before Android's switch, so someone who has
        // not agreed yet is sent to the disclosure rather than promised a screen
        // they cannot reach in one tap.
        open.text = when {
            ask == Ask.CAPTURE -> "Continue"
            ask == Ask.TEXT && !Prefs.textConsent(this) -> "Read and agree"
            else -> "Open settings"
        }
        Press.attachAll(open, later)
        open.setOnClickListener {
            dialog.dismiss()
            when (ask) {
                Ask.OVERLAY -> openOverlaySettings()
                Ask.TEXT -> openAccessibilitySettings()
                Ask.CAPTURE -> startCapture()
            }
        }
        later.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener { guidePulse?.cancel() }
        dialog.show()
        pulse(dialog.findViewById(R.id.guideRing), dialog.findViewById(R.id.guidePillRing))
    }

    /** The ring breathes, so the eye lands on it before a word is read. */
    private fun pulse(vararg rings: View) {
        guidePulse?.cancel()
        guidePulse = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { a ->
                val alpha = a.animatedValue as Float
                rings.forEach { it.alpha = alpha }
            }
            start()
        }
    }

    private fun stopLens() {
        LensService.stop(this)
        startBtn.postDelayed({ refresh() }, 400)
    }

    private fun toggleEngine() {
        val next = !Prefs.onlineEngine(this)
        Prefs.setOnlineEngine(this, next)
        applyEngine(next)
    }

    private fun applyEngine(online: Boolean) {
        engBtn.text = if (online) "Online" else "On-device"
        engBtn.setBackgroundResource(if (online) R.drawable.chip_accent_bg else R.drawable.btn_ghost)
        engBtn.setTextColor(getColor(if (online) R.color.clay_600 else R.color.stone_600))
        engHint.text = if (online) {
            "Best quality \u2014 sent to Google Translate"
        } else {
            "Offline models \u2014 nothing leaves the phone"
        }
    }

    private fun toggleAuto() {
        val next = !Prefs.autoTranslate(this)
        Prefs.setAutoTranslate(this, next)
        applyAuto(next)
    }

    private fun applyAuto(enabled: Boolean) {
        autoBtn.text = if (enabled) "On" else "Off"
        autoBtn.setBackgroundResource(if (enabled) R.drawable.chip_accent_bg else R.drawable.btn_ghost)
        autoBtn.setTextColor(
            getColor(if (enabled) R.color.clay_600 else R.color.stone_600),
        )
        autoHint.text =
            if (enabled) "Reads the moment the lens stops moving" else "Only reads when you let go of the lens"
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showLanguageDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(
            LanguagePicker.build(this, Prefs.targetLanguage(this)) { lang ->
                Prefs.setTargetLanguage(this, lang.code)
                refresh()
                Toast.makeText(this, "Translating into ${lang.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            },
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }

    /**
     * Whether the switch a place in Android leads to is on. Watching one that is
     * already on would yank the user out of Settings a moment after opening it,
     * so nothing is ever watched unless it is still off.
     */
    private fun granted(ask: Ask): Boolean = when (ask) {
        Ask.OVERLAY -> Settings.canDrawOverlays(this)
        Ask.TEXT -> LensAccessibilityService.isEnabled(this)
        Ask.CAPTURE -> true
    }

    private fun startWaiting(ask: Ask) {
        if (granted(ask)) return
        waitingFor = ask
        waitTicks = 0
        waitHandler.removeCallbacks(watch)
        waitHandler.postDelayed(watch, 500)
    }

    private fun stopWaiting() {
        waitHandler.removeCallbacks(watch)
        waitingFor = null
        waitTicks = 0
    }

    /**
     * Back to the front the moment the switch is allowed. A granted overlay
     * permission is one of the exemptions Android leaves open for exactly this,
     * which is why it is only ever needed while one of these two is being turned on.
     */
    private fun comeBack(from: Ask) {
        runCatching { getSystemService(ActivityManager::class.java)?.moveTaskToFront(taskId, 0) }
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }
        refresh()
        // Setup is one run, not a series of errands: on to the next switch.
        startBtn.postDelayed({ nextStep(from) }, 700)
    }

    /** What the setup still needs, once a switch has been allowed. */
    private fun nextStep(from: Ask) {
        if (isFinishing || isDestroyed) return
        when {
            !Settings.canDrawOverlays(this) ->
                if (from != Ask.OVERLAY) askPermission(Ask.OVERLAY)
            !LensAccessibilityService.isEnabled(this) ->
                if (from != Ask.TEXT) askPermission(Ask.TEXT)
            else -> askNotificationPermission()
        }
    }

    /* ── state ───────────────────────────────────────────────────────── */

    private fun refresh() {
        val canOverlay = Settings.canDrawOverlays(this)
        val textAccess = LensAccessibilityService.isEnabled(this)
        // A lens put away from the shade's button is not running *on screen*:
        // the slide offers "Start the lens" and brings it back.
        val running = LensService.isLensVisible
        val lookMode = LensService.hasCapture

        // Both permission rows read the same way: the button is the ask while
        // the switch is off, and the answer once it is on.
        dot1.background = ContextCompat.getDrawable(this, if (canOverlay) R.drawable.dot_ok else R.drawable.dot_step)
        btn1.text = if (canOverlay) "Allowed" else "Allow"
        btn1.setTextColor(getColor(if (canOverlay) R.color.olive_600 else R.color.ink_900))
        sub1.text = if (canOverlay) {
            "On — the lens can sit over any app"
        } else {
            "Off by default — lets the lens sit on top"
        }

        // The shade's button is the fourth switch, and the only one Android
        // lets the app add itself: the rest are the user's alone to allow.
        val quickButton = Prefs.tileAdded(this)
        dot4.background = ContextCompat.getDrawable(this, if (quickButton) R.drawable.dot_ok else R.drawable.dot_step)
        btn4.text = if (quickButton) "Added" else "Add"
        btn4.setTextColor(getColor(if (quickButton) R.color.olive_600 else R.color.ink_900))
        btn4.isEnabled = !quickButton
        sub4.text = if (quickButton) {
            "In the shade — one tap turns the lens on"
        } else {
            "Adds Lucid to the shade's quick buttons"
        }

        dot2.background = ContextCompat.getDrawable(this, if (textAccess) R.drawable.dot_ok else R.drawable.dot_step)
        btn2.text = if (textAccess) "Allowed" else "Allow"
        btn2.setTextColor(getColor(if (textAccess) R.color.olive_600 else R.color.ink_900))
        sub2.text = if (textAccess) {
            "On — the lens can read the text under it"
        } else {
            "Off by default — lets the lens read text"
        }

        // Look mode's consent lasts one session, so the live grant and the
        // user's choice are two different things: after an update the grant is
        // gone but the choice is not, and the row says so rather than quietly
        // reading "Turn on" as if it had never been asked for.
        val lookWanted = Prefs.lookMode(this)
        btn3.text = when {
            lookMode -> "On"
            lookWanted -> "Allow"
            else -> "Turn on"
        }
        btn3.setTextColor(getColor(if (lookMode) R.color.clay_600 else R.color.ink_900))
        btn3.setBackgroundResource(if (lookMode) R.drawable.chip_accent_bg else R.drawable.btn_ghost)
        btn3.isEnabled = !lookMode
        sub3.text = when {
            lookMode -> "On — images, video and hidden text, every app"
            lookWanted -> "Kept on — Android asks to share the screen each session"
            else -> "Optional — reads images, video and hidden text"
        }

        targetName.text = Languages.label(Prefs.targetLanguage(this))
        applyAuto(Prefs.autoTranslate(this))
        applyEngine(Prefs.onlineEngine(this))

        lensState.visibility = View.VISIBLE
        lensState.text = if (running) "Lens on" else "Lens off"
        lensState.setTextColor(getColor(if (running) R.color.clay_600 else R.color.stone_600))

        // Off: the well sits left and drags right. On: it sits right, drags
        // left, and the cross appears beside it as the other way to stop.
        startBtn.mode = if (running) SwipeToStart.Mode.STOP else SwipeToStart.Mode.START
        applyStopX(running)

    }

    /* ── the one-time setup ask ──────────────────────────────────────── */

    /**
     * Both switches ship off. The first thing the app does is ask for the one
     * or two that are still off, in the same sheet, with a single tap through
     * to Android's own screen for each.
     */
    private fun askForSetup(resumeTo: Ask? = null) {
        if (Settings.canDrawOverlays(this) && LensAccessibilityService.isEnabled(this)) {
            askNotificationPermission()
            return
        }
        if (setupDialog?.isShowing == true) return
        pendingAsk = resumeTo

        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_setup)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)
        val setupBtn1 = dialog.findViewById<View>(R.id.setupBtn1)
        val setupBtn2 = dialog.findViewById<View>(R.id.setupBtn2)
        val setupLater = dialog.findViewById<View>(R.id.setupLater)
        val consentAgree = dialog.findViewById<View>(R.id.consentAgree)
        val consentDecline = dialog.findViewById<View>(R.id.consentDecline)
        Press.attachAll(setupBtn1, setupBtn2, setupLater, consentAgree, consentDecline)
        // The sheet asks; the guide sheet then shows exactly where the switch
        // is before Android's own screen takes over.
        setupBtn1.setOnClickListener {
            dialog.dismiss()
            askPermission(Ask.OVERLAY)
        }
        setupBtn2.setOnClickListener {
            dialog.dismiss()
            askPermission(Ask.TEXT)
        }
        setupLater.setOnClickListener { dialog.dismiss() }
        // The agreement is the app's own, taken before Android's switch, and
        // only then does the text access row appear.
        consentAgree.setOnClickListener { afterAgree(dialog) }
        consentDecline.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (setupDialog === dialog) setupDialog = null
            // No notification ask here: closing this sheet now means going on to
            // the drawn guide, and a system prompt would cut across it. It is
            // asked once both switches are on, which is where it belongs.
        }

        setupDialog = dialog
        dialog.show()
        bindSetupDialog(dialog)
    }

    /**
     * Agreement is the answer the disclosure was waiting for, so it leads
     * straight on: to the switch it unlocks, or to the same guide every other
     * text-access ask opens with — never back to the sheet already read.
     */
    private fun afterAgree(dialog: Dialog) {
        Prefs.setTextConsent(this, true)
        dialog.dismiss()
        val next = pendingAsk
        pendingAsk = null
        when {
            next == Ask.TEXT -> openAccessibilitySettings()
            !Settings.canDrawOverlays(this) -> askPermission(Ask.OVERLAY)
            !LensAccessibilityService.isEnabled(this) -> askPermission(Ask.TEXT)
        }
    }

    /** Keeps the open sheet honest while the user is over in Settings. */
    private fun bindSetupDialog(dialog: Dialog? = setupDialog) {
        if (dialog == null || !dialog.isShowing) return

        val canOverlay = Settings.canDrawOverlays(this)
        val textAccess = LensAccessibilityService.isEnabled(this)
        if (canOverlay && textAccess) {
            dialog.dismiss()
            return
        }

        val both = !canOverlay && !textAccess
        val consented = Prefs.textConsent(this) || textAccess

        dialog.findViewById<TextView>(R.id.setupTitle).text = when {
            !consented -> "Text access, explained"
            both -> "Two switches, off by default"
            else -> "One switch left"
        }
        dialog.findViewById<TextView>(R.id.setupBody).text = when {
            !consented ->
                "Android keeps it off until you allow it. What the lens reads, and where that text goes, is below — agree first, then flip Android's switch."
            both -> "Android keeps both off until you allow them — nothing is recorded, no frame is saved."
            else -> "Android keeps this off until you allow it — nothing is recorded, no frame is saved."
        }

        dialog.findViewById<View>(R.id.setupConsent).visibility =
            if (consented) View.GONE else View.VISIBLE
        dialog.findViewById<View>(R.id.setupRow1).visibility =
            if (canOverlay) View.GONE else View.VISIBLE
        // No text access switch before the disclosure has been agreed to.
        dialog.findViewById<View>(R.id.setupRow2).visibility =
            if (textAccess || !consented) View.GONE else View.VISIBLE
    }

    private fun applyStopX(show: Boolean, animate: Boolean = true) {
        if (show == stopXShown) return
        stopXShown = show
        stopXAnim?.cancel()

        val size = dp(52)
        val gap = dp(12)
        val lp = stopX.layoutParams as ViewGroup.MarginLayoutParams
        if (!animate) {
            lp.width = if (show) size else 0
            lp.marginStart = if (show) gap else 0
            stopX.layoutParams = lp
            stopX.visibility = if (show) View.VISIBLE else View.GONE
            stopXBtn.isEnabled = show
            stopXBtn.alpha = if (show) 1f else 0f
            stopXBtn.scaleX = 1f
            stopXBtn.scaleY = 1f
            return
        }

        if (show) stopX.visibility = View.VISIBLE
        stopXBtn.isEnabled = false
        val fromW = lp.width.coerceAtLeast(0)
        val fromM = lp.marginStart
        val toW = if (show) size else 0
        val toM = if (show) gap else 0
        val fromAlpha = if (show) 0f else stopXBtn.alpha
        val toAlpha = if (show) 1f else 0f

        stopXAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (show) 300 else 190
            interpolator = if (show) spring else AccelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                lp.width = (fromW + (toW - fromW) * t).toInt()
                lp.marginStart = (fromM + (toM - fromM) * t).toInt()
                stopX.layoutParams = lp
                stopXBtn.alpha = fromAlpha + (toAlpha - fromAlpha) * t
                stopXBtn.scaleX = 0.72f + 0.28f * t
                stopXBtn.scaleY = stopXBtn.scaleX
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lp.width = toW
                    lp.marginStart = toM
                    stopX.layoutParams = lp
                    stopXBtn.alpha = toAlpha
                    stopXBtn.scaleX = 1f
                    stopXBtn.scaleY = 1f
                    stopXBtn.isEnabled = show
                    stopX.visibility = if (show) View.VISIBLE else View.GONE
                }
            })
            start()
        }
    }

    private val spring: Interpolator = Interpolator { t ->
        (1.0 - exp(-6.0 * t) * cos(9.0 * t)).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * A passing word, where nothing was decided. The space under the slide
     * belongs to the version and grafizum.com alone — never a line of text.
     */
    private fun say(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

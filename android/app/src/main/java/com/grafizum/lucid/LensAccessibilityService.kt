package com.grafizum.lucid

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads text from other apps through the accessibility tree — no screen
 * capture, no "start recording" dialog, no per-app choice.
 *
 * The user switches this on once in Settings › Accessibility. From then on the
 * lens can ask "what text is at this point?" for any app that exposes its text.
 */
class LensAccessibilityService : AccessibilityService() {

    /**
     * [chars] is the on-screen box of every character of [text], when the app
     * publishes them — a real per-character layout, not a guess. A null entry
     * is a character with no box of its own (a newline); the whole array is
     * null when the app does not answer, or answers with the node's own rect
     * for every character, and callers fall back to fitting the node's box.
     *
     * [roomySpacing] is only consulted when the node's box turns out to be too
     * roomy to measure the type from — see [TextLines.layout].
     *
     * [context] is the rest of the screen as text: read in the same pass,
     * never drawn, and handed to the engine so the line under the lens can be
     * read against what surrounds it. Empty for apps with nothing else on show.
     */
    data class Hit(
        val text: String,
        val bounds: Rect,
        val chars: Array<RectF?>? = null,
        val roomySpacing: Float = NATIVE_ROOMY_SPACING,
        val context: String = "",
        /**
         * True when the string is a node's label rather than text you can see:
         * an icon's description, a picture's alt text. It names the thing the
         * lens is over, it is not written on the screen.
         */
        val isLabel: Boolean = false,
    )

    companion object {
        @Volatile
        private var instance: LensAccessibilityService? = null

        /**
         * How tall one line of an app's text stands, as a multiple of its type
         * size, for the cases where the box is too roomy to measure the type
         * from. Android's own line spacing is 1.18, so anything above it here is
         * a box with padding of its own, or a page that set a line height.
         */
        private const val NATIVE_ROOMY_SPACING = 1.4f
        private const val WEB_LATIN_ROOMY_SPACING = 1.35f

        /** Japanese pages run roomier still — near 1.55 by measurement. */
        private const val WEB_CJK_ROOMY_SPACING = 1.55f

        fun isRunning(): Boolean = instance != null

        /** Whether the user has switched the service on, even if it isn't bound yet. */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            // The system writes the setting in its short form
            // ("pkg/.Service"), so the long form alone misses every real entry
            // and the setup screen would claim the switch is off right after a
            // restart, before the service has had a chance to bind.
            val component = ComponentName(context, LensAccessibilityService::class.java)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            return enabled.split(':', ';').any {
                val entry = it.trim()
                entry.equals(full, ignoreCase = true) || entry.equals(short, ignoreCase = true)
            }
        }

        /** The smallest text node whose on-screen bounds contain the point. */
        fun textAt(x: Int, y: Int): Hit? = instance?.findAt(x, y)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("LucidA11y", "connected (pid ${android.os.Process.myPid()})")
        LensState.publish()
    }

    override fun onDestroy() {
        instance = null
        Log.i("LucidA11y", "destroyed")
        LensState.publish()
        super.onDestroy()
    }

    /** The lens asks for text on demand; there is nothing to follow live. */
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private class Candidate(
        val node: AccessibilityNodeInfo,
        val text: String,
        /** Where [text] starts inside the node's raw string. */
        val offset: Int,
        /** True when [text] is the node's own text, so its character boxes line up. */
        val locatable: Boolean,
        val bounds: Rect,
    )

    private fun findAt(x: Int, y: Int): Hit? {
        // Only what the lens is actually over. Reaching for the nearest text
        // within a few dp reads a line the lens is nowhere near, which on a
        // page with text everywhere reads as the lens answering at random —
        // so a lens over blank space answers nothing instead.
        val hit = pointHit(x, y) ?: return null

        // Asking a node for its character geometry is a call into the app that
        // owns it, so this only runs on the caller's background thread.
        val chars = if (hit.locatable) characterBoxes(hit.node, hit.text.length + hit.offset) else null
        return Hit(
            text = hit.text,
            bounds = hit.bounds,
            chars = chars?.let { boxes ->
                Array(hit.text.length) { i ->
                    val at = hit.offset + i
                    if (at in boxes.indices) boxes[at] else null
                }
            },
            roomySpacing = roomySpacingOf(hit.node, hit.text),
            context = screenContext(hit.text),
            isLabel = !hit.locatable,
        )
    }

    /**
     * Everything else the screen is showing, in reading order.
     *
     * This is a second walk — [pointHit] prunes to the point, this one has to
     * visit the whole tree — so it is bounded by nodes and characters and runs
     * only on the caller's background thread, once per read. Lines that repeat
     * (list rows, toolbars) collapse, and the line under the lens is left out:
     * it travels as the line itself.
     */
    private fun screenContext(exclude: String): String {
        val out = ArrayList<String>(32)
        val seen = HashSet<String>()
        seen += exclude.trim()
        var chars = 0
        var visited = 0

        val box = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots().forEach { stack.addLast(it) }

        while (stack.isNotEmpty() && visited < NODE_BUDGET) {
            val node = stack.removeFirst()
            visited++

            if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                labelOf(node)?.let { label ->
                    if (label.text.length < MAX_LINE && seen.add(label.text)) {
                        out += label.text
                        chars += label.text.length
                    }
                }
            }
            if (chars >= Contexts.MAX_CHARS) break

            runCatching { node.getBoundsInScreen(box) }
            if (box.isEmpty) continue
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                stack.addLast(child)
            }
        }

        return out.joinToString("\n")
    }

    /**
     * How tall one line of this app's text stands, for boxes too roomy to
     * measure. A web page sets its own line height in CSS and Japanese pages
     * run roomier than Latin ones; a native app relies on Android's default
     * unless its control is padded, which is the common case for a box that
     * came out roomy.
     */
    private fun roomySpacingOf(node: AccessibilityNodeInfo, text: String): Float {
        val web = node.extras?.keySet()?.any { key ->
            key.endsWith(".cssDisplay") || key.endsWith(".chromeRole")
        } == true
        if (!web) return NATIVE_ROOMY_SPACING
        // A page sets one line height for its whole body, and Japanese pages mix
        // kanji, kana and Latin inside a sentence — so any CJK at all in the line
        // is taken as the page's convention, not a share of it.
        return if (text.any(::isCjk)) WEB_CJK_ROOMY_SPACING else WEB_LATIN_ROOMY_SPACING
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF ||   // kana
            code in 0x3400..0x4DBF ||      // kanji, extension A
            code in 0x4E00..0x9FFF ||      // kanji
            code in 0xF900..0xFAFF ||      // kanji, compatibility
            code in 0xAC00..0xD7AF        // hangul
    }

    /**
     * The app's own per-character boxes, or null when it does not publish them.
     * Chrome, the system UI and most text views answer this; the boxes come back
     * in screen coordinates to match the other rectangles we hold.
     */
    private fun characterBoxes(node: AccessibilityNodeInfo, length: Int): Array<RectF?>? {
        if (length <= 0) return null
        if (length > AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH) return null

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            putBoolean(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_IN_WINDOW_KEY, false)
        }

        val refreshed = runCatching {
            node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                args,
            )
        }.getOrNull() ?: return null
        if (!refreshed) return null

        val extras = runCatching { node.extras }.getOrNull() ?: return null
        extras.classLoader = RectF::class.java.classLoader
        @Suppress("DEPRECATION")
        val raw = runCatching {
            extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
        }.getOrNull() ?: return null

        val boxes = arrayOfNulls<RectF>(length)
        var found = false
        for (i in 0 until minOf(length, raw.size)) {
            val box = raw[i] as? RectF ?: continue
            if (box.width() <= 0f || box.height() <= 0f) continue
            boxes[i] = box
            found = true
        }

        // Some apps — Chrome among them — answer with the node's own rect for
        // every character. That is not per-character geometry, and drawing from
        // it would wash the whole line, so it is refused and the fit is used.
        val distinct = boxes.filterNotNull().distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
        return if (found && distinct.size >= 2) boxes else null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** How far one context walk visits before it stops: a read, not a crawl. */
    private val NODE_BUDGET = 500

    /** One context line capped; a whole paragraph is not worth a hint. */
    private val MAX_LINE = 240

    /**
     * The trees worth reading: activity windows only. The lens bubble, the
     * card and the language sheet are windows too, and their trees hold this
     * app's own answers — those must never come back as context around them.
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val open = ArrayList<AccessibilityWindowInfo>(8)
        runCatching { windows.forEach { open.add(it) } }

        val appWindows = open.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val roots = ArrayList<AccessibilityNodeInfo>(8)
        (if (appWindows.isNotEmpty()) appWindows else open).forEach { window ->
            window.root?.let { roots.add(it) }
        }

        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }
        return roots
    }

    private fun pointHit(x: Int, y: Int): Candidate? {
        val candidates = ArrayList<Candidate>(24)
        val box = Rect()

        for (root in roots()) {
            runCatching { collect(root, x, y, candidates, box, 0) }
        }

        // Exactly the text node under the lens: no climbing to the container
        // around it, so what gets highlighted is only what you pointed at.
        // The smallest box wins, which is the innermost node holding this point.
        return candidates.minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        out: MutableList<Candidate>,
        box: Rect,
        depth: Int,
    ) {
        if (depth > 48) return

        node.getBoundsInScreen(box)
        if (box.isEmpty) return
        if (!box.contains(x, y)) return

        if (node.isVisibleToUser) {
            labelOf(node)?.let {
                out.add(Candidate(node, it.text, it.offset, it.locatable, Rect(box)))
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collect(child, x, y, out, box, depth + 1)
        }
    }

    private class Label(val text: String, val offset: Int, val locatable: Boolean)

    private fun labelOf(node: AccessibilityNodeInfo): Label? {
        val raw = node.text?.toString().orEmpty()
        val text = raw.trim()
        val description = node.contentDescription?.toString()?.trim().orEmpty()

        if (text.length >= description.length) {
            if (text.length < 2 || text.none(Char::isLetter)) return null
            // Trimming the node's string shifts its character indices. A
            // content description has no character boxes at all.
            return Label(text, raw.indexOf(text).coerceAtLeast(0), locatable = true)
        }

        if (description.length < 2 || description.none(Char::isLetter)) return null
        return Label(description, 0, locatable = false)
    }
}

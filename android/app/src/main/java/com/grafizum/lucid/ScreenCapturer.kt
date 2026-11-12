package com.grafizum.lucid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Holds one MediaProjection session and hands back a single still frame on
 * demand.
 *
 * Nothing is recorded and nothing is written to disk. Frames land in a
 * two-slot ImageReader; the app only ever looks at the newest one, at the
 * moment the lens is tapped.
 */
class ScreenCapturer(private val context: Context) {

    private companion object {
        const val TAG = "LucidCapture"
        const val GRAB_TIMEOUT_MS = 900L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    val isReady: Boolean
        get() = reader != null

    /**
     * Creates the mirror. Must be called with the projection already un-stopped
     * and its callback registered (Android 14 requirement).
     */
    fun attach(mediaProjection: MediaProjection, w: Int, h: Int, densityDpi: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        release(keepProjection = true)

        thread = HandlerThread("lucid-capture").also { it.start() }
        handler = Handler(thread!!.looper)

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        width = w
        height = h

        return try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "lucid-lens",
                w,
                h,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
            projection = mediaProjection
            true
        } catch (err: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", err)
            release(keepProjection = false)
            false
        }
    }

    /** The newest frame, cropped to [rect] (in screen pixels). Null if none yet. */
    fun grab(rect: Rect): Bitmap? {
        val imageReader = reader ?: return null
        val deadline = SystemClock.elapsedRealtime() + GRAB_TIMEOUT_MS

        while (SystemClock.elapsedRealtime() < deadline) {
            val image: Image? = try {
                imageReader.acquireLatestImage()
            } catch (err: Exception) {
                null
            }

            if (image != null) {
                return try {
                    toCroppedBitmap(image, rect)
                } catch (err: Exception) {
                    Log.e(TAG, "frame conversion failed", err)
                    null
                } finally {
                    image.close()
                }
            }

            SystemClock.sleep(24)
        }

        return null
    }

    private fun toCroppedBitmap(image: Image, rect: Rect): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)

        val left = rect.left.coerceIn(0, paddedWidth - 2)
        val top = rect.top.coerceIn(0, image.height - 2)
        val right = rect.right.coerceIn(left + 2, paddedWidth)
        val bottom = rect.bottom.coerceIn(top + 2, image.height)

        val cropped = Bitmap.createBitmap(padded, left, top, right - left, bottom - top)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    fun release(keepProjection: Boolean = false) {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { reader?.close() }
        reader = null

        thread?.quitSafely()
        thread = null
        handler = null

        if (!keepProjection) {
            runCatching { projection?.stop() }
            projection = null
        }
    }
}

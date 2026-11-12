package com.grafizum.lucid

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * The quick-settings tile: turn the lens on or off from the notification
 * shade, without opening Lucid at all.
 *
 * The tile is an ACTIVE tile, so the system binds it whenever the shade is
 * open and this service can keep its state in sync with the lens session.
 */
class LensTileService : TileService() {

    companion object {
        private val handler = Handler(Looper.getMainLooper())

        /** Ask a visible tile to re-read the lens state. Safe to call anywhere. */
        fun refresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, LensTileService::class.java),
                )
            }
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        // The app's own row reads this, so it can say "Added" the moment the
        // user drops the button into the shade.
        Prefs.setTileAdded(this, true)
        LensState.publish()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Prefs.setTileAdded(this, false)
        LensState.publish()
    }

    override fun onStartListening() {
        super.onStartListening()
        // The shade is where the user finds out whether the lens is still
        // there, so check the window is actually on screen before drawing the
        // tile: a tile must never read "on" for a lens that has gone.
        LensService.verify()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (LensService.isRunning) stopLens() else startLens()
        // The session flips its own state slightly after the start call, so
        // settle the tile once more once that has landed.
        handler.postDelayed({ runCatching { refreshTile() } }, 500)
    }

    private fun startLens() {
        // The lens needs its two switches; from the shade there is nobody to
        // show a permission screen to, so say which one is missing and let the
        // user open Lucid and allow it there.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("Allow \u201CFloat over other apps\u201D in Lucid first.")
            openApp()
            return
        }
        if (!LensAccessibilityService.isEnabled(this)) {
            toast("Allow Text access in Lucid first.")
            openApp()
            return
        }
        val started = runCatching { LensService.start(this) }.isSuccess
        if (!started) {
            toast("Couldn't start the lens — open Lucid once.")
        }
        refreshTile()
    }

    /**
     * Bring the app up, since every missing permission is allowed there. From
     * 14 a service may only open a screen through the tile's own collapse, so
     * that is the route; older versions take the intent directly.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun stopLens() {
        // stopService() is allowed from the background, while the ACTION_STOP
        // startService() route throws when the app is cold — so stop the
        // service directly; its onDestroy runs the full session shutdown.
        val stopped = runCatching { stopService(Intent(this, LensService::class.java)) }
            .getOrDefault(false)
        if (!stopped) {
            runCatching { LensService.stop(this) }
                .onFailure { toast("Couldn't stop the lens — open Lucid.") }
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        // Read the session, not a remembered flag: isRunning is only true
        // while a live service is holding a lens on screen.
        val running = LensService.isRunning
        tile.label = getString(R.string.tile_label)
        // The app's two glyphs rather than a plain magnifier: the shade's
        // button is the one place Lucid sits beside tools that all draw a lens.
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_mark)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (running) R.string.tile_on else R.string.tile_off)
        }
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

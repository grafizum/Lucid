package com.grafizum.lucid

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import android.widget.FrameLayout

/**
 * A full-screen messaging surface used to try the lens (and to shoot the
 * preview). Not in the launcher: open it with
 *   adb shell am start -n com.grafizum.lucid/.DemoChatActivity
 */
class DemoChatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFFEDE4D4.toInt()
        window.navigationBarColor = 0xFFEDE4D4.toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(0xFFF3ECDE.toInt()) }

        val web = WebView(this).apply {
            setBackgroundColor(0xFFF3ECDE.toInt())
            settings.javaScriptEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            loadUrl("file:///android_asset/demo-chat.html")
        }

        root.addView(
            web,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
    }
}

package com.grafizum.lucid

import android.content.Context

object Prefs {
    private const val FILE = "lucid_prefs"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetLanguage(context: Context): String = sp(context).getString("target", "en") ?: "en"

    /** Read the text under the lens as soon as it stops moving. */
    fun autoTranslate(context: Context): Boolean = sp(context).getBoolean("autoTranslate", true)

    fun setAutoTranslate(context: Context, enabled: Boolean) =
        sp(context).edit().putBoolean("autoTranslate", enabled).apply()

    /** Use the online engine for accuracy, not just the offline models. */
    fun onlineEngine(context: Context): Boolean = sp(context).getBoolean("onlineEngine", true)

    fun setOnlineEngine(context: Context, enabled: Boolean) =
        sp(context).edit().putBoolean("onlineEngine", enabled).apply()

    fun setTargetLanguage(context: Context, code: String) =
        sp(context).edit().putString("target", code).apply()

    /**
     * The user has read what Text access does and tapped "I agree". Asked
     * before Android's own switch, because reading other apps' text is a
     * sensitive permission and Play requires that consent to be in the open.
     */
    fun textConsent(context: Context): Boolean = sp(context).getBoolean("textConsent", false)

    fun setTextConsent(context: Context, agreed: Boolean) =
        sp(context).edit().putBoolean("textConsent", agreed).apply()

    /**
     * The user chose Look mode at some point. The screen-share consent itself
     * cannot be stored — Android hands it out one session at a time — so this
     * only remembers the choice, which is what has to survive an app update,
     * a restart or the phone being rebooted. Without it the row quietly went
     * back to "Turn on" and the person had to remember they had ever set it.
     */
    fun lookMode(context: Context): Boolean = sp(context).getBoolean("lookMode", false)

    fun setLookMode(context: Context, on: Boolean) =
        sp(context).edit().putBoolean("lookMode", on).apply()

    fun bubbleX(context: Context): Int = sp(context).getInt("bubbleX", -1)

    fun bubbleY(context: Context): Int = sp(context).getInt("bubbleY", -1)

    fun saveBubble(context: Context, x: Int, y: Int) =
        sp(context).edit().putInt("bubbleX", x).putInt("bubbleY", y).apply()

    /**
     * Whether the shade's Lucid button has been added, as reported by the tile
     * itself. Only ever a hint: the button's real state lives with the system.
     */
    fun tileAdded(context: Context): Boolean = sp(context).getBoolean("tileAdded", false)

    fun setTileAdded(context: Context, added: Boolean) =
        sp(context).edit().putBoolean("tileAdded", added).apply()
}

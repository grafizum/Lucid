package com.grafizum.lucid

/** Lets the two services tell the UI that something changed. */
object LensState {

    private var listener: (() -> Unit)? = null

    fun publish() {
        listener?.invoke()
    }

    fun observe(callback: (() -> Unit)?) {
        listener = callback
        callback?.invoke()
    }
}

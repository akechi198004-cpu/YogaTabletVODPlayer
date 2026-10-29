package com.hm.video.data.api

import okhttp3.Call

/**
 * Tracks the single page request a screen currently cares about.
 *
 * A newer request, or leaving the screen, cancels the previous call so an abandoned slow
 * response cannot keep a worker thread, socket and its 20-second budget alive. Image and
 * playback traffic deliberately stay outside this scope.
 */
class PageCallScope {

    private var active: Call? = null

    /** Registers [call], cancelling whatever this scope was waiting for before. */
    @Synchronized
    fun begin(call: Call) {
        val previous = active
        active = call
        previous?.cancel()
    }

    /** Clears [call] if it is still the active one. */
    @Synchronized
    fun end(call: Call) {
        if (active === call) active = null
    }

    /** Abandons the in-flight call, if any. */
    @Synchronized
    fun cancel() {
        val call = active
        active = null
        call?.cancel()
    }
}

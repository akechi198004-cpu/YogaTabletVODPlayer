package com.hm.video.ui

import android.view.KeyEvent

/**
 * Invalidates a pending post-load focus request whenever the user presses a navigation key.
 *
 * The counter is advanced from Activity.dispatchKeyEvent, before any scroll container or child
 * can consume the event, so consumption by a focused control cannot hide the interaction.
 */
internal class FocusLoadGuard {
    private var generation = 0

    fun begin(): Int = generation

    fun isCurrent(token: Int): Boolean = token == generation

    fun onKeyEvent(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in TRACKED_KEYS) {
            generation++
        }
    }

    private companion object {
        val TRACKED_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU
        )
    }
}

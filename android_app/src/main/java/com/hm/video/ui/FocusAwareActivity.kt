package com.hm.video.ui

import android.app.Activity
import android.view.KeyEvent

/** Activity base that observes navigation keys before any child can consume them. */
open class FocusAwareActivity : Activity() {
    private val focusLoadGuard = FocusLoadGuard()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        focusLoadGuard.onKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    internal fun beginFocusLoad(): Int = focusLoadGuard.begin()

    internal fun isFocusLoadCurrent(token: Int): Boolean = focusLoadGuard.isCurrent(token)
}

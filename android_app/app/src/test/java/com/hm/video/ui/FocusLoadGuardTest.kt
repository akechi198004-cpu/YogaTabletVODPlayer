package com.hm.video.ui

import android.app.Application
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class FocusLoadGuardTest {

    @Test
    fun keyConsumedByChildStillInvalidatesPendingFocus() {
        val guard = FocusLoadGuard()
        val token = guard.begin()
        guard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(guard.isCurrent(token))
    }

    @Test
    fun movingAwayAndBackDoesNotRevivePendingFocus() {
        val guard = FocusLoadGuard()
        val token = guard.begin()
        guard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        guard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(guard.isCurrent(token))
    }

    @Test
    fun keyUpDoesNotInvalidateAndNewLoadStartsFresh() {
        val guard = FocusLoadGuard()
        val token = guard.begin()
        guard.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(guard.isCurrent(token))

        guard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(guard.isCurrent(token))
        assertTrue(guard.isCurrent(guard.begin()))
    }
}

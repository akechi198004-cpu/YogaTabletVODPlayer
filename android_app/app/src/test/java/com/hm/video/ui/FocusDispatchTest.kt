package com.hm.video.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class FocusDispatchTest {

    @Test
    fun childConsumedDpadStillInvalidatesPendingFocus() {
        val activity = Robolectric.buildActivity(FocusAwareActivity::class.java).create().get()
        val root = FrameLayout(activity)
        val child = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, _ -> true } // child consumes the key
        }
        root.addView(child, FrameLayout.LayoutParams(120, 80))
        activity.setContentView(root)
        child.requestFocus()

        val token = activity.beginFocusLoad()
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))

        assertFalse(activity.isFocusLoadCurrent(token))
    }

    @Test
    fun buttonAAndKeyUpBehaviourMatchesGuard() {
        val activity = Robolectric.buildActivity(FocusAwareActivity::class.java).create().get()
        val root = FrameLayout(activity)
        val child = View(activity).apply { isFocusable = true; isFocusableInTouchMode = true }
        root.addView(child, FrameLayout.LayoutParams(120, 80))
        activity.setContentView(root)
        child.requestFocus()

        val first = activity.beginFocusLoad()
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(activity.isFocusLoadCurrent(first))

        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(activity.isFocusLoadCurrent(first))
    }
}

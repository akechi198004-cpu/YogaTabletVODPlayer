package com.hm.video.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Decides which layout family a screen should use.
 *
 * This APK was written for TV boxes: the leanback layout moves the five tabs into the top
 * bar and gives the home page a 200dp widescreen hero carousel. A large touch tablet such
 * as the Yoga Tablet 8 (1280x800 at 213dpi, i.e. ~960x600dp) fits that layout much better
 * than the phone-shaped touch variant, so it is used there as well. Phones keep the
 * bottom-bar layout.
 */
object DisplayProfile {

    /** Shortest width, in dp, that can carry the top-bar navigation and the wide hero. */
    const val LEANBACK_MIN_WIDTH_DP = 600

    fun isLeanbackLayout(context: Context): Boolean {
        if (isTelevision(context)) return true
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        val metrics = context.resources.displayMetrics
        if (metrics.density <= 0f) return false
        return metrics.widthPixels / metrics.density >= LEANBACK_MIN_WIDTH_DP
    }

    fun isTelevision(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
}

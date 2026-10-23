package com.hm.video

import android.content.Context
import android.content.ContextWrapper
import com.hm.video.data.storage.UserStorageManager
import org.robolectric.RuntimeEnvironment

/** Install resources/storage without Application.onCreate starting the production proxy. */
fun installTestApplication(): UserStorageManager {
    val context = RuntimeEnvironment.getApplication()
    val app = HMVideoApplication()
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
        isAccessible = true; invoke(app, context)
    }
    val storage = UserStorageManager(context)
    HMVideoApplication::class.java.getDeclaredField("storageManager").apply {
        isAccessible = true; set(app, storage)
    }
    HMVideoApplication::class.java.getDeclaredField("instance").apply {
        isAccessible = true; set(null, app)
    }
    return storage
}

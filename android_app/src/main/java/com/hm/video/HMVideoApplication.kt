package com.hm.video

import androidx.multidex.MultiDexApplication
import com.hm.video.data.api.XingfanRepository
import com.hm.video.data.storage.UserStorageManager
import com.hm.video.player.HlsLocalProxy
import com.hm.video.util.ImageLoader

class HMVideoApplication : MultiDexApplication() {

    lateinit var storageManager: UserStorageManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.hm.video.util.LegacyTls.configurePlatformHttps()
        storageManager = UserStorageManager(this)
        XingfanRepository.init(this)
        ImageLoader.init(this)
        HlsLocalProxy.start()

        // Trimming scans the cache directories, so keep it off the startup path.
        Thread({
            XingfanRepository.trimDiskCache()
            ImageLoader.trimDiskCache()
        }, "hm-cache-maintenance").apply { isDaemon = true }.start()
    }

    override fun onTerminate() {
        super.onTerminate()
        HlsLocalProxy.stop()
    }

    companion object {
        lateinit var instance: HMVideoApplication
            private set
    }
}

package com.hm.video.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import android.view.View
import com.hm.video.R
import com.hm.video.data.api.XingfanClient
import com.hm.video.data.api.XingfanConfig
import com.hm.video.data.cache.CacheConfig
import com.hm.video.data.cache.ImageCache
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import okhttp3.Call

/**
 * Poster loader with three levels: decoded bitmaps in memory, encoded bytes on disk, then network.
 *
 * Disk bytes survive process death, so opening the app or switching tabs repaints posters from
 * local storage instead of downloading them again. Older copies are revalidated in the background
 * with If-Modified-Since: an unchanged poster costs a 304, a changed one replaces the local copy
 * for the next screen.
 */
object ImageLoader {

    private data class MemoryKey(val url: String, val width: Int, val height: Int)
    private data class MemoryEntry(val bitmap: Bitmap, val fetchedAt: Long)

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 10).coerceIn(1024 * 2, 1024 * 12)
    private val memoryCache = object : LruCache<MemoryKey, MemoryEntry>(cacheSize) {
        override fun sizeOf(key: MemoryKey, value: MemoryEntry): Int {
            return (value.bitmap.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val executor = ThreadPoolExecutor(
        3, 3, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(64)
    )
    private val revalidateExecutor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16), ThreadPoolExecutor.AbortPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap<ImageView, Call>()
    private val revalidating: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var diskCache: ImageCache? = null

    /** Called from Application.onCreate; creating the cache only prepares directories. */
    fun init(context: Context) {
        if (diskCache != null) return
        synchronized(this) {
            if (diskCache != null) return
            try {
                diskCache = ImageCache(File(context.cacheDir, CacheConfig.ROOT_DIR))
            } catch (_: Exception) {
                diskCache = null
            }
        }
    }

    /** Enforces the disk quota; call from a background thread. */
    fun trimDiskCache() {
        try {
            diskCache?.trim()
        } catch (_: Exception) {
            // A cache trim must never surface to the user.
        }
    }

    fun load(imageView: ImageView, url: String?) {
        val requestedUrl = url.orEmpty()
        imageView.tag = requestedUrl
        inFlight.remove(imageView)?.cancel()
        if (imageView.getTag(R.id.image_loader_attach_listener) == null) {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) { load(imageView, imageView.tag as? String) }
                override fun onViewDetachedFromWindow(view: View) { inFlight.remove(imageView)?.cancel() }
            }
            imageView.setTag(R.id.image_loader_attach_listener, listener)
            imageView.addOnAttachStateChangeListener(listener)
        }
        if (url.isNullOrEmpty()) {
            imageView.setImageDrawable(null)
            return
        }

        if (!imageView.isAttachedToWindow) return
        // Wait for real bounds instead of decoding a hero banner at poster fallback dimensions.
        if (imageView.width <= 0 || imageView.height <= 0) {
            imageView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    if (right <= left || bottom <= top) return
                    view.removeOnLayoutChangeListener(this)
                    if (isCurrent(imageView, requestedUrl)) load(imageView, requestedUrl)
                }
            })
            return
        }
        val targetWidth = imageView.width
        val targetHeight = imageView.height
        val memoryKey = MemoryKey(url, targetWidth, targetHeight)
        val cached = memoryCache.get(memoryKey)
        if (cached != null) {
            imageView.setImageBitmap(cached.bitmap)
            diskCache?.let { maybeRevalidate(url, it, cached.fetchedAt) }
            return
        }

        // Set placeholder color / background
        imageView.setBackgroundColor(0xFF121620.toInt())
        try {
            executor.execute {
                if (!isCurrent(imageView, requestedUrl)) return@execute

                val disk = diskCache
                val diskEntry = disk?.get(url)
                if (diskEntry != null) {
                    val bitmap = decode(diskEntry.payload, diskEntry.offset, diskEntry.length, targetWidth, targetHeight)
                    if (bitmap != null) {
                        memoryCache.put(memoryKey, MemoryEntry(bitmap, diskEntry.fetchedAt))
                        postBitmap(imageView, requestedUrl, bitmap)
                        maybeRevalidate(url, disk, diskEntry.fetchedAt)
                        return@execute
                    }
                    // Corrupted or undecodable cache file: drop it and fall back to the network.
                    disk.remove(url)
                }
                loadFromNetwork(imageView, requestedUrl, url, targetWidth, targetHeight)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A large TV home page may fill the bounded queue. Retry attached cards
            // asynchronously instead of throwing on the main thread or blocking it.
            mainHandler.postDelayed({
                if (imageView.isAttachedToWindow && imageView.tag == requestedUrl) load(imageView, url)
            }, 500)
        }
    }

    private fun loadFromNetwork(
        imageView: ImageView,
        requestedUrl: String,
        url: String,
        targetWidth: Int,
        targetHeight: Int
    ) {
        try {
            val fullUrl = expand(url)
            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", XingfanConfig.USER_AGENT)
                .header("Referer", "${XingfanConfig.SOURCE_BASE}/")
                .build()

            val call = XingfanClient.httpClient.newCall(request)
            inFlight[imageView] = call
            if (!isCurrent(imageView, requestedUrl)) {
                inFlight.remove(imageView, call)
                call.cancel()
                return
            }
            try {
                call.execute().use { response ->
                    if (response.code == 404 || response.code == 410) {
                        diskCache?.remove(url)
                        removeMemory(url)
                        return
                    }
                    if (!response.isSuccessful) return
                    val stream: InputStream? = response.body?.byteStream()
                    val bytes = stream?.use { readBounded(it, CacheConfig.IMAGE_MAX_ENTRY_BYTES.toInt()) } ?: return
                    if (!isCurrent(imageView, requestedUrl)) return
                    val bitmap = decode(bytes, 0, bytes.size, targetWidth, targetHeight) ?: return
                    memoryCache.put(MemoryKey(url, targetWidth, targetHeight), MemoryEntry(bitmap, System.currentTimeMillis()))
                    diskCache?.put(url, bytes)
                    postBitmap(imageView, requestedUrl, bitmap)
                }
            } finally {
                inFlight.remove(imageView, call)
            }
        } catch (_: Exception) {
            // Ignore load error gracefully; the placeholder stays.
        }
    }

    /**
     * Serves a cached poster immediately, then checks the origin if the local copy is old. This is
     * fire-and-forget: failures keep the cached image, and the currently visible bitmap is not
     * swapped mid-scroll. The refreshed bytes are used by the next bind/startup.
     */
    private fun maybeRevalidate(url: String, disk: ImageCache, fetchedAt: Long) {
        if (fetchedAt <= 0) return
        if (System.currentTimeMillis() - fetchedAt <= CacheConfig.IMAGE_REVALIDATE_MS) return
        if (!revalidating.add(url)) return
        try {
            revalidateExecutor.execute {
                try {
                    val request = Request.Builder()
                        .url(expand(url))
                        .header("User-Agent", XingfanConfig.USER_AGENT)
                        .header("Referer", "${XingfanConfig.SOURCE_BASE}/")
                        .header("If-Modified-Since", httpDate(fetchedAt))
                        .build()
                    XingfanClient.httpClient.newCall(request).execute().use { response ->
                        when {
                            response.code == 304 -> {
                                disk.markValidated(url)
                                val now = System.currentTimeMillis()
                                memoryCache.snapshot().filterKeys { it.url == url }.forEach { (key, value) ->
                                    memoryCache.put(key, value.copy(fetchedAt = now))
                                }
                            }
                            response.code == 404 || response.code == 410 -> {
                                disk.remove(url)
                                removeMemory(url)
                            }
                            response.isSuccessful -> {
                                val bytes = response.body?.byteStream()
                                    ?.use { readBounded(it, CacheConfig.IMAGE_MAX_ENTRY_BYTES.toInt()) }
                                if (bytes != null && isDecodable(bytes, 0, bytes.size)) {
                                    disk.put(url, bytes)
                                    removeMemory(url)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Offline or slow origin: keep the local copy, retry on a later run.
                } finally {
                    revalidating.remove(url)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            revalidating.remove(url)
        }
    }

    private fun removeMemory(url: String) {
        memoryCache.snapshot().keys.filter { it.url == url }.forEach { memoryCache.remove(it) }
    }

    private fun postBitmap(imageView: ImageView, requestedUrl: String, bitmap: Bitmap) {
        mainHandler.post {
            if (imageView.tag == requestedUrl) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun isCurrent(imageView: ImageView, requestedUrl: String): Boolean {
        return imageView.isAttachedToWindow && imageView.tag == requestedUrl
    }

    private fun expand(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "${XingfanConfig.SOURCE_BASE}$url"
        else -> "${XingfanConfig.SOURCE_BASE}/$url"
    }

    private fun decode(bytes: ByteArray, offset: Int, length: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (length <= 0) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while ((bounds.outWidth / sample > targetWidth * 2 && bounds.outHeight / sample > targetHeight * 2) ||
                bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > 4_000_000L) {
                sample *= 2
            }
            try {
                BitmapFactory.decodeByteArray(bytes, offset, length, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: OutOfMemoryError) {
                try {
                    BitmapFactory.decodeByteArray(bytes, offset, length, BitmapFactory.Options().apply { inSampleSize = sample * 2 })
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isDecodable(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun httpDate(epochMs: Long): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(java.util.Date(epochMs))
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalArgumentException("Image exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

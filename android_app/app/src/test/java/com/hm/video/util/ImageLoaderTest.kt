package com.hm.video.util

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.hm.video.LocalHttpServer
import com.hm.video.installTestApplication
import com.hm.video.data.cache.ImageCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class ImageLoaderTest {
    @Rule @JvmField val temp = TemporaryFolder()
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: LinearLayout
    private lateinit var disk: ImageCache
    private lateinit var memory: LruCache<Any, Any>

    @Before
    @Suppress("UNCHECKED_CAST")
    fun setUp() {
        installTestApplication()
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        root = LinearLayout(controller.get()).apply { orientation = LinearLayout.VERTICAL }
        controller.get().setContentView(root)
        disk = ImageCache(temp.root)
        ImageLoader::class.java.getDeclaredField("diskCache").apply { isAccessible = true; set(null, disk) }
        memory = ImageLoader::class.java.getDeclaredField("memoryCache").run {
            isAccessible = true; get(null) as LruCache<Any, Any>
        }
        memory.evictAll()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        memory.evictAll()
        ImageLoader::class.java.getDeclaredField("diskCache").apply { isAccessible = true; set(null, null) }
    }

    @Test
    fun smallPosterIsNotReusedAsLowResolutionHero() {
        val url = "https://example.invalid/size-test.jpg"
        disk.put(url, png())
        val small = image(128, 192)
        val hero = image(768, 256)
        ImageLoader.load(small, url)
        await { small.drawable is BitmapDrawable }
        val smallBitmap = (small.drawable as BitmapDrawable).bitmap

        ImageLoader.load(hero, url)
        await { hero.drawable is BitmapDrawable }
        val heroBitmap = (hero.drawable as BitmapDrawable).bitmap

        assertTrue(heroBitmap.width > smallBitmap.width)
        assertTrue("CENTER_CROP must not undersample only because the banner is short", heroBitmap.width >= 768)
    }

    @Test
    fun staleMemoryHitRevalidatesWithoutReplacingGoodImageWithInvalidResponse() {
        LocalHttpServer { "invalid image" }.use { server ->
            val url = "${server.baseUrl}/poster.jpg"
            disk.put(url, png())
            val view = image(128, 192)
            ImageLoader.load(view, url)
            await { view.drawable is BitmapDrawable }
            val bitmap = (view.drawable as BitmapDrawable).bitmap
            val entry = memory.snapshot().values.single()
            entry.javaClass.getDeclaredField("fetchedAt").apply { isAccessible = true; setLong(entry, 1L) }
            assertTrue(server.requests.isEmpty())

            ImageLoader.load(view, url)
            await {
                val refreshing = ImageLoader::class.java.getDeclaredField("revalidating").run {
                    isAccessible = true; get(null) as Set<*>
                }
                server.requests.isNotEmpty() && refreshing.isEmpty()
            }

            assertSame(bitmap, (view.drawable as BitmapDrawable).bitmap)
            assertNotNull(disk.get(url))
        }
    }

    private fun image(width: Int, height: Int): ImageView = ImageView(controller.get()).also {
        root.addView(it, LinearLayout.LayoutParams(width, height))
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 1000)
    }

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(1024, 1536, Bitmap.Config.ARGB_8888).apply {
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        output.toByteArray()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for local image work", condition())
    }
}

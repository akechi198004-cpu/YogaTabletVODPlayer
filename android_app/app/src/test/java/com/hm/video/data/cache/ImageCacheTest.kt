package com.hm.video.data.cache

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageCacheTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun cache() = ImageCache(temp.root)

    @Test
    fun storesEncodedBytesWithFetchTimestamp() {
        val cache = cache()
        val payload = ByteArray(64) { it.toByte() }

        assertTrue(cache.put("https://example.com/poster.jpg", payload))

        val entry = cache.get("https://example.com/poster.jpg")
        assertNotNull(entry)
        assertArrayEquals(payload, entry!!.payload.copyOfRange(entry.offset, entry.payload.size))
        assertTrue(entry.fetchedAt > 0)
        assertTrue(System.currentTimeMillis() - entry.fetchedAt < 5_000)
    }

    @Test
    fun markValidatedRefreshesTimestampAndKeepsBytes() {
        val cache = cache()
        val payload = "image-bytes".toByteArray()
        cache.put("p", payload)
        val before = cache.get("p")!!.fetchedAt

        Thread.sleep(5)
        cache.markValidated("p")

        val entry = cache.get("p")!!
        assertArrayEquals(payload, entry.payload.copyOfRange(entry.offset, entry.payload.size))
        assertTrue(entry.fetchedAt >= before)
    }

    @Test
    fun rawLegacyFilesAreRejectedAndRemoved() {
        val cache = cache()
        cache.put("p", "abc".toByteArray())
        // Overwrite with a bare image payload that has no header.
        val file = findBin(temp.root)
        assertNotNull(file)
        file!!.writeBytes("no-header".toByteArray())

        assertNull(cache.get("p"))
        assertNull(findBin(temp.root))
    }

    @Test
    fun removeDeletesEntry() {
        val cache = cache()
        cache.put("p", ByteArray(8))
        cache.remove("p")
        assertNull(cache.get("p"))
    }

    private fun findBin(root: File): File? {
        return File(root, "image").walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".bin") }
    }
}

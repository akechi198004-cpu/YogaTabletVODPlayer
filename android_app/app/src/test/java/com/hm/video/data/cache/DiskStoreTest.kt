package com.hm.video.data.cache

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiskStoreTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun store(
        maxBytes: Long = 1024 * 1024,
        maxEntries: Int = 100,
        maxAgeMs: Long = Long.MAX_VALUE
    ) = DiskStore(temp.root, maxBytes, maxEntries, maxAgeMs, namespace = "test")

    @Test
    fun putGetAndRemoveRoundTrip() {
        val disk = store()
        val bytes = "poster-bytes".toByteArray()

        assertTrue(disk.put("https://example.com/a.jpg?x=1", bytes))
        assertArrayEquals(bytes, disk.get("https://example.com/a.jpg?x=1"))

        disk.remove("https://example.com/a.jpg?x=1")
        assertNull(disk.get("https://example.com/a.jpg?x=1"))
    }

    @Test
    fun keysWithLongQueryStringsDoNotCollide() {
        val disk = store()
        val first = "https://example.com/p.jpg?token=" + "a".repeat(2000)
        val second = "https://example.com/p.jpg?token=" + "b".repeat(2000)

        assertTrue(disk.put(first, "one".toByteArray()))
        assertTrue(disk.put(second, "two".toByteArray()))

        assertArrayEquals("one".toByteArray(), disk.get(first))
        assertArrayEquals("two".toByteArray(), disk.get(second))
    }

    @Test
    fun evictsLeastRecentlyUsedEntriesBeyondByteQuota() {
        val disk = store(maxBytes = 10)
        disk.put("first", ByteArray(6) { 1 })
        disk.put("second", ByteArray(6) { 2 })
        disk.fileFor("first").setLastModified(System.currentTimeMillis() - 10_000)
        disk.fileFor("second").setLastModified(System.currentTimeMillis())

        disk.trim()

        assertNull(disk.get("first"))
        assertNotNull(disk.get("second"))
    }

    @Test
    fun evictsEntriesBeyondCountQuota() {
        val disk = store(maxEntries = 2)
        disk.put("a", byteArrayOf(1))
        disk.put("b", byteArrayOf(2))
        disk.put("c", byteArrayOf(3))
        disk.fileFor("a").setLastModified(System.currentTimeMillis() - 20_000)
        disk.fileFor("b").setLastModified(System.currentTimeMillis() - 10_000)
        disk.fileFor("c").setLastModified(System.currentTimeMillis())

        disk.trim()

        assertNull(disk.get("a"))
        assertNotNull(disk.get("b"))
        assertNotNull(disk.get("c"))
    }

    @Test
    fun evictsExpiredEntries() {
        val disk = store(maxAgeMs = 24 * 60 * 60 * 1000L)
        disk.put("old", byteArrayOf(1))
        val file = disk.fileFor("old")
        assertTrue(file.setLastModified(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000))

        disk.trim()

        assertNull(disk.get("old"))
    }

    @Test
    fun rejectsOversizedPayloads() {
        val disk = DiskStore(temp.root, 1024, 10, namespace = "small", maxEntryBytes = 4)
        assertFalse(disk.put("big", ByteArray(5)))
        assertNull(disk.get("big"))
    }

    @Test
    fun corruptFileBehavesAsMiss() {
        val disk = store()
        disk.put("key", byteArrayOf(1, 2, 3))
        val file = disk.fileFor("key")
        file.writeBytes(ByteArray(0))

        assertNull(disk.get("key"))
        assertFalse(file.exists())
    }

    @Test
    fun writesAreAtomicAndLeaveNoTempFiles() {
        val disk = store()
        repeat(5) { index -> disk.put("key$index", ByteArray(32) { index.toByte() }) }

        val leftovers = File(temp.root, "test/.tmp").listFiles()?.toList().orEmpty()
        assertTrue(leftovers.isEmpty())
        assertArrayEquals(ByteArray(32) { 4 }, disk.get("key4"))
    }
}

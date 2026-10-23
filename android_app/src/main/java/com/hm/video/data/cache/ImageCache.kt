package com.hm.video.data.cache

import java.io.File
import java.nio.ByteBuffer

/**
 * Byte store for poster artwork. The original encoded bytes are kept (not decoded bitmaps), so the
 * loader can still downsample to the exact view size after a cold start, keeping memory use low on
 * TV boxes.
 *
 * Each entry carries a small header with the time it was last confirmed against the origin. The
 * file's own modification time stays free for LRU bookkeeping, so frequently viewed posters still
 * get revalidated after their freshness window instead of being touched forever.
 */
class ImageCache(rootDir: File) {

    class Entry(
        val payload: ByteArray,
        val offset: Int,
        val fetchedAt: Long
    ) {
        val length: Int get() = payload.size - offset
    }

    private val store = DiskStore(
        rootDir = rootDir,
        maxBytes = CacheConfig.IMAGE_MAX_BYTES,
        maxEntries = CacheConfig.IMAGE_MAX_ENTRIES,
        maxAgeMs = CacheConfig.IMAGE_MAX_AGE_MS,
        maxEntryBytes = CacheConfig.IMAGE_MAX_ENTRY_BYTES + HEADER_SIZE,
        namespace = "image"
    )

    fun get(url: String): Entry? {
        val raw = store.get(url) ?: return null
        if (raw.size <= HEADER_SIZE || raw[0] != MAGIC_0 || raw[1] != MAGIC_1 ||
            raw[2] != MAGIC_2 || raw[3] != MAGIC_3
        ) {
            // Old or incomplete format: discard rather than feed junk to the decoder.
            store.remove(url)
            return null
        }
        val fetchedAt = ByteBuffer.wrap(raw, 4, 8).long
        return Entry(raw, HEADER_SIZE, fetchedAt)
    }

    fun put(url: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val raw = ByteArray(HEADER_SIZE + bytes.size)
        raw[0] = MAGIC_0
        raw[1] = MAGIC_1
        raw[2] = MAGIC_2
        raw[3] = MAGIC_3
        ByteBuffer.wrap(raw, 4, 8).putLong(System.currentTimeMillis())
        bytes.copyInto(raw, HEADER_SIZE)
        return store.put(url, raw)
    }

    /** Records that the origin confirmed this copy with a 304, without re-downloading it. */
    fun markValidated(url: String) {
        val entry = get(url) ?: return
        put(url, entry.payload.copyOfRange(entry.offset, entry.payload.size))
    }

    fun remove(url: String) = store.remove(url)

    fun trim() = store.trim()

    fun sizeBytes(): Long = store.sizeBytes()

    private companion object {
        const val HEADER_SIZE = 12
        val MAGIC_0: Byte = 'H'.code.toByte()
        val MAGIC_1: Byte = 'M'.code.toByte()
        val MAGIC_2: Byte = 'I'.code.toByte()
        val MAGIC_3: Byte = '1'.code.toByte()
    }
}

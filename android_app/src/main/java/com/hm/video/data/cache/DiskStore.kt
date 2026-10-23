package com.hm.video.data.cache

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * A tiny, dependency-free LRU file store used for offline poster and catalog caching.
 *
 * Design notes:
 * - Keys are hashed, so arbitrary URLs (including very long query strings) map to safe file names.
 * - Writes go to a temporary file first and are then renamed, so a crash can never leave a
 *   half-written cache entry behind.
 * - Reads never throw: a corrupt or unreadable entry simply behaves like a cache miss.
 * - Eviction is bounded by both total bytes and entry count, and optionally by age. Trimming is
 *   throttled so a burst of poster loads does not rescan the directory on every write.
 *
 * All public methods are safe to call from background threads. Never call them on the main thread.
 */
class DiskStore(
    rootDir: File,
    private val maxBytes: Long,
    private val maxEntries: Int,
    private val maxAgeMs: Long = Long.MAX_VALUE,
    private val maxEntryBytes: Long = Long.MAX_VALUE,
    private val namespace: String = "store"
) {

    private val directory = File(rootDir, namespace)
    private val tempDir = File(directory, ".tmp")
    private val lock = Any()
    private val knownBytes = AtomicLong(-1L)
    private var writesSinceTrim = 0

    init {
        safeMkdirs(directory)
        safeMkdirs(tempDir)
        // A previous process may have died between writing a temp file and renaming it.
        cleanTempFiles()
    }

    fun fileFor(key: String): File {
        val hash = sha1(key)
        return File(File(directory, hash.substring(0, 2)), "$hash.bin")
    }

    /** Returns the stored bytes, or null when absent/corrupt. Touches the entry for LRU ordering. */
    fun get(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) {
                file.delete()
                null
            } else {
                file.setLastModified(System.currentTimeMillis())
                bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stores [bytes] atomically. Returns true when the entry is readable afterwards.
     * Oversized or empty payloads are rejected rather than silently filling the quota.
     */
    fun put(key: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size.toLong() > maxEntryBytes) return false
        val target = fileFor(key)
        val previousLength = if (target.isFile) target.length() else -1L
        val temp = File(tempDir, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { it.write(bytes) }
            safeMkdirs(target.parentFile)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            val tracked = knownBytes.get()
            if (tracked >= 0) {
                knownBytes.addAndGet(bytes.size - (if (previousLength >= 0) previousLength else 0L))
            }
            maybeTrim()
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    /** Refreshes the LRU timestamp without rewriting the payload. */
    fun touch(key: String) {
        try {
            fileFor(key).setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {
            // Cache bookkeeping must never break a screen.
        }
    }

    fun lastModified(key: String): Long {
        return try {
            fileFor(key).lastModified()
        } catch (_: Exception) {
            0L
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            val file = fileFor(key)
            val length = if (file.isFile) file.length() else 0L
            if (file.delete()) {
                val tracked = knownBytes.get()
                if (tracked >= 0) knownBytes.addAndGet(-length)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
            safeMkdirs(tempDir)
            knownBytes.set(0L)
            writesSinceTrim = 0
        }
    }

    fun sizeBytes(): Long {
        val known = knownBytes.get()
        if (known >= 0) return known
        val counted = countBytes()
        knownBytes.set(counted)
        return counted
    }

    /**
     * Removes expired entries and evicts least-recently-used entries until both the byte quota and
     * the entry-count quota are satisfied.
     */
    fun trim() {
        synchronized(lock) {
            val collected = ArrayList<File>()
            collectFiles(directory, collected)
            val now = System.currentTimeMillis()
            val live = ArrayList<File>(collected.size)
            var total = 0L
            for (file in collected) {
                val modified = file.lastModified()
                if (maxAgeMs != Long.MAX_VALUE && now - modified > maxAgeMs) {
                    file.delete()
                    continue
                }
                total += file.length()
                live.add(file)
            }

            var count = live.size
            knownBytes.set(total)
            if (total <= maxBytes && count <= maxEntries) return

            live.sortBy { it.lastModified() }
            for (file in live) {
                if (total <= maxBytes && count <= maxEntries) break
                val length = file.length()
                if (file.delete()) {
                    total -= length
                    count--
                }
            }
            knownBytes.set(total)
        }
    }

    private fun maybeTrim() {
        val tracked = knownBytes.get()
        val dueToBytes = tracked >= maxBytes
        val dueToWrites = ++writesSinceTrim >= TRIM_EVERY_WRITES
        if (dueToBytes || dueToWrites) {
            writesSinceTrim = 0
            trim()
        }
    }

    private fun countBytes(): Long {
        val files = ArrayList<File>()
        collectFiles(directory, files)
        var total = 0L
        for (file in files) total += file.length()
        return total
    }

    private fun collectFiles(parent: File, out: MutableList<File>) {
        val children = parent.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name != tempDir.name) collectFiles(child, out)
            } else {
                out.add(child)
            }
        }
    }

    private fun cleanTempFiles() {
        try {
            tempDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    private fun safeMkdirs(dir: File?) {
        try {
            if (dir != null && !dir.isDirectory) dir.mkdirs()
        } catch (_: Exception) {
            // If the cache directory cannot be created we simply operate as a no-op cache.
        }
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val v = byte.toInt() and 0xff
            if (v < 0x10) out.append('0')
            out.append(Integer.toHexString(v))
        }
        return out.toString()
    }

    private companion object {
        const val TRIM_EVERY_WRITES = 24
    }
}

package com.hm.video.data.cache

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.Type

/**
 * Persists parsed catalog results (home, category pages, search, rank, daily updates, details) as
 * JSON on disk so a cold start or a tab switch can paint immediately without re-downloading and
 * re-parsing HTML.
 *
 * Each entry carries the HTTP validators returned by the origin; they are sent back with the next
 * refresh so an unchanged page costs a tiny 304 response instead of a full download + parse.
 *
 * Any decode problem deletes that single entry and behaves as a cache miss: a corrupt file or a
 * changed data model must never crash a screen.
 */
class MetadataCache(
    rootDir: File,
    private val formatVersion: Int = CacheConfig.FORMAT_VERSION,
    installationTag: String = ""
) {

    class Entry<T>(
        val value: T,
        val savedAt: Long,
        val etag: String?,
        val lastModified: String?
    ) {
        fun ageMs(now: Long = System.currentTimeMillis()): Long =
            (now - savedAt).coerceAtLeast(0L)
    }

    private val gson = Gson()
    private val store = DiskStore(
        rootDir = rootDir,
        maxBytes = CacheConfig.META_MAX_BYTES,
        maxEntries = CacheConfig.META_MAX_ENTRIES,
        maxAgeMs = CacheConfig.META_MAX_AGE_MS,
        namespace = "meta"
    )
    private val marker = File(rootDir, "meta.version")
    private val markerValue =
        if (installationTag.isBlank()) formatVersion.toString() else "$formatVersion|$installationTag"

    init {
        try {
            val installed = if (marker.isFile) marker.readText().trim() else ""
            if (installed != markerValue) {
                store.clear()
                marker.parentFile?.mkdirs()
                marker.writeText(markerValue)
            }
        } catch (_: Exception) {
            // A broken marker only costs us one extra cache miss; never fail app startup.
        }
    }

    fun <T> get(key: String, type: Type): Entry<T>? {
        val bytes = store.get(key) ?: return null
        return try {
            val envelope = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            val version = envelope.get("v")?.takeIf { it.isJsonPrimitive }?.asInt
            if (version != formatVersion) {
                store.remove(key)
                return null
            }
            val savedAt = envelope.get("savedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
            val payload = envelope.get("payload") ?: return null
            val value = gson.fromJson<T>(payload, type) ?: return null
            Entry(
                value = value,
                savedAt = savedAt,
                etag = envelope.stringOrNull("etag"),
                lastModified = envelope.stringOrNull("lastModified")
            )
        } catch (_: Exception) {
            store.remove(key)
            null
        }
    }

    fun <T> put(
        key: String,
        value: T,
        type: Type,
        etag: String? = null,
        lastModified: String? = null,
        savedAt: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            val envelope = JsonObject()
            envelope.addProperty("v", formatVersion)
            envelope.addProperty("savedAt", savedAt)
            if (!etag.isNullOrEmpty()) envelope.addProperty("etag", etag)
            if (!lastModified.isNullOrEmpty()) envelope.addProperty("lastModified", lastModified)
            envelope.add("payload", gson.toJsonTree(value, type))
            store.put(key, envelope.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Marks an entry as just validated (used when the origin answers 304 Not Modified) without
     * throwing away the parsed payload.
     */
    fun touch(key: String, etag: String? = null, lastModified: String? = null) {
        try {
            val bytes = store.get(key) ?: return
            val envelope = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            if (envelope.get("v")?.asInt != formatVersion) {
                store.remove(key)
                return
            }
            envelope.addProperty("savedAt", System.currentTimeMillis())
            if (!etag.isNullOrEmpty()) envelope.addProperty("etag", etag)
            if (!lastModified.isNullOrEmpty()) envelope.addProperty("lastModified", lastModified)
            store.put(key, envelope.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            store.remove(key)
        }
    }

    fun remove(key: String) = store.remove(key)

    fun trim() = store.trim()

    fun sizeBytes(): Long = store.sizeBytes()

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }
}

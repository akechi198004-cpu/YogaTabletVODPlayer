package com.hm.video.data.cache

/**
 * Tunables for the on-device caches. Kept in one place so freshness can be adjusted without
 * touching screen code.
 *
 * Freshness model (stale-while-revalidate):
 * - memoryTtlMs: how long a parsed result stays in the in-process cache.
 * - freshMs:    how long a disk entry is served without contacting the source at all.
 * - maxStaleMs: how old an entry may be and still be shown (background refresh, or offline).
 *
 * Entries older than maxStaleMs are ignored, so a layout/parser change on the source site can
 * never keep the app stuck on ancient data forever.
 */
data class CachePolicy(
    val memoryTtlMs: Long,
    val freshMs: Long,
    val maxStaleMs: Long
) {
    /** True when the entry can be shown immediately without any network access. */
    fun isFresh(ageMs: Long): Boolean = ageMs in 0 until freshMs

    /** True when the entry may be shown as a fallback while a refresh happens (or fails). */
    fun isUsable(ageMs: Long): Boolean = ageMs in 0 until maxStaleMs

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /** Home page rotates often; refresh in the background shortly after it is shown. */
        val HOME = CachePolicy(memoryTtlMs = MINUTE, freshMs = 5 * MINUTE, maxStaleMs = 7 * DAY)

        /** Catalog pages change slowly; a longer window avoids re-downloading while paging. */
        val LIST = CachePolicy(memoryTtlMs = MINUTE, freshMs = 15 * MINUTE, maxStaleMs = 30 * DAY)

        /** Repeated searches are common on a TV remote. */
        val SEARCH = CachePolicy(memoryTtlMs = MINUTE, freshMs = 10 * MINUTE, maxStaleMs = 7 * DAY)

        val DAILY = CachePolicy(memoryTtlMs = 5 * MINUTE, freshMs = 20 * MINUTE, maxStaleMs = 3 * DAY)

        val TOP = CachePolicy(memoryTtlMs = 5 * MINUTE, freshMs = 20 * MINUTE, maxStaleMs = 7 * DAY)

        /**
         * Episode lists only change when the source adds episodes. The detail page still refreshes
         * after six hours, and the player always re-resolves play URLs if needed.
         */
        val DETAIL = CachePolicy(memoryTtlMs = 10 * MINUTE, freshMs = 6 * HOUR, maxStaleMs = 60 * DAY)
    }
}

object CacheConfig {
    /**
     * Bump this whenever models or the envelope shape change. Old caches are wiped on upgrade
     * instead of being parsed into incompatible objects.
     */
    const val FORMAT_VERSION = 1

    const val ROOT_DIR = "hm_local_cache"

    const val META_MAX_BYTES = 32L * 1024 * 1024
    const val META_MAX_ENTRIES = 1600
    const val META_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    const val IMAGE_MAX_BYTES = 200L * 1024 * 1024
    const val IMAGE_MAX_ENTRIES = 3200
    const val IMAGE_MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000
    const val IMAGE_MAX_ENTRY_BYTES = 10L * 1024 * 1024

    /** Poster bytes are revalidated with the origin after this long, without blocking display. */
    const val IMAGE_REVALIDATE_MS = 7L * 24 * 60 * 60 * 1000

    /** Minimum spacing between background refreshes of the same catalog key. */
    const val MIN_REFRESH_INTERVAL_MS = 2L * 60 * 1000
}

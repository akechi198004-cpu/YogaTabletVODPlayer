package com.hm.video.data.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {

    @Test
    fun freshWindowServesWithoutNetwork() {
        val policy = CachePolicy.LIST
        assertTrue(policy.isFresh(0))
        assertTrue(policy.isFresh(policy.freshMs - 1))
        assertFalse(policy.isFresh(policy.freshMs))
    }

    @Test
    fun staleWindowIsUsableForBackgroundRefreshFallback() {
        val policy = CachePolicy.DETAIL
        val stale = policy.freshMs + 1
        assertFalse(policy.isFresh(stale))
        assertTrue(policy.isUsable(stale))
        assertTrue(policy.isUsable(policy.maxStaleMs - 1))
        assertFalse(policy.isUsable(policy.maxStaleMs))
    }

    @Test
    fun negativeAgesAreNeverConsideredFresh() {
        val policy = CachePolicy.HOME
        assertFalse(policy.isFresh(-1))
        assertFalse(policy.isUsable(-1))
    }

    @Test
    fun detailCacheOutlivesCatalogCache() {
        assertTrue(CachePolicy.DETAIL.freshMs > CachePolicy.LIST.freshMs)
        assertTrue(CachePolicy.DETAIL.maxStaleMs > CachePolicy.HOME.maxStaleMs)
    }
}

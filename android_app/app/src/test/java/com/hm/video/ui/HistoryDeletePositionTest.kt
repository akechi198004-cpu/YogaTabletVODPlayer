package com.hm.video.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDeletePositionTest {

    @Test
    fun deletingFirstKeepsNextRecordAtSameIndex() {
        assertEquals(0, MainActivity.nextFocusIndexAfterDelete(0, 3))
    }

    @Test
    fun deletingMiddleKeepsFollowingRecord() {
        assertEquals(2, MainActivity.nextFocusIndexAfterDelete(2, 4))
    }

    @Test
    fun deletingLastFallsBackToPreviousRecord() {
        assertEquals(3, MainActivity.nextFocusIndexAfterDelete(4, 4))
    }

    @Test
    fun deletingOnlyRecordLeavesNoTarget() {
        assertEquals(-1, MainActivity.nextFocusIndexAfterDelete(0, 0))
    }
}

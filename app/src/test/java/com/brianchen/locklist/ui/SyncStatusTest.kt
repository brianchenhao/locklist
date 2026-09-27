package com.brianchen.locklist.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun briefOfflineWithNothingWaitingIsNotAnAlarm() {
        assertFalse(
            syncNeedsAttention(now - 5 * minute, "No connection to the web", pending = 0, now = now, offline = true)
        )
    }

    @Test
    fun offlineRightAfterAnOfflineTickIsNotAnAlarmYet() {
        assertFalse(
            syncNeedsAttention(now - 5 * minute, "No connection to the web", pending = 1, now = now, offline = true)
        )
    }

    @Test
    fun offlineWithChangesWaitingOverHalfAnHourIsAnAlarm() {
        assertTrue(
            syncNeedsAttention(now - 31 * minute, "No connection to the web", pending = 1, now = now, offline = true)
        )
    }

    @Test
    fun aRefusalByTheWebIsAnAlarmAtOnce() {
        assertTrue(
            syncNeedsAttention(now - minute, "The web refused it: bad row", pending = 0, now = now, offline = false)
        )
    }

    @Test
    fun staleWaitingChangesAreAnAlarmWithoutAnyError() {
        assertTrue(syncNeedsAttention(now - 31 * minute, null, pending = 2, now = now))
        assertFalse(syncNeedsAttention(now - 31 * minute, null, pending = 0, now = now))
        assertFalse(syncNeedsAttention(now - 5 * minute, null, pending = 2, now = now))
    }
}

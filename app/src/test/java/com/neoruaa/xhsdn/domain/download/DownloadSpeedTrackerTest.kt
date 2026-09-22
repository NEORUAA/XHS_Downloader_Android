package com.neoruaa.xhsdn.domain.download

import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DownloadSpeedTrackerTest {
    @Test fun samplesActualElapsedTimeAndClearsIdleSpeed() {
        var now = 0L
        val tracker = DownloadSpeedTracker { now }
        tracker.recordBytes(1024)
        now = 250_000_000L
        assertNull(tracker.sampleBytesPerSecond())
        tracker.recordBytes(1024)
        now = 500_000_000L
        assertEquals(4096L, tracker.sampleBytesPerSecond())
        tracker.recordBytes(4096)
        now = 2_500_000_000L
        assertEquals(2048L, tracker.sampleBytesPerSecond())
        now = 3_000_000_000L
        assertEquals(0L, tracker.sampleBytesPerSecond())
    }

    @Test fun aggregatesConcurrentWorkersWithoutLosingBytes() {
        var now = 0L
        val tracker = DownloadSpeedTracker { now }
        val workers = List(4) { thread { repeat(10_000) { tracker.recordBytes(1) } } }
        workers.forEach { it.join() }
        now = 1_000_000_000L
        assertEquals(40_000L, tracker.sampleBytesPerSecond())
    }
}

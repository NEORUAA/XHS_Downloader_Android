package com.neoruaa.xhsdn.domain.download

import java.util.concurrent.atomic.AtomicLong

/** Counts received network bytes across workers, excluding cached and resumed file offsets. */
internal class DownloadSpeedTracker(private val nanoTime: () -> Long = System::nanoTime) {
    private val pendingBytes = AtomicLong()
    private var lastSample = nanoTime()

    fun recordBytes(count: Long) {
        if (count > 0) pendingBytes.addAndGet(count)
    }

    /** Called by the queue ticker; idle intervals naturally report zero. */
    fun sampleBytesPerSecond(): Long? {
        val now = nanoTime()
        val elapsed = now - lastSample
        if (elapsed < 500_000_000L) return null
        lastSample = now
        return (pendingBytes.getAndSet(0).toDouble() * 1_000_000_000 / elapsed).toLong()
    }
}

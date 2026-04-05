package com.tunisianprayertimes.platform

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop timer using ScheduledExecutorService.
 */
actual object TimerScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SilenceTimer").apply { isDaemon = true }
    }
    private var pendingTask: ScheduledFuture<*>? = null

    actual fun scheduleAfter(durationMinutes: Int, onExpired: () -> Unit): Long {
        cancel()
        val endsAt = System.currentTimeMillis() + durationMinutes * 60_000L
        pendingTask = executor.schedule(
            { onExpired() },
            durationMinutes.toLong(),
            TimeUnit.MINUTES
        )
        return endsAt
    }

    actual fun cancel() {
        pendingTask?.cancel(false)
        pendingTask = null
    }
}

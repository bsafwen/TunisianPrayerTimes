package com.tunisianprayertimes.platform

/**
 * Platform abstraction for scheduling timed events.
 * On Android: AlarmManager. On Desktop: ScheduledExecutorService.
 */
expect object TimerScheduler {
    /** Schedule a callback after [durationMinutes]. Returns the end time in millis. */
    fun scheduleAfter(durationMinutes: Int, onExpired: () -> Unit): Long

    /** Cancel any pending scheduled timer. */
    fun cancel()
}

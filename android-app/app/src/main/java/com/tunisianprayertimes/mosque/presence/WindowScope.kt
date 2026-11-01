package com.tunisianprayertimes.mosque.presence

/** Existing scheduler intervals are inputs; no padding or bridge is introduced. */
data class PrayerWindow(val occurrence: String, val start: Long, val end: Long, val enabled: Boolean = true) {
    init { require(occurrence.isNotBlank() && start >= 0 && end > start) }
    fun contains(now: Long): Boolean = enabled && now >= start && now < end
}

data class WindowCoverage(val start: Long, val activeEnd: Long, val occurrences: Set<String>)

object WindowScope {
    /** Component start identifies continuity; only windows already containing now supply an end. */
    fun coverage(windows: List<PrayerWindow>, dismissed: Set<String>, now: Long): WindowCoverage? {
        require(windows.map { it.occurrence }.distinct().size == windows.size)
        val eligible = windows.filter { it.enabled && it.occurrence !in dismissed }.sortedBy { it.start }
        val active = eligible.filter { it.contains(now) }
        if (active.isEmpty()) return null
        var start = eligible.first().start
        var end = eligible.first().end
        for (w in eligible.drop(1)) {
            if (w.start > end) {
                if (now >= start && now < end) break
                start = w.start; end = w.end
            } else end = maxOf(end, w.end)
        }
        return WindowCoverage(start, active.maxOf { it.end }, active.map { it.occurrence }.toSet())
    }
}


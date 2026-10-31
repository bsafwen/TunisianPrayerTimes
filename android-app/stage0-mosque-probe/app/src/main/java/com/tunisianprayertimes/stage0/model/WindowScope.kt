package com.tunisianprayertimes.stage0.model

/** Existing scheduler intervals are inputs; the probe neither invents padding nor changes their times. */
data class ProbeWindow(val occurrence: String, val start: Long, val end: Long, val enabled: Boolean = true) {
    init { require(occurrence.isNotBlank() && start >= 0 && end > start) }
    fun contains(now: Long): Boolean = enabled && now >= start && now < end
}

data class WindowCoverage(val start: Long, val activeEnd: Long, val occurrences: Set<String>)

object WindowScope {
    /** Component start identifies continuity; only windows already containing now supply an end. */
    fun coverage(windows: List<ProbeWindow>, dismissed: Set<String>, now: Long): WindowCoverage? {
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

/** Window-scoped model. Its interval inputs are fixtures, not a production scheduler integration. */
class WindowPresence(
    private val bootId: String,
    private val areas: List<ProbeArea>,
    windows: List<ProbeWindow>,
    val config: PresenceConfig = PresenceConfig(),
    mode: ProbeMode = ProbeMode.TIME_AND_MOSQUE,
    private var master: Boolean = true,
    private var ready: Boolean = true,
    dismissed: Set<String> = emptySet(),
) {
    var mode = mode; private set
    var windows = windows.toList(); private set
    private val dismissals = dismissed.toMutableSet()
    val dismissed: Set<String> get() = dismissals.toSet()
    private var machine: PresenceMachine? = null
    private var coverage: WindowCoverage? = null
    private var serial = 0L
    private var lastNow = -1L
    var evaluations = 0; private set
    var snapshot: PresenceSnapshot? = null; private set
    val epoch: EventEpoch? get() = machine?.snapshot?.epoch
    val presenceActive: Boolean get() = machine?.locationRequestActive == true && coverage != null
    val locationChecksAllowed: Boolean get() = mode == ProbeMode.TIME_AND_MOSQUE && coverage != null && master && ready
    val effectiveDeadline: Long? get() {
        val m = machine ?: return null
        val end = coverage?.activeEnd ?: return null
        val s = m.snapshot
        val candidates = listOfNotNull(s.acquisitionDeadlineMillis,
            s.lastUsableDeliveredAtMillis?.let { it + config.noObservationMillis },
            s.lastStrictInsideAtMillis?.let { it + config.ambiguityMillis }, end)
        return candidates.minOrNull()
    }

    fun reconcile(now: Long) {
        require(now >= lastNow && now >= 0) { "Monotonic time cannot move backwards" }
        lastNow = now
        val current = if (master && ready && mode == ProbeMode.TIME_AND_MOSQUE)
            WindowScope.coverage(windows, dismissed, now) else null
        if (current == null) { machine = null; coverage = null; snapshot = null; return }
        if (coverage?.start != current.start || machine == null) {
            machine = PresenceMachine(EventEpoch(bootId, "window-${++serial}-${current.start}"), areas, config)
        }
        coverage = current
        machine!!.tick(now)
        snapshot = machine!!.snapshot.let { s -> s.copy(
            acquisitionDeadlineMillis = s.acquisitionDeadlineMillis?.coerceAtMost(current.activeEnd)) }
    }

    fun begin(now: Long, deliberate: Boolean = false): Boolean {
        reconcile(now)
        val m = machine ?: return false
        if (coverage!!.activeEnd - now < config.entryDwellMillis) return false
        val result = m.beginAcquisition(now, m.snapshot.epoch, deliberate)
        reconcile(now)
        return result
    }

    fun observe(fix: ProbeFix, now: Long, token: EventEpoch?): FixAssessment {
        reconcile(now)
        val m = machine ?: return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.OUTSIDE_WINDOW)
        if (token != m.snapshot.epoch) return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.OBSOLETE_EPOCH)
        if (fix.elapsedRealtimeMillis < coverage!!.start) return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.AGE)
        evaluations++
        val result = m.observe(fix, now, token)
        reconcile(now)
        return result
    }

    fun skip(now: Long) {
        reconcile(now)
        dismissals.addAll(coverage?.occurrences.orEmpty())
        reconcile(now)
    }

    fun wake(now: Long, eligible: Boolean) {
        reconcile(now)
        if (eligible && presenceActive) skip(now)
    }

    fun replace(now: Long, windows: List<ProbeWindow> = this.windows, mode: ProbeMode = this.mode,
                master: Boolean = this.master, ready: Boolean = this.ready) {
        this.windows = windows.toList(); this.mode = mode; this.master = master; this.ready = ready
        machine = null; coverage = null
        reconcile(now)
    }
}

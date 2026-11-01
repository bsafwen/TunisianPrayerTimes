package com.tunisianprayertimes.mosque.presence

data class PresenceConfig(
    val entryDwellMillis: Long = 15_000,
    val acquisitionMillis: Long = 90_000,
    val exitDwellMillis: Long = 20_000,
    val noObservationMillis: Long = 300_000,
    val ambiguityMillis: Long = 900_000,
    val maxFixAgeMillis: Long = 30_000,
    val strictAccuracyMeters: Double = 30.0,
    val holdingAccuracyMeters: Double = 100.0,
    val entryMarginMeters: Double = 2.0,
    val exitMarginMeters: Double = 5.0,
    val observationIntervalMillis: Long = 60_000,
) {
    init {
        require(entryDwellMillis > 0 && acquisitionMillis > entryDwellMillis)
        require(exitDwellMillis > 0 && noObservationMillis > exitDwellMillis)
        require(ambiguityMillis > exitDwellMillis && ambiguityMillis / 3 > observationIntervalMillis)
        require(maxFixAgeMillis >= 0)
        require(strictAccuracyMeters > 0 && strictAccuracyMeters.isFinite() &&
            holdingAccuracyMeters.isFinite() && holdingAccuracyMeters >= strictAccuracyMeters)
        require(entryMarginMeters.isFinite() && exitMarginMeters.isFinite() &&
            entryMarginMeters >= 0 && exitMarginMeters >= 0 && observationIntervalMillis > 0)
    }
}

/** Caller bumps every generation when that authority changes. All callbacks carry the whole epoch. */
data class EventEpoch(
    val bootId: String,
    val sessionId: String,
    val modeGeneration: Long = 0,
    val permissionGeneration: Long = 0,
    val geometryGeneration: Long = 0,
    val requestGeneration: Long = 0,
) {
    init {
        require(bootId.isNotBlank() && sessionId.isNotBlank())
        require(modeGeneration >= 0 && permissionGeneration >= 0 && geometryGeneration >= 0 && requestGeneration >= 0)
    }
}

enum class PresenceState {
    IDLE, ACQUIRING, VISIT_CONFIRMED, VISIT_UNCERTAIN, EXITING,
    SUPPRESSED, INACTIVE_NEEDS_SETUP, INACTIVE_NEEDS_REARM,
}
enum class PresenceReason {
    INITIAL, ACQUISITION_STARTED, ENTRY_DWELL, ENTERED, UNCERTAIN, OUTSIDE_DWELL, CONFIRMED_EXIT,
    ACQUISITION_TIMEOUT, NO_OBSERVATION, AMBIGUITY_LIMIT, USER_STOP, WAKE_EXCEPTION,
    PREREQUISITE_LOSS, ENVIRONMENT_CHANGED, RECOVERY_REARM, RETRY,
}
enum class StartRoute { VISIBLE_USER_ACTION, GEOFENCE_EVENT, PRAYER_WINDOW_CALLBACK, VISIBLE_RESUME }

/** Only current support, not a trail. Safe to serialize locally; restore via recovered(), never constructor flags. */
data class PresenceSnapshot(
    val epoch: EventEpoch,
    val state: PresenceState,
    val reason: PresenceReason,
    val supportingAreaIds: Set<String> = emptySet(),
    val enteredAtMillis: Long? = null,
    val lastStrictInsideAtMillis: Long? = null,
    val lastUsableDeliveredAtMillis: Long? = null,
    val acquisitionDeadlineMillis: Long? = null,
    val requiresDepartureOrExplicitRearm: Boolean = false,
    val exitEvaluationRequested: Boolean = false,
) {
    val hasBoundedVisit: Boolean get() = state == PresenceState.VISIT_CONFIRMED ||
        state == PresenceState.VISIT_UNCERTAIN || state == PresenceState.EXITING
}

/**
 * Presence accumulator; WindowPresence gates every application-level observation. No Android effects. The caller serializes calls and supplies monotonic time.
 * A time limit is a desired-state limit, not a promise that Android will run a callback at that instant.
 */
class PresenceMachine(
    initialEpoch: EventEpoch,
    areas: List<SilenceArea>,
    val config: PresenceConfig = PresenceConfig(),
    ready: Boolean = true,
    storeAvailable: Boolean = true,
) {
    private var areas = validatedAreas(areas)
    private var ready = ready
    private var storeAvailable = storeAvailable
    private var lastEventAt = -1L
    private var lastAcceptedFixAt = -1L
    private var insideSeedAt: Long? = null
    private var insideSeedAreas: Set<String> = emptySet()
    private var outsideSeedAt: Long? = null
    private var acquisitionRetries = 0

    var snapshot: PresenceSnapshot = PresenceSnapshot(
        initialEpoch,
        if (ready && storeAvailable && areas.any { it.enabled }) PresenceState.IDLE else PresenceState.INACTIVE_NEEDS_SETUP,
        PresenceReason.INITIAL,
    )
        private set

    val locationRequestActive: Boolean get() = snapshot.hasBoundedVisit && usableEnvironment()

    fun beginAcquisition(
        now: Long,
        epoch: EventEpoch,
        deliberateRearm: Boolean = false,
        route: StartRoute = StartRoute.VISIBLE_USER_ACTION,
    ): Boolean {
        if (!acceptEvent(now, epoch)) return false
        reconcileDeadlines(now)
        if (!usableEnvironment() || snapshot.hasBoundedVisit || snapshot.state == PresenceState.ACQUIRING) return false
        if (snapshot.requiresDepartureOrExplicitRearm &&
            (!deliberateRearm || route != StartRoute.VISIBLE_USER_ACTION)) return false
        clearEvidence()
        acquisitionRetries = 0
        snapshot = PresenceSnapshot(
            epoch, PresenceState.ACQUIRING, PresenceReason.ACQUISITION_STARTED,
            acquisitionDeadlineMillis = deadline(now, config.acquisitionMillis),
        )
        return true
    }

    /** At most one retry; startup and retry both consume the original 90-second deadline. */
    fun retryAcquisition(now: Long, epoch: EventEpoch): Boolean {
        if (!acceptEvent(now, epoch)) return false
        reconcileDeadlines(now)
        if (snapshot.state != PresenceState.ACQUIRING || acquisitionRetries != 0) return false
        acquisitionRetries++
        insideSeedAt = null; insideSeedAreas = emptySet()
        snapshot = snapshot.copy(reason = PresenceReason.RETRY)
        return true
    }

    fun observe(fix: PresenceFix, deliveredNow: Long, epoch: EventEpoch): FixAssessment {
        if (epoch != snapshot.epoch) return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.OBSOLETE_EPOCH)
        if (!acceptEvent(deliveredNow, epoch)) return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.CLOCK)
        reconcileDeadlines(deliveredNow)
        if (!usableEnvironment()) return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.NOT_READY)
        if (fix.elapsedRealtimeMillis <= lastAcceptedFixAt) {
            return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.DUPLICATE_OR_OUT_OF_ORDER)
        }
        val result = PresenceGeometry.classify(fix, deliveredNow, areas, config, storeAvailable)
        // Even an unusable delivered sample cannot be replayed as a new observation with edited accuracy.
        if (fix.elapsedRealtimeMillis >= 0 && fix.elapsedRealtimeMillis <= deliveredNow) {
            lastAcceptedFixAt = fix.elapsedRealtimeMillis
        }
        if (result.classification == FixClass.UNUSABLE || result.classification == FixClass.STORE_UNAVAILABLE) return result
        // No observations may independently initiate acquisition or remove an unresolved-visit barrier.
        if (snapshot.state == PresenceState.IDLE) return result
        if (snapshot.state == PresenceState.ACQUIRING) {
            if (fix.elapsedRealtimeMillis < requireNotNull(snapshot.acquisitionDeadlineMillis) - config.acquisitionMillis) {
                return FixAssessment(FixClass.UNUSABLE, rejection = FixRejection.AGE)
            }
            acquire(fix, deliveredNow, result)
            return result
        }
        if (snapshot.hasBoundedVisit) {
            snapshot = snapshot.copy(lastUsableDeliveredAtMillis = deliveredNow, exitEvaluationRequested = false)
            when (result.classification) {
                FixClass.STRICT_INSIDE -> {
                    outsideSeedAt = null
                    snapshot = snapshot.copy(
                        state = PresenceState.VISIT_CONFIRMED, reason = PresenceReason.ENTERED,
                        supportingAreaIds = result.insideAreaIds,
                        lastStrictInsideAtMillis = fix.elapsedRealtimeMillis,
                    )
                }
                FixClass.STRICT_OUTSIDE -> processOutside(fix.elapsedRealtimeMillis)
                else -> {
                    outsideSeedAt = null
                    snapshot = snapshot.copy(state = PresenceState.VISIT_UNCERTAIN, reason = PresenceReason.UNCERTAIN)
                }
            }
        } else if (snapshot.requiresDepartureOrExplicitRearm) {
            // Suppression/expiry may observe a departure, but an inside fix cannot renew/clear them.
            if (result.classification == FixClass.STRICT_OUTSIDE) processOutside(fix.elapsedRealtimeMillis)
            else outsideSeedAt = null
        }
        return result
    }

    fun tick(now: Long, epoch: EventEpoch = snapshot.epoch): PresenceSnapshot {
        if (acceptEvent(now, epoch)) reconcileDeadlines(now)
        return snapshot
    }

    /** A circle transition requests observation. It is neither a polygon exit nor renewed support. */
    fun coarseExitHint(now: Long, epoch: EventEpoch): PresenceSnapshot {
        if (acceptEvent(now, epoch)) {
            reconcileDeadlines(now)
            snapshot = snapshot.copy(exitEvaluationRequested = true)
        }
        return snapshot
    }

    fun stop(now: Long, epoch: EventEpoch = snapshot.epoch): PresenceSnapshot =
        suppress(now, epoch, PresenceReason.USER_STOP)

    /** Combined-mode wake policy; TIME_ONLY wake policy stays in the existing wake controllers. */
    fun onWake(
        now: Long,
        epoch: EventEpoch = snapshot.epoch,
        eligible: Boolean,
        insidePrayerWindow: Boolean,
        mode: AutomaticSilenceMode = AutomaticSilenceMode.TIME_AND_MOSQUE,
    ): PresenceSnapshot {
        if (!acceptEvent(now, epoch)) return snapshot
        reconcileDeadlines(now)
        if (snapshot.hasBoundedVisit && mode != AutomaticSilenceMode.TIME_ONLY &&
            eligible && insidePrayerWindow) {
            return suppress(now, epoch, PresenceReason.WAKE_EXCEPTION)
        }
        return snapshot
    }

    /** Permission, geometry, mode and session replacement invalidates old callbacks and active authority. */
    fun replaceEnvironment(
        now: Long,
        epoch: EventEpoch,
        areas: List<SilenceArea> = this.areas,
        ready: Boolean = this.ready,
        storeAvailable: Boolean = this.storeAvailable,
    ): PresenceSnapshot {
        val checked = validatedAreas(areas)
        val bootChanged = epoch.bootId != snapshot.epoch.bootId
        if (now < 0 || (!bootChanged && now < lastEventAt)) return snapshot
        val changed = epoch != snapshot.epoch || checked != this.areas || ready != this.ready || storeAvailable != this.storeAvailable
        if (!changed) return tick(now)
        require(epoch != snapshot.epoch) { "Environment changes need a new epoch to reject already-dispatched callbacks" }
        val unresolved = snapshot.requiresDepartureOrExplicitRearm || snapshot.hasBoundedVisit
        this.areas = checked; this.ready = ready; this.storeAvailable = storeAvailable
        lastEventAt = now; clearEvidence()
        snapshot = PresenceSnapshot(
            epoch,
            if (!usableEnvironment()) PresenceState.INACTIVE_NEEDS_SETUP
            else if (unresolved) PresenceState.INACTIVE_NEEDS_REARM else PresenceState.IDLE,
            if (!usableEnvironment()) PresenceReason.PREREQUISITE_LOSS else PresenceReason.ENVIRONMENT_CHANGED,
            requiresDepartureOrExplicitRearm = unresolved,
        )
        return snapshot
    }

    private fun acquire(fix: PresenceFix, now: Long, result: FixAssessment) {
        if (result.classification != FixClass.STRICT_INSIDE) {
            insideSeedAt = null; insideSeedAreas = emptySet()
            return
        }
        val common = insideSeedAreas.intersect(result.insideAreaIds)
        val firstAt = insideSeedAt
        if (firstAt == null || common.isEmpty()) {
            insideSeedAt = fix.elapsedRealtimeMillis
            insideSeedAreas = result.insideAreaIds
            snapshot = snapshot.copy(reason = PresenceReason.ENTRY_DWELL)
            return
        }
        insideSeedAreas = common
        if (fix.elapsedRealtimeMillis - firstAt >= config.entryDwellMillis) {
            snapshot = PresenceSnapshot(
                snapshot.epoch, PresenceState.VISIT_CONFIRMED, PresenceReason.ENTERED,
                supportingAreaIds = common, enteredAtMillis = now,
                lastStrictInsideAtMillis = fix.elapsedRealtimeMillis, lastUsableDeliveredAtMillis = now,
            )
            outsideSeedAt = null
        }
    }

    private fun processOutside(fixAt: Long) {
        val firstAt = outsideSeedAt
        if (firstAt == null) {
            outsideSeedAt = fixAt
            if (snapshot.hasBoundedVisit) snapshot = snapshot.copy(state = PresenceState.EXITING, reason = PresenceReason.OUTSIDE_DWELL)
        } else if (fixAt - firstAt >= config.exitDwellMillis) {
            clearEvidence()
            snapshot = PresenceSnapshot(snapshot.epoch, PresenceState.IDLE, PresenceReason.CONFIRMED_EXIT)
        }
    }

    private fun suppress(now: Long, epoch: EventEpoch, reason: PresenceReason): PresenceSnapshot {
        if (!acceptEvent(now, epoch)) return snapshot
        reconcileDeadlines(now)
        if (snapshot.state == PresenceState.SUPPRESSED || snapshot.requiresDepartureOrExplicitRearm ||
            (!snapshot.hasBoundedVisit && snapshot.state != PresenceState.ACQUIRING)) return snapshot
        outsideSeedAt = null
        snapshot = snapshot.copy(
            state = PresenceState.SUPPRESSED, reason = reason,
            acquisitionDeadlineMillis = null,
            requiresDepartureOrExplicitRearm = true,
        )
        return snapshot
    }

    private fun reconcileDeadlines(now: Long) {
        if (snapshot.state == PresenceState.ACQUIRING && now >= requireNotNull(snapshot.acquisitionDeadlineMillis)) {
            clearEvidence()
            snapshot = PresenceSnapshot(snapshot.epoch, PresenceState.IDLE, PresenceReason.ACQUISITION_TIMEOUT)
        } else if (snapshot.hasBoundedVisit) {
            val earliest = listOf(
                deadline(requireNotNull(snapshot.lastUsableDeliveredAtMillis), config.noObservationMillis) to PresenceReason.NO_OBSERVATION,
                deadline(requireNotNull(snapshot.lastStrictInsideAtMillis), config.ambiguityMillis) to PresenceReason.AMBIGUITY_LIMIT,
            ).minBy { it.first }
            if (now >= earliest.first) {
                outsideSeedAt = null
                snapshot = snapshot.copy(
                    state = PresenceState.INACTIVE_NEEDS_REARM, reason = earliest.second,
                    requiresDepartureOrExplicitRearm = true, exitEvaluationRequested = false,
                )
            }
        }
    }

    private fun usableEnvironment(): Boolean = ready && storeAvailable && areas.any { it.enabled }
    private fun acceptEvent(now: Long, epoch: EventEpoch): Boolean {
        if (epoch != snapshot.epoch || now < 0 || now < lastEventAt) return false
        lastEventAt = now
        return true
    }
    private fun clearEvidence() {
        lastAcceptedFixAt = -1; insideSeedAt = null; insideSeedAreas = emptySet(); outsideSeedAt = null
    }

    companion object {
        /** Process recreation never revives an old active flag. Across boot, old elapsed deadlines are discarded. */
        fun recovered(
            previous: PresenceSnapshot,
            epoch: EventEpoch,
            areas: List<SilenceArea>,
            now: Long,
            config: PresenceConfig = PresenceConfig(),
            ready: Boolean = true,
            storeAvailable: Boolean = true,
        ): PresenceMachine {
            require(now >= 0 && (epoch.bootId != previous.epoch.bootId || epoch.sessionId != previous.epoch.sessionId)) {
                "Recovery must use a new session or boot epoch"
            }
            val machine = PresenceMachine(epoch, areas, config, ready, storeAvailable)
            val unresolved = previous.hasBoundedVisit || previous.requiresDepartureOrExplicitRearm
            val retainSuppression = previous.state == PresenceState.SUPPRESSED && previous.epoch.bootId == epoch.bootId
            machine.lastEventAt = now
            machine.snapshot = PresenceSnapshot(
                epoch,
                when {
                    !machine.usableEnvironment() -> PresenceState.INACTIVE_NEEDS_SETUP
                    retainSuppression -> PresenceState.SUPPRESSED
                    unresolved -> PresenceState.INACTIVE_NEEDS_REARM
                    else -> PresenceState.IDLE
                },
                PresenceReason.RECOVERY_REARM,
                requiresDepartureOrExplicitRearm = unresolved,
            )
            return machine
        }
    }
}

private fun deadline(start: Long, duration: Long): Long =
    if (Long.MAX_VALUE - start < duration) Long.MAX_VALUE else start + duration

private fun validatedAreas(areas: List<SilenceArea>): List<SilenceArea> {
    require(areas.map { it.id }.distinct().size == areas.size) { "Duplicate logical area IDs" }
    return areas.toList()
}

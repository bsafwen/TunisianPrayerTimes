package com.tunisianprayertimes.stage0.model

enum class ProbeMode { TIME_ONLY, TIME_AND_MOSQUE }
enum class ProbeOwner { TIME_AUTOMATIC, LOCATION_AUTOMATIC, MANUAL, UNTIL_ALARM }
data class ProbePolicyDecision(val requestedOwners: Set<ProbeOwner>) {
    val requestsSilence: Boolean get() = requestedOwners.isNotEmpty()
}

/** Truth-table experiment only. No platform mutation, production bridge or production alarm routing. */
object ProbePolicy {
    fun decide(
        mode: ProbeMode,
        masterEnabled: Boolean,
        ready: Boolean,
        prayerWindowActive: Boolean,
        presenceActive: Boolean,
        otherRequests: Set<ProbeOwner> = emptySet(),
        alreadyActiveTimeOnlyBridge: Boolean = false,
    ): ProbePolicyDecision {
        require(otherRequests.all { it == ProbeOwner.MANUAL || it == ProbeOwner.UNTIL_ALARM })
        val automatic = masterEnabled && ready && when (mode) {
            ProbeMode.TIME_ONLY -> prayerWindowActive || alreadyActiveTimeOnlyBridge
            ProbeMode.TIME_AND_MOSQUE -> prayerWindowActive && presenceActive
        }
        val owner = if (mode == ProbeMode.TIME_ONLY) ProbeOwner.TIME_AUTOMATIC else ProbeOwner.LOCATION_AUTOMATIC
        return ProbePolicyDecision(if (automatic) otherRequests + owner else otherRequests.toSet())
    }
}

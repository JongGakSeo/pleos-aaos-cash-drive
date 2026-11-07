package ai.cashdrive.core.safety

enum class SafetyState {
    UNKNOWN,
    RESTRICTED,
    STOPPED_UNQUALIFIED,
    PARKED_ALLOWED,
}

enum class GearEvidence { PARK, NOT_PARK, UNKNOWN }

data class SafetySnapshot(
    val generation: Long,
    val sequence: Long,
    val connected: Boolean,
    val uxrRequiresDistractionOptimization: Boolean?,
    val activeUxRestrictions: Int?,
    val gear: GearEvidence,
    val state: SafetyState,
) {
    val mayOpenAd: Boolean get() = state == SafetyState.PARKED_ALLOWED
}

/** Reducer accepts only ordered callbacks in the current car-service generation. */
class SafetyReducer {
    private var generation = 0L
    private var sequence = -1L
    private var connected = false
    private var requiresDo: Boolean? = null
    private var activeRestrictions: Int? = null
    private var gear = GearEvidence.UNKNOWN

    @Synchronized
    fun beginConnection(newGeneration: Long): SafetySnapshot {
        if (newGeneration > generation) {
            generation = newGeneration
            sequence = -1L
            connected = false
            requiresDo = null
            activeRestrictions = null
            gear = GearEvidence.UNKNOWN
        }
        return snapshot()
    }

    @Synchronized
    fun onConnected(eventGeneration: Long, eventSequence: Long): SafetySnapshot = update(eventGeneration, eventSequence) {
        connected = true
    }

    @Synchronized
    fun onUxRestrictions(
        eventGeneration: Long,
        eventSequence: Long,
        requiresDistractionOptimization: Boolean,
        activeRestrictions: Int,
    ): SafetySnapshot = update(eventGeneration, eventSequence) {
        requiresDo = requiresDistractionOptimization
        this.activeRestrictions = activeRestrictions
    }

    @Synchronized
    fun onGear(eventGeneration: Long, eventSequence: Long, evidence: GearEvidence): SafetySnapshot =
        update(eventGeneration, eventSequence) { gear = evidence }

    /** Disconnect/hidden lifecycle invalidates all evidence immediately. */
    @Synchronized
    fun invalidate(newGeneration: Long): SafetySnapshot {
        if (newGeneration >= generation) {
            generation = newGeneration
            sequence = -1L
            connected = false
            requiresDo = null
            activeRestrictions = null
            gear = GearEvidence.UNKNOWN
        }
        return snapshot()
    }

    private inline fun update(eventGeneration: Long, eventSequence: Long, mutation: () -> Unit): SafetySnapshot {
        if (eventGeneration == generation && eventSequence > sequence) {
            sequence = eventSequence
            mutation()
        }
        return snapshot()
    }

    @Synchronized
    fun current(): SafetySnapshot = snapshot()

    private fun snapshot(): SafetySnapshot {
        val state = when {
            !connected || requiresDo == null || activeRestrictions == null || gear == GearEvidence.UNKNOWN ->
                SafetyState.UNKNOWN
            requiresDo == true || activeRestrictions != 0 -> SafetyState.RESTRICTED
            gear == GearEvidence.PARK -> SafetyState.PARKED_ALLOWED
            else -> SafetyState.STOPPED_UNQUALIFIED
        }
        return SafetySnapshot(generation, sequence, connected, requiresDo, activeRestrictions, gear, state)
    }
}

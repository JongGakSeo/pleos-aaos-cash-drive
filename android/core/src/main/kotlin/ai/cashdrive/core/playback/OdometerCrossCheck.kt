package ai.cashdrive.core.playback

import kotlin.math.roundToLong

/**
 * Baseline for the odometer cross-check: the last odometer reading (km) that was compared, and how many
 * odometer deltas could not be confirmed by the speed meter (a jump, or distance with no measured movement).
 */
data class OdometerState(val lastKm: Float? = null, val heldDeltas: Int = 0)

/**
 * Compares Pleos Vehicle SDK odometer readings with the distance the speed-integrating [TripMeter] measured
 * over the same interval. Tickets never come from here: the odometer is display and verification only, and
 * the two sources are never summed. An accepted delta is an odometer increase that is plausible
 * (at most [maxDeltaMeters]) and backed by some measured movement.
 */
class OdometerCrossCheck(private val maxDeltaMeters: Long = 5_000) {
    init {
        require(maxDeltaMeters > 0)
    }

    /**
     * Returns the next baseline and the accepted metres: 0 when this is the first reading, the odometer did not
     * increase, the increase exceeds [maxDeltaMeters] (held), or [movedMetersSinceLast] is 0 (held). Every
     * finite reading becomes the new baseline so one implausible step never poisons the following ones.
     */
    fun accept(state: OdometerState, odometerKm: Float, movedMetersSinceLast: Long): Pair<OdometerState, Long> {
        require(movedMetersSinceLast >= 0)
        if (!odometerKm.isFinite() || odometerKm < 0f) return state to 0L
        val last = state.lastKm ?: return state.copy(lastKm = odometerKm) to 0L
        val deltaMeters = ((odometerKm.toDouble() - last.toDouble()) * 1_000.0).roundToLong()
        val rebased = state.copy(lastKm = odometerKm)
        return when {
            deltaMeters <= 0 -> rebased to 0L
            deltaMeters > maxDeltaMeters || movedMetersSinceLast == 0L -> rebased.copy(heldDeltas = state.heldDeltas + 1) to 0L
            else -> rebased to deltaMeters
        }
    }
}

package ai.cashdrive.core.rewards

data class DistanceState(
    val lastOdometerMeters: Long? = null,
    val lastElapsedRealtimeMs: Long? = null,
    val residualMeters: Long = 0,
    val quarantinedSamples: Long = 0,
    val bootId: String? = null,
)
data class OdometerSample(val meters: Long, val elapsedRealtimeMs: Long, val bootId: String = "local")
data class DistanceResult(val state: DistanceState, val acceptedMeters: Long, val earnedBlocks: Long, val quarantined: Boolean)

/** Approved sources explicitly rebaseline after reboot/gap. No distance is invented. */
class DistanceAccumulator(
    private val blockMeters: Long = 1_000,
    private val maxDeltaMeters: Long = 5_000,
    private val maxSampleGapMs: Long = 120_000,
    private val maxMetersPerSecond: Long = 100,
) {
    init {
        require(blockMeters > 0 && maxDeltaMeters >= blockMeters && maxSampleGapMs > 0)
        require(maxMetersPerSecond > 0 && maxSampleGapMs <= Long.MAX_VALUE / maxMetersPerSecond)
    }
    fun rebase(previous: DistanceState, sample: OdometerSample): DistanceState {
        validate(previous, sample)
        return previous.copy(lastOdometerMeters = sample.meters, lastElapsedRealtimeMs = sample.elapsedRealtimeMs, bootId = sample.bootId)
    }
    fun accept(previous: DistanceState, sample: OdometerSample): DistanceResult {
        validate(previous, sample)
        val oldMeters = previous.lastOdometerMeters
        val oldTime = previous.lastElapsedRealtimeMs
        if (oldMeters == null || oldTime == null) return DistanceResult(rebase(previous, sample), 0, 0, false)
        val delta = sample.meters - oldMeters
        val elapsed = sample.elapsedRealtimeMs - oldTime
        val invalid = previous.bootId != sample.bootId || delta < 0 || elapsed <= 0 ||
            elapsed > maxSampleGapMs || delta > maxDeltaMeters ||
            (elapsed in 1..maxSampleGapMs && delta > elapsed * maxMetersPerSecond / 1_000)
        if (invalid) return DistanceResult(previous.copy(quarantinedSamples = previous.quarantinedSamples + 1), 0, 0, true)
        val combined = Math.addExact(previous.residualMeters, delta)
        return DistanceResult(previous.copy(lastOdometerMeters = sample.meters,
            lastElapsedRealtimeMs = sample.elapsedRealtimeMs, residualMeters = combined % blockMeters),
            delta, combined / blockMeters, false)
    }
    private fun validate(previous: DistanceState, sample: OdometerSample) {
        require(sample.meters >= 0 && sample.elapsedRealtimeMs >= 0 && sample.bootId.isNotBlank())
        require(previous.residualMeters in 0L until blockMeters)
        require((previous.lastOdometerMeters == null) == (previous.lastElapsedRealtimeMs == null))
    }
}
